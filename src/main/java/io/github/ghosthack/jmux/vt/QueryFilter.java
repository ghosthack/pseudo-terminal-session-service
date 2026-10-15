package io.github.ghosthack.jmux.vt;

/**
 * Decides which tokens are <em>not</em> forwarded to attached clients because the server's
 * own emulator answers (or deliberately ignores) them. The set mirrors jediterm-core's
 * {@code JediEmulator} dispatch in the fork pinned by pom.xml (3.74-ghosthack.1); re-check
 * the cited lines on every fork bump. A client's jediterm would otherwise answer too —
 * duplicate replies with two clients attached, and no reply at all when no client is
 * attached, which is precisely the state right after {@code new} + {@code send-keys} when a
 * harness starts and probes its terminal.
 *
 * <ul>
 *   <li>DA1 — CSI, final {@code c}, no {@code >} prefix, any parameters ({@code CSI c},
 *       {@code CSI 0 c}, {@code CSI = c}): stripped; JediEmulator.java:485-489 answers all
 *       of them as VT102.</li>
 *   <li>DA2 — CSI, {@code >} prefix, final {@code c}, first parameter absent or 0:
 *       stripped (JediEmulator.java:846-856). {@code CSI > 1 c} passes through — jediterm
 *       answers nothing on either side.</li>
 *   <li>DSR/CPR — CSI, final {@code n}, no {@code ?} prefix, first parameter 5 or 6:
 *       stripped (JediEmulator.java:767-790). {@code CSI ? 6 n} (DECXCPR) passes through.</li>
 *   <li>XTWINOPS 8 — {@code CSI 8 ; … t}: stripped; the server's {@code JediTerminal}
 *       subclass ignores it too (it would resize the emulator but not the PTY). The
 *       server's own size announcements are injected after this filter.</li>
 *   <li>{@code CSI ? 996 n} — colour-scheme preference: <b>passed through</b>; only a
 *       client knows its theme, and the server drops its own {@code ?997} reply.</li>
 *   <li>OSC 10/11 {@code ?}, DECRQM, XTVERSION, kitty keyboard, everything else: passed
 *       through, so behaviour matches a direct attach today.</li>
 * </ul>
 *
 * Keep this and {@code HeadlessTerminal}'s reply interception in lock-step: a query the
 * emulator answers but this filter forwards is a duplicate-reply bug; a query this filter
 * strips but nobody answers is a hang for the program that asked.
 */
public final class QueryFilter {

    /** True when {@code token} must not be forwarded to clients. */
    public static boolean isServerAnswered(VtToken token) {
        if (token.kind() != VtToken.Kind.CSI) {
            return false;
        }
        String body = token.csiBody();
        switch (token.csiFinal()) {
            case 'c' -> {
                if (body.startsWith(">")) {
                    // DA2: strip only when the first parameter is absent or 0.
                    // JediEmulator.sendSecondaryDeviceAttributes (JediEmulator.java:846-856).
                    return leadingInt(body, 1) == 0;
                }
                // DA1 (primary/tertiary): jediterm answers any parameters with no '>' prefix.
                // JediEmulator.sendDeviceAttributes (JediEmulator.java:485-489).
                return true;
            }
            case 'n' -> {
                if (body.startsWith("?")) {
                    // DECXCPR (CSI ? 6 n) and CSI ? 996 n pass through — not answered here.
                    return false;
                }
                // DSR/CPR (JediEmulator.java:767-790): first parameter 5 or 6.
                int p = leadingInt(body, 0);
                return p == 5 || p == 6;
            }
            case 't' -> {
                // XTWINOPS: strip the "resize text area" form (8) only.
                return leadingInt(body, 0) == 8;
            }
            default -> {
                return false;
            }
        }
    }

    /** Parse the run of decimal digits starting at {@code from} in {@code body}; 0 when
     * there are none (also the default jediterm applies for an absent first parameter). */
    private static int leadingInt(String body, int from) {
        int i = from;
        int value = 0;
        boolean seen = false;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            value = value * 10 + (c - '0');
            seen = true;
            i++;
        }
        return seen ? value : 0;
    }

    private QueryFilter() {
    }
}
