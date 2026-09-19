/*
 * OtlpEndpoint.java
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

package org.bluezoo.gumdrop.telemetry.otlp;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpRequest;

import org.bluezoo.gumdrop.util.TlsUtils;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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
 * Represents an OTLP HTTP endpoint for telemetry export.
 *
 * <p>Encapsulates the endpoint URL configuration and manages the HTTP client
 * connection for sending telemetry data. Handles connection lifecycle including
 * reconnection on failure.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class OtlpEndpoint {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");
    private static final Logger logger = Logger.getLogger(OtlpEndpoint.class.getName());

    private final Gumdrop gumdrop;
    private final String name;
    private final String host;
    private final int port;
    private final String path;
    private final boolean secure;
    private final Map<String, String> headers;
    
    // TLS configuration
    private Path truststoreFile;
    private String truststorePass;
    private String truststoreFormat = "PKCS12";
    private volatile X509TrustManager trustManager;

    private HttpClient client;
    private volatile boolean connecting;
    private volatile boolean connected;
    private volatile CountDownLatch pendingConnectLatch;

    /**
     * Creates an OTLP endpoint from a URL string.
     *
     * @param gumdrop the runtime used to drive this endpoint's outbound
     *      HTTP client connections
     * @param name the endpoint name (traces, logs, metrics)
     * @param url the endpoint URL
     * @param defaultPath the default path if not specified in URL
     * @param headers custom headers to include in requests
     * @param config the telemetry configuration (for TLS settings)
     * @return the endpoint, or null if the URL is invalid
     */
    static OtlpEndpoint create(Gumdrop gumdrop, String name, String url, String defaultPath,
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
                port = secure ? 443 : 80;
            }

            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = defaultPath;
            }

            OtlpEndpoint endpoint = new OtlpEndpoint(gumdrop, name, host, port, path, secure, headers);
            
            // Copy TLS settings from config
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

    private OtlpEndpoint(Gumdrop gumdrop, String name, String host, int port, String path, boolean secure,
                         Map<String, String> headers) {
        this.gumdrop = gumdrop;
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
     * Gets or creates a trust manager for secure connections.
     *
     * <p>If a truststore is configured, loads a trust manager that trusts
     * certificates from that truststore. Otherwise returns null to use
     * the JVM's default trust settings.
     *
     * @return the trust manager, or null to use defaults
     */
    private X509TrustManager getOrCreateTrustManager() {
        X509TrustManager tm = trustManager;
        if (tm != null) {
            return tm;
        }
        synchronized (this) {
            tm = trustManager;
            if (tm != null) {
                return tm;
            }
            if (truststoreFile != null && truststorePass != null) {
                try {
                    TrustManager[] managers = TlsUtils.loadTrustManagers(truststoreFile, truststorePass, truststoreFormat);
                    for (int i = 0; i < managers.length; i++) {
                        if (managers[i] instanceof X509TrustManager) {
                            trustManager = (X509TrustManager) managers[i];
                            logger.fine(MessageFormat.format(L10N.getString("debug.truststore_loaded"), name, truststoreFile));
                            return trustManager;
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, MessageFormat.format(L10N.getString("warn.truststore_load_failed"), name), e);
                }
            }
        }
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

    /**
     * Returns the HTTP client for this endpoint.
     *
     * <p>Creates a new connection if not connected. This method should be called
     * from the export thread to ensure proper connection lifecycle management.
     *
     * @return the HTTP client, or null if connection failed or not yet connected
     */
    HttpClient getClient() {
        return getClient(null);
    }

    /**
     * Returns the HTTP client for this endpoint, optionally signalling when connected.
     *
     * @param connectLatch if non-null, counted down when connection is established
     * @return the HTTP client, or null if connection failed or not yet connected
     */
    HttpClient getClient(CountDownLatch connectLatch) {
        if (connected && client != null && client.isOpen()) {
            if (connectLatch != null) {
                connectLatch.countDown();
            }
            return client;
        }

        // Need to create a new connection
        if (connecting) {
            return null; // Connection attempt already in progress
        }

        try {
            connecting = true;
            connected = false;

            client = new HttpClient(host, port);
            if (secure) {
                client.setSecure(true);
                X509TrustManager tm = getOrCreateTrustManager();
                if (tm != null) {
                    client.setTrustManager(tm);
                }
            }

            // Initiate connection with handler
            client.connect(gumdrop, new OtlpConnectionHandler(connectLatch));

            logger.info(MessageFormat.format(L10N.getString("info.endpoint_connecting"), name, host, port));

            // Return null here - the connection is asynchronous
            // The next call to getClient() will return the client once connected
            return null;

        } catch (Exception e) {
            logger.log(Level.WARNING, MessageFormat.format(L10N.getString("warn.otlp_connection_create_failed"), name), e);
            connecting = false;
            if (connectLatch != null) {
                connectLatch.countDown();
            }
            return null;
        }
    }

    /**
     * Handler for OTLP connection lifecycle events.
     */
    private class OtlpConnectionHandler implements HttpClientHandler {

        private final CountDownLatch connectLatch;

        OtlpConnectionHandler(CountDownLatch connectLatch) {
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
            // Security handshake complete - connection is now secure
            logger.fine(MessageFormat.format(L10N.getString("debug.otlp_tls_established"), name));
        }

        @Override
        public void onError(Exception cause) {
            connecting = false;
            connected = false;
            logger.log(Level.WARNING, MessageFormat.format(L10N.getString("warn.otlp_connection_error"), name), cause);
        }

        @Override
        public void onDisconnected() {
            connecting = false;
            connected = false;
            logger.info(L10N.getString("info.endpoint_disconnected"));
        }
    }

    /**
     * Sends telemetry data to this endpoint using buffered mode.
     *
     * @param data the protobuf-encoded telemetry data
     * @param handler the response handler
     */
    void send(ByteBuffer data, OtlpResponseHandler handler) {
        HttpClient httpClient = getClient();
        if (httpClient == null) {
            handler.failed(new IOException("No connection to " + name + " endpoint"));
            return;
        }

        HttpRequest request = httpClient.post(path);

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
            logger.finest(MessageFormat.format(L10N.getString("finest.otlp_sent_bytes"), data.limit(), name));
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
    HttpRequestChannel openStream(OtlpResponseHandler handler) {
        HttpClient httpClient = getClient();
        if (httpClient == null) {
            handler.failed(new IOException("No connection to " + name + " endpoint"));
            return null;
        }

        HttpRequest request = httpClient.post(path);

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
            logger.finest(MessageFormat.format(L10N.getString("finest.otlp_streaming_opened"), name));
        }

        return new HttpRequestChannel(request);
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
                logger.log(Level.FINE, MessageFormat.format(L10N.getString("fine.otlp_close_error"), name), e);
            }
            client = null;
        }
    }

    @Override
    public String toString() {
        return name + " endpoint: " + (secure ? "https://" : "http://") + host + ":" + port + path;
    }
}
