/*
 * ResourceCollection.java
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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A collection of resources in the web application, and the means of
 * accessing them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ResourceCollection {

    String name;
    String description;
    List<String> urlPatterns = new ArrayList<>(); // this will be set to null for a @ServletSecurity
    Set<String> httpMethods = new LinkedHashSet<>();
    Set<String> httpMethodOmissions = new LinkedHashSet<>();

    /**
     * Does the specified request match a resource in this collection?
     */
    boolean matches(String method, String path) {
        if (urlPatterns == null) {
            return isCovered(method);
        }
        for (String pattern : urlPatterns) {
            if (pattern.equals(path)) {
                // 1. exact match
                return isCovered(method);
            } else if (pattern.endsWith("/*")
                    && path.startsWith(pattern.substring(0, pattern.length() - 1))) {
                // 2. longest path prefix
                return isCovered(method);
            } else if (pattern.startsWith("*.") && path.endsWith(pattern.substring(1))) {
                // 3. extension
                return isCovered(method);
            }
        }
        return false;
    }

    boolean matchesExact(String path) {
        if (urlPatterns == null) {
            return true;
        }
        for (String pattern : urlPatterns) {
            if (pattern.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Is the specified method covered by this constraint?
     */
    boolean isCovered(String method) {
        for (String s : httpMethodOmissions) {
            if (s.equals(method)) {
                return false;
            }
        }
        for (String s : httpMethods) {
            if (s.equals(method)) {
                return true;
            }
        }
        return httpMethods.isEmpty();
    }

}
