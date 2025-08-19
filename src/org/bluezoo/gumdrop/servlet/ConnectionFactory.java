/*
 * ConnectionFactory.java
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

/**
 * A JCA connection factory.
 * Corresponds to a <code>connection-factory</code> element in a
 * deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ConnectionFactory extends Resource {

    String jndiName;
    String connectionDefinitionId;
    Map<String,String> properties = new LinkedHashMap<>();

    void addProperty(String name, String value) {
        properties.put(name, value);
    }

    @Override String getName() {
        return jndiName;
    }

    @Override String getClassName() {
        return null;
    }

    @Override String getInterfaceName() {
        // At this point we have to get the definition from the ra.xml
        // The <connector> top level element has multiple
        // <connection-definition> children
        // We need to select the one with the
        // <managedconnectionfactory-class> content corresponding to our
        // connectionDefinitionId
        // The interface is <connectionfactory-interface>
        return null; // TODO
    }

    @Override Object newInstance() {
        // TODO
        return null;
    }

}
