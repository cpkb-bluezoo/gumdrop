/*
 * Injectable.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import javax.naming.NamingException;

/**
 * A reference to a JNDI resource in a web application that potentially
 * contains an injection target.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Injectable {

    /**
     * Returns the lookup name.
     */
    String getLookupName();
    void setLookupName(String lookupName);

    /**
     * Returns the product-specific mapped name.
     */
    String getMappedName();
    void setMappedName(String mappedName);

    /**
     * Returns the default JNDI name.
     */
    default String getDefaultName() {
        InjectionTarget injectionTarget = getInjectionTarget();
        return String.format("java:comp/env/%s/%s", injectionTarget.className, injectionTarget.name);
    }


    InjectionTarget getInjectionTarget();
    void setInjectionTarget(InjectionTarget injectionTarget);

    /**
     * Resolve a reference to the source object.
     */
    default Object resolve(javax.naming.Context ctx) throws NamingException {
        Object resolved = null;
        String lookupName = getLookupName();
        resolved = ctx.lookup(lookupName);
        if (resolved != null) {
            return resolved;
        }
        String mappedName = getMappedName();
        resolved = ctx.lookup("java:comp/env/" + mappedName);
        if (resolved != null) {
            return resolved;
        }
        String defaultName = getDefaultName();
        resolved = ctx.lookup(defaultName);
        return resolved;
    }

}

