/*
 * HttpConditionalRequestsTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http;

import org.junit.Test;

import java.util.Date;

import static org.junit.Assert.*;

/**
 * RFC 9110 / RFC 9111 conditional GET handling (validators, 304).
 */
public class HttpConditionalRequestsTest {

    @Test
    public void testStrongEntityTagFormat() {
        assertEquals("\"1a2b-3c\"",
                HttpConditionalRequests.strongEntityTag(0x1a2b, 0x3c));
    }

    @Test
    public void testIfNoneMatchStar() {
        assertTrue(HttpConditionalRequests.ifNoneMatchSatisfied("*", "\"x\""));
    }

    @Test
    public void testIfNoneMatchSingleTag() {
        String tag = HttpConditionalRequests.strongEntityTag(1000, 42);
        assertTrue(HttpConditionalRequests.ifNoneMatchSatisfied(tag, tag));
        assertFalse(HttpConditionalRequests.ifNoneMatchSatisfied("\"other\"",
                tag));
    }

    @Test
    public void testIfNoneMatchList() {
        String tag = HttpConditionalRequests.strongEntityTag(1000, 42);
        assertTrue(HttpConditionalRequests.ifNoneMatchSatisfied(
                "\"nomatch\", " + tag, tag));
    }

    @Test
    public void testIfNoneMatchWeakComparison() {
        assertTrue(HttpConditionalRequests.ifNoneMatchSatisfied(
                "W/\"abc\"", "\"abc\""));
    }

    @Test
    public void testIfModifiedSinceMatchAtSecondBoundary() throws Exception {
        long lastModified = 1_600_000_000_000L;
        String ims = new HttpDateFormat().format(new Date(lastModified));
        assertTrue(HttpConditionalRequests.shouldReturnNotModified(
                null, ims, lastModified,
                HttpConditionalRequests.strongEntityTag(lastModified, 10)));
    }

    @Test
    public void testIfModifiedSinceStaleClientDate() {
        long lastModified = 1_600_000_000_000L;
        assertFalse(HttpConditionalRequests.shouldReturnNotModified(
                null, "Sun, 06 Nov 1994 08:49:37 GMT", lastModified,
                HttpConditionalRequests.strongEntityTag(lastModified, 10)));
    }

    @Test
    public void testIfNoneMatchOverridesIfModifiedSince() {
        long lastModified = 1_000_000_000_000L;
        String tag = HttpConditionalRequests.strongEntityTag(lastModified, 1);
        String oldIms = "Sun, 06 Nov 1994 08:49:37 GMT";
        assertFalse(HttpConditionalRequests.shouldReturnNotModified(
                "\"nomatch\"", oldIms, lastModified, tag));
    }

    @Test
    public void testInvalidIfModifiedSinceIgnored() {
        long lastModified = 1_000_000_000_000L;
        assertFalse(HttpConditionalRequests.shouldReturnNotModified(
                null, "not-a-date", lastModified,
                HttpConditionalRequests.strongEntityTag(lastModified, 1)));
    }
}
