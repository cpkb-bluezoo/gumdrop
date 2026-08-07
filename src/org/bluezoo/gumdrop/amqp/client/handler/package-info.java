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
 * Typed, stateful callback interfaces for the AMQP 0-9-1 client
 * (mirrors {@link org.bluezoo.gumdrop.smtp.client.handler}). Each
 * interface exposes only the operations legal at that point in the
 * protocol, so the compiler rejects out-of-sequence calls:
 *
 * <pre>
 * ConnectionReady --(connection.start)--&gt; ClientHandshake
 *      --(start-ok, connection.tune)--&gt; ServerTuneHandler
 *      --(tune-ok)--&gt; ClientTuned
 *      --(connection.open)--&gt; ServerOpenHandler
 *      --(open-ok)--&gt; ClientConnection
 *      --(channel.open)--&gt; ServerChannelOpenHandler
 *      --(open-ok)--&gt; ClientChannel
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.amqp.client.AMQPClientProtocolHandler
 */
package org.bluezoo.gumdrop.amqp.client.handler;
