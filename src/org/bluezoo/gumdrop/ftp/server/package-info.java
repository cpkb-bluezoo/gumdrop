/*
 * package-info.java
 * Copyright (C) 2026 Chris Burdess
 */

/**
 * FTP server: listeners, composition, and staged handler/state interfaces
 * (RFC 959).
 *
 * <p>{@link org.bluezoo.gumdrop.ftp.server.FtpServer} is the canonical
 * server type — configure it with {@link
 * org.bluezoo.gumdrop.ftp.server.FtpServer#compose()}; do not subclass it
 * for application logic.
 *
 * <p>Implement {@link ClientConnected} and register it via {@link
 * FtpServerSessionProvider} (typically through {@link
 * FtpServerSessionProviders}). The protocol handler drives the session
 * through typed state interfaces at each step — mirroring the client-side
 * staged handlers in {@code org.bluezoo.gumdrop.ftp.client}.
 *
 * <p>Stock implementation: {@link DefaultFtpHandler}. Legacy {@link
 * org.bluezoo.gumdrop.ftp.FtpConnectionHandler} implementations are adapted
 * via {@link LegacyConnectionHandlerAdapter}.
 *
 * @see FtpServerSessionProvider
 * @see web/configuration.html
 */
package org.bluezoo.gumdrop.ftp.server;
