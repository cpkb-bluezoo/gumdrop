/*
 * GrpcMetadataTest.java
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
package org.bluezoo.gumdrop.grpc;

import java.util.Arrays;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link GrpcMetadata}: keys, text and binary values.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcMetadataTest {

    @Test
    public void valuesKeepTheirOrderAndRepeat() {
        GrpcMetadata md = new GrpcMetadata();
        md.add("x-a", "1");
        md.add("x-b", "2");
        md.add("x-a", "3");
        assertEquals("1", md.get("x-a"));
        assertEquals(Arrays.asList("1", "3"), md.getAll("x-a"));
        assertEquals(Arrays.asList("x-a", "x-b"), md.keys());
        assertEquals(3, md.size());
        assertEquals("x-b", md.keyAt(1));
        assertEquals("2", md.wireValueAt(1));
        assertNull(md.get("x-c"));
        assertTrue(md.getAll("x-c").isEmpty());
    }

    @Test
    public void binaryValuesRoundTripThroughBase64() {
        GrpcMetadata md = new GrpcMetadata();
        byte[] bytes = {0, 1, (byte) 0xfe, (byte) 0xff, 10};
        md.addBinary("x-blob-bin", bytes);
        assertArrayEquals(bytes, md.getBinary("x-blob-bin"));
        assertEquals("AAH+/wo=", md.wireValueAt(0));
        assertEquals(1, md.getAllBinary("x-blob-bin").size());
    }

    @Test
    public void receivedBinaryValuesMayBeUnpadded() {
        GrpcMetadata md = new GrpcMetadata();
        assertTrue(md.addReceived("x-blob-bin", "AAH+/wo"));
        assertArrayEquals(new byte[] {0, 1, (byte) 0xfe, (byte) 0xff, 10},
                md.getBinary("x-blob-bin"));
    }

    @Test
    public void receivedEntriesThatAreNotValidAreDropped() {
        GrpcMetadata md = new GrpcMetadata();
        assertFalse(md.addReceived("x-blob-bin", "***"));
        assertFalse(md.addReceived("X-Upper", "v"));
        assertFalse(md.addReceived("x-ctl", "a\u0001b"));
        assertTrue(md.isEmpty());
    }

    @Test
    public void keysAndValuesAreChecked() {
        String[] badKeys = {"", "X-Upper", "a b", "a:b", "grpc-status", "grpc-x",
            "content-type", "te"};
        for (int i = 0; i < badKeys.length; i++) {
            try {
                new GrpcMetadata().add(badKeys[i], "v");
                fail("accepted key '" + badKeys[i] + "'");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
        try {
            new GrpcMetadata().add("x-a", "café");
            fail("accepted a non-ASCII value");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new GrpcMetadata().add("x-blob-bin", "text");
            fail("accepted text for a binary key");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new GrpcMetadata().addBinary("x-blob", new byte[0]);
            fail("accepted bytes for a text key");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
