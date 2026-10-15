package io.github.ghosthack.jmux.vt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Test harness that wires the vt pipeline exactly as the session pump does:
 * {@code VtTokenizer → SgrNormalizer → HeadlessTerminal.apply / ModeTracker}, forwarding to
 * clients everything {@link QueryFilter} does not strip. Records the emulator replies and
 * the bytes a client would receive, so tests can assert both the resulting screen and the
 * broadcast stream.
 */
final class VtPipe {

    final VtTokenizer tokenizer = new VtTokenizer();
    final HeadlessTerminal terminal;
    final List<byte[]> replies = new ArrayList<>();
    final ByteArrayOutputStream forwarded = new ByteArrayOutputStream();

    VtPipe(int columns, int rows, int historyLines) {
        this.terminal = new HeadlessTerminal(columns, rows, historyLines, replies::add);
    }

    void feed(byte[] chunk, int off, int len) {
        VtTokenizer.Batch batch = tokenizer.feed(chunk, off, len);
        List<VtToken> normalized = new ArrayList<>(batch.tokens().size());
        for (VtToken token : batch.tokens()) {
            VtToken n = SgrNormalizer.normalize(token);
            normalized.add(n);
            if (!QueryFilter.isServerAnswered(n)) {
                byte[] fwd = n.bytesToForward();
                forwarded.write(fwd, 0, fwd.length);
            }
        }
        terminal.apply(normalized);
        byte[] frag = batch.openStringFragment();
        forwarded.write(frag, 0, frag.length);
    }

    void feed(byte[] chunk) {
        feed(chunk, 0, chunk.length);
    }

    void feed(String s) {
        feed(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Feeds {@code data} in fixed-size chunks (the last is short). */
    void feedChunked(byte[] data, int chunkSize) {
        for (int off = 0; off < data.length; off += chunkSize) {
            feed(data, off, Math.min(chunkSize, data.length - off));
        }
    }

    byte[] forwardedBytes() {
        return forwarded.toByteArray();
    }
}
