/*
 * SmtpServerSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp.server;

import org.bluezoo.gumdrop.ServerSessionProvider;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;

/**
 * SMTP server composition SPI — mints a staged handler pipeline per accepted
 * control connection.
 *
 * <p>Implementations return an object that implements {@link ClientConnected}
 * and subsequent staged server handler interfaces ({@link
 * org.bluezoo.gumdrop.smtp.handler.HelloHandler}, {@link
 * org.bluezoo.gumdrop.smtp.handler.MailFromHandler}, …). Stock examples:
 * {@link org.bluezoo.gumdrop.smtp.SimpleRelayHandler},
 * {@link org.bluezoo.gumdrop.smtp.LocalDeliveryHandler}.
 *
 * <p>{@link SmtpServer} implements this interface. Stateless protocols such as
 * HTTP and DNS compose with request/query handlers instead — they do not extend
 * {@link ServerSessionProvider}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerSessionProvider
 * @see docs/COMPOSITION.md
 */
public interface SmtpServerSessionProvider
        extends ServerSessionProvider<ClientConnected> {
}
