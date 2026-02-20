/*
 * DefaultIMAPService.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.imap.handler.ClientConnected;
import org.bluezoo.gumdrop.imap.handler.DefaultIMAPHandler;

/**
 * Default IMAP service that accepts all operations using the
 * configured Realm and MailboxFactory.
 *
 * <p>This service creates {@link DefaultIMAPHandler} instances for
 * each incoming connection. The handlers accept all operations and
 * perform them directly against the {@link
 * org.bluezoo.gumdrop.mailbox.MailboxFactory MailboxFactory}
 * configured on the service.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.imap.DefaultIMAPService">
 *   <property name="realm" ref="#myRealm"/>
 *   <property name="mailbox-factory" ref="#mbox"/>
 *   <listener class="org.bluezoo.gumdrop.imap.IMAPListener"
 *           port="143"/>
 *   <listener class="org.bluezoo.gumdrop.imap.IMAPListener"
 *           port="993" secure="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see IMAPService
 * @see DefaultIMAPHandler
 */
public class DefaultIMAPService extends IMAPService {

    @Override
    protected ClientConnected createHandler(TCPListener endpoint) {
        return new DefaultIMAPHandler();
    }

}
