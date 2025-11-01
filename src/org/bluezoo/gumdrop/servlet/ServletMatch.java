/*
 * ServletMatch.java
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
