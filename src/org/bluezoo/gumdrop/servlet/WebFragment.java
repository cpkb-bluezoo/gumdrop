/*
 * WebFragment.java
 * Copyright (C) 2025 Chris Burdess
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
