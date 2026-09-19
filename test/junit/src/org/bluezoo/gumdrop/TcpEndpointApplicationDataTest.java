/*
 * TcpEndpointApplicationDataTest.java
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

package org.bluezoo.gumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The {@link ProtocolHandler#receive} contract says bytes a handler leaves
 * unconsumed are preserved for the next call. Handlers such as the HTTP/2
 * frame parser rely on this to resume a frame that straddles two reads.
 * Decrypted TLS application data must honour it exactly as plaintext reads
 * do (a TLS record boundary rarely coincides with a protocol frame
 * boundary).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TcpEndpointApplicationDataTest {

    /** Consumes only complete fixed-size messages, like a frame parser. */
    private static final class MessageHandler implements ProtocolHandler {
        static final int SIZE = 10;
        final List<byte[]> messages = new ArrayList<byte[]>();

        @Override
        public void receive(ByteBuffer data) {
            while (data.remaining() >= SIZE) {
                byte[] m = new byte[SIZE];
                data.get(m);
                messages.add(m);
            }
        }

        @Override public void connected(Endpoint endpoint) { }
        @Override public void securityEstablished(SecurityInfo info) { }
        @Override public void disconnected() { }
        @Override public void error(Exception cause) { }
    }

    private static byte[] stream(int messages) {
        byte[] all = new byte[messages * MessageHandler.SIZE];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) (i / MessageHandler.SIZE);
        }
        return all;
    }

    private static void feed(TcpEndpoint endpoint, byte[] all, int chunk) {
        for (int pos = 0; pos < all.length; pos += chunk) {
            int n = Math.min(chunk, all.length - pos);
            endpoint.onApplicationData(ByteBuffer.wrap(all, pos, n).slice());
        }
    }

    @Test
    public void unconsumedTlsBytesArePreservedForNextRead() {
        int[] chunks = {1, 3, 7, 10, 13, 25, 99};
        for (int c = 0; c < chunks.length; c++) {
            MessageHandler h = new MessageHandler();
            TcpEndpoint endpoint = new TcpEndpoint(h);
            feed(endpoint, stream(20), chunks[c]);
            assertEquals("chunk size " + chunks[c], 20, h.messages.size());
            for (int i = 0; i < 20; i++) {
                assertEquals((byte) i, h.messages.get(i)[0]);
                assertEquals((byte) i, h.messages.get(i)[MessageHandler.SIZE - 1]);
            }
        }
    }

    @Test
    public void fullyConsumedDataLeavesNothingBuffered() {
        MessageHandler h = new MessageHandler();
        TcpEndpoint endpoint = new TcpEndpoint(h);
        feed(endpoint, stream(3), 10);
        assertEquals(3, h.messages.size());
        feed(endpoint, stream(2), 10);
        assertTrue(h.messages.size() == 5);
    }
}
