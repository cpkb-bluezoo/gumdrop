/*
 * IntegrationTestRule.java
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

import org.junit.AssumptionViolatedException;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/**
 * JUnit rule that provides enhanced diagnostics for integration tests.
 * 
 * <p>This rule:
 * <ul>
 *   <li>Records test start/end times for performance analysis</li>
 *   <li>Captures test outcomes with detailed context</li>
 *   <li>Logs test lifecycle events for debugging</li>
 *   <li>Provides failure context for post-mortem analysis</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * public class MyIntegrationTest {
 *     &#64;Rule
 *     public IntegrationTestRule testRule = new IntegrationTestRule();
 *     
 *     &#64;Test
 *     public void myTest() {
 *         // Test code
 *     }
 * }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IntegrationTestRule extends TestWatcher {

    private final IntegrationTestContext context;
    private String currentTestName;
    private long startTime;

    public IntegrationTestRule() {
        this.context = IntegrationTestContext.getInstance();
    }

    @Override
    protected void starting(Description description) {
        currentTestName = getTestName(description);
        startTime = System.currentTimeMillis();
        
        context.startTiming(currentTestName);
        context.logEvent("TEST_START", currentTestName);
    }

    @Override
    protected void succeeded(Description description) {
        long duration = System.currentTimeMillis() - startTime;
        context.endTiming(currentTestName);
        context.recordResult(currentTestName, 
            new IntegrationTestContext.TestResult(
                IntegrationTestContext.TestStatus.PASSED,
                "Test passed",
                duration
            ));
        context.logEvent("TEST_PASSED", currentTestName + " (" + duration + "ms)");
    }

    @Override
    protected void failed(Throwable e, Description description) {
        long duration = System.currentTimeMillis() - startTime;
        context.endTiming(currentTestName);
        
        // Build detailed failure message
        String message = buildFailureMessage(e, description);
        
        context.recordResult(currentTestName, 
            new IntegrationTestContext.TestResult(
                IntegrationTestContext.TestStatus.FAILED,
                message,
                e,
                duration
            ));
        context.logEvent("TEST_FAILED", currentTestName + ": " + e.getMessage(), e);
    }

    @Override
    protected void skipped(AssumptionViolatedException e, Description description) {
        long duration = System.currentTimeMillis() - startTime;
        context.endTiming(currentTestName);
        context.recordResult(currentTestName, 
            new IntegrationTestContext.TestResult(
                IntegrationTestContext.TestStatus.SKIPPED,
                "Skipped: " + e.getMessage(),
                duration
            ));
        context.logEvent("TEST_SKIPPED", currentTestName + ": " + e.getMessage());
    }

    @Override
    protected void finished(Description description) {
        context.logEvent("TEST_FINISHED", currentTestName);
    }

    /**
     * Returns the current test's full name (class.method).
     */
    public String getCurrentTestName() {
        return currentTestName;
    }

    /**
     * Returns the elapsed time since the test started.
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Logs an event for the current test.
     */
    public void log(String message) {
        context.logEvent("TEST_LOG", currentTestName + ": " + message);
    }

    /**
     * Logs a checkpoint during a long-running test.
     */
    public void checkpoint(String name) {
        long elapsed = getElapsedTime();
        context.logEvent("CHECKPOINT", currentTestName + " [" + name + "] at " + elapsed + "ms");
    }

    private String getTestName(Description description) {
        return description.getClassName() + "." + description.getMethodName();
    }

    private String buildFailureMessage(Throwable e, Description description) {
        StringBuilder sb = new StringBuilder();
        sb.append("Test: ").append(getTestName(description)).append("\n");
        sb.append("Error: ").append(e.getClass().getSimpleName());
        sb.append(": ").append(e.getMessage()).append("\n");
        
        // Add relevant port information
        int httpPort = context.getPort("http-server");
        if (httpPort > 0) {
            sb.append("HTTP Port: ").append(httpPort);
            sb.append(" (listening: ").append(context.isPortListening("127.0.0.1", httpPort)).append(")\n");
        }
        
        int smtpPort = context.getPort("smtp-server");
        if (smtpPort > 0) {
            sb.append("SMTP Port: ").append(smtpPort);
            sb.append(" (listening: ").append(context.isPortListening("127.0.0.1", smtpPort)).append(")\n");
        }
        
        // Add system info
        sb.append("Free Memory: ").append(Runtime.getRuntime().freeMemory() / 1024 / 1024).append("MB\n");
        sb.append("Active Threads: ").append(Thread.activeCount()).append("\n");
        
        return sb.toString();
    }
}

