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
        assertTrue(HTTPUtils.isChunkedTransferEncoding("gzip, chunked"));
        assertFalse(HTTPUtils.isChunkedTransferEncoding("chunked, gzip"));
        assertFalse(HTTPUtils.isChunkedTransferEncoding("xchunked"));
    }
}
