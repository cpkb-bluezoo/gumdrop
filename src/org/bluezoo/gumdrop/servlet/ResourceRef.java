/*
 * ResourceRef.java
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

import javax.annotation.Resource;

/**
 * A reference to an external resource.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ResourceRef implements Injectable {

    String description;
    String name; // res-ref-name
    String className; // res-type
    Resource.AuthenticationType resAuth;
    String resSharingScope;

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    void init(Resource config) {
        description = config.description();
        name = config.name();
        resAuth = config.authenticationType();
        lookupName = config.lookup();
        mappedName = config.mappedName();
        className = config.type().getName();
        // TODO config.shareable()
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

    @Override public InjectionTarget getInjectionTarget() {
        return injectionTarget;
    }

    @Override public void setInjectionTarget(InjectionTarget injectionTarget) {
        this.injectionTarget = injectionTarget;
    }

}
