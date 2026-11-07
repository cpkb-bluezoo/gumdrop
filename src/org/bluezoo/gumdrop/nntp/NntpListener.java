/*
 * NntpListener.java
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

package org.bluezoo.gumdrop.nntp;

import java.net.InetAddress;
import java.util.List;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.nntp.server.ClientConnected;
import org.bluezoo.gumdrop.nntp.server.NntpServer;
import org.bluezoo.gumdrop.nntp.server.NntpServerSessionProvider;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.util.CidrNetwork;

/**
 * TCP transport listener for NNTP connections. It serves the standard
 * NNTP port (119, cleartext or with STARTTLS) and NNTPS (563, implicit TLS).
 *
 * <p>The server is a news reader server: it implements the reader
 * commands of RFC 3977. Newsgroups are the mailboxes of a
 * {@link MailboxFactory}'s store, and articles are their messages.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3977">RFC 3977 - NNTP</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8143">RFC 8143 - NNTP with TLS</a>
 */
public class NntpListener extends TcpListener {

    private EventLogger events() {
        return eventTelemetry().getLogger(NntpListener.class, NntpProtocolHandler.L10N);
    }

    /** The default NNTP port (RFC 3977 section 1). */
    protected static final int NNTP_DEFAULT_PORT = 119;

    /** The default NNTPS port (RFC 8143 section 3, implicit TLS). */
    protected static final int NNTPS_DEFAULT_PORT = 563;

    /** The default user whose store serves unauthenticated readers. */
    static final String DEFAULT_ANONYMOUS_USER = "news";

    protected int port = -1;
    protected Realm realm;
    protected MailboxFactory mailboxFactory;
    protected String anonymousUser = DEFAULT_ANONYMOUS_USER;
    protected boolean postingAllowed = false;
    protected boolean authenticationRequired = false;

    // Metrics for this listener (null if telemetry is not enabled)
    private NntpServerMetrics metrics;

    private NntpServer server;
    private NntpServerSessionProvider sessionProvider;

    @Override
    public String getDescription() {
        return secure ? "nntps" : "nntp";
    }

    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the port. Returns {@code this} for fluent configuration.
     *
     * @param port the port number
     * @return this listener
     */
    public NntpListener port(int port) {
        this.port = port;
        return this;
    }

    @Override
    public NntpListener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public NntpListener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public NntpListener secure(boolean flag) {
        super.secure(flag);
        return this;
    }

    @Override
    public NntpListener tls(TlsConfig tls) {
        super.tls(tls);
        return this;
    }

    @Override
    public NntpListener maxConnections(int max) {
        super.maxConnections(max);
        return this;
    }

    @Override
    public NntpListener maxConnectionsPerIP(int max) {
        super.maxConnectionsPerIP(max);
        return this;
    }

    @Override
    public NntpListener rateLimit(String rateLimit) {
        super.rateLimit(rateLimit);
        return this;
    }

    @Override
    public NntpListener maxAuthFailures(int max) {
        super.maxAuthFailures(max);
        return this;
    }

    @Override
    public NntpListener authLockoutTimeMs(long lockoutMs) {
        super.authLockoutTimeMs(lockoutMs);
        return this;
    }

    @Override
    public NntpListener allowedNetworks(List<CidrNetwork> allowedNetworks) {
        super.allowedNetworks(allowedNetworks);
        return this;
    }

    @Override
    public NntpListener blockedNetworks(List<CidrNetwork> blockedNetworks) {
        super.blockedNetworks(blockedNetworks);
        return this;
    }

    @Override
    public NntpListener name(String name) {
        super.name(name);
        return this;
    }

    @Override
    public NntpListener maxNetInSize(int size) {
        super.maxNetInSize(size);
        return this;
    }

    @Override
    public NntpListener maxNetOutSize(int size) {
        super.maxNetOutSize(size);
        return this;
    }

    @Override
    public NntpListener idleTimeoutMs(long idleTimeoutMs) {
        super.idleTimeoutMs(idleTimeoutMs);
        return this;
    }

    @Override
    public NntpListener readTimeoutMs(long readTimeoutMs) {
        super.readTimeoutMs(readTimeoutMs);
        return this;
    }

    @Override
    public NntpListener connectionTimeoutMs(long connectionTimeoutMs) {
        super.connectionTimeoutMs(connectionTimeoutMs);
        return this;
    }

    @Override
    public NntpListener maxDtlsPeers(int max) {
        super.maxDtlsPeers(max);
        return this;
    }

