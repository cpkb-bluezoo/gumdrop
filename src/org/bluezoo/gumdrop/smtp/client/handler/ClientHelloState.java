/*
 * ClientHelloState.java
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

/**
 * Operations available after receiving the server greeting.
 * 
 * <p>This interface is provided to the handler in
 * {@link ServerGreeting#handleGreeting} and allows the handler to initiate
 * the SMTP session with either EHLO or HELO.
 * 
 * <p>Use EHLO for Extended SMTP (recommended for modern servers) or HELO
 * as a fallback for servers that don't support ESMTP. The {@code esmtp}
 * parameter in {@code handleGreeting} indicates whether the server
 * advertised ESMTP support.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerGreeting#handleGreeting
 */
public interface ClientHelloState {

    /**
     * Sends an EHLO command to initiate an Extended SMTP session.
     * 
     * <p>EHLO is the preferred command for modern SMTP servers and enables
     * extensions like STARTTLS, AUTH, PIPELINING, and SIZE.
     * 
     * @param hostname the client's hostname to announce
     * @param callback receives the server's response
     */
    void ehlo(String hostname, ServerEhloReplyHandler callback);

    /**
     * Sends a HELO command to initiate a basic SMTP session.
     * 
     * <p>HELO is used as a fallback when EHLO is not supported. No
     * extensions will be available.
     * 
     * @param hostname the client's hostname to announce
     * @param callback receives the server's response
     */
    void helo(String hostname, ServerHeloReplyHandler callback);

    /**
     * Closes the connection without establishing a session.
     * 
     * <p>Use this to abort immediately after receiving the greeting if
     * the server is not acceptable (e.g., wrong server, policy violation).
     */
    void quit();

}

