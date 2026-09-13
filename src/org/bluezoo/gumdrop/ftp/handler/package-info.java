/*
 * package-info.java
 * Copyright (C) 2026 Chris Burdess
 */

/**
 * Staged handler and state interfaces for the FTP server (RFC 959).
 *
 * <p>Implement {@link ClientConnected} and register it via
 * {@link org.bluezoo.gumdrop.ftp.server.FtpServerSessionProvider}. The
 * protocol handler drives the session through typed state interfaces at each
 * step — mirroring the client-side staged handlers in
 * {@code org.bluezoo.gumdrop.ftp.client.handler}.
 *
 * <p>Stock implementation: {@link DefaultFtpHandler}. Legacy
 * {@link org.bluezoo.gumdrop.ftp.FtpConnectionHandler} implementations are
 * adapted via {@link LegacyConnectionHandlerAdapter}.
 *
 * @see org.bluezoo.gumdrop.ftp.server.FtpServerSessionProvider
 * @see docs/COMPOSITION.md
 */
package org.bluezoo.gumdrop.ftp.handler;
