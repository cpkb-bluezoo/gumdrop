package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HttpUtils}.
 */
public class HTTPUtilsTest {

    // ========== isValidMethod ==========

    @Test
    public void testValidMethods() {
        assertTrue(HttpUtils.isValidMethod("GET"));
        assertTrue(HttpUtils.isValidMethod("POST"));
        assertTrue(HttpUtils.isValidMethod("PUT"));
        assertTrue(HttpUtils.isValidMethod("DELETE"));
        assertTrue(HttpUtils.isValidMethod("PATCH"));
        assertTrue(HttpUtils.isValidMethod("HEAD"));
        assertTrue(HttpUtils.isValidMethod("OPTIONS"));
        assertTrue(HttpUtils.isValidMethod("TRACE"));
        assertTrue(HttpUtils.isValidMethod("CONNECT"));
    }

    @Test
    public void testValidMethodWebDAV() {
        assertTrue(HttpUtils.isValidMethod("PROPFIND"));
        assertTrue(HttpUtils.isValidMethod("PROPPATCH"));
        assertTrue(HttpUtils.isValidMethod("MKCOL"));
        assertTrue(HttpUtils.isValidMethod("COPY"));
        assertTrue(HttpUtils.isValidMethod("MOVE"));
        assertTrue(HttpUtils.isValidMethod("LOCK"));
        assertTrue(HttpUtils.isValidMethod("UNLOCK"));
    }

    @Test
    public void testInvalidMethodNull() {
        assertFalse(HttpUtils.isValidMethod((String) null));
    }

    @Test
    public void testInvalidMethodEmpty() {
        assertFalse(HttpUtils.isValidMethod(""));
    }

    @Test
    public void testInvalidMethodWithSpace() {
        assertFalse(HttpUtils.isValidMethod("GET POST"));
    }

    @Test
    public void testInvalidMethodWithControlChar() {
        assertFalse(HttpUtils.isValidMethod("GET\r\n"));
        assertFalse(HttpUtils.isValidMethod("GET\0"));
    }

    @Test
    public void testValidMethodCharSequence() {
        assertTrue(HttpUtils.isValidMethod((CharSequence) "GET"));
        assertFalse(HttpUtils.isValidMethod((CharSequence) null));
        assertFalse(HttpUtils.isValidMethod(new StringBuilder()));
    }

    // ========== isTokenChar ==========

    @Test
    public void testTokenCharAlphanumeric() {
        assertTrue(HttpUtils.isTokenChar('A'));
        assertTrue(HttpUtils.isTokenChar('z'));
        assertTrue(HttpUtils.isTokenChar('0'));
        assertTrue(HttpUtils.isTokenChar('9'));
    }

    @Test
    public void testTokenCharSpecials() {
        assertTrue(HttpUtils.isTokenChar('!'));
        assertTrue(HttpUtils.isTokenChar('#'));
        assertTrue(HttpUtils.isTokenChar('$'));
        assertTrue(HttpUtils.isTokenChar('%'));
        assertTrue(HttpUtils.isTokenChar('&'));
        assertTrue(HttpUtils.isTokenChar('\''));
        assertTrue(HttpUtils.isTokenChar('*'));
        assertTrue(HttpUtils.isTokenChar('+'));
        assertTrue(HttpUtils.isTokenChar('-'));
        assertTrue(HttpUtils.isTokenChar('.'));
        assertTrue(HttpUtils.isTokenChar('^'));
        assertTrue(HttpUtils.isTokenChar('_'));
        assertTrue(HttpUtils.isTokenChar('`'));
        assertTrue(HttpUtils.isTokenChar('|'));
        assertTrue(HttpUtils.isTokenChar('~'));
    }

    @Test
    public void testTokenCharInvalid() {
        assertFalse(HttpUtils.isTokenChar(' '));
        assertFalse(HttpUtils.isTokenChar('\t'));
        assertFalse(HttpUtils.isTokenChar('/'));
        assertFalse(HttpUtils.isTokenChar('('));
        assertFalse(HttpUtils.isTokenChar(')'));
        assertFalse(HttpUtils.isTokenChar('<'));
        assertFalse(HttpUtils.isTokenChar('>'));
        assertFalse(HttpUtils.isTokenChar('@'));
        assertFalse(HttpUtils.isTokenChar('['));
        assertFalse(HttpUtils.isTokenChar(']'));
        assertFalse(HttpUtils.isTokenChar('{'));
        assertFalse(HttpUtils.isTokenChar('}'));
        assertFalse(HttpUtils.isTokenChar('"'));
        assertFalse(HttpUtils.isTokenChar('\\'));
        assertFalse(HttpUtils.isTokenChar(','));
        assertFalse(HttpUtils.isTokenChar(';'));
        assertFalse(HttpUtils.isTokenChar('='));
    }

    @Test
    public void testTokenCharHighByte() {
        assertFalse(HttpUtils.isTokenChar((char) 128));
        assertFalse(HttpUtils.isTokenChar((char) 255));
    }

    // ========== isValidRequestTarget ==========

    @Test
    public void testValidRequestTargets() {
        assertTrue(HttpUtils.isValidRequestTarget("/"));
        assertTrue(HttpUtils.isValidRequestTarget("/index.html"));
        assertTrue(HttpUtils.isValidRequestTarget("/path/to/resource"));
        assertTrue(HttpUtils.isValidRequestTarget("/search?q=hello&lang=en"));
        assertTrue(HttpUtils.isValidRequestTarget("/path?key=value#fragment"));
        assertTrue(HttpUtils.isValidRequestTarget("*"));
    }

