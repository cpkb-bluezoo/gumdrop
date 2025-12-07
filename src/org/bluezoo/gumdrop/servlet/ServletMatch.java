/*
 * ServletMatch.java
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

import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.MappingMatch;

/**
 * The servlet name, servlet path, and path info when matching a servlet
 * mapping.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ServletMatch implements HttpServletMapping {

    ServletDef servletDef;
    String servletPath;
    String pathInfo;
    MappingMatch mappingMatch;
    String matchValue;

    @Override public String getMatchValue() {
        return matchValue.startsWith("/") ? matchValue.substring(1) : matchValue;
    }

    @Override public String getPattern() {
        return servletPath.startsWith("/") ? servletPath.substring(1) : servletPath;
    }

    @Override public String getServletName() {
        return servletDef.name;
    }

    @Override public MappingMatch getMappingMatch() {
        return mappingMatch;
    }

}
