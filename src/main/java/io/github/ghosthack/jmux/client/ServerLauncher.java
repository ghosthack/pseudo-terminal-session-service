package io.github.ghosthack.jmux.client;

import io.github.ghosthack.jmux.server.ServerHome;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * How a client starts a server when none is listening. Resolution order:
 * <ol>
 *   <li>{@code -Djmux.launcher=/path} or {@code JMUX_LAUNCHER}
 *       — an executable (the jpackage launcher, or a script) run as
 *       {@code <launcher> serve --daemon --home <home>};</li>
 *   <li>a sibling jpackage launcher named {@code jmux} (or {@code .exe})
 *       next to the current process's executable;</li>
 *   <li>{@code <java.home>/bin/java} ({@code java.exe} on Windows) {@code -cp <absolutized
 *       java.class.path> …Main serve --daemon --home <home>} — works from Harness Manager's
 *       shaded jar (this project's classes are on its runtime classpath) and from this
 *       project's own CLI jar. The classpath is absolutized because the child is started
 *       from the user's home directory, not the parent's cwd.</li>
 * </ol>
 * The child is started from the user's home directory with
 * {@code redirectErrorStream(true).redirectOutput(Redirect.appendTo(<home>/server.log))}
 * and stdin left as the default pipe — never {@code inheritIO}: on Windows the JVM passes
 * {@code CREATE_NO_WINDOW} only when no std handle is inherited, which is what gives the
 * daemon its own hidden console so it survives the parent's console closing or Ctrl+C.
 * ({@code serve --daemon} redirects its own stdio too, belt and braces.)
 */
public final class ServerLauncher {

    public static final String LAUNCHER_PROPERTY = "jmux.launcher";
    public static final String LAUNCHER_ENV = "JMUX_LAUNCHER";
    public static final String MAIN_CLASS = "io.github.ghosthack.jmux.Main";
    public static final String NATIVE_LAUNCHER = "jmux";

    /** The subcommand and flag every resolved command ends with, before {@code --home}. */
    static final List<String> SERVE_ARGS = List.of("serve", "--daemon");

    /** {@code <java.home>/bin/java}, or {@code java.exe} when {@code osName} starts with
     * "Windows". Shared with Harness Manager's {@code StimulatorClient}, whose own lookup
     * lacked the {@code .exe} and therefore never resolved on Windows. */
    public static Path javaBinary(String javaHome, String osName) {
        return Path.of(javaHome, "bin", isWindows(osName) ? "java.exe" : "java");
    }

    public static Path javaBinary() {
        return javaBinary(System.getProperty("java.home"), System.getProperty("os.name", ""));
    }

    /** The argv that would be used, ending in {@code serve --daemon} ({@link #spawn} appends
     * {@code --home <home>}), or empty when nothing resolves. Never spawns. Parameterised
     * for tests. */
    public static Optional<List<String>> resolveCommand(String launcherProperty, String launcherEnv,
                                                        Optional<Path> currentExecutable,
                                                        String javaHome, String classpath, String osName,
                                                        boolean mainClassPresent) {
        Optional<String> explicit = firstNonBlank(launcherProperty, launcherEnv);
        if (explicit.isPresent()) {
            // Configured on purpose: used as given, so a wrong path fails loudly at spawn
            // instead of silently falling through to a different server build.
            return Optional.of(withServeArgs(Path.of(explicit.get()).toAbsolutePath().normalize().toString()));
        }
        Optional<Path> sibling = currentExecutable
                .map(exe -> exe.toAbsolutePath().normalize().getParent())
                .flatMap(directory -> packagedLauncher(directory, osName));
        if (sibling.isPresent()) {
            return Optional.of(withServeArgs(sibling.get().toString()));
        }
        if (mainClassPresent && javaHome != null && !javaHome.isBlank()
                && classpath != null && !classpath.isBlank()) {
            // -jar would honour the manifest's Enable-Native-Access; a -cp launch does not,
            // and pty4j/JNA then print restricted-method warnings into server.log.
            return Optional.of(withServeArgs(javaBinary(javaHome, osName).toString(),
                    "--enable-native-access=ALL-UNNAMED", "-cp", absolutize(classpath), MAIN_CLASS));
        }
        return Optional.empty();
    }

    public static Optional<List<String>> resolveCommand() {
        return resolveCommand(System.getProperty(LAUNCHER_PROPERTY), System.getenv(LAUNCHER_ENV),
                ProcessHandle.current().info().command().map(Path::of),
                System.getProperty("java.home"), System.getProperty("java.class.path", ""),
                System.getProperty("os.name", ""), mainClassPresent());
    }

    /** Spawns the server for {@code home}; returns the process. Throws
     * {@link SessionServerException} when nothing resolves or the spawn fails. */
    public static Process spawn(Path home) {
        List<String> command = new ArrayList<>(resolveCommand().orElseThrow(() -> new SessionServerException(
                "cannot start jmux: no launcher configured (" + LAUNCHER_PROPERTY
                        + " / " + LAUNCHER_ENV + "), no sibling launcher, and " + MAIN_CLASS
                        + " is not on the classpath")));
        command.add("--home");
        command.add(home.toAbsolutePath().normalize().toString());
        try {
            Files.createDirectories(home);
            return new ProcessBuilder(command)
                    .directory(new File(System.getProperty("user.home")))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(ServerHome.log(home).toFile()))
                    .start();
        } catch (IOException e) {
            throw new SessionServerException("cannot start jmux: " + String.join(" ", command), e);
        }
    }

    private static Optional<Path> packagedLauncher(Path directory, String osName) {
        if (directory == null) {
            return Optional.empty();
        }
        Path launcher = directory.resolve(isWindows(osName) ? NATIVE_LAUNCHER + ".exe" : NATIVE_LAUNCHER);
        return Files.isRegularFile(launcher) ? Optional.of(launcher) : Optional.empty();
    }

    private static List<String> withServeArgs(String... prefix) {
        List<String> command = new ArrayList<>(Arrays.asList(prefix));
        command.addAll(SERVE_ARGS);
        return List.copyOf(command);
    }

    private static Optional<String> firstNonBlank(String property, String env) {
        if (property != null && !property.isBlank()) {
            return Optional.of(property);
        }
        if (env != null && !env.isBlank()) {
            return Optional.of(env);
        }
        return Optional.empty();
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.startsWith("Windows");
    }

    private static String absolutize(String classpath) {
        return Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static boolean mainClassPresent() {
        try {
            Class.forName(MAIN_CLASS, false, ServerLauncher.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private ServerLauncher() {
    }
}
