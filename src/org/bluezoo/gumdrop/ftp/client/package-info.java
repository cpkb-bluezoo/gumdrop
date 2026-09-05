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
 * <p>{@link org.bluezoo.gumdrop.ftp.client.FTPClientProtocolHandler}
 * drives the control connection; different state interfaces (package
 * {@link org.bluezoo.gumdrop.ftp.client.handler}) are provided at each
 * stage of the protocol, so only the commands valid at that point can be
 * issued. Replies are parsed by a streaming {@link
 * org.bluezoo.gumdrop.ByteStreamLexer}-based reader rather than a
 * buffered-line model, so a multi-line reply never needs to be
 * materialised whole before dispatch.
 *
 * <p>FTP is a two-connection protocol: the control connection above
 * handles commands and replies, while data connections (PASV/EPSV/PORT/
 * EPRT for RETR/STOR/LIST) are negotiated and managed separately,
 * mirroring the server side's own {@code FTPDataConnectionCoordinator}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.ftp.client.FTPClientProtocolHandler
 * @see org.bluezoo.gumdrop.ftp
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a> (FTP)
 */
package org.bluezoo.gumdrop.ftp.client;
