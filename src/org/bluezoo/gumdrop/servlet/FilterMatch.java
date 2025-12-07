/*
 * FilterMatch.java
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

import javax.servlet.http.MappingMatch;

/**
 * A filter definition/mapping pair.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FilterMatch {

    final FilterDef filterDef;
    final FilterMapping filterMapping;
    final MappingMatch mappingMatch;

    FilterMatch(FilterDef filterDef, FilterMapping filterMapping, MappingMatch mappingMatch) {
        this.filterDef = filterDef;
        this.filterMapping = filterMapping;
        this.mappingMatch = mappingMatch;
    }

    public String toString() {
        StringBuffer buf = new StringBuffer(getClass().getName());
        buf.append("[filterDef=");
        buf.append(filterDef);
        buf.append(",filterMapping=");
        buf.append(filterMapping);
        buf.append(",mappingMatch=");
        buf.append(mappingMatch);
        buf.append("]");
        return buf.toString();
    }

}
