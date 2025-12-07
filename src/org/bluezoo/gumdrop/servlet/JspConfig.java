/*
 * JspConfig.java
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.descriptor.JspPropertyGroupDescriptor;
import javax.servlet.descriptor.TaglibDescriptor;

/**
 * A <code>jsp-config</code> deployment descriptor definition.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspConfig implements JspConfigDescriptor {

    List<Taglib> taglibs = new ArrayList<>();
    List<JspPropertyGroup> jspPropertyGroups = new ArrayList<>();

    void addTaglib(Taglib taglib) {
        taglibs.add(taglib);
    }

    void addJspPropertyGroup(JspPropertyGroup jspPropertyGroup) {
        jspPropertyGroups.add(jspPropertyGroup);
    }

    void merge(JspConfig other) {
        taglibs.addAll(other.taglibs);
        jspPropertyGroups.addAll(other.jspPropertyGroups);
    }

    // -- JspConfigDescriptor --

    @Override public Collection<TaglibDescriptor> getTaglibs() {
        return Collections.unmodifiableList(taglibs);
    }

    @Override public Collection<JspPropertyGroupDescriptor> getJspPropertyGroups() {
        return Collections.unmodifiableList(jspPropertyGroups);
    }

}
