package io.github.ghosthack.jmux.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The session's foreground command, as tmux's {@code pane_current_command} reports it
 * (SESSION-HOST.md "Probes").
 *
 * <p>Unix: {@code ps -o pid=,pgid=,tpgid=,comm= -p <shell + descendants>}; the foreground
 * process is the one whose {@code pid} equals the shell row's {@code tpgid} (the leader of
 * the terminal's foreground process group), falling back to the shell itself when no row
 * matches (the leader already exited, or the shell has no controlling terminal).
 *
 * <p>Windows, or when {@code ps} is unavailable: walk from the shell down the chain formed
 * by taking the newest live child at each level, skipping transparent wrappers
 * ({@code cmd}, {@code conhost}) and processes whose name cannot be read, and report the
 * first remaining process; else the shell. Never the leaf: tool children and MCP servers
 * under a harness are newer leaves and would flip the name mid-turn. The walk is written
 * over {@link ProcessNode} so it can be exercised with synthetic trees.
 *
 * <p>Every reported name is the basename, lower-cased, with a trailing {@code .exe} and a
 * login-shell {@code -} prefix removed.
 */
public final class ForegroundProbe {

    /** Foreground pid and normalized command name; {@code command} is "" when unknown. */
    public record Foreground(long pid, String command) {
    }

    /** A process-tree node: {@code ProcessHandle} in production, a synthetic tree in tests. */
    public interface ProcessNode {
        long pid();

        /** The raw executable name or path ("" when unreadable); normalized by the probe. */
        String command();

        boolean isAlive();

        /** Launch time, used to pick the newest child; {@code Instant.EPOCH} when unknown. */
        Instant startTime();

        List<? extends ProcessNode> children();
    }

    /** Processes the fallback walk looks through rather than at. */
    static final Set<String> TRANSPARENT = Set.of("cmd", "conhost");

    /** {@code pid pgid tpgid comm}; comm may contain spaces (a path under /Applications). */
    private static final Pattern PS_ROW = Pattern.compile("^\\s*(\\d+)\\s+(\\d+)\\s+(-?\\d+)\\s+(.*?)\\s*$");

    private static final boolean WINDOWS = ShellCommand.isWindows(System.getProperty("os.name", ""));

    /** The foreground of the session whose shell is {@code shellPid}. Never null; when the
     * shell is gone the command is "" and the pid is the shell's. */
    public static Foreground probe(long shellPid) {
        Optional<ProcessHandle> shell = ProcessHandle.of(shellPid);
        if (shell.isEmpty()) {
            return new Foreground(shellPid, "");
        }
        if (!WINDOWS) {
            Optional<Foreground> viaPs = viaPs(shell.get());
            if (viaPs.isPresent()) {
                return viaPs.get();
            }
        }
        return walk(HandleNode.of(shell.get()));
    }

    private static Optional<Foreground> viaPs(ProcessHandle shell) {
        String pids = Stream.concat(Stream.of(shell), shell.descendants())
                .map(h -> Long.toString(h.pid()))
                .collect(Collectors.joining(","));
        return ProbeCommand.output(List.of("ps", "-o", "pid=,pgid=,tpgid=,comm=", "-p", pids))
                .flatMap(out -> parsePs(out, shell.pid()));
    }

    /** The {@code pid == tpgid} rule over canned or live {@code ps} output; empty when the
     * shell's own row is missing (then the caller falls back to the tree walk). */
    static Optional<Foreground> parsePs(String psOutput, long shellPid) {
        record Row(long pid, long tpgid, String comm) {
        }
        List<Row> rows = new ArrayList<>();
        for (String line : psOutput.split("\\R")) {
            Matcher m = PS_ROW.matcher(line);
            if (m.matches()) {
                rows.add(new Row(Long.parseLong(m.group(1)), Long.parseLong(m.group(3)), m.group(4)));
            }
        }
        Row shell = rows.stream().filter(r -> r.pid() == shellPid).findFirst().orElse(null);
        if (shell == null) {
            return Optional.empty();
        }
        Row foreground = rows.stream().filter(r -> r.pid() == shell.tpgid()).findFirst().orElse(shell);
        return Optional.of(new Foreground(foreground.pid(), normalize(foreground.comm())));
    }

    /** The tree-walk heuristic over any {@link ProcessNode} tree. */
    static Foreground walk(ProcessNode shell) {
        ProcessNode node = shell;
        while (true) {
            ProcessNode child = newestLiveChild(node);
            if (child == null) {
                return new Foreground(shell.pid(), normalize(shell.command()));
            }
            String name = normalize(child.command());
            if (!name.isEmpty() && !TRANSPARENT.contains(name)) {
                return new Foreground(child.pid(), name);
            }
            node = child;
        }
    }

    private static ProcessNode newestLiveChild(ProcessNode node) {
        return node.children().stream()
                .filter(ProcessNode::isAlive)
                .max(Comparator.comparing(ProcessNode::startTime).thenComparingLong(ProcessNode::pid))
                .orElse(null);
    }

    /** Basename, without a login-shell {@code -} prefix, lower-cased, without {@code .exe}. */
    static String normalize(String command) {
        if (command == null) {
            return "";
        }
        String name = command.strip();
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) {
            name = name.substring(cut + 1);
        }
        if (name.startsWith("-")) {
            name = name.substring(1);
        }
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    /** {@link ProcessNode} over a live {@code ProcessHandle}. */
    private record HandleNode(ProcessHandle handle) implements ProcessNode {

        static HandleNode of(ProcessHandle handle) {
            return new HandleNode(handle);
        }

        @Override
        public long pid() {
            return handle.pid();
        }

        @Override
        public String command() {
            ProcessHandle.Info info = handle.info();
            return info.command().orElseGet(() -> {
                // Without permission to read the command, the first token of the command line
                // (when readable) is the next best thing.
                String[] argv = info.arguments().orElse(null);
                return argv == null || argv.length == 0 ? "" : argv[0];
            });
        }

        @Override
        public boolean isAlive() {
            return handle.isAlive();
        }

        @Override
        public Instant startTime() {
            return handle.info().startInstant().orElse(Instant.EPOCH);
        }

        @Override
        public List<HandleNode> children() {
            return handle.children().map(HandleNode::of).toList();
        }
    }

    private ForegroundProbe() {
    }
}
