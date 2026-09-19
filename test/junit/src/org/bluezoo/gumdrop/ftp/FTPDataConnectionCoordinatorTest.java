/*
 * FTPDataConnectionCoordinatorTest.java
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

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link FtpDataConnectionCoordinator}, including RFC 4217 section 10
 * data connection security verification and RFC 3659 MACHINE_LISTING type.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPDataConnectionCoordinatorTest {

    @Test
    public void testTransferTypeIncludesMachineListing() {
        // RFC 3659 section 7: MLSD uses MACHINE_LISTING
        FtpDataConnectionCoordinator.TransferType type =
                FtpDataConnectionCoordinator.TransferType.MACHINE_LISTING;
        assertNotNull(type);
        assertEquals("MACHINE_LISTING", type.name());
    }

    @Test
    public void testAllTransferTypes() {
        FtpDataConnectionCoordinator.TransferType[] types =
                FtpDataConnectionCoordinator.TransferType.values();
        assertEquals("Should have 5 transfer types", 5, types.length);
    }

    @Test
    public void testSetControlClientAddress() throws Exception {
        // RFC 4217 section 10: control client address can be set
        FtpDataConnectionCoordinator coordinator =
                new FtpDataConnectionCoordinator(new StubControlConnection());
        InetAddress addr = InetAddress.getByName("192.168.1.100");
        coordinator.setControlClientAddress(addr);
        // No exception means success — actual IP matching is tested
        // at the integration level when real socket connections are used
    }

    @Test
    public void testSetupActiveModeRejectsForeignAddress() throws Exception {
        StubFTPListener listener = new StubFTPListener();
        FtpDataConnectionCoordinator coordinator =
                new FtpDataConnectionCoordinator(
                        new StubControlConnection(listener));
        coordinator.setControlClientAddress(
                InetAddress.getByName("192.168.1.100"));

        assertFalse(coordinator.setupActiveMode("10.0.0.1", 50000));
        assertEquals(FtpDataConnectionCoordinator.DataConnectionMode.NONE,
                coordinator.getMode());
    }

    @Test
    public void testSetupActiveModeAcceptsMatchingAddress() throws Exception {
        StubFTPListener listener = new StubFTPListener();
        FtpDataConnectionCoordinator coordinator =
                new FtpDataConnectionCoordinator(
                        new StubControlConnection(listener));
        coordinator.setControlClientAddress(
                InetAddress.getByName("192.168.1.100"));

        assertTrue(coordinator.setupActiveMode("192.168.1.100", 50000));
        assertEquals(FtpDataConnectionCoordinator.DataConnectionMode.ACTIVE,
                coordinator.getMode());
    }

    @Test
    public void testSetupActiveModeAllowsBounceWhenConfigured() throws Exception {
        StubFTPListener listener = new StubFTPListener();
        listener.setAllowActiveModeBounce(true);
        FtpDataConnectionCoordinator coordinator =
                new FtpDataConnectionCoordinator(
                        new StubControlConnection(listener));
        coordinator.setControlClientAddress(
                InetAddress.getByName("192.168.1.100"));

        assertTrue(coordinator.setupActiveMode("10.0.0.1", 50000));
        assertEquals(FtpDataConnectionCoordinator.DataConnectionMode.ACTIVE,
                coordinator.getMode());
    }

    @Test
    public void testPendingTransferForMLSD() {
        FtpDataConnectionCoordinator.PendingTransfer transfer =
                new FtpDataConnectionCoordinator.PendingTransfer(
                        FtpDataConnectionCoordinator.TransferType.MACHINE_LISTING,
                        "/pub",
                        false,
                        0,
                        null,
                        null
                );
        assertEquals(FtpDataConnectionCoordinator.TransferType.MACHINE_LISTING,
                transfer.getType());
        assertEquals("/pub", transfer.getPath());
    }

    private static class StubControlConnection implements FtpControlConnection {
        private final FtpListener server;

        StubControlConnection() {
            this(null);
        }

        StubControlConnection(FtpListener server) {
            this.server = server;
        }

        @Override
        public FtpListener getServer() {
            return server;
        }
    }

    private static class StubFTPListener extends FtpListener {
    }
}
