/*
 * MboxMailboxIntegrationTest.java
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

package org.bluezoo.gumdrop.mailbox.mbox;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Integration tests for {@link MboxMailbox}.
 *
 * <p>Integration test: queues two sessions on the JVM-wide gate from real
 * threads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MboxMailboxIntegrationTest {

    private Path tempDir;
    private Path mboxFile;

    private static final String SAMPLE_MBOX =
            "From sender@example.com Mon Jan  1 00:00:00 2025\r\n" +
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: First message\r\n" +
            "\r\n" +
            "Hello, this is message one.\r\n" +
            "\r\n" +
            "From another@example.com Tue Jan  2 00:00:00 2025\r\n" +
            "From: another@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Second message\r\n" +
            "\r\n" +
            "Hello, this is message two.\r\n";

    @Before
    public void setUp() throws IOException {
        tempDir = MemoryFileSystem.create().getPath("/mbox");
        Files.createDirectories(tempDir);
        mboxFile = tempDir.resolve("test.mbox");
    }

    @After
    public void tearDown() {
        // JVM-global test hook: never leak it into another test.
        MboxMailbox.beforeJvmGateAcquire = null;
    }

    private MboxMailbox openSampleMailbox(boolean readOnly) throws IOException {
        Files.write(mboxFile, SAMPLE_MBOX.getBytes(StandardCharsets.US_ASCII));
        return new MboxMailbox(mboxFile, "test", readOnly);
    }

    // Regression test for issue #135: two MboxMailbox instances on the same
    // file in the same JVM used to race straight to the OS-level FileLock
    // and the second one would throw OverlappingFileLockException instead
    // of blocking/queueing. A second open on a background thread must now
    // block until the first session closes, then succeed - not fail.
    @Test(timeout = 10000)
    public void testConcurrentSameJvmSessionsQueueInsteadOfCrashing()
            throws Exception {
        MboxMailbox first = openSampleMailbox(true);

        final java.util.concurrent.CountDownLatch secondStarted =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch secondAtGate =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<MboxMailbox> secondRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> secondError =
                new java.util.concurrent.atomic.AtomicReference<>();

        MboxMailbox.beforeJvmGateAcquire = new Runnable() {
            @Override
            public void run() {
                secondAtGate.countDown();
            }
        };
        try {
            Thread opener = new Thread(new Runnable() {
                @Override
                public void run() {
                    secondStarted.countDown();
                    try {
                        secondRef.set(new MboxMailbox(mboxFile, "test", true));
                    } catch (Throwable t) {
                        secondError.set(t);
                    }
                }
            });
            opener.start();

            assertTrue(secondStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue("second open must be blocked on the JVM gate, not have "
                            + "failed or returned",
                    secondAtGate.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue("second open must still be blocked behind the first "
                            + "session, not have failed or returned",
                    opener.isAlive());

            first.close(false);
            opener.join(5000);

            assertNull("second open must not have thrown "
                            + "OverlappingFileLockException or any other error",
                    secondError.get());
            MboxMailbox second = secondRef.get();
            assertNotNull("second open must have succeeded once the first "
                            + "session closed", second);
            try {
                assertEquals(2, second.getMessageCount());
            } finally {
                second.close(false);
            }
        } finally {
            MboxMailbox.beforeJvmGateAcquire = null;
        }
    }
}
