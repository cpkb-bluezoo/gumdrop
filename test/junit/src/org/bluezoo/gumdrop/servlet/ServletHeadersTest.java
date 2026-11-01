/*
 * ServletHeadersTest.java
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

package org.bluezoo.gumdrop.servlet;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HttpStatus;

import static org.junit.Assert.*;

/**
 * Unit tests for the lookup, mutation and pseudo-header helpers of
 * {@link ServletHeaders}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletHeadersTest {

    private static ServletHeaders sample() {
        ServletHeaders headers = new ServletHeaders();
        headers.add("Accept", "text/html");
        headers.add("X-Other", "1");
        headers.add("accept", "application/json");
        return headers;
    }

    @Test
    public void copyConstructorCopiesHeaders() {
        List<Header> source = new ArrayList<Header>();
        source.add(new Header("A", "1"));
        source.add(new Header("B", "2"));
        ServletHeaders copy = new ServletHeaders(source);
        assertEquals(2, copy.size());
        assertEquals("2", copy.getValue("b"));
    }

    @Test
    public void capacityConstructorStartsEmpty() {
        ServletHeaders headers = new ServletHeaders(4);
        assertTrue(headers.isEmpty());
        assertNull(headers.getValue("x"));
    }

    @Test
    public void lookupIsCaseInsensitive() {
        ServletHeaders headers = sample();
        assertEquals("text/html", headers.getValue("ACCEPT"));
        Header first = headers.getHeader("accept");
        assertNotNull(first);
        assertEquals("text/html", first.getValue());
        assertNull(headers.getHeader("missing"));
        assertNull(headers.getValue("missing"));
        assertTrue(headers.containsName("X-OTHER"));
        assertFalse(headers.containsName("missing"));
    }

    @Test
    public void getHeadersReturnsACopy() {
        ServletHeaders headers = sample();
        List<Header> found = headers.getHeaders("Accept");
        assertEquals(2, found.size());
        found.clear();
        assertEquals(2, headers.getHeaders("accept").size());
        assertTrue(headers.getHeaders("missing").isEmpty());
    }

    @Test
    public void getValuesSkipsNullValues() {
        ServletHeaders headers = sample();
        headers.add(new Header("Accept", null));
        List<String> values = headers.getValues("Accept");
        assertEquals(2, values.size());
        assertEquals("text/html", values.get(0));
        assertEquals("application/json", values.get(1));
        assertTrue(headers.getValues("missing").isEmpty());
    }

    @Test
    public void combinedValueJoinsWithCommaAndSkipsNulls() {
        ServletHeaders headers = sample();
        assertEquals("text/html, application/json", headers.getCombinedValue("accept"));
        assertEquals("1", headers.getCombinedValue("X-Other"));
        assertNull(headers.getCombinedValue("missing"));
        ServletHeaders onlyNull = new ServletHeaders();
        onlyNull.add(new Header("N", null));
        assertNull(onlyNull.getCombinedValue("N"));
        onlyNull.add("N", "v");
        assertEquals("v", onlyNull.getCombinedValue("n"));
    }

    @Test
    public void setReplacesAllExistingValues() {
        ServletHeaders headers = sample();
        headers.set("ACCEPT", "text/plain");
        assertEquals(1, headers.getHeaders("accept").size());
        assertEquals("text/plain", headers.getValue("accept"));
        assertEquals("1", headers.getValue("x-other"));
    }

    @Test
    public void removeAllReportsWhetherAnythingWasRemoved() {
        ServletHeaders headers = sample();
        assertTrue(headers.removeAll("ACCEPT"));
        assertFalse(headers.containsName("accept"));
        assertFalse(headers.removeAll("accept"));
        assertEquals(1, headers.size());
    }

    @Test
    public void indexIsInvalidatedByMutationAndReusedOtherwise() {
        ServletHeaders headers = sample();
        headers.getValue("accept");
        int builds = headers.indexBuildCountForTesting;
        headers.getValue("x-other");
        headers.containsName("accept");
        assertEquals("repeat lookups must reuse the cached index", builds, headers.indexBuildCountForTesting);
        headers.add("Late", "yes");
        assertEquals("yes", headers.getValue("late"));
        assertEquals(builds + 1, headers.indexBuildCountForTesting);
    }

    @Test
    public void pseudoHeaderHelpers() {
        ServletHeaders headers = new ServletHeaders();
        assertNull(headers.getMethod());
        assertNull(headers.getPath());
        headers.add(":method", "GET");
        headers.add(":path", "/index.html?x=1");
        assertEquals("GET", headers.getMethod());
        assertEquals("/index.html?x=1", headers.getPath());
        headers.status(HttpStatus.NOT_FOUND);
        assertEquals("404", headers.getValue(":status"));
        headers.status(HttpStatus.OK);
        assertEquals("200", headers.getValue(":status"));
        assertEquals(1, headers.getHeaders(":status").size());
    }
}
