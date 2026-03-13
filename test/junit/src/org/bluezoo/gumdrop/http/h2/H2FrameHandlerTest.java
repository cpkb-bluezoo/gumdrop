package org.bluezoo.gumdrop.http.h2;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link H2FrameHandler} constants and utility methods.
 */
public class H2FrameHandlerTest {

    // ========== typeToString ==========

    @Test
    public void testTypeToStringData() {
        assertEquals("DATA", H2FrameHandler.typeToString(H2FrameHandler.TYPE_DATA));
    }

    @Test
    public void testTypeToStringHeaders() {
        assertEquals("HEADERS", H2FrameHandler.typeToString(H2FrameHandler.TYPE_HEADERS));
    }

    @Test
    public void testTypeToStringPriority() {
        assertEquals("PRIORITY", H2FrameHandler.typeToString(H2FrameHandler.TYPE_PRIORITY));
    }

    @Test
    public void testTypeToStringRstStream() {
        assertEquals("RST_STREAM", H2FrameHandler.typeToString(H2FrameHandler.TYPE_RST_STREAM));
    }

    @Test
    public void testTypeToStringSettings() {
        assertEquals("SETTINGS", H2FrameHandler.typeToString(H2FrameHandler.TYPE_SETTINGS));
    }

    @Test
    public void testTypeToStringPushPromise() {
        assertEquals("PUSH_PROMISE", H2FrameHandler.typeToString(H2FrameHandler.TYPE_PUSH_PROMISE));
    }

    @Test
    public void testTypeToStringPing() {
        assertEquals("PING", H2FrameHandler.typeToString(H2FrameHandler.TYPE_PING));
    }

    @Test
    public void testTypeToStringGoaway() {
        assertEquals("GOAWAY", H2FrameHandler.typeToString(H2FrameHandler.TYPE_GOAWAY));
    }

    @Test
    public void testTypeToStringWindowUpdate() {
        assertEquals("WINDOW_UPDATE", H2FrameHandler.typeToString(H2FrameHandler.TYPE_WINDOW_UPDATE));
    }

    @Test
    public void testTypeToStringContinuation() {
        assertEquals("CONTINUATION", H2FrameHandler.typeToString(H2FrameHandler.TYPE_CONTINUATION));
    }

    @Test
    public void testTypeToStringUnknown() {
        assertEquals("UNKNOWN(99)", H2FrameHandler.typeToString(99));
        assertEquals("UNKNOWN(-1)", H2FrameHandler.typeToString(-1));
    }

    // ========== errorToString ==========

    @Test
    public void testErrorToStringNoError() {
        assertEquals("NO_ERROR", H2FrameHandler.errorToString(H2FrameHandler.ERROR_NO_ERROR));
    }

    @Test
    public void testErrorToStringProtocolError() {
        assertEquals("PROTOCOL_ERROR", H2FrameHandler.errorToString(H2FrameHandler.ERROR_PROTOCOL_ERROR));
    }

    @Test
    public void testErrorToStringInternalError() {
        assertEquals("INTERNAL_ERROR", H2FrameHandler.errorToString(H2FrameHandler.ERROR_INTERNAL_ERROR));
    }

    @Test
    public void testErrorToStringFlowControlError() {
        assertEquals("FLOW_CONTROL_ERROR", H2FrameHandler.errorToString(H2FrameHandler.ERROR_FLOW_CONTROL_ERROR));
    }

    @Test
    public void testErrorToStringSettingsTimeout() {
        assertEquals("SETTINGS_TIMEOUT", H2FrameHandler.errorToString(H2FrameHandler.ERROR_SETTINGS_TIMEOUT));
    }

    @Test
    public void testErrorToStringStreamClosed() {
        assertEquals("STREAM_CLOSED", H2FrameHandler.errorToString(H2FrameHandler.ERROR_STREAM_CLOSED));
    }

    @Test
    public void testErrorToStringFrameSizeError() {
        assertEquals("FRAME_SIZE_ERROR", H2FrameHandler.errorToString(H2FrameHandler.ERROR_FRAME_SIZE_ERROR));
    }

    @Test
    public void testErrorToStringRefusedStream() {
        assertEquals("REFUSED_STREAM", H2FrameHandler.errorToString(H2FrameHandler.ERROR_REFUSED_STREAM));
    }

    @Test
    public void testErrorToStringCancel() {
        assertEquals("CANCEL", H2FrameHandler.errorToString(H2FrameHandler.ERROR_CANCEL));
    }

    @Test
    public void testErrorToStringCompressionError() {
        assertEquals("COMPRESSION_ERROR", H2FrameHandler.errorToString(H2FrameHandler.ERROR_COMPRESSION_ERROR));
    }

