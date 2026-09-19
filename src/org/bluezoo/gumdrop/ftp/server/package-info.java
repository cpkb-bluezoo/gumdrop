/*
 * package-info.java
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
