/*
 * ConnectionFactory.java
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

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import javax.servlet.ServletException;
import org.xml.sax.Attributes;

/**
 * A JCA connection factory.
 * Corresponds to a <code>connection-factory</code> element in a
 * deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ConnectionFactory extends Resource {

    String jndiName;
    String connectionDefinitionId;
    Map<String,String> properties = new LinkedHashMap<>();

    @Override public void addProperty(String name, String value) {
        properties.put(name, value);
    }

    @Override public void init(Attributes config) {
        jndiName = config.getValue("jndi-name");
        connectionDefinitionId = config.getValue("connection-definition-id");
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
        // For now, create a simple connection factory implementation
        // In a full JCA implementation, this would integrate with resource adapters
        try {
            return new BasicJCAConnectionFactory(this);
        } catch (Exception e) {
            String message = "Failed to create JCA connection factory: " + jndiName;
            Context.LOGGER.log(Level.SEVERE, message, e);
            return null;
        }
    }

}
