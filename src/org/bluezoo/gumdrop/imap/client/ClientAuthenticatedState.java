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

package org.bluezoo.gumdrop.imap.client;

/**
 * Operations available in AUTHENTICATED state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ClientAuthenticatedState {

    void select(String mailbox, SelectReplyHandler callback);

    void examine(String mailbox, SelectReplyHandler callback);

    void create(String mailbox, MailboxReplyHandler callback);

    void delete(String mailbox, MailboxReplyHandler callback);

    void rename(String from, String to, MailboxReplyHandler callback);

    void subscribe(String mailbox, MailboxReplyHandler callback);

    void unsubscribe(String mailbox, MailboxReplyHandler callback);

    void list(String reference, String pattern, ListReplyHandler callback);

    void lsub(String reference, String pattern, ListReplyHandler callback);

    void status(String mailbox, String[] items, StatusReplyHandler callback);

    void namespace(NamespaceReplyHandler callback);

    void append(String mailbox, String[] flags, String date, long size, AppendReplyHandler callback);

    void idle(IdleEventHandler callback);

    void noop(NoopReplyHandler callback);

    /**
     * Negotiates RFC 4978 DEFLATE compression on the connection.
     *
     * @param callback invoked when the server responds to {@code COMPRESS DEFLATE}
     */
    void compress(CompressReplyHandler callback);

    /**
     * Enables IMAP extensions (for example {@code UTF8=ACCEPT}, CONDSTORE).
     *
     * @param extensions capability names to enable
     * @param callback invoked when the server responds
     */
    void enable(String[] extensions, EnableReplyHandler callback);

    /**
     * Registers or updates RFC 5465 NOTIFY subscriptions.
     *
     * @param notifyArgs arguments after the NOTIFY command keyword
     * @param callback invoked when the server responds
     */
    void notifySet(String notifyArgs, NotifyReplyHandler callback);

    /**
     * Cancels all RFC 5465 NOTIFY subscriptions.
     *
     * @param callback invoked when the server responds
     */
    void notifyNone(NotifyReplyHandler callback);

    // RFC 9208 — QUOTA commands
    void getQuota(String quotaRoot, QuotaReplyHandler callback);

    void getQuotaRoot(String mailbox, QuotaReplyHandler callback);

    void logout();
}
