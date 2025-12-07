/*
 * FilterMapping.java
 * Copyright (C) 2005 Chris Burdess
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

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.servlet.DispatcherType;

/**
 * A filter mapping entry.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FilterMapping {

    String filterName;
    Set<String> urlPatterns = new LinkedHashSet<>();
    Set<String> servletNames = new LinkedHashSet<>();
    EnumSet<DispatcherType> dispatchers;

    FilterDef filterDef; // resolved link to filter definition
    Set<ServletDef> servletDefs = new LinkedHashSet<>(); // resolved links to servlet defs

    FilterMapping() {
        dispatchers = EnumSet.noneOf(DispatcherType.class);
    }

    FilterMapping(DispatcherType[] dispatcherTypes) {
        dispatchers = EnumSet.copyOf(Arrays.asList(dispatcherTypes));
    }

    void addUrlPattern(String urlPattern) {
        urlPatterns.add(urlPattern);
    }

    void addServletName(String servletName) {
        servletNames.add(servletName);
    }

    boolean matches(DispatcherType dispatcher) {
        if (dispatchers.isEmpty()) {
            // match REQUEST
            return dispatcher == DispatcherType.REQUEST;
        }
        return dispatchers.contains(dispatcher);
    }

}
