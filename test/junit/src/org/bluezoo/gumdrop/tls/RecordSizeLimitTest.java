/*
 * RecordSizeLimitTest.java
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

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Unit tests for RFC 8449 {@code record_size_limit} helpers and wire encoding.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RecordSizeLimitTest {

    @Test
    public void decodeRejectsShortExtensionBody() {
        try {
            RecordSizeLimit.decodeExtensionValue(new byte[] { 0x01 });
            fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            // ok
        }
    }

    @Test
    public void decodeRejectsBelowMinimum() throws Exception {
        try {
            RecordSizeLimit.decodeExtensionValue(new byte[] { 0x00, 0x3f });
            fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            // ok
        }
    }

    @Test
    public void roundTripEncodeDecode() throws Exception {
        int limit = 4096;
        byte[] encoded = RecordSizeLimit.encodeExtensionValue(limit);
        assertEquals(limit, RecordSizeLimit.decodeExtensionValue(encoded));
    }

    @Test
    public void localInboundLimitUsesDefaultWhenExtensionDisabled() {
        assertEquals(RecordSizeLimit.DEFAULT,
                RecordSizeLimit.localInboundLimit(512, false));
    }

    @Test
    public void localInboundLimitClampsConfiguredValue() {
        assertEquals(RecordSizeLimit.MINIMUM, RecordSizeLimit.clampLocal(1));
        assertEquals(RecordSizeLimit.ABSOLUTE_MAX, RecordSizeLimit.clampLocal(99999));
    }
}
