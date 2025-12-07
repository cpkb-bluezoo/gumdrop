/*
 * TelemetryIntegrationTest.java
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

package org.bluezoo.gumdrop.telemetry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.http.HTTPServer;
import org.bluezoo.gumdrop.smtp.SMTPServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Integration tests for the complete telemetry system using real servers.
 * 
 * <p>These tests verify end-to-end telemetry collection by:
 * <ol>
 *   <li>Starting a mock OTLP collector</li>
 *   <li>Starting HTTP and SMTP servers with telemetry enabled</li>
 *   <li>Making client requests to those servers</li>
 *   <li>Verifying telemetry was received by the collector</li>
 * </ol>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TelemetryIntegrationTest {

    private static final int COLLECTOR_PORT = 24318;
    private static final int HTTP_PORT = 28080;
    private static final int SMTP_PORT = 28025;
    private static final String SERVICE_NAME = "gumdrop-test";
    private static final String SERVICE_VERSION = "1.0.0-test";

    private MockOTLPCollector collector;
    private TelemetryConfig telemetryConfig;
    private OTLPExporter exporter;
    private Gumdrop gumdrop;
    private HTTPServer httpServer;
    private SMTPServer smtpServer;

    private Logger rootLogger;
    private Level originalLogLevel;

    @Before
    public void setUp() throws Exception {
        // Reduce logging noise during tests
        rootLogger = Logger.getLogger("");
        originalLogLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.WARNING);

        // Start the mock OTLP collector first
        collector = new MockOTLPCollector(COLLECTOR_PORT);
        collector.start();

        // Configure telemetry to send to our mock collector
        telemetryConfig = new TelemetryConfig();
        telemetryConfig.setServiceName(SERVICE_NAME);
        telemetryConfig.setServiceVersion(SERVICE_VERSION);
        telemetryConfig.setTracesEndpoint(collector.getTracesEndpoint());
        telemetryConfig.setLogsEndpoint(collector.getLogsEndpoint());
        telemetryConfig.setMetricsEndpoint(collector.getMetricsEndpoint());
        telemetryConfig.setFlushIntervalMs(100); // Fast flush for testing
        telemetryConfig.setBatchSize(1); // Send immediately
        telemetryConfig.setTimeoutMs(5000);

        // Initialize the config - this automatically creates the exporter
        // (In production, this is called by ComponentRegistry after setting properties)
        telemetryConfig.init();
        exporter = (OTLPExporter) telemetryConfig.getExporter();

        // Create HTTP server with telemetry enabled
        httpServer = new HTTPServer();
        httpServer.setPort(HTTP_PORT);
        httpServer.setAddresses("127.0.0.1");
        httpServer.setTelemetryConfig(telemetryConfig);

        // Create SMTP server with telemetry enabled
        smtpServer = new SMTPServer();
        smtpServer.setPort(SMTP_PORT);
        smtpServer.setAddresses("127.0.0.1");
        smtpServer.setTelemetryConfig(telemetryConfig);

        // Start both servers
        Collection<Server> servers = new ArrayList<Server>();
        servers.add(httpServer);
        servers.add(smtpServer);
        gumdrop = new Gumdrop(servers, 2);
        gumdrop.start();

        // Wait for servers to be ready
        waitForPort(HTTP_PORT);
        waitForPort(SMTP_PORT);
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (gumdrop != null) {
                gumdrop.shutdown();
                gumdrop.join();
            }
            if (exporter != null) {
                exporter.shutdown();
            }
            if (collector != null) {
                collector.stop();
            }
            // Allow time for port release (TIME_WAIT state)
            Thread.sleep(2000);
        } finally {
            if (rootLogger != null && originalLogLevel != null) {
                rootLogger.setLevel(originalLogLevel);
            }
        }
    }

    // ========================================================================
    // HTTP Server Telemetry Tests
    // ========================================================================

    @Test
    public void testHttpServerGeneratesTelemetry() throws Exception {
        // Make an HTTP request
        String response = sendHttpRequest("GET", "/", null);
        
        // Verify we got the 404 response (default behavior)
        assertTrue("Should receive HTTP response", response.contains("HTTP/1.1"));
        assertTrue("Should receive 404 status", response.contains("404"));

        // Wait for telemetry to be exported
        waitForTelemetry();

        // Verify trace was received
        assertTrue("Should receive trace from HTTP request", 
                   collector.getTraceRequestCount() > 0);
        assertTrue("Should receive non-empty trace data",
                   collector.getTotalTraceBytesReceived() > 0);
    }

    @Test
    public void testHttpServerMultipleRequests() throws Exception {
        collector.clear();

        // Make multiple HTTP requests
        for (int i = 0; i < 5; i++) {
            String response = sendHttpRequest("GET", "/test/" + i, null);
            assertTrue("Request " + i + " should succeed", response.contains("HTTP/1.1"));
        }

        waitForTelemetry();

        // Verify traces were received
        assertTrue("Should receive traces from HTTP requests", 
                   collector.getTraceRequestCount() > 0);
    }

    @Test
    public void testHttpServerPostRequest() throws Exception {
        collector.clear();

        // Make a POST request with body
        String body = "test=data&key=value";
        String response = sendHttpRequest("POST", "/submit", body);
        
        assertTrue("Should receive HTTP response", response.contains("HTTP/1.1"));

        waitForTelemetry();

        assertTrue("Should receive trace from POST request", 
                   collector.getTraceRequestCount() > 0);
    }

    @Test
    public void testHttpTraceparentPropagation() throws Exception {
        collector.clear();

        // Make an HTTP request with traceparent header to simulate distributed trace
        String upstreamTraceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        String response = sendHttpRequestWithTraceparent("GET", "/api/test", null, upstreamTraceparent);
        
        assertTrue("Should receive HTTP response", response.contains("HTTP/1.1"));
        
        // Check that response includes traceparent header (trace continuation)
        assertTrue("Response should include traceparent", 
                   response.toLowerCase().contains("traceparent"));

        waitForTelemetry();

        assertTrue("Should receive trace", collector.getTraceRequestCount() > 0);
    }

    // ========================================================================
    // SMTP Server Telemetry Tests
    // ========================================================================

    @Test
    public void testSmtpServerGeneratesTelemetry() throws Exception {
        collector.clear();

        // Make an SMTP session
        SmtpResponse response = doSmtpSession();
        
        // Verify SMTP session worked
        assertTrue("Should get greeting", response.greeting.startsWith("220"));
        assertTrue("Should get EHLO response", response.ehloResponse.startsWith("250"));
        assertTrue("Should get QUIT response", response.quitResponse.startsWith("221"));

        waitForTelemetry();

        // Verify trace was received
        assertTrue("Should receive trace from SMTP session", 
                   collector.getTraceRequestCount() > 0);
        assertTrue("Should receive non-empty trace data",
                   collector.getTotalTraceBytesReceived() > 0);
    }

    @Test
    public void testSmtpServerMultipleSessions() throws Exception {
        collector.clear();

        // Make multiple SMTP sessions
        for (int i = 0; i < 3; i++) {
            SmtpResponse response = doSmtpSession();
            assertTrue("Session " + i + " should get greeting", 
                       response.greeting.startsWith("220"));
        }

        waitForTelemetry();

        assertTrue("Should receive traces from SMTP sessions", 
                   collector.getTraceRequestCount() > 0);
    }

    @Test
    public void testSmtpServerWithMailTransaction() throws Exception {
        collector.clear();

        // Do an SMTP session with a full mail transaction (will fail at RCPT but generates telemetry)
        SmtpResponse response = doSmtpMailTransaction();
        
        assertTrue("Should get greeting", response.greeting.startsWith("220"));

        waitForTelemetry();

        assertTrue("Should receive trace from SMTP mail transaction", 
                   collector.getTraceRequestCount() > 0);
    }

    // ========================================================================
    // Combined Server Tests
    // ========================================================================

    @Test
    public void testMultipleProtocolsTelemetry() throws Exception {
        collector.clear();

        // Make HTTP request
        String httpResponse = sendHttpRequest("GET", "/test", null);
        assertTrue("HTTP request should succeed", httpResponse.contains("HTTP/1.1"));

        // Make SMTP session
        SmtpResponse smtpResponse = doSmtpSession();
        assertTrue("SMTP session should succeed", smtpResponse.greeting.startsWith("220"));

        waitForTelemetry();

        // Verify traces were received from both protocols
        assertTrue("Should receive traces", collector.getTraceRequestCount() > 0);
    }

    @Test
    public void testConcurrentRequestsTelemetry() throws Exception {
        collector.clear();

        // Make concurrent requests
        final int numThreads = 5;
        Thread[] threads = new Thread[numThreads];
        final boolean[] results = new boolean[numThreads];

        for (int i = 0; i < numThreads; i++) {
            final int index = i;
            threads[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        if (index % 2 == 0) {
                            String response = sendHttpRequest("GET", "/concurrent/" + index, null);
                            results[index] = response.contains("HTTP/1.1");
                        } else {
                            SmtpResponse response = doSmtpSession();
                            results[index] = response.greeting.startsWith("220");
                        }
                    } catch (Exception e) {
                        results[index] = false;
                    }
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join(5000);
        }

        // Verify all requests succeeded
        for (int i = 0; i < numThreads; i++) {
            assertTrue("Request " + i + " should succeed", results[i]);
        }

        waitForTelemetry();

        // Verify telemetry was received
        assertTrue("Should receive traces from concurrent requests", 
                   collector.getTraceRequestCount() > 0);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void waitForPort(int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (isPortListening("127.0.0.1", port)) {
                Thread.sleep(200);
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Port " + port + " not listening");
    }

    private boolean isPortListening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForTelemetry() throws InterruptedException {
        // Wait for async telemetry export
        int maxWait = 3000;
        int waited = 0;
        int interval = 100;

        while (waited < maxWait) {
            Thread.sleep(interval);
            waited += interval;
            
            if (collector.getTraceRequestCount() > 0) {
                Thread.sleep(200); // Extra time for any additional exports
                return;
            }
        }
    }

    private String sendHttpRequest(String method, String path, String body) throws IOException {
        return sendHttpRequestWithTraceparent(method, path, body, null);
    }

    private String sendHttpRequestWithTraceparent(String method, String path, String body, 
                                                   String traceparent) throws IOException {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress("127.0.0.1", HTTP_PORT), 5000);

            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            StringBuilder request = new StringBuilder();
            request.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: localhost\r\n");
            request.append("Connection: close\r\n");
            
            if (traceparent != null) {
                request.append("traceparent: ").append(traceparent).append("\r\n");
            }
            
            if (body != null) {
                request.append("Content-Type: application/x-www-form-urlencoded\r\n");
                request.append("Content-Length: ").append(body.length()).append("\r\n");
            }
            
            request.append("\r\n");
            
            if (body != null) {
                request.append(body);
            }

            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line).append("\r\n");
            }

            return response.toString();
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private SmtpResponse doSmtpSession() throws IOException {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress("127.0.0.1", SMTP_PORT), 5000);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            SmtpResponse response = new SmtpResponse();

            // Read greeting
            response.greeting = readSmtpResponse(in);

            // Send EHLO
            out.write("EHLO localhost\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            response.ehloResponse = readSmtpResponse(in);

            // Send QUIT
            out.write("QUIT\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            response.quitResponse = readSmtpResponse(in);

            return response;
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private SmtpResponse doSmtpMailTransaction() throws IOException {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress("127.0.0.1", SMTP_PORT), 5000);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            SmtpResponse response = new SmtpResponse();

            // Read greeting
            response.greeting = readSmtpResponse(in);

            // Send EHLO
            out.write("EHLO localhost\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            response.ehloResponse = readSmtpResponse(in);

            // Try MAIL FROM (will work)
            out.write("MAIL FROM:<test@example.com>\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            response.mailFromResponse = readSmtpResponse(in);

            // Try RCPT TO (may fail without handler, but generates telemetry)
            out.write("RCPT TO:<recipient@example.com>\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            response.rcptToResponse = readSmtpResponse(in);

            // Reset
            out.write("RSET\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            readSmtpResponse(in);

            // Send QUIT
            out.write("QUIT\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            response.quitResponse = readSmtpResponse(in);

            return response;
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private String readSmtpResponse(BufferedReader in) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            response.append(line).append("\r\n");
            // Check if this is the last line of a multiline response
            if (line.length() >= 4 && line.charAt(3) == ' ') {
                break;
            }
            // Single-line response
            if (line.length() >= 3 && Character.isDigit(line.charAt(0))) {
                if (line.length() == 3 || line.charAt(3) != '-') {
                    break;
                }
            }
        }
        return response.toString();
    }

    /**
     * Container for SMTP response data.
     */
    private static class SmtpResponse {
        String greeting;
        String ehloResponse;
        String mailFromResponse;
        String rcptToResponse;
        String quitResponse;
    }
}
