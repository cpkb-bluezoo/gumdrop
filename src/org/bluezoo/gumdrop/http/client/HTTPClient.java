/*
 * HTTPClient.java
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

package org.bluezoo.gumdrop.http.client;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.h3.HTTP3ClientHandler;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;

/**
 * Listener interface for Alt-Svc header notifications.
 *
 * <p>Implementations receive the raw Alt-Svc header value when it
 * appears in an HTTP response, enabling protocol upgrade discovery.
 */
interface AltSvcListener {

    /**
     * Called when an Alt-Svc header is received in a response.
     *
     * @param value the raw Alt-Svc header value
     */
    void altSvcReceived(String value);
}

/**
 * High-level HTTP client facade.
 *
 * <p>This class provides a simple, concrete API for making HTTP requests.
 * It internally creates either a {@link TCPTransportFactory} (for
 * HTTP/1.1 and HTTP/2) or a {@link QuicTransportFactory} (for HTTP/3),
 * wiring the appropriate protocol handler and forwarding lifecycle
 * events to the caller's {@link HTTPClientHandler}.
 *
 * <h4>Basic Usage</h4>
 * <pre>{@code
 * HTTPClient client = new HTTPClient("api.example.com", 443);
 * client.setSecure(true);
 * client.connect(new HTTPClientHandler() {
 *     public void onConnected(Endpoint endpoint) {
 *         HTTPRequest req = client.get("/users");
 *         req.send(responseHandler);
 *     }
 *     public void onSecurityEstablished(SecurityInfo info) { }
 *     public void onError(Exception cause) { cause.printStackTrace(); }
 *     public void onDisconnected() { }
 * });
 * }</pre>
 *
 * <h4>With explicit SelectorLoop (server integration)</h4>
 * <pre>{@code
 * HTTPClient client = new HTTPClient(selectorLoop, "api.example.com", 443);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientHandler
 * @see HTTPRequest
 */
public class HTTPClient implements AltSvcListener {

    private static final Logger LOGGER =
            Logger.getLogger(HTTPClient.class.getName());

    private final String host;
    private final int port;
    private final SelectorLoop selectorLoop;
    private InetAddress hostAddress;

    // Configuration (set before connect)
    private boolean secure;
    private SSLContext sslContext;
    private String username;
    private String password;
    private boolean h2Enabled = true;
    private boolean h2cUpgradeEnabled = true;
    private boolean h2WithPriorKnowledge;
    private boolean h3Enabled;
    private boolean altSvcEnabled = true;
    private Path certFile;
    private Path keyFile;
    private boolean verifyPeer = true;

    // Internal transport components (created at connect time)
    private TCPTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private HTTPClientProtocolHandler endpointHandler;

    // HTTP/3 transport components (created at connect time)
    private QuicTransportFactory quicTransportFactory;
    private QuicEngine quicEngine;
    private HTTP3ClientHandler h3Handler;

    // Alt-Svc upgrade state
    private volatile boolean h3UpgradeInProgress;
    private HTTPClientHandler connectHandler;

    /**
     * Creates an HTTP client for the given host and port.
     *
     * <p>Uses the next available worker loop from the global
     * {@link Gumdrop} instance. DNS resolution is deferred until
     * {@link #connect} is called.
     *
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public HTTPClient(String host, int port) {
        this(null, host, port);
    }

    /**
     * Creates an HTTP client with an explicit selector loop.
     *
     * <p>Use this constructor when integrating with server-side code
     * that has its own selector loop management. DNS resolution is
     * deferred until {@link #connect} is called.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public HTTPClient(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.port = port;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Configuration (before connect)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets whether this client uses TLS.
     *
     * @param secure true for TLS
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    /**
     * Sets an externally-configured SSL context.
     *
     * @param context the SSL context
     */
    public void setSSLContext(SSLContext context) {
        this.sslContext = context;
    }

    /**
     * Sets HTTP Basic Authentication credentials.
     *
     * @param username the username
     * @param password the password
     */
    public void credentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     * Enables or disables HTTP/2 over TLS (h2).
     *
     * @param enabled true to enable HTTP/2
     */
    public void setH2Enabled(boolean enabled) {
        this.h2Enabled = enabled;
    }

