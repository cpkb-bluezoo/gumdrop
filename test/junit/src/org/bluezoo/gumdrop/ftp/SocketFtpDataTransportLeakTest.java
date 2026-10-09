/*
 * SocketFtpDataTransportLeakTest.java
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
import java.nio.channels.ServerSocketChannel;

import org.junit.Test;

import org.bluezoo.gumdrop.testsupport.FailingServerSocketChannel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Checks that {@link SocketFtpDataTransport} closes the passive-mode server
 * channel it opened when binding it fails, for a system-assigned port, a
 * requested port and a configured port range.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocketFtpDataTransportLeakTest {

    private final FailingServerSocketChannel channel = new FailingServerSocketChannel();

    private SocketFtpDataTransport transport() {
        return new SocketFtpDataTransport() {
            @Override
            ServerSocketChannel openServerChannel() {
                return channel;
            }
        };
    }

    private void assertBindFailsAndClosesTheChannel(FtpListener listener, int port) {
        try {
            transport().openBound(listener, port);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(channel.getBindCount() > 0);
            assertEquals(1, channel.getCloseCount());
            assertTrue(!channel.isOpen());
        }
    }

    @Test
    public void failedBindOfASystemAssignedPortClosesTheChannel() {
        assertBindFailsAndClosesTheChannel(new FtpListener(), 0);
    }

    @Test
    public void failedBindOfARequestedPortClosesTheChannel() {
        assertBindFailsAndClosesTheChannel(new FtpListener(), 4000);
    }

    @Test
    public void exhaustedPortRangeClosesTheChannel() {
        FtpListener listener = new FtpListener();
        listener.pasvMinPort(4000);
        listener.pasvMaxPort(4002);
        assertBindFailsAndClosesTheChannel(listener, 0);
        assertEquals(3, channel.getBindCount());
    }
}
