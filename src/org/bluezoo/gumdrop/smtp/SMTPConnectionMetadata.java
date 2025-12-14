/*
 * SMTPConnectionMetadata.java
 * Copyright (C) 2025 Chris Burdess
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

import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

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

    /**
     * Returns the DSN envelope parameters from the MAIL FROM command.
     * 
     * <p>These parameters indicate how the sender wants Delivery Status
     * Notifications handled for this message:
     * <ul>
     * <li><b>RET</b> - Whether to return the full message or just headers in DSNs</li>
     * <li><b>ENVID</b> - An opaque envelope identifier for correlation</li>
     * </ul>
     * 
     * @return DSN envelope parameters, or null if none were specified
     * @see DSNEnvelopeParameters
     */
    DSNEnvelopeParameters getDSNEnvelopeParameters();

    /**
     * Returns the DSN recipient parameters for a specific recipient.
     * 
     * <p>These parameters indicate when to send Delivery Status Notifications
     * for this recipient:
     * <ul>
     * <li><b>NOTIFY</b> - When to send DSNs (NEVER, SUCCESS, FAILURE, DELAY)</li>
     * <li><b>ORCPT</b> - The original recipient address before any forwarding</li>
     * </ul>
     * 
     * @param recipient the recipient to get DSN parameters for
     * @return DSN recipient parameters, or null if none were specified
     * @see DSNRecipientParameters
     */
    DSNRecipientParameters getDSNRecipientParameters(EmailAddress recipient);

    /**
     * Returns whether REQUIRETLS was specified for this message.
     * 
     * <p>When true, the sender has indicated that this message must only be
     * transmitted over TLS-protected connections (RFC 8689). If the server
     * cannot guarantee TLS protection to the next hop during relay, the
     * message should be bounced rather than delivered insecurely.
     * 
     * @return true if REQUIRETLS was specified in MAIL FROM
     */
    boolean isRequireTls();

}
