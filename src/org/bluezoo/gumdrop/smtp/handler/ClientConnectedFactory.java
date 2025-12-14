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

package org.bluezoo.gumdrop.smtp.handler;

/**
 * Factory for creating {@link ClientConnected} handler instances.
 * 
 * <p>The SMTP server calls this factory to create a new handler for each
 * client connection. This allows per-connection state and configuration.
 * 
 * <p><strong>Example implementation:</strong>
 * <pre>{@code
 * public class MyHandlerFactory implements ClientConnectedFactory {
 *     
 *     private final Mailbox mailbox;
 *     
 *     public MyHandlerFactory(Mailbox mailbox) {
 *         this.mailbox = mailbox;
 *     }
 *     
 *     public ClientConnected createHandler() {
 *         return new MyMailHandler(mailbox);
 *     }
 * }
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientConnected
 * @see SMTPServer#setHandlerFactory
 */
public interface ClientConnectedFactory {

    /**
     * Creates a new handler for an incoming client connection.
     * 
     * <p>This is called once per connection. The returned handler
     * receives the initial {@link ClientConnected#connected} callback
     * and manages the connection's lifecycle.
     * 
     * @return a new handler instance
     */
    ClientConnected createHandler();

}

