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

package org.bluezoo.gumdrop.servlet.jndi;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.xml.sax.Attributes;

/**
 * A JCA connection factory.
 * Corresponds to a <code>connection-factory</code> element in a
 * deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ConnectionFactory extends Resource {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.jndi.L10N");
    private static final Logger LOGGER = Logger.getLogger(ConnectionFactory.class.getName());

    String jndiName;
    String connectionDefinitionId;
    Map<String,String> properties = new LinkedHashMap<>();

    // -- Setters and Getters --

    /**
     * Sets the JNDI name by which this connection factory will be registered.
     * Corresponds to the {@code jndi-name} element in the JCA connector deployment descriptor.
     * <p>
     * The name must be a valid JNDI name.
     *
     * @param jndiName the JNDI name for this connection factory
     * @see <a href="https://jakarta.ee/specifications/connectors/2.0/">
     *      Jakarta Connectors 2.0 Specification</a>
     */
    public void setJndiName(String jndiName) {
        this.jndiName = jndiName;
    }

    /**
     * Sets the identifier for the connection definition.
     * Corresponds to the {@code connection-definition-id} element in the JCA connector
     * deployment descriptor.
     * <p>
     * This links the connection factory to a specific connection definition
     * in the resource adapter's deployment descriptor.
     *
     * @param connectionDefinitionId the connection definition identifier
     * @see <a href="https://jakarta.ee/specifications/connectors/2.0/">
     *      Jakarta Connectors 2.0 Specification</a>
     */
    public void setConnectionDefinitionId(String connectionDefinitionId) {
        this.connectionDefinitionId = connectionDefinitionId;
    }

    @Override public void addProperty(String name, String value) {
        properties.put(name, value);
    }

    @Override public void init(Attributes config) {
        jndiName = config.getValue("jndi-name");
        connectionDefinitionId = config.getValue("connection-definition-id");
    }

    @Override public String getName() {
        return jndiName;
    }

    @Override public String getClassName() {
        return null;
    }

    /**
     * Returns the connection factory interface name.
     * <p>
     * For full JCA integration, this would require parsing the resource
     * adapter's {@code ra.xml} to find the {@code connectionfactory-interface}
     * from the {@code connection-definition} matching our
     * {@code connection-definition-id}. Currently returns null as Gumdrop
     * does not implement full JCA resource adapter parsing.
     *
     * @return null (JCA ra.xml parsing not implemented)
     */
    @Override public String getInterfaceName() {
        // Full implementation would require:
        // 1. Parse the resource adapter's META-INF/ra.xml
        // 2. Find the <connection-definition> with matching
        //    <managedconnectionfactory-class> = connectionDefinitionId
        // 3. Return the <connectionfactory-interface> value
        return null;
    }

    @Override public Object newInstance() {
        // For now, create a simple connection factory implementation
        // In a full JCA implementation, this would integrate with resource adapters
        try {
            return new BasicJCAConnectionFactory(this);
        } catch (Exception e) {
            String message = L10N.getString("err.jca_connection_factory");
            message = MessageFormat.format(message, jndiName);
            LOGGER.log(Level.SEVERE, message, e);
            return null;
        }
    }

}

