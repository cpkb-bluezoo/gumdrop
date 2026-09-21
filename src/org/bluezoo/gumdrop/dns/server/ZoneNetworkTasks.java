/*
 * ZoneNetworkTasks.java
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
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsZoneClient;

import java.net.InetSocketAddress;
import java.text.MessageFormat;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Non-blocking zone maintenance over the network (AXFR refresh, NOTIFY) on
 * the {@link SelectorLoop} reactor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ZoneNetworkTasks {

    private static final Logger LOGGER = Logger.getLogger(ZoneNetworkTasks.class.getName());

    private ZoneNetworkTasks() {
    }

    static void refreshFromMasterAsync(SelectorLoop loop, final MutableZone zone,
            final InetSocketAddress master, final Runnable onSuccess) {
        if (loop == null || master == null || zone == null) {
            return;
        }
        DnsZoneClient.axfrOverTcp(loop, master, zone.getOrigin(),
                DnsZoneClient.DEFAULT_TIMEOUT_MS, null, null,
                new DnsZoneClient.TransferCallback() {
                    @Override
                    public void onSuccess(List<DnsResourceRecord> records) {
                        zone.replaceFromAxfr(records);
                        if (onSuccess != null) {
                            onSuccess.run();
                        }
                    }

                    @Override
                    public void onFailure(Exception error) {
                        LOGGER.log(Level.FINE, MessageFormat.format(
                                DnsServer.L10N.getString("fine.zone_axfr_refresh_failed"),
                                master), error);
                    }
                });
    }

    static void notifyPeersAsync(SelectorLoop loop, final String origin,
            final List<InetSocketAddress> peers) {
        if (loop == null || peers == null || peers.isEmpty()) {
            return;
        }
        for (int i = 0; i < peers.size(); i++) {
            final InetSocketAddress peer = peers.get(i);
            if (peer == null) {
                continue;
            }
            DnsZoneClient.sendNotify(loop, peer, origin,
                    new DnsZoneClient.MessageCallback() {
                        @Override
                        public void onSuccess(DnsMessage ignored) {
                        }

                        @Override
                        public void onFailure(Exception error) {
                            LOGGER.log(Level.FINE, MessageFormat.format(
                                    DnsServer.L10N.getString(
                                            "fine.zone_notify_peer_failed"),
                                    peer), error);
                        }
                    });
        }
    }
}
