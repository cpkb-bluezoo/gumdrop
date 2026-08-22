/*
 * CapsuleParserTest.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RFC 9297 section 3 Capsule Protocol encode/parse tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CapsuleParserTest {

    @Test
    public void testDatagramCapsuleRoundTrip() throws Exception {
        Capsule capsule = Capsule.datagram("ping".getBytes(StandardCharsets.US_ASCII));
        byte[] bytes = capsule.encode();
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> got = parser.push(ByteBuffer.wrap(bytes));
        assertEquals(1, got.size());
        assertEquals(Capsule.TYPE_DATAGRAM, got.get(0).getType());
        assertEquals("ping", new String(got.get(0).getValue(), StandardCharsets.US_ASCII));
        assertTrue(parser.finish());
    }

    @Test
    public void testSplitAcrossPushes() throws Exception {
        byte[] bytes = Capsule.datagram("abcdef".getBytes(StandardCharsets.US_ASCII)).encode();
        CapsuleParser parser = new CapsuleParser();
        assertTrue(parser.push(ByteBuffer.wrap(bytes, 0, 2)).isEmpty());
        List<Capsule> got = parser.push(ByteBuffer.wrap(bytes, 2, bytes.length - 2));
        assertEquals(1, got.size());
        assertEquals("abcdef", new String(got.get(0).getValue(), StandardCharsets.US_ASCII));
    }

    @Test
    public void testUnknownTypeIsSurfaced() throws Exception {
        Capsule capsule = new Capsule(0x99, new byte[] { 'x' });
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> got = parser.push(ByteBuffer.wrap(capsule.encode()));
        assertEquals(0x99L, got.get(0).getType());
    }

    @Test
    public void testFinishRejectsTruncated() throws Exception {
        CapsuleParser parser = new CapsuleParser();
        parser.push(ByteBuffer.wrap(new byte[] { 0x00 }));
        assertFalse(parser.finish());
    }

    @Test
    public void testCapsuleProtocolHeaderTrue() {
        Headers headers = new Headers();
        headers.add("Capsule-Protocol", "?1");
        assertTrue(Capsule.capsuleProtocolEnabled(headers));
        Headers disabled = new Headers();
        disabled.add("capsule-protocol", "?0");
        assertFalse(Capsule.capsuleProtocolEnabled(disabled));
    }
}
