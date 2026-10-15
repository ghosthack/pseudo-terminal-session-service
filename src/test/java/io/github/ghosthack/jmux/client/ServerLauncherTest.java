package io.github.ghosthack.jmux.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every resolution branch of {@link ServerLauncher}, parameterised so nothing spawns. */
class ServerLauncherTest {

    private static final String MAC = "Mac OS X";
    private static final String WINDOWS = "Windows 11";

    @Test
    void javaBinaryGetsTheExeSuffixOnWindowsOnly() {
        assertEquals(Path.of("/jdk", "bin", "java"), ServerLauncher.javaBinary("/jdk", MAC));
        assertEquals(Path.of("/jdk", "bin", "java"), ServerLauncher.javaBinary("/jdk", "Linux"));
        assertEquals(Path.of("C:\\jdk", "bin", "java.exe"), ServerLauncher.javaBinary("C:\\jdk", WINDOWS));
        assertEquals(Path.of("C:\\jdk", "bin", "java.exe"), ServerLauncher.javaBinary("C:\\jdk", "Windows Server 2022"));
    }

    @Test
    void explicitPropertyWinsAndIsUsedAsGiven() {
        Optional<List<String>> command = ServerLauncher.resolveCommand("/opt/hss/bin/launcher", "/ignored/env",
                Optional.of(Path.of("/apps/HarnessManager")), "/jdk", "a.jar", MAC, true);
        // Absolutized with the real Path API, like the source does — a bare "/opt/..." string
        // is already absolute on Unix but is resolved against the current drive on Windows.
        assertEquals(List.of(Path.of("/opt/hss/bin/launcher").toAbsolutePath().normalize().toString(),
                "serve", "--daemon"), command.orElseThrow());
    }

    @Test
    void environmentVariableIsUsedWhenThePropertyIsBlank() {
        Optional<List<String>> command = ServerLauncher.resolveCommand("  ", "relative/launcher.sh",
                Optional.empty(), "/jdk", "a.jar", MAC, true);
        assertEquals(List.of(Path.of("relative/launcher.sh").toAbsolutePath().normalize().toString(), "serve", "--daemon"),
                command.orElseThrow());
    }

    @Test
    void siblingPackagedLauncherIsFound(@TempDir Path dir) throws Exception {
        Path launcher = dir.resolve(ServerLauncher.NATIVE_LAUNCHER);
        Files.writeString(launcher, "#!/bin/sh\n");
        Optional<List<String>> command = ServerLauncher.resolveCommand(null, null,
                Optional.of(dir.resolve("HarnessManager")), "/jdk", "a.jar", MAC, true);
        assertEquals(List.of(launcher.toString(), "serve", "--daemon"), command.orElseThrow());
    }

    @Test
    void siblingLauncherNeedsTheExeSuffixOnWindows(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ServerLauncher.NATIVE_LAUNCHER), "");
        Optional<List<String>> withoutExe = ServerLauncher.resolveCommand(null, null,
                Optional.of(dir.resolve("HarnessManager.exe")), "C:\\jdk", "a.jar", WINDOWS, true);
        assertEquals(Path.of("C:\\jdk", "bin", "java.exe").toString(), withoutExe.orElseThrow().get(0),
                "a suffix-less sibling is not a Windows launcher; falls through to java.exe");

        Path exe = dir.resolve(ServerLauncher.NATIVE_LAUNCHER + ".exe");
        Files.writeString(exe, "");
        Optional<List<String>> withExe = ServerLauncher.resolveCommand(null, null,
                Optional.of(dir.resolve("HarnessManager.exe")), "C:\\jdk", "a.jar", WINDOWS, true);
        assertEquals(List.of(exe.toString(), "serve", "--daemon"), withExe.orElseThrow());
    }

    @Test
    void classpathFallbackAbsolutizesEntriesAndNamesTheMainClass() {
        String classpath = "target/classes" + File.pathSeparator + "/abs/lib.jar";
        Optional<List<String>> command = ServerLauncher.resolveCommand(null, null,
                Optional.of(Path.of("/apps/HarnessManager")), "/jdk", classpath, MAC, true);
        assertEquals(List.of(
                Path.of("/jdk", "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                Path.of("target/classes").toAbsolutePath().normalize() + File.pathSeparator
                        + Path.of("/abs/lib.jar").toAbsolutePath().normalize(),
                ServerLauncher.MAIN_CLASS,
                "serve", "--daemon"), command.orElseThrow());
    }

    @Test
    void classpathFallbackUsesJavaExeOnWindows() {
        Optional<List<String>> command = ServerLauncher.resolveCommand(null, null,
                Optional.empty(), "C:\\jdk", "hss.jar", WINDOWS, true);
        assertEquals(Path.of("C:\\jdk", "bin", "java.exe").toString(), command.orElseThrow().get(0));
        assertEquals("serve", command.get().get(command.get().size() - 2));
    }

    @Test
    void nothingResolvesWithoutMainClassOrClasspath() {
        assertTrue(ServerLauncher.resolveCommand(null, null, Optional.empty(), "/jdk", "a.jar", MAC, false).isEmpty());
        assertTrue(ServerLauncher.resolveCommand(null, null, Optional.empty(), "/jdk", "", MAC, true).isEmpty());
        assertTrue(ServerLauncher.resolveCommand(null, null, Optional.empty(), null, "a.jar", MAC, true).isEmpty());
        assertTrue(ServerLauncher.resolveCommand(null, null, Optional.of(Path.of("/apps/HarnessManager")),
                "/jdk", "", MAC, true).isEmpty());
    }

    @Test
    void realResolutionFindsThisProjectOnTheClasspath() {
        List<String> command = ServerLauncher.resolveCommand().orElseThrow();
        assertTrue(command.contains(ServerLauncher.MAIN_CLASS) || command.size() == 3, String.valueOf(command));
        assertEquals(List.of("serve", "--daemon"), command.subList(command.size() - 2, command.size()));
        assertEquals(ServerLauncher.javaBinary(), Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java"));
    }
}
