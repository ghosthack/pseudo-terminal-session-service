package io.github.ghosthack.jmux.cli;

import io.github.ghosthack.jmux.Main;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.server.ServerHome;
import io.github.ghosthack.jmux.session.CaptureFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argument parsing and the dead-server exit codes, driving {@link Main#run} against a
 * temporary home where no server listens. {@code new} and {@code serve} are deliberately
 * absent from the dead-server cases: the first would spawn a real daemon, the second
 * needs the real session manager.
 */
class CliTest {

    @TempDir
    Path home;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    @AfterEach
    void nothingSpawned() {
        assertFalse(Files.exists(ServerHome.socket(home)), "no server appeared in the temp home");
        assertFalse(Files.exists(ServerHome.log(home)), "no spawn wrote a server.log");
    }

    @Test
    void noArgumentsPrintsUsageAndFails() {
        assertEquals(1, run());
        assertTrue(err().contains("usage:"));
        assertEquals(0, run("--help"));
    }

    @Test
    void unknownCommandAndUnknownOptionAreUsageErrors() {
        assertEquals(1, run("frobnicate"));
        assertTrue(err().contains("unknown command 'frobnicate'"));
        stderr.reset();
        assertEquals(1, run("list", "--bogus"));
        assertTrue(err().contains("unknown option --bogus"));
        stderr.reset();
        assertEquals(1, run("list", "--home"));
        assertTrue(err().contains("--home needs a value"));
    }

    @Test
    void versionPrintsProtocolAndArtifactVersion() {
        assertEquals(0, run("version"));
        assertTrue(out().contains("jmux "), out());
        assertTrue(out().contains("(protocol " + Protocol.VERSION + ")"), out());
        assertFalse(out().contains("server running"));
        stdout.reset();
        assertEquals(0, run("version", "--json"));
        assertTrue(out().contains("\"protocol\":" + Protocol.VERSION), out());
    }

    @Test
    void deadServerListPrintsNothing() {
        assertEquals(0, run("list"));
        assertEquals("", out());
        assertEquals(0, run("list", "--json"));
        assertEquals("[]", out().strip());
    }

    @Test
    void deadServerHasPrintsFalse() {
        assertEquals(0, run("has", "foo"));
        assertEquals("false", out().strip());
    }

    @Test
    void deadServerKillAndShutdownSucceed() {
        assertEquals(0, run("kill", "foo"));
        assertEquals(0, run("shutdown"));
        assertEquals("", out());
        assertEquals("", err());
    }

    @Test
    void deadServerSessionCommandsSayNoSuchSession() {
        for (String[] argv : List.of(
                new String[]{"capture", "foo"},
                new String[]{"capture", "foo", "--json"},
                new String[]{"path", "foo"},
                new String[]{"send-keys", "foo", "echo", "hi"},
                new String[]{"input", "foo", "--text", "hi"},
                new String[]{"input", "foo", "--paste", "--text", "hi", "Enter", "C-c"},
                new String[]{"label", "foo", "a=b"},
                new String[]{"wait", "foo", "--quiet", "100", "--timeout", "200"},
                new String[]{"watch", "foo", "--quiet", "100"},
                new String[]{"capture", "foo", "--format", "annotated"},
                new String[]{"attach", "foo"})) {
            stderr.reset();
            stdout.reset();
            assertEquals(1, run(argv), String.join(" ", argv));
            assertTrue(err().contains("no such session: foo"), String.join(" ", argv) + " -> " + err());
            assertEquals("", out());
        }
    }

    @Test
    void missingPositionalsAreUsageErrorsBeforeAnythingRuns() {
        assertEquals(1, run("new"));
        assertTrue(err().contains("NAME is required"));
        stderr.reset();
        assertEquals(1, run("new", "foo", "--size", "wide"));
        assertTrue(err().contains("--size must be COLUMNSxROWS"));
        stderr.reset();
        assertEquals(1, run("send-keys", "foo"));
        assertTrue(err().contains("TEXT is required"));
        stderr.reset();
        assertEquals(1, run("serve", "--history-lines", "many"));
        assertTrue(err().contains("--history-lines must be an integer"));
    }

    @Test
    void watchOfEverythingOnADeadServerSaysNoServer() {
        assertEquals(1, run("watch"));
        assertTrue(err().contains("no session server is running"), err());
    }

    @Test
    void newCommandsValidateTheirArgumentsBeforeAnythingRuns() {
        assertEquals(1, run("input", "foo"));
        assertTrue(err().contains("--text or a KEY is required"), err());
        stderr.reset();
        assertEquals(1, run("label", "foo", "novalue"));
        assertTrue(err().contains("labels are KEY=VALUE"), err());
        stderr.reset();
        assertEquals(1, run("label", "foo", "=v"));
        assertTrue(err().contains("labels are KEY=VALUE"), err());
        stderr.reset();
        assertEquals(1, run("wait", "foo", "--quiet", "soon"));
        assertTrue(err().contains("--quiet must be an integer"), err());
        stderr.reset();
        assertEquals(1, run("capture", "foo", "--format", "html"));
        assertTrue(err().contains("--format must be text, annotated or ansi"), err());
        stderr.reset();
        assertEquals(1, run("wait"));
        assertTrue(err().contains("NAME is required"), err());
    }

    @Test
    void labelWordsParse() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a", "1");
        expected.put("b", null);
        expected.put("c", "x=y");
        assertEquals(expected, Cli.parseLabels(List.of("a=1", "b=", "c=x=y")));
        assertEquals(Map.of(), Cli.parseLabels(List.of()));
    }

    @Test
    void captureFormatOption() {
        assertEquals(CaptureFormat.TEXT, Cli.captureFormat(Args.parse(List.of(), Set.of("--format"), Set.of())));
        assertEquals(CaptureFormat.ANSI,
                Cli.captureFormat(Args.parse(List.of("--format", "ANSI"), Set.of("--format"), Set.of())));
        assertEquals(CaptureFormat.ANNOTATED,
                Cli.captureFormat(Args.parse(List.of("--format=annotated"), Set.of("--format"), Set.of())));
    }

    @Test
    void homeOptionOverridesTheDefaultEverywhere() {
        assertEquals(0, run("has", "foo", "--home=" + home));
        assertEquals("false", out().strip());
    }

    @Test
    void argsGrammar() {
        Args args = Args.parse(List.of("name", "--json", "--dir=/tmp/x", "--size", "120x40", "more"),
                Set.of("--dir", "--size"), Set.of("--json"));
        assertEquals(List.of("name", "more"), args.positionals());
        assertTrue(args.flag("--json"));
        assertEquals("/tmp/x", args.value("--dir").orElseThrow());
        assertArrayEquals(new int[]{120, 40}, Args.size(args.value("--size").orElseThrow()));
        assertThrows(Args.UsageException.class, () -> Args.size("0x10"));
        assertThrows(Args.UsageException.class, () -> Args.size("10"));
        assertThrows(Args.UsageException.class, () ->
                Args.parse(List.of("--json=yes"), Set.of(), Set.of("--json")));
    }

    private int run(String... argv) {
        List<String> full = new ArrayList<>(List.of(argv));
        if (!full.contains("--home") && full.stream().noneMatch(a -> a.startsWith("--home="))) {
            full.add("--home");
            full.add(home.toString());
        }
        return Main.run(full.toArray(String[]::new), new ByteArrayInputStream(new byte[0]),
                new PrintStream(stdout, true), new PrintStream(stderr, true));
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }
}
