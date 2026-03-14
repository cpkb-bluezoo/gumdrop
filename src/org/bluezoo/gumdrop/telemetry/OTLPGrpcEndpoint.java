/*
 * OTLPGrpcEndpoint.java
 * Copyright (C) 2026 Chris Burdess
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPRequest;

import org.bluezoo.gumdrop.util.TLSUtils;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents an OTLP gRPC endpoint for telemetry export.
 *
 * <p>Uses gRPC over HTTP/2 with the OTLP collector service paths.
 * Sends ExportTraceServiceRequest, ExportLogsServiceRequest, and
 * ExportMetricsServiceRequest messages. The protobuf payload is identical
 * to OTLP/HTTP (TracesData, LogsData, MetricsData); only the transport
 * framing differs (gRPC 5-byte prefix, Content-Type: application/grpc).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class OTLPGrpcEndpoint {

    private static final String CONTENT_TYPE_GRPC = "application/grpc";
    private static final int DEFAULT_GRPC_PORT = 4317;

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");
    private static final Logger logger = Logger.getLogger(OTLPGrpcEndpoint.class.getName());

    private final String name;
    private final String host;
    private final int port;
    private final String path;
    private final boolean secure;
    private final Map<String, String> headers;

    private Path truststoreFile;
    private String truststorePass;
    private String truststoreFormat = "PKCS12";
    private volatile SSLContext sslContext;

    private HTTPClient client;
    private volatile boolean connecting;
    private volatile boolean connected;
    private volatile CountDownLatch pendingConnectLatch;

    /**
     * Creates an OTLP gRPC endpoint from a URL string.
     *
     * @param name the endpoint name (traces, logs, metrics)
     * @param url the endpoint URL (e.g. https://localhost:4317)
     * @param grpcPath the gRPC service path (e.g. /opentelemetry.proto.collector.trace.v1.TraceService/Export)
     * @param headers custom headers
     * @param config the telemetry configuration
     * @return the endpoint, or null if the URL is invalid
     */
    static OTLPGrpcEndpoint create(String name, String url, String grpcPath,
                                    Map<String, String> headers, TelemetryConfig config) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                logger.warning(MessageFormat.format(L10N.getString("warn.invalid_endpoint_url"), name, url));
                return null;
            }

            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            int port = uri.getPort();
            if (port <= 0) {
                port = DEFAULT_GRPC_PORT;
            }

            OTLPGrpcEndpoint endpoint = new OTLPGrpcEndpoint(name, host, port, grpcPath, secure, headers);

            if (config != null) {
                endpoint.truststoreFile = config.getTruststoreFile();
                endpoint.truststorePass = config.getTruststorePass();
                endpoint.truststoreFormat = config.getTruststoreFormat();
            }

            return endpoint;

        } catch (IllegalArgumentException e) {
            logger.warning(MessageFormat.format(L10N.getString("warn.invalid_endpoint_url"), name, url + " - " + e.getMessage()));
            return null;
        }
    }

    private OTLPGrpcEndpoint(String name, String host, int port, String path, boolean secure,
                            Map<String, String> headers) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.path = path;
        this.secure = secure;
        this.headers = headers;
    }

    String getName() {
        return name;
    }

    String getHost() {
        return host;
    }

    int getPort() {
        return port;
    }

    String getPath() {
        return path;
    }

    boolean isSecure() {
        return secure;
    }

    private SSLContext getOrCreateSSLContext() {
        SSLContext ctx = sslContext;
        if (ctx != null) {
            return ctx;
        }
        synchronized (this) {
            ctx = sslContext;
            if (ctx != null) {
                return ctx;
            }
            if (truststoreFile != null && truststorePass != null) {
                try {
                    sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null,
                            TLSUtils.loadTrustManagers(truststoreFile, truststorePass, truststoreFormat),
                            null);
                    logger.fine("Loaded truststore for OTLP gRPC " + name + " endpoint: " + truststoreFile);
                    return sslContext;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to load truststore for OTLP gRPC " + name + " endpoint", e);
                }
            }
        }
        return null;
    }

    boolean isConnected() {
        return connected && client != null && client.isOpen();
    }

    boolean isConnecting() {
        return connecting;
    }

    boolean connectAndWait(long timeoutMs) {
        if (isConnected()) {
            return true;
        }

        CountDownLatch connectLatch = new CountDownLatch(1);
        pendingConnectLatch = connectLatch;
        try {
            getClient(connectLatch);
            try {
                if (connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    return connected && client != null && client.isOpen();
                }
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } finally {
            pendingConnectLatch = null;
        }
    }

    HTTPClient getClient() {
        return getClient(null);
    }

    HTTPClient getClient(CountDownLatch connectLatch) {
        if (connected && client != null && client.isOpen()) {
            if (connectLatch != null) {
                connectLatch.countDown();
            }
            return client;
        }

        if (connecting) {
            return null;
        }

        try {
            connecting = true;
            connected = false;

            client = new HTTPClient(host, port);
            if (secure) {
                client.setSecure(true);
                SSLContext ctx = getOrCreateSSLContext();
                if (ctx != null) {
                    client.setSSLContext(ctx);
                }
            }

            client.connect(new OTLPGrpcConnectionHandler(connectLatch));

            logger.info(MessageFormat.format(L10N.getString("info.endpoint_connecting"), name, host, port));

            return null;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create OTLP gRPC " + name + " connection", e);
            connecting = false;
            if (connectLatch != null) {
                connectLatch.countDown();
            }
            return null;
        }
    }

    private class OTLPGrpcConnectionHandler implements HTTPClientHandler {

        private final CountDownLatch connectLatch;

        OTLPGrpcConnectionHandler(CountDownLatch connectLatch) {
            this.connectLatch = connectLatch;
        }

        @Override
        public void onConnected(Endpoint endpoint) {
            connecting = false;
            connected = true;
            if (connectLatch != null) {
                connectLatch.countDown();
            } else if (pendingConnectLatch != null) {
                pendingConnectLatch.countDown();
            }
            logger.info(MessageFormat.format(L10N.getString("info.endpoint_connected"), name, host, port));
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
            logger.fine("TLS established with OTLP gRPC " + name + " endpoint");
        }

        @Override
        public void onError(Exception cause) {
            connecting = false;
            connected = false;
            logger.log(Level.WARNING, "OTLP gRPC " + name + " connection error", cause);
        }

        @Override
        public void onDisconnected() {
            connecting = false;
            connected = false;
            logger.info(L10N.getString("info.endpoint_disconnected"));
        }
    }

    /**
     * Sends gRPC-framed telemetry data to this endpoint.
     *
     * @param data the protobuf-encoded telemetry data (ExportTraceServiceRequest etc.)
     * @param handler the response handler
     */
    void send(ByteBuffer data, OTLPGrpcResponseHandler handler) {
        HTTPClient httpClient = getClient();
        if (httpClient == null) {
            handler.failed(new IOException("No connection to " + name + " endpoint"));
            return;
        }

        ByteBuffer framed = GrpcFraming.frame(data);

        HTTPRequest request = httpClient.post(path);
        request.header("Content-Type", CONTENT_TYPE_GRPC);
        request.header("Te", "trailers");

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.header(entry.getKey(), entry.getValue());
            }
        }

        request.startRequestBody(handler);
        request.requestBodyContent(framed);
        request.endRequestBody();

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest("Sent " + framed.remaining() + " bytes (gRPC framed) to OTLP " + name + " endpoint");
        }
    }

    void close() {
        connected = false;
        connecting = false;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                logger.log(Level.FINE, "Error closing OTLP gRPC " + name + " connection", e);
            }
            client = null;
        }
    }

    @Override
    public String toString() {
        return name + " gRPC endpoint: " + (secure ? "https://" : "http://") + host + ":" + port + path;
    }
}