    /**
     * Returns the authentication realm.
     *
     * @return the realm for AUTHINFO, or null if there is none
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for AUTHINFO.
     *
     * @param realm the realm
     * @return this
     */
    public NntpListener realm(Realm realm) {
        this.realm = realm;
        return this;
    }

    /**
     * Returns the mailbox factory whose store holds the newsgroups.
     *
     * @return the factory, or null if not configured
     */
    public MailboxFactory getMailboxFactory() {
        return mailboxFactory;
    }

    /**
     * Sets the mailbox factory. The store it creates is opened for the
     * authenticated user, or for the {@link #anonymousUser anonymous user}
     * when the reader has not authenticated.
     *
     * @param mailboxFactory the factory
     * @return this
     */
    public NntpListener mailboxFactory(MailboxFactory mailboxFactory) {
        this.mailboxFactory = mailboxFactory;
        return this;
    }

    /**
     * Returns the user whose store serves unauthenticated readers.
     *
     * @return the user name
     */
    public String getAnonymousUser() {
        return anonymousUser;
    }

    /**
     * Sets the user whose store serves unauthenticated readers. The default
     * is {@code news}.
     *
     * @param anonymousUser the user name
     * @return this
     */
    public NntpListener anonymousUser(String anonymousUser) {
        this.anonymousUser = anonymousUser;
        return this;
    }

    /**
     * Returns whether the server tells readers they may post.
     *
     * @return true if posting is advertised
     */
    public boolean isPostingAllowed() {
        return postingAllowed;
    }

    /**
     * Sets whether the server tells readers they may post (the 200 greeting
     * and the POST capability).
     *
     * @param postingAllowed true to allow posting
     * @return this
     */
    public NntpListener postingAllowed(boolean postingAllowed) {
        this.postingAllowed = postingAllowed;
        return this;
    }

    /**
     * Returns whether clients must authenticate before reading news.
     *
     * @return true if authentication is required
     */
    public boolean isAuthenticationRequired() {
        return authenticationRequired;
    }

    /**
     * Sets whether clients must authenticate (AUTHINFO, RFC 4643) before
     * they may read or post news; commands other than CAPABILITIES, MODE,
     * HELP, QUIT, STARTTLS and AUTHINFO are refused with 480 until they do.
     * Requires a {@link #realm(Realm) realm}. Default false.
     *
     * @param authenticationRequired true to require authentication
     * @return this listener
     */
    public NntpListener authenticationRequired(boolean authenticationRequired) {
        this.authenticationRequired = authenticationRequired;
        return this;
    }

    @Override
    public void start() {
        super.start();
        if (port <= 0) {
            port = secure ? NNTPS_DEFAULT_PORT : NNTP_DEFAULT_PORT;
        }

        if (isMetricsEnabled()) {
            metrics = new NntpServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Sets the owning server. Called by {@link NntpServer} during wiring.
     *
     * @param server the owning server
     * @return this
     */
    /**
     * Returns the metrics of this listener, or null if metrics are not
     * enabled in the telemetry configuration.
     *
     * @return the metrics
     */
    public NntpServerMetrics getMetrics() {
        return metrics;
    }

    public NntpListener server(NntpServer server) {
        this.server = server;
        return this;
    }

    /**
     * Returns the owning server, or null if used standalone.
     *
     * @return the owning server
     */
    public NntpServer getServer() {
        return server;
    }

    /**
     * Returns the session provider.
     *
     * @return the provider, or null
     */
    public NntpServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    /**
     * Sets the session provider.
     *
     * @param sessionProvider the provider
     * @return this
     */
    public NntpListener sessionProvider(NntpServerSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
        return this;
    }

    /**
     * Opens the application handler pipeline for a new connection.
     *
     * @return the handler, or {@code null} for default protocol behaviour
     */
    public ClientConnected openApplicationSession() {
        if (sessionProvider != null) {
            try {
                return sessionProvider.openSession(this);
            } catch (Exception e) {
                events().warn("warn.failed_create_handler_from_session_provider").thrown(e).emit();
            }
        }
        NntpServer srv = getServer();
        if (srv != null) {
            try {
                return srv.openSession(this);
            } catch (Exception e) {
                events().warn("warn.failed_create_handler_from_server").thrown(e).emit();
            }
        }
        return null;
    }

    @Override
    protected ProtocolHandler createHandler() {
        return new NntpProtocolHandler(this);
    }

    /**
     * Checks if a TLS context is available for STARTTLS.
     * RFC 4642 section 2.2.
     *
     * @return true if STARTTLS is supported
     */
    protected boolean isSTARTTLSAvailable() {
        return isTLSConfigured();
    }

}
