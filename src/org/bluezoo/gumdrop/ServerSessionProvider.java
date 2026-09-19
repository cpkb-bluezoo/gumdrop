/*
 * ServerSessionProvider.java
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

package org.bluezoo.gumdrop;

/**
 * Composes a <strong>stateful</strong> protocol server: supplies a fresh
 * application handler pipeline for each accepted connection.
 *
 * <p>Stateful protocols (SMTP, FTP, IMAP, POP3, …) maintain session state
 * across many command/response exchanges on one transport connection. The
 * server wires a {@code ServerSessionProvider} once at composition time; the
 * provider mints a new session-scoped handler set on every accept.
 *
 * <p><strong>Stateless protocols do not use this SPI.</strong> HTTP composes
 * with {@link org.bluezoo.gumdrop.http.server.HttpStreamHandler} and
 * {@link org.bluezoo.gumdrop.http.server.HttpRequestHandler} (one handler per
 * request stream, no long-lived session pipeline). DNS composes with
 * {@link org.bluezoo.gumdrop.dns.server.DnsQueryHandler} (one handler
 * invocation per query datagram).
 *
 * <p>Protocol-specific subinterfaces (e.g.
 * {@link org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProvider}) fix the
 * session pipeline entry type and document staged handler interfaces for that
 * protocol.
 *
 * @param <S> the session pipeline entry type for one accepted connection
 *            (often the first staged server handler interface)
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see web/configuration.html
 */
public interface ServerSessionProvider<S> {

    /**
     * Opens the application handler pipeline for one accepted connection.
     *
     * <p>Called on the accept path. The returned object must be safe to use
     * only on that connection (implementations are typically stateful and not
     * shared across concurrent peers).
     *
     * @param listener the control listener that accepted the connection
     * @return the session pipeline entry handler, or {@code null} for default
     *         protocol behaviour when supported
     */
    S openSession(TcpListener listener);

}
