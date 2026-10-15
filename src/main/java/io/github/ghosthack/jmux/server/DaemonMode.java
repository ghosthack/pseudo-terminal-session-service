package io.github.ghosthack.jmux.server;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What {@code serve --daemon} does beyond plain {@code serve}: its own stdio goes to
 * {@code server.log} (append — the launcher already redirected the process's descriptors
 * there, but a server started by hand from a terminal must not die with that terminal's
 * pipe either) and, on Unix, SIGHUP and SIGINT are ignored so the parent's terminal
 * closing or a Ctrl+C in it cannot take the daemon down with the sessions it hosts.
 *
 * <p>{@code sun.misc.Signal} is reached reflectively: it is a JDK-internal API that is
 * absent or refuses these signals on some platforms (Windows has no SIGHUP), and a daemon
 * that cannot ignore signals should still serve, so any failure is logged and swallowed.
 */
public final class DaemonMode {

    public static void enter(Path log) throws IOException {
        Files.createDirectories(log.toAbsolutePath().getParent());
        PrintStream out = new PrintStream(new FileOutputStream(log.toFile(), true), true, StandardCharsets.UTF_8);
        System.setOut(out);
        System.setErr(out);
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            ignoreSignal("HUP");
            ignoreSignal("INT");
        }
    }

    private static void ignoreSignal(String name) {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            Object signal = signalClass.getConstructor(String.class).newInstance(name);
            Object ignore = handlerClass.getField("SIG_IGN").get(null);
            Method handle = signalClass.getMethod("handle", signalClass, handlerClass);
            handle.invoke(null, signal, ignore);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.err.println("daemon: cannot ignore SIG" + name + ": " + e);
        }
    }

    private DaemonMode() {
    }
}
