package io.github.ghosthack.jmux.session;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What a session runs and the environment it runs in (SESSION-HOST.md "Session model").
 *
 * <p>Command: an explicit command when the caller gave one; otherwise the platform login
 * shell — Unix {@code $SHELL} (fallback {@code /bin/sh}) with {@code -l}; Windows
 * {@code pwsh.exe} → {@code powershell.exe} (whichever is first on {@code PATH}) →
 * {@code %COMSPEC%}.
 *
 * <p>Environment: the daemon's own, plus {@code TERM=xterm-256color},
 * {@code COLORTERM=truecolor}, {@code LANG} defaulted to {@code en_US.UTF-8} and
 * {@code JMUX_SESSION=<name>}, then the caller's extra variables (which may override
 * {@code COLORTERM}, {@code LANG} or anything inherited, but never {@code TERM} or
 * {@code JMUX_SESSION}). When the shell is {@code cmd.exe} and {@code PROMPT} is
 * unset, {@code PROMPT=$E]9;9;$P$E\$P$G} makes cmd emit an OSC 9;9 cwd report at every
 * prompt while the visible prompt stays {@code C:\path>} — the only way {@code current-path}
 * can follow a {@code cd} in cmd without a process probe.
 *
 * @param command     argv, never empty
 * @param environment the complete environment for the PTY process
 */
public record ShellCommand(List<String> command, Map<String, String> environment) {

    static final String TERM = "xterm-256color";
    static final String DEFAULT_LANG = "en_US.UTF-8";
    static final String CMD_PROMPT = "$E]9;9;$P$E\\$P$G";

    public ShellCommand {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
    }

    /** The command for {@code sessionName} on this machine: {@code explicitCommand}
     * (null/empty = the platform login shell) with the session environment. */
    public static ShellCommand forSession(String sessionName, List<String> explicitCommand) {
        return forSession(sessionName, explicitCommand, Map.of());
    }

    /** As above, with {@code extraEnvironment} (null = none) merged over the defaults. */
    public static ShellCommand forSession(String sessionName, List<String> explicitCommand,
                                          Map<String, String> extraEnvironment) {
        return build(sessionName, explicitCommand, System.getenv(), extraEnvironment,
                System.getProperty("os.name", ""), ShellCommand::isOnPath);
    }

    /** The pure core of {@link #forSession}: every platform input is a parameter so each
     * branch is unit-testable on any OS. {@code executableOnPath} answers "is this file
     * name on PATH" for the Windows shell probe. */
    static ShellCommand build(String sessionName, List<String> explicitCommand, Map<String, String> inherited,
                              String osName, Predicate<String> executableOnPath) {
        return build(sessionName, explicitCommand, inherited, Map.of(), osName, executableOnPath);
    }

    /** {@link #build} with the caller's {@code extra} variables (null = none). */
    static ShellCommand build(String sessionName, List<String> explicitCommand, Map<String, String> inherited,
                              Map<String, String> extra, String osName, Predicate<String> executableOnPath) {
        boolean windows = isWindows(osName);
        List<String> command = explicitCommand != null && !explicitCommand.isEmpty()
                ? explicitCommand
                : windows ? windowsShell(inherited, executableOnPath) : unixShell(inherited);

        Map<String, String> env = new HashMap<>(inherited);
        env.put("TERM", TERM);
        env.put("COLORTERM", "truecolor");
        env.putIfAbsent("LANG", DEFAULT_LANG);
        if (extra != null) {
            env.putAll(extra);
        }
        // Not overridable: the emulator is xterm-256color and the name is the session's.
        env.put("TERM", TERM);
        env.put("JMUX_SESSION", sessionName);
        // Windows environment names are case-insensitive; a user-set "Prompt" must count.
        if (windows && isCmdExe(command.get(0)) && !containsKeyIgnoreCase(env, "PROMPT")) {
            env.put("PROMPT", CMD_PROMPT);
        }
        return new ShellCommand(command, env);
    }

    static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static List<String> unixShell(Map<String, String> inherited) {
        String shell = inherited.get("SHELL");
        if (shell == null || shell.isBlank()) {
            shell = "/bin/sh";
        }
        return List.of(shell, "-l");
    }

    private static List<String> windowsShell(Map<String, String> inherited, Predicate<String> executableOnPath) {
        for (String candidate : List.of("pwsh.exe", "powershell.exe")) {
            if (executableOnPath.test(candidate)) {
                return List.of(candidate);
            }
        }
        String comspec = getIgnoreCase(inherited, "COMSPEC");
        return List.of(comspec == null || comspec.isBlank() ? "cmd.exe" : comspec);
    }

    private static boolean isCmdExe(String executable) {
        // Split on both separators by hand: Path.of would keep a Windows path whole on Unix.
        int cut = Math.max(executable.lastIndexOf('\\'), executable.lastIndexOf('/'));
        String base = executable.substring(cut + 1).toLowerCase(Locale.ROOT);
        return base.equals("cmd.exe") || base.equals("cmd");
    }

    private static boolean containsKeyIgnoreCase(Map<String, String> map, String key) {
        return getIgnoreCase(map, key) != null;
    }

    private static String getIgnoreCase(Map<String, String> map, String key) {
        String exact = map.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Whether a regular file named {@code fileName} exists in one of the PATH directories. */
    static boolean isOnPath(String fileName) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && Files.isRegularFile(Path.of(dir, fileName))) {
                return true;
            }
        }
        return false;
    }
}