    @Test
    public void testErrorToStringConnectError() {
        assertEquals("CONNECT_ERROR", H2FrameHandler.errorToString(H2FrameHandler.ERROR_CONNECT_ERROR));
    }

    @Test
    public void testErrorToStringEnhanceYourCalm() {
        assertEquals("ENHANCE_YOUR_CALM", H2FrameHandler.errorToString(H2FrameHandler.ERROR_ENHANCE_YOUR_CALM));
    }

    @Test
    public void testErrorToStringInadequateSecurity() {
        assertEquals("INADEQUATE_SECURITY", H2FrameHandler.errorToString(H2FrameHandler.ERROR_INADEQUATE_SECURITY));
    }

    @Test
    public void testErrorToStringHttp11Required() {
        assertEquals("HTTP_1_1_REQUIRED", H2FrameHandler.errorToString(H2FrameHandler.ERROR_HTTP_1_1_REQUIRED));
    }

    @Test
    public void testErrorToStringUnknown() {
        assertEquals("UNKNOWN(999)", H2FrameHandler.errorToString(999));
        assertEquals("UNKNOWN(-1)", H2FrameHandler.errorToString(-1));
    }

    // ========== Frame Type Constants ==========

    @Test
    public void testFrameTypeValues() {
        assertEquals(0x0, H2FrameHandler.TYPE_DATA);
        assertEquals(0x1, H2FrameHandler.TYPE_HEADERS);
        assertEquals(0x2, H2FrameHandler.TYPE_PRIORITY);
        assertEquals(0x3, H2FrameHandler.TYPE_RST_STREAM);
        assertEquals(0x4, H2FrameHandler.TYPE_SETTINGS);
        assertEquals(0x5, H2FrameHandler.TYPE_PUSH_PROMISE);
        assertEquals(0x6, H2FrameHandler.TYPE_PING);
        assertEquals(0x7, H2FrameHandler.TYPE_GOAWAY);
        assertEquals(0x8, H2FrameHandler.TYPE_WINDOW_UPDATE);
        assertEquals(0x9, H2FrameHandler.TYPE_CONTINUATION);
    }

    // ========== Error Code Constants ==========

    @Test
    public void testErrorCodeValues() {
        assertEquals(0x0, H2FrameHandler.ERROR_NO_ERROR);
        assertEquals(0x1, H2FrameHandler.ERROR_PROTOCOL_ERROR);
        assertEquals(0x2, H2FrameHandler.ERROR_INTERNAL_ERROR);
        assertEquals(0x3, H2FrameHandler.ERROR_FLOW_CONTROL_ERROR);
        assertEquals(0x4, H2FrameHandler.ERROR_SETTINGS_TIMEOUT);
        assertEquals(0x5, H2FrameHandler.ERROR_STREAM_CLOSED);
        assertEquals(0x6, H2FrameHandler.ERROR_FRAME_SIZE_ERROR);
        assertEquals(0x7, H2FrameHandler.ERROR_REFUSED_STREAM);
        assertEquals(0x8, H2FrameHandler.ERROR_CANCEL);
        assertEquals(0x9, H2FrameHandler.ERROR_COMPRESSION_ERROR);
        assertEquals(0xa, H2FrameHandler.ERROR_CONNECT_ERROR);
        assertEquals(0xb, H2FrameHandler.ERROR_ENHANCE_YOUR_CALM);
        assertEquals(0xc, H2FrameHandler.ERROR_INADEQUATE_SECURITY);
        assertEquals(0xd, H2FrameHandler.ERROR_HTTP_1_1_REQUIRED);
    }

    // ========== Flag Constants ==========

    @Test
    public void testFlagValues() {
        assertEquals(0x1, H2FrameHandler.FLAG_ACK);
        assertEquals(0x1, H2FrameHandler.FLAG_END_STREAM);
        assertEquals(0x4, H2FrameHandler.FLAG_END_HEADERS);
        assertEquals(0x8, H2FrameHandler.FLAG_PADDED);
        assertEquals(0x20, H2FrameHandler.FLAG_PRIORITY);
    }

    // ========== Settings Constants ==========

    @Test
    public void testSettingsValues() {
        assertEquals(0x1, H2FrameHandler.SETTINGS_HEADER_TABLE_SIZE);
        assertEquals(0x2, H2FrameHandler.SETTINGS_ENABLE_PUSH);
        assertEquals(0x3, H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS);
        assertEquals(0x4, H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE);
        assertEquals(0x5, H2FrameHandler.SETTINGS_MAX_FRAME_SIZE);
        assertEquals(0x6, H2FrameHandler.SETTINGS_MAX_HEADER_LIST_SIZE);
    }
}
