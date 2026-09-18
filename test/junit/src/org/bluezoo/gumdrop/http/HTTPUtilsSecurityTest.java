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
