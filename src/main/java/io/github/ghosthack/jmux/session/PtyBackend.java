package io.github.ghosthack.jmux.session;

import com.pty4j.PtyProcess;

/**
 * Which pty4j backend a session actually got. pty4j 0.13.10 defaults to winpty on Windows
 * and silently falls back to it when ConPTY creation fails, so the daemon records the
 * real backend per session and reports it on {@code list}/{@code new}; winpty is a
 * diagnosable degraded state, not a supported configuration (SESSION-HOST.md "Session
 * model").
 *
 * <p>Detection goes by class name so that no Windows-only pty4j class (and none of its JNA
 * bindings) is ever referenced, let alone loaded, on macOS or Linux.
 */
enum PtyBackend {
    UNIX("unix"),
    CONPTY("conpty"),
    WINPTY("winpty"),
    CYGWIN("cygwin"),
    UNKNOWN("unknown");

    private final String id;

    PtyBackend(String id) {
        this.id = id;
    }

    /** The wire value: {@code unix} | {@code conpty} | {@code winpty} (| {@code cygwin} | {@code unknown}). */
    String id() {
        return id;
    }

    static PtyBackend of(PtyProcess process) {
        return ofClassName(process.getClass().getName());
    }

    static PtyBackend ofClassName(String className) {
        return switch (className) {
            case "com.pty4j.unix.UnixPtyProcess" -> UNIX;
            case "com.pty4j.windows.conpty.WinConPtyProcess" -> CONPTY;
            // pty4j has both the Kotlin wrapper and the underlying Java class; the builder
            // instantiates the latter, but accept either.
            case "com.pty4j.windows.WinPtyProcess", "com.pty4j.windows.winpty.WinPtyProcess" -> WINPTY;
            case "com.pty4j.windows.cygwin.CygwinPtyProcess" -> CYGWIN;
            default -> UNKNOWN;
        };
    }
}
