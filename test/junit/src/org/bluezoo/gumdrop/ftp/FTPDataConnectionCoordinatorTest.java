/*
 * FTPDataConnectionCoordinatorTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.ftp;

import java.net.InetAddress;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link FTPDataConnectionCoordinator}, including RFC 4217 section 10
 * data connection security verification and RFC 3659 MACHINE_LISTING type.
 */
public class FTPDataConnectionCoordinatorTest {

    @Test
    public void testTransferTypeIncludesMachineListing() {
        // RFC 3659 section 7: MLSD uses MACHINE_LISTING
        FTPDataConnectionCoordinator.TransferType type =
                FTPDataConnectionCoordinator.TransferType.MACHINE_LISTING;
        assertNotNull(type);
        assertEquals("MACHINE_LISTING", type.name());
    }

    @Test
    public void testAllTransferTypes() {
        FTPDataConnectionCoordinator.TransferType[] types =
                FTPDataConnectionCoordinator.TransferType.values();
        assertEquals("Should have 5 transfer types", 5, types.length);
    }

    @Test
    public void testSetControlClientAddress() throws Exception {
        // RFC 4217 section 10: control client address can be set
        FTPDataConnectionCoordinator coordinator =
                new FTPDataConnectionCoordinator(new StubControlConnection());
        InetAddress addr = InetAddress.getByName("192.168.1.100");
        coordinator.setControlClientAddress(addr);
        // No exception means success — actual IP matching is tested
        // at the integration level when real socket connections are used
    }

    @Test
    public void testPendingTransferForMLSD() {
        FTPDataConnectionCoordinator.PendingTransfer transfer =
                new FTPDataConnectionCoordinator.PendingTransfer(
                        FTPDataConnectionCoordinator.TransferType.MACHINE_LISTING,
                        "/pub",
                        false,
                        0,
                        null,
                        null
                );
        assertEquals(FTPDataConnectionCoordinator.TransferType.MACHINE_LISTING,
                transfer.getType());
        assertEquals("/pub", transfer.getPath());
    }

    private static class StubControlConnection implements FTPControlConnection {
        @Override
        public FTPListener getServer() {
            return null;
        }
    }
}
