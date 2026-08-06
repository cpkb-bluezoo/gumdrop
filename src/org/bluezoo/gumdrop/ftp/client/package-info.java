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
 * Non-blocking FTP client (RFC 959) for driving remote or in-process FTP
 * servers.
 *
 * <p>This package provides an asynchronous, event-driven FTP client
 * following the same architecture as the {@code smtp.client}, {@code
 * pop3.client}, and {@code imap.client} packages: a single {@link
 * org.bluezoo.gumdrop.ProtocolHandler} implementation for the control
 * connection, a type-safe stateful handler pattern
 * ({@code ClientLoginState}, {@code ClientAuthenticatedState}, etc.), and a
 * streaming {@link org.bluezoo.gumdrop.ByteStreamLexer}-based reply parser
 * (issue #85) rather than a buffered-line model.
 *
 * <p>FTP is a two-connection protocol (control + data). The control
 * connection is handled by {@link
 * org.bluezoo.gumdrop.ftp.client.FTPClientProtocolHandler} exactly like the
 * other client protocol handlers; data connections (PASV/EPSV/PORT/EPRT for
 * RETR/STOR/LIST) are handled separately, mirroring the design of the FTP
 * server's own {@code FTPDataConnectionCoordinator}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.ftp.client.FTPClientProtocolHandler
 * @see org.bluezoo.gumdrop.ftp
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a> (FTP)
 */
package org.bluezoo.gumdrop.ftp.client;
