/*
 * ComposedSmtpServer.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.smtp.server;

import org.bluezoo.gumdrop.TcpListener;

/**
 * Concrete {@link SmtpServer} assembled from listeners and a session provider.
 *
 * <p>Created via {@link SmtpServer#compose()}; not intended for subclassing.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
