/*
 * HttpUtilsHostAndLengthTest.java
 * Copyright (C) 2025 Chris Burdess
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
 * Unit tests for {@link HttpUtils} Host, Transfer-Encoding and
 * Content-Length validation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpUtilsHostAndLengthTest {

    @Test
    public void validHosts() {
        assertTrue(HttpUtils.isValidHost("example.com"));
        assertTrue(HttpUtils.isValidHost("example.com:8080"));
        assertTrue(HttpUtils.isValidHost("192.0.2.1:65535"));
        assertTrue(HttpUtils.isValidHost("[::1]"));
        assertTrue(HttpUtils.isValidHost("[2001:db8::1]:443"));
    }

    @Test
    public void invalidHosts() {
        assertFalse(HttpUtils.isValidHost(null));
        assertFalse(HttpUtils.isValidHost(""));
        assertFalse(HttpUtils.isValidHost("example.com:"));
        assertFalse(HttpUtils.isValidHost("example.com:0"));
        assertFalse(HttpUtils.isValidHost("example.com:65536"));
        assertFalse(HttpUtils.isValidHost("example.com:abc"));
        assertFalse(HttpUtils.isValidHost(":80"));
        assertFalse(HttpUtils.isValidHost("bad host"));
        assertFalse(HttpUtils.isValidHost("bad_host"));
        assertFalse(HttpUtils.isValidHost("[::1"));
        assertFalse(HttpUtils.isValidHost("[not-ipv6]"));
        assertFalse(HttpUtils.isValidHost("[::1]x"));
        assertFalse(HttpUtils.isValidHost("[::1]:"));
        assertFalse(HttpUtils.isValidHost("[::1]:0"));
        assertFalse(HttpUtils.isValidHost("[::1]:abc"));
    }

    @Test
    public void chunkedMustBeFinalCoding() {
        assertFalse(HttpUtils.isChunkedTransferEncoding(null));
        assertTrue(HttpUtils.isChunkedTransferEncoding("chunked"));
        // Only a sole "chunked" token is accepted by this implementation.
        assertFalse(HttpUtils.isChunkedTransferEncoding("gzip, chunked"));
        assertFalse(HttpUtils.isChunkedTransferEncoding("chunked, gzip"));
        assertFalse(HttpUtils.isChunkedTransferEncoding("gzip"));
    }

    @Test
    public void contentLengthValidation() {
        assertEquals(-1L, HttpUtils.validateContentLength(null));
        assertEquals(0L, HttpUtils.validateContentLength("0"));
        assertEquals(42L, HttpUtils.validateContentLength(" 42 "));
        assertEquals(42L, HttpUtils.validateContentLength("42, 42"));
        assertEquals(-1L, HttpUtils.validateContentLength("42, 43"));
        assertEquals(-1L, HttpUtils.validateContentLength("-1"));
        assertEquals(-1L, HttpUtils.validateContentLength("abc"));
        assertEquals(-1L, HttpUtils.validateContentLength(""));
    }

    @Test
    public void priorityParamsEncodingAndOrdering() {
        PriorityParams defaults = PriorityParams.DEFAULT;
        assertEquals(defaults, PriorityParams.parse((String) null));
        assertNotNull(defaults.toString());
        assertEquals(defaults.hashCode(), PriorityParams.DEFAULT.hashCode());
        assertFalse(defaults.equals("x"));
    }
}
