/*
 * ResourceCollection.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
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
