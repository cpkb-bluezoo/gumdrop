/*
 * H3RequestTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * Tests for H3Request priority header emission (RFC 9218).
 */

package org.bluezoo.gumdrop.http.h3;

import java.lang.reflect.Field;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;

import org.junit.Test;
import static org.junit.Assert.*;

public class H3RequestTest {

    /**
     * RFC 9218 section 4: priority(0) should map to urgency 7 (lowest).
     */
    @Test
    public void testPriorityZeroMapsToUrgency7() throws Exception {
        H3Request request = new H3Request(null, "GET", "/", "example.com", "https", null);
        request.priority(0);

        String value = findHeader(request, "priority");
        assertNotNull("priority header should be emitted", value);
        assertEquals("u=7", value);
    }

    /**
     * RFC 9218 section 4: priority(255) should map to urgency 0 (highest).
     */
    @Test
    public void testPriority255MapsToUrgency0() throws Exception {
        H3Request request = new H3Request(null, "GET", "/", "example.com", "https", null);
        request.priority(255);

        String value = findHeader(request, "priority");
        assertNotNull("priority header should be emitted", value);
        assertEquals("u=0", value);
    }

    /**
     * Mid-range weight should produce a mid-range urgency.
     */
    @Test
    public void testPriorityMidRange() throws Exception {
        H3Request request = new H3Request(null, "GET", "/", "example.com", "https", null);
        request.priority(128);

        String value = findHeader(request, "priority");
        assertNotNull("priority header should be emitted", value);
        assertTrue("urgency should be between 0 and 7",
                value.startsWith("u="));
        int urgency = Integer.parseInt(value.substring(2));
        assertTrue(urgency >= 0 && urgency <= 7);
    }

    /**
     * Without calling priority(), no priority header should be present.
     */
    @Test
    public void testNoPriorityByDefault() throws Exception {
        H3Request request = new H3Request(null, "GET", "/", "example.com", "https", null);
        assertNull(findHeader(request, "priority"));
    }

    @SuppressWarnings("unchecked")
    private String findHeader(H3Request request, String name) throws Exception {
        Field f = H3Request.class.getDeclaredField("headers");
        f.setAccessible(true);
        List<Header> headers = (List<Header>) f.get(request);
        for (Header h : headers) {
            if (name.equals(h.getName())) {
                return h.getValue();
            }
        }
        return null;
    }
}
