/*
 * SmtpClient.java
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

package org.bluezoo.gumdrop.smtp.client;

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.client.ClientDial;
import org.bluezoo.gumdrop.dns.client.DaneTrustManager;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnssecAwareQueryCallback;
import org.bluezoo.gumdrop.dns.DnssecStatus;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * High-level SMTP client facade.
 *
 * <p>This class provides a simple, concrete API for connecting to SMTP
 * servers. It internally creates a {@link TcpTransportFactory},
 * {@link ClientEndpoint}, and {@link SmtpClientProtocolHandler}, wiring
 * them together and forwarding lifecycle events to the caller's
 * {@link RemoteGreeting} handler, supplied directly to {@link
 * #connect(Gumdrop, RemoteGreeting)}.
 *
 * <h4>Composition (recommended)</h4>
 * <pre>{@code
 * SmtpClient client = new SmtpClient()
 *         .host("smtp.example.com")
 *         .port(587);
 * client.connect(gumdrop, new MyRemoteGreeting());
 * }</pre>
 *
 * <h4>Plaintext with STARTTLS (submission)</h4>
 * <pre>{@code
 * SmtpClient client = new SmtpClient(selectorLoop, "smtp.example.com", 587);
 * client.tls(TlsConfig.keystore(Path.of("client.p12"), "changeit"));
 * client.connect(gumdrop, new RemoteGreeting() {
 *     public void handleGreeting(ClientHelloState hello,
 *                                String message, boolean esmtp) {
 *         hello.ehlo("myhostname", ehloHandler);
 *     }
 *     // ...
 * });
 * }</pre>
 *
 * <h4>Implicit TLS (SMTPS)</h4>
 * <pre>{@code
 * SmtpClient client = new SmtpClient("smtp.example.com", 465);
 * client.secure(true).tls(TlsConfig.keystore(Path.of("client.p12"), "changeit"));
 * client.connect(gumdrop, greetingHandler);
 * }</pre>
 *
 * <h4>Opportunistic DANE (RFC 7672)</h4>
 * <pre>{@code
 * SmtpClient client = new SmtpClient("mail.example.com", 25);
 * client.daneResolver(myResolver); // a DNSSEC-enabled DnsResolver
 * client.connect(gumdrop, greetingHandler);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RemoteGreeting
 * @see SmtpClientProtocolHandler
 * @see org.bluezoo.gumdrop.dns.client.DaneTrustManager
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321</a> (SMTP)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314</a> (Implicit TLS, SMTPS port 465)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3207">RFC 3207</a> (STARTTLS)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7672">RFC 7672</a> (SMTP DANE)
 */
public class SmtpClient {

    private final ClientDial dial = ClientDial.withDefaultPort(25);
    private final TlsConfig tls = new TlsConfig();
    private boolean secure;

    private DnsResolver daneResolver;

    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private SmtpClientProtocolHandler endpointHandler;
    private Gumdrop gumdrop;

    /**
     * Creates an SMTP client for fluent configuration before {@link #connect(Gumdrop, RemoteGreeting)}.
     */
    public SmtpClient() {
    }

    /**
     * Creates an SMTP client for the given hostname and port.
     *
     * <p>Uses the next available worker loop from the global
     * {@link Gumdrop} instance. DNS resolution is deferred until
     * {@link #connect} is called.
     *
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public SmtpClient(String host, int port) {
        this(null, host, port);
    }

    /**
     * Creates an SMTP client with an explicit selector loop.
     *
     * <p>DNS resolution is deferred until {@link #connect} is called.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop
     *                     worker
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public SmtpClient(SelectorLoop selectorLoop, String host,
                      int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    /**
     * Creates an SMTP client for the given address and port.
     *
     * @param host the remote host address
     * @param port the remote port
     */
    public SmtpClient(InetAddress host, int port) {
        this(null, host, port);
    }

