/*
 * ResourceCollectionTest.java
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

import static org.junit.Assert.*;

/**
 * Unit tests for ResourceCollection.
 * 
 * Tests URL pattern matching logic including:
 * - Exact path matches
 * - Prefix pattern matches (ending with /*)
 * - Extension pattern matches (starting with *.)
 * - HTTP method coverage
 * - HTTP method omissions
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResourceCollectionTest {

    private ResourceCollection collection;

    @Before
    public void setUp() {
        collection = new ResourceCollection();
    }

    // ===== Exact Match Tests =====

    @Test
    public void testExactMatch() {
        collection.urlPatterns.add("/admin/users");
        
        assertTrue(collection.matches("GET", "/admin/users"));
        assertFalse(collection.matches("GET", "/admin/users/"));
        assertFalse(collection.matches("GET", "/admin"));
        assertFalse(collection.matches("GET", "/admin/users/list"));
    }

    @Test
    public void testExactMatchRoot() {
        collection.urlPatterns.add("/");
        
        assertTrue(collection.matches("GET", "/"));
        assertFalse(collection.matches("GET", "/anything"));
    }

    @Test
    public void testExactMatchMultiplePatterns() {
        collection.urlPatterns.add("/path1");
        collection.urlPatterns.add("/path2");
        collection.urlPatterns.add("/path3");
        
        assertTrue(collection.matches("GET", "/path1"));
        assertTrue(collection.matches("GET", "/path2"));
        assertTrue(collection.matches("GET", "/path3"));
        assertFalse(collection.matches("GET", "/path4"));
    }

    // ===== Prefix Pattern Tests =====

    @Test
    public void testPrefixMatch() {
        collection.urlPatterns.add("/admin/*");
        
        assertTrue(collection.matches("GET", "/admin/"));
        assertTrue(collection.matches("GET", "/admin/users"));
        assertTrue(collection.matches("GET", "/admin/users/list"));
        assertTrue(collection.matches("GET", "/admin/settings/advanced"));
        assertFalse(collection.matches("GET", "/administrator"));
        assertFalse(collection.matches("GET", "/other"));
    }

    @Test
    public void testPrefixMatchRootWildcard() {
        collection.urlPatterns.add("/*");
        
        assertTrue(collection.matches("GET", "/"));
        assertTrue(collection.matches("GET", "/anything"));
        assertTrue(collection.matches("GET", "/deep/nested/path"));
    }

    @Test
    public void testPrefixMatchNestedPath() {
        collection.urlPatterns.add("/api/v1/*");
        
        assertTrue(collection.matches("GET", "/api/v1/users"));
        assertTrue(collection.matches("GET", "/api/v1/products/123"));
        assertFalse(collection.matches("GET", "/api/v2/users"));
        assertFalse(collection.matches("GET", "/api/"));
    }

    // ===== Extension Pattern Tests =====

    @Test
    public void testExtensionMatch() {
        collection.urlPatterns.add("*.jsp");
        
        assertTrue(collection.matches("GET", "/page.jsp"));
        assertTrue(collection.matches("GET", "/admin/page.jsp"));
        assertTrue(collection.matches("GET", "/deep/nested/page.jsp"));
        assertFalse(collection.matches("GET", "/page.html"));
        assertFalse(collection.matches("GET", "/page.jspx"));
    }

    @Test
    public void testExtensionMatchMultiple() {
        collection.urlPatterns.add("*.jsp");
        collection.urlPatterns.add("*.jspx");
        
        assertTrue(collection.matches("GET", "/page.jsp"));
        assertTrue(collection.matches("GET", "/page.jspx"));
        assertFalse(collection.matches("GET", "/page.html"));
    }

    @Test
    public void testExtensionMatchWithDots() {
        collection.urlPatterns.add("*.tar.gz");
        
        assertTrue(collection.matches("GET", "/archive.tar.gz"));
        assertFalse(collection.matches("GET", "/archive.gz"));
        assertFalse(collection.matches("GET", "/archive.tar"));
    }

    // ===== Mixed Pattern Tests =====

    @Test
    public void testMixedPatterns() {
        collection.urlPatterns.add("/exact");
        collection.urlPatterns.add("/prefix/*");
        collection.urlPatterns.add("*.do");
        
        assertTrue(collection.matches("GET", "/exact"));
        assertTrue(collection.matches("GET", "/prefix/anything"));
        assertTrue(collection.matches("GET", "/action.do"));
        assertFalse(collection.matches("GET", "/other"));
    }

    // ===== matchesExact Tests =====

    @Test
    public void testMatchesExact() {
        collection.urlPatterns.add("/path1");
        collection.urlPatterns.add("/path2");
        
        assertTrue(collection.matchesExact("/path1"));
        assertTrue(collection.matchesExact("/path2"));
        assertFalse(collection.matchesExact("/path3"));
    }

    @Test
    public void testMatchesExactWithNullPatterns() {
        collection.urlPatterns = null;
        
        // When urlPatterns is null, matchesExact should return true for any path
        assertTrue(collection.matchesExact("/anything"));
        assertTrue(collection.matchesExact("/"));
    }

    // ===== HTTP Method Coverage Tests =====

    @Test
    public void testIsCoveredEmptyMethods() {
        // Empty httpMethods means all methods are covered
        assertTrue(collection.isCovered("GET"));
        assertTrue(collection.isCovered("POST"));
        assertTrue(collection.isCovered("PUT"));
        assertTrue(collection.isCovered("DELETE"));
    }

    @Test
    public void testIsCoveredSpecificMethods() {
        collection.httpMethods.add("GET");
        collection.httpMethods.add("POST");
        
        assertTrue(collection.isCovered("GET"));
        assertTrue(collection.isCovered("POST"));
        assertFalse(collection.isCovered("PUT"));
        assertFalse(collection.isCovered("DELETE"));
    }

    @Test
    public void testIsCoveredWithOmissions() {
        // Omit GET and POST, all others should be covered
        collection.httpMethodOmissions.add("GET");
        collection.httpMethodOmissions.add("POST");
        
        assertFalse(collection.isCovered("GET"));
        assertFalse(collection.isCovered("POST"));
        assertTrue(collection.isCovered("PUT"));
        assertTrue(collection.isCovered("DELETE"));
    }

    @Test
    public void testIsCoveredOmissionsOverrideMethods() {
        // If a method is in both httpMethods and httpMethodOmissions,
        // omission takes precedence
        collection.httpMethods.add("GET");
        collection.httpMethods.add("POST");
        collection.httpMethodOmissions.add("GET");
        
        assertFalse(collection.isCovered("GET"));
        assertTrue(collection.isCovered("POST"));
        assertFalse(collection.isCovered("PUT")); // Not in httpMethods
    }

    // ===== Full matches() with HTTP Methods =====

    @Test
    public void testMatchesWithHttpMethods() {
        collection.urlPatterns.add("/admin/*");
        collection.httpMethods.add("GET");
        collection.httpMethods.add("POST");
        
        assertTrue(collection.matches("GET", "/admin/users"));
        assertTrue(collection.matches("POST", "/admin/users"));
        assertFalse(collection.matches("DELETE", "/admin/users"));
    }

    @Test
    public void testMatchesWithOmissions() {
        collection.urlPatterns.add("/public/*");
        collection.httpMethodOmissions.add("DELETE");
        
        assertTrue(collection.matches("GET", "/public/page"));
        assertTrue(collection.matches("POST", "/public/page"));
        assertFalse(collection.matches("DELETE", "/public/page"));
    }

    @Test
    public void testMatchesNullUrlPatterns() {
        // null urlPatterns (used for @ServletSecurity annotations)
        collection.urlPatterns = null;
        
        // Should only check method coverage
        assertTrue(collection.matches("GET", "/any/path"));
        assertTrue(collection.matches("POST", "/completely/different"));
    }

    @Test
    public void testMatchesNullUrlPatternsWithMethodRestriction() {
        collection.urlPatterns = null;
        collection.httpMethods.add("GET");
        
        assertTrue(collection.matches("GET", "/any/path"));
        assertFalse(collection.matches("POST", "/any/path"));
    }

    // ===== Edge Cases =====

    @Test
    public void testEmptyUrlPatterns() {
        // Empty list (not null) - nothing should match
        assertFalse(collection.matches("GET", "/anything"));
        assertFalse(collection.matches("GET", "/"));
    }

    @Test
    public void testEmptyPath() {
        collection.urlPatterns.add("");
        
        assertTrue(collection.matches("GET", ""));
        assertFalse(collection.matches("GET", "/"));
    }

    @Test
    public void testCaseSensitivity() {
        collection.urlPatterns.add("/Admin");
        
        assertTrue(collection.matches("GET", "/Admin"));
        assertFalse(collection.matches("GET", "/admin"));
        assertFalse(collection.matches("GET", "/ADMIN"));
    }

    @Test
    public void testMethodCaseSensitivity() {
        collection.httpMethods.add("GET");
        
        assertTrue(collection.isCovered("GET"));
        assertFalse(collection.isCovered("get"));
        assertFalse(collection.isCovered("Get"));
    }

    @Test
    public void testTrailingSlashExactMatch() {
        collection.urlPatterns.add("/path");
        
        assertTrue(collection.matches("GET", "/path"));
        assertFalse(collection.matches("GET", "/path/"));
    }

    @Test
    public void testPrefixBoundary() {
        collection.urlPatterns.add("/api/*");
        
        // /api/ should match (path starts with /api/)
        assertTrue(collection.matches("GET", "/api/"));
        assertTrue(collection.matches("GET", "/api/test"));
        
        // /api should NOT match - the pattern is /api/* which requires /api/ prefix
        // Actually looking at the code: path.startsWith(pattern.substring(0, pattern.length() - 1))
        // For pattern "/api/*", substring gives "/api/" 
        // So "/api" does not start with "/api/" - correctly false
        assertFalse(collection.matches("GET", "/api"));
        
        // /apitest should NOT match (different path, not a subpath)
        assertFalse(collection.matches("GET", "/apitest"));
    }

}

