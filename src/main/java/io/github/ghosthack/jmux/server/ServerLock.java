package io.github.ghosthack.jmux.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The exclusive {@code server.lock} a server holds for its whole life — step (1) of the
 * fixed startup order. Only the server process ever opens this file; clients never lock
 * or probe it, so a held lock means exactly "another live server owns this home".
 *
 * <p>The pid written by {@link #writePid} is for diagnostics only ({@code cat server.lock});
 * the lock itself is what carries the ownership, because a pid in a file cannot tell a
 * crashed server from a live one and an OS file lock can.
 */
final class ServerLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private ServerLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Empty when another server (in this or any other process) already holds the lock. */
    static Optional<ServerLock> tryAcquire(Path lockFile) throws IOException {
        Files.createDirectories(lockFile.toAbsolutePath().getParent());
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            // Same JVM already holds it (an in-process second server, as tests do): the
            // JDK reports that as an exception rather than a null lock.
            lock = null;
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            return Optional.empty();
        }
        return Optional.of(new ServerLock(channel, lock));
    }

    void writePid(long pid) throws IOException {
        channel.truncate(0);
        ByteBuffer bytes = ByteBuffer.wrap((pid + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
        channel.force(false);
    }

    /** Releases the lock and closes the file; the file itself stays (it is the next
     * server's lock target). */
    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // Closing the channel below releases the lock anyway.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing left to do with a channel that will not close; the process is exiting.
        }
    }
}
