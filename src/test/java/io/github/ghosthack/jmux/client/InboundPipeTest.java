package io.github.ghosthack.jmux.client;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboundPipeTest {

    /** jediterm's TerminalStarter treats an interrupted read as its normal, quiet
     * session-stop path — but only when it arrives as {@link InterruptedIOException}
     * specifically, not the plain {@link java.io.IOException} supertype; anything else
     * is logged as an uncaught emulator error. A widget stops a session via
     * {@code Future.cancel(true)}, i.e. by interrupting this exact thread mid-read. */
    @Test
    void interruptingABlockedReadThrowsInterruptedIOExceptionAndRestoresTheInterruptFlag() throws Exception {
        InboundPipe pipe = new InboundPipe(64);
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptedAfterCatch = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            blocked.countDown();
            try {
                pipe.read(new byte[16], 0, 16);
            } catch (Throwable t) {
                thrown.set(t);
                interruptedAfterCatch.set(Thread.currentThread().isInterrupted());
            }
        });
        reader.start();
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        Thread.sleep(50); // let the reader actually reach the blocking await()
        reader.interrupt();
        reader.join(5000);

        assertInstanceOf(InterruptedIOException.class, thrown.get());
        assertEquals(Boolean.TRUE, interruptedAfterCatch.get(), "interrupt status must be restored");
    }
}
