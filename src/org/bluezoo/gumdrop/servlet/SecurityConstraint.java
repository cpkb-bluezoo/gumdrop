/*
 * SecurityConstraint.java
 * Copyright (C) 2005, 2025 Chris Burdess
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.HttpConstraint;
import javax.servlet.annotation.HttpMethodConstraint;

/**
 * An authorization constraint.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SecurityConstraint {

    String displayName;
    List<ResourceCollection> resourceCollections = new ArrayList<>();
    List<String> authConstraints = new ArrayList<>();
    ServletSecurity.TransportGuarantee transportGuarantee = ServletSecurity.TransportGuarantee.NONE;
    ServletSecurity.EmptyRoleSemantic emptyRoleSemantic = ServletSecurity.EmptyRoleSemantic.PERMIT;

    /**
     * Initialize a security constraint that metches the specified method.
     */
    void init(HttpMethodConstraint config) {
        for (String roleName : config.rolesAllowed()) {
            authConstraints.add(roleName);
        }
        transportGuarantee = config.transportGuarantee();
        emptyRoleSemantic = config.emptyRoleSemantic();
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns = null;
        rc.httpMethods = new LinkedHashSet<>(Collections.singleton(config.value()));
        resourceCollections.add(rc);
    }

    /**
     * Initialize a default security constraint that matches all methods
     * that are not in the specified collection.
     */
    void init(HttpConstraint config, Set<String> methods) {
        for (String roleName : config.rolesAllowed()) {
            authConstraints.add(roleName);
        }
        transportGuarantee = config.transportGuarantee();
        emptyRoleSemantic = config.value();
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns = null;
        rc.httpMethodOmissions = methods;
        resourceCollections.add(rc);
    }

    boolean matches(String method, String path) {
        for (ResourceCollection rc : resourceCollections ) {
            if (rc.matches(method, path)) {
                return true;
            }
        }
        return false;
    }

    void addResourceCollection(ResourceCollection resourceCollection) {
        resourceCollections.add(resourceCollection);
    }

    void addAuthConstraint(String roleName) {
        authConstraints.add(roleName);
    }

}
