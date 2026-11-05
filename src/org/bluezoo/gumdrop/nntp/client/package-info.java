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
 * The NNTP client: {@link org.bluezoo.gumdrop.nntp.client.NntpClient}
 * connects to a news server and hands an
 * {@link org.bluezoo.gumdrop.nntp.client.NntpSession} to an
 * {@link org.bluezoo.gumdrop.nntp.client.NntpClientHandler} once the
 * greeting, STARTTLS and AUTHINFO are done.
 *
 * <p>Commands on a session are sent one at a time in the order issued.
 * Multi-line results are delivered as they arrive to an
 * {@link org.bluezoo.gumdrop.nntp.client.NntpConsumer}: article text
 * dot-unstuffed in whatever pieces the connection delivers it, and LIST
 * ACTIVE and OVER output as parsed entries, so nothing is held whole.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
package org.bluezoo.gumdrop.nntp.client;
