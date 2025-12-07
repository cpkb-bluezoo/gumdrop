/*
 * MockOTLPCollector.java
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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.http.HTTPConnection;
import org.bluezoo.gumdrop.http.HTTPServer;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.Stream;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * A mock OpenTelemetry Collector for integration testing.
 * 
 * <p>This class implements a minimal OTLP/HTTP endpoint that receives
 * telemetry data (traces, logs, metrics) and stores the raw requests 
 * for verification in tests. It runs as a Gumdrop HTTPServer subclass.
 * 
 * <p>Usage:
 * <pre>
 * MockOTLPCollector collector = new MockOTLPCollector(14318);
 * collector.start();
 * 
 * // ... send telemetry ...
 * 
 * // Verify received data
 * assertTrue(collector.getTraceRequestCount() > 0);
 * 
 * collector.stop();
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MockOTLPCollector {

    private static final Logger LOGGER = Logger.getLogger(MockOTLPCollector.class.getName());

    private final int port;
    private OTLPCollectorServer server;
    private Gumdrop gumdrop;

    // Raw request data storage
    private final List<byte[]> rawTraceRequests;
    private final List<byte[]> rawLogRequests;
    private final List<byte[]> rawMetricRequests;

    /**
     * Creates a mock OTLP collector on the specified port.
     *
     * @param port the port to listen on
     */
    public MockOTLPCollector(int port) {
        this.port = port;
        this.rawTraceRequests = new CopyOnWriteArrayList<byte[]>();
        this.rawLogRequests = new CopyOnWriteArrayList<byte[]>();
        this.rawMetricRequests = new CopyOnWriteArrayList<byte[]>();
    }

    /**
     * Starts the mock collector.
     */
    public void start() throws Exception {
        server = new OTLPCollectorServer(this);
        server.setPort(port);
        server.setAddresses("127.0.0.1");

        Collection<Server> servers = Collections.singletonList((Server) server);
        gumdrop = new Gumdrop(servers, 2);
        gumdrop.start();

        // Wait for server to be ready
        waitForReady();
    }

    /**
     * Stops the mock collector.
     */
    public void stop() throws Exception {
        if (gumdrop != null) {
            gumdrop.shutdown();
            gumdrop.join();
            gumdrop = null;
        }
        server = null;
        
        // Allow time for port release
        Thread.sleep(500);
    }

    /**
     * Clears all received data.
     */
    public void clear() {
        rawTraceRequests.clear();
        rawLogRequests.clear();
        rawMetricRequests.clear();
    }

    /**
     * Returns the port this collector is listening on.
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the endpoint URL for traces.
     */
    public String getTracesEndpoint() {
        return "http://127.0.0.1:" + port + "/v1/traces";
    }

    /**
     * Returns the endpoint URL for logs.
     */
    public String getLogsEndpoint() {
        return "http://127.0.0.1:" + port + "/v1/logs";
    }

    /**
     * Returns the endpoint URL for metrics.
     */
    public String getMetricsEndpoint() {
        return "http://127.0.0.1:" + port + "/v1/metrics";
    }

    /**
     * Returns the count of trace requests received.
     */
    public int getTraceRequestCount() {
        return rawTraceRequests.size();
    }

    /**
     * Returns the count of log requests received.
     */
    public int getLogRequestCount() {
        return rawLogRequests.size();
    }

    /**
     * Returns the count of metric requests received.
     */
    public int getMetricRequestCount() {
        return rawMetricRequests.size();
    }

    /**
     * Returns the raw protobuf data for trace requests.
     */
    public List<byte[]> getRawTraceRequests() {
        return new ArrayList<byte[]>(rawTraceRequests);
    }

    /**
     * Returns the raw protobuf data for log requests.
     */
    public List<byte[]> getRawLogRequests() {
        return new ArrayList<byte[]>(rawLogRequests);
    }

    /**
     * Returns the raw protobuf data for metric requests.
     */
    public List<byte[]> getRawMetricRequests() {
        return new ArrayList<byte[]>(rawMetricRequests);
    }

    /**
     * Returns the total size of all received trace data in bytes.
     */
    public long getTotalTraceBytesReceived() {
        long total = 0;
        for (byte[] data : rawTraceRequests) {
            total += data.length;
        }
        return total;
    }

    /**
     * Returns the total size of all received log data in bytes.
     */
    public long getTotalLogBytesReceived() {
        long total = 0;
        for (byte[] data : rawLogRequests) {
            total += data.length;
        }
        return total;
    }

    /**
     * Returns the total size of all received metric data in bytes.
     */
    public long getTotalMetricBytesReceived() {
        long total = 0;
        for (byte[] data : rawMetricRequests) {
            total += data.length;
        }
        return total;
    }

    void addTraceRequest(byte[] data) {
        rawTraceRequests.add(data);
    }

    void addLogRequest(byte[] data) {
        rawLogRequests.add(data);
    }

    void addMetricRequest(byte[] data) {
        rawMetricRequests.add(data);
    }

    private void waitForReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (isPortListening("127.0.0.1", port)) {
                Thread.sleep(200); // Extra delay for server stabilization
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Mock OTLP Collector failed to start on port " + port);
    }

    private boolean isPortListening(String host, int p) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, p), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ========================================================================
    // HTTP Server Implementation
    // ========================================================================

    /**
     * Custom HTTP server that handles OTLP requests.
     */
    static class OTLPCollectorServer extends HTTPServer {

        private final MockOTLPCollector collector;

        OTLPCollectorServer(MockOTLPCollector collector) {
            this.collector = collector;
        }

        @Override
        public Connection newConnection(SocketChannel channel, SSLEngine engine) {
            return new OTLPCollectorConnection(channel, engine, isSecure(), collector);
        }
    }

    /**
     * Custom HTTP connection that creates OTLP streams.
     */
    static class OTLPCollectorConnection extends HTTPConnection {

        private final MockOTLPCollector collector;

        OTLPCollectorConnection(SocketChannel channel, SSLEngine engine, boolean secure, 
                               MockOTLPCollector collector) {
            super(channel, engine, secure, 0);
            this.collector = collector;
        }

        @Override
        protected Stream newStream(HTTPConnection connection, int streamId) {
            return new OTLPCollectorStream(connection, streamId, collector);
        }
    }

    /**
     * Stream that handles individual OTLP requests.
     * 
     * <p>This stream accepts POST requests to /v1/traces, /v1/logs, and /v1/metrics,
     * stores the raw request body, and returns 200 OK.
     */
    static class OTLPCollectorStream extends Stream {

        private final MockOTLPCollector collector;
        private ByteArrayOutputStream bodyBuffer;
        private String currentPath;
        private boolean expectingBody;

        OTLPCollectorStream(HTTPConnection connection, int streamId, MockOTLPCollector collector) {
            super(connection, streamId);
            this.collector = collector;
            this.bodyBuffer = new ByteArrayOutputStream();
        }

        @Override
        protected void endHeaders(Headers headers) {
            // Get path
            String path = headers.getValue(":path");
            
            this.currentPath = path;
            this.expectingBody = false;
            
            // Get content-length to know if we're expecting a body
            String contentLengthStr = headers.getValue("content-length");
            long contentLength = 0;
            if (contentLengthStr != null) {
                try {
                    contentLength = Long.parseLong(contentLengthStr);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
            
            // Check if this is an OTLP path
            boolean isOtlpPath = path != null && 
                (path.equals("/v1/traces") || path.equals("/v1/logs") || path.equals("/v1/metrics"));
            
            if (isOtlpPath) {
                // We expect a body
                this.expectingBody = true;
                if (contentLength == 0) {
                    // No body, handle immediately
                    handleRequest(path, new byte[0]);
                }
            } else {
                // Not an OTLP path, return 404
                try {
                    sendError(404);
                } catch (ProtocolException e) {
                    LOGGER.log(Level.WARNING, "Error sending 404 response", e);
                }
            }
        }

        @Override
        protected void receiveRequestBody(byte[] buf) {
            if (expectingBody) {
                try {
                    bodyBuffer.write(buf);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error buffering request body", e);
                }
            }
        }

        @Override
        protected void endRequest() {
            if (expectingBody && currentPath != null) {
                byte[] body = bodyBuffer.toByteArray();
                handleRequest(currentPath, body);
            }
        }

        private void handleRequest(String path, byte[] body) {
            try {
                if ("/v1/traces".equals(path)) {
                    if (body.length > 0) {
                        collector.addTraceRequest(body);
                    }
                    sendSuccess();
                } else if ("/v1/logs".equals(path)) {
                    if (body.length > 0) {
                        collector.addLogRequest(body);
                    }
                    sendSuccess();
                } else if ("/v1/metrics".equals(path)) {
                    if (body.length > 0) {
                        collector.addMetricRequest(body);
                    }
                    sendSuccess();
                } else {
                    sendError(404);
                }
            } catch (ProtocolException e) {
                LOGGER.log(Level.WARNING, "Error handling OTLP request", e);
            }
        }

        private void sendSuccess() throws ProtocolException {
            Headers responseHeaders = new Headers();
            responseHeaders.add(new Header("Content-Type", "application/x-protobuf"));
            responseHeaders.add(new Header("Content-Length", "0"));
            sendResponseHeaders(200, responseHeaders, true);
        }
    }
}
