/*
 * ComposedSmtpServer.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp.server;

import org.bluezoo.gumdrop.TcpListener;

/**
 * Concrete {@link SmtpServer} assembled from listeners and a session provider.
 *
 * <p>Created via {@link SmtpServer#compose()}; not intended for subclassing.
 */
final class ComposedSmtpServer extends SmtpServer {

    private final SmtpServerSessionProvider sessionProvider;

    ComposedSmtpServer(SmtpServerSessionProvider sessionProvider) {
        if (sessionProvider == null) {
            throw new NullPointerException("sessionProvider");
        }
        this.sessionProvider = sessionProvider;
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        return sessionProvider.openSession(listener);
    }

    @Override
    protected SmtpServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

}
