/*
 * SmtpClientSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp.client;

import org.bluezoo.gumdrop.ClientSessionProvider;
import org.bluezoo.gumdrop.smtp.client.handler.RemoteGreeting;

/**
 * SMTP client composition SPI — supplies the bootstrap handler for one outbound
 * session.
 *
 * <p>The returned {@link RemoteGreeting} receives the server banner and enters
 * the staged client pipeline ({@link
 * org.bluezoo.gumdrop.smtp.client.handler.ClientHelloState}, {@link
 * org.bluezoo.gumdrop.smtp.client.handler.ClientSession}, …).
 *
 * <p>Pass to {@link SmtpClient#connect(SmtpClientSessionProvider)} or call
 * {@link #openSession()} and pass the result to
 * {@link SmtpClient#connect(RemoteGreeting)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientSessionProvider
 * @see docs/COMPOSITION.md
 */
public interface SmtpClientSessionProvider
        extends ClientSessionProvider<RemoteGreeting> {
}
