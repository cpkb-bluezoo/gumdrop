/*
 * DnsQueryHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;

/**
 * Application logic for a {@link DnsServer} — resolves or declines DNS queries.
 *
 * <p>The {@link DnsServer} protocol shell (listeners, validation, cookies,
 * MQTYPE merging) delegates to a handler after parsing each query. Stock
 * implementations include {@link EmptyDnsQueryHandler},
 * {@link UpstreamRelayHandler}, and {@link AuthoritativeZoneHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DnsQueryHandlers
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
     * Initialises handler resources. Called from {@link DnsServer#start()}.
     */
    default void start() {
    }

    /**
     * Releases handler resources. Called from {@link DnsServer#stop()}.
     */
    default void stop() {
    }

}
