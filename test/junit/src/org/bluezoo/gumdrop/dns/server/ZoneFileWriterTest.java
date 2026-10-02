/*
 * ZoneFileWriterTest.java
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
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Zone file saving is offloaded to the storage executor. Runs on an
 * in-memory file system and a thread-free Gumdrop whose queued executor
 * stands in for the storage pool.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ZoneFileWriterTest {

    @Test
    public void testRoundTripIsOffloadedToStorageExecutor() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        Path source = mem.getPath("/zones/src.zone");
        Path target = mem.getPath("/zones/out.zone");
        Files.createDirectories(source.getParent());
        Files.writeString(source, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 3600\n"
                + "@ IN SOA ns1.example.com. admin.example.com. 1 3600 1800 86400 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n"
                + "www IN A 192.0.2.1\n");
        MutableZone zone = ZoneFile.load(source).asMutable();

        TestGumdrop.QueuedExecutor work = new TestGumdrop.QueuedExecutor();
        Gumdrop gumdrop = TestGumdrop.create(work);
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        ZoneStorage.saveAsync(gumdrop.getStorageExecutor(), target, zone,
                gumdrop.nextWorkerLoop(),
                new StorageExecutor.Callback<Void>() {
                    @Override
                    public void completed(Void result) {
                        done.set(true);
                    }

                    @Override
                    public void failed(Throwable t) {
                        error.set(t);
                        done.set(true);
                    }
                });
        assertEquals("the save must be queued on the storage executor", 1, work.pendingCount());
        assertFalse(done.get());
        assertFalse(Files.exists(target));
        work.runAll();
        assertTrue(done.get());
        assertNull(error.get());

        ZoneFile reloaded = ZoneFile.load(target);
        assertEquals(1, reloaded.asMutable().getSerial());
        assertEquals(1, reloaded.lookup("www.example.com.",
                org.bluezoo.gumdrop.dns.DnsType.A).getAnswers().size());
    }
}
