package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HTTPVersion}.
 */
public class HTTPVersionTest {

    @Test
    public void testToString() {
        assertEquals("HTTP/1.0", HTTPVersion.HTTP_1_0.toString());
        assertEquals("HTTP/1.1", HTTPVersion.HTTP_1_1.toString());
        assertEquals("HTTP/2.0", HTTPVersion.HTTP_2_0.toString());
        assertEquals("HTTP/3", HTTPVersion.HTTP_3.toString());
        assertEquals("(unknown)", HTTPVersion.UNKNOWN.toString());
    }

    @Test
    public void testAlpnIdentifiers() {
        assertEquals("http/1.0", HTTPVersion.HTTP_1_0.getAlpnIdentifier());
        assertEquals("http/1.1", HTTPVersion.HTTP_1_1.getAlpnIdentifier());
        assertEquals("h2", HTTPVersion.HTTP_2_0.getAlpnIdentifier());
        assertEquals("h3", HTTPVersion.HTTP_3.getAlpnIdentifier());
        assertNull(HTTPVersion.UNKNOWN.getAlpnIdentifier());
    }

    @Test
    public void testFromVersionString() {
        assertEquals(HTTPVersion.HTTP_1_0, HTTPVersion.fromVersionString("HTTP/1.0"));
        assertEquals(HTTPVersion.HTTP_1_1, HTTPVersion.fromVersionString("HTTP/1.1"));
        assertEquals(HTTPVersion.HTTP_2_0, HTTPVersion.fromVersionString("HTTP/2.0"));
        assertEquals(HTTPVersion.HTTP_3, HTTPVersion.fromVersionString("HTTP/3"));
    }

    @Test
    public void testFromVersionStringNull() {
        assertEquals(HTTPVersion.UNKNOWN, HTTPVersion.fromVersionString(null));
    }

    @Test
    public void testFromVersionStringUnrecognized() {
        assertEquals(HTTPVersion.UNKNOWN, HTTPVersion.fromVersionString("HTTP/4.0"));
        assertEquals(HTTPVersion.UNKNOWN, HTTPVersion.fromVersionString(""));
        assertEquals(HTTPVersion.UNKNOWN, HTTPVersion.fromVersionString("garbage"));
    }

    @Test
    public void testFromAlpnIdentifier() {
        assertEquals(HTTPVersion.HTTP_1_0, HTTPVersion.fromAlpnIdentifier("http/1.0"));
        assertEquals(HTTPVersion.HTTP_1_1, HTTPVersion.fromAlpnIdentifier("http/1.1"));
        assertEquals(HTTPVersion.HTTP_2_0, HTTPVersion.fromAlpnIdentifier("h2"));
        assertEquals(HTTPVersion.HTTP_3, HTTPVersion.fromAlpnIdentifier("h3"));
    }

    @Test
    public void testFromAlpnIdentifierH2c() {
        assertEquals(HTTPVersion.HTTP_2_0, HTTPVersion.fromAlpnIdentifier("h2c"));
    }

    @Test
    public void testFromAlpnIdentifierNull() {
        assertEquals(HTTPVersion.UNKNOWN, HTTPVersion.fromAlpnIdentifier(null));
    }

    @Test
    public void testFromAlpnIdentifierUnrecognized() {
        assertEquals(HTTPVersion.UNKNOWN, HTTPVersion.fromAlpnIdentifier("h4"));
        assertEquals(HTTPVersion.UNKNOWN, HTTPVersion.fromAlpnIdentifier(""));
    }

    @Test
    public void testFromStringPrefersAlpn() {
        assertEquals(HTTPVersion.HTTP_2_0, HTTPVersion.fromString("h2"));
        assertEquals(HTTPVersion.HTTP_3, HTTPVersion.fromString("h3"));
        assertEquals(HTTPVersion.HTTP_1_1, HTTPVersion.fromString("http/1.1"));
    }

    @Test
    public void testFromStringFallsBackToVersionString() {
        assertEquals(HTTPVersion.HTTP_1_1, HTTPVersion.fromString("HTTP/1.1"));
        assertEquals(HTTPVersion.HTTP_2_0, HTTPVersion.fromString("HTTP/2.0"));
    }

    @Test
    public void testFromStringUnrecognized() {
        assertEquals(HTTPVersion.UNKNOWN, HTTPVersion.fromString(null));
        assertEquals(HTTPVersion.UNKNOWN, HTTPVersion.fromString("nonsense"));
    }

    @Test
    public void testSupportsMultiplexing() {
        assertFalse(HTTPVersion.HTTP_1_0.supportsMultiplexing());
        assertFalse(HTTPVersion.HTTP_1_1.supportsMultiplexing());
        assertTrue(HTTPVersion.HTTP_2_0.supportsMultiplexing());
        assertTrue(HTTPVersion.HTTP_3.supportsMultiplexing());
        assertFalse(HTTPVersion.UNKNOWN.supportsMultiplexing());
    }

    @Test
    public void testRequiresHostHeader() {
        assertFalse(HTTPVersion.HTTP_1_0.requiresHostHeader());
        assertTrue(HTTPVersion.HTTP_1_1.requiresHostHeader());
        assertTrue(HTTPVersion.HTTP_2_0.requiresHostHeader());
        assertTrue(HTTPVersion.HTTP_3.requiresHostHeader());
        assertFalse(HTTPVersion.UNKNOWN.requiresHostHeader());
    }

    @Test
    public void testHttp1FramingHeadersOnMultiplexedTransports() {
        assertTrue(HTTPVersion.isHttp1FramingHeader(
                "Content-Length", "42"));
        assertTrue(HTTPVersion.isHttp1FramingHeader(
                "Transfer-Encoding", "chunked"));
        assertTrue(HTTPVersion.isHttp1FramingHeader(
                "Connection", "close"));
        assertFalse(HTTPVersion.isHttp1FramingHeader(
                "TE", "trailers"));
        assertTrue(HTTPVersion.isHttp1FramingHeader(
                "TE", "gzip"));
        assertFalse(HTTPVersion.isHttp1FramingHeader(
                "Content-Type", "text/plain"));
    }

    @Test
    public void testStripHttp1FramingHeaders() {
        Headers headers = new Headers();
        headers.add("Content-Length", "42");
        headers.add("Transfer-Encoding", "chunked");
        headers.add("Content-Type", "text/plain");
        headers.add(":method", "GET");
        HTTPVersion.stripHttp1FramingHeaders(headers);
        assertNull(headers.getValue("Content-Length"));
        assertNull(headers.getValue("Transfer-Encoding"));
        assertEquals("text/plain", headers.getValue("Content-Type"));
        assertEquals("GET", headers.getValue(":method"));
    }
}
