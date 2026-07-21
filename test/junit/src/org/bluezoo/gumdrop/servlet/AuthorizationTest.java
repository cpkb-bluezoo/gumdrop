/*
 * AuthorizationTest.java
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

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.annotation.ServletSecurity;

import org.bluezoo.gumdrop.servlet.ContextRequestDispatcher.ConstraintRequirement;
import org.bluezoo.gumdrop.servlet.ContextRequestDispatcher.RoleTester;

import static org.bluezoo.gumdrop.servlet.ContextRequestDispatcher.isRoleAuthorized;
import static org.bluezoo.gumdrop.servlet.ContextRequestDispatcher.requirementOf;
import static org.junit.Assert.*;

/**
 * Regression tests for the servlet authorization decision logic in
 * {@link ContextRequestDispatcher}.
 *
 * <p>These lock in the fix for the authorization bypass in which an
 * authenticated user was granted access to resources protected by roles they
 * did not hold (because a failed role check re-invoked authentication instead
 * of denying access).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AuthorizationTest {

    /** A role tester backed by a fixed set of granted roles. */
    private static RoleTester user(String... roles) {
        final Set<String> granted = new HashSet<String>(Arrays.asList(roles));
        return new RoleTester() {
            public boolean isUserInRole(String roleName) {
                return granted.contains(roleName);
            }
        };
    }

    private static SecurityConstraint constraintWithRoles(String... roles) {
        SecurityConstraint sc = new SecurityConstraint();
        for (String role : roles) {
            sc.addAuthConstraint(role);
        }
        return sc;
    }

    // ===== requirementOf() =====

    @Test
    public void testNoAuthConstraintImposesNoRestriction() {
        // No auth-constraint element: authConstraints empty, default PERMIT.
        SecurityConstraint sc = new SecurityConstraint();
        assertEquals(ConstraintRequirement.NONE, requirementOf(sc));
    }

    @Test
    public void testEmptyAuthConstraintDeniesAll() {
        // Empty <auth-constraint/>: parser sets emptyRoleSemantic = DENY.
        SecurityConstraint sc = new SecurityConstraint();
        sc.emptyRoleSemantic = ServletSecurity.EmptyRoleSemantic.DENY;
        assertEquals(ConstraintRequirement.DENY_ALL, requirementOf(sc));
    }

    @Test
    public void testNullRolePlaceholderWithDenyIsDenyAll() {
        // Defensive: a null placeholder role with DENY semantic denies all.
        SecurityConstraint sc = new SecurityConstraint();
        sc.addAuthConstraint(null);
        sc.emptyRoleSemantic = ServletSecurity.EmptyRoleSemantic.DENY;
        assertEquals(ConstraintRequirement.DENY_ALL, requirementOf(sc));
    }

    @Test
    public void testRolesRequireRoleMembership() {
        SecurityConstraint sc = constraintWithRoles("manager");
        assertEquals(ConstraintRequirement.ROLE, requirementOf(sc));
    }

    // ===== isRoleAuthorized() =====

    @Test
    public void testWrongRoleIsDenied() {
        // The core regression: an authenticated user lacking the role is NOT
        // authorized.
        SecurityConstraint sc = constraintWithRoles("manager");
        assertFalse(isRoleAuthorized(sc.authConstraints, user("user")));
    }

    @Test
    public void testCorrectRoleIsAuthorized() {
        SecurityConstraint sc = constraintWithRoles("manager");
        assertTrue(isRoleAuthorized(sc.authConstraints, user("manager")));
    }

    @Test
    public void testMultipleRolesAnyMatchAuthorizes() {
        SecurityConstraint sc = constraintWithRoles("admin", "manager");
        assertTrue(isRoleAuthorized(sc.authConstraints, user("manager")));
        assertFalse(isRoleAuthorized(sc.authConstraints, user("guest")));
    }

    @Test
    public void testUserWithNoRolesIsDenied() {
        SecurityConstraint sc = constraintWithRoles("manager");
        assertFalse(isRoleAuthorized(sc.authConstraints, user()));
    }

    @Test
    public void testDoubleStarMatchesAnyAuthenticatedUser() {
        SecurityConstraint sc = constraintWithRoles("**");
        assertTrue(isRoleAuthorized(sc.authConstraints, user()));
        assertTrue(isRoleAuthorized(sc.authConstraints, user("anything")));
    }

    @Test
    public void testSingleStarMatchesAnyAuthenticatedUser() {
        SecurityConstraint sc = constraintWithRoles("*");
        assertTrue(isRoleAuthorized(sc.authConstraints, user()));
    }

    @Test
    public void testNullAndEmptyRolesAreIgnored() {
        SecurityConstraint sc = new SecurityConstraint();
        sc.addAuthConstraint(null);
        sc.addAuthConstraint("");
        assertFalse(isRoleAuthorized(sc.authConstraints, user("manager")));
    }

    // ===== Manager admin app scenario =====

    @Test
    public void testManagerAppBlocksNonManager() {
        // The bundled manager webapp protects /* with role "manager".
        SecurityConstraint managerConstraint = constraintWithRoles("manager");

        // A non-manager authenticated user must be denied (requires a role,
        // and the user is not in it).
        assertEquals(ConstraintRequirement.ROLE, requirementOf(managerConstraint));
        assertFalse(isRoleAuthorized(managerConstraint.authConstraints, user("ftp-read")));

        // The manager user is allowed.
        assertTrue(isRoleAuthorized(managerConstraint.authConstraints, user("manager")));
    }

    @Test
    public void testDenyAllConstraintDeniesEvenManager() {
        SecurityConstraint denyAll = new SecurityConstraint();
        denyAll.emptyRoleSemantic = ServletSecurity.EmptyRoleSemantic.DENY;
        assertEquals(ConstraintRequirement.DENY_ALL, requirementOf(denyAll));
        // isRoleAuthorized is not consulted for DENY_ALL, but confirm an empty
        // role set never authorizes anyone.
        assertFalse(isRoleAuthorized(Collections.<String>emptyList(), user("manager")));
    }
}
