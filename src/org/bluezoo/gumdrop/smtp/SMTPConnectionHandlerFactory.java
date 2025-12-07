/*
 * SMTPConnectionHandlerFactory.java
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

/**
 * Factory for creating SMTP connection handler instances.
 * <p>
 * This factory pattern ensures that each SMTP connection gets its own
 * dedicated handler instance, preventing thread safety issues and state
 * confusion that would occur if handlers were shared between connections.
 * <p>
 * The factory approach provides several benefits:
 * <ul>
 * <li><em>Thread Safety</em> - Each connection runs in its own thread with its own handler</li>
 * <li><em>State Isolation</em> - Connection-specific state remains separate</li>
 * <li><em>Performance</em> - No synchronization overhead, no reflection</li>
 * <li><em>Flexibility</em> - Supports dependency injection and configuration</li>
 * <li><em>Resource Management</em> - Handlers can be garbage collected per connection</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre><code>
 * // Simple lambda factory
 * connector.setHandlerFactory(() -&gt; new MyMailHandler());
 * 
 * // Factory with configuration
 * connector.setHandlerFactory(() -&gt; new MyMailHandler(config, database, logger));
 * 
 * // Custom factory class for complex initialization
 * public class MyHandlerFactory implements SMTPConnectionHandlerFactory {
 *     private final Config config;
 *     private final Database db;
 *     
 *     public MyHandlerFactory(Config config, Database db) {
 *         this.config = config;
 *         this.db = db;
 *     }
 *     
 *     &#64;Override
 *     public SMTPConnectionHandler createHandler() {
 *         return new MyMailHandler(config, db, new ConnectionLogger());
 *     }
 * }
 * </code></pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SMTPConnectionHandler
 * @see SMTPServer#setHandlerFactory(SMTPConnectionHandlerFactory)
 */

public interface SMTPConnectionHandlerFactory {

    /**
     * Creates a new SMTP connection handler instance.
     * <p>
	 * This method is called once for each new SMTP connection to create
     * a dedicated handler instance for that connection. The handler will
     * be used exclusively by that connection's thread and will be garbage
     * collected when the connection closes.
     * <p>
	 * Implementations should:
     * <ul>
     * <li>Create a new handler instance (never return cached instances)</li>
     * <li>Perform any necessary initialization or dependency injection</li>
     * <li>Be thread-safe (may be called concurrently from multiple threads)</li>
     * <li>Be fast (called on connection establishment hot path)</li>
     * <li>Handle exceptions gracefully (return null to use default behavior)</li>
     * </ul>
     * 
     * @return a new SMTPConnectionHandler instance for the connection,
     *         or null to use default connection behavior (accept all mail)
     */
    SMTPConnectionHandler createHandler();

}
