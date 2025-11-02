/*
 * ConnectionFactory.java
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
