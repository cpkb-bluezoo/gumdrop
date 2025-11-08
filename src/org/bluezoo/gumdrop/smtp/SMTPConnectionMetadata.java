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
 * Interface providing metadata about an SMTP connection for policy decisions.
 * This interface exposes relevant information about the client connection
 * that an SMTP handler might need to make informed decisions about sender
 * and recipient policies.
 * <p>
 * The metadata includes:
 * <ul>
 * <li>Network information (client IP, local server details)</li>
 * <li>Security context (SSL/TLS status, client certificates)</li>
 * <li>Authentication state (username, authentication method)</li>
 * <li>Connection properties (secure channel, connection time)</li>
 * </ul>
 * <p>
 * This allows handlers to implement sophisticated policies based on:
 * <ul>
 * <li>Source IP reputation and geolocation</li>
 * <li>Client certificate validation</li>
 * <li>Authentication status and user identity</li>
 * <li>Connection security and encryption</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SMTPConnectionHandler
 */
public interface SMTPConnectionMetadata {

    /**
     * Returns the client's remote socket address.
     * Useful for IP-based policies, geolocation, and reputation checks.
     * 
     * @return the client socket address
     */
    InetSocketAddress getClientAddress();

    /**
     * Returns the server's local socket address.
     * Useful for determining which connector/port received the connection.
     * 
     * @return the server socket address
     */
    InetSocketAddress getServerAddress();

    /**
     * Returns whether the connection is encrypted with SSL/TLS.
     * 
     * @return true if the connection uses SSL/TLS encryption
     */
    boolean isSecure();

    /**
     * Returns the client's X.509 certificates if provided.
     * Only available for secure connections with client certificate authentication.
     * 
     * @return client certificates or null if none provided
     */
    X509Certificate[] getClientCertificates();

    /**
     * Returns the TLS cipher suite in use.
     * Only available for secure connections.
     * 
     * @return cipher suite name or null if connection is not secure
     */
    String getCipherSuite();

    /**
     * Returns the TLS protocol version in use.
     * Only available for secure connections.
     * 
     * @return protocol version (e.g., "TLSv1.3") or null if not secure
     */
    String getProtocolVersion();

    /**
     * Returns the timestamp when the connection was established.
     * Useful for connection duration tracking and timeout policies.
     * 
     * @return connection establishment time in milliseconds since epoch
     */
    long getConnectionTimeMillis();

    /**
     * Returns the connection duration in milliseconds.
     * 
     * @return milliseconds since connection was established
     */
    default long getConnectionDurationMillis() {
        return System.currentTimeMillis() - getConnectionTimeMillis();
    }

}
