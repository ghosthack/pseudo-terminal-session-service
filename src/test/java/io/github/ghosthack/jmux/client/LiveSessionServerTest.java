package io.github.ghosthack.jmux.client;

import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.server.ServeOptions;
import io.github.ghosthack.jmux.server.SessionManagerFactory;
import io.github.ghosthack.jmux.server.SessionServer;
import io.github.ghosthack.jmux.session.CaptureFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The client against an in-process {@link SessionServer} with real PTY sessions
 * ({@code /bin/sh}) on a temp home: the {@code env} acknowledgement on {@code new} and
 * {@code input}'s {@code requireBracketedPaste}, end to end.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class LiveSessionServerTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private Path home;
    private SessionServer server;
    private SessionServerClient client;

    @BeforeEach
    void start() throws IOException {
        home = Files.createTempDirectory("hss-live");
        ServeOptions options = ServeOptions.defaults(home);
        server = new SessionServer(options, SessionManagerFactory.createSessionManager(options));
        server.start();
        client = new SessionServerClient(home, false);
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        try (Stream<Path> tree = Files.walk(home)) {
            tree.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void newAcknowledgesTheEnvironmentItApplied() {
        SessionServerClient.NewSessionResult withEnv = client.newSession("env", home, 80, 24,
                List.of("/bin/sh"), null, Map.of("HM_LIVE_TOKEN", "t0k"));
        assertTrue(withEnv.envApplied());
        assertTrue(withEnv.pid() > 0);
        assertFalse(withEnv.backend().isEmpty());
        client.sendKeys("env", "echo \"[$HM_LIVE_TOKEN]\"", true);
        await("the environment to echo back", () -> screen("env").contains("[t0k]"));

        SessionServerClient.NewSessionResult without = client.newSession("plain", home, 80, 24,
                List.of("/bin/sh"), null, null);
        assertTrue(without.envApplied(), "nothing requested, nothing missing");
    }

    @Test
    void requireBracketedPasteRefusesAPlainShellAndAcceptsOnceTheProgramEnablesIt() {
        client.newSession("p", home, 80, 24, List.of("/bin/sh"), null, null);
        client.sendKeys("p", "cat -v", true);
        await("cat to run", () -> foreground("p").equals("cat"));
        SessionServerException refused = assertThrows(SessionServerException.class,
                () -> client.input("p", "re''fused", true, true, List.of("Enter")));
        assertEquals(Protocol.ERROR_BRACKETED_PASTE_DISABLED, refused.getMessage());
        client.input("p", "typed''marker", false, List.of("Enter"));
        await("the marker line", () -> screen("p").contains("typed''marker"));
        assertFalse(screen("p").contains("re''fused"), "the refused paste wrote nothing, keys included");
        client.input("p", null, false, List.of("C-c"));
        await("cat to end", () -> !foreground("p").equals("cat"));

        client.sendKeys("p", "printf '\\033[?2004h'; cat -v", true);
        await("cat to run again", () -> foreground("p").equals("cat"));
        await("bracketed paste mode", () -> client.capture("p", CaptureFormat.ANSI).orElseThrow().text()
                .contains("[?2004h"));
        client.input("p", "ac''cepted", true, true, List.of("Enter"));
        await("the bracketed paste", () -> screen("p").contains("^[[200~ac''cepted^[[201~"));
        client.input("p", null, false, List.of("C-c"));
    }

    private String screen(String name) {
        return client.capture(name).orElseThrow().text();
    }

    private String foreground(String name) {
        return client.capture(name).orElseThrow().foregroundCommand();
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what);
            }
        }
    }
}
