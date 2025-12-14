/*
 * IntegrationTestContext.java
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared context for integration tests providing diagnostics, port allocation,
 * and test environment management.
 * 
 * <p>This class provides:
 * <ul>
 *   <li>Unique port allocation to avoid conflicts between tests</li>
 *   <li>Test event logging for post-mortem analysis</li>
 *   <li>Environment pre-flight checks</li>
 *   <li>Performance timing collection</li>
 *   <li>Failure diagnostics with context</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IntegrationTestContext {

    private static final IntegrationTestContext INSTANCE = new IntegrationTestContext();

    // Port allocation range (ephemeral ports above well-known services)
    private static final int PORT_RANGE_START = 30000;
    private static final int PORT_RANGE_END = 40000;
    private final AtomicInteger nextPort = new AtomicInteger(PORT_RANGE_START);
    private final Map<String, Integer> allocatedPorts = new ConcurrentHashMap<>();

    // Test diagnostics
    private final List<TestEvent> events = new ArrayList<>();
    private final Map<String, Long> testTimings = new ConcurrentHashMap<>();
    private final Map<String, TestResult> testResults = new ConcurrentHashMap<>();

    // Environment state
    private boolean environmentValidated = false;
    private List<String> environmentIssues = new ArrayList<>();

    // Log output directory
    private File logDirectory;

    private IntegrationTestContext() {
        logDirectory = new File("test/integration/results/diagnostics");
        logDirectory.mkdirs();
    }

    public static IntegrationTestContext getInstance() {
        return INSTANCE;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Port Allocation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Allocates a unique, available port for the given test component.
     *
     * @param componentName identifier for the component (e.g., "http-server", "smtp-server")
     * @return an available port number
     * @throws RuntimeException if no ports are available
     */
    public synchronized int allocatePort(String componentName) {
        // Check if already allocated
        Integer existing = allocatedPorts.get(componentName);
        if (existing != null) {
            return existing;
        }

        // Find an available port
        int attempts = 0;
        while (attempts < 100) {
            int port = nextPort.getAndIncrement();
            if (port > PORT_RANGE_END) {
                nextPort.set(PORT_RANGE_START);
                port = nextPort.getAndIncrement();
            }

            if (isPortAvailable(port)) {
                allocatedPorts.put(componentName, port);
                logEvent("PORT_ALLOCATED", componentName + " -> " + port);
                return port;
            }
            attempts++;
        }

        throw new RuntimeException("Failed to allocate port for " + componentName + 
            " after " + attempts + " attempts");
    }

    /**
     * Returns a previously allocated port for the component.
     *
     * @param componentName the component identifier
     * @return the allocated port, or -1 if not allocated
     */
    public int getPort(String componentName) {
        Integer port = allocatedPorts.get(componentName);
        return port != null ? port : -1;
    }

    /**
     * Releases a port allocation.
     */
    public void releasePort(String componentName) {
        Integer port = allocatedPorts.remove(componentName);
        if (port != null) {
            logEvent("PORT_RELEASED", componentName + " <- " + port);
        }
    }

    /**
     * Releases all allocated ports.
     */
    public void releaseAllPorts() {
        allocatedPorts.clear();
        logEvent("PORTS_CLEARED", "All port allocations released");
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Environment Validation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Performs pre-flight environment checks.
     * Call this before running tests to detect configuration issues early.
     *
     * @return true if the environment is suitable for integration testing
     */
    public boolean validateEnvironment() {
        environmentIssues.clear();
        
        // Check Java version
        String javaVersion = System.getProperty("java.version");
        logEvent("ENV_CHECK", "Java version: " + javaVersion);

        // Check test directories
        File configDir = new File("test/integration/config");
        if (!configDir.exists() || !configDir.isDirectory()) {
            environmentIssues.add("Integration test config directory not found: " + configDir);
        }

        File certsDir = new File("test/integration/certs");
        if (!certsDir.exists()) {
            certsDir.mkdirs();
            logEvent("ENV_FIX", "Created certs directory: " + certsDir);
        }

        // Check test certificate availability
        File keystore = new File("test/integration/certs/test-keystore.p12");
        if (!keystore.exists()) {
            environmentIssues.add("Test keystore not found: " + keystore + 
                ". Run TestCertificateManager.generateTestPKI() first.");
        }

        // Check if common test ports are available
        int[] testPorts = {18080, 18443, 12599, 11143, 11993, 11110, 11995};
        for (int port : testPorts) {
            if (!isPortAvailable(port)) {
                environmentIssues.add("Test port " + port + " is in use");
            }
        }

        // Check build directory
        File buildDir = new File("build");
        if (!buildDir.exists() || !buildDir.isDirectory()) {
            environmentIssues.add("Build directory not found. Run 'ant build' first.");
        }

        environmentValidated = environmentIssues.isEmpty();
        
        if (!environmentValidated) {
            logEvent("ENV_FAILED", "Environment validation failed with " + 
                environmentIssues.size() + " issues");
            for (String issue : environmentIssues) {
                logEvent("ENV_ISSUE", issue);
            }
        } else {
            logEvent("ENV_OK", "Environment validation passed");
        }

        return environmentValidated;
    }

    /**
     * Returns the list of environment issues found during validation.
     */
    public List<String> getEnvironmentIssues() {
        return new ArrayList<>(environmentIssues);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test Event Logging
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Logs a test event for diagnostic purposes.
     */
    public void logEvent(String type, String message) {
        TestEvent event = new TestEvent(type, message);
        synchronized (events) {
            events.add(event);
        }
    }

    /**
     * Logs a test event with exception details.
     */
    public void logEvent(String type, String message, Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        logEvent(type, message + "\n" + sw.toString());
    }

    /**
     * Returns all logged events.
     */
    public List<TestEvent> getEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /**
     * Clears all logged events.
     */
    public void clearEvents() {
        synchronized (events) {
            events.clear();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test Timing
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Records the start time for a test.
     */
    public void startTiming(String testName) {
        testTimings.put(testName, System.currentTimeMillis());
    }

    /**
     * Records the end time and calculates duration for a test.
     *
     * @return duration in milliseconds, or -1 if start was not recorded
     */
    public long endTiming(String testName) {
        Long startTime = testTimings.get(testName);
        if (startTime == null) {
            return -1;
        }
        long duration = System.currentTimeMillis() - startTime;
        logEvent("TIMING", testName + ": " + duration + "ms");
        return duration;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test Results
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Records the result of a test.
     */
    public void recordResult(String testName, TestResult result) {
        testResults.put(testName, result);
        logEvent(result.status.name(), testName + ": " + result.message);
    }

    /**
     * Returns all recorded test results.
     */
    public Map<String, TestResult> getResults() {
        return new HashMap<>(testResults);
    }

    /**
     * Generates a summary of all test results.
     */
    public TestSummary getSummary() {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        int errors = 0;
        long totalDuration = 0;

        for (TestResult result : testResults.values()) {
            switch (result.status) {
                case PASSED:
                    passed++;
                    break;
                case FAILED:
                    failed++;
                    break;
                case SKIPPED:
                    skipped++;
                    break;
                case ERROR:
                    errors++;
                    break;
            }
            totalDuration += result.durationMs;
        }

        return new TestSummary(passed, failed, skipped, errors, totalDuration);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Diagnostics Export
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Writes all diagnostic information to a file.
     */
    public void writeDiagnostics(String filename) {
        File file = new File(logDirectory, filename);
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

            writer.println("=== Integration Test Diagnostics ===");
            writer.println("Generated: " + sdf.format(new Date()));
            writer.println();

            // Environment
            writer.println("=== Environment ===");
            writer.println("Java: " + System.getProperty("java.version"));
            writer.println("OS: " + System.getProperty("os.name") + " " + 
                System.getProperty("os.version"));
            writer.println("Validated: " + environmentValidated);
            if (!environmentIssues.isEmpty()) {
                writer.println("Issues:");
                for (String issue : environmentIssues) {
                    writer.println("  - " + issue);
                }
            }
            writer.println();

            // Summary
            TestSummary summary = getSummary();
            writer.println("=== Summary ===");
            writer.println("Passed: " + summary.passed);
            writer.println("Failed: " + summary.failed);
            writer.println("Skipped: " + summary.skipped);
            writer.println("Errors: " + summary.errors);
            writer.println("Total Duration: " + summary.totalDurationMs + "ms");
            writer.println();

            // Port allocations
            writer.println("=== Port Allocations ===");
            for (Map.Entry<String, Integer> entry : allocatedPorts.entrySet()) {
                writer.println(entry.getKey() + " -> " + entry.getValue());
            }
            writer.println();

            // Events
            writer.println("=== Events ===");
            synchronized (events) {
                for (TestEvent event : events) {
                    writer.println(sdf.format(event.timestamp) + " [" + event.type + "] " + 
                        event.message);
                }
            }
            writer.println();

            // Failed tests detail
            writer.println("=== Failed Tests ===");
            for (Map.Entry<String, TestResult> entry : testResults.entrySet()) {
                if (entry.getValue().status == TestStatus.FAILED || 
                    entry.getValue().status == TestStatus.ERROR) {
                    writer.println("Test: " + entry.getKey());
                    writer.println("Message: " + entry.getValue().message);
                    if (entry.getValue().stackTrace != null) {
                        writer.println("Stack Trace:");
                        writer.println(entry.getValue().stackTrace);
                    }
                    writer.println();
                }
            }

            logEvent("DIAGNOSTICS", "Written to " + file.getAbsolutePath());

        } catch (IOException e) {
            logEvent("ERROR", "Failed to write diagnostics", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Network Utilities
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Checks if a port is listening for connections.
     */
    public boolean isPortListening(String host, int port) {
        return isPortListening(host, port, 200);
    }

    /**
     * Checks if a port is listening for connections with a timeout.
     */
    public boolean isPortListening(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Waits for a port to become available for listening.
     *
     * @param host the host to check
     * @param port the port number
     * @param timeoutMs maximum time to wait
     * @return true if the port became available within the timeout
     */
    public boolean waitForPort(String host, int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isPortListening(host, port)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Waits for a port to stop listening (e.g., after server shutdown).
     */
    public boolean waitForPortClosed(String host, int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isPortListening(host, port)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Inner Classes
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Represents a logged test event.
     */
    public static class TestEvent {
        public final Date timestamp;
        public final String type;
        public final String message;

        TestEvent(String type, String message) {
            this.timestamp = new Date();
            this.type = type;
            this.message = message;
        }
    }

    /**
     * Possible test result statuses.
     */
    public enum TestStatus {
        PASSED,
        FAILED,
        SKIPPED,
        ERROR
    }

    /**
     * Represents the result of a single test.
     */
    public static class TestResult {
        public final TestStatus status;
        public final String message;
        public final String stackTrace;
        public final long durationMs;

        public TestResult(TestStatus status, String message, long durationMs) {
            this.status = status;
            this.message = message;
            this.stackTrace = null;
            this.durationMs = durationMs;
        }

        public TestResult(TestStatus status, String message, Throwable error, long durationMs) {
            this.status = status;
            this.message = message;
            this.durationMs = durationMs;
            if (error != null) {
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                this.stackTrace = sw.toString();
            } else {
                this.stackTrace = null;
            }
        }
    }

    /**
     * Summary of all test results.
     */
    public static class TestSummary {
        public final int passed;
        public final int failed;
        public final int skipped;
        public final int errors;
        public final long totalDurationMs;

        TestSummary(int passed, int failed, int skipped, int errors, long totalDurationMs) {
            this.passed = passed;
            this.failed = failed;
            this.skipped = skipped;
            this.errors = errors;
            this.totalDurationMs = totalDurationMs;
        }

        public int getTotal() {
            return passed + failed + skipped + errors;
        }

        public boolean isAllPassed() {
            return failed == 0 && errors == 0;
        }

        @Override
        public String toString() {
            return String.format("Tests: %d, Passed: %d, Failed: %d, Skipped: %d, Errors: %d, Time: %dms",
                getTotal(), passed, failed, skipped, errors, totalDurationMs);
        }
    }
}

