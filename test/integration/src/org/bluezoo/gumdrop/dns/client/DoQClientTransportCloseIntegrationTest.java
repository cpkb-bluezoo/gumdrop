/*
 * DoQClientTransportCloseIntegrationTest.java
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

package org.bluezoo.gumdrop.dns.client;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;

import java.net.InetAddress;

import org.bluezoo.gumdrop.testsupport.RecordingSelectorLoop;
import org.junit.Test;

/**
 * {@link DoQClientTransport#close()} must hand the QUIC engine's close to
 * the engine's selector loop rather than close it on the caller's thread.
 *
 * <p>Integration test: the transport opens a real UDP QUIC client socket on
 * connect, so the close hand-off is observed against a real engine.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQClientTransportCloseIntegrationTest {

    private static final class Quiet implements DnsClientTransportHandler {
        @Override
        public void onReceive(ByteBuffer data) {
        }

        @Override
        public void onError(Exception cause) {
        }
    }

    @Test
    public void closeIsPostedToTheEnginesLoop() throws Exception {
        RecordingSelectorLoop loop = new RecordingSelectorLoop();
        DoQClientTransport transport = new DoQClientTransport();
        transport.open(InetAddress.getLoopbackAddress(), 853, loop, new Quiet());
        int before = loop.recordedCount();

        transport.close();

        assertEquals("the close is handed to the loop", before + 1, loop.recordedCount());
        loop.runRecorded();
        transport.close();
        assertEquals("an engine its loop has closed is left alone", 0, loop.recordedCount());
    }
}
