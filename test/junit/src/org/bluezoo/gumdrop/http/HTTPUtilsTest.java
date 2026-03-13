package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HTTPUtils}.
 */
public class HTTPUtilsTest {

    // ========== isValidMethod ==========

    @Test
    public void testValidMethods() {
        assertTrue(HTTPUtils.isValidMethod("GET"));
        assertTrue(HTTPUtils.isValidMethod("POST"));
        assertTrue(HTTPUtils.isValidMethod("PUT"));
        assertTrue(HTTPUtils.isValidMethod("DELETE"));
        assertTrue(HTTPUtils.isValidMethod("PATCH"));
        assertTrue(HTTPUtils.isValidMethod("HEAD"));
        assertTrue(HTTPUtils.isValidMethod("OPTIONS"));
        assertTrue(HTTPUtils.isValidMethod("TRACE"));
        assertTrue(HTTPUtils.isValidMethod("CONNECT"));
    }

    @Test
    public void testValidMethodWebDAV() {
        assertTrue(HTTPUtils.isValidMethod("PROPFIND"));
        assertTrue(HTTPUtils.isValidMethod("PROPPATCH"));
        assertTrue(HTTPUtils.isValidMethod("MKCOL"));
        assertTrue(HTTPUtils.isValidMethod("COPY"));
        assertTrue(HTTPUtils.isValidMethod("MOVE"));
        assertTrue(HTTPUtils.isValidMethod("LOCK"));
        assertTrue(HTTPUtils.isValidMethod("UNLOCK"));
    }

    @Test
    public void testInvalidMethodNull() {
        assertFalse(HTTPUtils.isValidMethod((String) null));
    }

    @Test
    public void testInvalidMethodEmpty() {
        assertFalse(HTTPUtils.isValidMethod(""));
    }

    @Test
    public void testInvalidMethodWithSpace() {
        assertFalse(HTTPUtils.isValidMethod("GET POST"));
    }

    @Test
    public void testInvalidMethodWithControlChar() {
        assertFalse(HTTPUtils.isValidMethod("GET\r\n"));
        assertFalse(HTTPUtils.isValidMethod("GET\0"));
    }

    @Test
    public void testValidMethodCharSequence() {
        assertTrue(HTTPUtils.isValidMethod((CharSequence) "GET"));
        assertFalse(HTTPUtils.isValidMethod((CharSequence) null));
        assertFalse(HTTPUtils.isValidMethod(new StringBuilder()));
    }

    // ========== isTokenChar ==========

    @Test
    public void testTokenCharAlphanumeric() {
        assertTrue(HTTPUtils.isTokenChar('A'));
        assertTrue(HTTPUtils.isTokenChar('z'));
        assertTrue(HTTPUtils.isTokenChar('0'));
        assertTrue(HTTPUtils.isTokenChar('9'));
    }

    @Test
    public void testTokenCharSpecials() {
        assertTrue(HTTPUtils.isTokenChar('!'));
        assertTrue(HTTPUtils.isTokenChar('#'));
        assertTrue(HTTPUtils.isTokenChar('$'));
        assertTrue(HTTPUtils.isTokenChar('%'));
        assertTrue(HTTPUtils.isTokenChar('&'));
        assertTrue(HTTPUtils.isTokenChar('\''));
        assertTrue(HTTPUtils.isTokenChar('*'));
        assertTrue(HTTPUtils.isTokenChar('+'));
        assertTrue(HTTPUtils.isTokenChar('-'));
        assertTrue(HTTPUtils.isTokenChar('.'));
        assertTrue(HTTPUtils.isTokenChar('^'));
        assertTrue(HTTPUtils.isTokenChar('_'));
        assertTrue(HTTPUtils.isTokenChar('`'));
        assertTrue(HTTPUtils.isTokenChar('|'));
        assertTrue(HTTPUtils.isTokenChar('~'));
    }

    @Test
    public void testTokenCharInvalid() {
        assertFalse(HTTPUtils.isTokenChar(' '));
        assertFalse(HTTPUtils.isTokenChar('\t'));
        assertFalse(HTTPUtils.isTokenChar('/'));
        assertFalse(HTTPUtils.isTokenChar('('));
        assertFalse(HTTPUtils.isTokenChar(')'));
        assertFalse(HTTPUtils.isTokenChar('<'));
        assertFalse(HTTPUtils.isTokenChar('>'));
        assertFalse(HTTPUtils.isTokenChar('@'));
        assertFalse(HTTPUtils.isTokenChar('['));
        assertFalse(HTTPUtils.isTokenChar(']'));
        assertFalse(HTTPUtils.isTokenChar('{'));
        assertFalse(HTTPUtils.isTokenChar('}'));
        assertFalse(HTTPUtils.isTokenChar('"'));
        assertFalse(HTTPUtils.isTokenChar('\\'));
        assertFalse(HTTPUtils.isTokenChar(','));
        assertFalse(HTTPUtils.isTokenChar(';'));
        assertFalse(HTTPUtils.isTokenChar('='));
    }

    @Test
    public void testTokenCharHighByte() {
        assertFalse(HTTPUtils.isTokenChar((char) 128));
        assertFalse(HTTPUtils.isTokenChar((char) 255));
    }

    // ========== isValidRequestTarget ==========

    @Test
    public void testValidRequestTargets() {
        assertTrue(HTTPUtils.isValidRequestTarget("/"));
        assertTrue(HTTPUtils.isValidRequestTarget("/index.html"));
        assertTrue(HTTPUtils.isValidRequestTarget("/path/to/resource"));
        assertTrue(HTTPUtils.isValidRequestTarget("/search?q=hello&lang=en"));
        assertTrue(HTTPUtils.isValidRequestTarget("/path?key=value#fragment"));
        assertTrue(HTTPUtils.isValidRequestTarget("*"));
    }

