/*
 * SelectedHandler.java
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
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageSet;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.mailbox.StoreAction;
import org.bluezoo.gumdrop.quota.QuotaManager;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Handler for IMAP SELECTED state commands.
 * 
 * <p>This handler receives commands when a mailbox is selected
 * (RFC 9051 Section 3.3). The client can now access messages in
 * the selected mailbox.
 * 
 * <p>Methods receive the {@link MailboxStore} established at login and
 * the currently selected {@link Mailbox}. This allows the handler to:
 * <ul>
 *   <li>Access the current mailbox for message operations</li>
 *   <li>Access other mailboxes (e.g., COPY/MOVE targets)</li>
 *   <li>Manage the mailbox hierarchy</li>
 * </ul>
 * 
 * <p>Protocol-level commands (CAPABILITY, NOOP, LOGOUT, NAMESPACE)
 * are handled automatically by the server.
 * 
 * <p>Policy decisions in selected state:
 * <ul>
 *   <li>FETCH/SEARCH - message access (logging, auditing)</li>
 *   <li>STORE - modify message flags (write access check)</li>
 *   <li>COPY/MOVE - copy/move messages (destination access check)</li>
 *   <li>EXPUNGE - remove deleted messages (write access check)</li>
 *   <li>CLOSE/UNSELECT - deselect mailbox</li>
 *   <li>STATUS - mailbox status access</li>
 *   <li>Mailbox management - CREATE/DELETE/RENAME/SUBSCRIBE</li>
 *   <li>APPEND - message delivery</li>
 *   <li>QUOTA - quota management</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SelectState#selectOk
 */
public interface SelectedHandler {

    // ============== AUTHENTICATED state commands (also valid in SELECTED) ==============

    /**
     * Called when the client sends SELECT command.
     * 
     * @param store the user's mailbox store
     * @param mailboxName the mailbox name to select
     * @param state operations for responding
     */
    void select(MailboxStore store, String mailboxName, SelectState state);

    /**
     * Called when the client sends EXAMINE command.
     * 
     * @param store the user's mailbox store
     * @param mailboxName the mailbox name to examine
     * @param state operations for responding
     */
    void examine(MailboxStore store, String mailboxName, SelectState state);

    /**
     * Called when the client sends CREATE command.
     * 
     * @param store the user's mailbox store
     * @param mailboxName the new mailbox name
     * @param state operations for responding
     */
    void create(MailboxStore store, String mailboxName, CreateState state);

    /**
     * Called when the client sends DELETE command.
     * 
     * @param store the user's mailbox store
     * @param mailboxName the mailbox to delete
     * @param state operations for responding
     */
    void delete(MailboxStore store, String mailboxName, DeleteState state);

    /**
     * Called when the client sends RENAME command.
     * 
     * @param store the user's mailbox store
     * @param oldName the current mailbox name
     * @param newName the new mailbox name
     * @param state operations for responding
     */
    void rename(MailboxStore store, String oldName, String newName, RenameState state);

    /**
     * Called when the client sends SUBSCRIBE command.
     * 
     * @param store the user's mailbox store
     * @param mailboxName the mailbox to subscribe to
     * @param state operations for responding
     */
    void subscribe(MailboxStore store, String mailboxName, SubscribeState state);

    /**
     * Called when the client sends UNSUBSCRIBE command.
     * 
     * @param store the user's mailbox store
     * @param mailboxName the mailbox to unsubscribe from
     * @param state operations for responding
     */
    void unsubscribe(MailboxStore store, String mailboxName, SubscribeState state);

    /**
     * Called when the client sends LIST command.
     * 
     * @param store the user's mailbox store
     * @param reference the reference name
     * @param pattern the mailbox pattern
     * @param state operations for responding
     */
    void list(MailboxStore store, String reference, String pattern, ListState state);

    /**
     * Called when the client sends LSUB command.
     * 
     * @param store the user's mailbox store
     * @param reference the reference name
     * @param pattern the mailbox pattern
     * @param state operations for responding
     */
    void lsub(MailboxStore store, String reference, String pattern, ListState state);

    /**
     * Called when the client sends STATUS command.
     * 
     * @param store the user's mailbox store
     * @param mailboxName the mailbox to query
     * @param statusItems the status items requested
     * @param state operations for responding
     */
    void status(MailboxStore store, String mailboxName, Set<StatusItem> statusItems, SelectedStatusState state);

    /**
     * Called when the client sends APPEND command.
     * 
     * @param store the user's mailbox store
     * @param mailboxName the target mailbox
     * @param flags optional flags for the message
     * @param internalDate optional internal date
     * @param state operations for receiving the message literal
     */
    void append(MailboxStore store, String mailboxName, Set<Flag> flags, 
                OffsetDateTime internalDate, AppendState state);

    /**
     * Called when the client sends GETQUOTA command.
     * 
     * @param quotaManager the server's quota manager (may be null if not configured)
     * @param store the user's mailbox store
     * @param quotaRoot the quota root name
     * @param state operations for responding
     */
    void getQuota(QuotaManager quotaManager, MailboxStore store, String quotaRoot, QuotaState state);

    /**
     * Called when the client sends GETQUOTAROOT command.
     * 
     * @param quotaManager the server's quota manager (may be null if not configured)
     * @param store the user's mailbox store
     * @param mailboxName the mailbox name
     * @param state operations for responding
     */
    void getQuotaRoot(QuotaManager quotaManager, MailboxStore store, String mailboxName, QuotaState state);

