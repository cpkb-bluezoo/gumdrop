/*
 * FtpPassiveRangeIntegrationTest.java
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


package org.bluezoo.gumdrop.ftp;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.SocketChannel;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.bluezoo.gumdrop.AcceptSelectorLoop;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.TcpTransportFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration test of the passive-mode listener of the socket-backed
 * {@link SocketFtpDataTransport} against real loopback ports: a configured
 * PASV port range that is exhausted, a range that has a free port, and an
 * explicitly requested port that is already taken. The outcome of each case
 * is decided by which ports the test itself holds open, so nothing depends on
 * timing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpPassiveRangeIntegrationTest {

    private static Gumdrop gumdrop;
    private static ClientEndpoint keeper;

    @BeforeClass
    public static void bootGumdrop() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1)
                .drainTimeoutMs(0));
        keeper = new ClientEndpoint(new TcpTransportFactory(),
                gumdrop.nextWorkerLoop(), "localhost", 1);
        gumdrop.addClient(keeper);
        gumdrop.ensureAcceptLoop();
    }

    @AfterClass
    public static void stopGumdrop() throws Exception {
        gumdrop.shutdown();
        gumdrop.join();
    }

    private static final AcceptSelectorLoop.RawAcceptHandler IGNORE =
            new AcceptSelectorLoop.RawAcceptHandler() {
                @Override
                public void accepted(SocketChannel channel) throws IOException {
                    channel.close();
                }
            };

    private FtpListener listener() {
        FtpListener listener = new FtpListener();
        listener.start(gumdrop);
        return listener;
    }

    @Test
    public void exhaustedRangeFailsTheListen() throws Exception {
        ServerSocket busy = new ServerSocket(0);
        try {
            int port = busy.getLocalPort();
            FtpListener listener = listener();
            listener.pasvMinPort(port);
            listener.pasvMaxPort(port);
            try {
                new SocketFtpDataTransport().listenPassive(listener, 0, IGNORE);
                fail("expected IOException");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("No available port"));
            }
        } finally {
            busy.close();
        }
    }

    @Test
    public void rangeWithAFreePortIsUsed() throws Exception {
        ServerSocket busy = new ServerSocket(0);
        try {
            int top = busy.getLocalPort();
            int bottom = top - 32;
            FtpListener listener = listener();
            listener.pasvMinPort(bottom);
            listener.pasvMaxPort(top);
            FtpDataTransport.PassiveListener passive =
                    new SocketFtpDataTransport().listenPassive(listener, 0, IGNORE);
            try {
                int port = passive.port();
                assertTrue("port " + port, port >= bottom && port <= top);
                assertNotEquals(top, port);
            } finally {
                passive.close();
            }
        } finally {
            busy.close();
        }
    }

    @Test
    public void explicitPortThatIsTakenFailsTheListen() throws Exception {
        ServerSocket busy = new ServerSocket(0);
        try {
            int port = busy.getLocalPort();
            try {
                new SocketFtpDataTransport().listenPassive(listener(), port, IGNORE);
                fail("expected IOException");
            } catch (IOException expected) {
                assertEquals(port, busy.getLocalPort());
            }
        } finally {
            busy.close();
        }
    }
}
