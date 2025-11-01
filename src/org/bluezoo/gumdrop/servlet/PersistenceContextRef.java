/*
 * PersistenceContextRef.java
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

import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;

/**
 * A reference to a container-managed <code>EntityManager</code>.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class PersistenceContextRef implements Injectable {

    String description;
    String name; // persistence-context-ref-name
    PersistenceContextType type; // persistence-context-type
    String unitName; // persistence-unit-name

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    void init(PersistenceContext config) {
        name = config.name();
        type = config.type();
        unitName = config.unitName();
    }

    // -- Injectable --

    @Override public String getLookupName() {
        return lookupName;
    }

    @Override public void setLookupName(String lookupName) {
        this.lookupName = lookupName;
    }

    @Override public String getMappedName() {
        return mappedName;
    }

    @Override public void setMappedName(String mappedName) {
        this.mappedName = mappedName;
    }

    @Override public String getDefaultName() {
        return String.format("java:comp/env/$s", unitName);
    }

    @Override public InjectionTarget getInjectionTarget() {
        return injectionTarget;
    }

    @Override public void setInjectionTarget(InjectionTarget injectionTarget) {
        this.injectionTarget = injectionTarget;
    }

}
