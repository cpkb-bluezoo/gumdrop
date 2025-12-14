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

package org.bluezoo.gumdrop.pop3.handler;

/**
 * Factory for creating {@link ClientConnected} handler instances.
 * 
 * <p>Implement this interface to create handlers for new POP3 connections.
 * A new handler instance should be created for each connection to maintain
 * per-connection state.
 * 
 * <p><strong>Example implementation:</strong>
 * <pre>{@code
 * public class MyPOP3HandlerFactory implements ClientConnectedFactory {
 *     
 *     private final MailboxFactory mailboxFactory;
 *     private final Realm realm;
 *     
 *     public MyPOP3HandlerFactory(MailboxFactory mailboxFactory, Realm realm) {
 *         this.mailboxFactory = mailboxFactory;
 *         this.realm = realm;
 *     }
 *     
 *     public ClientConnected createHandler() {
 *         return new MyPOP3Handler(mailboxFactory, realm);
 *     }
 * }
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientConnected
 */
public interface ClientConnectedFactory {

    /**
     * Creates a new handler for a POP3 connection.
     * 
     * <p>This is called when a new client connection is accepted.
     * Each connection should have its own handler instance.
     * 
     * @return a new ClientConnected handler
     */
    ClientConnected createHandler();

}

