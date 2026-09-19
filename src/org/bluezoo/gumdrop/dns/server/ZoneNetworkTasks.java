/*
 * ZoneNetworkTasks.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.dns.client.DnsZoneOperations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Blocking zone maintenance over the network (AXFR refresh, NOTIFY), offloaded
 * from {@link org.bluezoo.gumdrop.SelectorLoop} threads.
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
                LOGGER.log(Level.FINE, "AXFR refresh from " + master + " failed", error);
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
                        LOGGER.log(Level.FINE, "NOTIFY to " + peer + " failed", e);
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
                LOGGER.log(Level.FINE, "NOTIFY batch failed", error);
            }
        });
    }
}
