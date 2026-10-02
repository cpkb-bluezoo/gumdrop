/*
 * ZoneFilePersistenceTest.java
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
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Dynamic updates are persisted through the storage executor when the zone
 * file is read-write, and not at all when it is read-only. Uses an in-memory
 * file system and a thread-free Gumdrop whose queued executor stands in for
 * the storage pool.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ZoneFilePersistenceTest {

    private static final String ZONE = ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n";

    private static DnsMessage addRecord(MutableZone mutable, int id, String name, String ip)
            throws Exception {
        return DnsMessage.createDynamicUpdate(id,
                Collections.singletonList(mutable.getSoaRecord()),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(DnsResourceRecord.a(name, 300,
                        InetAddress.getByName(ip))));
    }

    private static final class Flag implements DnsQueryCallback {
        final AtomicBoolean answered = new AtomicBoolean(false);

        @Override
        public void onResponse(DnsMessage response) {
            answered.set(true);
        }

        @Override
        public void onError(String error) {
            answered.set(true);
        }
    }

    @Test
    public void testUpdatePersistsWhenReadWrite() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        Path zone = mem.getPath("/zones/persist.zone");
        Files.createDirectories(zone.getParent());
        Files.writeString(zone, ZONE);
        TestGumdrop.QueuedExecutor work = new TestGumdrop.QueuedExecutor();
        Gumdrop gumdrop = TestGumdrop.create(work);
        AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                .zoneFile(zone, ZoneFileAccessMode.READ_WRITE)
                .deferZoneFileLoad(false)
                .notifyFromNsRecords(false)
                .build();
        handler.start(gumdrop);

        MutableZone mutable = handler.getZone();
        DnsMessage update = addRecord(mutable, 42, "new.example.com.", "192.0.2.9");
        Flag flag = new Flag();
        handler.handleNonQueryOpcode(update, gumdrop.nextWorkerLoop(), flag);
        assertTrue("the save must be queued on the storage executor", work.pendingCount() > 0);
        work.runAll();
        assertTrue(flag.answered.get());

        ZoneFile reloaded = ZoneFile.load(zone);
        assertEquals(2, reloaded.asMutable().getSerial());
        assertEquals(ZoneLookupResult.STATUS_ANSWER,
                reloaded.lookup("new.example.com.", DnsType.A).getStatus());
    }

    @Test
    public void testReadOnlySkipsDiskWrite() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        Path zone = mem.getPath("/zones/ro.zone");
        Files.createDirectories(zone.getParent());
        Files.writeString(zone, ZONE);
        TestGumdrop.QueuedExecutor work = new TestGumdrop.QueuedExecutor();
        Gumdrop gumdrop = TestGumdrop.create(work);
        AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                .zoneFile(zone, ZoneFileAccessMode.READ_ONLY)
                .deferZoneFileLoad(false)
                .notifyFromNsRecords(false)
                .build();
        handler.start(gumdrop);

        MutableZone mutable = handler.getZone();
        DnsMessage update = addRecord(mutable, 43, "skip.example.com.", "192.0.2.10");
        Flag flag = new Flag();
        handler.handleNonQueryOpcode(update, gumdrop.nextWorkerLoop(), flag);
        assertTrue(flag.answered.get());
        assertEquals("a read-only zone must not touch the storage executor", 0,
                work.pendingCount());
        assertEquals(ZONE, Files.readString(zone));
    }
}
