/*
 * POP3Listener.java
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

package org.bluezoo.gumdrop.pop3;

import java.util.logging.Logger;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
/**
 * TCP transport listener for POP3 connections.
 * This endpoint supports both standard POP3 (port 110) and POP3S (port 995),
 * with transparent SSL/TLS support and STARTTLS capability.
 *
 * <p>POP3 is defined in RFC 1939 with extensions in:
 * <ul>
 *   <li>RFC 1957 - Implementation notes and recommendations
 *   <li>RFC 2449 - POP3 Extension Mechanism
 *   <li>RFC 2595 - TLS for POP3 (STLS command)
 *   <li>RFC 6816 - POP3 Support for UTF-8
 *   <li>RFC 8314 - Cleartext considered obsolete (use TLS)
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1939">RFC 1939 - POP3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2449">RFC 2449 - POP3 Extensions</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2595">RFC 2595 - STLS</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6816">RFC 6816 - UTF-8</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314 - TLS</a>
 */
public class POP3Listener extends TCPListener {

    private static final Logger LOGGER =
            Logger.getLogger(POP3Listener.class.getName());

    /**
     * The default POP3 port (cleartext or with STARTTLS).
     */
    protected static final int POP3_DEFAULT_PORT = 110;

    /**
     * The default POP3S port (implicit TLS).
     */
    protected static final int POP3S_DEFAULT_PORT = 995;

    protected int port = -1;
    protected Realm realm;
    protected MailboxFactory mailboxFactory;
    protected long loginDelayMs = 0;
    protected long transactionTimeoutMs = 600000; // 10 minutes default
    protected boolean enableAPOP = true;
    protected boolean enableUTF8 = true;
    protected boolean enablePipelining = false;

    // Back-reference to the owning service (null when used standalone)
    private POP3Service service;

    // Metrics for this endpoint (null if telemetry is not enabled)
    private POP3ServerMetrics metrics;

