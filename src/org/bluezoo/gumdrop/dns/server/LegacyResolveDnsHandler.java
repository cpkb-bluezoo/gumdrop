/*
 * LegacyResolveDnsHandler.java
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

/**
 * Bridges {@link DnsServer#resolve(DnsMessage)} subclasses to the handler SPI.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class LegacyResolveDnsHandler implements DnsQueryHandler {

    private final DnsServer server;

    LegacyResolveDnsHandler(DnsServer server) {
        this.server = server;
    }

    @Override
    public void handleQuery(DnsMessage query, SelectorLoop loop,
                            DnsQueryCallback callback) {
        DnsMessage response = server.resolve(query);
        if (response != null) {
            callback.onResponse(response);
        } else {
            EmptyDnsQueryHandler.INSTANCE.handleQuery(query, loop, callback);
        }
    }

}
