/*
 * ClientDial.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.client;

import java.io.IOException;
import java.net.InetAddress;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TransportFactory;
import org.bluezoo.gumdrop.dns.client.DnsResolver;

/**
 * Shared outbound dial target for protocol client facades — host, port, UNIX
 * socket path, selector loop, and optional DNS resolver.
 *
 * <p>Configure fluently before {@link #connect(TransportFactory, ProtocolHandler)}.
 * When no {@link #dnsResolver(DnsResolver)} is set, {@link ClientEndpoint} uses
 * {@link DnsResolver#forLoop} at connect time (until {@code Runtime} client
 * defaults land).
 *
 * @see org.bluezoo.gumdrop.tls.ClientTlsConfig
 * @see docs/COMPOSITION.md
 */
public final class ClientDial {

    private String host;
    private InetAddress hostAddress;
    private int port;
    private String socketPath;
    private SelectorLoop selectorLoop;
    private DnsResolver dnsResolver;

    private ClientDial(int defaultPort) {
        this.port = defaultPort;
    }

    /**
     * Creates dial state with the protocol's usual default port when unset.
     */
    public static ClientDial withDefaultPort(int defaultPort) {
        return new ClientDial(defaultPort);
    }

    public ClientDial host(String host) {
        this.host = host;
        this.hostAddress = null;
        this.socketPath = null;
        return this;
    }

    public ClientDial host(InetAddress hostAddress) {
        if (hostAddress == null) {
            throw new NullPointerException("hostAddress");
        }
        this.hostAddress = hostAddress;
        this.host = null;
        this.socketPath = null;
        return this;
    }

    public ClientDial port(int port) {
        this.port = port;
        return this;
    }

    public ClientDial socketPath(String socketPath) {
        if (socketPath == null) {
            throw new NullPointerException("socketPath");
        }
        this.socketPath = socketPath;
        this.host = null;
        this.hostAddress = null;
        return this;
    }

    public ClientDial selectorLoop(SelectorLoop selectorLoop) {
        this.selectorLoop = selectorLoop;
        return this;
    }

    /**
     * Optional resolver for hostname lookup at connect. When {@code null},
     * {@link ClientEndpoint} chooses one from the selector loop.
     */
    public ClientDial dnsResolver(DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
        return this;
    }

    public String getHost() {
        return host;
    }

    public InetAddress getHostAddress() {
        return hostAddress;
    }

    public int getPort() {
        return port;
    }

    public String getSocketPath() {
        return socketPath;
    }

    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    public DnsResolver getDnsResolver() {
        return dnsResolver;
    }

    /**
     * @throws IllegalStateException if no target was configured
     */
    public void requireTarget() {
        if (socketPath == null && host == null && hostAddress == null) {
            throw new IllegalStateException(
                    "host, host address, or socketPath is required");
        }
    }

    /**
     * Opens a {@link ClientEndpoint} for this dial target. Does not connect.
     */
    public ClientEndpoint openEndpoint(TransportFactory factory) {
        requireTarget();
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        ClientEndpoint endpoint;
        if (socketPath != null) {
            endpoint = (selectorLoop != null)
                    ? new ClientEndpoint(factory, selectorLoop, socketPath)
                    : new ClientEndpoint(factory, socketPath);
        } else if (host != null) {
            endpoint = (selectorLoop != null)
                    ? new ClientEndpoint(factory, selectorLoop, host, port)
                    : new ClientEndpoint(factory, host, port);
        } else {
            endpoint = (selectorLoop != null)
                    ? new ClientEndpoint(factory, selectorLoop, hostAddress, port)
                    : new ClientEndpoint(factory, hostAddress, port);
        }
        if (dnsResolver != null) {
            endpoint.setDnsResolver(dnsResolver);
        }
        return endpoint;
    }

    /**
     * Opens a {@link ClientEndpoint} and initiates an asynchronous connect.
     */
    public ClientEndpoint connect(TransportFactory factory, ProtocolHandler handler)
            throws IOException {
        ClientEndpoint endpoint = openEndpoint(factory);
        endpoint.connect(handler);
        return endpoint;
    }

}