    /**
     * Enables or disables HTTP/2 upgrade from HTTP/1.1 (h2c).
     *
     * @param enabled true to enable h2c upgrade
     */
    public void setH2cUpgradeEnabled(boolean enabled) {
        this.h2cUpgradeEnabled = enabled;
    }

    /**
     * Enables or disables HTTP/2 with prior knowledge (no upgrade).
     *
     * @param enabled true to connect with prior knowledge of HTTP/2
     */
    public void setH2WithPriorKnowledge(boolean enabled) {
        this.h2WithPriorKnowledge = enabled;
    }

    /**
     * Enables or disables HTTP/3 over QUIC.
     *
     * <p>When enabled, the client connects via QUIC with ALPN "h3"
     * instead of TCP. HTTP/3 requires TLS 1.3 (built into QUIC), so
     * the {@link #setSecure(boolean)} flag is implicitly true.
     *
     * <p>If PEM certificate/key files are needed for client authentication,
     * set them via {@link #setCertFile(String)} and
     * {@link #setKeyFile(String)}.
     *
     * @param enabled true to enable HTTP/3
     */
    public void setH3Enabled(boolean enabled) {
        this.h3Enabled = enabled;
    }

    /**
     * Enables or disables Alt-Svc header discovery and automatic
     * HTTP/3 upgrade.
     *
     * <p>When enabled (the default), the client inspects Alt-Svc
     * response headers and may transparently open an HTTP/3 connection.
     * Disable this when a specific protocol version is required.
     *
     * @param enabled true to enable Alt-Svc discovery
     */
    public void setAltSvcEnabled(boolean enabled) {
        this.altSvcEnabled = enabled;
    }

    /**
     * Sets the PEM certificate chain file for QUIC client authentication.
     *
     * @param path the PEM file path
     */
    public void setCertFile(Path path) {
        this.certFile = path;
    }

    public void setCertFile(String path) {
        this.certFile = Path.of(path);
    }

    /**
     * Sets the PEM private key file for QUIC client authentication.
     *
     * @param path the PEM file path
     */
    public void setKeyFile(Path path) {
        this.keyFile = path;
    }

    public void setKeyFile(String path) {
        this.keyFile = Path.of(path);
    }

