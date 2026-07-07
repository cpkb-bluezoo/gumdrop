/*
 * HTTPUtilsSecurityTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

public class HTTPUtilsSecurityTest {

    @Test
    public void testChunkedAsFinalCoding() {
        assertTrue(HTTPUtils.isChunkedTransferEncoding("chunked"));
        assertFalse(HTTPUtils.isChunkedTransferEncoding("gzip, chunked"));
        assertFalse(HTTPUtils.isChunkedTransferEncoding("chunked, gzip"));
        assertFalse(HTTPUtils.isChunkedTransferEncoding("xchunked"));
    }

    @Test
    public void testValidateContentLengthSimple() {
        assertEquals(100, HTTPUtils.validateContentLength("100"));
    }

    @Test
    public void testValidateContentLengthMultipleDifferent() {
        assertEquals(-1, HTTPUtils.validateContentLength("100, 200"));
    }

    @Test
    public void testValidateContentLengthMultipleEqual() {
        assertEquals(200, HTTPUtils.validateContentLength("200, 200"));
    }
}
