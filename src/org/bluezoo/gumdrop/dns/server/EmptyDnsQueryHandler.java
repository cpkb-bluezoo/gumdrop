/*
 * EmptyDnsQueryHandler.java
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

import java.util.Collections;

/**
 * Default DNS application handler — {@code NOERROR} with an empty answer
 * section (no upstream relay, no zone data).
 *
 * @see DnsQueryHandlers#empty()
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EmptyDnsQueryHandler implements DnsQueryHandler {

    /** Shared stateless instance. */
    public static final EmptyDnsQueryHandler INSTANCE = new EmptyDnsQueryHandler();

    private EmptyDnsQueryHandler() {
    }

    @Override
    public void handleQuery(DnsMessage query, SelectorLoop loop,
                            DnsQueryCallback callback) {
        callback.onResponse(query.createResponse(
                Collections.<org.bluezoo.gumdrop.dns.DnsResourceRecord>emptyList()));
    }

}
