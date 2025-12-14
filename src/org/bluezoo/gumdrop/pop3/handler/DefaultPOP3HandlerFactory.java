/*
 * DefaultPOP3HandlerFactory.java
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
 * Factory that creates {@link DefaultPOP3Handler} instances.
 * 
 * <p>This factory creates handlers that accept all connections and use the
 * configured MailboxFactory to open user mailboxes. It provides a simple,
 * permissive POP3 implementation suitable for many deployments.
 * 
 * <p><strong>Configuration example:</strong>
 * <pre>{@code
 * <component id="pop3-handler-factory"
 *            class="org.bluezoo.gumdrop.pop3.handler.DefaultPOP3HandlerFactory"/>
 * 
 * <server class="org.bluezoo.gumdrop.pop3.POP3Server">
 *     <property name="port">110</property>
 *     <property name="handler-factory" ref="#pop3-handler-factory"/>
 *     <property name="mailbox-factory" ref="#maildir"/>
 *     <property name="realm" ref="#myRealm"/>
 * </server>
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DefaultPOP3Handler
 */
public class DefaultPOP3HandlerFactory implements ClientConnectedFactory {

    private String greeting = "POP3 server ready";

    /**
     * Creates a new DefaultPOP3HandlerFactory with default settings.
     */
    public DefaultPOP3HandlerFactory() {
    }

    /**
     * Returns the greeting message sent to clients.
     * 
     * @return the greeting message
     */
    public String getGreeting() {
        return greeting;
    }

    /**
     * Sets the greeting message sent to clients.
     * 
     * @param greeting the greeting message
     */
    public void setGreeting(String greeting) {
        this.greeting = greeting;
    }

    @Override
    public ClientConnected createHandler() {
        return new DefaultPOP3Handler(greeting);
    }

}

