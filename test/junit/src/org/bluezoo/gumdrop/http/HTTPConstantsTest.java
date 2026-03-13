package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HTTPConstants}.
 */
public class HTTPConstantsTest {

    @Test
    public void testGetMessageKnownCodes() {
        assertEquals("Continue", HTTPConstants.getMessage(100));
        assertEquals("OK", HTTPConstants.getMessage(200));
        assertEquals("Moved Permanently", HTTPConstants.getMessage(301));
        assertEquals("Bad Request", HTTPConstants.getMessage(400));
        assertEquals("Not Found", HTTPConstants.getMessage(404));
        assertEquals("Internal Server Error", HTTPConstants.getMessage(500));
    }

    @Test
    public void testGetMessageAllInformational() {
        assertEquals("Continue", HTTPConstants.getMessage(100));
        assertEquals("Switching Protocols", HTTPConstants.getMessage(101));
        assertEquals("Processing", HTTPConstants.getMessage(102));
        assertEquals("Early Hints", HTTPConstants.getMessage(103));
    }

    @Test
    public void testGetMessageRedirectionCodes() {
        assertEquals("Multiple Choices", HTTPConstants.getMessage(300));
        assertEquals("Found", HTTPConstants.getMessage(302));
        assertEquals("See Other", HTTPConstants.getMessage(303));
        assertEquals("Not Modified", HTTPConstants.getMessage(304));
        assertEquals("Temporary Redirect", HTTPConstants.getMessage(307));
        assertEquals("Permanent Redirect", HTTPConstants.getMessage(308));
    }

    @Test
    public void testGetMessageWebDAVCodes() {
        assertEquals("Multi-Status", HTTPConstants.getMessage(207));
        assertEquals("Already Reported", HTTPConstants.getMessage(208));
        assertEquals("Locked", HTTPConstants.getMessage(423));
        assertEquals("Failed Dependency", HTTPConstants.getMessage(424));
        assertEquals("Insufficient Storage", HTTPConstants.getMessage(507));
        assertEquals("Loop Detected", HTTPConstants.getMessage(508));
    }

    @Test
    public void testGetMessageTeapot() {
        assertEquals("I'm a Teapot", HTTPConstants.getMessage(418));
    }

    @Test
    public void testGetMessageUnknownCode() {
        assertEquals("Unknown Status Code", HTTPConstants.getMessage(999));
        assertEquals("Unknown Status Code", HTTPConstants.getMessage(0));
        assertEquals("Unknown Status Code", HTTPConstants.getMessage(-1));
    }

    @Test
    public void testMessagesMapNotEmpty() {
        assertFalse(HTTPConstants.messages.isEmpty());
    }

    @Test
    public void testMessagesMapContainsStandardCodes() {
        assertTrue(HTTPConstants.messages.containsKey(200));
        assertTrue(HTTPConstants.messages.containsKey(404));
        assertTrue(HTTPConstants.messages.containsKey(500));
    }

    @Test
    public void testRFC9110ReasonPhrases() {
        assertEquals("Content Too Large", HTTPConstants.getMessage(413));
        assertEquals("URI Too Long", HTTPConstants.getMessage(414));
        assertEquals("Range Not Satisfiable", HTTPConstants.getMessage(416));
        assertEquals("Unprocessable Content", HTTPConstants.getMessage(422));
    }
}
