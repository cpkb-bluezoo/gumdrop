/*
 * ZoneFilePersistenceTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ZoneFilePersistenceTest {

    @Before
    public void clearObserver() {
        StorageExecutor.workThreadObserver = null;
    }

    @After
    public void tearDownObserver() {
        StorageExecutor.workThreadObserver = null;
    }

    @Test
    public void testUpdatePersistsWhenReadWrite() throws Exception {
        Path zone = Files.createTempFile("persist", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n");
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        try {
            AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                    .zoneFile(zone, ZoneFileAccessMode.READ_WRITE)
                    .deferZoneFileLoad(false)
                    .notifyFromNsRecords(false)
                    .build();
            handler.start(gumdrop);

            final AtomicBoolean savedOnStorage = new AtomicBoolean(false);
            StorageExecutor.workThreadObserver =
                    new StorageExecutor.WorkThreadObserver() {
                        @Override
                        public void observed(Thread thread) {
                            savedOnStorage.set(true);
                        }
                    };

            MutableZone mutable = handler.getZone();
            DnsResourceRecord zoneSoa = mutable.getSoaRecord();
            DnsResourceRecord add = DnsResourceRecord.a("new.example.com.", 300,
                    InetAddress.getByName("192.0.2.9"));
            DnsMessage update = DnsMessage.createDynamicUpdate(42,
                    Collections.singletonList(zoneSoa),
                    Collections.<DnsResourceRecord>emptyList(),
                    Collections.singletonList(add));

            final CountDownLatch latch = new CountDownLatch(1);
            handler.handleNonQueryOpcode(update, gumdrop.nextWorkerLoop(),
                    new DnsQueryCallback() {
                        @Override
                        public void onResponse(DnsMessage response) {
                            latch.countDown();
                        }

                        @Override
                        public void onError(String error) {
                            latch.countDown();
                        }
                    });
            assertTrue(latch.await(10, TimeUnit.SECONDS));

            for (int i = 0; i < 50; i++) {
                if (savedOnStorage.get()) {
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue(savedOnStorage.get());

            ZoneFile reloaded = null;
            for (int i = 0; i < 50; i++) {
                reloaded = ZoneFile.load(zone);
                if (reloaded.asMutable().getSerial() == 2) {
                    break;
                }
                Thread.sleep(100);
            }
            assertEquals(2, reloaded.asMutable().getSerial());
            assertEquals(ZoneLookupResult.STATUS_ANSWER,
                    reloaded.lookup("new.example.com.", DnsType.A).getStatus());
        } finally {
            gumdrop.shutdown();
            gumdrop.join();
            Files.deleteIfExists(zone);
        }
    }

    @Test
    public void testReadOnlySkipsDiskWrite() throws Exception {
        Path zone = Files.createTempFile("ro", ".zone");
        String original = ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n";
        Files.writeString(zone, original);
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        try {
            AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                    .zoneFile(zone, ZoneFileAccessMode.READ_ONLY)
                    .deferZoneFileLoad(false)
                    .notifyFromNsRecords(false)
                    .build();
            handler.start(gumdrop);

            final AtomicBoolean storageWork = new AtomicBoolean(false);
            StorageExecutor.workThreadObserver =
                    new StorageExecutor.WorkThreadObserver() {
                        @Override
                        public void observed(Thread thread) {
                            storageWork.set(true);
                        }
                    };

            MutableZone mutable = handler.getZone();
            DnsMessage update = DnsMessage.createDynamicUpdate(43,
                    Collections.singletonList(mutable.getSoaRecord()),
                    Collections.<DnsResourceRecord>emptyList(),
                    Collections.singletonList(DnsResourceRecord.a(
                            "skip.example.com.", 300,
                            InetAddress.getByName("192.0.2.10"))));

            final CountDownLatch latch = new CountDownLatch(1);
            handler.handleNonQueryOpcode(update, gumdrop.nextWorkerLoop(),
                    new DnsQueryCallback() {
                        @Override
                        public void onResponse(DnsMessage response) {
                            latch.countDown();
                        }

                        @Override
                        public void onError(String error) {
                            latch.countDown();
                        }
                    });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(300);
            assertFalse(storageWork.get());
            assertEquals(original, Files.readString(zone));
        } finally {
            gumdrop.shutdown();
            gumdrop.join();
            Files.deleteIfExists(zone);
        }
    }
}
