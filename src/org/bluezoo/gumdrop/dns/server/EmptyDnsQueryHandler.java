/*
 * EmptyDnsQueryHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
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
