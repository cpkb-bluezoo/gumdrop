/*
 * RoleBasedQuotaManagerAsyncSaveTest.java
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

package org.bluezoo.gumdrop.quota;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.StorageExecutor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Regression coverage for issue #295: {@link RoleBasedQuotaManager}'s
 * {@code recordXxx} methods called {@code saveUserUsage} inline, a
 * synchronous {@code Properties} file write, on whatever thread called
 * them -- for a server processing many messages per user, a blocking disk
 * write on every single message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RoleBasedQuotaManagerAsyncSaveTest {

    private Path tempDir;
    private Gumdrop gumdrop;
    private RoleBasedQuotaManager manager;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("quota-async-save");
        StorageExecutor.workThreadObserver = null;
        System.setProperty("gumdrop.workers", "1");
        gumdrop = Gumdrop.getInstance();
        gumdrop.setDrainTimeoutMs(0);
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
        assertNotNull("StorageExecutor must exist after Gumdrop.start()",
                gumdrop.getStorageExecutor());

        manager = new RoleBasedQuotaManager();
        manager.setStorageDir(tempDir.toString());
        manager.setDefaultQuota("1GB");
    }

    @After
    public void tearDown() throws Exception {
        StorageExecutor.workThreadObserver = null;
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
        deleteRecursively(tempDir.toFile());
    }

    @Test(timeout = 20000)
    public void recordMessageAddedDoesNotWriteInlineOnTheCallersThread()
            throws Exception {
        final CountDownLatch writeStarted = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread worker) {
                writeStarted.countDown();
                try {
                    releaseWrite.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        // The call itself must return before the (artificially blocked)
        // write below ever completes.
        manager.recordMessageAdded("alice", 1024L);

        assertTrue("the write must be dispatched through StorageExecutor "
                + "-- the observer hook was never invoked, meaning the "
                + "write ran inline on the caller's thread instead",
                writeStarted.await(5, TimeUnit.SECONDS));

        File usageFile = new File(tempDir.toFile(), "alice.usage");
        assertFalse("usage file must not exist yet: the write is still "
                + "artificially blocked, so if the caller already "
                + "returned, it cannot have waited for it",
                usageFile.exists());

        releaseWrite.countDown();

        long deadline = System.currentTimeMillis() + 5000;
        while (!usageFile.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("usage file was never written after being released",
                usageFile.exists());

        Properties props = loadProps(usageFile);
        assertEquals("1024", props.getProperty("storage.used"));
        assertEquals("1", props.getProperty("message.count"));
    }

    @Test(timeout = 20000)
    public void updatesArrivingWhileASaveIsInFlightAreNotLost()
            throws Exception {
        final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        StorageExecutor.workThreadObserver = new StorageExecutor.WorkThreadObserver() {
            private boolean first = true;
            @Override
            public synchronized void observed(Thread worker) {
                if (first) {
                    first = false;
                    firstWriteStarted.countDown();
                    try {
                        releaseFirstWrite.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };

        manager.recordMessageAdded("bob", 100L);
        assertTrue("first write never started",
                firstWriteStarted.await(5, TimeUnit.SECONDS));

        // Both of these arrive while the first save for "bob" is still
        // blocked mid-flight.
        manager.recordMessageAdded("bob", 200L);
        manager.recordMessageAdded("bob", 300L);

        releaseFirstWrite.countDown();

        File usageFile = new File(tempDir.toFile(), "bob.usage");
        long deadline = System.currentTimeMillis() + 10000;
        Properties props = new Properties();
        while (System.currentTimeMillis() < deadline) {
            if (usageFile.exists()) {
                props = loadProps(usageFile);
                if ("3".equals(props.getProperty("message.count"))) {
                    break;
                }
            }
            Thread.sleep(20);
        }
        assertEquals("all three updates must eventually be reflected on disk, "
                + "not just the first",
                "3", props.getProperty("message.count"));
        assertEquals("600", props.getProperty("storage.used"));
    }

    private static Properties loadProps(File file) throws Exception {
        Properties props = new Properties();
        try (FileReader reader = new FileReader(file)) {
            props.load(reader);
        }
        return props;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        f.delete();
    }
}
