package org.bluezoo.gumdrop.http;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HttpConstants}.
 */
public class HTTPConstantsTest {

    /**
     * getMessageBytes is a pre-encoded, allocation-free counterpart to
     * getMessage for wire-writing callers (HttpProtocolHandler); it must
     * describe the exact same text for every known code, and fall back
     * the same way for an unknown one.
     */
    @Test
    public void testGetMessageBytesMatchesGetMessageForKnownCodes() {
        int[] codes = { 100, 200, 204, 301, 400, 404, 418, 500, 511 };
        for (int code : codes) {
            byte[] expected = HttpConstants.getMessage(code).getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals("mismatch for status " + code,
                    expected, HttpConstants.getMessageBytes(code));
        }
    }

    @Test
    public void testGetMessageBytesUnknownCodeFallsBackLikeGetMessage() {
        byte[] expected = HttpConstants.getMessage(999).getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(expected, HttpConstants.getMessageBytes(999));
    }

    @Test
    public void testGetMessageKnownCodes() {
        assertEquals("Continue", HttpConstants.getMessage(100));
        assertEquals("OK", HttpConstants.getMessage(200));
        assertEquals("Moved Permanently", HttpConstants.getMessage(301));
        assertEquals("Bad Request", HttpConstants.getMessage(400));
        assertEquals("Not Found", HttpConstants.getMessage(404));
        assertEquals("Internal Server Error", HttpConstants.getMessage(500));
    }

    @Test
    public void testGetMessageAllInformational() {
        assertEquals("Continue", HttpConstants.getMessage(100));
        assertEquals("Switching Protocols", HttpConstants.getMessage(101));
        assertEquals("Processing", HttpConstants.getMessage(102));
        assertEquals("Early Hints", HttpConstants.getMessage(103));
    }

    @Test
    public void testGetMessageRedirectionCodes() {
        assertEquals("Multiple Choices", HttpConstants.getMessage(300));
        assertEquals("Found", HttpConstants.getMessage(302));
        assertEquals("See Other", HttpConstants.getMessage(303));
        assertEquals("Not Modified", HttpConstants.getMessage(304));
        assertEquals("Temporary Redirect", HttpConstants.getMessage(307));
        assertEquals("Permanent Redirect", HttpConstants.getMessage(308));
    }

    @Test
    public void testGetMessageWebDAVCodes() {
        assertEquals("Multi-Status", HttpConstants.getMessage(207));
        assertEquals("Already Reported", HttpConstants.getMessage(208));
        assertEquals("Locked", HttpConstants.getMessage(423));
        assertEquals("Failed Dependency", HttpConstants.getMessage(424));
        assertEquals("Insufficient Storage", HttpConstants.getMessage(507));
        assertEquals("Loop Detected", HttpConstants.getMessage(508));
    }

    @Test
    public void testGetMessageTeapot() {
        assertEquals("I'm a Teapot", HttpConstants.getMessage(418));
    }

    @Test
    public void testGetMessageUnknownCode() {
        assertEquals("Unknown Status Code", HttpConstants.getMessage(999));
        assertEquals("Unknown Status Code", HttpConstants.getMessage(0));
        assertEquals("Unknown Status Code", HttpConstants.getMessage(-1));
    }

    @Test
    public void testMessagesMapNotEmpty() {
        assertFalse(HttpConstants.messages.isEmpty());
    }

    @Test
    public void testMessagesMapContainsStandardCodes() {
        assertTrue(HttpConstants.messages.containsKey(200));
        assertTrue(HttpConstants.messages.containsKey(404));
        assertTrue(HttpConstants.messages.containsKey(500));
    }

    @Test
    public void testRFC9110ReasonPhrases() {
        assertEquals("Content Too Large", HttpConstants.getMessage(413));
        assertEquals("URI Too Long", HttpConstants.getMessage(414));
        assertEquals("Range Not Satisfiable", HttpConstants.getMessage(416));
        assertEquals("Unprocessable Content", HttpConstants.getMessage(422));
    }
}
