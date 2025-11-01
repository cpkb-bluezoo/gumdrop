/*
 * SMTPConnectionMetadata.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.smtp;

import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;

/**
 * Metadata about an SMTP connection providing context for policy decisions.
 * This class encapsulates all relevant information about the client connection
 * that an SMTP handler might need to make informed decisions about sender
 * and recipient policies.
 * <p>
 * The metadata includes:
 * <ul>
 * <li>Network information (client IP, local server details)</li>
 * <li>Security context (SSL/TLS status, client certificates)</li>
 * <li>Authentication state (username, authentication method)</li>
 * <li>Protocol information (HELO/EHLO greeting, capabilities used)</li>
 * <li>Connection properties (secure channel, connection time)</li>
 * </ul>
 * <p>
 * This allows handlers to implement sophisticated policies based on:
 * <ul>
 * <li>Source IP reputation and geolocation</li>
 * <li>Client certificate validation</li>
 * <li>Authentication status and user identity</li>
 * <li>Connection security and encryption</li>
 * <li>Protocol compliance and behavior</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SMTPConnectionHandler
 */
public class SMTPConnectionMetadata {

    private final InetSocketAddress clientAddress;
    private final InetSocketAddress serverAddress;
    private final boolean secure;
    private final X509Certificate[] clientCertificates;
    private final String cipherSuite;
    private final String protocolVersion;
    
    private final boolean isAuthenticated;
    private final String authenticatedUser;
    private final String authenticationMethod;
    
    private final String heloHostname;
    private final boolean isExtendedSMTP;
    private final long connectionTimeMillis;
    private final String connectorDescription;

    /**
     * Creates SMTP connection metadata.
     * 
     * @param clientAddress the remote client socket address
     * @param serverAddress the local server socket address
     * @param secure true if connection is encrypted (SSL/TLS)
     * @param clientCertificates client X.509 certificates (null if none)
     * @param cipherSuite TLS cipher suite in use (null if not secure)
     * @param protocolVersion TLS protocol version (null if not secure)
     * @param isAuthenticated true if client has authenticated
     * @param authenticatedUser username if authenticated (null otherwise)
     * @param authenticationMethod AUTH method used (null if not authenticated)
     * @param heloHostname hostname from HELO/EHLO command
     * @param isExtendedSMTP true if EHLO was used (supports extensions)
     * @param connectionTimeMillis timestamp when connection was established
     * @param connectorDescription description of the SMTP connector
     */
    public SMTPConnectionMetadata(
            InetSocketAddress clientAddress,
            InetSocketAddress serverAddress,
            boolean secure,
            X509Certificate[] clientCertificates,
            String cipherSuite,
            String protocolVersion,
            boolean isAuthenticated,
            String authenticatedUser,
            String authenticationMethod,
            String heloHostname,
            boolean isExtendedSMTP,
            long connectionTimeMillis,
            String connectorDescription) {
        
        this.clientAddress = clientAddress;
        this.serverAddress = serverAddress;
        this.secure = secure;
        this.clientCertificates = clientCertificates != null ? clientCertificates.clone() : null;
        this.cipherSuite = cipherSuite;
        this.protocolVersion = protocolVersion;
        this.isAuthenticated = isAuthenticated;
        this.authenticatedUser = authenticatedUser;
        this.authenticationMethod = authenticationMethod;
        this.heloHostname = heloHostname;
        this.isExtendedSMTP = isExtendedSMTP;
        this.connectionTimeMillis = connectionTimeMillis;
        this.connectorDescription = connectorDescription;
    }

    /**
     * Returns the client's remote socket address.
     * Useful for IP-based policies, geolocation, and reputation checks.
     * 
     * @return the client socket address
     */
    public InetSocketAddress getClientAddress() {
        return clientAddress;
    }

    /**
     * Returns the server's local socket address.
     * Useful for determining which connector/port received the connection.
     * 
     * @return the server socket address
     */
    public InetSocketAddress getServerAddress() {
        return serverAddress;
    }

