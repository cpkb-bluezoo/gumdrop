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
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for server integration tests.
 * 
 * <p>This class handles the lifecycle of starting and stopping the Gumdrop
 * server with a test configuration, providing a real network environment
 * for end-to-end testing.
 * 
 * <p>Uses the Gumdrop singleton pattern with full lifecycle management:
 * servers are added before start() and removed on shutdown().
 * 
 * <h3>Features</h3>
 * <ul>
 *   <li>Automatic server lifecycle management</li>
 *   <li>Integration with {@link IntegrationTestContext} for diagnostics</li>
 *   <li>Test event logging for post-mortem analysis</li>
 *   <li>Detailed failure diagnostics</li>
 *   <li>Pre-flight environment validation</li>
 * </ul>
 * 
 * <h3>Usage</h3>
 * <pre>
 * public class MyServerTest extends AbstractServerIntegrationTest {
 *     &#64;Override
 *     protected File getTestConfigFile() {
 *         return new File("test/integration/config/my-server-test.xml");
 *     }
 *     
 *     &#64;Test
 *     public void testSomething() {
 *         // Test code - server is already running
 *     }
 * }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class AbstractServerIntegrationTest {
    
    protected Gumdrop gumdrop;
    protected ComponentRegistry registry;
    protected Collection<Server> servers;
    
    /** Test context for diagnostics and utilities */
    protected final IntegrationTestContext testContext = IntegrationTestContext.getInstance();
    
    /** Rule to capture current test name */
    @Rule
    public TestName testName = new TestName();
    
    /** Rule for enhanced test diagnostics */
    @Rule
    public IntegrationTestRule testRule = new IntegrationTestRule();
    
    private Logger rootLogger;
    private Level originalLogLevel;
    private List<String> serverAddresses = new ArrayList<>();
    
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
    
    /**
     * Returns the expected log level during tests.
     * Override to enable more verbose logging for debugging.
     * Default is WARNING.
     */
    protected Level getTestLogLevel() {
        return Level.WARNING;
    }
    
    /**
     * Called once before any tests in the class run.
     * Validates the test environment.
     */
    @BeforeClass
    public static void validateTestEnvironment() {
        IntegrationTestContext ctx = IntegrationTestContext.getInstance();
        if (!ctx.validateEnvironment()) {
            List<String> issues = ctx.getEnvironmentIssues();
            StringBuilder msg = new StringBuilder("Integration test environment not ready:\n");
            for (String issue : issues) {
                msg.append("  - ").append(issue).append("\n");
            }
            System.err.println(msg.toString());
            // Don't fail - allow tests to run and fail individually if needed
        }
    }
    
    @Before
    public void startServer() throws Exception {
        String testId = getClass().getSimpleName() + "." + testName.getMethodName();
        testContext.logEvent("SERVER_SETUP", "Starting server for " + testId);
        
        // Configure logging for tests
        rootLogger = Logger.getLogger("");
        originalLogLevel = rootLogger.getLevel();
        Level testLevel = getTestLogLevel();
        rootLogger.setLevel(testLevel);
        // Also set handler levels to allow FINEST/FINER messages through
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            handler.setLevel(testLevel);
        }
        
        File configFile = getTestConfigFile();
        if (!configFile.exists()) {
            String msg = "Test configuration file not found: " + configFile.getAbsolutePath();
            testContext.logEvent("CONFIG_ERROR", msg);
            throw new IllegalStateException(msg);
        }
        
        testContext.logEvent("CONFIG_LOADED", "Using config: " + configFile.getName());
        
        // Parse configuration
        ParseResult result = new ConfigurationParser().parse(configFile);
        registry = result.getRegistry();
        servers = result.getServers();
        
        // Verify we have servers
        if (servers == null || servers.isEmpty()) {
            String msg = "No servers configured in: " + configFile;
            testContext.logEvent("CONFIG_ERROR", msg);
            throw new IllegalStateException(msg);
        }
        
        // Log server details
        for (Server server : servers) {
            String addr = "127.0.0.1:" + server.getPort();
            serverAddresses.add(addr);
            testContext.logEvent("SERVER_CONFIG", server.getClass().getSimpleName() + " on " + addr);
        }
        
        // Set worker count for testing before getting the singleton
        System.setProperty("gumdrop.workers", "2");
        
        // Get the Gumdrop singleton and add servers
        gumdrop = Gumdrop.getInstance();
        for (Server server : servers) {
            gumdrop.addServer(server);
        }
        
        try {
            gumdrop.start();
            testContext.logEvent("SERVER_STARTING", "Gumdrop.start() called");
        } catch (Exception e) {
            testContext.logEvent("SERVER_START_ERROR", "Failed to start: " + e.getMessage(), e);
            throw e;
        }
        
        // Wait for server to be ready
        waitForServerReady();
        testContext.logEvent("SERVER_READY", "All servers listening");
    }
    
    @After
    public void stopServer() throws Exception {
        String testId = getClass().getSimpleName() + "." + testName.getMethodName();
        testContext.logEvent("SERVER_TEARDOWN", "Stopping server for " + testId);
        
        List<Throwable> errors = new ArrayList<>();
        
        try {
            if (gumdrop != null) {
                gumdrop.shutdown();
                testContext.logEvent("SHUTDOWN", "Gumdrop.shutdown() called");
                try {
                    gumdrop.join();
                    testContext.logEvent("SHUTDOWN", "Gumdrop.join() completed");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.add(e);
                }
            }
            
            if (registry != null) {
                registry.shutdown();
                testContext.logEvent("SHUTDOWN", "Registry shutdown completed");
            }
            
            // Allow time for port release (TIME_WAIT socket state)
            Thread.sleep(1500);
            
            // Verify ports are released
            for (String addr : serverAddresses) {
                String[] parts = addr.split(":");
                int port = Integer.parseInt(parts[1]);
                if (isPortListening(parts[0], port)) {
                    testContext.logEvent("PORT_LEAK", "Port " + port + " still in use after shutdown");
                }
            }
            serverAddresses.clear();
            
        } catch (Exception e) {
            testContext.logEvent("TEARDOWN_ERROR", "Error during teardown", e);
            errors.add(e);
        } finally {
            if (rootLogger != null && originalLogLevel != null) {
                rootLogger.setLevel(originalLogLevel);
            }
        }
        
        if (!errors.isEmpty()) {
            // Log all errors for diagnostics
            for (Throwable error : errors) {
                testContext.logEvent("TEARDOWN_ERROR", formatStackTrace(error));
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
            StringBuilder status = new StringBuilder();
            
            for (Server server : servers) {
                int port = server.getPort();
                boolean listening = isPortListening("127.0.0.1", port);
                status.append(server.getClass().getSimpleName())
                      .append(":").append(port)
                      .append("=").append(listening ? "UP" : "DOWN")
                      .append(" ");
                if (!listening) {
                    allReady = false;
                }
            }
            
            if (allReady) {
                // Wait for server to process any probe connections from isPortListening()
                // before the actual test begins
                Thread.sleep(500);
                return;
            }
            
            testContext.logEvent("SERVER_WAIT", "Waiting... " + status.toString());
            Thread.sleep(100);
        }
        
        // Build detailed failure message
        StringBuilder msg = new StringBuilder("Server failed to start within timeout:\n");
        for (Server server : servers) {
            int port = server.getPort();
            boolean listening = isPortListening("127.0.0.1", port);
            msg.append("  ").append(server.getClass().getSimpleName())
               .append(" on port ").append(port)
               .append(": ").append(listening ? "listening" : "NOT listening")
               .append("\n");
        }
        testContext.logEvent("SERVER_TIMEOUT", msg.toString());
        throw new IllegalStateException(msg.toString());
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
    
    /**
     * Logs a test event for diagnostic purposes.
     */
    protected void log(String message) {
        testContext.logEvent("TEST", message);
    }
    
    /**
     * Logs a test checkpoint for timing analysis.
     */
    protected void checkpoint(String name) {
        testRule.checkpoint(name);
    }
    
    /**
     * Asserts with enhanced diagnostics.
     * On failure, logs additional context about server state.
     */
    protected void assertWithDiagnostics(String message, boolean condition) {
        if (!condition) {
            StringBuilder diag = new StringBuilder();
            diag.append("Assertion failed: ").append(message).append("\n");
            diag.append("Server status:\n");
            for (Server server : servers) {
                int port = server.getPort();
                boolean listening = isPortListening("127.0.0.1", port);
                diag.append("  ").append(server.getClass().getSimpleName())
                    .append(":").append(port)
                    .append(" = ").append(listening ? "UP" : "DOWN")
                    .append("\n");
            }
            testContext.logEvent("ASSERT_FAILED", diag.toString());
            throw new AssertionError(message);
        }
    }
    
    private String formatStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}

