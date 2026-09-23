/*
 * HandshakeMessageReassemblerTest.java
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

package org.bluezoo.gumdrop.tls;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link HandshakeMessageReassembler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HandshakeMessageReassemblerTest {

    @Test
    public void reassemblesMessageSplitAcrossTwoFeeds() throws Exception {
        byte[] body = new byte[] { 0x01, 0x02, 0x03 };
        byte[] message = WireWriter.frameHandshakeMessage(1, body);
        int split = 2;
        HandshakeMessageReassembler reassembler = new HandshakeMessageReassembler();
        final List<byte[]> complete = new ArrayList<byte[]>();
        HandshakeMessageReassembler.MessageConsumer consumer = new HandshakeMessageReassembler.MessageConsumer() {
            @Override
            public void accept(byte[] completeMessage) {
                complete.add(completeMessage);
            }
        };
        reassembler.feed(new byte[] { message[0], message[1] }, consumer);
        assertEquals(0, complete.size());
        reassembler.feed(java.util.Arrays.copyOfRange(message, split, message.length), consumer);
        assertEquals(1, complete.size());
        assertEquals(message.length, complete.get(0).length);
    }

    private static final class Recorder implements HandshakeMessageReassembler.StreamingMessageConsumer {
        final List<byte[]> complete = new ArrayList<byte[]>();
        final List<String> events = new ArrayList<String>();
        final java.io.ByteArrayOutputStream streamed = new java.io.ByteArrayOutputStream();
        byte[] header;

        @Override
        public void accept(byte[] completeMessage) {
            complete.add(completeMessage);
            events.add("message");
        }

        @Override
        public void streamStart(byte[] messageHeader) {
            header = messageHeader;
            events.add("start");
        }

        @Override
        public void streamData(byte[] chunk) {
            streamed.write(chunk, 0, chunk.length);
            events.add("data");
        }

        @Override
        public void streamEnd() {
            events.add("end");
        }
    }

    @Test
    public void streamsCompressedCertificateWithoutBufferingIt() throws Exception {
        byte[] body = new byte[5000];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        byte[] streamedMessage = WireWriter.frameHandshakeMessage(25, body);
        byte[] ordinary = WireWriter.frameHandshakeMessage(20, new byte[] { 9, 9 });
        byte[] wire = new byte[streamedMessage.length + ordinary.length];
        System.arraycopy(streamedMessage, 0, wire, 0, streamedMessage.length);
        System.arraycopy(ordinary, 0, wire, streamedMessage.length, ordinary.length);

        HandshakeMessageReassembler reassembler = new HandshakeMessageReassembler();
        Recorder rec = new Recorder();
        for (int i = 0; i < wire.length; i += 3) {
            reassembler.feed(java.util.Arrays.copyOfRange(wire, i, Math.min(wire.length, i + 3)), rec);
        }

        assertEquals(4, rec.header.length);
        assertEquals(25, rec.header[0] & 0xff);
        org.junit.Assert.assertArrayEquals(body, rec.streamed.toByteArray());
        assertEquals(1, rec.complete.size());
        assertEquals(20, rec.complete.get(0)[0] & 0xff);
        assertEquals("start", rec.events.get(0));
        assertEquals("message", rec.events.get(rec.events.size() - 1));
        assertEquals("end", rec.events.get(rec.events.size() - 2));
    }

    @Test
    public void streamsHandshakeMessageSplitInsideHeader() throws Exception {
        byte[] message = WireWriter.frameHandshakeMessage(25, new byte[] { 1, 2, 3 });
        HandshakeMessageReassembler reassembler = new HandshakeMessageReassembler();
        Recorder rec = new Recorder();
        reassembler.feed(new byte[] { message[0], message[1] }, rec);
        assertEquals(0, rec.events.size());
        reassembler.feed(java.util.Arrays.copyOfRange(message, 2, message.length), rec);
        org.junit.Assert.assertArrayEquals(new byte[] { 1, 2, 3 }, rec.streamed.toByteArray());
        assertEquals("end", rec.events.get(rec.events.size() - 1));
    }
}
