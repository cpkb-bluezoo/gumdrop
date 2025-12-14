/*
 * AuthenticatedHandler.java
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

import org.bluezoo.gumdrop.imap.StatusItem;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.quota.QuotaManager;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Handler for IMAP AUTHENTICATED state commands.
 * 
 * <p>This handler receives commands after successful authentication
 * (RFC 9051 Section 3.2). The client can now access mailbox lists and
 * select mailboxes for message access.
 * 
 * <p>All methods receive the {@link MailboxStore} established at login time,
 * allowing the handler to access the user's mailbox hierarchy. If no store
 * was configured (null), the handler must implement its own storage logic.
 * 
 * <p>Protocol-level commands (CAPABILITY, NOOP, LOGOUT, NAMESPACE)
 * are handled automatically by the server and don't involve the handler.
 * 
 * <p>Key policy decisions:
 * <ul>
 *   <li>SELECT/EXAMINE - mailbox access control</li>
 *   <li>CREATE/DELETE/RENAME - mailbox management permissions</li>
 *   <li>LIST/LSUB - mailbox visibility filtering</li>
 *   <li>STATUS - mailbox status access (for logging/auditing)</li>
 *   <li>APPEND - message delivery permissions and quota</li>
 *   <li>QUOTA - quota management</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LoginState#loginOk
 * @see AuthenticateState#authenticationOk
 */
public interface AuthenticatedHandler {

    /**
     * Called when the client sends SELECT command.
     * 
     * <p>SELECT opens a mailbox for read-write access.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param mailboxName the mailbox name
     * @param state operations for responding
     */
    void select(MailboxStore store, String mailboxName, SelectState state);

    /**
     * Called when the client sends EXAMINE command.
     * 
     * <p>EXAMINE opens a mailbox for read-only access.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param mailboxName the mailbox name
     * @param state operations for responding
     */
    void examine(MailboxStore store, String mailboxName, SelectState state);

    /**
     * Called when the client sends CREATE command.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param mailboxName the new mailbox name
     * @param state operations for responding
     */
    void create(MailboxStore store, String mailboxName, CreateState state);

    /**
     * Called when the client sends DELETE command.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param mailboxName the mailbox to delete
     * @param state operations for responding
     */
    void delete(MailboxStore store, String mailboxName, DeleteState state);

    /**
     * Called when the client sends RENAME command.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param oldName the current mailbox name
     * @param newName the new mailbox name
     * @param state operations for responding
     */
    void rename(MailboxStore store, String oldName, String newName, RenameState state);

    /**
     * Called when the client sends SUBSCRIBE command.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param mailboxName the mailbox to subscribe to
     * @param state operations for responding
     */
    void subscribe(MailboxStore store, String mailboxName, SubscribeState state);

    /**
     * Called when the client sends UNSUBSCRIBE command.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param mailboxName the mailbox to unsubscribe from
     * @param state operations for responding
     */
    void unsubscribe(MailboxStore store, String mailboxName, SubscribeState state);

    /**
     * Called when the client sends LIST command.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param reference the reference name
     * @param pattern the mailbox pattern (may contain wildcards)
     * @param state operations for responding
     */
    void list(MailboxStore store, String reference, String pattern, ListState state);

    /**
     * Called when the client sends LSUB command.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param reference the reference name
     * @param pattern the mailbox pattern
     * @param state operations for responding
     */
    void lsub(MailboxStore store, String reference, String pattern, ListState state);

    /**
     * Called when the client sends STATUS command.
     * 
     * <p>STATUS queries information about a mailbox without selecting it.
     * The handler can use this for logging or access control.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param mailboxName the mailbox to query
     * @param statusItems the status items requested
     * @param state operations for responding
     */
    void status(MailboxStore store, String mailboxName, Set<StatusItem> statusItems, AuthenticatedStatusState state);

    /**
     * Called when the client sends APPEND command.
     * 
     * @param store the user's mailbox store (may be null if not configured)
     * @param mailboxName the target mailbox
     * @param flags optional flags for the message (may be null)
     * @param internalDate optional internal date (may be null)
     * @param state operations for receiving the message literal
     */
    void append(MailboxStore store, String mailboxName, Set<Flag> flags, 
                OffsetDateTime internalDate, AppendState state);

    /**
     * Called when the client sends GETQUOTA command.
     * 
     * @param quotaManager the server's quota manager (may be null if not configured)
     * @param store the user's mailbox store (may be null if not configured)
     * @param quotaRoot the quota root name
     * @param state operations for responding
     */
    void getQuota(QuotaManager quotaManager, MailboxStore store, String quotaRoot, QuotaState state);

    /**
     * Called when the client sends GETQUOTAROOT command.
     * 
     * @param quotaManager the server's quota manager (may be null if not configured)
     * @param store the user's mailbox store (may be null if not configured)
     * @param mailboxName the mailbox name
     * @param state operations for responding
     */
    void getQuotaRoot(QuotaManager quotaManager, MailboxStore store, String mailboxName, QuotaState state);

    /**
     * Called when the client sends SETQUOTA command.
     * 
     * @param quotaManager the server's quota manager (may be null if not configured)
     * @param store the user's mailbox store (may be null if not configured)
     * @param quotaRoot the quota root name
     * @param resourceLimits the resource limits (resource name to limit value)
     * @param state operations for responding
     */
    void setQuota(QuotaManager quotaManager, MailboxStore store, String quotaRoot, 
                  Map<String, Long> resourceLimits, QuotaState state);

}