    /**
     * Sets whether to verify the peer's TLS certificate for QUIC
     * connections. Defaults to {@code true}.
     *
     * @param verify true to verify the peer certificate
     */
    public void setVerifyPeer(boolean verify) {
        this.verifyPeer = verify;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Connects to the remote server.
     *
     * <p>Creates the transport factory, endpoint handler, and client
     * endpoint, then initiates the connection. Lifecycle events are
     * forwarded to the given handler.
     *
     * <p>If {@link #setH3Enabled(boolean)} is true, the connection uses
     * QUIC with HTTP/3 instead of TCP.
     *
     * @param handler the handler to receive connection lifecycle events
     */
    public void connect(final HTTPClientHandler handler) {
        this.connectHandler = handler;

        if (h3Enabled) {
            if (hostAddress != null) {
                connectH3(hostAddress, port, host, handler);
            } else {
                resolveAndConnectH3(host, port, handler);
            }
            return;
        }

        transportFactory = new TCPTransportFactory();
        transportFactory.setSecure(secure);
        if (sslContext != null) {
            transportFactory.setSSLContext(sslContext);
        }
        transportFactory.start();

        endpointHandler = new HTTPClientProtocolHandler(
                handler, host, port, secure);
        if (altSvcEnabled) {
            endpointHandler.setAltSvcListener(this);
        }
        if (username != null) {
            endpointHandler.credentials(username, password);
        }
        endpointHandler.setH2Enabled(h2Enabled);
        endpointHandler.setH2cUpgradeEnabled(h2cUpgradeEnabled);
        if (h2WithPriorKnowledge) {
            endpointHandler.setH2WithPriorKnowledge(true);
        }

        try {
            if (selectorLoop != null) {
                clientEndpoint = new ClientEndpoint(
                        transportFactory, selectorLoop,
                        host, port);
            } else {
                clientEndpoint = new ClientEndpoint(
                        transportFactory, host, port);
            }
            clientEndpoint.connect(endpointHandler);
        } catch (IOException e) {
            handler.onError(e);
        }
    }

    /**
     * Connects to a target host using HTTP/3 over QUIC.
     *
     * @param targetAddress the address to connect to (may differ from origin)
     * @param targetPort the port to connect to
     * @param serverName the TLS SNI hostname (the original origin)
     * @param handler the handler to receive connection lifecycle events
     */
    private void connectH3(final InetAddress targetAddress,
                           final int targetPort,
                           final String serverName,
                           final HTTPClientHandler handler) {
        SelectorLoop loop = selectorLoop;
        if (loop == null) {
            loop = Gumdrop.getInstance().nextWorkerLoop();
        }
        if (loop == null) {
            handler.onError(new IOException(
                    "No SelectorLoop available for HTTP/3"));
            return;
        }

        quicTransportFactory = new QuicTransportFactory();
        quicTransportFactory.setApplicationProtocols("h3");
        if (certFile != null) {
            quicTransportFactory.setCertFile(certFile);
        }
        if (keyFile != null) {
            quicTransportFactory.setKeyFile(keyFile);
        }
        quicTransportFactory.setVerifyPeer(verifyPeer);

        try {
            quicTransportFactory.start();
        } catch (RuntimeException e) {
            handler.onError(new IOException(
                    "Failed to start QUIC transport: " + e.getMessage()));
            return;
        }

        try {
            quicEngine = quicTransportFactory.connect(
                    targetAddress, targetPort,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(
                                QuicConnection connection) {
                            h3Handler = new HTTP3ClientHandler(connection);
                            handler.onConnected(null);
                            handler.onSecurityEstablished(
                                    connection.getSecurityInfo());
                        }
                    },
                    loop, serverName);
        } catch (IOException e) {
            handler.onError(e);
        }
    }

    private void resolveAndConnectH3(final String targetHost,
                                     final int targetPort,
                                     final HTTPClientHandler handler) {
        SelectorLoop loop = selectorLoop;
        if (loop == null) {
            Gumdrop gumdrop = Gumdrop.getInstance();
            gumdrop.start();
            loop = gumdrop.nextWorkerLoop();
        }
        if (loop == null) {
            handler.onError(new IOException(
                    "No SelectorLoop available for DNS resolution"));
            return;
        }
        DNSResolver resolver = DNSResolver.forLoop(loop);
        resolver.resolve(targetHost, new ResolveCallback() {
            @Override
            public void onResolved(List<InetAddress> addresses) {
                hostAddress = addresses.get(0);
                connectH3(hostAddress, targetPort, targetHost, handler);
            }

            @Override
            public void onError(String error) {
                handler.onError(new IOException(
                        "DNS resolution failed for " + targetHost
                        + ": " + error));
            }
        });
    }

    /**
     * Returns whether the connection is open and ready for requests.
     *
     * @return true if connected and open
     */
    public boolean isOpen() {
        if (h3Handler != null) {
            return !h3Handler.isGoaway();
        }
        return endpointHandler != null && endpointHandler.isOpen();
    }

    /**
     * Closes the connection and deregisters from Gumdrop's lifecycle
     * tracking.
     */
    public void close() {
        if (h3Handler != null) {
            h3Handler.close();
        }
        if (quicEngine != null) {
            quicEngine.close();
        }
        if (endpointHandler != null) {
            endpointHandler.close();
        }
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
    }

    /**
     * Returns the negotiated HTTP version.
     *
     * @return the HTTP version, or null if not yet negotiated
     */
    public HTTPVersion getVersion() {
        if (h3Handler != null) {
            return HTTPVersion.HTTP_3;
        }
        if (endpointHandler == null) {
            return null;
        }
        return endpointHandler.getVersion();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Request factory (delegates to endpoint handler)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a GET request.
     *
     * @param path the request path
     * @return the HTTP request
     */
    public HTTPRequest get(String path) {
        return request("GET", path);
    }

    /**
     * Creates a POST request.
     *
     * @param path the request path
     * @return the HTTP request
     */
    public HTTPRequest post(String path) {
        return request("POST", path);
    }

    /**
     * Creates a PUT request.
     *
     * @param path the request path
     * @return the HTTP request
     */
    public HTTPRequest put(String path) {
        return request("PUT", path);
    }

    /**
     * Creates a DELETE request.
     *
     * @param path the request path
     * @return the HTTP request
     */
    public HTTPRequest delete(String path) {
        return request("DELETE", path);
    }

    /**
     * Creates a HEAD request.
     *
     * @param path the request path
     * @return the HTTP request
     */
    public HTTPRequest head(String path) {
        return request("HEAD", path);
    }

    /**
     * Creates an OPTIONS request.
     *
     * @param path the request path
     * @return the HTTP request
     */
    public HTTPRequest options(String path) {
        return request("OPTIONS", path);
    }

    /**
     * Creates a PATCH request.
     *
     * @param path the request path
     * @return the HTTP request
     */
    public HTTPRequest patch(String path) {
        return request("PATCH", path);
    }

    /**
     * Creates a request with the given HTTP method.
     *
     * @param method the HTTP method
     * @param path the request path
     * @return the HTTP request
     */
    public HTTPRequest request(String method, String path) {
        if (h3Handler != null) {
            String scheme = "https";
            String authority = host;
            if (port != 443) {
                authority = host + ":" + port;
            }
            return new org.bluezoo.gumdrop.http.h3.H3Request(
                    h3Handler, method, path, authority, scheme);
        }
        return endpointHandler.request(method, path);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Alt-Svc discovery
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void altSvcReceived(String value) {
        if (h3Handler != null || h3UpgradeInProgress) {
            return;
        }

        int[] parsed = parseAltSvcH3(value);
        if (parsed == null) {
            return;
        }

        String altHost = null;
        int altHostLen = parsed[0];
        int altPort = parsed[1];
        if (altHostLen > 0) {
            altHost = extractAltSvcHost(value, altHostLen);
        }

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("Alt-Svc discovered h3 endpoint: "
                    + (altHost != null ? altHost : host) + ":" + altPort);
        }

        h3UpgradeInProgress = true;
        if (altHost != null) {
            resolveAndConnectH3(altHost, altPort, connectHandler);
        } else if (hostAddress != null) {
            connectH3(hostAddress, altPort, host, connectHandler);
        } else {
            resolveAndConnectH3(host, altPort, connectHandler);
        }
    }

    /**
     * Parses the h3 entry from an Alt-Svc header value.
     * Character-by-character parsing; no regex.
     *
     * <p>Looks for {@code h3="[host]:port"} in the value.
     * Returns a two-element array {@code [hostLength, port]} where
     * hostLength is the length of the host substring (0 means same
     * origin), or {@code null} if no h3 entry is found.
     */
    static int[] parseAltSvcH3(String value) {
        int len = value.length();
        int i = 0;

        while (i < len) {
            while (i < len && (value.charAt(i) == ' '
                    || value.charAt(i) == '\t')) {
                i++;
            }

            if (i + 4 <= len
                    && value.charAt(i) == 'h'
                    && value.charAt(i + 1) == '3'
                    && value.charAt(i + 2) == '='
                    && value.charAt(i + 3) == '"') {
                i += 4;

                int hostStart = i;
                int colonPos = -1;

                while (i < len && value.charAt(i) != '"') {
                    if (value.charAt(i) == ':') {
                        colonPos = i;
                    }
                    i++;
                }

                if (i >= len || colonPos < 0) {
                    return null;
                }

                int hostLen = colonPos - hostStart;
                int portStart = colonPos + 1;
                int portEnd = i;

                int port = 0;
                for (int p = portStart; p < portEnd; p++) {
                    char c = value.charAt(p);
                    if (c < '0' || c > '9') {
                        return null;
                    }
                    port = port * 10 + (c - '0');
                }

                if (port <= 0 || port > 65535) {
                    return null;
                }

                return new int[] { hostLen, port };
            }

            while (i < len && value.charAt(i) != ',') {
                i++;
            }
            if (i < len) {
                i++;
            }
        }

        return null;
    }

    /**
     * Extracts the host portion from the h3 Alt-Svc entry.
     * Assumes the value starts with {@code h3="host:port"} and the
     * host is {@code hostLen} characters after the opening quote.
     */
    private static String extractAltSvcHost(String value, int hostLen) {
        int i = 0;
        int len = value.length();
        while (i < len) {
            while (i < len && (value.charAt(i) == ' '
                    || value.charAt(i) == '\t')) {
                i++;
            }
            if (i + 4 <= len
                    && value.charAt(i) == 'h'
                    && value.charAt(i + 1) == '3'
                    && value.charAt(i + 2) == '='
                    && value.charAt(i + 3) == '"') {
                return value.substring(i + 4, i + 4 + hostLen);
            }
            while (i < len && value.charAt(i) != ',') {
                i++;
            }
            if (i < len) {
                i++;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLI entry point
    // ═══════════════════════════════════════════════════════════════════

    private static void printUsage() {
        System.err.println(
                "Usage: HTTPClient [options] <URL>\n"
                + "\n"
                + "Options:\n"
                + "  -X <method>       HTTP method (default: GET)\n"
                + "  -H <name:value>   Add request header (repeatable)\n"
                + "  -d <file>         Request body from file (- for stdin)\n"
                + "  -o <file>         Write response body to file"
                        + " (default: stdout)\n"
                + "  --http1.1         Force HTTP/1.1 only\n"
                + "  --http2           Force HTTP/2"
                        + " (prior knowledge / ALPN)\n"
                + "  --http3           Force HTTP/3 (QUIC)\n"
                + "  -E <cert>:<key>   PEM client certificate and key\n"
                + "  -k                Skip peer certificate verification\n"
                + "  -v                Verbose (print response headers)\n"
                + "  -I                HEAD request (headers only)\n"
                + "\n"
                + "URL format: [http|https]://host[:port][/path]\n"
                + "Default port: 80 for http, 443 for https\n");
    }

    /**
     * CLI entry point for making HTTP requests.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        String method = "GET";
        List requestHeaders = new ArrayList();
        String bodyFile = null;
        String outputFile = null;
        String forceVersion = null;
        String pemCert = null;
        String pemKey = null;
        boolean skipVerify = false;
        boolean verbose = false;
        boolean headersOnly = false;
        String url = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-X".equals(arg)) {
                if (++i >= args.length) {
                    System.err.println("Missing argument for -X");
                    System.exit(1);
                }
                method = args[i];
            } else if ("-H".equals(arg)) {
                if (++i >= args.length) {
                    System.err.println("Missing argument for -H");
                    System.exit(1);
                }
                requestHeaders.add(args[i]);
            } else if ("-d".equals(arg)) {
                if (++i >= args.length) {
                    System.err.println("Missing argument for -d");
                    System.exit(1);
                }
                bodyFile = args[i];
            } else if ("-o".equals(arg)) {
                if (++i >= args.length) {
                    System.err.println("Missing argument for -o");
                    System.exit(1);
                }
                outputFile = args[i];
            } else if ("--http1.1".equals(arg)) {
                forceVersion = "1.1";
            } else if ("--http2".equals(arg)) {
                forceVersion = "2";
            } else if ("--http3".equals(arg)) {
                forceVersion = "3";
            } else if ("-E".equals(arg)) {
                if (++i >= args.length) {
                    System.err.println("Missing argument for -E");
                    System.exit(1);
                }
                String certKeyArg = args[i];
                int colonPos = certKeyArg.indexOf(':');
                if (colonPos < 0) {
                    System.err.println(
                            "Invalid -E format, expected cert:key");
                    System.exit(1);
                }
                pemCert = certKeyArg.substring(0, colonPos);
                pemKey = certKeyArg.substring(colonPos + 1);
            } else if ("-k".equals(arg)) {
                skipVerify = true;
            } else if ("-v".equals(arg)) {
                verbose = true;
            } else if ("-I".equals(arg)) {
                headersOnly = true;
                method = "HEAD";
            } else if (arg.startsWith("-")) {
                System.err.println("Unknown option: " + arg);
                printUsage();
                System.exit(1);
            } else {
                url = arg;
            }
        }

        if (url == null) {
            printUsage();
            System.exit(1);
        }

        String scheme;
        String hostPort;
        String path;
        if (url.startsWith("https://")) {
            scheme = "https";
            hostPort = url.substring(8);
        } else if (url.startsWith("http://")) {
            scheme = "http";
            hostPort = url.substring(7);
        } else {
            System.err.println("URL must start with http:// or https://");
            System.exit(1);
            return;
        }

        int slashPos = hostPort.indexOf('/');
        if (slashPos >= 0) {
            path = hostPort.substring(slashPos);
            hostPort = hostPort.substring(0, slashPos);
        } else {
            path = "/";
        }

        String targetHost;
        int targetPort;
        int colonPos = hostPort.lastIndexOf(':');
        if (colonPos >= 0) {
            targetHost = hostPort.substring(0, colonPos);
            try {
                targetPort = Integer.parseInt(
                        hostPort.substring(colonPos + 1));
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number");
                System.exit(1);
                return;
            }
        } else {
            targetHost = hostPort;
            targetPort = "https".equals(scheme) ? 443 : 80;
        }

        SelectorLoop loop = new SelectorLoop(1);
        loop.start();

        try {
            runRequest(loop, targetHost, targetPort, scheme, path, method,
                    requestHeaders, bodyFile, outputFile, forceVersion,
                    pemCert, pemKey, skipVerify, verbose, headersOnly);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            loop.shutdown();
            System.exit(1);
        }

        loop.shutdown();
        System.exit(0);
    }

    private static void runRequest(
            final SelectorLoop loop,
            final String targetHost, final int targetPort,
            final String scheme, final String path,
            final String method, final List requestHeaders,
            final String bodyFile, final String outputFile,
            final String forceVersion,
            final String pemCert, final String pemKey,
            final boolean skipVerify,
            final boolean verbose, final boolean headersOnly)
            throws Exception {

        final HTTPClient client =
                new HTTPClient(loop, targetHost, targetPort);

        boolean isSecure = "https".equals(scheme);
        client.setSecure(isSecure);
        client.setVerifyPeer(!skipVerify);

        if (isSecure && !"3".equals(forceVersion)) {
            SSLContext ctx = createClientSSLContext(
                    pemCert, pemKey, skipVerify);
            client.setSSLContext(ctx);
        }
        if (pemCert != null) {
            client.setCertFile(pemCert);
        }
        if (pemKey != null) {
            client.setKeyFile(pemKey);
        }

        if ("3".equals(forceVersion)) {
            client.setH3Enabled(true);
            Gumdrop.getInstance().start();
        } else if ("2".equals(forceVersion)) {
            if (isSecure) {
                client.setH2Enabled(true);
            } else {
                client.setH2WithPriorKnowledge(true);
            }
        } else if ("1.1".equals(forceVersion)) {
            client.setH2Enabled(false);
            client.setH2cUpgradeEnabled(false);
        }

        client.setAltSvcEnabled(false);

        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicReference connectError = new AtomicReference();

        client.connect(new HTTPClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                connectLatch.countDown();
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                connectLatch.countDown();
            }

            @Override
            public void onError(Exception cause) {
                connectError.set(cause);
                connectLatch.countDown();
                doneLatch.countDown();
            }

            @Override
            public void onDisconnected() {
                doneLatch.countDown();
            }
        });

        connectLatch.await();

        Exception connErr = (Exception) connectError.get();
        if (connErr != null) {
            throw connErr;
        }

        if (verbose) {
            HTTPVersion version = client.getVersion();
            if (version != null) {
                System.err.println("* Connected via " + version);
            }
        }

        final OutputStream out;
        if (outputFile != null && !"-".equals(outputFile)) {
            out = new FileOutputStream(outputFile);
        } else {
            out = System.out;
        }

        final AtomicReference responseError = new AtomicReference();
        final CountDownLatch responseLatch = new CountDownLatch(1);

        HTTPRequest req = client.request(method, path);

        for (int i = 0; i < requestHeaders.size(); i++) {
            String hdr = (String) requestHeaders.get(i);
            int cp = hdr.indexOf(':');
            if (cp > 0) {
                String name = hdr.substring(0, cp).trim();
                String value = hdr.substring(cp + 1).trim();
                req.header(name, value);
            }
        }

        if (bodyFile != null) {
            InputStream bodyIn;
            if ("-".equals(bodyFile)) {
                bodyIn = System.in;
            } else {
                bodyIn = new FileInputStream(bodyFile);
            }
            req.startRequestBody(createResponseHandler(
                    out, verbose, headersOnly, responseLatch,
                    responseError, client));
            byte[] buf = new byte[8192];
            int n;
            while ((n = bodyIn.read(buf)) >= 0) {
                req.requestBodyContent(ByteBuffer.wrap(buf, 0, n));
            }
            req.endRequestBody();
            if (!"-".equals(bodyFile)) {
                bodyIn.close();
            }
        } else {
            req.send(createResponseHandler(
                    out, verbose, headersOnly, responseLatch,
                    responseError, client));
        }

        responseLatch.await();

        if (out != System.out) {
            out.close();
        }

        client.close();

        Exception respErr = (Exception) responseError.get();
        if (respErr != null) {
            throw respErr;
        }
    }

    private static SSLContext createClientSSLContext(
            String pemCert, String pemKey, boolean skipVerify)
            throws Exception {
        TrustManager[] tm = null;
        if (skipVerify) {
            LOGGER.warning("TLS certificate verification disabled " +
                    "(skipVerify=true). " +
                    "This is insecure and should not be used in production.");
            tm = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(
                            X509Certificate[] certs, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(
                            X509Certificate[] certs, String authType) {
                    }
                }
            };
        }

        KeyManager[] km = null;
        if (pemCert != null && pemKey != null) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            CertificateFactory cf =
                    CertificateFactory.getInstance("X.509");
            FileInputStream certIn = new FileInputStream(pemCert);
            Certificate cert = cf.generateCertificate(certIn);
            certIn.close();
            ks.setCertificateEntry("client-cert", cert);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, new char[0]);
            km = kmf.getKeyManagers();
        }

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(km, tm, null);
        return ctx;
    }

    private static HTTPResponseHandler createResponseHandler(
            final OutputStream out,
            final boolean verbose,
            final boolean headersOnly,
            final CountDownLatch doneLatch,
            final AtomicReference errorRef,
            final HTTPClient client) {
        return new DefaultHTTPResponseHandler() {

            @Override
            public void ok(HTTPResponse response) {
                if (verbose || headersOnly) {
                    HTTPVersion version = client.getVersion();
                    String versionStr = version != null
                            ? version.toString() : "HTTP/?";
                    System.err.println(versionStr + " "
                            + response.getStatus().code + " "
                            + response.getStatus());
                }
            }

            @Override
            public void error(HTTPResponse response) {
                if (verbose || headersOnly) {
                    HTTPVersion version = client.getVersion();
                    String versionStr = version != null
                            ? version.toString() : "HTTP/?";
                    System.err.println(versionStr + " "
                            + response.getStatus().code + " "
                            + response.getStatus());
                }
            }

            @Override
            public void header(String name, String value) {
                if (verbose || headersOnly) {
                    System.err.println(name + ": " + value);
                }
            }

            @Override
            public void startResponseBody() {
                if (verbose || headersOnly) {
                    System.err.println();
                }
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                if (headersOnly) {
                    return;
                }
                try {
                    if (data.hasArray()) {
                        out.write(data.array(),
                                data.arrayOffset() + data.position(),
                                data.remaining());
                    } else {
                        byte[] tmp = new byte[data.remaining()];
                        data.get(tmp);
                        out.write(tmp);
                    }
                    out.flush();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            "Error writing response body", e);
                }
            }

            @Override
            public void close() {
                doneLatch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                errorRef.set(ex);
                doneLatch.countDown();
            }
        };
    }
}
