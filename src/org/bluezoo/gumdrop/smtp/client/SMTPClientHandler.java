/*
 * SMTPClientHandler.java
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

package org.bluezoo.gumdrop.smtp.client;

import org.bluezoo.gumdrop.ClientHandler;

/**
 * Handler interface for SMTP client connections.
 * 
 * <p>This interface extends {@link ClientHandler} to provide SMTP-specific
 * event handling for client connections. Implementations drive the SMTP
 * protocol by responding to server greetings, replies, and connection events,
 * and can send commands back to the server through the provided connection.
 * 
 * <p>The handler follows an event-driven pattern similar to server-side
 * handlers, where all protocol interaction happens in response to network
 * events rather than through imperative API calls.
 * 
 * <p><strong>Protocol Flow:</strong>
 * <ol>
 * <li>{@link #onConnected()} - TCP connection established</li>
 * <li>{@link #onGreeting(SMTPResponse, SMTPClientConnection)} - Server greeting received</li>
 * <li>{@link #onReply(SMTPResponse, SMTPClientConnection)} - Command responses received</li>
 * <li>{@link #onDisconnected()} - Connection closed</li>
 * </ol>
 * 
 * <p><strong>Thread Safety:</strong> All handler methods are called from the
 * connection's executor thread and should not perform blocking operations.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientHandler
 * @see SMTPClientConnection
 */
public interface SMTPClientHandler extends ClientHandler {

    /**
     * Called when the SMTP server sends its initial greeting message.
     * 
     * <p>This method is invoked immediately after the TCP connection is
     * established and the server sends its 220 greeting response. The handler
     * can examine the greeting to determine server capabilities and initiate
     * the appropriate handshake (HELO/EHLO).
     * 
     * <p>The connection parameter provides access to command methods such as
     * {@code connection.helo(hostname)} or {@code connection.ehlo(hostname)}
     * to continue the protocol exchange.
     * 
     * @param greeting the server's greeting response (typically 220 code)
     * @param connection the SMTP connection to send commands through
     */
    void onGreeting(SMTPResponse greeting, SMTPClientConnection connection);

    /**
     * Called when the server sends a response to a client command.
     * 
     * <p>This method is invoked for all server responses except the initial
     * greeting. The handler can examine the response code and message to
     * determine the appropriate next action in the SMTP protocol flow.
     * 
     * <p>Common response scenarios:
     * <ul>
     * <li>HELO/EHLO responses - proceed to authentication or message transfer</li>
     * <li>MAIL FROM responses - proceed to RCPT TO or handle rejection</li>
     * <li>RCPT TO responses - send more recipients or proceed to DATA</li>
     * <li>DATA responses (354) - begin message content transmission</li>
     * <li>Message acceptance responses (250) - proceed to QUIT or next message</li>
     * </ul>
     * 
     * <p>The connection parameter provides access to command methods to continue
     * the protocol exchange based on the response.
     * 
     * @param response the server's response to a command
     * @param connection the SMTP connection to send commands through
     */
    void onReply(SMTPResponse response, SMTPClientConnection connection);
}
