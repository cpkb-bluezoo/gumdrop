/*
 * AuthoritativeZoneHandlerStorageTest.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsZoneClient;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
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

import static org.junit.Assert.*;

/**
 * Zone loading and secondary installation through the storage executor in
 * {@link AuthoritativeZoneHandler}, using an in-memory file system, a
 * thread-free Gumdrop with a queued storage executor, and a mock master
 * client and refresh scheduler.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AuthoritativeZoneHandlerStorageTest {

    private static final String ORIGIN = "example.com.";
    private static final String OTHER = "example.net.";
    private static final InetSocketAddress MASTER = new InetSocketAddress("192.0.2.53", 53);

    private static final String ZONE = ""
            + "$ORIGIN example.com.\n"
            + "$TTL 300\n"
            + "@ IN SOA ns1.example.com. host.example.com. 1 900 300 3600 300\n"
            + "@ IN NS ns1.example.com.\n"
            + "ns1 IN A 127.0.0.1\n";

    /** Master client mock answering from fields. */
    private static final class MockClient implements ZoneMasterClient {
        int masterSerial;
        List<DnsResourceRecord> records;
        int transfers;
        final List<String> transferred = new ArrayList<String>();

        @Override
        public void querySoaSerial(SelectorLoop loop, InetSocketAddress master, String origin,
                SerialCallback callback) {
            callback.onSerial(masterSerial);
        }

        @Override
        public void transfer(SelectorLoop loop, InetSocketAddress master, String origin,
                DnsZoneClient.TransferCallback callback) {
            transfers++;
            transferred.add(origin);
            callback.onSuccess(records);
        }

        @Override
        public void notify(SelectorLoop loop, InetSocketAddress peer, String origin) {
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return new InetAddress[0];
        }
    }

    /** Scheduler mock that records tasks without running them. */
    private static final class MockScheduler implements SecondaryZoneRefresher.Scheduler {
        final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public void schedule(long delayMs, Runnable task) {
            tasks.add(task);
        }

        @Override
        public void shutdown() {
        }
    }

    private static List<DnsResourceRecord> transfer(String origin, int serial) throws Exception {
        DnsResourceRecord soa = DnsResourceRecord.soa(origin, 300, "ns1." + origin, "host." + origin,
                serial, 900, 300, 3600, 300);
        return Arrays.asList(soa,
                DnsResourceRecord.a("new." + origin, 300, InetAddress.getByName("192.0.2.77")),
                soa);
    }

    private static int rcodeOf(AuthoritativeZoneHandler handler, String name) {
        final AtomicReference<DnsMessage> response = new AtomicReference<DnsMessage>();
        handler.handleQuery(DnsMessage.createQuery(7, name, DnsType.A), null, null,
                new DnsQueryCallback() {
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

    @Test
    public void testStartWithoutGumdropIsNoOp() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        Path zone = mem.getPath("/zones/a.zone");
        Files.createDirectories(zone.getParent());
        Files.writeString(zone, ZONE);
        AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                .zoneFile(zone).deferZoneFileLoad(true).build();
        handler.start((Gumdrop) null);
        assertTrue(handler.getZones().isEmpty());
        handler.stop();
    }

    @Test
    public void testDeferredZoneFileLoadsOnStorageExecutor() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        Path zone = mem.getPath("/zones/b.zone");
        Files.createDirectories(zone.getParent());
        Files.writeString(zone, ZONE);
        TestGumdrop.QueuedExecutor work = new TestGumdrop.QueuedExecutor();
        Gumdrop gumdrop = TestGumdrop.create(work);
        MockClient client = new MockClient();
        client.masterSerial = 1;
        MockScheduler scheduler = new MockScheduler();
        AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                .zoneFile(zone, ZoneFileAccessMode.READ_WRITE)
                .deferZoneFileLoad(true)
                .slaveOf(ORIGIN, MASTER)
                .notifyFromNsRecords(false)
                .build();
        handler.setMasterClient(client);
        handler.setRefreshScheduler(scheduler);
        handler.start(gumdrop);
        assertTrue(work.pendingCount() > 0);
        work.runAll();
        assertEquals(1, handler.getZones().size());
        assertEquals(ORIGIN, handler.getZone().getOrigin());
        assertEquals(0, client.transfers);
        assertFalse("the refresh timer was armed after the load", scheduler.tasks.isEmpty());
        assertEquals(0, rcodeOf(handler, "ns1.example.com."));
    }

    @Test
    public void testMissingFileSecondaryTransfersAndPersistsFirstCopy() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        Path zone = mem.getPath("/zones/missing.zone");
        Files.createDirectories(zone.getParent());
        TestGumdrop.QueuedExecutor work = new TestGumdrop.QueuedExecutor();
        Gumdrop gumdrop = TestGumdrop.create(work);
        MockClient client = new MockClient();
        client.masterSerial = 4;
        client.records = transfer(ORIGIN, 4);
        MockScheduler scheduler = new MockScheduler();
        AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                .zoneFile(zone, ZoneFileAccessMode.READ_WRITE)
                .deferZoneFileLoad(true)
                .slaveOf(ORIGIN, MASTER)
                .notifyFromNsRecords(false)
                .build();
        handler.setMasterClient(client);
        handler.setRefreshScheduler(scheduler);
        handler.start(gumdrop);
        work.runAll();
        assertEquals(1, client.transfers);
        assertEquals(ORIGIN, client.transferred.get(0));
        assertEquals(4, handler.getZone().getSerial());
        work.runAll();
        assertTrue("the first copy was saved", Files.exists(zone));
        String saved = Files.readString(zone);
        assertTrue(saved.contains("new"));
    }

    @Test
    public void testMissingFileBindsFirstUnboundSecondaryOrigin() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        Path zone = mem.getPath("/zones/other.zone");
        Files.createDirectories(zone.getParent());
        TestGumdrop.QueuedExecutor work = new TestGumdrop.QueuedExecutor();
        Gumdrop gumdrop = TestGumdrop.create(work);
        MockClient client = new MockClient();
        client.masterSerial = 9;
        client.records = transfer(OTHER, 9);
        MockScheduler scheduler = new MockScheduler();
        Path source = mem.getPath("/zones/mem.zone");
        Files.writeString(source, ZONE);
        MutableZone inMemory = ZoneFile.load(source).asMutable();
        AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                .zone(inMemory)
                .zoneFile(zone, ZoneFileAccessMode.READ_ONLY)
                .deferZoneFileLoad(true)
                .slaveOf(ORIGIN, MASTER)
                .slaveOf(OTHER, MASTER)
                .notifyFromNsRecords(false)
                .build();
        handler.setMasterClient(client);
        handler.setRefreshScheduler(scheduler);
        handler.start(gumdrop);
        work.runAll();
        assertTrue(client.transferred.contains(OTHER));
        assertEquals(2, handler.getZones().size());
    }
}
