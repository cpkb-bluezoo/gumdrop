/*
 * DtlsReassemblerTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DtlsReassemblerTest {

    @Test
    public void coalescesOutOfOrderFragments() throws Exception {
        DtlsReassembler reassembler = new DtlsReassembler();
        byte[] body = "hello-handshake-body".getBytes("US-ASCII");
        byte[] full = frame(1, 0, body);

        List<byte[]> tail = reassembler.addFragment(fragment(1, 0, body.length, 10, body.length - 10, body, 10));
        assertTrue(tail.isEmpty());

        List<byte[]> head = reassembler.addFragment(fragment(1, 0, body.length, 0, 10, body, 0));
        assertEquals(1, head.size());
        assertArrayEquals(full, head.get(0));
    }

    @Test
    public void rejectsOverflowingFragment() throws Exception {
        DtlsReassembler reassembler = new DtlsReassembler();
        byte[] body = new byte[8];
        try {
            reassembler.addFragment(fragment(1, 0, body.length, 4, 8, body, 0));
            org.junit.Assert.fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            // ok
        }
    }

    @Test
    public void duplicateFragmentIsIgnored() throws Exception {
        DtlsReassembler reassembler = new DtlsReassembler();
        byte[] body = "abc".getBytes("US-ASCII");
        reassembler.addFragment(fragment(1, 0, body.length, 0, body.length, body, 0));
        List<byte[]> again = reassembler.addFragment(fragment(1, 0, body.length, 0, body.length, body, 0));
        assertTrue(again.isEmpty());
    }

    private static byte[] fragment(int msgType, int messageSeq, int totalLength, int offset, int length,
            byte[] body, int bodyOffset) {
        byte[] payload = new byte[12 + length];
        payload[0] = (byte) msgType;
        payload[1] = (byte) ((totalLength >> 16) & 0xff);
        payload[2] = (byte) ((totalLength >> 8) & 0xff);
        payload[3] = (byte) (totalLength & 0xff);
        payload[4] = (byte) ((messageSeq >> 8) & 0xff);
        payload[5] = (byte) (messageSeq & 0xff);
        payload[6] = (byte) ((offset >> 16) & 0xff);
        payload[7] = (byte) ((offset >> 8) & 0xff);
        payload[8] = (byte) (offset & 0xff);
        payload[9] = (byte) ((length >> 16) & 0xff);
        payload[10] = (byte) ((length >> 8) & 0xff);
        payload[11] = (byte) (length & 0xff);
        System.arraycopy(body, bodyOffset, payload, 12, length);
        return payload;
    }

    private static byte[] frame(int msgType, int messageSeq, byte[] body) {
        byte[] message = new byte[4 + body.length];
        message[0] = (byte) msgType;
        message[1] = (byte) ((body.length >> 16) & 0xff);
        message[2] = (byte) ((body.length >> 8) & 0xff);
        message[3] = (byte) (body.length & 0xff);
        System.arraycopy(body, 0, message, 4, body.length);
        return message;
    }

}
