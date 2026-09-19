/*
 * DnsTcpListener.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;

import java.net.InetAddress;

/**
 * Cleartext DNS-over-TCP listener (RFC 1035 section 4.2.2).
 *
 * <p>Uses the same length-prefixed framing as DoT, without TLS. Suitable
 * for AXFR/IXFR and large responses.
 */
public class DnsTcpListener extends TcpListener {

    private static final int DEFAULT_PORT = 53;

    private int port = DEFAULT_PORT;
    private org.bluezoo.gumdrop.dns.server.DnsServer server;

    public DnsTcpListener() {
        secure = false;
    }

    @Override
    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public DnsTcpListener port(int port) {
        setPort(port);
        return this;
    }

    @Override
    public DnsTcpListener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public DnsTcpListener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public String getDescription() {
        return "dns-tcp";
    }

    public void setServer(org.bluezoo.gumdrop.dns.server.DnsServer server) {
        this.server = server;
    }

    public org.bluezoo.gumdrop.dns.server.DnsServer getServer() {
        return server;
    }

    @Override
    protected ProtocolHandler createHandler() {
        return new DoTProtocolHandler(server, "tcp");
    }
}
