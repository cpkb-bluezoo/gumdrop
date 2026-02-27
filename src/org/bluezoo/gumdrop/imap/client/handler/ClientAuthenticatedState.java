/*
 * ClientAuthenticatedState.java
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

package org.bluezoo.gumdrop.imap.client.handler;

/**
 * Operations available in AUTHENTICATED state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ClientAuthenticatedState {

    void select(String mailbox, ServerSelectReplyHandler callback);

    void examine(String mailbox, ServerSelectReplyHandler callback);

    void create(String mailbox, ServerMailboxReplyHandler callback);

    void delete(String mailbox, ServerMailboxReplyHandler callback);

    void rename(String from, String to, ServerMailboxReplyHandler callback);

    void subscribe(String mailbox, ServerMailboxReplyHandler callback);

    void unsubscribe(String mailbox, ServerMailboxReplyHandler callback);

    void list(String reference, String pattern, ServerListReplyHandler callback);

    void lsub(String reference, String pattern, ServerListReplyHandler callback);

    void status(String mailbox, String[] items, ServerStatusReplyHandler callback);

    void namespace(ServerNamespaceReplyHandler callback);

    void append(String mailbox, String[] flags, String date, long size, ServerAppendReplyHandler callback);

    void idle(ServerIdleEventHandler callback);

    void noop(ServerNoopReplyHandler callback);

    void logout();
}
