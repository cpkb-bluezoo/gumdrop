/*
 * BasicJCAConnection.java
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

import java.util.Map;
import java.util.logging.Logger;

/**
 * Basic implementation of a JCA-style Connection for Gumdrop servlet container.
 * 
 * <p>This provides a simple connection implementation that represents
 * an application-level handle to an external system connection.
 * 
 * <p>This class can be extended or used as-is for basic external system
 * integration scenarios.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BasicJCAConnection {
    
    private static final Logger LOGGER = Logger.getLogger(BasicJCAConnection.class.getName());
    
    private final BasicJCAConnectionFactory factory;
    private final Map<String, String> connectionProperties;
    private boolean closed = false;
    
    public BasicJCAConnection(BasicJCAConnectionFactory factory, Map<String, String> connectionProperties) {
        this.factory = factory;
        this.connectionProperties = connectionProperties;
    }
    
    /**
     * Execute a business operation on the external system.
     * This is a simplified version of JCA Interaction.execute().
     * 
     * @param operation the operation name or identifier
     * @param input the input data (can be any object)
     * @return the result from the external system
     * @throws Exception if the operation fails
     */
    public Object execute(String operation, Object input) throws Exception {
        checkClosed();
        
        // In a real implementation, this would:
        // 1. Connect to the external system
        // 2. Execute the business operation
        // 3. Return the results
        
        LOGGER.fine("Executing operation '" + operation + "' on " + factory.getConfig().jndiName);
        
        // Placeholder implementation
        return "Operation '" + operation + "' completed successfully";
    }
    
    /**
     * Get metadata about this connection.
     */
    public String getEISProductName() {
        return factory.getEISProductName();
    }
    
    /**
     * Get the user name associated with this connection.
     */
    public String getUserName() {
        if (connectionProperties != null) {
            return connectionProperties.getOrDefault("username", System.getProperty("user.name"));
        }
        return System.getProperty("user.name");
    }
    
    /**
     * Get a connection property.
     */
    public String getProperty(String name) {
        if (connectionProperties != null) {
            return connectionProperties.get(name);
        }
        return null;
    }
    
    /**
     * Close this connection and return it to the pool.
     */
    public void close() {
        if (!closed) {
            closed = true;
            factory.returnConnection(this);
            LOGGER.fine("Closed Basic JCA connection for: " + factory.getConfig().jndiName);
        }
    }
    
    /**
     * Check if this connection is closed and throw an exception if so.
     */
    private void checkClosed() throws Exception {
        if (closed) {
            throw new Exception("Connection is closed");
        }
    }
}
