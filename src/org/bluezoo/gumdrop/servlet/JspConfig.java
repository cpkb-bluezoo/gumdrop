/*
 * JspConfig.java
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
