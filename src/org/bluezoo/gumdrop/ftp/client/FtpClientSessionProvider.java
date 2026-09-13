/*
 * FtpClientSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.client;

import org.bluezoo.gumdrop.ClientSessionProvider;
import org.bluezoo.gumdrop.ftp.client.handler.RemoteGreeting;

/**
 * FTP client composition SPI — supplies the bootstrap handler for one outbound
 * session.
 *
 * <p>The returned {@link RemoteGreeting} receives the server greeting and enters
 * the staged client pipeline ({@link
 * org.bluezoo.gumdrop.ftp.client.handler.ClientLoginState}, {@link
 * org.bluezoo.gumdrop.ftp.client.handler.ClientAuthenticatedState}, …).
 *
 * <p>Pass to {@link FtpClient#connect(FtpClientSessionProvider)} or call
 * {@link #openSession()} and pass the result to
 * {@link FtpClient#connect(RemoteGreeting)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientSessionProvider
 * @see docs/COMPOSITION.md
 */
public interface FtpClientSessionProvider
        extends ClientSessionProvider<RemoteGreeting> {
}