    /**
     * Creates an SMTP client with an explicit selector loop and address.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop
     *                     worker
     * @param host the remote host address
     * @param port the remote port
     */
    public SmtpClient(SelectorLoop selectorLoop, InetAddress host,
                      int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    /**
     * Creates an SMTP client for a UNIX domain socket, mirroring
     * {@link org.bluezoo.gumdrop.TcpListener#path(java.nio.file.Path)} on the server side.
     *
     * <p>Uses the next available worker loop from the global {@link
     * Gumdrop} instance.
     *
     * @param socketPath the UNIX domain socket path
     */
    public SmtpClient(String socketPath) {
        this(null, socketPath);
    }

    /**
     * Creates an SMTP client for a UNIX domain socket with an
     * explicit selector loop.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param socketPath the UNIX domain socket path
     */
    public SmtpClient(SelectorLoop selectorLoop, String socketPath) {
        dial.selectorLoop(selectorLoop).socketPath(socketPath);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Configuration (before connect)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets the remote hostname. Resolved via {@link DnsResolver} at
     * {@link #connect(Gumdrop, RemoteGreeting)}.
     *
     * @param host the remote hostname
     * @return this client
     */
    public SmtpClient host(String host) {
        dial.host(host);
        return this;
    }

    /**
     * Sets the remote host address (no DNS lookup at connect).
     *
     * @param hostAddress the remote address
     * @return this client
     */
    public SmtpClient host(InetAddress hostAddress) {
        dial.host(hostAddress);
        return this;
    }

    /**
     * Sets the remote port.
     *
     * @param port the port number
     * @return this client
     */
    public SmtpClient port(int port) {
        dial.port(port);
        return this;
    }

    /**
     * Sets the UNIX domain socket path (mutually exclusive with host).
     *
     * @param socketPath the socket path
     * @return this client
     */
    public SmtpClient socketPath(String socketPath) {
        dial.socketPath(socketPath);
        return this;
    }

    /**
     * Sets the selector loop for this client.
     *
     * @param selectorLoop the loop, or null for a Gumdrop worker
     * @return this client
     */
    public SmtpClient selectorLoop(SelectorLoop selectorLoop) {
        dial.selectorLoop(selectorLoop);
        return this;
    }

    /**
     * Sets whether this client uses implicit TLS (SMTPS).
     *
     * @param secure true for implicit TLS
     * @return this client
     */
    public SmtpClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * Sets this client's TLS settings (certificates, trust, ECH and so on). The
     * settings are copied, so later changes to {@code source} are not seen.
     * Whether TLS is used at all is decided by {@link #secure(boolean)}.
     *
     * @param source the TLS configuration
     * @return this client
     */
    public SmtpClient tls(TlsConfig source) {
        tls.copyFrom(source);
        return this;
    }

    /**
     * Enables opportunistic DANE authentication via the given resolver.
     *
     * @param resolver the resolver, or null to disable
     * @return this client
     */
    public SmtpClient daneResolver(DnsResolver resolver) {
        this.daneResolver = resolver;
        return this;
    }

    public SmtpClient dnsResolver(DnsResolver resolver) {
        dial.dnsResolver(resolver);
        return this;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Connects to the remote SMTP server.
     *
     * <p>If a DANE resolver was configured via {@link
     * #daneResolver}, first looks up TLSA records for this
     * client's host and port; otherwise connects immediately.
     *
     * @param gumdrop the runtime this connection is made under
     * @param handler the handler to receive the server greeting and
     *                lifecycle events
     */
    public void connect(Gumdrop gumdrop, final RemoteGreeting handler) {
        this.gumdrop = gumdrop;
        if (daneResolver != null && dial.getHost() != null) {
            lookupDane(handler);
        } else {
            doConnect(handler);
        }
    }

    /**
     * Looks up TLSA records for this client's host/port and, if the
     * lookup is DNSSEC-secure and non-empty, installs a {@link
     * DaneTrustManager} before proceeding to {@link #doConnect}.
     * RFC 7672 section 3.1.3: an insecure or empty lookup is not an
     * error -- it just means DANE does not apply, so the connection
     * proceeds with whatever trust manager was already configured.
     */
    private void lookupDane(final RemoteGreeting handler) {
        String tlsaName = "_" + dial.getPort() + "._tcp." + dial.getHost();
        daneResolver.queryTLSA(tlsaName, new DnssecAwareQueryCallback() {
            @Override
            public void onResponse(DnsMessage response, DnssecStatus status) {
                if (status == DnssecStatus.SECURE) {
                    List<DnsResourceRecord> tlsaRecords = new ArrayList<>();
                    for (DnsResourceRecord rr : response.getAnswers()) {
                        if (rr.getType() == DnsType.TLSA) {
                            tlsaRecords.add(rr);
                        }
                    }
                    if (!tlsaRecords.isEmpty()) {
                        tls.trustManager(new DaneTrustManager(
                                tls.getTrustManager(), tlsaRecords));
                    }
                }
                doConnect(handler);
            }

            @Override
            public void onError(String error) {
                doConnect(handler);
            }
        });
    }

    /**
     * Creates the transport factory, endpoint handler, and client
     * endpoint, then initiates the connection. Lifecycle events are
     * forwarded to the given handler.
     *
     * @param handler the handler to receive the server greeting and
     *                lifecycle events
     */
    private void doConnect(RemoteGreeting handler) {
        dial.requireTarget();
        transportFactory = new TcpTransportFactory();
        endpointHandler = new SmtpClientProtocolHandler(handler);
        ClientConnect.discoverEch(gumdrop, secure, dial, tls, new ClientConnect.EchDiscoveryCallback() {
            @Override
            public void discovered(byte[] echConfigList) {
                try {
                    ClientConnect.prepareTls(secure, tls, transportFactory, echConfigList);
                    endpointHandler.setSecure(secure);
                    clientEndpoint = ClientConnect.openAndConnect(
                            gumdrop, dial, transportFactory, endpointHandler);
                } catch (IOException e) {
                    handler.onError(e);
                }
            }
        });
    }

    /**
     * Returns whether the connection is open.
     *
     * @return true if connected and open
     */
    public boolean isOpen() {
        return endpointHandler != null && endpointHandler.isOpen();
    }

    /**
     * Closes the connection.
     */
    public void close() {
        if (endpointHandler != null) {
            endpointHandler.close();
        }
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
    }

}
