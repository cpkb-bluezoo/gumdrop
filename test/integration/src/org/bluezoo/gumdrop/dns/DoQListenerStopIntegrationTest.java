/*
 * DoQListenerStopIntegrationTest.java
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

package org.bluezoo.gumdrop.dns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.testsupport.RecordingSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.junit.After;
import org.junit.Test;

/**
 * {@link DoQListener#stop()} must not close its QUIC engines on the caller's thread:
 * engines are owned by their selector loop, so stop() hands the close to that
 * loop. Checked structurally with a loop that records what it is given: the
 * UDP socket is still bound after stop() returns, and is released only when
 * the recorded task runs.
 *
 * <p>Integration test: binds a real UDP port through a booted runtime and
 * checks it is released only when the owning loop runs the close.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQListenerStopIntegrationTest {

    private Gumdrop gumdrop;

    @After
    public void tearDown() {
        if (gumdrop != null) {
            gumdrop.shutdownNow();
        }
    }

    private static int freeUdpPort() throws IOException {
        DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        try {
            return s.getLocalPort();
        } finally {
            s.close();
        }
    }

    private static boolean portIsBound(int port) throws IOException {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return false;
        } catch (SocketException e) {
            return true;
        } finally {
            if (s != null) {
                s.close();
            }
        }
    }

    @Test
    public void stopHandsTheEngineCloseToItsLoop() throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        int port = freeUdpPort();
        RecordingSelectorLoop loop = new RecordingSelectorLoop();
        DoQListener listener = new DoQListener();
        listener.setServerCredentials(TestCertificates.ec256().credentials());
        listener.setPort(port);
        listener.addresses(InetAddress.getLoopbackAddress());
        listener.setSelectorLoop(loop);
        listener.start(gumdrop);
        assertTrue("engine bound", portIsBound(port));

        listener.stop();

        assertTrue("stop() must not close the engine on the caller's thread", portIsBound(port));
        assertEquals("one close task per engine is posted to the loop", 1, loop.recordedCount());
        loop.runRecorded();
        if (portIsBound(port)) {
            fail("the loop's close task releases the socket");
        }
    }
}