    @Test
    public void testValidRequestTargetWithPercentEncoding() {
        assertTrue(HTTPUtils.isValidRequestTarget("/path%20with%20spaces"));
        assertTrue(HTTPUtils.isValidRequestTarget("/caf%C3%A9"));
    }

    @Test
    public void testValidRequestTargetIPv6() {
        assertTrue(HTTPUtils.isValidRequestTarget("/[::1]:8080/path"));
    }

    @Test
    public void testInvalidRequestTargetNull() {
        assertFalse(HTTPUtils.isValidRequestTarget((String) null));
    }

    @Test
    public void testInvalidRequestTargetEmpty() {
        assertFalse(HTTPUtils.isValidRequestTarget(""));
    }

    @Test
    public void testInvalidRequestTargetWithSpace() {
        assertFalse(HTTPUtils.isValidRequestTarget("/path with spaces"));
    }

    @Test
    public void testInvalidRequestTargetWithControlChar() {
        assertFalse(HTTPUtils.isValidRequestTarget("/path\r\n"));
        assertFalse(HTTPUtils.isValidRequestTarget("/path\0"));
    }

    @Test
    public void testRequestTargetCharSequence() {
        assertTrue(HTTPUtils.isValidRequestTarget((CharSequence) "/index.html"));
        assertFalse(HTTPUtils.isValidRequestTarget((CharSequence) null));
    }

    // ========== isRequestTargetChar ==========

    @Test
    public void testRequestTargetCharValid() {
        assertTrue(HTTPUtils.isRequestTargetChar('/'));
        assertTrue(HTTPUtils.isRequestTargetChar('?'));
        assertTrue(HTTPUtils.isRequestTargetChar('#'));
        assertTrue(HTTPUtils.isRequestTargetChar('='));
        assertTrue(HTTPUtils.isRequestTargetChar('&'));
        assertTrue(HTTPUtils.isRequestTargetChar('%'));
        assertTrue(HTTPUtils.isRequestTargetChar(':'));
        assertTrue(HTTPUtils.isRequestTargetChar('@'));
    }

    @Test
    public void testRequestTargetCharInvalid() {
        assertFalse(HTTPUtils.isRequestTargetChar(' '));
        assertFalse(HTTPUtils.isRequestTargetChar('\t'));
        assertFalse(HTTPUtils.isRequestTargetChar('{'));
        assertFalse(HTTPUtils.isRequestTargetChar('}'));
        assertFalse(HTTPUtils.isRequestTargetChar('"'));
        assertFalse(HTTPUtils.isRequestTargetChar('<'));
        assertFalse(HTTPUtils.isRequestTargetChar('>'));
        assertFalse(HTTPUtils.isRequestTargetChar('\\'));
    }

    // ========== isValidHeaderName ==========

    @Test
    public void testValidHeaderNames() {
        assertTrue(HTTPUtils.isValidHeaderName("Content-Type"));
        assertTrue(HTTPUtils.isValidHeaderName("Accept"));
        assertTrue(HTTPUtils.isValidHeaderName("X-Custom-Header"));
        assertTrue(HTTPUtils.isValidHeaderName("x-lowercase"));
        assertTrue(HTTPUtils.isValidHeaderName("Content_Length"));
    }

    @Test
    public void testValidPseudoHeaders() {
        assertTrue(HTTPUtils.isValidHeaderName(":status"));
        assertTrue(HTTPUtils.isValidHeaderName(":path"));
        assertTrue(HTTPUtils.isValidHeaderName(":method"));
        assertTrue(HTTPUtils.isValidHeaderName(":scheme"));
        assertTrue(HTTPUtils.isValidHeaderName(":authority"));
    }

    @Test
    public void testInvalidHeaderNameNull() {
        assertFalse(HTTPUtils.isValidHeaderName(null));
    }

    @Test
    public void testInvalidHeaderNameEmpty() {
        assertFalse(HTTPUtils.isValidHeaderName(""));
    }

    @Test
    public void testInvalidHeaderNameJustColon() {
        assertFalse(HTTPUtils.isValidHeaderName(":"));
    }

    @Test
    public void testInvalidHeaderNameColonInMiddle() {
        assertFalse(HTTPUtils.isValidHeaderName("Content:Type"));
    }

    @Test
    public void testInvalidHeaderNameWithSpace() {
        assertFalse(HTTPUtils.isValidHeaderName("Content Type"));
    }

    // ========== isValidHeaderValue ==========

    @Test
    public void testValidHeaderValues() {
        assertTrue(HTTPUtils.isValidHeaderValue("text/html"));
        assertTrue(HTTPUtils.isValidHeaderValue("Hello, World!"));
        assertTrue(HTTPUtils.isValidHeaderValue("value with\ttab"));
        assertTrue(HTTPUtils.isValidHeaderValue(""));
    }

    @Test
    public void testValidHeaderValueNull() {
        assertTrue(HTTPUtils.isValidHeaderValue(null));
    }

    @Test
    public void testInvalidHeaderValueControlChars() {
        assertFalse(HTTPUtils.isValidHeaderValue("value\r\nwith newlines"));
        assertFalse(HTTPUtils.isValidHeaderValue("value\0with null"));
        assertFalse(HTTPUtils.isValidHeaderValue("\u0001control"));
    }

    @Test
    public void testValidHeaderValueHighBytes() {
        assertTrue(HTTPUtils.isValidHeaderValue("caf\u00E9"));
    }
}
