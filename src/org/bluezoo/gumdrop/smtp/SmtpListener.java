/*
 * SmtpListener.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.util.CidrNetwork;
import java.util.List;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ResourceBundle;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.GssapiServer;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.smtp.server.ClientConnected;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.telemetry.EventLogger;

/**
 * TCP transport listener for SMTP connections.
 * This endpoint supports both standard SMTP (port 25) and submission
 * service (port 587), with transparent SSL/TLS support for SMTPS.
 *
 * <p>SMTP-specific features include:
 * <ul>
 * <li>CIDR-based network filtering (allow/block lists)</li>
 * <li>Connection rate limiting (inherited from TcpListener)</li>
 * <li>Authentication rate limiting (inherited from TcpListener)</li>
 * <li>Optional authentication requirement (MSA mode)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321 - SMTP</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6409">RFC 6409 - Message Submission</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314 - Implicit TLS (port 465)</a>
 */
public class SmtpListener extends TcpListener {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle(SmtpListener.class.getPackage().getName() + ".L10N");

    private EventLogger events() {
        return eventTelemetry().getLogger(SmtpListener.class, L10N);
    }

    /**
     * The default SMTP port (standard mail transfer).
     * @see <a href="https://www.rfc-editor.org/rfc/rfc5321#section-4.5.4.1">RFC 5321 §4.5.4.1</a>
     */
    protected static final int SMTP_DEFAULT_PORT = 25;

    /**
     * The default SMTP submission port (authenticated mail submission).
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6409">RFC 6409</a>
     */
    protected static final int SMTP_SUBMISSION_PORT = 587;

    /**
     * The default SMTPS port (SMTP over SSL/TLS).
     * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314</a>
     */
    protected static final int SMTPS_DEFAULT_PORT = 465;

    protected int port = -1;
    protected long maxMessageSize = 35882577; // ~35MB default, configurable
    protected int maxRecipients = 100;       // RFC 5321 minimum is 100 (RFC 9422 RCPTMAX)
    protected int maxTransactionsPerSession = 0; // 0 = unlimited (RFC 9422 MAILMAX)
    protected Realm realm; // Authentication realm for SMTP AUTH
    protected MailboxFactory mailboxFactory; // Factory for local mailbox delivery

    // Connection filtering and policy settings
    protected boolean authRequired = false; // Force authentication (MSA mode)

    // RFC 4752 — GSSAPI/Kerberos authentication
    protected GssapiServer gssapiServer;

    // Back-reference to the owning server (null when used standalone)
    private org.bluezoo.gumdrop.smtp.server.SmtpServer server;

    // Session pipeline for composed servers (null when using legacy service wiring)
    private org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProvider sessionProvider;

    // Metrics for this endpoint (null if telemetry is not enabled)
    private SmtpServerMetrics metrics;

    /**
     * Returns a short description of this endpoint.
     */
    @Override
    public String getDescription() {
        return secure ? "smtps" : "smtp";
    }

    /**
     * Returns the port number this endpoint is bound to.
     */
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
    public SmtpListener port(int port) {
        this.port = port;
        return this;
    }

    @Override
    public SmtpListener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public SmtpListener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public SmtpListener secure(boolean flag) {
        super.secure(flag);
        return this;
    }

    @Override
    public SmtpListener tls(TlsConfig tls) {
        super.tls(tls);
        return this;
    }

    @Override
    public SmtpListener maxConnections(int max) {
        super.maxConnections(max);
        return this;
    }

    @Override
    public SmtpListener maxConnectionsPerIP(int max) {
        super.maxConnectionsPerIP(max);
        return this;
    }

    @Override
    public SmtpListener rateLimit(String rateLimit) {
        super.rateLimit(rateLimit);
        return this;
    }

    @Override
    public SmtpListener maxAuthFailures(int max) {
        super.maxAuthFailures(max);
        return this;
    }

    @Override
    public SmtpListener authLockoutTimeMs(long lockoutMs) {
        super.authLockoutTimeMs(lockoutMs);
        return this;
    }

    @Override
    public SmtpListener allowedNetworks(List<CidrNetwork> allowedNetworks) {
        super.allowedNetworks(allowedNetworks);
        return this;
    }

    @Override
    public SmtpListener blockedNetworks(List<CidrNetwork> blockedNetworks) {
        super.blockedNetworks(blockedNetworks);
        return this;
    }

    @Override
    public SmtpListener name(String name) {
        super.name(name);
        return this;
    }

