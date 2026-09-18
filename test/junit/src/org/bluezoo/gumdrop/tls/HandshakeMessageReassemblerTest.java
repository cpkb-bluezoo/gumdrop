/*
 * HandshakeMessageReassemblerTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link HandshakeMessageReassembler}.
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
