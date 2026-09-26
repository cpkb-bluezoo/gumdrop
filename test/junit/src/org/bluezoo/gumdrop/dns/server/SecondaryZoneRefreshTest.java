/*
 * SecondaryZoneRefreshTest.java
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
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsZoneClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #500: secondaries refresh on the SOA timers and refuse updates;
 * NOTIFY targets given by name reach every replica.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SecondaryZoneRefreshTest {

    private static final String ORIGIN = "example.com.";
    private static final InetSocketAddress MASTER = new InetSocketAddress("192.0.2.53", 53);
    private static final int REFRESH = 900;
    private static final int RETRY = 300;
    private static final int EXPIRE = 3600;

    private Path zoneFile;
    private final FakeClient client = new FakeClient();
    private final ManualScheduler scheduler = new ManualScheduler();
    private long clock = 1_000_000L;

    @Before
    public void writeZone() throws Exception {
        zoneFile = Files.createTempFile("secondary", ".zone");
        Files.writeString(zoneFile, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 " + REFRESH + " " + RETRY + " " + EXPIRE + " 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n");
    }

    @After
    public void cleanUp() throws Exception {
        Files.deleteIfExists(zoneFile);
    }

    private AuthoritativeZoneHandler secondary() throws Exception {
        AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                .zone(ZoneFile.load(zoneFile).asMutable())
                .slaveOf(ORIGIN, MASTER)
                .notifyFromNsRecords(false)
                .build();
        handler.setMasterClient(client);
        handler.setRefreshScheduler(scheduler);
        handler.refresher().setClock(new java.util.function.LongSupplier() {
            @Override
            public long getAsLong() {
                return clock;
            }
        });
        return handler;
    }

    private static List<DnsResourceRecord> transfer(int serial) throws Exception {
        DnsResourceRecord soa = DnsResourceRecord.soa(ORIGIN, 300, "ns1.example.com.", "host.example.com.",
                serial, REFRESH, RETRY, EXPIRE, 300);
        return Arrays.asList(soa,
                DnsResourceRecord.a("new.example.com.", 300, InetAddress.getByName("192.0.2.77")),
                soa);
    }

    private static int rcodeOf(AuthoritativeZoneHandler handler, DnsMessage query) {
        final AtomicReference<DnsMessage> response = new AtomicReference<DnsMessage>();
        handler.handleQuery(query, null, null, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage message) {
                response.set(message);
            }

            @Override
            public void onError(String error) {
            }
        });
        return response.get().getRcode();
    }

    private static int lookupRcode(AuthoritativeZoneHandler handler, String name) {
        return rcodeOf(handler, DnsMessage.createQuery(7, name, DnsType.A));
    }

    private static DnsMessage respondToNonQuery(AuthoritativeZoneHandler handler, DnsMessage message) {
        final AtomicReference<DnsMessage> response = new AtomicReference<DnsMessage>();
        handler.handleNonQueryOpcode(message, null, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage r) {
                response.set(r);
            }

            @Override
            public void onError(String error) {
            }
        });
        return response.get();
    }

    private DnsMessage update() throws Exception {
        MutableZone zone = ZoneFile.load(zoneFile).asMutable();
        return DnsMessage.createDynamicUpdate(42, Collections.singletonList(zone.getSoaRecord()),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(DnsResourceRecord.a("upd.example.com.", 300,
                        InetAddress.getByName("192.0.2.9"))));
    }

    @Test
    public void secondaryRefusesUpdatesWithoutNotifying() throws Exception {
        AuthoritativeZoneHandler handler = secondary();
        client.peersResolved = new InetAddress[0];
        DnsMessage response = respondToNonQuery(handler, update());
        assertEquals(DnsMessage.RCODE_REFUSED, response.getRcode());
        assertEquals(1, handler.getZone().getSerial());
        assertTrue(client.notifies.isEmpty());
    }

    @Test
    public void startTransfersWhenMasterIsAhead() throws Exception {
        AuthoritativeZoneHandler handler = secondary();
        client.masterSerial = 5;
        client.transferRecords = transfer(5);
        handler.beginSecondaryRefresh(null);
        assertEquals(1, client.transfers);
        assertEquals(5, handler.getZone().getSerial());
        assertEquals(DnsMessage.RCODE_NOERROR, lookupRcode(handler, "new.example.com."));
        assertEquals("the next check waits for the SOA refresh interval",
                REFRESH * 1000L, scheduler.lastDelay());
    }

    @Test
    public void startDoesNotTransferWhenSerialMatches() throws Exception {
        AuthoritativeZoneHandler handler = secondary();
        client.masterSerial = 1;
        handler.beginSecondaryRefresh(null);
        assertEquals(0, client.transfers);
        assertEquals(REFRESH * 1000L, scheduler.lastDelay());
    }

    @Test
    public void secondaryWithNoZoneTransfersFirstCopy() throws Exception {
        AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                .slaveOf(ORIGIN, MASTER)
                .notifyFromNsRecords(false)
                .build();
        handler.setMasterClient(client);
        handler.setRefreshScheduler(scheduler);
        assertTrue(handler.getZones().isEmpty());
        client.masterSerial = 5;
        client.transferRecords = transfer(5);
        handler.beginSecondaryRefresh(null);
        assertEquals(1, client.transfers);
        assertEquals(5, handler.getZone().getSerial());
    }

    @Test
    public void failedTransferRetriesOnSoaRetryInterval() throws Exception {
        AuthoritativeZoneHandler handler = secondary();
        client.masterSerial = 5;
        client.transferFails = true;
        handler.beginSecondaryRefresh(null);
        assertEquals(RETRY * 1000L, scheduler.lastDelay());
        assertEquals(1, handler.getZone().getSerial());

        client.transferFails = false;
        client.transferRecords = transfer(5);
        scheduler.runLast();
        assertEquals(2, client.transfers);
        assertEquals(5, handler.getZone().getSerial());
        assertEquals(REFRESH * 1000L, scheduler.lastDelay());
    }

    @Test
    public void unreachableMasterExpiresTheZone() throws Exception {
        AuthoritativeZoneHandler handler = secondary();
        client.soaFails = true;
        handler.beginSecondaryRefresh(null);
        assertEquals(RETRY * 1000L, scheduler.lastDelay());
        assertEquals(DnsMessage.RCODE_NOERROR, lookupRcode(handler, "ns1.example.com."));

        clock += EXPIRE * 1000L;
        scheduler.runLast();
        assertTrue(handler.refresher().isExpired(ORIGIN));
        assertEquals("an expired zone is not answered", DnsMessage.RCODE_SERVFAIL,
                lookupRcode(handler, "ns1.example.com."));

        client.soaFails = false;
        client.masterSerial = 6;
        client.transferRecords = transfer(6);
        scheduler.runLast();
        assertFalse(handler.refresher().isExpired(ORIGIN));
        assertEquals(DnsMessage.RCODE_NOERROR, lookupRcode(handler, "new.example.com."));
    }

    @Test
    public void notifyTransfersAtOnce() throws Exception {
        AuthoritativeZoneHandler handler = secondary();
        client.masterSerial = 1;
        handler.beginSecondaryRefresh(null);
        assertEquals(0, client.transfers);

        client.masterSerial = 9;
        client.transferRecords = transfer(9);
        DnsMessage response = respondToNonQuery(handler, DnsMessage.createNotify(11, ORIGIN));
        assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
        assertEquals(1, client.transfers);
        assertEquals(9, handler.getZone().getSerial());
    }

    @Test
    public void notifyNameWithTwoAddressesSendsTwoPackets() throws Exception {
        client.peersResolved = new InetAddress[] {
            InetAddress.getByName("192.0.2.1"), InetAddress.getByName("192.0.2.2") };
        AuthoritativeZoneHandler handler = primaryNotifying();
        respondToNonQuery(handler, update());
        assertEquals(2, client.notifies.size());
        assertTrue(client.notifies.contains(new InetSocketAddress("192.0.2.1", 5353)));
        assertTrue(client.notifies.contains(new InetSocketAddress("192.0.2.2", 5353)));
    }

    @Test
    public void notifyNameWithOneAddressSendsOnePacket() throws Exception {
        client.peersResolved = new InetAddress[] { InetAddress.getByName("192.0.2.1") };
        AuthoritativeZoneHandler handler = primaryNotifying();
        respondToNonQuery(handler, update());
        assertEquals(1, client.notifies.size());
    }

    private AuthoritativeZoneHandler primaryNotifying() throws Exception {
        AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                .zone(ZoneFile.load(zoneFile).asMutable())
                .notifyPeer("replicas.example.svc", 5353)
                .notifyFromNsRecords(false)
                .build();
        handler.setMasterClient(client);
        return handler;
    }

    // ── Test doubles ──

    private static final class FakeClient implements ZoneMasterClient {
        int masterSerial;
        boolean soaFails;
        boolean transferFails;
        List<DnsResourceRecord> transferRecords;
        int transfers;
        InetAddress[] peersResolved = new InetAddress[0];
        final List<InetSocketAddress> notifies = new ArrayList<InetSocketAddress>();

        @Override
        public void querySoaSerial(SelectorLoop loop, InetSocketAddress master, String origin,
                SerialCallback callback) {
            if (soaFails) {
                callback.onFailure(new IOException("unreachable"));
            } else {
                callback.onSerial(masterSerial);
            }
        }

        @Override
        public void transfer(SelectorLoop loop, InetSocketAddress master, String origin,
                DnsZoneClient.TransferCallback callback) {
            transfers++;
            if (transferFails) {
                callback.onFailure(new IOException("refused"));
            } else {
                callback.onSuccess(transferRecords);
            }
        }

        @Override
        public void notify(SelectorLoop loop, InetSocketAddress peer, String origin) {
            notifies.add(peer);
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return peersResolved;
        }
    }

    private static final class ManualScheduler implements SecondaryZoneRefresher.Scheduler {
        private final List<long[]> delays = new ArrayList<long[]>();
        private final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public void schedule(long delayMs, Runnable task) {
            delays.add(new long[] { delayMs });
            tasks.add(task);
        }

        @Override
        public void shutdown() {
        }

        long lastDelay() {
            return delays.get(delays.size() - 1)[0];
        }

        void runLast() {
            tasks.get(tasks.size() - 1).run();
        }
    }
}