    @Override
    public SmtpListener maxNetInSize(int size) {
        super.maxNetInSize(size);
        return this;
    }

    @Override
    public SmtpListener maxNetOutSize(int size) {
        super.maxNetOutSize(size);
        return this;
    }

    @Override
    public SmtpListener idleTimeoutMs(long idleTimeoutMs) {
        super.idleTimeoutMs(idleTimeoutMs);
        return this;
    }

    @Override
    public SmtpListener readTimeoutMs(long readTimeoutMs) {
        super.readTimeoutMs(readTimeoutMs);
        return this;
    }

    @Override
    public SmtpListener connectionTimeoutMs(long connectionTimeoutMs) {
        super.connectionTimeoutMs(connectionTimeoutMs);
        return this;
    }

    @Override
    public SmtpListener maxDtlsPeers(int max) {
        super.maxDtlsPeers(max);
        return this;
    }

    /**
     * Returns the maximum message size in bytes.
     * @return the maximum message size
     */
    public long getMaxMessageSize() {
        return maxMessageSize;
    }

    /**
     * Sets the maximum message size in bytes.
     * @param maxMessageSize the maximum message size
     * @return this
     */
    public SmtpListener maxMessageSize(long maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
        return this;
    }

    /**
     * Returns the maximum number of recipients per transaction (RCPTMAX).
     *
     * <p>Per RFC 5321, servers must accept at least 100 recipients.
     * This value is advertised via the LIMITS extension (RFC 9422).
     *
     * @return the maximum recipients per transaction
     */
    public int getMaxRecipients() {
        return maxRecipients;
    }

    /**
     * Sets the maximum number of recipients per transaction.
     *
     * <p>Per RFC 5321, this must be at least 100. Setting a lower value
     * violates the specification and may cause interoperability issues.
     *
     * @param maxRecipients the maximum recipients (should be at least 100)
     * @return this
     */
    public SmtpListener maxRecipients(int maxRecipients) {
        this.maxRecipients = maxRecipients;
        return this;
    }

    /**
     * Returns the maximum mail transactions per session (MAILMAX).
     *
     * <p>A value of 0 means unlimited. This limits how many MAIL FROM
     * commands can be issued during a single connection.
     *
     * @return the maximum transactions, or 0 for unlimited
     */
    public int getMaxTransactionsPerSession() {
        return maxTransactionsPerSession;
    }

    /**
     * Sets the maximum mail transactions per session.
     *
     * <p>Set to 0 for unlimited. This can help prevent connection abuse
     * where a single connection sends many separate messages.
     *
     * @param maxTransactions the maximum transactions, or 0 for unlimited
     * @return this
     */
    public SmtpListener maxTransactionsPerSession(int maxTransactions) {
        this.maxTransactionsPerSession = maxTransactions;
        return this;
    }

    /**
     * Returns the authentication realm.
     * @return the realm for SMTP authentication, or null if no authentication
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for SMTP AUTH.
     * @param realm the realm to use for authentication
     * @return this
     */
    public SmtpListener realm(Realm realm) {
        this.realm = realm;
        return this;
    }

    /**
     * Returns the GSSAPI server for Kerberos authentication (RFC 4752),
     * or null if GSSAPI is not configured.
     *
     * @return the GSSAPI server, or null
     */
    public GssapiServer getGSSAPIServer() {
        return gssapiServer;
    }

    /**
     * Sets the GSSAPI server for Kerberos authentication (RFC 4752).
     *
     * @param gssapiServer the GSSAPI server
     * @return this
     */
    public SmtpListener gssapiServer(GssapiServer gssapiServer) {
        this.gssapiServer = gssapiServer;
        return this;
    }

    /**
     * Configures GSSAPI/Kerberos authentication (RFC 4752) by creating
     * a {@link GssapiServer} from the specified keytab and service
     * principal.
     *
     * @param keytabPath the path to the Kerberos keytab file
     * @param servicePrincipal the service principal name
     *        (e.g. "smtp/mail.example.com@EXAMPLE.COM")
     * @throws IOException if the keytab cannot be read or credentials
     *         cannot be acquired
     */
    public SmtpListener configureGSSAPI(Path keytabPath, String servicePrincipal)
            throws IOException {
        this.gssapiServer = new GssapiServer(keytabPath, servicePrincipal);
        return this;
    }