    /**
     * Called when the client sends SETQUOTA command.
     * 
     * @param quotaManager the server's quota manager (may be null if not configured)
     * @param store the user's mailbox store
     * @param quotaRoot the quota root name
     * @param resourceLimits the resource limits (resource name to limit value)
     * @param state operations for responding
     */
    void setQuota(QuotaManager quotaManager, MailboxStore store, String quotaRoot, 
                  Map<String, Long> resourceLimits, QuotaState state);

    // ============== SELECTED state specific commands ==============

    /**
     * Called when the client sends CLOSE command.
     * 
     * <p>CLOSE implicitly expunges deleted messages and returns to
     * AUTHENTICATED state.
     * 
     * @param mailbox the currently selected mailbox
     * @param state operations for responding
     */
    void close(Mailbox mailbox, CloseState state);

    /**
     * Called when the client sends UNSELECT command.
     * 
     * <p>UNSELECT returns to AUTHENTICATED state without expunging.
     * 
     * @param mailbox the currently selected mailbox
     * @param state operations for responding
     */
    void unselect(Mailbox mailbox, CloseState state);

    /**
     * Called when the client sends EXPUNGE command.
     * 
     * @param mailbox the currently selected mailbox
     * @param state operations for responding
     */
    void expunge(Mailbox mailbox, ExpungeState state);

    /**
     * Called when the client sends UID EXPUNGE command.
     * 
     * @param mailbox the currently selected mailbox
     * @param uidSet the UID set of messages to expunge
     * @param state operations for responding
     */
    void uidExpunge(Mailbox mailbox, MessageSet uidSet, ExpungeState state);

    /**
     * Called when the client sends STORE command.
     * 
     * @param mailbox the currently selected mailbox
     * @param messages the message sequence set
     * @param action the flag action (REPLACE, ADD, or REMOVE)
     * @param flags the flags to set/add/remove
     * @param silent true if .SILENT modifier was used
     * @param state operations for responding
     */
    void store(Mailbox mailbox, MessageSet messages, StoreAction action, Set<Flag> flags, 
               boolean silent, StoreState state);

    /**
     * Called when the client sends UID STORE command.
     * 
     * @param mailbox the currently selected mailbox
     * @param uidSet the UID set
     * @param action the flag action (REPLACE, ADD, or REMOVE)
     * @param flags the flags to set/add/remove
     * @param silent true if .SILENT modifier was used
     * @param state operations for responding
     */
    void uidStore(Mailbox mailbox, MessageSet uidSet, StoreAction action, Set<Flag> flags, 
                  boolean silent, StoreState state);

    /**
     * Called when the client sends COPY command.
     * 
     * @param store the user's mailbox store (to access target mailbox)
     * @param mailbox the currently selected (source) mailbox
     * @param messages the message sequence set
     * @param targetMailbox the destination mailbox name
     * @param state operations for responding
     */
    void copy(MailboxStore store, Mailbox mailbox, MessageSet messages, 
              String targetMailbox, CopyState state);

    /**
     * Called when the client sends UID COPY command.
     * 
     * @param store the user's mailbox store (to access target mailbox)
     * @param mailbox the currently selected (source) mailbox
     * @param uidSet the UID set
     * @param targetMailbox the destination mailbox name
     * @param state operations for responding
     */
    void uidCopy(MailboxStore store, Mailbox mailbox, MessageSet uidSet, 
                 String targetMailbox, CopyState state);

    /**
     * Called when the client sends MOVE command.
     * 
     * @param store the user's mailbox store (to access target mailbox)
     * @param mailbox the currently selected (source) mailbox
     * @param messages the message sequence set
     * @param targetMailbox the destination mailbox name
     * @param state operations for responding
     */
    void move(MailboxStore store, Mailbox mailbox, MessageSet messages, 
              String targetMailbox, MoveState state);

    /**
     * Called when the client sends UID MOVE command.
     * 
     * @param store the user's mailbox store (to access target mailbox)
     * @param mailbox the currently selected (source) mailbox
     * @param uidSet the UID set
     * @param targetMailbox the destination mailbox name
     * @param state operations for responding
     */
    void uidMove(MailboxStore store, Mailbox mailbox, MessageSet uidSet, 
                 String targetMailbox, MoveState state);

    // ============== Data retrieval commands (for logging/auditing) ==============

    /**
     * Called when the client sends FETCH command.
     * 
     * <p>The handler can use this for logging or access control.
     * Typically just calls {@code state.proceed(this)}.
     * 
     * @param mailbox the currently selected mailbox
     * @param messages the message sequence set
     * @param fetchItems the data items to fetch (FLAGS, ENVELOPE, BODY, etc.)
     * @param state operations for responding
     */
    void fetch(Mailbox mailbox, MessageSet messages, Set<String> fetchItems, FetchState state);

    /**
     * Called when the client sends UID FETCH command.
     * 
     * @param mailbox the currently selected mailbox
     * @param uidSet the UID set
     * @param fetchItems the data items to fetch
     * @param state operations for responding
     */
    void uidFetch(Mailbox mailbox, MessageSet uidSet, Set<String> fetchItems, FetchState state);

    /**
     * Called when the client sends SEARCH command.
     * 
     * <p>The handler can use this for logging or access control.
     * Typically just calls {@code state.proceed(this)}.
     * 
     * @param mailbox the currently selected mailbox
     * @param criteria the search criteria
     * @param state operations for responding
     */
    void search(Mailbox mailbox, SearchCriteria criteria, SearchState state);

    /**
     * Called when the client sends UID SEARCH command.
     * 
     * @param mailbox the currently selected mailbox
     * @param criteria the search criteria
     * @param state operations for responding
     */
    void uidSearch(Mailbox mailbox, SearchCriteria criteria, SearchState state);

}
