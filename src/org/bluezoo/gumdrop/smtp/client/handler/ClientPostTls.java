/*
 * ClientPostTls.java
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
 * Operations available after TLS upgrade.
 * 
 * <p>This interface is provided to the handler after STARTTLS succeeds and
 * the TLS handshake completes. Per RFC 3207, the client <em>must</em> re-issue
 * EHLO after TLS upgrade to learn the server's post-TLS capabilities.
 * 
 * <p>The only operation available is {@code ehlo()} - this interface enforces
 * the RFC requirement that EHLO must be re-sent after STARTTLS.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerStarttlsReplyHandler#handleTlsEstablished
 */
public interface ClientPostTls {

    /**
     * Re-issues EHLO after TLS upgrade.
     * 
     * <p>Per RFC 3207, this is required after STARTTLS. The server may
     * advertise different capabilities over the encrypted connection
     * (e.g., additional AUTH mechanisms).
     * 
     * @param hostname the client's hostname to announce
     * @param callback receives the server's response
     */
    void ehlo(String hostname, ServerEhloReplyHandler callback);

    /**
     * Closes the connection.
     * 
     * <p>Use this to abort if TLS succeeded but the handler decides not
     * to continue (e.g., certificate validation failure at application level).
     */
    void quit();

}

