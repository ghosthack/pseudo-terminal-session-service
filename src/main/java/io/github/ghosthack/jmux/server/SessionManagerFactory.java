package io.github.ghosthack.jmux.server;

import io.github.ghosthack.jmux.session.SessionManager;
import io.github.ghosthack.jmux.session.SessionManagerImpl;

/**
 * The one place {@code serve} obtains the real, PTY-backed {@link SessionManager}. Kept
 * behind a factory so the transport (this package) is testable against a fake manager
 * and the session package stays a separate, independently tested layer.
 */
public final class SessionManagerFactory {

    public static SessionManager createSessionManager(ServeOptions options) {
        return new SessionManagerImpl(options.historyLines());
    }

    private SessionManagerFactory() {
    }
}
