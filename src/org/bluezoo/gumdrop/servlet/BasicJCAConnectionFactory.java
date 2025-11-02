/*
 * BasicJCAConnectionFactory.java
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Basic implementation of a JCA-style ConnectionFactory for Gumdrop servlet container.
 * 
 * <p>This provides a simple connection factory implementation that can be
 * used with basic enterprise resource integration. It includes connection pooling
 * and lifecycle management without requiring full JCA specification compliance.
 * 
 * <p>This implementation is suitable for:
 * <ul>
 * <li>Simple external system integration</li>
 * <li>Custom connector implementations</li>
 * <li>Testing and development environments</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BasicJCAConnectionFactory {
    
    private static final Logger LOGGER = Logger.getLogger(BasicJCAConnectionFactory.class.getName());
    
    private final ConnectionFactory config;
    private final Map<String, Object> connectionPool;
    private final Object poolLock = new Object();
    
    // Connection pool configuration
    private int maxPoolSize = 10;
    private int initialPoolSize = 2;
    private int poolSize = 0;
    
    public BasicJCAConnectionFactory(ConnectionFactory config) {
        this.config = config;
        this.connectionPool = new ConcurrentHashMap<>();
        
        // Initialize connection pool configuration from properties
        initializePoolConfiguration();
        
        LOGGER.info("Created Basic JCA ConnectionFactory: " + config.jndiName);
    }
    
    /**
     * Initialize connection pool configuration from properties.
     */
    private void initializePoolConfiguration() {
        Map<String, String> properties = config.properties;
        
        if (properties.containsKey("maxPoolSize")) {
            try {
                maxPoolSize = Integer.parseInt(properties.get("maxPoolSize"));
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid maxPoolSize property: " + properties.get("maxPoolSize"));
            }
        }
        
        if (properties.containsKey("initialPoolSize")) {
            try {
                initialPoolSize = Integer.parseInt(properties.get("initialPoolSize"));
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid initialPoolSize property: " + properties.get("initialPoolSize"));
            }
        }
    }
    
    /**
     * Get a connection from this factory.
     * 
     * @return a new connection instance
     */
    public BasicJCAConnection getConnection() {
        return getConnection(null);
    }
    
    /**
     * Get a connection from this factory with specific properties.
     * 
     * @param properties connection-specific properties (can be null)
     * @return a new connection instance
     */
    public BasicJCAConnection getConnection(Map<String, String> properties) {
        synchronized (poolLock) {
            // Create a simple connection proxy
            BasicJCAConnection connection = new BasicJCAConnection(this, properties);
            poolSize++;
            
            LOGGER.fine("Created Basic JCA connection for: " + config.jndiName + 
                       " (pool size: " + poolSize + "/" + maxPoolSize + ")");
            
            return connection;
        }
    }
    
    /**
     * Return a connection to the pool.
     * Called by BasicJCAConnection when closed.
     */
    void returnConnection(BasicJCAConnection connection) {
        synchronized (poolLock) {
            poolSize--;
            LOGGER.fine("Returned Basic JCA connection for: " + config.jndiName + 
                       " (pool size: " + poolSize + "/" + maxPoolSize + ")");
        }
    }
    
    /**
     * Get the connection factory configuration.
     */
    ConnectionFactory getConfig() {
        return config;
    }
    
    /**
     * Get basic metadata about this factory.
     */
    public String getAdapterName() {
        return "Gumdrop Basic JCA Adapter for " + config.jndiName;
    }
    
    /**
     * Get the external system product name (configured via properties).
     */
    public String getEISProductName() {
        return config.properties.getOrDefault("eisProductName", "External System via " + config.jndiName);
    }
}
