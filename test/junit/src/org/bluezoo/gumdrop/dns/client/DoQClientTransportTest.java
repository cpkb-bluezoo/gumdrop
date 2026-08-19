/*
 * DoQClientTransportTest.java
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

import java.lang.reflect.Field;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DoQClientTransport} session resumption and 0-RTT.
 * RFC 9250 section 4.5.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQClientTransportTest {

    /**
     * RFC 9250 section 4.5: only QUERY and NOTIFY opcodes may ride 0-RTT
     * early data -- checked directly against the raw wire bytes (before
     * any {@code DNSMessage} parse), matching {@code
     * DoQClientTransport.isEarlyDataEligible}'s own comment about the
     * header byte layout (RFC 1035 section 4.1.1: {@code QR(1) OPCODE(4)
     * ...}).
     */
    @Test
    public void testEarlyDataEligibleOpcodes() throws Exception {
        java.lang.reflect.Method method = DoQClientTransport.class
                .getDeclaredMethod("isEarlyDataEligible", java.nio.ByteBuffer.class);
        method.setAccessible(true);

        assertTrue("QUERY should be 0-RTT-eligible",
                (Boolean) method.invoke(null, header(org.bluezoo.gumdrop.dns.DNSMessage.OPCODE_QUERY)));
        assertTrue("NOTIFY should be 0-RTT-eligible",
                (Boolean) method.invoke(null, header(org.bluezoo.gumdrop.dns.DNSMessage.OPCODE_NOTIFY)));
        assertFalse("STATUS should not be 0-RTT-eligible",
                (Boolean) method.invoke(null, header(org.bluezoo.gumdrop.dns.DNSMessage.OPCODE_STATUS)));
        assertFalse("IQUERY should not be 0-RTT-eligible",
                (Boolean) method.invoke(null, header(org.bluezoo.gumdrop.dns.DNSMessage.OPCODE_IQUERY)));
        assertFalse("A too-short buffer should not be eligible",
                (Boolean) method.invoke(null, java.nio.ByteBuffer.wrap(new byte[] {0, 0})));
    }

    // Builds a minimal 3-byte prefix of a DNS header (ID(2) + the flags
    // byte containing QR(1)/OPCODE(4)) -- enough for isEarlyDataEligible's
    // own header-byte check, without needing a full DNSMessage.
    private static java.nio.ByteBuffer header(int opcode) {
        byte flagsHighByte = (byte) ((opcode << 3) & 0xFF);
        return java.nio.ByteBuffer.wrap(new byte[] {0, 0, flagsHighByte});
    }

    @Test
    public void testCloseOnNewTransport() {
        DoQClientTransport transport = new DoQClientTransport();
        transport.close();
    }

    @Test
    public void testSendWithoutOpenReportsError() {
        DoQClientTransport transport = new DoQClientTransport();
        final boolean[] errorReported = {false};
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(12);

        DNSClientTransportHandler handler = new DNSClientTransportHandler() {
            @Override
            public void onReceive(java.nio.ByteBuffer response) {
            }

            @Override
            public void onError(Exception cause) {
                errorReported[0] = true;
            }
        };

        try {
            Field handlerField = DoQClientTransport.class
                    .getDeclaredField("handler");
            handlerField.setAccessible(true);
            handlerField.set(transport, handler);
        } catch (ReflectiveOperationException e) {
            fail("Could not set handler: " + e);
        }

        transport.send(data);
        assertTrue("Should report error when not connected",
                errorReported[0]);
    }
}
