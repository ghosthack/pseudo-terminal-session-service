package io.github.ghosthack.jmux.session;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/** Polling waits for the live tests; a miss fails with the caller's description. */
final class Await {

    static final Duration DEFAULT = Duration.ofSeconds(10);

    static void until(String what, BooleanSupplier condition) {
        until(what, DEFAULT, condition);
    }

    static void until(String what, Duration timeout, BooleanSupplier condition) {
        until(what, timeout, condition, () -> "");
    }

    static void until(String what, BooleanSupplier condition, Supplier<String> detail) {
        until(what, DEFAULT, condition, detail);
    }

    static void until(String what, Duration timeout, BooleanSupplier condition, Supplier<String> detail) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what + " after " + timeout + ": " + detail.get());
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what);
            }
        }
    }

    /** True if {@code condition} holds continuously for {@code duration}. */
    static boolean holdsFor(Duration duration, BooleanSupplier condition) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            if (!condition.getAsBoolean()) {
                return false;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private Await() {
    }
}
