package io.github.ghosthack.jmux.cli;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Turns the server's in-band size announcements ({@code CSI 8 ; rows ; columns t},
 * sent when another client resizes the session) into a notice for the console attach
 * instead of letting them reach the terminal, where an emulator that honours XTWINOPS
 * would resize the user's window. Sequences may arrive split across reads, so a
 * candidate is held back until it either completes or stops matching; anything that is
 * not an announcement passes through byte for byte.
 */
final class SizeAnnouncementFilter {

    interface Listener {
        void onAnnouncement(int columns, int rows);
    }

    private static final byte ESC = 0x1b;
    /** {@code ESC [ 8 ;} plus two numbers of up to five digits, {@code ;} and {@code t}. */
    private static final int MAX_HOLD = 16;

    private enum Match { FULL, PREFIX, NONE }

    private final Listener listener;
    private final ByteArrayOutputStream held = new ByteArrayOutputStream();

    SizeAnnouncementFilter(Listener listener) {
        this.listener = listener;
    }

    /** The bytes of {@code in} that should reach the terminal now. */
    byte[] filter(byte[] in, int off, int len) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        for (int i = off; i < off + len; i++) {
            accept(in[i], out);
        }
        return out.toByteArray();
    }

    /** Whatever is still held back at the end of the stream. */
    byte[] flush() {
        byte[] rest = held.toByteArray();
        held.reset();
        return rest;
    }

    private void accept(byte b, ByteArrayOutputStream out) {
        if (held.size() == 0) {
            if (b == ESC) {
                held.write(b);
            } else {
                out.write(b);
            }
            return;
        }
        held.write(b);
        byte[] candidate = held.toByteArray();
        switch (classify(candidate)) {
            case FULL -> {
                held.reset();
                announce(candidate);
            }
            case PREFIX -> {
                if (held.size() > MAX_HOLD) {
                    out.writeBytes(flush());
                }
            }
            case NONE -> {
                // Give up on this candidate, but the byte that broke it may itself open
                // a new one — hand it back through the empty-held path.
                held.reset();
                out.write(candidate, 0, candidate.length - 1);
                accept(b, out);
            }
        }
    }

    private void announce(byte[] sequence) {
        String body = new String(sequence, 2, sequence.length - 3, StandardCharsets.US_ASCII);
        String[] parts = body.split(";");
        listener.onAnnouncement(Integer.parseInt(parts[2]), Integer.parseInt(parts[1]));
    }

    private static Match classify(byte[] c) {
        int i = 0;
        // Fixed head: ESC [ 8 ;
        byte[] head = {ESC, '[', '8', ';'};
        for (; i < c.length && i < head.length; i++) {
            if (c[i] != head[i]) {
                return Match.NONE;
            }
        }
        if (i < head.length) {
            return Match.PREFIX;
        }
        // rows ; columns t — each number at least one digit
        int digits = 0;
        for (int field = 0; field < 2; field++) {
            digits = 0;
            while (i < c.length && c[i] >= '0' && c[i] <= '9') {
                i++;
                digits++;
            }
            if (i == c.length) {
                return Match.PREFIX;
            }
            if (digits == 0) {
                return Match.NONE;
            }
            byte expected = field == 0 ? (byte) ';' : (byte) 't';
            if (c[i] != expected) {
                return Match.NONE;
            }
            i++;
        }
        return i == c.length ? Match.FULL : Match.NONE;
    }
}