    /**
     * Returns a short description of this endpoint.
     */
    @Override
    public String getDescription() {
        return secure ? "pop3s" : "pop3";
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
     *
     * @param port the port number
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Returns the authentication realm.
     *
     * @return the realm for POP3 authentication, or null if no authentication
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for POP3 authentication.
     *
     * @param realm the realm to use for authentication
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    /**
     * Returns the mailbox factory for this endpoint.
     *
     * @return the mailbox factory, or null if not configured
     */
    public MailboxFactory getMailboxFactory() {
        return mailboxFactory;
    }

    /**
     * Sets the mailbox factory for creating mailbox instances.
     * Each POP3 connection will use this factory to obtain a mailbox
     * for the authenticated user.
     *
     * @param mailboxFactory the factory to create mailbox instances
     */
    public void setMailboxFactory(MailboxFactory mailboxFactory) {
        this.mailboxFactory = mailboxFactory;
    }

    /**
     * Returns the login delay in milliseconds.
     * RFC 2449 section 4 recommends a minimum delay between failed
     * authentication attempts to slow down brute-force attacks.
     *
     * @return the login delay in milliseconds
     */
    public long getLoginDelayMs() {
        return loginDelayMs;
    }

    /**
     * Sets the login delay in milliseconds.
     * This is enforced after failed authentication attempts.
     *
     * @param loginDelayMs the delay in milliseconds (0 to disable)
     */
    public void setLoginDelayMs(long loginDelayMs) {
        this.loginDelayMs = loginDelayMs;
    }

    /**
     * Sets the login delay using a string with optional time unit suffix.
     * Supported suffixes: ms, s, m, h (milliseconds, seconds, minutes,
     * hours).
     *
     * @param delay the delay string (e.g., "5s", "500ms")
     */
    public void setLoginDelay(String delay) {
        this.loginDelayMs = parseTimeoutString(delay);
    }

    /**
     * Returns the transaction timeout in milliseconds.
     * RFC 1939 section 3 recommends a minimum 10-minute timeout.
     *
     * @return the timeout in milliseconds
     */
    public long getTransactionTimeoutMs() {
        return transactionTimeoutMs;
    }

    /**
     * Sets the transaction timeout in milliseconds.
     *
     * @param transactionTimeoutMs the timeout in milliseconds
     */
    public void setTransactionTimeoutMs(long transactionTimeoutMs) {
        this.transactionTimeoutMs = transactionTimeoutMs;
    }

    /**
     * Sets the transaction timeout using a string with optional time
     * unit suffix.
     *
     * @param timeout the timeout string (e.g., "10m", "600s")
     */
    public void setTransactionTimeout(String timeout) {
        this.transactionTimeoutMs = parseTimeoutString(timeout);
    }

    /**
     * Parses a timeout string with optional time unit suffix.
     *
     * @param timeout the timeout string (e.g., "30s", "5m", "1h",
     *                "5000ms")
     * @return the timeout in milliseconds
     */
    private long parseTimeoutString(String timeout) {
        if (timeout == null || timeout.isEmpty()) {
            return 0;
        }
        timeout = timeout.trim().toLowerCase();

        long multiplier = 1;
        String numPart = timeout;

        if (timeout.endsWith("ms")) {
            numPart = timeout.substring(0, timeout.length() - 2);
            multiplier = 1;
        } else if (timeout.endsWith("s")) {
            numPart = timeout.substring(0, timeout.length() - 1);
            multiplier = 1000;
        } else if (timeout.endsWith("m")) {
            numPart = timeout.substring(0, timeout.length() - 1);
            multiplier = 60 * 1000;
        } else if (timeout.endsWith("h")) {
            numPart = timeout.substring(0, timeout.length() - 1);
            multiplier = 60 * 60 * 1000;
        }

        try {
            return Long.parseLong(numPart.trim()) * multiplier;
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid timeout format: " + timeout);
            return 0;
        }
    }

    /**
     * Returns whether APOP authentication is enabled.
     *
     * @return true if APOP is enabled
     */
    public boolean isEnableAPOP() {
        return enableAPOP;
    }

    /**
     * Sets whether APOP authentication is enabled.
     * APOP provides challenge-response authentication without
     * sending passwords in cleartext.
     *
     * @param enableAPOP true to enable APOP
     */
    public void setEnableAPOP(boolean enableAPOP) {
        this.enableAPOP = enableAPOP;
    }

    /**
     * Returns whether UTF-8 support is enabled (RFC 6816).
     *
     * @return true if UTF-8 is enabled
     */
    public boolean isEnableUTF8() {
        return enableUTF8;
    }

    /**
     * Sets whether UTF-8 support is enabled.
     * When enabled, the endpoint advertises UTF8 capability.
     *
     * @param enableUTF8 true to enable UTF-8
     */
    public void setEnableUTF8(boolean enableUTF8) {
        this.enableUTF8 = enableUTF8;
    }

    /**
     * Returns whether command pipelining is enabled (RFC 2449
     * section 6.8).
     *
     * @return true if pipelining is enabled
     */
    public boolean isEnablePipelining() {
        return enablePipelining;
    }

    /**
     * Sets whether command pipelining is enabled.
     * When enabled, clients can send multiple commands without
     * waiting for responses.
     *
     * @param enablePipelining true to enable pipelining
     */
    public void setEnablePipelining(boolean enablePipelining) {
        this.enablePipelining = enablePipelining;
    }

    /**
     * Starts this endpoint, setting default port if not specified.
     */
    @Override
    public void start() {
        super.start();
        if (port <= 0) {
            port = secure ? POP3S_DEFAULT_PORT : POP3_DEFAULT_PORT;
        }

        if (mailboxFactory == null) {
            LOGGER.warning("No mailbox factory configured"
                    + " - POP3 endpoint will not be functional");
        }

        if (isMetricsEnabled()) {
            metrics = new POP3ServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this endpoint, or null if telemetry is
     * not enabled.
     *
     * @return the POP3 server metrics
     */
    public POP3ServerMetrics getMetrics() {
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
     * Sets the owning service. Called by {@link POP3Service} during
     * wiring.
     *
     * @param service the owning service
     */
    void setService(POP3Service service) {
        this.service = service;
    }

    /**
     * Returns the owning service, or null if used standalone.
     *
     * @return the owning service
     */
    public POP3Service getService() {
        return service;
    }

    /**
     * Creates a new POP3ProtocolHandler for a newly accepted
     * connection.
     *
     * @return a new POP3 endpoint handler
     */
    @Override
    protected ProtocolHandler createHandler() {
        return new POP3ProtocolHandler(this);
    }

    /**
     * Checks if SSL/TLS context is available for STARTTLS.
     *
     * @return true if STARTTLS is supported, false otherwise
     */
    protected boolean isSTARTTLSAvailable() {
        return context != null;
    }

}