    /**
     * Returns whether the connection is encrypted with SSL/TLS.
     * 
     * @return true if the connection uses SSL/TLS encryption
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Returns the client's X.509 certificates if provided.
     * Only available for secure connections with client certificate authentication.
     * 
     * @return client certificates or null if none provided
     */
    public X509Certificate[] getClientCertificates() {
        return clientCertificates != null ? clientCertificates.clone() : null;
    }

    /**
     * Returns the TLS cipher suite in use.
     * Only available for secure connections.
     * 
     * @return cipher suite name or null if connection is not secure
     */
    public String getCipherSuite() {
        return cipherSuite;
    }

    /**
     * Returns the TLS protocol version in use.
     * Only available for secure connections.
     * 
     * @return protocol version (e.g., "TLSv1.3") or null if not secure
     */
    public String getProtocolVersion() {
        return protocolVersion;
    }

    /**
     * Returns whether the client has successfully authenticated.
     * 
     * @return true if client is authenticated via SMTP AUTH
     */
    public boolean isAuthenticated() {
        return isAuthenticated;
    }

    /**
     * Returns the authenticated username.
     * Only available if client has successfully authenticated.
     * 
     * @return username or null if not authenticated
     */
    public String getAuthenticatedUser() {
        return authenticatedUser;
    }

    /**
     * Returns the authentication method used.
     * Only available if client has successfully authenticated.
     * 
     * @return authentication method (e.g., "PLAIN", "LOGIN") or null
     */
    public String getAuthenticationMethod() {
        return authenticationMethod;
    }

    /**
     * Returns the hostname provided in HELO/EHLO command.
     * This may not be the actual client hostname and should be validated
     * against reverse DNS if hostname verification is required.
     * 
     * @return HELO/EHLO hostname or null if not yet provided
     */
    public String getHeloHostname() {
        return heloHostname;
    }

    /**
     * Returns whether Extended SMTP (ESMTP) is being used.
     * True if client sent EHLO command, false for HELO command.
     * ESMTP indicates support for protocol extensions.
     * 
     * @return true if using ESMTP (EHLO command)
     */
    public boolean isExtendedSMTP() {
        return isExtendedSMTP;
    }

    /**
     * Returns the timestamp when the connection was established.
     * Useful for connection duration tracking and timeout policies.
     * 
     * @return connection establishment time in milliseconds since epoch
     */
    public long getConnectionTimeMillis() {
        return connectionTimeMillis;
    }

    /**
     * Returns a description of the SMTP connector that accepted this connection.
     * Useful for distinguishing between different server configurations
     * (e.g., "smtp" vs "smtps" vs "submission").
     * 
     * @return connector description
     */
    public String getConnectorDescription() {
        return connectorDescription;
    }

    /**
     * Returns the connection duration in milliseconds.
     * 
     * @return milliseconds since connection was established
     */
    public long getConnectionDurationMillis() {
        return System.currentTimeMillis() - connectionTimeMillis;
    }

    /**
     * Convenience method to check if this is a submission port connection.
     * Typically submission ports (587, 465) require authentication.
     * 
     * @return true if connected to a typical submission port
     */
    public boolean isSubmissionPort() {
        int port = serverAddress.getPort();
        return port == 587 || port == 465;
    }

    /**
     * Convenience method to check if this is a standard SMTP port connection.
     * Port 25 is typically used for server-to-server mail transfer.
     * 
     * @return true if connected to port 25
     */
    public boolean isStandardSMTPPort() {
        return serverAddress.getPort() == 25;
    }

    @Override
    public String toString() {
        return String.format("SMTPConnectionMetadata{client=%s, server=%s, secure=%s, auth=%s/%s, helo=%s, esmtp=%s}",
                clientAddress, serverAddress, secure, isAuthenticated, authenticatedUser, heloHostname, isExtendedSMTP);
    }

}
