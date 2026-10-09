/*
 * FtpDataCoordinatorEdgeTest.java
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

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.testsupport.StubSocketChannel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure and in-memory edge cases of {@link FtpDataConnectionCoordinator} and
 * {@link FtpDataConnection}: the PASV address derivation for every control
 * address shape, reply formatting, queued and failing data connections,
 * cleanup and abort, and active-mode address policy. No socket, loop or
 * thread is involved.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpDataCoordinatorEdgeTest {

    private static final class Control implements FtpControlConnection {
        private final FtpListener server;

        Control(FtpListener server) {
            this.server = server;
        }

        @Override
        public FtpListener getServer() {
            return server;
        }
    }

    private FtpListener listener;
    private FtpDataConnectionCoordinator coordinator;

    @Before
    public void setUp() {
        listener = new FtpListener();
        coordinator = new FtpDataConnectionCoordinator(new Control(listener));
    }

    private static InetAddress ip(String text) throws Exception {
        return InetAddress.getByName(text);
    }

    private StubSocketChannel channel(String remote) {
        return new StubSocketChannel(new InetSocketAddress("127.0.0.1", 40000),
                new InetSocketAddress(remote, 50000));
    }

    // PASV address derivation

    @Test
    public void pasvAddressRequiresALocalAddress() throws Exception {
        try {
            FtpDataConnectionCoordinator.ipv4AddressForPasv(null, ip("192.0.2.1"));
            fail("expected UnknownHostException");
        } catch (java.net.UnknownHostException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("local"));
        }
    }

    @Test
    public void pasvAddressForIpv4Locals() throws Exception {
        assertEquals("127.0.0.1", FtpDataConnectionCoordinator
                .ipv4AddressForPasv(ip("0.0.0.0"), null).getHostAddress());
        assertEquals("127.0.0.1", FtpDataConnectionCoordinator
                .ipv4AddressForPasv(ip("127.0.0.5"), null).getHostAddress());
        assertEquals("192.0.2.7", FtpDataConnectionCoordinator
                .ipv4AddressForPasv(ip("192.0.2.7"), null).getHostAddress());
    }

    @Test
    public void pasvAddressForIpv6Locals() throws Exception {
        assertEquals("127.0.0.1", FtpDataConnectionCoordinator
                .ipv4AddressForPasv(ip("::1"), null).getHostAddress());
        assertEquals("192.0.2.9", FtpDataConnectionCoordinator
                .ipv4AddressForPasv(ip("2001:db8::1"), ip("192.0.2.9")).getHostAddress());
    }

    @Test
    public void pasvAddressForIpv6LocalNeedsAUsableIpv4Peer() throws Exception {
        InetAddress local = ip("2001:db8::1");
        InetAddress[] peers = {null, ip("2001:db8::2"), ip("0.0.0.0")};
        for (int i = 0; i < peers.length; i++) {
            try {
                FtpDataConnectionCoordinator.ipv4AddressForPasv(local, peers[i]);
                fail("expected UnknownHostException for peer " + peers[i]);
            } catch (java.net.UnknownHostException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("EPSV"));
            }
        }
    }

    // PASV reply formatting

    @Test
    public void passiveResponseBeforePassiveModeIsAStateError() throws Exception {
        try {
            coordinator.generatePassiveResponse(ip("192.0.2.1"));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(FtpDataConnectionCoordinator.DataConnectionMode.NONE, coordinator.getMode());
        }
    }

    // queued connections, cleanup and abort

    @Test
    public void connectionIsQueuedWhenTheControlClientIsUnknown() {
        StubSocketChannel ch = channel("192.0.2.1");
        FtpDataConnection conn = new FtpDataConnection(ch, coordinator);
        coordinator.setControlClientAddress(null);
        coordinator.acceptDataConnection(conn);
        assertFalse("queued, not closed", ch.getCloseCount() > 0);
        coordinator.cleanup();
        assertTrue("cleanup closes queued connections", ch.getCloseCount() > 0);
    }

    @Test
    public void connectionFromTheControlClientIsQueued() throws Exception {
        coordinator.setControlClientAddress(ip("192.0.2.1"));
        StubSocketChannel ch = channel("192.0.2.1");
        coordinator.acceptDataConnection(new FtpDataConnection(ch, coordinator));
        assertFalse(ch.getCloseCount() > 0);
        coordinator.cleanup();
        assertTrue(ch.getCloseCount() > 0);
    }

    @Test
    public void connectionFromAnotherHostIsClosedImmediately() throws Exception {
        coordinator.setControlClientAddress(ip("192.0.2.1"));
        StubSocketChannel ch = channel("192.0.2.99");
        coordinator.acceptDataConnection(new FtpDataConnection(ch, coordinator));
        assertTrue(ch.getCloseCount() > 0);
    }

    @Test
    public void abortWithoutATransferReportsNone() {
        assertFalse(coordinator.abortTransfer());
        assertEquals(FtpDataConnectionCoordinator.DataConnectionMode.NONE, coordinator.getMode());
        assertFalse(coordinator.hasActiveDataConnection());
    }

    // active mode policy

    @Test
    public void activeModeWithoutKnownControlClientIsRefused() {
        assertFalse(coordinator.setupActiveMode("192.0.2.1", 50000));
        assertEquals(FtpDataConnectionCoordinator.DataConnectionMode.NONE, coordinator.getMode());
    }

    @Test
    public void activeModeBounceIsAllowedWhenConfiguredEvenWithoutControlClient() {
        listener.allowActiveModeBounce(true);
        assertTrue(coordinator.setupActiveMode("192.0.2.1", 50000));
        assertEquals(FtpDataConnectionCoordinator.DataConnectionMode.ACTIVE, coordinator.getMode());
    }

    @Test
    public void activeModeWithoutServerFollowsControlClientAddressOnly() throws Exception {
        FtpDataConnectionCoordinator bare = new FtpDataConnectionCoordinator(new Control(null));
        bare.setControlClientAddress(ip("192.0.2.1"));
        assertTrue(bare.setupActiveMode("192.0.2.1", 50000));
        bare.cleanup();
        assertFalse(bare.setupActiveMode("192.0.2.2", 50000));
    }

    // data connection

    @Test
    public void dataConnectionTracksTransferStateAndSurvivesCloseFailure() {
        StubSocketChannel ch = channel("192.0.2.1");
        FtpDataConnection conn = new FtpDataConnection(ch, coordinator);
        assertFalse(conn.isTransferActive());
        conn.setTransferActive(true);
        assertTrue(conn.isTransferActive());
        ch.setFailOnClose(true);
        conn.close();
        assertTrue(ch.getCloseCount() > 0);
        conn.close();
    }
}
