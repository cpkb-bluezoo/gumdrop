/*
 * RecordSizeLimitTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Unit tests for RFC 8449 {@code record_size_limit} helpers and wire encoding.
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
