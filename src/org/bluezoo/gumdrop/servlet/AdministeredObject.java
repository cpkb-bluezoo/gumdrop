/*
 * AdministeredObject.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import org.xml.sax.Attributes;

/**
 * A JCA administered object.
 * Corresponds to an <code>administered-object</code> element in a
 * deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class AdministeredObject extends Resource implements Injectable {

    String description;
    String jndiName;
    String administeredObjectInterface;
    String administeredObjectClass;
    Map<String,String> properties = new LinkedHashMap<>();

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    // -- Resource --

    @Override public void addProperty(String name, String value) {
        properties.put(name, value);
    }

    @Override public void init(Attributes config) {
        description = config.getValue("description");
        jndiName = config.getValue("jndi-name");
        administeredObjectInterface = config.getValue("administered-object-interface");
        administeredObjectClass = config.getValue("administered-object-class");
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

    // -- Resource --

    @Override String getName() {
        return jndiName;
    }

    @Override String getClassName() {
        return administeredObjectClass;
    }

    @Override String getInterfaceName() {
        return administeredObjectInterface;
    }

}
