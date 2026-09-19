/*
 * HTTPUtilsSecurityTest.java
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
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPUtilsSecurityTest {

    @Test
    public void testChunkedAsFinalCoding() {
        assertTrue(HttpUtils.isChunkedTransferEncoding("chunked"));
        assertFalse(HttpUtils.isChunkedTransferEncoding("gzip, chunked"));
        assertFalse(HttpUtils.isChunkedTransferEncoding("chunked, gzip"));
        assertFalse(HttpUtils.isChunkedTransferEncoding("xchunked"));
    }

    @Test
    public void testValidateContentLengthSimple() {
        assertEquals(100, HttpUtils.validateContentLength("100"));
    }

    @Test
    public void testValidateContentLengthMultipleDifferent() {
        assertEquals(-1, HttpUtils.validateContentLength("100, 200"));
    }

    @Test
    public void testValidateContentLengthMultipleEqual() {
        assertEquals(200, HttpUtils.validateContentLength("200, 200"));
    }
}
