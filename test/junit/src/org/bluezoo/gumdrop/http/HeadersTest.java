package org.bluezoo.gumdrop.http;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Headers}.
 */
public class HeadersTest {

    @Test
    public void testDefaultConstructor() {
        Headers headers = new Headers();
        assertTrue(headers.isEmpty());
        assertEquals(0, headers.size());
    }

    @Test
    public void testCapacityConstructor() {
        Headers headers = new Headers(16);
        assertTrue(headers.isEmpty());
    }

    @Test
    public void testCopyConstructor() {
        List<Header> source = Arrays.asList(
                new Header("Content-Type", "text/html"),
                new Header("Accept", "application/json"));
        Headers headers = new Headers(source);
        assertEquals(2, headers.size());
    }

    @Test
    public void testAddByNameValue() {
        Headers headers = new Headers();
        headers.add("Content-Type", "text/html");
        assertEquals(1, headers.size());
        assertEquals("text/html", headers.getValue("Content-Type"));
    }

    @Test
    public void testGetValueCaseInsensitive() {
        Headers headers = new Headers();
        headers.add("Content-Type", "text/html");

        assertEquals("text/html", headers.getValue("Content-Type"));
        assertEquals("text/html", headers.getValue("content-type"));
        assertEquals("text/html", headers.getValue("CONTENT-TYPE"));
    }

    @Test
    public void testGetValueNotFound() {
        Headers headers = new Headers();
        headers.add("Content-Type", "text/html");
        assertNull(headers.getValue("Accept"));
    }

    @Test
    public void testGetValueReturnsFirst() {
        Headers headers = new Headers();
        headers.add("Accept", "text/html");
        headers.add("Accept", "application/json");
        assertEquals("text/html", headers.getValue("Accept"));
    }

    @Test
    public void testGetValues() {
        Headers headers = new Headers();
        headers.add("Accept", "text/html");
        headers.add("Accept", "application/json");
        headers.add("Content-Type", "text/plain");

        List<String> values = headers.getValues("Accept");
        assertEquals(2, values.size());
        assertEquals("text/html", values.get(0));
        assertEquals("application/json", values.get(1));
    }

    @Test
    public void testGetValuesCaseInsensitive() {
        Headers headers = new Headers();
        headers.add("Accept", "text/html");
        headers.add("accept", "application/json");

        List<String> values = headers.getValues("ACCEPT");
        assertEquals(2, values.size());
    }

    @Test
    public void testGetValuesEmpty() {
        Headers headers = new Headers();
        List<String> values = headers.getValues("NonExistent");
        assertNotNull(values);
        assertTrue(values.isEmpty());
    }

    @Test
    public void testGetHeader() {
        Headers headers = new Headers();
        headers.add("Content-Type", "text/html");

        Header found = headers.getHeader("content-type");
        assertNotNull(found);
        assertEquals("Content-Type", found.getName());
        assertEquals("text/html", found.getValue());
    }

    @Test
    public void testGetHeaderNotFound() {
        Headers headers = new Headers();
        assertNull(headers.getHeader("Content-Type"));
    }

    @Test
    public void testGetHeaders() {
        Headers headers = new Headers();
        headers.add("Set-Cookie", "a=1");
        headers.add("Set-Cookie", "b=2");
        headers.add("Content-Type", "text/html");

        List<Header> cookies = headers.getHeaders("Set-Cookie");
        assertEquals(2, cookies.size());
    }

    @Test
    public void testContainsName() {
        Headers headers = new Headers();
        headers.add("Content-Type", "text/html");

        assertTrue(headers.containsName("Content-Type"));
        assertTrue(headers.containsName("content-type"));
        assertFalse(headers.containsName("Accept"));
    }

    @Test
    public void testSet() {
        Headers headers = new Headers();
        headers.add("Content-Type", "text/html");
        headers.add("Content-Type", "text/plain");

        headers.set("Content-Type", "application/json");

        assertEquals(1, headers.getHeaders("Content-Type").size());
        assertEquals("application/json", headers.getValue("Content-Type"));
    }

    @Test
    public void testSetAddsWhenNotPresent() {
        Headers headers = new Headers();
        headers.set("Content-Type", "text/html");
        assertEquals("text/html", headers.getValue("Content-Type"));
        assertEquals(1, headers.size());
    }

    @Test
    public void testRemoveAll() {
        Headers headers = new Headers();
        headers.add("Accept", "text/html");
        headers.add("Content-Type", "text/html");
        headers.add("Accept", "application/json");

        assertTrue(headers.removeAll("Accept"));
        assertEquals(1, headers.size());
        assertFalse(headers.containsName("Accept"));
        assertTrue(headers.containsName("Content-Type"));
    }

    @Test
    public void testRemoveAllNotFound() {
        Headers headers = new Headers();
        headers.add("Content-Type", "text/html");
        assertFalse(headers.removeAll("Accept"));
        assertEquals(1, headers.size());
    }

    @Test
    public void testRemoveAllCaseInsensitive() {
        Headers headers = new Headers();
        headers.add("Content-Type", "text/html");
        headers.add("content-type", "text/plain");

        assertTrue(headers.removeAll("CONTENT-TYPE"));
        assertTrue(headers.isEmpty());
    }

    @Test
    public void testGetCombinedValue() {
        Headers headers = new Headers();
        headers.add("Accept", "text/html");
        headers.add("Accept", "application/json");

        assertEquals("text/html, application/json", headers.getCombinedValue("Accept"));
    }

    @Test
    public void testGetCombinedValueSingle() {
        Headers headers = new Headers();
        headers.add("Content-Type", "text/html");
        assertEquals("text/html", headers.getCombinedValue("Content-Type"));
    }

    @Test
    public void testGetCombinedValueNotFound() {
        Headers headers = new Headers();
        assertNull(headers.getCombinedValue("Accept"));
    }

    @Test
    public void testStatusPseudoHeader() {
        Headers headers = new Headers();
        headers.status(HTTPStatus.OK);
        assertEquals("200", headers.getValue(":status"));
    }

    @Test
    public void testStatusPseudoHeaderReplace() {
        Headers headers = new Headers();
        headers.status(HTTPStatus.OK);
        headers.status(HTTPStatus.NOT_FOUND);
        assertEquals("404", headers.getValue(":status"));
        assertEquals(1, headers.getHeaders(":status").size());
    }

    @Test
    public void testGetMethod() {
        Headers headers = new Headers();
        headers.add(":method", "GET");
        assertEquals("GET", headers.getMethod());
    }

    @Test
    public void testGetMethodNotPresent() {
        Headers headers = new Headers();
        assertNull(headers.getMethod());
    }

    @Test
    public void testGetPath() {
        Headers headers = new Headers();
        headers.add(":path", "/index.html");
        assertEquals("/index.html", headers.getPath());
    }

    @Test
    public void testGetPathNotPresent() {
        Headers headers = new Headers();
        assertNull(headers.getPath());
    }

    @Test
    public void testOrderPreserved() {
        Headers headers = new Headers();
        headers.add("A-Header", "first");
        headers.add("B-Header", "second");
        headers.add("C-Header", "third");

        assertEquals("A-Header", headers.get(0).getName());
        assertEquals("B-Header", headers.get(1).getName());
        assertEquals("C-Header", headers.get(2).getName());
    }
}
