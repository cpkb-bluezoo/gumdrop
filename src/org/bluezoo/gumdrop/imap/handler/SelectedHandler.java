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
     * @param state operations for responding
     * @param store the user's mailbox store
     * @param mailboxName the mailbox name to select
     */
    void select(SelectState state, MailboxStore store, String mailboxName);

    /**
     * Called when the client sends EXAMINE command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store
     * @param mailboxName the mailbox name to examine
     */
    void examine(SelectState state, MailboxStore store, String mailboxName);

    /**
     * Called when the client sends CREATE command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store
     * @param mailboxName the new mailbox name
     */
    void create(CreateState state, MailboxStore store, String mailboxName);

    /**
     * Called when the client sends DELETE command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store
     * @param mailboxName the mailbox to delete
     */
    void delete(DeleteState state, MailboxStore store, String mailboxName);

    /**
     * Called when the client sends RENAME command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store
     * @param oldName the current mailbox name
     * @param newName the new mailbox name
     */
    void rename(RenameState state, MailboxStore store, String oldName, String newName);

    /**
     * Called when the client sends SUBSCRIBE command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store
     * @param mailboxName the mailbox to subscribe to
     */
    void subscribe(SubscribeState state, MailboxStore store, String mailboxName);

    /**
     * Called when the client sends UNSUBSCRIBE command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store
     * @param mailboxName the mailbox to unsubscribe from
     */
    void unsubscribe(SubscribeState state, MailboxStore store, String mailboxName);

    /**
     * Called when the client sends LIST command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store
     * @param reference the reference name
     * @param pattern the mailbox pattern
     */
    void list(ListState state, MailboxStore store, String reference, String pattern);

    /**
     * Called when the client sends LSUB command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store
     * @param reference the reference name
     * @param pattern the mailbox pattern
     */
    void lsub(ListState state, MailboxStore store, String reference, String pattern);

    /**
     * Called when the client sends STATUS command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store
     * @param mailboxName the mailbox to query
     * @param statusItems the status items requested
     */
    void status(SelectedStatusState state, MailboxStore store, String mailboxName,
                Set<StatusItem> statusItems);

    /**
     * Called when the client sends APPEND command.
     * 
     * @param state operations for receiving the message literal
     * @param store the user's mailbox store
     * @param mailboxName the target mailbox
     * @param flags optional flags for the message
     * @param internalDate optional internal date
     */
    void append(AppendState state, MailboxStore store, String mailboxName, Set<Flag> flags,
                OffsetDateTime internalDate);

    /**
     * Called when the client sends GETQUOTA command.
     * 
     * @param state operations for responding
     * @param quotaManager the server's quota manager (may be null if not configured)
     * @param store the user's mailbox store
     * @param quotaRoot the quota root name
     */
    void getQuota(QuotaState state, QuotaManager quotaManager, MailboxStore store,
                  String quotaRoot);

    /**
     * Called when the client sends GETQUOTAROOT command.
     * 
     * @param state operations for responding
     * @param quotaManager the server's quota manager (may be null if not configured)
     * @param store the user's mailbox store
     * @param mailboxName the mailbox name
     */
    void getQuotaRoot(QuotaState state, QuotaManager quotaManager, MailboxStore store,
                      String mailboxName);

    /**
     * Called when the client sends SETQUOTA command.
     * 
     * @param state operations for responding
     * @param quotaManager the server's quota manager (may be null if not configured)
     * @param store the user's mailbox store
     * @param quotaRoot the quota root name
     * @param resourceLimits the resource limits (resource name to limit value)
     */
    void setQuota(QuotaState state, QuotaManager quotaManager, MailboxStore store,
                  String quotaRoot, Map<String, Long> resourceLimits);

    // ============== SELECTED state specific commands ==============

    /**
     * Called when the client sends CLOSE command.
     * 
     * <p>CLOSE implicitly expunges deleted messages and returns to
     * AUTHENTICATED state.
     * 
     * @param state operations for responding
     * @param mailbox the currently selected mailbox
     */
    void close(CloseState state, Mailbox mailbox);

    /**
     * Called when the client sends UNSELECT command.
     * 
     * <p>UNSELECT returns to AUTHENTICATED state without expunging.
     * 
     * @param state operations for responding
     * @param mailbox the currently selected mailbox
     */
    void unselect(CloseState state, Mailbox mailbox);

    /**
     * Called when the client sends EXPUNGE command.
     * 
     * @param state operations for responding
     * @param mailbox the currently selected mailbox
     */
    void expunge(ExpungeState state, Mailbox mailbox);

    /**
     * Called when the client sends UID EXPUNGE command.
     * 
     * @param state operations for responding
     * @param mailbox the currently selected mailbox
     * @param uidSet the UID set of messages to expunge
     */
    void uidExpunge(ExpungeState state, Mailbox mailbox, MessageSet uidSet);

    /**
     * Called when the client sends STORE command.
     * 
     * @param state operations for responding
     * @param mailbox the currently selected mailbox
     * @param messages the message sequence set
     * @param action the flag action (REPLACE, ADD, or REMOVE)
     * @param flags the flags to set/add/remove
     * @param silent true if .SILENT modifier was used
     */
    void store(StoreState state, Mailbox mailbox, MessageSet messages, StoreAction action,
               Set<Flag> flags, boolean silent);

    /**
     * Called when the client sends UID STORE command.
     * 
     * @param state operations for responding
     * @param mailbox the currently selected mailbox
     * @param uidSet the UID set
     * @param action the flag action (REPLACE, ADD, or REMOVE)
     * @param flags the flags to set/add/remove
     * @param silent true if .SILENT modifier was used
     */
    void uidStore(StoreState state, Mailbox mailbox, MessageSet uidSet, StoreAction action,
                  Set<Flag> flags, boolean silent);

    /**
     * Called when the client sends COPY command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store (to access target mailbox)
     * @param mailbox the currently selected (source) mailbox
     * @param messages the message sequence set
     * @param targetMailbox the destination mailbox name
     */
    void copy(CopyState state, MailboxStore store, Mailbox mailbox, MessageSet messages,
              String targetMailbox);

    /**
     * Called when the client sends UID COPY command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store (to access target mailbox)
     * @param mailbox the currently selected (source) mailbox
     * @param uidSet the UID set
     * @param targetMailbox the destination mailbox name
     */
    void uidCopy(CopyState state, MailboxStore store, Mailbox mailbox, MessageSet uidSet,
                 String targetMailbox);

    /**
     * Called when the client sends MOVE command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store (to access target mailbox)
     * @param mailbox the currently selected (source) mailbox
     * @param messages the message sequence set
     * @param targetMailbox the destination mailbox name
     */
    void move(MoveState state, MailboxStore store, Mailbox mailbox, MessageSet messages,
              String targetMailbox);

    /**
     * Called when the client sends UID MOVE command.
     * 
     * @param state operations for responding
     * @param store the user's mailbox store (to access target mailbox)
     * @param mailbox the currently selected (source) mailbox
     * @param uidSet the UID set
     * @param targetMailbox the destination mailbox name
     */
    void uidMove(MoveState state, MailboxStore store, Mailbox mailbox, MessageSet uidSet,
                 String targetMailbox);

    // ============== Data retrieval commands (for logging/auditing) ==============

    /**
     * Called when the client sends FETCH command.
     * 
     * <p>The handler can use this for logging or access control.
     * Typically just calls {@code state.proceed(this)}.
     * 
     * @param state operations for responding
     * @param mailbox the currently selected mailbox
     * @param messages the message sequence set
     * @param fetchItems the data items to fetch (FLAGS, ENVELOPE, BODY, etc.)
     */
    void fetch(FetchState state, Mailbox mailbox, MessageSet messages, Set<String> fetchItems);

    /**
     * Called when the client sends UID FETCH command.
     * 
     * @param state operations for responding
     * @param mailbox the currently selected mailbox
     * @param uidSet the UID set
     * @param fetchItems the data items to fetch
     */
    void uidFetch(FetchState state, Mailbox mailbox, MessageSet uidSet, Set<String> fetchItems);

    /**
     * Called when the client sends SEARCH command.
     * 
     * <p>The handler can use this for logging or access control.
     * Typically just calls {@code state.proceed(this)}.
     * 
     * @param state operations for responding
     * @param mailbox the currently selected mailbox
     * @param criteria the search criteria
     */
    void search(SearchState state, Mailbox mailbox, SearchCriteria criteria);

    /**
     * Called when the client sends UID SEARCH command.
     * 
     * @param state operations for responding
     * @param mailbox the currently selected mailbox
     * @param criteria the search criteria
     */
    void uidSearch(SearchState state, Mailbox mailbox, SearchCriteria criteria);

}
