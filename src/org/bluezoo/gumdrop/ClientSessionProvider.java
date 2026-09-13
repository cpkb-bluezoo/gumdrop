/*
 * ClientSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop;

/**
 * Composes a <strong>stateful</strong> protocol client: supplies the bootstrap
 * handler for a new outbound session.
 *
 * <p>After {@link org.bluezoo.gumdrop.ClientEndpoint} connects, the protocol
 * engine drives a staged handler pipeline. The client facade accepts a
 * {@code ClientSessionProvider} (or equivalent bootstrap handler) to start that
 * pipeline — for example {@link org.bluezoo.gumdrop.smtp.client.SmtpClient#connect}
 * with {@link org.bluezoo.gumdrop.smtp.client.SmtpClientSessionProvider}.
 *
 * <p><strong>Stateless clients do not use this SPI.</strong> HTTP
 * {@link org.bluezoo.gumdrop.http.HttpClient} issues discrete requests; DNS
 * {@link org.bluezoo.gumdrop.dns.client.DnsResolver} resolves individual
 * queries. Neither maintains a command-level session pipeline.
 *
 * @param <S> the session bootstrap type (first staged client handler)
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerSessionProvider
 * @see docs/COMPOSITION.md
 */
public interface ClientSessionProvider<S> {

    /**
     * Returns the bootstrap handler for one outbound session.
     *
     * <p>May be called once per {@code connect}; implementations that hold
     * per-session state should return a new object each time.
     *
     * @return the session bootstrap handler
     */
    S openSession();

}
