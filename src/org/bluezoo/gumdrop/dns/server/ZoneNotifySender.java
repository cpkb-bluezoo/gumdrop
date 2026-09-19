/*
 * ZoneNotifySender.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.client.DnsZoneOperations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends RFC 1996 NOTIFY to configured secondaries after zone changes.
 */
final class ZoneNotifySender {

    private static final Logger LOGGER = Logger.getLogger(ZoneNotifySender.class.getName());

    private ZoneNotifySender() {
    }

    static void notifySecondaries(MutableZone zone,
                                  List<InetSocketAddress> peers) {
        if (peers == null || peers.isEmpty()) {
            return;
        }
        String origin = zone.getOrigin();
        for (int i = 0; i < peers.size(); i++) {
            InetSocketAddress peer = peers.get(i);
            try {
                DnsZoneOperations.sendNotify(peer, origin);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "NOTIFY to " + peer + " failed", e);
            }
        }
    }
}
