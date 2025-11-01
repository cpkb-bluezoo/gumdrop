/*
 * Injectable.java
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

import javax.naming.NamingException;

/**
 * A reference to a JNDI resource in a web application that potentially
 * contains an injection target.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface Injectable {

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
        return String.format("java:comp/env/$s/$s", injectionTarget.className, injectionTarget.name);
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
