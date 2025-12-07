/*
 * FilterMappingTest.java
 * Copyright (C) 2025 Chris Burdess
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

import org.junit.Before;
import org.junit.Test;

import javax.servlet.DispatcherType;

import static org.junit.Assert.*;

/**
 * Unit tests for FilterMapping.
 * 
 * Tests filter mapping functionality including:
 * - Dispatcher type matching
 * - URL pattern management
 * - Servlet name management
 * - Default dispatcher behavior
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FilterMappingTest {

    private FilterMapping mapping;

    @Before
    public void setUp() {
        mapping = new FilterMapping();
    }

    // ===== Default Constructor Tests =====

    @Test
    public void testDefaultConstructorEmptyDispatchers() {
        // Default constructor should have empty dispatchers
        assertTrue(mapping.dispatchers.isEmpty());
    }

    @Test
    public void testDefaultConstructorEmptyUrlPatterns() {
        assertTrue(mapping.urlPatterns.isEmpty());
    }

    @Test
    public void testDefaultConstructorEmptyServletNames() {
        assertTrue(mapping.servletNames.isEmpty());
    }

    // ===== Constructor with Dispatcher Types =====

    @Test
    public void testConstructorWithDispatcherTypes() {
        FilterMapping fm = new FilterMapping(new DispatcherType[] {
            DispatcherType.REQUEST, DispatcherType.FORWARD
        });
        
        assertTrue(fm.dispatchers.contains(DispatcherType.REQUEST));
        assertTrue(fm.dispatchers.contains(DispatcherType.FORWARD));
        assertFalse(fm.dispatchers.contains(DispatcherType.INCLUDE));
    }

    @Test
    public void testConstructorWithSingleDispatcher() {
        FilterMapping fm = new FilterMapping(new DispatcherType[] {
            DispatcherType.ASYNC
        });
        
        assertEquals(1, fm.dispatchers.size());
        assertTrue(fm.dispatchers.contains(DispatcherType.ASYNC));
    }

    @Test
    public void testConstructorWithAllDispatchers() {
        FilterMapping fm = new FilterMapping(new DispatcherType[] {
            DispatcherType.REQUEST,
            DispatcherType.FORWARD,
            DispatcherType.INCLUDE,
            DispatcherType.ERROR,
            DispatcherType.ASYNC
        });
        
        assertEquals(5, fm.dispatchers.size());
    }

    // ===== matches() with Empty Dispatchers =====

    @Test
    public void testMatchesEmptyDispatchersOnlyMatchesRequest() {
        // Empty dispatchers should only match REQUEST
        assertTrue(mapping.matches(DispatcherType.REQUEST));
        assertFalse(mapping.matches(DispatcherType.FORWARD));
        assertFalse(mapping.matches(DispatcherType.INCLUDE));
        assertFalse(mapping.matches(DispatcherType.ERROR));
        assertFalse(mapping.matches(DispatcherType.ASYNC));
    }

    // ===== matches() with Specific Dispatchers =====

    @Test
    public void testMatchesRequestDispatcher() {
        mapping.dispatchers.add(DispatcherType.REQUEST);
        
        assertTrue(mapping.matches(DispatcherType.REQUEST));
        assertFalse(mapping.matches(DispatcherType.FORWARD));
    }

    @Test
    public void testMatchesForwardDispatcher() {
        mapping.dispatchers.add(DispatcherType.FORWARD);
        
        assertFalse(mapping.matches(DispatcherType.REQUEST));
        assertTrue(mapping.matches(DispatcherType.FORWARD));
    }

    @Test
    public void testMatchesIncludeDispatcher() {
        mapping.dispatchers.add(DispatcherType.INCLUDE);
        
        assertTrue(mapping.matches(DispatcherType.INCLUDE));
        assertFalse(mapping.matches(DispatcherType.REQUEST));
    }

    @Test
    public void testMatchesErrorDispatcher() {
        mapping.dispatchers.add(DispatcherType.ERROR);
        
        assertTrue(mapping.matches(DispatcherType.ERROR));
        assertFalse(mapping.matches(DispatcherType.REQUEST));
    }

    @Test
    public void testMatchesAsyncDispatcher() {
        mapping.dispatchers.add(DispatcherType.ASYNC);
        
        assertTrue(mapping.matches(DispatcherType.ASYNC));
        assertFalse(mapping.matches(DispatcherType.REQUEST));
    }

    @Test
    public void testMatchesMultipleDispatchers() {
        mapping.dispatchers.add(DispatcherType.REQUEST);
        mapping.dispatchers.add(DispatcherType.FORWARD);
        mapping.dispatchers.add(DispatcherType.INCLUDE);
        
        assertTrue(mapping.matches(DispatcherType.REQUEST));
        assertTrue(mapping.matches(DispatcherType.FORWARD));
        assertTrue(mapping.matches(DispatcherType.INCLUDE));
        assertFalse(mapping.matches(DispatcherType.ERROR));
        assertFalse(mapping.matches(DispatcherType.ASYNC));
    }

    // ===== URL Pattern Management =====

    @Test
    public void testAddUrlPattern() {
        mapping.addUrlPattern("/api/*");
        
        assertTrue(mapping.urlPatterns.contains("/api/*"));
        assertEquals(1, mapping.urlPatterns.size());
    }

    @Test
    public void testAddMultipleUrlPatterns() {
        mapping.addUrlPattern("/api/*");
        mapping.addUrlPattern("*.do");
        mapping.addUrlPattern("/exact/path");
        
        assertEquals(3, mapping.urlPatterns.size());
        assertTrue(mapping.urlPatterns.contains("/api/*"));
        assertTrue(mapping.urlPatterns.contains("*.do"));
        assertTrue(mapping.urlPatterns.contains("/exact/path"));
    }

    @Test
    public void testAddDuplicateUrlPattern() {
        mapping.addUrlPattern("/api/*");
        mapping.addUrlPattern("/api/*");
        
        // LinkedHashSet should not allow duplicates
        assertEquals(1, mapping.urlPatterns.size());
    }

    // ===== Servlet Name Management =====

    @Test
    public void testAddServletName() {
        mapping.addServletName("MyServlet");
        
        assertTrue(mapping.servletNames.contains("MyServlet"));
        assertEquals(1, mapping.servletNames.size());
    }

    @Test
    public void testAddMultipleServletNames() {
        mapping.addServletName("Servlet1");
        mapping.addServletName("Servlet2");
        mapping.addServletName("Servlet3");
        
        assertEquals(3, mapping.servletNames.size());
        assertTrue(mapping.servletNames.contains("Servlet1"));
        assertTrue(mapping.servletNames.contains("Servlet2"));
        assertTrue(mapping.servletNames.contains("Servlet3"));
    }

    @Test
    public void testAddDuplicateServletName() {
        mapping.addServletName("MyServlet");
        mapping.addServletName("MyServlet");
        
        // LinkedHashSet should not allow duplicates
        assertEquals(1, mapping.servletNames.size());
    }

    // ===== Field Assignment Tests =====

    @Test
    public void testFilterNameField() {
        mapping.filterName = "MyFilter";
        assertEquals("MyFilter", mapping.filterName);
    }

    @Test
    public void testFilterDefField() {
        FilterDef filterDef = new FilterDef();
        filterDef.name = "TestFilter";
        
        mapping.filterDef = filterDef;
        
        assertSame(filterDef, mapping.filterDef);
        assertEquals("TestFilter", mapping.filterDef.name);
    }

    @Test
    public void testServletDefsField() {
        ServletDef sd1 = new ServletDef();
        sd1.name = "Servlet1";
        ServletDef sd2 = new ServletDef();
        sd2.name = "Servlet2";
        
        mapping.servletDefs.add(sd1);
        mapping.servletDefs.add(sd2);
        
        assertEquals(2, mapping.servletDefs.size());
        assertTrue(mapping.servletDefs.contains(sd1));
        assertTrue(mapping.servletDefs.contains(sd2));
    }

    // ===== Edge Cases =====

    @Test
    public void testEmptyUrlPattern() {
        mapping.addUrlPattern("");
        
        assertTrue(mapping.urlPatterns.contains(""));
    }

    @Test
    public void testEmptyServletName() {
        mapping.addServletName("");
        
        assertTrue(mapping.servletNames.contains(""));
    }

    @Test
    public void testUrlPatternPreservesOrder() {
        mapping.addUrlPattern("/first");
        mapping.addUrlPattern("/second");
        mapping.addUrlPattern("/third");
        
        // LinkedHashSet preserves insertion order
        String[] patterns = mapping.urlPatterns.toArray(new String[0]);
        assertEquals("/first", patterns[0]);
        assertEquals("/second", patterns[1]);
        assertEquals("/third", patterns[2]);
    }

}

