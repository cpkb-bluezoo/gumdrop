/*
 * H3ErrorCodeTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * Tests that HTTP/3, QPACK, and HTTP Datagram application error codes
 * match RFC 9114 section 8.1, RFC 9204 section 6, and RFC 9297.
 */

package org.bluezoo.gumdrop.http.h3;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Pins the HTTP/3 / QPACK application-error namespace to the RFC values
 * so later SETTINGS, push, critical-stream, and field-section work can
 * close with the correct {@code CONNECTION_CLOSE} / {@code RESET_STREAM}
 * codes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ErrorCodeTest {

    @Test
    public void testRfc9114Http3ErrorCodes() {
        assertEquals(0x0100L, H3ErrorCode.H3_NO_ERROR);
        assertEquals(0x0101L, H3ErrorCode.H3_GENERAL_PROTOCOL_ERROR);
        assertEquals(0x0102L, H3ErrorCode.H3_INTERNAL_ERROR);
        assertEquals(0x0103L, H3ErrorCode.H3_STREAM_CREATION_ERROR);
        assertEquals(0x0104L, H3ErrorCode.H3_CLOSED_CRITICAL_STREAM);
        assertEquals(0x0105L, H3ErrorCode.H3_FRAME_UNEXPECTED);
        assertEquals(0x0106L, H3ErrorCode.H3_FRAME_ERROR);
        assertEquals(0x0107L, H3ErrorCode.H3_EXCESSIVE_LOAD);
        assertEquals(0x0108L, H3ErrorCode.H3_ID_ERROR);
        assertEquals(0x0109L, H3ErrorCode.H3_SETTINGS_ERROR);
        assertEquals(0x010aL, H3ErrorCode.H3_MISSING_SETTINGS);
        assertEquals(0x010bL, H3ErrorCode.H3_REQUEST_REJECTED);
        assertEquals(0x010cL, H3ErrorCode.H3_REQUEST_CANCELLED);
        assertEquals(0x010dL, H3ErrorCode.H3_REQUEST_INCOMPLETE);
        assertEquals(0x010eL, H3ErrorCode.H3_MESSAGE_ERROR);
        assertEquals(0x010fL, H3ErrorCode.H3_CONNECT_ERROR);
        assertEquals(0x0110L, H3ErrorCode.H3_VERSION_FALLBACK);
    }

    @Test
    public void testRfc9297DatagramErrorCode() {
        assertEquals(0x33L, H3ErrorCode.H3_DATAGRAM_ERROR);
    }

    @Test
    public void testRfc9204QpackErrorCodes() {
        assertEquals(0x0200L, H3ErrorCode.QPACK_DECOMPRESSION_FAILED);
        assertEquals(0x0201L, H3ErrorCode.QPACK_ENCODER_STREAM_ERROR);
        assertEquals(0x0202L, H3ErrorCode.QPACK_DECODER_STREAM_ERROR);
    }

    @Test
    public void testQpackEncoderStreamErrorIsNotTheStreamType() {
        // RFC 9114 section 6.2 / RFC 9204 section 4.2: unidirectional
        // stream type 0x02 is the QPACK encoder stream. The application
        // error for a malformed encoder stream is 0x0201. These used to
        // be conflated as a private 0x02 constant in H3ControlStream.
        assertNotEquals(0x02L, H3ErrorCode.QPACK_ENCODER_STREAM_ERROR);
        assertEquals(0x0201L, H3ErrorCode.QPACK_ENCODER_STREAM_ERROR);
    }
}
