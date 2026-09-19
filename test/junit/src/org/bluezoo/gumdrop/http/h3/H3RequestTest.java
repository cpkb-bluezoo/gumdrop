/*
 * H3RequestTest.java
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

package org.bluezoo.gumdrop.http.h3;

import java.lang.reflect.Field;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
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
