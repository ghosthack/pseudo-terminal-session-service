package io.github.ghosthack.jmux.session;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SESSION-HOST.md "Tests (server)" → {@code ForegroundProbeTest}: the synthetic trees for
 * the tree-walk heuristic, the {@code ps} rule over canned output, and a live
 * {@code sh -c 'sleep 5'} under a real PTY (the {@code pid == tpgid} rule needs a
 * controlling terminal — without one {@code tpgid} is 0 — so the live process gets one
 * from pty4j; bash execs the single command, so the tty's foreground group leader is
 * {@code sleep} itself).
 */
class ForegroundProbeTest {

    /** A synthetic process node; children are added by name and get increasing pids/start times. */
    private static final class Node implements ForegroundProbe.ProcessNode {
        private static long nextPid = 1000;
        private final long pid = nextPid++;
        private final String command;
        private final Instant started = Instant.ofEpochSecond(pid);
        private final List<Node> children = new ArrayList<>();
        boolean alive = true;

        Node(String command) {
            this.command = command;
        }

        Node child(String command) {
            Node c = new Node(command);
            children.add(c);
            return c;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public String command() {
            return command;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public Instant startTime() {
            return started;
        }

        @Override
        public List<Node> children() {
            return children;
        }
    }

    @Test
    void shellAloneReportsTheShell() {
        Node shell = new Node("C:\\Program Files\\PowerShell\\7\\pwsh.exe");
        ForegroundProbe.Foreground fg = ForegroundProbe.walk(shell);
        assertEquals("pwsh", fg.command());
        assertEquals(shell.pid(), fg.pid());
    }

    @Test
    void shellToNodeReportsNode() {
        Node shell = new Node("pwsh.exe");
        Node node = shell.child("C:\\Program Files\\nodejs\\node.exe");
        ForegroundProbe.Foreground fg = ForegroundProbe.walk(shell);
        assertEquals("node", fg.command());
        assertEquals(node.pid(), fg.pid());
    }

    @Test
    void cmdWrapperIsSkipped() {
        Node shell = new Node("pwsh.exe");
        Node node = shell.child("cmd.exe").child("node.exe");
        assertEquals(new ForegroundProbe.Foreground(node.pid(), "node"), ForegroundProbe.walk(shell));
    }

    @Test
    void harnessIsReportedNotItsVersionChild() {
        Node shell = new Node("/bin/zsh");
        Node claude = shell.child("/Users/x/.local/bin/claude");
        claude.child("2.1.235");
        assertEquals(new ForegroundProbe.Foreground(claude.pid(), "claude"), ForegroundProbe.walk(shell));
    }

    @Test
    void harnessIsReportedNotItsToolOrMcpChildren() {
        Node shell = new Node("-zsh");
        Node claude = shell.child("claude");
        claude.child("Python");
        claude.child("/usr/bin/git");
        assertEquals(new ForegroundProbe.Foreground(claude.pid(), "claude"), ForegroundProbe.walk(shell));
    }

    @Test
    void newestLiveChildWins() {
        Node shell = new Node("bash");
        Node older = shell.child("vim");
        Node newer = shell.child("less");
        assertEquals("less", ForegroundProbe.walk(shell).command());
        newer.alive = false;
        assertEquals("vim", ForegroundProbe.walk(shell).command());
        older.alive = false;
        assertEquals("bash", ForegroundProbe.walk(shell).command());
    }

    @Test
    void wrapperWithNoChildrenFallsBackToTheShell() {
        Node shell = new Node("cmd.exe");
        shell.child("conhost.exe");
        assertEquals(new ForegroundProbe.Foreground(shell.pid(), "cmd"), ForegroundProbe.walk(shell));
    }

    @Test
    void normalizesNames() {
        assertEquals("zsh", ForegroundProbe.normalize("-zsh"));
        assertEquals("node", ForegroundProbe.normalize("C:\\nodejs\\Node.EXE"));
        assertEquals("python", ForegroundProbe.normalize("/opt/homebrew/bin/Python"));
        assertEquals("", ForegroundProbe.normalize(null));
        assertEquals("", ForegroundProbe.normalize("  "));
    }

    @Test
    void psRuleFindsTheForegroundGroupLeader() {
        String ps = """
                  501   501   777 -zsh
                  777   777   777 /usr/local/bin/node
                  778   777   777 /usr/bin/git
                """;
        assertEquals(Optional.of(new ForegroundProbe.Foreground(777, "node")), ForegroundProbe.parsePs(ps, 501));
    }

    @Test
    void psRuleFallsBackToTheShellWhenTheLeaderIsGoneOrNoTty() {
        String leaderGone = """
                  501   501   900 /bin/bash
                  902   900   900 /usr/bin/sort
                """;
        assertEquals(Optional.of(new ForegroundProbe.Foreground(501, "bash")), ForegroundProbe.parsePs(leaderGone, 501));

        String noTty = "  501   501     0 /bin/bash\n  502   501     0 /usr/bin/sleep\n";
        assertEquals(Optional.of(new ForegroundProbe.Foreground(501, "bash")), ForegroundProbe.parsePs(noTty, 501));

        String idle = "  501   501   501 -bash\n";
        assertEquals(Optional.of(new ForegroundProbe.Foreground(501, "bash")), ForegroundProbe.parsePs(idle, 501));
    }

    @Test
    void psRuleKeepsSpacesInCommandPaths() {
        String ps = "  501   501   600 -zsh\n  600   600   600 /Applications/Foo Bar.app/Contents/MacOS/Foo Bar\n";
        assertEquals("foo bar", ForegroundProbe.parsePs(ps, 501).orElseThrow().command());
    }

    @Test
    void psRuleIsEmptyWithoutTheShellRow() {
        assertEquals(Optional.empty(), ForegroundProbe.parsePs("  9   9   9 sh\n", 501));
        assertEquals(Optional.empty(), ForegroundProbe.parsePs("", 501));
        assertEquals(Optional.empty(), ForegroundProbe.parsePs("ps: 501: no such process\n", 501));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void liveSleepUnderAPtyIsTheForeground() throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        PtyProcess process = new PtyProcessBuilder(new String[]{"/bin/sh", "-c", "exec sleep 5"})
                .setEnvironment(env)
                .setDirectory(System.getProperty("java.io.tmpdir"))
                .setInitialColumns(80)
                .setInitialRows(24)
                .start();
        try {
            Await.until("sleep to be the foreground of pid " + process.pid(),
                    () -> ForegroundProbe.probe(process.pid()).command().equals("sleep"));
            assertTrue(process.isAlive());
        } finally {
            process.destroyForcibly();
        }
    }
}
