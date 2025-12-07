/*
 * EjbRef.java
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

import javax.ejb.EJB;
import javax.naming.NamingException;

/**
 * A reference to an enterprise bean.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class EjbRef implements Injectable {

    String description;
    String name; // ejb-ref-name
    String className; // ejb-ref-type
    String home; // home or local-home
    String remoteOrLocal; // remote or local
    String ejbLink;
    boolean remote; // if this is a reference to a remote EJB

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    EjbRef(boolean remote) {
        this.remote = remote;
    }

    void init(EJB config) {
        description = config.description();
        name = config.name();
        className = config.beanInterface().getName();
        ejbLink = config.beanName();
        lookupName = config.lookup();
        mappedName = config.mappedName();
    }

    // -- Injectable --

    @Override public String getLookupName() {
        return lookupName;
    }

    @Override public void setLookupName(String lookupName) {
        this.lookupName = lookupName;
    }

    @Override public InjectionTarget getInjectionTarget() {
        return injectionTarget;
    }

    @Override public void setInjectionTarget(InjectionTarget injectionTarget) {
        this.injectionTarget = injectionTarget;
    }

    @Override public String getMappedName() {
        return mappedName;
    }

    @Override public void setMappedName(String mappedName) {
        this.mappedName = mappedName;
    }

    @Override public String getDefaultName() {
        return ejbLink;
    }

    @Override public Object resolve(javax.naming.Context ctx) throws NamingException {
        // Note that the order for resolution is different for EJBs
        Object resolved = ctx.lookup(lookupName);
        if (resolved != null) {
            return resolved;
        }
        resolved = ctx.lookup(ejbLink);
        if (resolved != null) {                                                                                                     return resolved;
        }
        String defaultName = getDefaultName();
        resolved = ctx.lookup("java:comp/env/" + mappedName);
        if (resolved != null) {                                                                                                     return resolved;
        }
        // TODO try some other shenanigans
        return resolved;
    }

}
