package io.github.ghosthack.jmux.client;

import io.github.ghosthack.jmux.server.ServeOptions;
import io.github.ghosthack.jmux.server.SessionManagerFactory;
import io.github.ghosthack.jmux.server.SessionServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spawn step of {@link SessionServerClient#newSession} with the launcher replaced by
 * a stub process, so no real daemon is ever forked: a spawn that exits 0 means a peer won
 * the lock and the poll must keep going until that peer listens; a non-zero exit fails
 * fast with the status. The peer is a real {@link SessionServer} bound in-process.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class SessionServerClientSpawnTest {

    private SessionServer peer;
    private Path home;

    @AfterEach
    void stop() throws IOException {
        if (peer != null) {
            peer.close();
        }
        if (home != null) {
            Files.deleteIfExists(home.resolve("server.sock"));
            Files.deleteIfExists(home.resolve("server.lock"));
            Files.deleteIfExists(home.resolve("server.log"));
            Files.deleteIfExists(home);
        }
    }

    private static Process exited(int status) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", "exit " + status).start();
        p.waitFor();
        return p;
    }

    @Test
    void spawnThatExitsZeroKeepsPollingUntilThePeerListens() throws Exception {
        home = Files.createTempDirectory("hss-spawn");
        ServeOptions options = ServeOptions.defaults(home).withKeepAlive(true);
        Thread starter = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(400); // the "winner" is still starting when the loser exits 0
                peer = new SessionServer(options, SessionManagerFactory.createSessionManager(options));
                peer.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        SessionServerClient client = new SessionServerClient(home, true, ignored -> {
            try {
                return exited(0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        client.newSession("hm-spawn", home, 80, 24, null);
        starter.join();
        assertTrue(client.has("hm-spawn"));
        client.kill("hm-spawn");
    }

    @Test
    void spawnThatExitsNonZeroFailsFastWithItsStatus() throws Exception {
        home = Files.createTempDirectory("hss-spawn");
        SessionServerClient client = new SessionServerClient(home, true, ignored -> {
            try {
                return exited(3);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        long started = System.nanoTime();
        SessionServerException e = assertThrows(SessionServerException.class,
                () -> client.newSession("hm-spawn", home, 80, 24, null));
        assertTrue(e.getMessage().contains("exited with status 3"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "failed fast, not at the deadline");
        assertEquals(false, client.isServerRunning());
    }
}
