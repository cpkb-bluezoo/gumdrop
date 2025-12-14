/*
 * HelloHandler.java
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

package org.bluezoo.gumdrop.smtp.handler;

import org.bluezoo.gumdrop.TLSInfo;

import java.security.Principal;

/**
 * Handler for client greeting commands (HELO/EHLO) and pre-mail commands.
 * 
 * <p>This handler is active after the connection is accepted and waiting
 * for the client to identify itself. It receives HELO/EHLO commands and
 * authentication notifications.
 * 
 * <p>After accepting a greeting with {@link HelloState#acceptHello}, the
 * handler transitions to {@link MailFromHandler} where MAIL FROM commands
 * can be received.
 * 
 * <h4>STARTTLS</h4>
 * <p>STARTTLS is handled automatically by the connection when available.
 * If the client requests STARTTLS and TLS is configured, the connection
 * performs the TLS handshake and then:
 * <ol>
 *   <li>Calls {@link #tlsEstablished(TLSInfo)} with session details</li>
 *   <li>Awaits the required post-TLS EHLO (RFC 3207)</li>
 *   <li>Calls {@link #hello} with the new EHLO</li>
 * </ol>
 * 
 * <h4>Authentication</h4>
 * <p>SASL authentication mechanics are handled by the connection. After
 * successful authentication, {@link #authenticated} is called with the
 * authenticated Principal for policy decision.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HelloState
 * @see ConnectedState#acceptConnection
 */
public interface HelloHandler {

    /**
     * Called when the client sends HELO or EHLO.
     * 
     * <p>The handler should evaluate the client's hostname and decide
     * whether to accept or reject the greeting. Accepting transitions
     * to the mail-from ready state.
     * 
     * @param extended true for EHLO (Extended SMTP), false for HELO
     * @param hostname the client's announced hostname
     * @param state operations for responding to the greeting
     */
    void hello(boolean extended, String hostname, HelloState state);

    /**
     * Called when TLS is established after STARTTLS.
     * 
     * <p>STARTTLS is handled automatically by the connection. This
     * notification allows the handler to log the TLS upgrade or
     * adjust policy based on TLS parameters.
     * 
     * <p>After this call, the client must re-issue EHLO per RFC 3207,
     * which will arrive via {@link #hello}.
     * 
     * @param tlsInfo information about the TLS session
     */
    void tlsEstablished(TLSInfo tlsInfo);

    /**
     * Called after successful SASL authentication.
     * 
     * <p>The connection handles the SASL exchange (challenges, responses).
     * This method is called after successful authentication with the
     * authenticated identity.
     * 
     * <p>The handler should decide whether to accept this principal
     * for sending mail.
     * 
     * @param principal the authenticated identity
     * @param state operations for accepting or rejecting
     */
    void authenticated(Principal principal, AuthenticateState state);

    /**
     * Called when the client sends QUIT.
     * 
     * <p>The connection will send a 221 response and close.
     * {@link ClientConnected#disconnected()} will be called.
     */
    void quit();

}
