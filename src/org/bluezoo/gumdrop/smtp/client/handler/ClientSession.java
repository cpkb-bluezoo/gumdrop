/*
 * ClientSession.java
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

package org.bluezoo.gumdrop.smtp.client.handler;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

/**
 * Operations available in an established SMTP session.
 * 
 * <p>This interface is provided to the handler after a successful EHLO or HELO
 * command, and represents the "ready" state where the client can:
 * <ul>
 * <li>Begin a mail transaction with {@code mailFrom()}</li>
 * <li>Upgrade to TLS with {@code starttls()} (if offered by server)</li>
 * <li>Authenticate with {@code auth()} (if offered by server)</li>
 * <li>Close the connection with {@code quit()}</li>
 * </ul>
 * 
 * <p>After successful message delivery, the handler receives this interface
 * again, allowing multiple messages to be sent over a single connection.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerEhloReplyHandler#handleEhlo
 * @see ServerHeloReplyHandler#handleHelo
 */
public interface ClientSession {

    /**
     * Begins a mail transaction with the specified sender.
     * 
     * @param sender the envelope sender address
     * @param callback receives the server's response
     */
    void mailFrom(EmailAddress sender, ServerMailFromReplyHandler callback);

    /**
     * Begins a mail transaction with sender and message size hint.
     * 
     * <p>The size parameter is only useful if the server advertised the
     * SIZE extension in its EHLO response. It allows the server to reject
     * messages that would exceed limits before content is transferred.
     * 
     * @param sender the envelope sender address
     * @param size the estimated message size in bytes
     * @param callback receives the server's response
     */
    void mailFrom(EmailAddress sender, long size, ServerMailFromReplyHandler callback);

    /**
     * Upgrades the connection to TLS.
     * 
     * <p>This should only be called if the server advertised STARTTLS in
     * its EHLO response. After successful TLS upgrade, the handler
     * <em>must</em> re-issue EHLO as required by RFC 3207.
     * 
     * @param callback receives the server's response
     */
    void starttls(ServerStarttlsReplyHandler callback);

    /**
     * Initiates SASL authentication.
     * 
     * <p>This should only be called if the server advertised AUTH in its
     * EHLO response. The mechanism should be one of those offered by the
     * server.
     * 
     * @param mechanism the SASL mechanism name (e.g., "PLAIN", "LOGIN", "XOAUTH2")
     * @param initialResponse initial response bytes for mechanisms that support it
     *                        (may be null for mechanisms like LOGIN)
     * @param callback receives the server's response
     */
    void auth(String mechanism, byte[] initialResponse, ServerAuthReplyHandler callback);

    /**
     * Closes the connection gracefully.
     * 
     * <p>Sends a QUIT command and closes the connection. The connection
     * handles the server's 221 response internally.
     */
    void quit();

}

