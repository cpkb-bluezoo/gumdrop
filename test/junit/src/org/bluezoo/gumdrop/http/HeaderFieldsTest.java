/*
 * HeaderFieldsTest.java
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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HeaderFields}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HeaderFieldsTest {

    @Test
    public void testNewListIsEmpty() {
        List<Header> headers = new ArrayList<Header>();
        assertTrue(headers.isEmpty());
        assertEquals(0, headers.size());
    }

    @Test
    public void testAddByNameValue() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Content-Type", "text/html");
        assertEquals(1, headers.size());
        assertEquals("text/html", HeaderFields.getValue(headers, "Content-Type"));
    }

    @Test
    public void testGetValueCaseInsensitive() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Content-Type", "text/html");

        assertEquals("text/html", HeaderFields.getValue(headers, "Content-Type"));
        assertEquals("text/html", HeaderFields.getValue(headers, "content-type"));
        assertEquals("text/html", HeaderFields.getValue(headers, "CONTENT-TYPE"));
    }

    @Test
    public void testGetValueNotFound() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Content-Type", "text/html");
        assertNull(HeaderFields.getValue(headers, "Accept"));
    }

    @Test
    public void testGetValueReturnsFirst() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Accept", "text/html");
        HeaderFields.add(headers, "Accept", "application/json");
        assertEquals("text/html", HeaderFields.getValue(headers, "Accept"));
    }

    @Test
    public void testGetValues() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Accept", "text/html");
        HeaderFields.add(headers, "Accept", "application/json");
        HeaderFields.add(headers, "Content-Type", "text/plain");

        List<String> values = HeaderFields.getValues(headers, "Accept");
        assertEquals(2, values.size());
        assertEquals("text/html", values.get(0));
        assertEquals("application/json", values.get(1));
    }

    @Test
    public void testGetValuesCaseInsensitive() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Accept", "text/html");
        HeaderFields.add(headers, "accept", "application/json");

        List<String> values = HeaderFields.getValues(headers, "ACCEPT");
        assertEquals(2, values.size());
    }

    @Test
    public void testGetValuesEmpty() {
        List<Header> headers = new ArrayList<Header>();
        List<String> values = HeaderFields.getValues(headers, "NonExistent");
        assertNotNull(values);
        assertTrue(values.isEmpty());
    }

    @Test
    public void testGetHeader() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Content-Type", "text/html");

        Header found = HeaderFields.getHeader(headers, "content-type");
        assertNotNull(found);
        assertEquals("Content-Type", found.getName());
        assertEquals("text/html", found.getValue());
    }

    @Test
    public void testGetHeaderNotFound() {
        List<Header> headers = new ArrayList<Header>();
        assertNull(HeaderFields.getHeader(headers, "Content-Type"));
    }

    @Test
    public void testGetHeaders() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Set-Cookie", "a=1");
        HeaderFields.add(headers, "Set-Cookie", "b=2");
        HeaderFields.add(headers, "Content-Type", "text/html");

        List<Header> cookies = HeaderFields.getHeaders(headers, "Set-Cookie");
        assertEquals(2, cookies.size());
    }

    @Test
    public void testContainsName() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Content-Type", "text/html");

        assertTrue(HeaderFields.containsName(headers, "Content-Type"));
        assertTrue(HeaderFields.containsName(headers, "content-type"));
        assertFalse(HeaderFields.containsName(headers, "Accept"));
    }

    @Test
    public void testSet() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Content-Type", "text/html");
        HeaderFields.add(headers, "Content-Type", "text/plain");

        HeaderFields.set(headers, "Content-Type", "application/json");

        assertEquals(1, HeaderFields.getHeaders(headers, "Content-Type").size());
        assertEquals("application/json", HeaderFields.getValue(headers, "Content-Type"));
    }

    @Test
    public void testSetAddsWhenNotPresent() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.set(headers, "Content-Type", "text/html");
        assertEquals("text/html", HeaderFields.getValue(headers, "Content-Type"));
        assertEquals(1, headers.size());
    }

    @Test
    public void testRemoveAll() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Accept", "text/html");
        HeaderFields.add(headers, "Content-Type", "text/html");
        HeaderFields.add(headers, "Accept", "application/json");

        assertTrue(HeaderFields.removeAll(headers, "Accept"));
        assertEquals(1, headers.size());
        assertFalse(HeaderFields.containsName(headers, "Accept"));
        assertTrue(HeaderFields.containsName(headers, "Content-Type"));
    }

    @Test
    public void testRemoveAllNotFound() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Content-Type", "text/html");
        assertFalse(HeaderFields.removeAll(headers, "Accept"));
        assertEquals(1, headers.size());
    }

    @Test
    public void testRemoveAllCaseInsensitive() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Content-Type", "text/html");
        HeaderFields.add(headers, "content-type", "text/plain");

        assertTrue(HeaderFields.removeAll(headers, "CONTENT-TYPE"));
        assertTrue(headers.isEmpty());
    }

    @Test
    public void testGetCombinedValue() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Accept", "text/html");
        HeaderFields.add(headers, "Accept", "application/json");

        assertEquals("text/html, application/json", HeaderFields.getCombinedValue(headers, "Accept"));
    }

    @Test
    public void testGetCombinedValueSingle() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Content-Type", "text/html");
        assertEquals("text/html", HeaderFields.getCombinedValue(headers, "Content-Type"));
    }

    @Test
    public void testGetCombinedValueNotFound() {
        List<Header> headers = new ArrayList<Header>();
        assertNull(HeaderFields.getCombinedValue(headers, "Accept"));
    }

    @Test
    public void testStatusPseudoHeader() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header(":status", String.valueOf(HttpStatus.OK.code)));
        assertEquals("200", HeaderFields.getValue(headers, ":status"));
    }

    @Test
    public void testStatusPseudoHeaderReplace() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.set(headers, ":status", "200");
        HeaderFields.set(headers, ":status", "404");
        assertEquals("404", HeaderFields.getValue(headers, ":status"));
        assertEquals(1, HeaderFields.getHeaders(headers, ":status").size());
    }

    @Test
    public void testMethodPseudoHeader() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, ":method", "GET");
        assertEquals("GET", HeaderFields.getValue(headers, ":method"));
    }

    @Test
    public void testMethodPseudoHeaderNotPresent() {
        List<Header> headers = new ArrayList<Header>();
        assertNull(HeaderFields.getValue(headers, ":method"));
    }

    @Test
    public void testPathPseudoHeader() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, ":path", "/index.html");
        assertEquals("/index.html", HeaderFields.getValue(headers, ":path"));
    }

    @Test
    public void testPathPseudoHeaderNotPresent() {
        List<Header> headers = new ArrayList<Header>();
        assertNull(HeaderFields.getValue(headers, ":path"));
    }

    @Test
    public void testNullValueSkippedByValuesAndCombined() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header("X-Null", null));
        HeaderFields.add(headers, "X-Null", "v");
        assertEquals(1, HeaderFields.getValues(headers, "x-null").size());
        assertEquals("v", HeaderFields.getCombinedValue(headers, "X-NULL"));
    }

    @Test
    public void testSetOnAbsentNameAppends() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.set(headers, "Content-Type", "text/html");
        assertEquals(1, headers.size());
        assertEquals("text/html", HeaderFields.getValue(headers, "content-type"));
    }

    @Test
    public void testSetReplacesAllCaseInsensitively() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Accept", "a");
        HeaderFields.add(headers, "X-Other", "o");
        HeaderFields.add(headers, "ACCEPT", "b");
        HeaderFields.set(headers, "accept", "c");
        assertEquals(2, headers.size());
        assertEquals(1, HeaderFields.getValues(headers, "Accept").size());
        assertEquals("c", HeaderFields.getValue(headers, "Accept"));
    }

    @Test
    public void testEmptyListLookups() {
        List<Header> headers = new ArrayList<Header>();
        assertNull(HeaderFields.getValue(headers, "A"));
        assertTrue(HeaderFields.getValues(headers, "A").isEmpty());
        assertNull(HeaderFields.getHeader(headers, "A"));
        assertTrue(HeaderFields.getHeaders(headers, "A").isEmpty());
        assertFalse(HeaderFields.containsName(headers, "A"));
        assertNull(HeaderFields.getCombinedValue(headers, "A"));
        assertFalse(HeaderFields.removeAll(headers, "A"));
    }

    @Test
    public void testOrderPreserved() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "A-Header", "first");
        HeaderFields.add(headers, "B-Header", "second");
        HeaderFields.add(headers, "C-Header", "third");

        assertEquals("A-Header", headers.get(0).getName());
        assertEquals("B-Header", headers.get(1).getName());
        assertEquals("C-Header", headers.get(2).getName());
    }
}
