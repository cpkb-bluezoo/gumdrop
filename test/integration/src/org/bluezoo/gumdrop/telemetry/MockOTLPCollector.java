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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPServer;
import org.bluezoo.gumdrop.http.HTTPStatus;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

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
        LOGGER.info("Starting MockOTLPCollector on port " + port);
        
        server = new OTLPCollectorServer(this);
        server.setPort(port);
        server.setAddresses("127.0.0.1");

        System.setProperty("gumdrop.workers", "2");
        gumdrop = Gumdrop.getInstance();
        gumdrop.addServer(server);
        gumdrop.start();

        // Wait for server to be ready
        waitForReady();
        LOGGER.info("MockOTLPCollector started and ready on port " + port);
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

        OTLPCollectorServer(MockOTLPCollector collector) {
            setHandlerFactory(new OTLPHandlerFactory(collector));
        }
    }

    /**
     * Factory that creates OTLP request handlers.
     */
    static class OTLPHandlerFactory implements HTTPRequestHandlerFactory {

        private final MockOTLPCollector collector;

        OTLPHandlerFactory(MockOTLPCollector collector) {
            this.collector = collector;
        }

        @Override
        public HTTPRequestHandler createHandler(Headers headers, HTTPResponseState state) {
            return new OTLPRequestHandler(collector);
        }
    }

    /**
     * Handler that processes individual OTLP requests.
     * 
     * <p>This handler accepts POST requests to /v1/traces, /v1/logs, and /v1/metrics,
     * stores the raw request body, and returns 200 OK.
     */
    static class OTLPRequestHandler extends DefaultHTTPRequestHandler {

        private final MockOTLPCollector collector;
        private ByteArrayOutputStream bodyBuffer;
        private String currentPath;
        private boolean expectingBody;
        private HTTPResponseState state;

        OTLPRequestHandler(MockOTLPCollector collector) {
            this.collector = collector;
            this.bodyBuffer = new ByteArrayOutputStream();
        }

        @Override
        public void headers(Headers headers, HTTPResponseState state) {
            this.state = state;
            
            // Get path
            String path = headers.getPath();
            this.currentPath = path;
            this.expectingBody = false;
            
            LOGGER.info("MockOTLPCollector received request: " + headers.getMethod() + " " + path);
            
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
            
            // Check for chunked encoding
            String transferEncoding = headers.getValue("transfer-encoding");
            boolean isChunked = transferEncoding != null && 
                transferEncoding.toLowerCase().contains("chunked");
            
            LOGGER.info("  Content-Length: " + contentLength + ", Transfer-Encoding: " + transferEncoding);
            
            // Check if this is an OTLP path
            boolean isOtlpPath = path != null && 
                (path.equals("/v1/traces") || path.equals("/v1/logs") || path.equals("/v1/metrics"));
            
            if (isOtlpPath) {
                if (contentLength > 0 || isChunked) {
                    // We expect a body
                    this.expectingBody = true;
                    LOGGER.info("  Expecting body");
                } else {
                    // No body, handle immediately
                    this.expectingBody = false;
                    LOGGER.info("  No body expected, handling immediately");
                    handleRequest(path, new byte[0]);
                }
            } else {
                // Not an OTLP path, return 404
                LOGGER.info("  Not an OTLP path, returning 404");
                sendError(404);
            }
        }

        @Override
        public void requestBodyContent(ByteBuffer data, HTTPResponseState state) {
            if (expectingBody) {
                try {
                    int remaining = data.remaining();
                    byte[] buf = new byte[remaining];
                    data.get(buf);
                    bodyBuffer.write(buf);
                    LOGGER.info("  Received body chunk: " + remaining + " bytes (total: " + bodyBuffer.size() + ")");
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error buffering request body", e);
                }
            }
        }

        @Override
        public void endRequestBody(HTTPResponseState state) {
            LOGGER.info("  endRequestBody called, expectingBody=" + expectingBody + ", path=" + currentPath);
            if (expectingBody && currentPath != null) {
                byte[] body = bodyBuffer.toByteArray();
                LOGGER.info("  Total body size: " + body.length + " bytes");
                handleRequest(currentPath, body);
            }
        }

        private void handleRequest(String path, byte[] body) {
            LOGGER.info("  handleRequest: path=" + path + ", bodySize=" + body.length);
            if ("/v1/traces".equals(path)) {
                if (body.length > 0) {
                    collector.addTraceRequest(body);
                    LOGGER.info("  Added trace request, total count: " + collector.getTraceRequestCount());
                }
                sendSuccess();
            } else if ("/v1/logs".equals(path)) {
                if (body.length > 0) {
                    collector.addLogRequest(body);
                    LOGGER.info("  Added log request, total count: " + collector.getLogRequestCount());
                }
                sendSuccess();
            } else if ("/v1/metrics".equals(path)) {
                if (body.length > 0) {
                    collector.addMetricRequest(body);
                    LOGGER.info("  Added metric request, total count: " + collector.getMetricRequestCount());
                }
                sendSuccess();
            } else {
                sendError(404);
            }
        }

        private void sendSuccess() {
            LOGGER.info("  Sending 200 OK response");
            Headers responseHeaders = new Headers();
            responseHeaders.status(HTTPStatus.OK);
            responseHeaders.add(new Header("Content-Type", "application/x-protobuf"));
            responseHeaders.add(new Header("Content-Length", "0"));
            state.headers(responseHeaders);
            state.complete();
        }

        private void sendError(int statusCode) {
            Headers responseHeaders = new Headers();
            responseHeaders.status(HTTPStatus.fromCode(statusCode));
            responseHeaders.add(new Header("Content-Length", "0"));
            state.headers(responseHeaders);
            state.complete();
        }
    }
}
