package io.github.ghosthack.jmux.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionManager} contract over the real manager: name uniqueness, spawn failures,
 * and the idle timer (armed at boot and after the last session ends, cancelled by create).
 * Sessions run a plain {@code /bin/sh} (no login files) so the tests stay hermetic.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class SessionManagerImplTest {

    private static final Duration IDLE = Duration.ofMillis(200);
    private static final List<String> SH = List.of("/bin/sh");

    private SessionManagerImpl manager;
    private final AtomicInteger idleFired = new AtomicInteger();

    private SessionManagerImpl newManager() {
        manager = new SessionManagerImpl(100, IDLE);
        manager.setListener(idleFired::incrementAndGet);
        return manager;
    }

    @AfterEach
    void killEverything() {
        if (manager != null) {
            manager.killAll();
        }
    }

    @Test
    void namesAreUnique(@TempDir Path dir) {
        SessionManagerImpl m = newManager();
        Session first = m.create("dup", dir, 80, 24, SH);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> m.create("dup", dir, 80, 24, SH));
        assertTrue(e.getMessage().contains("dup"));
        assertEquals(List.of(first), m.sessions(), "the failed create left no second session");
        assertEquals(first, m.find("dup").orElseThrow());
    }

    @Test
    void labelsAtCreationAndCreatedListeners(@TempDir Path dir) {
        SessionManagerImpl m = newManager();
        List<Session> created = new CopyOnWriteArrayList<>();
        Consumer<Session> listener = created::add;
        m.addCreatedListener(listener);
        Session labelled = m.create("labelled", dir, 80, 24, SH, Map.of("harness", "codex"));
        assertEquals(Map.of("harness", "codex"), labelled.labels());
        assertEquals(Map.of("harness", "codex"), labelled.info().labels());
        assertEquals(List.of(labelled), created, "told after the session is listed");
        assertTrue(m.find("labelled").isPresent());
        assertThrows(IllegalStateException.class, () -> m.create("labelled", dir, 80, 24, SH));
        assertEquals(1, created.size(), "a failed create tells nobody");
        m.removeCreatedListener(listener);
        Session plain = m.create("plain", dir, 80, 24, SH);
        assertEquals(Map.of(), plain.labels());
        assertEquals(1, created.size());
    }

    @Test
    void missingWorkdirIsASpawnFailure(@TempDir Path dir) {
        SessionManagerImpl m = newManager();
        assertThrows(UncheckedIOException.class, () -> m.create("nodir", dir.resolve("absent"), 80, 24, SH));
        assertTrue(m.find("nodir").isEmpty());
        assertTrue(m.sessions().isEmpty());
    }

    @Test
    void unspawnableCommandIsASpawnFailureAndLeavesTheTimerArmed(@TempDir Path dir) {
        SessionManagerImpl m = newManager();
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> m.create("nobin", dir, 80, 24, List.of("/nonexistent/binary")));
        assertTrue(e.getMessage().contains("/nonexistent/binary"), e.getMessage());
        assertTrue(m.sessions().isEmpty());
        // A new that failed after the spawn must still let the server exit.
        Await.until("the boot idle timer despite the failed create", () -> idleFired.get() == 1);
    }

    @Test
    void idleTimerArmsAtBoot() {
        newManager();
        Await.until("the boot idle timer", () -> idleFired.get() == 1);
    }

    @Test
    void listenerSetAfterTheBootTimerFiredStillHears() throws InterruptedException {
        manager = new SessionManagerImpl(100, IDLE);
        Thread.sleep(IDLE.toMillis() * 3);
        manager.setListener(idleFired::incrementAndGet);
        Await.until("the re-armed idle timer", () -> idleFired.get() == 1);
    }

    @Test
    void createCancelsTheTimerAndKillRearmsIt(@TempDir Path dir) {
        SessionManagerImpl m = newManager();
        m.create("one", dir, 80, 24, SH);
        assertTrue(Await.holdsFor(IDLE.multipliedBy(3), () -> idleFired.get() == 0),
                "no idle notification while a session lives");

        m.create("two", dir, 80, 24, SH);
        m.kill("one");
        assertEquals(1, m.sessions().size());
        assertTrue(Await.holdsFor(IDLE.multipliedBy(3), () -> idleFired.get() == 0),
                "killing one of two sessions does not arm the timer");

        m.kill("two");
        assertTrue(m.sessions().isEmpty());
        Await.until("the idle timer after the last kill", () -> idleFired.get() == 1);
        assertTrue(Await.holdsFor(IDLE.multipliedBy(3), () -> idleFired.get() == 1), "it fires once");
    }

    @Test
    void killOfAnAbsentSessionIsANoOp() {
        SessionManagerImpl m = newManager();
        m.kill("never-there");
        m.kill(null);
        assertTrue(m.sessions().isEmpty());
    }

    @Test
    void aSessionThatExitsOnItsOwnLeavesTheTableAndArmsTheTimer(@TempDir Path dir) {
        SessionManagerImpl m = newManager();
        Session session = m.create("short", dir, 80, 24, List.of("/bin/sh", "-c", "exit 3"));
        Await.until("the session to exit", () -> !session.isAlive() && m.find("short").isEmpty());
        Await.until("the idle timer after the exit", () -> idleFired.get() == 1);
    }

    @Test
    void killAllEmptiesTheTable(@TempDir Path dir) {
        SessionManagerImpl m = newManager();
        Session a = m.create("a", dir, 80, 24, SH);
        Session b = m.create("b", dir, 80, 24, SH);
        assertEquals(List.of(a, b), m.sessions(), "creation order");
        m.killAll();
        assertTrue(m.sessions().isEmpty());
        assertFalse(a.isAlive());
        assertFalse(b.isAlive());
    }

    @Test
    void theShellSeesTheEnvironmentGivenAtCreation(@TempDir Path dir) {
        SessionManagerImpl m = newManager();
        Session session = m.create("env", dir, 80, 24, SH, Map.of(),
                Map.of("JMUX_TEST_VAR", "from-create", "JMUX_SESSION", "spoofed"));
        // The variable's value only appears once expanded, never in the echoed keystrokes.
        session.sendKeys("echo \"[$JMUX_TEST_VAR|$JMUX_SESSION]\"", true);
        Await.until("the expanded variables", () -> session.capture().text().contains("[from-create|env]"),
                () -> session.capture().text());
    }
}