    /**
     * Sets the factory for creating mailbox stores for local delivery.
     *
     * <p>If configured, the mailbox factory is passed to handlers when
     * processing RCPT TO commands, allowing them to check if recipients
     * have local mailboxes.
     *
     * @param factory the mailbox factory, or null if not doing local delivery
     * @return this
     */
    public SmtpListener mailboxFactory(MailboxFactory factory) {
        this.mailboxFactory = factory;
        return this;
    }

    /**
     * Returns the configured mailbox factory for local delivery.
     * @return the factory or null if none configured
     */
    public MailboxFactory getMailboxFactory() {
        return mailboxFactory;
    }

    /**
     * Returns whether authentication is required for this endpoint.
     * @return true if AUTH is mandatory, false if optional
     */
    public boolean isAuthRequired() {
        return authRequired;
    }

    /**
     * Sets whether authentication is required.
     * This should be true for Message Submission (port 587), false for MTA (port 25).
     * @param authRequired true to require AUTH command before accepting mail
     * @return this
     */
    public SmtpListener authRequired(boolean authRequired) {
        this.authRequired = authRequired;
        return this;
    }

    /**
     * Starts this endpoint, setting default port if not specified.
     */
    @Override
    public void start() {
        super.start();
        if (port <= 0) {
            port = secure ? SMTPS_DEFAULT_PORT : SMTP_DEFAULT_PORT;
        }
        if (isMetricsEnabled()) {
            metrics = new SmtpServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this endpoint, or null if telemetry is
     * not enabled.
     *
     * @return the SMTP server metrics
     */
    public SmtpServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * Stops this endpoint.
     */
    @Override
    public void stop() {
        super.stop();
    }

    /**
     * Sets the owning server. Called by {@link org.bluezoo.gumdrop.smtp.server.SmtpServer} during
     * wiring.
     *
     * @param server the owning server
     * @return this
     */
    public SmtpListener server(org.bluezoo.gumdrop.smtp.server.SmtpServer server) {
        this.server = server;
        return this;
    }

    /**
     * Returns the owning server, or null if used standalone.
     *
     * @return the owning server
     */
    public org.bluezoo.gumdrop.smtp.server.SmtpServer getServer() {
        return server;
    }

    /**
     * Returns the configured session provider, or {@code null}.
     */
    public org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    /**
     * Sets the session provider. Returns {@code this} for fluent configuration.
     *
     * @param sessionProvider the session provider
     * @return this listener
     */
    public SmtpListener sessionProvider(
            org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
        return this;
    }

    /**
     * Creates a new SmtpProtocolHandler for a newly accepted connection.
     *
     * <p>If a {@link org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProvider}
     * is set (composition path), the handler is obtained from
     * {@link org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProvider#openSession}.
     * Otherwise, if an {@link org.bluezoo.gumdrop.smtp.server.SmtpServer} is set,
     * the handler comes from {@link org.bluezoo.gumdrop.smtp.server.SmtpServer#openSession}.
     *
     * @return a new SMTP endpoint handler
     */
    @Override
    protected ProtocolHandler createHandler() {
        ClientConnected handler = null;
        if (sessionProvider != null) {
            try {
                handler = sessionProvider.openSession(this);
            } catch (Exception e) {
                events().warn("warn.smtp_session_provider_failed").thrown(e).emit();
            }
        } else {
            org.bluezoo.gumdrop.smtp.server.SmtpServer srv = getServer();
            if (srv != null) {
                try {
                    handler = srv.openSession(this);
                } catch (Exception e) {
                    events().warn("warn.smtp_server_session_failed").thrown(e).emit();
                }
            }
        }
        return new SmtpProtocolHandler(this, handler);
    }

    /**
     * Checks if the given client address is authorized to use XCLIENT extension.
     *
     * <p>XCLIENT is a Postfix extension that allows trusted proxies to override
     * client connection information. By default, this is disabled.
     *
     * <p>Override this method to implement XCLIENT authorization based on
     * your network topology (e.g., allow from specific proxy IP addresses).
     *
     * @param clientAddress the client's IP address
     * @return true if XCLIENT is authorized, false otherwise
     */
    protected boolean isXclientAuthorized(InetAddress clientAddress) {
        return false;
    }

    /**
     * Checks if SSL/TLS context is available for STARTTLS.
     * @return true if STARTTLS is supported, false otherwise
     */
    protected boolean isSTARTTLSAvailable() {
        return isTLSConfigured();
    }
}
