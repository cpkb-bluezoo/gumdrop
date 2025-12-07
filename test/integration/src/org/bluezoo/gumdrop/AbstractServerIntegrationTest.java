/*
 * AbstractServerIntegrationTest.java
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

package org.bluezoo.gumdrop;

import org.junit.After;
import org.junit.Before;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for server integration tests.
 * 
 * <p>This class handles the lifecycle of starting and stopping the Gumdrop
 * server with a test configuration, providing a real network environment
 * for end-to-end testing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class AbstractServerIntegrationTest {
    
    protected Gumdrop gumdrop;
    protected ComponentRegistry registry;
    protected Collection<Server> servers;
    
    private Logger rootLogger;
    private Level originalLogLevel;
    
    /**
     * Returns the configuration file for this test.
     */
    protected abstract File getTestConfigFile();
    
    /**
     * Returns the maximum time to wait for server startup (milliseconds).
     * Default is 5 seconds.
     */
    protected long getStartupTimeout() {
        return 5000;
    }
    
    /**
     * Returns the maximum time to wait for server shutdown (milliseconds).
     * Default is 5 seconds.
     */
    protected long getShutdownTimeout() {
        return 5000;
    }
    
    @Before
    public void startServer() throws Exception {
        // Reduce logging noise during tests
        rootLogger = Logger.getLogger("");
        originalLogLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.WARNING);
        
        File configFile = getTestConfigFile();
        if (!configFile.exists()) {
            throw new IllegalStateException("Test configuration file not found: " + configFile);
        }
        
        // Parse configuration
        ParseResult result = new ConfigurationParser().parse(configFile);
        registry = result.getRegistry();
        servers = result.getServers();
        
        // Verify we have servers
        if (servers == null || servers.isEmpty()) {
            throw new IllegalStateException("No servers configured in: " + configFile);
        }
        
        // Create and start Gumdrop with 2 worker threads for testing
        gumdrop = new Gumdrop(servers, 2);
        gumdrop.start();
        
        // Wait for server to be ready
        waitForServerReady();
    }
    
    @After
    public void stopServer() throws Exception {
        try {
            if (gumdrop != null) {
                gumdrop.shutdown();
                try {
                    gumdrop.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            if (registry != null) {
                registry.shutdown();
            }
            
            // Allow time for port release (TIME_WAIT socket state)
            Thread.sleep(1500);
        } finally {
            if (rootLogger != null && originalLogLevel != null) {
                rootLogger.setLevel(originalLogLevel);
            }
        }
    }
    
    /**
     * Waits for the server to be ready by checking if ports are bound.
     */
    protected void waitForServerReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + getStartupTimeout();
        
        while (System.currentTimeMillis() < deadline) {
            boolean allReady = true;
            
            for (Server server : servers) {
                if (!isPortListening("127.0.0.1", server.getPort())) {
                    allReady = false;
                    break;
                }
            }
            
            if (allReady) {
                // Wait for server to process any probe connections from isPortListening()
                // before the actual test begins
                Thread.sleep(500);
                return;
            }
            
            Thread.sleep(100);
        }
        
        throw new IllegalStateException("Server failed to start within timeout");
    }
    
    /**
     * Checks if a port is listening for connections.
     */
    protected boolean isPortListening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Pauses briefly to allow async operations to complete.
     */
    protected void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Pauses for a specified duration.
     */
    protected void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

