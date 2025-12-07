/*
 * OAuth2ConfigurationListener.java
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

package com.example.oauth;

import org.bluezoo.gumdrop.servlet.Context;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servlet context listener that configures OAuth 2.0 authentication.
 * 
 * This listener initializes the OAuth realm and registers it with the Gumdrop
 * servlet context during application startup. It loads configuration from the
 * oauth-config.properties file and supports environment variable overrides.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OAuth2ConfigurationListener implements ServletContextListener {
    
    private static final Logger LOGGER = Logger.getLogger(OAuth2ConfigurationListener.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        LOGGER.info("Initializing OAuth 2.0 configuration");
        
        try {
            // Load OAuth configuration
            Properties config = loadConfiguration();
            
            // Override with environment variables if present
            applyEnvironmentOverrides(config);
            
            // Validate required configuration
            validateConfiguration(config);
            
            // Get the Gumdrop context
            Context context = getGumdropContext(sce);
            if (context == null) {
                throw new IllegalStateException("Cannot access Gumdrop servlet context. " +
                    "Ensure this application is running under Gumdrop servlet container.");
            }
            
            // Create and register the OAuth realm
            OAuthRealm oauthRealm = new OAuthRealm(config);
            String realmName = config.getProperty("oauth.realm.name", "MyOAuthRealm");
            context.addRealm(realmName, oauthRealm);
            
            LOGGER.info("OAuth 2.0 realm '" + realmName + "' configured successfully");
            LOGGER.info("Configuration: " + oauthRealm.getConfigurationSummary());
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize OAuth 2.0 configuration", e);
            throw new RuntimeException("OAuth 2.0 initialization failed", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        LOGGER.info("OAuth 2.0 configuration cleanup completed");
    }
    
    /**
     * Loads OAuth configuration from the properties file.
     */
    private Properties loadConfiguration() throws IOException {
        Properties config = new Properties();
        
        // Try to load from classpath first
        String configFile = "oauth-config.properties";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(configFile)) {
            if (is != null) {
                config.load(is);
                LOGGER.info("Loaded OAuth configuration from classpath: " + configFile);
            } else {
                LOGGER.warning("OAuth configuration file not found in classpath: " + configFile);
            }
        }
        
        return config;
    }
    
    /**
     * Applies environment variable overrides to configuration.
     * Converts properties like oauth.client.id to environment variables like OAUTH_CLIENT_ID.
     */
    private void applyEnvironmentOverrides(Properties config) {
        String[] keys = {
            "oauth.authorization.server.url",
            "oauth.client.id", 
            "oauth.client.secret",
            "oauth.token.introspection.endpoint",
            "oauth.http.timeout",
            "oauth.cache.enabled",
            "oauth.cache.ttl",
            "oauth.log.level"
        };
        
        for (String key : keys) {
            String envVar = key.toUpperCase().replace('.', '_');
            String envValue = System.getenv(envVar);
            
            if (envValue != null && !envValue.trim().isEmpty()) {
                config.setProperty(key, envValue.trim());
                LOGGER.info("Applied environment override: " + key + " (from " + envVar + ")");
            }
        }
        
        // Also check system properties
        for (String key : keys) {
            String sysValue = System.getProperty(key);
            if (sysValue != null && !sysValue.trim().isEmpty()) {
                config.setProperty(key, sysValue.trim());
                LOGGER.info("Applied system property override: " + key);
            }
        }
    }
    
    /**
     * Validates that required OAuth configuration is present.
     */
    private void validateConfiguration(Properties config) {
        String[] requiredKeys = {
            "oauth.authorization.server.url",
            "oauth.client.id",
            "oauth.client.secret"
        };
        
        for (String key : requiredKeys) {
            String value = config.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Required OAuth configuration property missing: " + key + 
                    ". Set this in oauth-config.properties or as environment variable " + 
                    key.toUpperCase().replace('.', '_'));
            }
        }
        
        // Validate URL format
        String serverUrl = config.getProperty("oauth.authorization.server.url");
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            throw new IllegalArgumentException("oauth.authorization.server.url must be a valid HTTP(S) URL, got: " + serverUrl);
        }
        
        LOGGER.info("OAuth configuration validation passed");
    }
    
    /**
     * Gets the Gumdrop servlet context from the servlet context event.
     */
    private Context getGumdropContext(ServletContextEvent sce) {
        // The Gumdrop servlet container should store the Context as an attribute
        // This is a typical pattern for exposing container-specific objects
        Context context = (Context) sce.getServletContext().getAttribute("gumdrop.context");
        
        if (context == null) {
            // Alternative: try to get it from a well-known attribute name
            context = (Context) sce.getServletContext().getAttribute("org.bluezoo.gumdrop.servlet.Context");
        }
        
        if (context == null) {
            // Last resort: check if there's a method to get it
            // This would need to be implemented in the Gumdrop servlet container
            LOGGER.warning("Could not locate Gumdrop Context. Check servlet container integration.");
        }
        
        return context;
    }
}
