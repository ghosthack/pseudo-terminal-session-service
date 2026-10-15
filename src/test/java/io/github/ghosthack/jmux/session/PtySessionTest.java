package io.github.ghosthack.jmux.session;

import io.github.ghosthack.jmux.vt.HeadlessTerminal;
import io.github.ghosthack.jmux.vt.VtToken;
import io.github.ghosthack.jmux.vt.VtTokenizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live {@link PtySession} over {@code /bin/sh -l} (SESSION-HOST.md "Session model"):
 * send-keys → capture, the redraw-on-attach handshake, fan-out of live output, the in-band
 * size announcement, {@code current-path}, and the kill sequence (shell and descendants).
 *
 * <p>Commands are typed as {@code echo hel''lo} so the shell's echo of the keystrokes does
 * not contain the word the assertions look for; only the command's output does.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class PtySessionTest {

    private static final int HISTORY = 200;
    /** The string terminator a BEHIND re-sync snapshot opens with. */
    private static final byte[] ST = {0x1B, '\\'};

    @TempDir
    Path workdir;

    private PtySession session;
    private final RecordingClient clientA = new RecordingClient();
    private final CountDownLatch ended = new CountDownLatch(1);
    private final AtomicReference<Integer> endedWith = new AtomicReference<>();

    @BeforeEach
    void spawn() throws IOException {
        ShellCommand shell = ShellCommand.build("t", List.of("/bin/sh", "-l"), System.getenv(),
                System.getProperty("os.name"), ShellCommand::isOnPath);
        session = PtySession.spawn("t", workdir, 80, 24, shell, HISTORY, (s, code) -> {
            endedWith.set(code);
            ended.countDown();
        });
        session.attach(clientA, 80, 24);
    }

    @AfterEach
    void kill() {
        if (session != null) {
            session.kill();
        }
    }

    @Test
    void endToEnd() throws Exception {
        // --- new + send-keys → capture
        assertTrue(session.isAlive());
        Session.Info info = session.info();
        assertEquals("t", info.name());
        assertEquals("unix", info.backend());
        assertEquals(workdir, info.workdir());
        assertEquals(1, info.clients());
        assertTrue(info.pid() > 0);

        session.sendKeys("echo hel''lo", true);
        Await.until("capture to contain hello", () -> session.capture().text().contains("hello"),
                () -> session.capture().text());
        Session.Capture capture = session.capture();
        assertTrue(capture.alive());
        assertEquals(session.info().pid(), capture.processId());
        assertEquals("sh", capture.foregroundCommand(), "idle at the prompt: the shell is the foreground");
        assertEquals(80, capture.columns());
        assertEquals(24, capture.rows());
        assertTrue(clientA.text().contains("hello"), "the attached client saw the live output");

        // --- attach a second client: its first bytes are a snapshot that redraws "hello"
        RecordingClient clientB = new RecordingClient();
        session.attach(clientB, 80, 24);
        List<byte[]> firstChunks = clientB.chunks();
        assertFalse(firstChunks.isEmpty(), "attach hands the snapshot before returning");
        byte[] snapshot = firstChunks.get(0);
        assertTrue(new String(snapshot, StandardCharsets.UTF_8).startsWith("[?1049l"), "redraw reset prefix");
        HeadlessTerminal replica = new HeadlessTerminal(80, 24, HISTORY, bytes -> { });
        replica.apply(tokensOf(snapshot));
        assertTrue(replica.screenText().contains("hello"), "replica screen:\n" + replica.screenText());
        assertEquals(session.capture().text(), replica.screenText(), "the replica matches capture exactly");
        assertEquals(2, session.clients().size());

        // --- live output fans out to both clients
        int aBefore = clientA.byteCount();
        int bBefore = clientB.byteCount();
        session.write("echo b''ye\r".getBytes(StandardCharsets.UTF_8));
        Await.until("client A to receive bye", () -> clientA.textAfter(aBefore).contains("bye"));
        Await.until("client B to receive bye", () -> clientB.textAfter(bBefore).contains("bye"));

        // --- resize from B: A gets the in-band announcement, B does not; capture follows
        int aBeforeResize = clientA.byteCount();
        int bBeforeResize = clientB.byteCount();
        session.resize(100, 30, clientB);
        Await.until("client A to receive the size announcement",
                () -> clientA.textAfter(aBeforeResize).contains("[8;30;100t"));
        assertFalse(clientB.textAfter(bBeforeResize).contains("[8;30;100t"), "the resizer is not told");
        assertEquals(100, session.capture().columns());
        assertEquals(30, session.capture().rows());
        assertEquals(100, session.info().columns());
        assertEquals(30, session.info().rows());
        // An attach at a different size resizes again and announces to everyone else.
        RecordingClient clientC = new RecordingClient();
        int aBeforeAttach = clientA.byteCount();
        session.attach(clientC, 90, 25);
        assertTrue(clientA.textAfter(aBeforeAttach).contains("[8;25;90t"));
        assertEquals(90, session.info().columns());
        session.detach(clientC);
        assertEquals(2, session.clients().size());

        // --- current-path: no OSC hint from a stock sh, so the lsof/proc probe of the shell
        assertEquals(workdir.toRealPath(), session.currentPath().toRealPath());
        session.sendKeys("mkdir -p sub && cd sub", true);
        Await.until("current-path to follow cd", () -> {
            try {
                return session.currentPath().toRealPath().equals(workdir.resolve("sub").toRealPath());
            } catch (IOException e) {
                return false;
            }
        });

        // --- kill: a foreground sleep (a descendant of the shell) goes with the shell
        session.sendKeys("sleep 100", true);
        Await.until("sleep to become the foreground", () -> session.capture().foregroundCommand().equals("sleep"));
        List<ProcessHandle> descendants = ProcessHandle.of(session.info().pid()).orElseThrow().descendants().toList();
        assertFalse(descendants.isEmpty(), "sleep is a descendant of the shell");
        session.kill();
        assertFalse(session.isAlive());
        assertTrue(ended.await(10, TimeUnit.SECONDS), "the exit listener ran");
        assertNotNull(endedWith.get());
        Await.until("onExit on client A", () -> clientA.hasExited());
        Await.until("onExit on client B", () -> clientB.hasExited());
        assertEquals(endedWith.get(), clientA.exitCode());
        assertFalse(session.info().alive());
        assertEquals(0, session.clients().size(), "clients are dropped after the exit");
        Await.until("descendants to be gone", Duration.ofSeconds(5),
                () -> descendants.stream().noneMatch(ProcessHandle::isAlive));

        // A late attach still gets the last screen and then the end.
        RecordingClient late = new RecordingClient();
        session.attach(late, 80, 24);
        assertTrue(late.hasExited());
        assertEquals(endedWith.get(), late.exitCode());
        assertFalse(late.chunks().isEmpty());
    }

    @Test
    void emulatorRepliesGoToThePty() {
        // printf the DA1 query; the daemon answers CSI ?6c (stripped from clients) and the
        // shell reads the reply as keystrokes: bash swallows "ESC [ ?" as a key prefix and
        // echoes the remaining "6c" on the next prompt line.
        session.sendKeys("printf '\\033[c'", true);
        Await.until("the DA1 reply to be typed back at the shell", () -> {
            String text = session.capture().text();
            int query = text.lastIndexOf("printf");
            return query >= 0 && text.indexOf("6c", query + "printf '\\033[c'".length()) > 0;
        }, () -> session.capture().text());
        assertFalse(clientA.text().contains("[c"), "the query itself never reached the client");
    }

    @Test
    void snapshotForIsAFreshRedrawAtTheCurrentSize() {
        session.sendKeys("echo re''sync", true);
        Await.until("resync output", () -> session.capture().text().contains("resync"));
        RecordingClient behind = new RecordingClient();
        session.snapshotFor(behind);
        assertEquals(1, behind.chunks().size(), "one snapshot, no open string body");
        byte[] resync = behind.chunks().get(0);
        assertArrayEquals(ST, Arrays.copyOf(resync, ST.length), "a re-sync snapshot opens with ST");
        HeadlessTerminal replica = new HeadlessTerminal(80, 24, HISTORY, bytes -> { });
        replica.apply(tokensOf(resync));
        assertTrue(replica.screenText().contains("resync"));
        assertEquals(1, session.clients().size(), "snapshotFor does not register the client");

        // The attach snapshot is not prefixed: a fresh client is in ground state by definition.
        RecordingClient fresh = new RecordingClient();
        session.attach(fresh, 80, 24);
        assertFalse(Arrays.equals(ST, Arrays.copyOf(fresh.chunks().get(0), ST.length)));
        session.detach(fresh);

        // A client cut off inside an OSC body (its last batch ended in-string and the BEL
        // went down with its queue) must not swallow the redraw as string body.
        byte[] cut = "]2;cut-off".getBytes(StandardCharsets.US_ASCII);
        byte[] stream = new byte[cut.length + resync.length];
        System.arraycopy(cut, 0, stream, 0, cut.length);
        System.arraycopy(resync, 0, stream, cut.length, resync.length);
        List<VtToken> tokens = tokensOf(stream);
        assertFalse(tokens.isEmpty(), "the tokenizer left the open string");
        VtToken first = tokens.get(0);
        assertEquals(VtToken.Kind.OSC, first.kind());
        byte[] closed = new byte[cut.length + ST.length];
        System.arraycopy(cut, 0, closed, 0, cut.length);
        System.arraycopy(ST, 0, closed, cut.length, ST.length);
        assertArrayEquals(closed, first.bytes(), "the open string closes at the prefix and swallows nothing else");
        HeadlessTerminal cutReplica = new HeadlessTerminal(80, 24, HISTORY, bytes -> { });
        cutReplica.apply(tokens.subList(1, tokens.size()));
        assertTrue(cutReplica.screenText().contains("resync"), "the redraw survived:\n" + cutReplica.screenText());
    }

    @Test
    void killGivesTheForegroundProgramTheGraceToHandleSighup() throws Exception {
        // A foreground program whose HUP handler takes a moment (a harness flushing its
        // transcript, vim writing its recovery file): the shell forwards HUP and exits at
        // once, and the grace must cover the program, not just the shell.
        Path marker = workdir.resolve("done");
        session.sendKeys("sh -c 'trap \"sleep 1; touch " + marker + "; exit 0\" HUP; sleep 100 & wait'", true);
        Await.until("the trap to be armed and sleep running", () -> hasDescendantNamed("sleep"));
        long t0 = System.nanoTime();
        session.kill();
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(Files.exists(marker), "the HUP handler finished before any SIGKILL (kill took " + tookMs + " ms)");
        assertTrue(tookMs < PtySession.KILL_GRACE.toMillis() + 1000,
                "kill returned once the tree was gone, took " + tookMs + " ms");
    }

    @Test
    void killFallsBackToSigkillForADescendantThatIgnoresSighup() throws Exception {
        session.sendKeys("sh -c 'trap \"\" HUP; sleep 100'", true);
        Await.until("the HUP-ignoring sleep to be running", () -> hasDescendantNamed("sleep"));
        List<ProcessHandle> descendants = ProcessHandle.of(session.info().pid()).orElseThrow().descendants().toList();
        assertFalse(descendants.isEmpty());
        long t0 = System.nanoTime();
        session.kill();
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(tookMs >= PtySession.KILL_GRACE.toMillis() - 200,
                "the whole grace was spent on the HUP-ignoring descendant, took " + tookMs + " ms");
        assertTrue(tookMs < PtySession.KILL_GRACE.toMillis() + 2000, "took " + tookMs + " ms");
        Await.until("the descendants to be SIGKILLed", Duration.ofSeconds(5),
                () -> descendants.stream().noneMatch(ProcessHandle::isAlive));
    }

    @Test
    void writeAndSendKeysAreOrdered() {
        session.write("echo o''ne; ".getBytes(StandardCharsets.UTF_8));
        session.sendKeys("echo t''wo", false);
        session.sendKeys("", true);
        Await.until("both echoes in order", () -> {
            String text = session.capture().text();
            return text.contains("one") && text.indexOf("one") < text.indexOf("two");
        }, () -> session.capture().text());
    }

    @Test
    void environmentReachesTheShell() {
        session.sendKeys("echo \"$JMUX_SESSION/$TERM/$COLORTERM\" | tr a-z A-Z", true);
        Await.until("the session environment to echo back",
                () -> session.capture().text().contains("T/XTERM-256COLOR/TRUECOLOR"),
                () -> session.capture().text());
    }

    @Test
    void toStringIsInformative() {
        assertEquals("t", session.name());
        assertTrue(session.toString().contains("unix"));
        assertTrue(session.toString().contains(Long.toString(session.info().pid())));
    }

    @Test
    void outputBytesCountEveryReadAndShowInInfoAndCapture() {
        Await.until("the prompt", () -> session.outputBytes() > 0);
        long before = session.outputBytes();
        session.sendKeys("echo co''unted", true);
        Await.until("counted output", () -> session.capture().text().contains("counted"));
        assertTrue(session.outputBytes() > before + "counted".length());
        Session.Capture capture = session.capture();
        assertTrue(capture.outputBytes() >= before);
        assertEquals(session.outputBytes(), session.outputBytes());
    }

    @Test
    void awaitQuietWaitsForTheCausedOutputThenForSilence() throws Exception {
        Await.until("the prompt", () -> session.outputBytes() > 0);
        session.awaitQuiet(-1, 200, 5000);
        long before = session.outputBytes();
        session.write("sleep 0.3; echo la''te\r".getBytes(StandardCharsets.UTF_8));
        // The echo of the typed line alone is output past `before`; quiet needs 500 ms of silence,
        // which the 300 ms sleep does not give, so "late" is on screen when it returns.
        Session.WaitResult settled = session.awaitQuiet(before, 500, 10_000);
        assertTrue(settled.quiet());
        assertTrue(settled.alive());
        assertTrue(settled.outputBytes() > before);
        assertTrue(session.capture().text().contains("late"), session.capture().text());

        // Nothing comes: phase one times out and the idle screen counts as settled.
        long t0 = System.nanoTime();
        Session.WaitResult nothing = session.awaitQuiet(session.outputBytes() + 1_000_000, 100, 300);
        assertTrue(nothing.quiet());
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 250, "phase one used its timeout");

        // Continuous output never goes quiet within the timeout.
        session.sendKeys("while :; do echo bu''sy; sleep 0.05; done", true);
        Await.until("busy output", () -> session.capture().text().contains("busy"));
        Session.WaitResult busy = session.awaitQuiet(-1, 400, 800);
        assertFalse(busy.quiet());
        assertTrue(busy.alive());
        session.input(List.of("C-c"));
        assertTrue(session.awaitQuiet(-1, 300, 10_000).quiet(), "quiet again after C-c");
    }

    @Test
    void awaitQuietReturnsPromptlyWhenTheSessionExits() throws Exception {
        Await.until("the prompt", () -> session.outputBytes() > 0);
        session.sendKeys("sleep 0.3; while :; do echo x; sleep 0.05; done", true);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
                return;
            }
            session.kill();
        });
        long t0 = System.nanoTime();
        Session.WaitResult result = session.awaitQuiet(-1, 5000, 20_000);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000);
        assertTrue(result.quiet());
        assertFalse(result.alive());
    }

    @Test
    void inputKeysFollowTheCursorKeyMode() {
        session.sendKeys("printf '\\033[?1h'; cat -v", true);
        Await.until("cat to run", () -> session.capture().foregroundCommand().equals("cat"));
        session.input(List.of("Up", "Enter"));
        Await.until("the application-mode arrow", () -> session.capture().text().contains("^[OA"),
                () -> session.capture().text());
        session.input(List.of("C-c"));
        Await.until("cat to end", () -> !session.capture().foregroundCommand().equals("cat"));
        session.sendKeys("printf '\\033[?1l'; cat -v", true);
        Await.until("cat to run again", () -> session.capture().foregroundCommand().equals("cat"));
        session.input(List.of("Up", "x", "Enter"));
        Await.until("the normal-mode arrow", () -> session.capture().text().contains("^[[Ax"),
                () -> session.capture().text());
        session.input(List.of("C-c"));
    }

    @Test
    void pasteIsBracketedOnlyWhenTheProgramAskedForIt() {
        session.sendKeys("cat -v", true);
        Await.until("cat to run", () -> session.capture().foregroundCommand().equals("cat"));
        session.paste("pl''ain");
        session.input(List.of("Enter"));
        Await.until("the raw paste", () -> session.capture().text().contains("pl''ain"));
        assertFalse(session.capture().text().contains("200~"));
        session.input(List.of("C-c"));
        Await.until("cat to end", () -> !session.capture().foregroundCommand().equals("cat"));

        session.sendKeys("printf '\\033[?2004h'; cat -v", true);
        Await.until("cat to run again", () -> session.capture().foregroundCommand().equals("cat"));
        Await.until("bracketed paste mode", () -> session.capture(CaptureFormat.ANSI).text().contains("[?2004h"));
        session.paste("br''acketed\u001b[201~");
        session.input(List.of("Enter"));
        Await.until("the bracketed paste", () -> session.capture().text().contains("^[[200~br''acketed[201~^[[201~"),
                () -> session.capture().text());
        // Nested end markers: a one-pass removal of ESC[201~ would leave a working one.
        session.paste("ne''sted\u001b[201\u001b[201~~x\u001b[20\u001b[201~1~y\u009b201~z");
        session.input(List.of("Enter"));
        Await.until("the nested paste, still inside one bracket",
                () -> session.capture().text().contains("^[[200~ne''sted[201[201~~x[20[201~1~y201~z^[[201~"),
                () -> session.capture().text());
        session.input(List.of("C-c"));
    }

    @Test
    void pasteIfBracketedRefusesUntilTheProgramEnablesBracketedPaste() {
        session.sendKeys("cat -v", true);
        Await.until("cat to run", () -> session.capture().foregroundCommand().equals("cat"));
        long before = session.outputBytes();
        assertFalse(session.pasteIfBracketed("re''fused"), "plain cat never enabled DEC 2004");
        session.input(List.of("Enter"));
        Await.until("the Enter echo", () -> session.outputBytes() > before);
        assertFalse(session.capture().text().contains("re''fused"), "a refused paste writes nothing");
        session.input(List.of("C-c"));
        Await.until("cat to end", () -> !session.capture().foregroundCommand().equals("cat"));

        session.sendKeys("printf '\\033[?2004h'; cat -v", true);
        Await.until("cat to run again", () -> session.capture().foregroundCommand().equals("cat"));
        Await.until("bracketed paste mode", () -> session.capture(CaptureFormat.ANSI).text().contains("[?2004h"));
        assertTrue(session.pasteIfBracketed("ac''cepted"));
        session.input(List.of("Enter"));
        Await.until("the bracketed paste", () -> session.capture().text().contains("^[[200~ac''cepted^[[201~"),
                () -> session.capture().text());
        session.input(List.of("C-c"));
    }

    @Test
    void pasteSafeStripsEveryEscAndC1CsiSoNoNestingClosesTheBracket() {
        assertEquals("[201[201~~rm\r", PtySession.pasteSafe("\u001b[201\u001b[201~~rm\r"));
        assertEquals("[20[201~1~", PtySession.pasteSafe("\u001b[20\u001b[201~1~"));
        assertEquals("201~ and [200~", PtySession.pasteSafe("\u009b201~ and \u001b\u001b[200~"));
        assertEquals("multi\nline\ttext ✓", PtySession.pasteSafe("multi\nline\ttext ✓"));
        for (String hostile : List.of("\u001b[201\u001b[201~~", "\u001b[2\u001b[201~01~", "\u009b\u001b[201~201~",
                "\u001b\u001b[201~[201~")) {
            String safe = PtySession.pasteSafe(hostile);
            assertFalse(safe.contains("\u001b") || safe.contains("\u009b"), hostile);
        }
    }

    @Test
    void captureFormats() throws Exception {
        session.sendKeys("echo fo''rmats", true);
        Await.until("output", () -> session.capture().text().contains("formats"));
        session.awaitQuiet(-1, 300, 5000);
        Session.Capture text = session.capture(CaptureFormat.TEXT);
        Session.Capture annotated = session.capture(CaptureFormat.ANNOTATED);
        assertTrue(annotated.cursorVisible());
        assertTrue(annotated.text().contains("▮"), annotated.text());
        assertEquals(text.text(), annotated.text().replace("▮", "").replaceAll(" +\n", "\n"));
        Session.Capture ansi = session.capture(CaptureFormat.ANSI);
        assertTrue(ansi.text().startsWith("\u001b[?1049l"), "the redraw stream");
        HeadlessTerminal replica = new HeadlessTerminal(80, 24, 0, bytes -> { });
        replica.apply(tokensOf(ansi.text().getBytes(StandardCharsets.UTF_8)));
        assertEquals(text.text(), replica.screenText());
    }

    @Test
    void labelsMerge() {
        assertEquals(Map.of(), session.labels());
        session.label(Map.of("a", "1", "b", "2"));
        Map<String, String> changes = new HashMap<>();
        changes.put("a", null);
        changes.put("c", "3");
        session.label(changes);
        assertEquals(Map.of("b", "2", "c", "3"), session.labels());
        assertEquals(Map.of("b", "2", "c", "3"), session.info().labels());
        assertThrows(UnsupportedOperationException.class, () -> session.labels().put("x", "y"));
    }

    @Test
    void watcherSeesOutputQuietTitleBellAndExit() throws Exception {
        Await.until("the prompt", () -> session.outputBytes() > 0);
        session.awaitQuiet(-1, 200, 5000);
        List<SessionEvent> events = new CopyOnWriteArrayList<>();
        Session.Watcher watcher = new Session.Watcher() {
            @Override
            public void onEvent(SessionEvent event) {
                events.add(event);
            }

            @Override
            public long quietMs() {
                return 200;
            }
        };
        session.addWatcher(watcher);
        session.addWatcher(watcher); // idempotent
        // One write: sendKeys' 400 ms pause before Enter would be a quiet period of its own.
        session.write("printf '\\033]2;my''title\\007'; printf '\\007'\r".getBytes(StandardCharsets.UTF_8));
        Await.until("output, title, bell and quiet", () -> types(events).containsAll(
                List.of("output", "title", "bell", "quiet")), () -> events.toString());
        assertEquals("output", events.get(0).type(), "activity comes first");
        assertEquals(1, types(events).stream().filter("output"::equals).count(), "once per active period");
        SessionEvent title = events.stream().filter(e -> e.type().equals("title")).findFirst().orElseThrow();
        assertEquals("mytitle", title.title());
        assertEquals("t", title.name());
        assertEquals(1, types(events).stream().filter("quiet"::equals).count());

        // A new active period after the quiet one.
        session.write("echo again\r".getBytes(StandardCharsets.UTF_8));
        Await.until("a second output and quiet",
                () -> types(events).stream().filter("quiet"::equals).count() == 2, () -> events.toString());
        assertEquals(2, types(events).stream().filter("output"::equals).count());

        session.sendKeys("exit 7", true);
        Await.until("exit", () -> types(events).contains("exit"), () -> events.toString());
        SessionEvent exit = events.get(events.size() - 1);
        assertEquals("exit", exit.type());
        assertEquals(7, exit.exitCode());
        assertEquals(session.outputBytes(), exit.outputBytes());

        List<SessionEvent> late = new CopyOnWriteArrayList<>();
        session.addWatcher(late::add);
        assertEquals(List.of(new SessionEvent("t", "exit", session.outputBytes(), null, 7)), late,
                "a watcher added after the exit learns it at once");
    }

    @Test
    void removedWatcherHearsNothingMore() {
        List<SessionEvent> events = new CopyOnWriteArrayList<>();
        Session.Watcher watcher = events::add;
        session.addWatcher(watcher);
        session.removeWatcher(watcher);
        session.sendKeys("echo x", true);
        assertTrue(Await.holdsFor(Duration.ofMillis(700), events::isEmpty), events.toString());
    }

    private static List<String> types(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::type).toList();
    }

    /** True when some descendant of the session's shell is the named executable. */
    private boolean hasDescendantNamed(String name) {
        return ProcessHandle.of(session.info().pid()).orElseThrow().descendants()
                .anyMatch(h -> h.info().command().map(c -> c.endsWith("/" + name)).orElse(false));
    }

    private static List<VtToken> tokensOf(byte[] bytes) {
        VtTokenizer tokenizer = new VtTokenizer();
        List<VtToken> tokens = new ArrayList<>(tokenizer.feed(bytes).tokens());
        tokens.addAll(tokenizer.flush().tokens());
        return tokens;
    }
}
