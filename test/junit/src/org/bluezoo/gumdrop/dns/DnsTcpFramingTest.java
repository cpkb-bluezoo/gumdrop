/*
 * DnsTcpFramingTest.java
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

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsTcpFramingTest {

    @Test
    public void testReadAllFramedMessagesMultiAxfr() throws Exception {
        byte[] msg1 = minimalWire(1);
        byte[] msg2 = minimalWire(2);
        byte[] stream = concatFramed(msg1, msg2);
        List<byte[]> parsed = DnsTcpFraming.readAllFramedMessages(stream);
        assertEquals(2, parsed.size());
        assertArrayEquals(msg1, parsed.get(0));
        assertArrayEquals(msg2, parsed.get(1));
    }

    @Test
    public void testReadAllFramedMessagesSingle() throws Exception {
        byte[] msg = minimalWire(42);
        List<byte[]> parsed = DnsTcpFraming.readAllFramedMessages(concatFramed(msg));
        assertEquals(1, parsed.size());
        assertArrayEquals(msg, parsed.get(0));
    }

    @Test(expected = IOException.class)
    public void testReadAllFramedMessagesRejectsTruncatedTail() throws Exception {
        byte[] msg = minimalWire(1);
        byte[] stream = concatFramed(msg);
        byte[] truncated = new byte[stream.length - 1];
        System.arraycopy(stream, 0, truncated, 0, truncated.length);
        DnsTcpFraming.readAllFramedMessages(truncated);
    }

    private static byte[] minimalWire(int id) throws Exception {
        DnsMessage m = DnsMessage.createQuery(id, "example.com.", DnsType.SOA);
        java.nio.ByteBuffer wire = m.serialize();
        byte[] bytes = new byte[wire.remaining()];
        wire.get(bytes);
        return bytes;
    }

    private static byte[] concatFramed(byte[]... messages) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < messages.length; i++) {
            DnsTcpFraming.writeFramed(out, messages[i]);
        }
        return out.toByteArray();
    }
}
