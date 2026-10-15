package io.github.ghosthack.jmux.session;

import io.github.ghosthack.jmux.protocol.Protocol;

/**
 * Something a {@link Session.Watcher} is told about. {@code type} is one of
 * {@link Protocol#EVENT_OUTPUT} (the session became active), {@link Protocol#EVENT_QUIET}
 * (no output for the watcher's quiet period after activity), {@link Protocol#EVENT_TITLE}
 * ({@code title} carries the new one), {@link Protocol#EVENT_BELL} and
 * {@link Protocol#EVENT_EXIT} ({@code exitCode} set). {@code outputBytes} is the session's
 * byte counter when the event was raised; {@code title} and {@code exitCode} are null on
 * the events that do not carry them.
 */
public record SessionEvent(String name, String type, long outputBytes, String title, Integer exitCode) {
}
