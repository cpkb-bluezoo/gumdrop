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
 * The NNTP server pipeline: the stages through which an application takes
 * part in a connection.
 *
 * <p>A {@link org.bluezoo.gumdrop.nntp.server.ClientConnected} handler
 * accepts the connection through its
 * {@link org.bluezoo.gumdrop.nntp.server.ConnectedState} and supplies a
 * {@link org.bluezoo.gumdrop.nntp.server.ReaderHandler}, which answers the
 * reader commands through the state interfaces
 * ({@link org.bluezoo.gumdrop.nntp.server.GroupState},
 * {@link org.bluezoo.gumdrop.nntp.server.ListState},
 * {@link org.bluezoo.gumdrop.nntp.server.ArticleState} and
 * {@link org.bluezoo.gumdrop.nntp.server.OverState}).
 * {@link org.bluezoo.gumdrop.nntp.server.DefaultNntpHandler} is the stock
 * implementation, over a mailbox store.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
package org.bluezoo.gumdrop.nntp.server;