    @Test
    public void testValidRequestTargetWithPercentEncoding() {
        assertTrue(HttpUtils.isValidRequestTarget("/path%20with%20spaces"));
        assertTrue(HttpUtils.isValidRequestTarget("/caf%C3%A9"));
    }

    @Test
    public void testValidRequestTargetIPv6() {
        assertTrue(HttpUtils.isValidRequestTarget("/[::1]:8080/path"));
    }

    @Test
    public void testInvalidRequestTargetNull() {
        assertFalse(HttpUtils.isValidRequestTarget((String) null));
    }

    @Test
    public void testInvalidRequestTargetEmpty() {
        assertFalse(HttpUtils.isValidRequestTarget(""));
    }

    @Test
    public void testInvalidRequestTargetWithSpace() {
        assertFalse(HttpUtils.isValidRequestTarget("/path with spaces"));
    }

    @Test
    public void testInvalidRequestTargetWithControlChar() {
        assertFalse(HttpUtils.isValidRequestTarget("/path\r\n"));
        assertFalse(HttpUtils.isValidRequestTarget("/path\0"));
    }

    @Test
    public void testRequestTargetCharSequence() {
        assertTrue(HttpUtils.isValidRequestTarget((CharSequence) "/index.html"));
        assertFalse(HttpUtils.isValidRequestTarget((CharSequence) null));
    }

    // ========== isRequestTargetChar ==========

    @Test
    public void testRequestTargetCharValid() {
        assertTrue(HttpUtils.isRequestTargetChar('/'));
        assertTrue(HttpUtils.isRequestTargetChar('?'));
        assertTrue(HttpUtils.isRequestTargetChar('#'));
        assertTrue(HttpUtils.isRequestTargetChar('='));
        assertTrue(HttpUtils.isRequestTargetChar('&'));
        assertTrue(HttpUtils.isRequestTargetChar('%'));
        assertTrue(HttpUtils.isRequestTargetChar(':'));
        assertTrue(HttpUtils.isRequestTargetChar('@'));
    }

    @Test
    public void testRequestTargetCharInvalid() {
        assertFalse(HttpUtils.isRequestTargetChar(' '));
        assertFalse(HttpUtils.isRequestTargetChar('\t'));
        assertFalse(HttpUtils.isRequestTargetChar('{'));
        assertFalse(HttpUtils.isRequestTargetChar('}'));
        assertFalse(HttpUtils.isRequestTargetChar('"'));
        assertFalse(HttpUtils.isRequestTargetChar('<'));
        assertFalse(HttpUtils.isRequestTargetChar('>'));
        assertFalse(HttpUtils.isRequestTargetChar('\\'));
    }

    // ========== isValidHeaderName ==========

    @Test
    public void testValidHeaderNames() {
        assertTrue(HttpUtils.isValidHeaderName("Content-Type"));
        assertTrue(HttpUtils.isValidHeaderName("Accept"));
        assertTrue(HttpUtils.isValidHeaderName("X-Custom-Header"));
        assertTrue(HttpUtils.isValidHeaderName("x-lowercase"));
        assertTrue(HttpUtils.isValidHeaderName("Content_Length"));
    }

    @Test
    public void testValidPseudoHeaders() {
        assertTrue(HttpUtils.isValidHeaderName(":status"));
        assertTrue(HttpUtils.isValidHeaderName(":path"));
        assertTrue(HttpUtils.isValidHeaderName(":method"));
        assertTrue(HttpUtils.isValidHeaderName(":scheme"));
        assertTrue(HttpUtils.isValidHeaderName(":authority"));
    }

    @Test
    public void testInvalidHeaderNameNull() {
        assertFalse(HttpUtils.isValidHeaderName(null));
    }

    @Test
    public void testInvalidHeaderNameEmpty() {
        assertFalse(HttpUtils.isValidHeaderName(""));
    }

    @Test
    public void testInvalidHeaderNameJustColon() {
        assertFalse(HttpUtils.isValidHeaderName(":"));
    }

    @Test
    public void testInvalidHeaderNameColonInMiddle() {
        assertFalse(HttpUtils.isValidHeaderName("Content:Type"));
    }

    @Test
    public void testInvalidHeaderNameWithSpace() {
        assertFalse(HttpUtils.isValidHeaderName("Content Type"));
    }

    // ========== isValidHeaderValue ==========

    @Test
    public void testValidHeaderValues() {
        assertTrue(HttpUtils.isValidHeaderValue("text/html"));
        assertTrue(HttpUtils.isValidHeaderValue("Hello, World!"));
        assertTrue(HttpUtils.isValidHeaderValue("value with\ttab"));
        assertTrue(HttpUtils.isValidHeaderValue(""));
    }

    @Test
    public void testValidHeaderValueNull() {
        assertTrue(HttpUtils.isValidHeaderValue(null));
    }

    @Test
    public void testInvalidHeaderValueControlChars() {
        assertFalse(HttpUtils.isValidHeaderValue("value\r\nwith newlines"));
        assertFalse(HttpUtils.isValidHeaderValue("value\0with null"));
        assertFalse(HttpUtils.isValidHeaderValue("\u0001control"));
    }

    @Test
    public void testValidHeaderValueHighBytes() {
        assertTrue(HttpUtils.isValidHeaderValue("caf\u00E9"));
    }
}
