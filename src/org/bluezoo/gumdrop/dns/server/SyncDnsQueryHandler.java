/*
 * SyncDnsQueryHandler.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQueryContext;

/**
 * Base class for synchronous (non-blocking) query policy on the selector thread.
 *
 * <p>Return {@code null} from {@link #resolveQuery} to produce an empty
 * {@code NOERROR} (via {@link EmptyDnsQueryHandler}), for example when chained
 * ahead of {@link UpstreamRelayHandler} with {@link DnsQueryHandlers#chain}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DnsQueryHandlers
 */
public abstract class SyncDnsQueryHandler implements DnsQueryHandler {

    @Override
    public final void handleQuery(DnsMessage query, SelectorLoop loop,
                                  DnsQueryCallback callback) {
        respond(resolveQuery(query), query, loop, callback);
    }

    @Override
    public void handleQuery(DnsMessage query, SelectorLoop loop,
                            DnsQueryContext context, DnsQueryCallback callback) {
        respond(resolveQuery(query, context), query, loop, callback);
    }

    private static void respond(DnsMessage response, DnsMessage query,
                                SelectorLoop loop, DnsQueryCallback callback) {
        if (response != null) {
            callback.onResponse(response);
        } else {
            EmptyDnsQueryHandler.INSTANCE.handleQuery(query, loop, callback);
        }
    }

    /**
     * Builds a response for {@code query}, or {@code null} for empty NOERROR.
     */
    protected abstract DnsMessage resolveQuery(DnsMessage query);

    /**
     * Builds a response for {@code query} knowing the client connection it
     * arrived on, or {@code null} for empty NOERROR. The server calls this
     * overload; override it to vary answers per client. The default
     * delegates to {@link #resolveQuery(DnsMessage)}.
     *
     * @param query the query
     * @param context the connection the query arrived on
     */
    protected DnsMessage resolveQuery(DnsMessage query, DnsQueryContext context) {
        return resolveQuery(query);
    }
}
