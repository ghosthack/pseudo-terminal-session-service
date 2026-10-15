package io.github.ghosthack.jmux.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SESSION-HOST.md "Session model": the login shell, the environment and the cmd.exe prompt. */
class ShellCommandTest {

    private static final Map<String, String> UNIX_ENV = Map.of("SHELL", "/bin/zsh", "PATH", "/usr/bin", "HOME", "/Users/x");

    @Test
    void unixUsesShellAsLoginShell() {
        ShellCommand cmd = ShellCommand.build("hm-foo", null, UNIX_ENV, "Mac OS X", exe -> false);
        assertEquals(List.of("/bin/zsh", "-l"), cmd.command());
    }

    @Test
    void unixFallsBackToBinSh() {
        ShellCommand cmd = ShellCommand.build("hm-foo", List.of(), Map.of("SHELL", " "), "Linux", exe -> false);
        assertEquals(List.of("/bin/sh", "-l"), cmd.command());
        ShellCommand noShell = ShellCommand.build("hm-foo", null, Map.of(), "Linux", exe -> false);
        assertEquals(List.of("/bin/sh", "-l"), noShell.command());
    }

    @Test
    void environmentInheritsAndAddsTheSessionVariables() {
        ShellCommand cmd = ShellCommand.build("hm-foo", null, UNIX_ENV, "Mac OS X", exe -> false);
        Map<String, String> env = cmd.environment();
        assertEquals("/Users/x", env.get("HOME"));
        assertEquals("/usr/bin", env.get("PATH"));
        assertEquals("xterm-256color", env.get("TERM"));
        assertEquals("truecolor", env.get("COLORTERM"));
        assertEquals("en_US.UTF-8", env.get("LANG"));
        assertEquals("hm-foo", env.get("JMUX_SESSION"));
        assertFalse(env.containsKey("PROMPT"), "PROMPT is a cmd.exe-only rule");
    }

    @Test
    void langIsOnlyDefaulted() {
        Map<String, String> inherited = Map.of("LANG", "de_DE.UTF-8", "TERM", "dumb", "COLORTERM", "no");
        Map<String, String> env = ShellCommand.build("s", null, inherited, "Linux", exe -> false).environment();
        assertEquals("de_DE.UTF-8", env.get("LANG"));
        assertEquals("xterm-256color", env.get("TERM"), "TERM is always ours");
        assertEquals("truecolor", env.get("COLORTERM"), "COLORTERM is always ours");
    }

    @Test
    void explicitCommandOverridesTheShell() {
        ShellCommand cmd = ShellCommand.build("s", List.of("/usr/bin/top", "-l", "1"), UNIX_ENV, "Mac OS X", exe -> false);
        assertEquals(List.of("/usr/bin/top", "-l", "1"), cmd.command());
        assertEquals("s", cmd.environment().get("JMUX_SESSION"));
    }

    @Test
    void windowsPrefersPwshThenPowershellThenComspec() {
        Map<String, String> env = Map.of("COMSPEC", "C:\\Windows\\system32\\cmd.exe");
        Set<String> both = Set.of("pwsh.exe", "powershell.exe");
        assertEquals(List.of("pwsh.exe"), ShellCommand.build("s", null, env, "Windows 11", both::contains).command());
        assertEquals(List.of("powershell.exe"),
                ShellCommand.build("s", null, env, "Windows 11", "powershell.exe"::equals).command());
        assertEquals(List.of("C:\\Windows\\system32\\cmd.exe"),
                ShellCommand.build("s", null, env, "Windows 11", exe -> false).command());
        assertEquals(List.of("cmd.exe"), ShellCommand.build("s", null, Map.of(), "Windows 10", exe -> false).command(),
                "no COMSPEC at all: cmd.exe by name");
    }

    @Test
    void cmdExeGetsTheCwdReportingPromptOnlyWhenUnset() {
        Map<String, String> env = Map.of("COMSPEC", "C:\\Windows\\system32\\cmd.exe");
        ShellCommand cmd = ShellCommand.build("s", null, env, "Windows 11", exe -> false);
        assertEquals("$E]9;9;$P$E\\$P$G", cmd.environment().get("PROMPT"));

        Map<String, String> withPrompt = Map.of("COMSPEC", "C:\\Windows\\system32\\cmd.exe", "Prompt", "$P$G");
        ShellCommand kept = ShellCommand.build("s", null, withPrompt, "Windows 11", exe -> false);
        assertEquals("$P$G", kept.environment().get("Prompt"));
        assertFalse(kept.environment().containsKey("PROMPT"), "a user prompt (any case) is left alone");

        ShellCommand pwsh = ShellCommand.build("s", null, env, "Windows 11", "pwsh.exe"::equals);
        assertFalse(pwsh.environment().containsKey("PROMPT"), "PowerShell has its own prompt");

        ShellCommand explicitCmd = ShellCommand.build("s", List.of("cmd.exe", "/k", "echo hi"), Map.of(), "Windows 11", exe -> false);
        assertEquals("$E]9;9;$P$E\\$P$G", explicitCmd.environment().get("PROMPT"), "an explicit cmd.exe counts too");
    }

    @Test
    void windowsDetectionIsByPrefix() {
        assertEquals(true, ShellCommand.isWindows("Windows Server 2022"));
        assertEquals(false, ShellCommand.isWindows("Mac OS X"));
        assertEquals(false, ShellCommand.isWindows(null));
    }

    @Test
    void rejectsAnEmptyCommand() {
        assertThrows(IllegalArgumentException.class, () -> new ShellCommand(List.of(), Map.of()));
    }

    @Test
    void extraEnvironmentIsMergedAfterTheDefaultsButNeverOverridesTermOrTheSessionName() {
        Map<String, String> extra = Map.of("HM_MCP_TOKEN", "t0k", "LANG", "fr_FR.UTF-8", "COLORTERM", "24bit",
                "PATH", "/opt/bin", "TERM", "dumb", "JMUX_SESSION", "spoofed");
        Map<String, String> env = ShellCommand.build("hm-foo", null, UNIX_ENV, extra, "Mac OS X", exe -> false)
                .environment();
        assertEquals("t0k", env.get("HM_MCP_TOKEN"));
        assertEquals("fr_FR.UTF-8", env.get("LANG"));
        assertEquals("24bit", env.get("COLORTERM"));
        assertEquals("/opt/bin", env.get("PATH"), "inherited variables can be replaced");
        assertEquals("/Users/x", env.get("HOME"));
        assertEquals("xterm-256color", env.get("TERM"));
        assertEquals("hm-foo", env.get("JMUX_SESSION"));
        assertEquals(ShellCommand.build("hm-foo", null, UNIX_ENV, "Mac OS X", exe -> false).environment(),
                ShellCommand.build("hm-foo", null, UNIX_ENV, null, "Mac OS X", exe -> false).environment(),
                "null extra = none");
    }
}
