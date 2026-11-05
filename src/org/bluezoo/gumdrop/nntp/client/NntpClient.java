/*
 * NntpClient.java
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

package org.bluezoo.gumdrop.nntp.client;

import java.io.IOException;
import java.net.InetAddress;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.client.ClientDial;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.TlsConfig;

/**
 * An NNTP client (RFC 3977) that reads news from a server, with STARTTLS
 * (RFC 4642) and AUTHINFO USER/PASS (RFC 4643).
 *
 * <p>Configure the client, then {@link #connect connect} it. The greeting,
 * CAPABILITIES, TLS and login are dealt with first; when they are done the
 * handler is given an {@link NntpSession} to issue commands on.
 *
 * <pre>
 * new NntpClient("news.example.com", 119)
 *         .startTls(NntpClient.StartTls.REQUIRED)
 *         .credentials("alice", "secret")
 *         .connect(gumdrop, new NntpClientHandler() {
 *             public void connected(NntpSession session, NntpGreeting greeting) {
 *                 session.listActive("comp.*", ..., ...);
 *                 session.quit();
 *             }
 *             public void error(Exception cause) { ... }
 *             public void disconnected() { }
 *         });
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpClient {

    /** Whether to upgrade a plain connection with STARTTLS. */
    public enum StartTls {
        /** Never. */
        NEVER,
        /** When the server offers it; otherwise carry on in the clear. */
        IF_OFFERED,
        /** Always; the connection fails if the server does not offer it. */
        REQUIRED
    }

    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 60000L;

    private final ClientDial dial = ClientDial.withDefaultPort(119);
    private final TlsConfig tls = new TlsConfig();
    private boolean secure;
    private StartTls startTls = StartTls.NEVER;
    private String user;
    private String password;
    private long commandTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS;

    private NntpClientProtocolHandler connection;
    private ClientEndpoint clientEndpoint;

    /** Creates a client; set the host and port before connecting. */
    public NntpClient() {
    }

    /**
     * Creates a client for a host and port.
     *
     * @param host the server's name or address
     * @param port the port (119 for NNTP, 563 for NNTPS)
     */
    public NntpClient(String host, int port) {
        dial.host(host).port(port);
    }

    /**
     * Creates a client for a host and port.
     *
     * @param host the server's address
     * @param port the port
     */
    public NntpClient(InetAddress host, int port) {
        dial.host(host).port(port);
    }

    /**
     * Sets whether TLS is used from the first byte (NNTPS, RFC 8143).
     *
     * @param secure true for implicit TLS
     * @return this client
     */
    public NntpClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * Sets the TLS settings, such as trust.
     *
     * @param source the settings, which are copied
     * @return this client
     */
    public NntpClient tls(TlsConfig source) {
        tls.copyFrom(source);
        return this;
    }

    /**
     * Sets whether to upgrade a plain connection with STARTTLS. Default
     * {@link StartTls#NEVER}.
     *
     * @param mode the mode
     * @return this client
     */
    public NntpClient startTls(StartTls mode) {
        this.startTls = mode;
        return this;
    }

    /**
     * Sets the login. AUTHINFO USER/PASS is sent once TLS has been set up as
     * configured. With {@link StartTls#IF_OFFERED} and a server that does not
     * offer it, the password goes in the clear.
     *
     * @param user the user name
     * @param password the password
     * @return this client
     */
    public NntpClient credentials(String user, String password) {
        this.user = user;
        this.password = password;
        return this;
    }

    /**
     * Sets how long to wait for the server to answer a command; 0 waits
     * forever. Default 60 seconds.
     *
     * @param ms the time in milliseconds
     * @return this client
     */
    public NntpClient commandTimeoutMs(long ms) {
        this.commandTimeoutMs = ms;
        return this;
    }

    /**
     * Sets the host.
     *
     * @param host the server's name
     * @return this client
     */
    public NntpClient host(String host) {
        dial.host(host);
        return this;
    }

    /**
     * Sets the port.
     *
     * @param port the port
     * @return this client
     */
    public NntpClient port(int port) {
        dial.port(port);
        return this;
    }

    /**
     * Sets the event loop to use; by default one is chosen.
     *
     * @param selectorLoop the loop
     * @return this client
     */
    public NntpClient selectorLoop(SelectorLoop selectorLoop) {
        dial.selectorLoop(selectorLoop);
        return this;
    }

    /**
     * Sets the DNS resolver; by default the system one is used.
     *
     * @param dnsResolver the resolver
     * @return this client
     */
    public NntpClient dnsResolver(DnsResolver dnsResolver) {
        dial.dnsResolver(dnsResolver);
        return this;
    }

    /**
     * Connects. This returns at once; the handler hears of the outcome.
     *
     * @param gumdrop the running Gumdrop
     * @param handler receives the session when it is ready, and failures
     */
    public void connect(final Gumdrop gumdrop, final NntpClientHandler handler) {
        dial.requireTarget();
        if (user != null && password == null) {
            throw new IllegalStateException("a user name needs a password");
        }
        final TcpTransportFactory transportFactory = new TcpTransportFactory();
        connection = new NntpClientProtocolHandler(handler, user, password, startTls,
                commandTimeoutMs);
        final NntpClientProtocolHandler protocol = connection;
        ClientConnect.discoverEch(gumdrop, secure, dial, tls, new ClientConnect.EchDiscoveryCallback() {
            @Override
            public void discovered(byte[] echConfigList) {
                try {
                    ClientConnect.prepareTls(secure, tls, transportFactory, echConfigList);
                    clientEndpoint = ClientConnect.openAndConnect(gumdrop, dial, transportFactory, protocol);
                } catch (IOException e) {
                    handler.error(e);
                }
            }
        });
    }

    /**
     * Returns whether the connection is open.
     *
     * @return true if it is
     */
    public boolean isOpen() {
        return connection != null && connection.isOpen();
    }

    /** Closes the connection without sending QUIT. */
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
    }

}
