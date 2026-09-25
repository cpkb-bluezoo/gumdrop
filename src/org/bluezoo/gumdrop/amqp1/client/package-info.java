/*
 * src/org/bluezoo/gumdrop/amqp1/client/package-info.java
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
 * Non-blocking AMQP 1.0 client for messaging against a broker such as
 * RabbitMQ 4 or ActiveMQ Artemis.
 *
 * <p>{@link org.bluezoo.gumdrop.amqp1.client.Amqp1ClientRecovery} is the
 * facade most applications should use: it dials the broker (implicit TLS,
 * {@code amqps}, port 5671, with {@code setSecure}), authenticates, opens
 * the connection and a session, and, when the connection is lost,
 * reconnects with exponential backoff
 * ({@link org.bluezoo.gumdrop.amqp1.client.Amqp1RecoveryPolicy}) and
 * attaches the application's links again. See
 * {@link org.bluezoo.gumdrop.amqp1.client.Amqp1RecoverableSession} for
 * exactly what is preserved across a reconnect.
 *
 * <p>{@link org.bluezoo.gumdrop.amqp1.client.Amqp1ClientProtocolHandler}
 * underlies it and can be used directly, with a
 * {@link org.bluezoo.gumdrop.ClientEndpoint}, for finer control. It drives
 * the connection and session lifecycle: the SASL security layer, the AMQP
 * protocol header, {@code open}, {@code begin}/{@code end} and
 * {@code close}, with keepalives honouring both peers' idle timeouts.
 * Within a session, {@link org.bluezoo.gumdrop.amqp1.client.Amqp1Sender} and
 * {@link org.bluezoo.gumdrop.amqp1.client.Amqp1Receiver} links carry
 * messages: a delivery is streamed in both directions (see
 * {@link org.bluezoo.gumdrop.amqp1.client.Amqp1OutgoingDelivery} and
 * {@link org.bluezoo.gumdrop.amqp1.client.Amqp1ReceiverHandler}) under link
 * credit and the session's transfer window, and settled with explicit
 * dispositions.
 *
 * <p>The API is single-threaded: handler callbacks run on the connection's
 * event loop, and calls made from them are always safe. From any other
 * thread, go through {@link org.bluezoo.gumdrop.amqp1.client.Amqp1ClientRecovery#execute}.
 *
 * <p>Every step is exposed through a typed-state interface offering only
 * the operations legal at that point, so the compiler rejects
 * out-of-sequence calls:
 *
 * <pre>
 * Amqp1ConnectionReady --(sasl-mechanisms)--&gt; Amqp1SaslHandshake
 *      --(sasl-outcome)--&gt; Amqp1AuthHandler
 *      --(authenticated)--&gt; Amqp1ConnectionOpener
 *      --(open)--&gt; Amqp1OpenHandler
 *      --(peer open)--&gt; Amqp1Connection
 *      --(begin)--&gt; Amqp1SessionHandler / Amqp1Session
 * </pre>
 *
 * <p>Wire encoding lives in {@code org.bluezoo.gumdrop.amqp1.codec}, which
 * this package builds on but which does not depend on it. This is
 * unrelated to the AMQP 0-9-1 client in {@code org.bluezoo.gumdrop.amqp.client}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.amqp1.codec
 * @see <a href="https://docs.oasis-open.org/amqp/core/v1.0/os/amqp-core-v1.0-os.html">AMQP 1.0 core</a>
 */
package org.bluezoo.gumdrop.amqp1.client;
