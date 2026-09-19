/*
 * ZoneFileWriterTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.StorageExecutor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ZoneFileWriterTest {

    @Before
    public void clearObserver() {
        StorageExecutor.workThreadObserver = null;
    }

    @After
    public void tearDownObserver() {
        StorageExecutor.workThreadObserver = null;
    }

    @Test
    public void testRoundTripOnStorageThread() throws Exception {
        Path source = Files.createTempFile("zf-src", ".zone");
        Path target = Files.createTempFile("zf-out", ".zone");
        Files.writeString(source, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 3600\n"
                + "@ IN SOA ns1.example.com. admin.example.com. 1 3600 1800 86400 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n"
                + "www IN A 192.0.2.1\n");
        try {
            MutableZone zone = ZoneFile.load(source).asMutable();

            Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
            try {
                final AtomicBoolean onStorage = new AtomicBoolean(false);
                StorageExecutor.workThreadObserver =
                        new StorageExecutor.WorkThreadObserver() {
                            @Override
                            public void observed(Thread thread) {
                                onStorage.set(true);
                            }
                        };
                final CountDownLatch done = new CountDownLatch(1);
                final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
                ZoneStorage.saveAsync(gumdrop.getStorageExecutor(), target, zone,
                        gumdrop.nextWorkerLoop(),
                        new StorageExecutor.Callback<Void>() {
                            @Override
                            public void completed(Void result) {
                                done.countDown();
                            }

                            @Override
                            public void failed(Throwable t) {
                                error.set(t);
                                done.countDown();
                            }
                        });
                assertTrue(done.await(10, TimeUnit.SECONDS));
                assertNull(error.get());
                assertTrue(onStorage.get());

                ZoneFile reloaded = ZoneFile.load(target);
                assertEquals(1, reloaded.asMutable().getSerial());
                assertEquals(1, reloaded.lookup("www.example.com.",
                        org.bluezoo.gumdrop.dns.DnsType.A).getAnswers().size());
            } finally {
                gumdrop.shutdown();
                gumdrop.join();
            }
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(target);
        }
    }
}
