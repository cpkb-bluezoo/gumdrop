/*
 * SecurityConstraintTest.java
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

import javax.servlet.annotation.ServletSecurity;

import static org.junit.Assert.*;

/**
 * Unit tests for SecurityConstraint.
 * 
 * Tests security constraint functionality including:
 * - Resource collection matching
 * - Auth constraint management
 * - Transport guarantee settings
 * - Empty role semantic handling
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SecurityConstraintTest {

    private SecurityConstraint constraint;

    @Before
    public void setUp() {
        constraint = new SecurityConstraint();
    }

    // ===== Default Value Tests =====

    @Test
    public void testDefaultDisplayName() {
        assertNull(constraint.displayName);
    }

    @Test
    public void testDefaultResourceCollections() {
        assertNotNull(constraint.resourceCollections);
        assertTrue(constraint.resourceCollections.isEmpty());
    }

    @Test
    public void testDefaultAuthConstraints() {
        assertNotNull(constraint.authConstraints);
        assertTrue(constraint.authConstraints.isEmpty());
    }

    @Test
    public void testDefaultTransportGuarantee() {
        assertEquals(ServletSecurity.TransportGuarantee.NONE, constraint.transportGuarantee);
    }

    @Test
    public void testDefaultEmptyRoleSemantic() {
        assertEquals(ServletSecurity.EmptyRoleSemantic.PERMIT, constraint.emptyRoleSemantic);
    }

    // ===== addResourceCollection Tests =====

    @Test
    public void testAddResourceCollection() {
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add("/admin/*");
        
        constraint.addResourceCollection(rc);
        
        assertEquals(1, constraint.resourceCollections.size());
        assertSame(rc, constraint.resourceCollections.get(0));
    }

    @Test
    public void testAddMultipleResourceCollections() {
        ResourceCollection rc1 = new ResourceCollection();
        rc1.urlPatterns.add("/admin/*");
        
        ResourceCollection rc2 = new ResourceCollection();
        rc2.urlPatterns.add("/api/*");
        
        constraint.addResourceCollection(rc1);
        constraint.addResourceCollection(rc2);
        
        assertEquals(2, constraint.resourceCollections.size());
    }

    // ===== addAuthConstraint Tests =====

    @Test
    public void testAddAuthConstraint() {
        constraint.addAuthConstraint("admin");
        
        assertEquals(1, constraint.authConstraints.size());
        assertTrue(constraint.authConstraints.contains("admin"));
    }

    @Test
    public void testAddMultipleAuthConstraints() {
        constraint.addAuthConstraint("admin");
        constraint.addAuthConstraint("manager");
        constraint.addAuthConstraint("user");
        
        assertEquals(3, constraint.authConstraints.size());
        assertTrue(constraint.authConstraints.contains("admin"));
        assertTrue(constraint.authConstraints.contains("manager"));
        assertTrue(constraint.authConstraints.contains("user"));
    }

    @Test
    public void testAddDuplicateAuthConstraint() {
        constraint.addAuthConstraint("admin");
        constraint.addAuthConstraint("admin");
        
        // List allows duplicates
        assertEquals(2, constraint.authConstraints.size());
    }

    // ===== matches() Tests =====

    @Test
    public void testMatchesExactPath() {
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add("/admin/dashboard");
        constraint.addResourceCollection(rc);
        
        assertTrue(constraint.matches("GET", "/admin/dashboard"));
        assertFalse(constraint.matches("GET", "/admin/other"));
    }

    @Test
    public void testMatchesPrefixPath() {
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add("/admin/*");
        constraint.addResourceCollection(rc);
        
        assertTrue(constraint.matches("GET", "/admin/dashboard"));
        assertTrue(constraint.matches("GET", "/admin/users/list"));
        assertFalse(constraint.matches("GET", "/public/page"));
    }

    @Test
    public void testMatchesExtensionPath() {
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add("*.admin");
        constraint.addResourceCollection(rc);
        
        assertTrue(constraint.matches("GET", "/page.admin"));
        assertTrue(constraint.matches("GET", "/deep/path/config.admin"));
        assertFalse(constraint.matches("GET", "/page.html"));
    }

    @Test
    public void testMatchesMultipleResourceCollections() {
        ResourceCollection rc1 = new ResourceCollection();
        rc1.urlPatterns.add("/admin/*");
        
        ResourceCollection rc2 = new ResourceCollection();
        rc2.urlPatterns.add("/api/*");
        
        constraint.addResourceCollection(rc1);
        constraint.addResourceCollection(rc2);
        
        assertTrue(constraint.matches("GET", "/admin/users"));
        assertTrue(constraint.matches("GET", "/api/data"));
        assertFalse(constraint.matches("GET", "/public/page"));
    }

    @Test
    public void testMatchesWithHttpMethodRestriction() {
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add("/admin/*");
        rc.httpMethods.add("GET");
        rc.httpMethods.add("POST");
        constraint.addResourceCollection(rc);
        
        assertTrue(constraint.matches("GET", "/admin/users"));
        assertTrue(constraint.matches("POST", "/admin/users"));
        assertFalse(constraint.matches("DELETE", "/admin/users"));
    }

    @Test
    public void testMatchesEmptyResourceCollections() {
        // No resource collections means nothing matches
        assertFalse(constraint.matches("GET", "/anything"));
        assertFalse(constraint.matches("POST", "/admin"));
    }

    @Test
    public void testMatchesWithMethodOmissions() {
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add("/public/*");
        rc.httpMethodOmissions.add("DELETE");
        constraint.addResourceCollection(rc);
        
        assertTrue(constraint.matches("GET", "/public/page"));
        assertTrue(constraint.matches("POST", "/public/page"));
        assertFalse(constraint.matches("DELETE", "/public/page"));
    }

    // ===== Field Assignment Tests =====

    @Test
    public void testDisplayName() {
        constraint.displayName = "Admin Constraint";
        assertEquals("Admin Constraint", constraint.displayName);
    }

    @Test
    public void testTransportGuaranteeConfidential() {
        constraint.transportGuarantee = ServletSecurity.TransportGuarantee.CONFIDENTIAL;
        assertEquals(ServletSecurity.TransportGuarantee.CONFIDENTIAL, constraint.transportGuarantee);
    }

    @Test
    public void testTransportGuaranteeNone() {
        constraint.transportGuarantee = ServletSecurity.TransportGuarantee.NONE;
        assertEquals(ServletSecurity.TransportGuarantee.NONE, constraint.transportGuarantee);
    }

    @Test
    public void testEmptyRoleSemanticPermit() {
        constraint.emptyRoleSemantic = ServletSecurity.EmptyRoleSemantic.PERMIT;
        assertEquals(ServletSecurity.EmptyRoleSemantic.PERMIT, constraint.emptyRoleSemantic);
    }

    @Test
    public void testEmptyRoleSemanticDeny() {
        constraint.emptyRoleSemantic = ServletSecurity.EmptyRoleSemantic.DENY;
        assertEquals(ServletSecurity.EmptyRoleSemantic.DENY, constraint.emptyRoleSemantic);
    }

    // ===== Complex Configuration Tests =====

    @Test
    public void testAdminSecurityConstraint() {
        // Typical admin section constraint
        constraint.displayName = "Admin Protection";
        
        ResourceCollection rc = new ResourceCollection();
        rc.name = "Admin Resources";
        rc.urlPatterns.add("/admin/*");
        constraint.addResourceCollection(rc);
        
        constraint.addAuthConstraint("admin");
        constraint.addAuthConstraint("superuser");
        constraint.transportGuarantee = ServletSecurity.TransportGuarantee.CONFIDENTIAL;
        
        assertTrue(constraint.matches("GET", "/admin/dashboard"));
        assertTrue(constraint.matches("POST", "/admin/settings"));
        assertEquals(2, constraint.authConstraints.size());
        assertEquals(ServletSecurity.TransportGuarantee.CONFIDENTIAL, constraint.transportGuarantee);
    }

    @Test
    public void testApiSecurityConstraint() {
        // API endpoint constraint with specific methods
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add("/api/*");
        rc.httpMethods.add("POST");
        rc.httpMethods.add("PUT");
        rc.httpMethods.add("DELETE");
        constraint.addResourceCollection(rc);
        
        constraint.addAuthConstraint("api-user");
        
        // Only modification methods should be constrained
        assertFalse(constraint.matches("GET", "/api/data")); // GET not in httpMethods
        assertTrue(constraint.matches("POST", "/api/data"));
        assertTrue(constraint.matches("PUT", "/api/data"));
        assertTrue(constraint.matches("DELETE", "/api/data"));
    }

    @Test
    public void testDenyAllExceptSpecificMethods() {
        // Allow only GET and HEAD, deny all others
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add("/readonly/*");
        rc.httpMethodOmissions.add("GET");
        rc.httpMethodOmissions.add("HEAD");
        constraint.addResourceCollection(rc);
        
        // GET and HEAD are omitted from the constraint (allowed without auth)
        assertFalse(constraint.matches("GET", "/readonly/page"));
        assertFalse(constraint.matches("HEAD", "/readonly/page"));
        
        // Other methods match the constraint (require auth)
        assertTrue(constraint.matches("POST", "/readonly/page"));
        assertTrue(constraint.matches("DELETE", "/readonly/page"));
    }

    // ===== Edge Cases =====

    @Test
    public void testEmptyAuthConstraints() {
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add("/*");
        constraint.addResourceCollection(rc);
        
        // Empty auth constraints with DENY semantic means deny all
        constraint.emptyRoleSemantic = ServletSecurity.EmptyRoleSemantic.DENY;
        
        assertTrue(constraint.authConstraints.isEmpty());
        assertTrue(constraint.matches("GET", "/any/path"));
    }

    @Test
    public void testWildcardRole() {
        constraint.addAuthConstraint("*");
        
        assertTrue(constraint.authConstraints.contains("*"));
    }

    @Test
    public void testNullUrlPatternsInResourceCollection() {
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns = null;
        constraint.addResourceCollection(rc);
        
        // Null patterns should match any path (for @ServletSecurity)
        assertTrue(constraint.matches("GET", "/anything"));
        assertTrue(constraint.matches("POST", "/completely/different/path"));
    }

}

