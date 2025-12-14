/*
 * ClientConnectedFactory.java
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

package org.bluezoo.gumdrop.imap.handler;

/**
 * Factory for creating {@link ClientConnected} handler instances.
 * 
 * <p>This factory is configured on the IMAP server to provide handlers
 * for new client connections. The factory is called once per connection
 * to create a handler that manages that connection's lifecycle.
 * 
 * <p><strong>Example usage:</strong>
 * <pre>{@code
 * public class MyIMAPHandlerFactory implements ClientConnectedFactory {
 *     private final MailboxFactory mailboxFactory;
 *     private final Realm realm;
 *     
 *     public MyIMAPHandlerFactory(MailboxFactory mailboxFactory, Realm realm) {
 *         this.mailboxFactory = mailboxFactory;
 *         this.realm = realm;
 *     }
 *     
 *     public ClientConnected createHandler() {
 *         return new MyIMAPHandler(mailboxFactory, realm);
 *     }
 * }
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientConnected
 */
public interface ClientConnectedFactory {

    /**
     * Creates a new handler for an incoming client connection.
     * 
     * <p>This method is called for each new IMAP connection. The returned
     * handler will receive protocol events for the lifetime of that
     * connection.
     * 
     * <p>Implementations should ensure that each call returns a new handler
     * instance, as handlers maintain per-connection state.
     * 
     * @return a new handler instance for the connection
     */
    ClientConnected createHandler();

}

