/*
 * ClientHandshake.java
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

package org.bluezoo.gumdrop.amqp.client.handler;

import java.util.concurrent.ExecutorService;

import org.bluezoo.gumdrop.auth.SASLClientMechanism;

/**
 * State after {@code connection.start} — send credentials via
 * {@code start-ok}.
 *
 * <p>SASL {@code PLAIN} is offered as a direct convenience overload since
 * it is the mechanism almost all brokers require at minimum. For
 * {@code AMQPLAIN}, {@code EXTERNAL}, or {@code GSSAPI} (issue #188), pass
 * a {@link SASLClientMechanism} obtained from
 * {@link org.bluezoo.gumdrop.auth.SASLUtils#createClient} (or, for
 * {@code AMQPLAIN}, from the AMQP client package itself) instead — the
 * protocol handler drives it through {@code start-ok} and, if the broker
 * demands further rounds via {@code connection.secure}, through as many
 * {@code secure-ok} exchanges as the mechanism requires.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ClientHandshake {

    /**
     * Authenticates with SASL {@code PLAIN} and waits for
     * {@code connection.tune}.
     *
     * @param username the username
     * @param password the password
     * @param handler receives {@code tune} once the server responds
     */
    void startOk(String username, String password, ServerTuneHandler handler);

    /**
     * Authenticates with an arbitrary non-blocking SASL mechanism (e.g.
     * {@code AMQPLAIN} or {@code EXTERNAL}) and waits for
     * {@code connection.tune}.
     *
     * <p>Not for {@code GSSAPI} — its first challenge evaluation may
     * block on KDC contact; use
     * {@link #startOk(SASLClientMechanism, ServerTuneHandler, ExecutorService)}
     * instead.
     *
     * @param saslClient the SASL mechanism driving the exchange
     * @param handler receives {@code tune} once the server responds
     */
    void startOk(SASLClientMechanism saslClient, ServerTuneHandler handler);

    /**
     * Authenticates with an arbitrary SASL mechanism, offloading each
     * {@link SASLClientMechanism#evaluateChallenge} call to
     * {@code executor} before dispatching the resulting frame back on the
     * connection's event loop.
     *
     * <p>Required for {@code GSSAPI}, whose first challenge evaluation may
     * contact the KDC via blocking socket I/O; safe to use for any other
     * mechanism too.
     *
     * @param saslClient the SASL mechanism driving the exchange
     * @param handler receives {@code tune} once the server responds
     * @param executor worker executor for blocking challenge evaluation
     */
    void startOk(SASLClientMechanism saslClient, ServerTuneHandler handler, ExecutorService executor);

}
