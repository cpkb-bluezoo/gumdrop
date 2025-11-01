/*
 * WebFragment.java
 * Copyright (C) 2025 Chris Burdess
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

import java.util.List;

/**
 * Represents a web-fragment.xml file.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class WebFragment extends DeploymentDescriptor {

    // Represents the <others> element. This value cannot exist in XML
    static final String OTHERS = "\u0000";

    String name;
    List<String> before;
    List<String> after;

    boolean isEmpty() {
        return name != null && super.isEmpty();
    }

    boolean isBefore(WebFragment webFragment) {
        return before != null && before.contains(webFragment.name);
    }

    boolean isBeforeOthers() {
        return before != null && before.contains(OTHERS);
    }

    boolean isAfter(WebFragment webFragment) {
        return after != null && after.contains(webFragment.name);
    }

    boolean isAfterOthers() {
        return after != null && after.contains(OTHERS);
    }

}
