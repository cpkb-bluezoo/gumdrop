/*
 * LegacyResolveDnsHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;

/**
 * Bridges {@link DnsServer#resolve(DnsMessage)} subclasses to the handler SPI.
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
