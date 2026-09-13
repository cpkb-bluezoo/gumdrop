/*
 * ServerSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
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
 * with {@link org.bluezoo.gumdrop.http.server.HttpRequestRouter} /
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
 * @see ClientSessionProvider
 * @see docs/COMPOSITION.md
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
