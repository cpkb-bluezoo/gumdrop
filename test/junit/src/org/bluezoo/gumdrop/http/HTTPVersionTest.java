/*
 * HTTPVersionTest.java
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

package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HttpVersion}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPVersionTest {

    @Test
    public void testToString() {
        assertEquals("HTTP/1.0", HttpVersion.HTTP_1_0.toString());
        assertEquals("HTTP/1.1", HttpVersion.HTTP_1_1.toString());
        assertEquals("HTTP/2.0", HttpVersion.HTTP_2_0.toString());
        assertEquals("HTTP/3", HttpVersion.HTTP_3.toString());
        assertEquals("(unknown)", HttpVersion.UNKNOWN.toString());
    }

    @Test
    public void testAlpnIdentifiers() {
        assertEquals("http/1.0", HttpVersion.HTTP_1_0.getAlpnIdentifier());
        assertEquals("http/1.1", HttpVersion.HTTP_1_1.getAlpnIdentifier());
        assertEquals("h2", HttpVersion.HTTP_2_0.getAlpnIdentifier());
        assertEquals("h3", HttpVersion.HTTP_3.getAlpnIdentifier());
        assertNull(HttpVersion.UNKNOWN.getAlpnIdentifier());
    }

    @Test
    public void testFromVersionString() {
        assertEquals(HttpVersion.HTTP_1_0, HttpVersion.fromVersionString("HTTP/1.0"));
        assertEquals(HttpVersion.HTTP_1_1, HttpVersion.fromVersionString("HTTP/1.1"));
        assertEquals(HttpVersion.HTTP_2_0, HttpVersion.fromVersionString("HTTP/2.0"));
        assertEquals(HttpVersion.HTTP_3, HttpVersion.fromVersionString("HTTP/3"));
    }

    @Test
    public void testFromVersionStringNull() {
        assertEquals(HttpVersion.UNKNOWN, HttpVersion.fromVersionString(null));
    }

    @Test
    public void testFromVersionStringUnrecognized() {
        assertEquals(HttpVersion.UNKNOWN, HttpVersion.fromVersionString("HTTP/4.0"));
        assertEquals(HttpVersion.UNKNOWN, HttpVersion.fromVersionString(""));
        assertEquals(HttpVersion.UNKNOWN, HttpVersion.fromVersionString("garbage"));
    }

    @Test
    public void testFromAlpnIdentifier() {
        assertEquals(HttpVersion.HTTP_1_0, HttpVersion.fromAlpnIdentifier("http/1.0"));
        assertEquals(HttpVersion.HTTP_1_1, HttpVersion.fromAlpnIdentifier("http/1.1"));
        assertEquals(HttpVersion.HTTP_2_0, HttpVersion.fromAlpnIdentifier("h2"));
        assertEquals(HttpVersion.HTTP_3, HttpVersion.fromAlpnIdentifier("h3"));
    }

    @Test
    public void testFromAlpnIdentifierH2c() {
        assertEquals(HttpVersion.HTTP_2_0, HttpVersion.fromAlpnIdentifier("h2c"));
    }

    @Test
    public void testFromAlpnIdentifierNull() {
        assertEquals(HttpVersion.UNKNOWN, HttpVersion.fromAlpnIdentifier(null));
    }

    @Test
    public void testFromAlpnIdentifierUnrecognized() {
        assertEquals(HttpVersion.UNKNOWN, HttpVersion.fromAlpnIdentifier("h4"));
        assertEquals(HttpVersion.UNKNOWN, HttpVersion.fromAlpnIdentifier(""));
    }

    @Test
    public void testFromStringPrefersAlpn() {
        assertEquals(HttpVersion.HTTP_2_0, HttpVersion.fromString("h2"));
        assertEquals(HttpVersion.HTTP_3, HttpVersion.fromString("h3"));
        assertEquals(HttpVersion.HTTP_1_1, HttpVersion.fromString("http/1.1"));
    }

    @Test
    public void testFromStringFallsBackToVersionString() {
        assertEquals(HttpVersion.HTTP_1_1, HttpVersion.fromString("HTTP/1.1"));
        assertEquals(HttpVersion.HTTP_2_0, HttpVersion.fromString("HTTP/2.0"));
    }

    @Test
    public void testFromStringUnrecognized() {
        assertEquals(HttpVersion.UNKNOWN, HttpVersion.fromString(null));
        assertEquals(HttpVersion.UNKNOWN, HttpVersion.fromString("nonsense"));
    }

    @Test
    public void testSupportsMultiplexing() {
        assertFalse(HttpVersion.HTTP_1_0.supportsMultiplexing());
        assertFalse(HttpVersion.HTTP_1_1.supportsMultiplexing());
        assertTrue(HttpVersion.HTTP_2_0.supportsMultiplexing());
        assertTrue(HttpVersion.HTTP_3.supportsMultiplexing());
        assertFalse(HttpVersion.UNKNOWN.supportsMultiplexing());
    }

    @Test
    public void testRequiresHostHeader() {
        assertFalse(HttpVersion.HTTP_1_0.requiresHostHeader());
        assertTrue(HttpVersion.HTTP_1_1.requiresHostHeader());
        assertTrue(HttpVersion.HTTP_2_0.requiresHostHeader());
        assertTrue(HttpVersion.HTTP_3.requiresHostHeader());
        assertFalse(HttpVersion.UNKNOWN.requiresHostHeader());
    }

    @Test
    public void testHttp1FramingHeadersOnMultiplexedTransports() {
        assertTrue(HttpVersion.isHttp1FramingHeader(
                "Content-Length", "42"));
        assertTrue(HttpVersion.isHttp1FramingHeader(
                "Transfer-Encoding", "chunked"));
        assertTrue(HttpVersion.isHttp1FramingHeader(
                "Connection", "close"));
        assertFalse(HttpVersion.isHttp1FramingHeader(
                "TE", "trailers"));
        assertTrue(HttpVersion.isHttp1FramingHeader(
                "TE", "gzip"));
        assertFalse(HttpVersion.isHttp1FramingHeader(
                "Content-Type", "text/plain"));
    }

    @Test
    public void testStripHttp1FramingHeaders() {
        Headers headers = new Headers();
        headers.add("Content-Length", "42");
        headers.add("Transfer-Encoding", "chunked");
        headers.add("Content-Type", "text/plain");
        headers.add(":method", "GET");
        HttpVersion.stripHttp1FramingHeaders(headers);
        assertNull(headers.getValue("Content-Length"));
        assertNull(headers.getValue("Transfer-Encoding"));
        assertEquals("text/plain", headers.getValue("Content-Type"));
        assertEquals("GET", headers.getValue(":method"));
    }
}
