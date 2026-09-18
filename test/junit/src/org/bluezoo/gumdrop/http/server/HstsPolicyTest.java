/*
 * HstsPolicyTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http.server;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RFC 6797 {@code Strict-Transport-Security} header value formatting.
 */
public class HstsPolicyTest {

    @Test
    public void testDisabledProducesNoHeader() {
        assertNull(HstsPolicy.disabled().headerValue());
    }

    @Test
    public void testMaxAgeOnly() {
        assertEquals("max-age=86400",
                HstsPolicy.enabled(86400).headerValue());
    }

    @Test
    public void testIncludeSubDomainsAndPreload() {
        HstsPolicy policy = HstsPolicy.enabled(31536000)
                .includeSubDomains(true)
                .preload(true);
        assertEquals("max-age=31536000; includeSubDomains; preload",
                policy.headerValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeMaxAgeRejected() {
        HstsPolicy.enabled(-1);
    }
}
