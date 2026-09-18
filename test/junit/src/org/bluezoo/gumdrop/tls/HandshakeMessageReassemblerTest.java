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
}
