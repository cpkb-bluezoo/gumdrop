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
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.dns.client.DnsZoneOperations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Blocking zone maintenance over the network (AXFR refresh, NOTIFY), offloaded
 * from {@link org.bluezoo.gumdrop.SelectorLoop} threads.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ZoneNetworkTasks {

    private static final Logger LOGGER = Logger.getLogger(ZoneNetworkTasks.class.getName());

    private ZoneNetworkTasks() {
    }

    static void refreshFromMasterAsync(StorageExecutor executor, SelectorLoop loop,
                                       final MutableZone zone,
                                       final InetSocketAddress master,
                                       final Runnable onSuccess) {
        if (executor == null || master == null || zone == null) {
            return;
        }
        executor.submit(ZoneStorage.loopDispatcher(loop), new Callable<Void>() {
            @Override
            public Void call() throws IOException {
                List<org.bluezoo.gumdrop.dns.DnsResourceRecord> records =
                        DnsZoneOperations.axfrOverTcp(master, zone.getOrigin());
                zone.replaceFromAxfr(records);
                return null;
            }
        }, new StorageExecutor.Callback<Void>() {
            @Override
            public void completed(Void result) {
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.FINE, MessageFormat.format(
                        DnsServer.L10N.getString("fine.zone_axfr_refresh_failed"),
                        master), error);
            }
        });
    }

    static void notifyPeersAsync(StorageExecutor executor, SelectorLoop loop,
                                 final String origin,
                                 final List<InetSocketAddress> peers) {
        if (executor == null || peers == null || peers.isEmpty()) {
            return;
        }
        executor.submit(ZoneStorage.loopDispatcher(loop), new Callable<Void>() {
            @Override
            public Void call() {
                for (int i = 0; i < peers.size(); i++) {
                    InetSocketAddress peer = peers.get(i);
                    try {
                        DnsZoneOperations.sendNotify(peer, origin);
                    } catch (IOException e) {
                        LOGGER.log(Level.FINE, MessageFormat.format(
                                DnsServer.L10N.getString("fine.zone_notify_peer_failed"),
                                peer), e);
                    }
                }
                return null;
            }
        }, new StorageExecutor.Callback<Void>() {
            @Override
            public void completed(Void result) {
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.FINE,
                        DnsServer.L10N.getString("fine.zone_notify_batch_failed"),
                        error);
            }
        });
    }
}
