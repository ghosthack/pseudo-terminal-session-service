package io.github.ghosthack.jmux.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The current working directory of a process, by platform (SESSION-HOST.md "Probes"):
 * Windows {@code com.pty4j.windows.WinHelper.getCurrentDirectory(pid)} (pty4j's bundled
 * {@code win-helper.dll}, which reads the PEB of any pid); Linux {@code /proc/<pid>/cwd};
 * macOS {@code lsof -a -p <pid> -d cwd -Fn} (~35 ms — only ever called from user actions,
 * never from the poller). An empty result means "unknown", and the session falls back to
 * the shell's pid and then to its launch directory.
 *
 * <p>{@code WinHelper} is reached through reflection so that the class — and the JNA
 * Windows bindings its static initializer pulls in — is never touched on macOS or Linux.
 */
public final class CwdProbe {

    private static final Logger log = LoggerFactory.getLogger(CwdProbe.class);
    private static final boolean WINDOWS = ShellCommand.isWindows(System.getProperty("os.name", ""));
    private static final String WIN_HELPER = "com.pty4j.windows.WinHelper";

    public static Optional<Path> cwdOf(long pid) {
        if (WINDOWS) {
            return viaWinHelper(pid);
        }
        Optional<Path> proc = viaProc(pid);
        return proc.isPresent() ? proc : viaLsof(pid);
    }

    /** Linux: the {@code /proc/<pid>/cwd} symlink; empty where there is no procfs or the
     * link is unreadable (another user's process). */
    static Optional<Path> viaProc(long pid) {
        Path link = Path.of("/proc", Long.toString(pid), "cwd");
        if (!Files.isSymbolicLink(link)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readSymbolicLink(link));
        } catch (IOException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    /** macOS (and any Unix with lsof): one field-mode query for the cwd descriptor.
     * {@code -b} keeps lsof itself off the blocking {@code stat}/{@code readlink} calls it
     * would otherwise make on every mount point (a dead network mount hangs them); the
     * {@link ProbeCommand} deadline is the real guard. */
    static Optional<Path> viaLsof(long pid) {
        return ProbeCommand.output(List.of("lsof", "-b", "-a", "-p", Long.toString(pid), "-d", "cwd", "-Fn"))
                .flatMap(CwdProbe::parseLsof);
    }

    /** {@code -Fn} output is one field per line: {@code p<pid>}, {@code fcwd}, {@code n<path>}. */
    static Optional<Path> parseLsof(String output) {
        boolean inCwd = false;
        for (String line : output.split("\\R")) {
            if (line.startsWith("f")) {
                inCwd = line.equals("fcwd");
            } else if (inCwd && line.startsWith("n") && line.length() > 1) {
                return toPath(line.substring(1));
            }
        }
        return Optional.empty();
    }

    static Optional<Path> viaWinHelper(long pid) {
        try {
            Method method = Class.forName(WIN_HELPER).getMethod("getCurrentDirectory", long.class);
            Object result = method.invoke(null, pid);
            return result instanceof String s ? toPath(s) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            log.debug("WinHelper.getCurrentDirectory({}) failed: {}", pid, cause.toString());
            return Optional.empty();
        }
    }

    private static Optional<Path> toPath(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(trimmed));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private CwdProbe() {
    }
}
