/*
 * OTLPEndpoint.java
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

import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.TLSInfo;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPRequest;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents an OTLP HTTP endpoint for telemetry export.
 *
 * <p>Encapsulates the endpoint URL configuration and manages the HTTP client
 * connection for sending telemetry data. Handles connection lifecycle including
 * reconnection on failure.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class OTLPEndpoint {

    private static final Logger logger = Logger.getLogger(OTLPEndpoint.class.getName());

    private final String name;
    private final String host;
    private final int port;
    private final String path;
    private final boolean secure;
    private final Map<String, String> headers;
    
    // TLS configuration
    private String truststoreFile;
    private String truststorePass;
    private String truststoreFormat = "PKCS12";
    private SSLContext sslContext;

    private HTTPClient client;
    private volatile boolean connecting;
    private volatile boolean connected;

    /**
     * Creates an OTLP endpoint from a URL string.
     *
     * @param name the endpoint name (traces, logs, metrics)
     * @param url the endpoint URL
     * @param defaultPath the default path if not specified in URL
     * @param headers custom headers to include in requests
     * @param config the telemetry configuration (for TLS settings)
     * @return the endpoint, or null if the URL is invalid
     */
    static OTLPEndpoint create(String name, String url, String defaultPath, 
                               Map<String, String> headers, TelemetryConfig config) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                logger.warning("Invalid OTLP " + name + " endpoint URL: " + url);
                return null;
            }

            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            int port = uri.getPort();
            if (port <= 0) {
                port = secure ? 443 : 80;
            }

            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = defaultPath;
            }

            OTLPEndpoint endpoint = new OTLPEndpoint(name, host, port, path, secure, headers);
            
            // Copy TLS settings from config
            if (config != null) {
                endpoint.truststoreFile = config.getTruststoreFile();
                endpoint.truststorePass = config.getTruststorePass();
                endpoint.truststoreFormat = config.getTruststoreFormat();
            }
            
            return endpoint;

        } catch (IllegalArgumentException e) {
            logger.warning("Invalid OTLP " + name + " endpoint URL: " + url + " - " + e.getMessage());
            return null;
        }
    }

    private OTLPEndpoint(String name, String host, int port, String path, boolean secure,
                         Map<String, String> headers) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.path = path;
        this.secure = secure;
        this.headers = headers;
    }

    /**
     * Returns the endpoint name.
     *
     * @return the name (traces, logs, or metrics)
     */
    String getName() {
        return name;
    }

    /**
     * Returns the host.
     *
     * @return the host
     */
    String getHost() {
        return host;
    }

    /**
     * Returns the port.
     *
     * @return the port
     */
    int getPort() {
        return port;
    }

    /**
     * Returns the path.
     *
     * @return the path
     */
    String getPath() {
        return path;
    }

    /**
     * Returns whether this endpoint uses TLS.
     *
     * @return true if secure
     */
    boolean isSecure() {
        return secure;
    }

    /**
     * Gets or creates an SSLContext for secure connections.
     *
     * <p>If a truststore is configured, creates an SSLContext that trusts
     * certificates from that truststore. Otherwise returns null to use
     * the JVM's default trust settings.
     *
     * @return the SSLContext, or null to use defaults
     */
    private SSLContext getOrCreateSSLContext() {
        // If already created, return it
        if (sslContext != null) {
            return sslContext;
        }
        
        // Create from truststore config if available
        if (truststoreFile != null && truststorePass != null) {
            try {
                KeyStore trustStore = KeyStore.getInstance(truststoreFormat);
                try (FileInputStream fis = new FileInputStream(truststoreFile)) {
                    trustStore.load(fis, truststorePass.toCharArray());
                }
                
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, tmf.getTrustManagers(), null);
                
                logger.fine("Loaded truststore for " + name + " endpoint: " + truststoreFile);
                return sslContext;
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load truststore for " + name + " endpoint", e);
            }
        }
        
        // Use JVM defaults
        return null;
    }

    /**
     * Returns whether this endpoint has an active, open connection.
     *
     * @return true if connected
     */
    boolean isConnected() {
        return connected && client != null && client.isOpen();
    }

    /**
     * Returns whether a connection attempt is in progress.
     *
     * @return true if connecting
     */
    boolean isConnecting() {
        return connecting;
    }

    /**
     * Initiates a connection and waits for it to complete.
     *
     * <p>This method blocks until the connection is established or the timeout
     * expires. Use this for scenarios where you need to ensure a connection
     * is ready before sending data.
     *
     * @param timeoutMs the maximum time to wait in milliseconds
     * @return true if connected, false if timed out or failed
     */
    boolean connectAndWait(long timeoutMs) {
        // If already connected, return immediately
        if (isConnected()) {
            return true;
        }

        // Initiate connection if not already connecting
        getClient();

        // Wait for connection to complete
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (connected && client != null && client.isOpen()) {
                return true;
            }
            if (!connecting && !connected) {
                // Connection failed
                return false;
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
     * Returns the HTTP client for this endpoint.
     *
     * <p>Creates a new connection if not connected. This method should be called
     * from the export thread to ensure proper connection lifecycle management.
     *
     * @return the HTTP client, or null if connection failed or not yet connected
     */
    HTTPClient getClient() {
        if (connected && client != null && client.isOpen()) {
            return client;
        }

        // Need to create a new connection
        if (connecting) {
            return null; // Connection attempt already in progress
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

            // Initiate connection with handler
            client.connect(new OTLPConnectionHandler());

            logger.info("Connecting to OTLP " + name + " endpoint " + host + ":" + port);

            // Return null here - the connection is asynchronous
            // The next call to getClient() will return the client once connected
            return null;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create OTLP " + name + " connection", e);
            connecting = false;
            return null;
        }
    }

    /**
     * Handler for OTLP connection lifecycle events.
     */
    private class OTLPConnectionHandler implements HTTPClientHandler {

        @Override
        public void onConnected(ConnectionInfo info) {
            connecting = false;
            connected = true;
            logger.info("Connected to OTLP " + name + " endpoint " + host + ":" + port);
        }

        @Override
        public void onTLSStarted(TLSInfo info) {
            // TLS handshake complete - connection is now secure
            logger.fine("TLS established with OTLP " + name + " endpoint");
        }

        @Override
        public void onError(Exception cause) {
            connecting = false;
            connected = false;
            logger.log(Level.WARNING, "OTLP " + name + " connection error", cause);
        }

        @Override
        public void onDisconnected() {
            connecting = false;
            connected = false;
            logger.info("Disconnected from OTLP " + name + " endpoint");
        }
    }

    /**
     * Sends telemetry data to this endpoint using buffered mode.
     *
     * @param data the protobuf-encoded telemetry data
     * @param handler the response handler
     */
    void send(ByteBuffer data, OTLPResponseHandler handler) {
        HTTPClient httpClient = getClient();
        if (httpClient == null) {
            handler.failed(new IOException("No connection to " + name + " endpoint"));
            return;
        }

        HTTPRequest request = httpClient.post(path);

        // Set standard headers
        request.header("Content-Type", "application/x-protobuf");
        request.header("Content-Length", String.valueOf(data.remaining()));

        // Set custom headers from config
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.header(entry.getKey(), entry.getValue());
            }
        }

        // Send with body
        request.startRequestBody(handler);
        request.requestBodyContent(data);
        request.endRequestBody();

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest("Sent " + data.limit() + " bytes to OTLP " + name + " endpoint");
        }
    }

    /**
     * Opens a streaming channel to this endpoint.
     *
     * <p>The returned channel can be passed directly to a serializer for
     * true streaming output. When done, close the channel to complete the request.
     *
     * @param handler the response handler
     * @return the channel, or null if connection failed
     */
    HTTPRequestChannel openStream(OTLPResponseHandler handler) {
        HTTPClient httpClient = getClient();
        if (httpClient == null) {
            handler.failed(new IOException("No connection to " + name + " endpoint"));
            return null;
        }

        HTTPRequest request = httpClient.post(path);

        // Set standard headers - use chunked encoding for streaming
        request.header("Content-Type", "application/x-protobuf");
        request.header("Transfer-Encoding", "chunked");

        // Set custom headers from config
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.header(entry.getKey(), entry.getValue());
            }
        }

        // Start the request body
        request.startRequestBody(handler);

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest("Opened streaming channel to OTLP " + name + " endpoint");
        }

        return new HTTPRequestChannel(request);
    }

    /**
     * Closes the connection to this endpoint.
     */
    void close() {
        connected = false;
        connecting = false;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                logger.log(Level.FINE, "Error closing OTLP " + name + " connection", e);
            }
            client = null;
        }
    }

    @Override
    public String toString() {
        return name + " endpoint: " + (secure ? "https://" : "http://") + host + ":" + port + path;
    }
}
