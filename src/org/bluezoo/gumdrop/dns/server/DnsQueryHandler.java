/*
 * DnsQueryHandler.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;

/**
 * Application logic for a {@link DnsServer} — resolves or declines DNS queries.
 *
 * <p>DNS is <strong>stateless</strong> at the application layer: each query is
 * handled independently. Compose {@link DnsServer} with a {@code DnsQueryHandler},
 * not {@link org.bluezoo.gumdrop.ServerSessionProvider}. Stateful protocols
 * (SMTP, FTP, …) use session providers instead — see {@code web/configuration.html}.
 *
 * <p>The {@link DnsServer} protocol shell (listeners, validation, cookies,
 * MQTYPE merging) delegates to a handler after parsing each message. Standard
 * {@code OPCODE_QUERY} traffic uses {@link #handleQuery(DnsMessage,
 * SelectorLoop, DnsQueryCallback)}; other opcodes (RFC 1996 NOTIFY, RFC 2136
 * dynamic update, and so on) use {@link #handleNonQueryOpcode(DnsMessage,
 * SelectorLoop, DnsQueryCallback)}. Return {@code false} from the latter when
 * the handler does not support that opcode so the next chained handler, or the
 * server's default {@code NOTIMP}, can run. Stock implementations include
 * {@link EmptyDnsQueryHandler}, {@link UpstreamRelayHandler}, and
 * {@link AuthoritativeZoneHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DnsQueryHandlers
 * @see org.bluezoo.gumdrop.ServerSessionProvider
 */
public interface DnsQueryHandler {

    /**
     * Handles a DNS query asynchronously.
     *
     * @param query the parsed query message
     * @param loop the selector loop for outbound connections, or {@code null}
     *             to obtain a worker loop from {@link org.bluezoo.gumdrop.Gumdrop}
     * @param callback invoked exactly once with the response
     */
    void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryCallback callback);

    /**
     * Handles a message that is not a standard {@code OPCODE_QUERY}, if
     * this handler supports it.
     *
     * <p>When {@code true} is returned, the handler must invoke
     * {@code callback} exactly once. When {@code false} is returned, the
     * server tries the next handler in a {@link DnsQueryHandlers#chain
     * chain}, or responds with {@code NOTIMP} if none claim the message.
     *
     * @param query the parsed DNS message
     * @param loop the selector loop for any outbound work, or {@code null}
     * @param callback receives the response when this handler claims the
     *                 message
     * @return {@code true} if this handler claimed the message
     */
    default boolean handleNonQueryOpcode(DnsMessage query, SelectorLoop loop,
                                         DnsQueryCallback callback) {
        return false;
    }

    /**
     * Initialises handler resources. Called from {@link DnsServer#start(Gumdrop)}.
     *
     * @param gumdrop the runtime the owning {@link DnsServer} is starting under
     */
    default void start(Gumdrop gumdrop) {
        start();
    }

    /**
     * Initialises handler resources, using no runtime reference. Prefer
     * {@link #start(Gumdrop)}; overridden by handlers that don't need one.
     */
    default void start() {
    }

    /**
     * Releases handler resources. Called from {@link DnsServer#stop()}.
     */
    default void stop() {
    }

}
