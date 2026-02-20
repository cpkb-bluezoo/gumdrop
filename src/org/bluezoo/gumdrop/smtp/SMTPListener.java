/*
 * SMTPListener.java
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

import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;


/**
 * TCP transport listener for SMTP connections.
 * This endpoint supports both standard SMTP (port 25) and submission
 * service (port 587), with transparent SSL/TLS support for SMTPS.
 *
 * <p>SMTP-specific features include:
 * <ul>
 * <li>CIDR-based network filtering (allow/block lists)</li>
 * <li>Connection rate limiting (inherited from TCPListener)</li>
 * <li>Authentication rate limiting (inherited from TCPListener)</li>
 * <li>Optional authentication requirement (MSA mode)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321 - SMTP</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6409">RFC 6409 - Message Submission</a>
 */
public class SMTPListener extends TCPListener {

    private static final Logger LOGGER =
            Logger.getLogger(SMTPListener.class.getName());

    /**
     * The default SMTP port (standard mail transfer).
     */
    protected static final int SMTP_DEFAULT_PORT = 25;

    /**
     * The default SMTP submission port (authenticated mail submission).
     */
    protected static final int SMTP_SUBMISSION_PORT = 587;

    /**
     * The default SMTPS port (SMTP over SSL/TLS).
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

    // Back-reference to the owning service (null when used standalone)
    private SMTPService service;

    // Metrics for this endpoint (null if telemetry is not enabled)
    private SMTPServerMetrics metrics;

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
     * Sets the port number this endpoint should bind to.
     * @param port the port number
     */
    public void setPort(int port) {
        this.port = port;
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
     */
    public void setMaxMessageSize(long maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
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
     */
    public void setMaxRecipients(int maxRecipients) {
        this.maxRecipients = maxRecipients;
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
     */
    public void setMaxTransactionsPerSession(int maxTransactions) {
        this.maxTransactionsPerSession = maxTransactions;
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
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    /**
     * Sets the factory for creating mailbox stores for local delivery.
     *
     * <p>If configured, the mailbox factory is passed to handlers when
     * processing RCPT TO commands, allowing them to check if recipients
     * have local mailboxes.
     *
     * @param factory the mailbox factory, or null if not doing local delivery
     */
    public void setMailboxFactory(MailboxFactory factory) {
        this.mailboxFactory = factory;
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
     */
    public void setAuthRequired(boolean authRequired) {
        this.authRequired = authRequired;
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
            metrics = new SMTPServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this endpoint, or null if telemetry is
     * not enabled.
     *
     * @return the SMTP server metrics
     */
    public SMTPServerMetrics getMetrics() {
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
     * Sets the owning service. Called by {@link SMTPService} during
     * wiring.
     *
     * @param service the owning service
     */
    void setService(SMTPService service) {
        this.service = service;
    }

    /**
     * Returns the owning service, or null if used standalone.
     *
     * @return the owning service
     */
    public SMTPService getService() {
        return service;
    }

    /**
     * Creates a new SMTPProtocolHandler for a newly accepted connection.
     *
     * <p>If an {@link SMTPService} is set, the handler is obtained from
     * the service's {@link SMTPService#createHandler(org.bluezoo.gumdrop.TCPListener)}
     * method.
     *
     * @return a new SMTP endpoint handler
     */
    @Override
    protected ProtocolHandler createHandler() {
        ClientConnected handler = null;
        if (service != null) {
            try {
                handler = service.createHandler(this);
            } catch (Exception e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING,
                            "Failed to create SMTP handler from service,"
                                    + " using default behaviour", e);
                }
            }
        }
        return new SMTPProtocolHandler(this, handler);
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
    protected boolean isXclientAuthorized(java.net.InetAddress clientAddress) {
        return false;
    }

    /**
     * Checks if SSL/TLS context is available for STARTTLS.
     * @return true if STARTTLS is supported, false otherwise
     */
    protected boolean isSTARTTLSAvailable() {
        return context != null;
    }
}
