/*
 * SocksSessionHandler.java
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

package org.bluezoo.gumdrop.socks.handler;

/**
 * Authorization for both SOCKS commands (CONNECT and BIND) on one accepted
 * connection.
 *
 * <p>A SOCKS client issues exactly one command per connection, so a single
 * object implementing both {@link ConnectHandler} and {@link BindHandler}
 * covers the whole session. Returned by {@link
 * org.bluezoo.gumdrop.socks.server.SocksServerSessionProvider#openSession}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.socks.server.SocksServerSessionProvider
 */
public interface SocksSessionHandler extends ConnectHandler, BindHandler {
}
