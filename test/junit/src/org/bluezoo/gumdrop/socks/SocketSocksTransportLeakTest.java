/*
 * SocketSocksTransportLeakTest.java
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


package org.bluezoo.gumdrop.socks;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;

import org.junit.Test;

import org.bluezoo.gumdrop.testsupport.FailingServerSocketChannel;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * Checks that {@link SocketSocksTransport} closes the BIND server channel it
 * opened when binding it fails.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocketSocksTransportLeakTest {

    @Test
    public void failedBindClosesTheChannel() {
        final FailingServerSocketChannel channel = new FailingServerSocketChannel();
        SocketSocksTransport transport = new SocketSocksTransport() {
            @Override
            ServerSocketChannel openServerChannel() {
                return channel;
            }
        };
        try {
            transport.listenBind(TestGumdrop.create(), null);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals(1, channel.getBindCount());
            assertEquals(1, channel.getCloseCount());
            assertFalse(channel.isOpen());
        }
    }
}
