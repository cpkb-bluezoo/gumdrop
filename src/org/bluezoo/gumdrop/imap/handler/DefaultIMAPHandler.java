/*
 * DefaultIMAPHandler.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.imap.StatusItem;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageSet;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.mailbox.StoreAction;
import org.bluezoo.gumdrop.quota.QuotaManager;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Default IMAP handler implementation that accepts all operations.
 *
 * <p>This handler authorises every request and delegates disk I/O to the
 * protocol via {@code state.proceed(this)}. The protocol opens stores and
 * mailboxes and performs CRUD on {@link org.bluezoo.gumdrop.StorageExecutor},
 * matching the STATUS/SEARCH pattern. Subclasses can override specific
 * methods to add custom policy logic (deny, or perform their own work).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultIMAPHandler implements ClientConnected, NotAuthenticatedHandler,
        AuthenticatedHandler, SelectedHandler {

    // ============== ClientConnected ==============

    @Override
    public void connected(ConnectedState state, Endpoint endpoint) {
        state.acceptConnection("IMAP4rev2 server ready", this);
    }

    @Override
    public void disconnected() {
        // No cleanup needed
    }

    // ============== NotAuthenticatedHandler ==============

    @Override
    public void authenticate(AuthenticateState state, Principal principal,
            MailboxFactory factory) {
        // Authorise only; protocol opens the store via StorageExecutor.
        state.proceed(this);
    }

    // ============== AuthenticatedHandler ==============

    @Override
    public void select(SelectState state, MailboxStore store, String mailboxName) {
        state.proceed(this);
    }

    @Override
    public void examine(SelectState state, MailboxStore store, String mailboxName) {
        state.proceed(this);
    }

    @Override
    public void create(CreateState state, MailboxStore store, String mailboxName) {
        state.proceed(this);
    }

    @Override
    public void delete(DeleteState state, MailboxStore store, String mailboxName) {
        state.proceed(this);
    }

    @Override
    public void rename(RenameState state, MailboxStore store, String oldName, String newName) {
        state.proceed(this);
    }

    @Override
    public void subscribe(SubscribeState state, MailboxStore store, String mailboxName) {
        state.proceed(this);
    }

    @Override
    public void unsubscribe(SubscribeState state, MailboxStore store, String mailboxName) {
        state.proceed(this);
    }

    @Override
    public void list(ListState state, MailboxStore store, String reference, String pattern) {
        state.proceed(this);
    }

    @Override
    public void lsub(ListState state, MailboxStore store, String reference, String pattern) {
        state.proceed(this);
    }

    @Override
    public void status(AuthenticatedStatusState state, MailboxStore store,
            String mailboxName, Set<StatusItem> statusItems) {
        state.proceed(this);
    }

    @Override
    public void append(AppendState state, MailboxStore store, String mailboxName,
            Set<Flag> flags, OffsetDateTime internalDate) {
        state.proceed(this);
    }

    @Override
    public void getQuota(QuotaState state, QuotaManager quotaManager, MailboxStore store,
            String quotaRoot) {
        if (quotaManager == null) {
            state.quotaNotSupported(this);
        } else {
            state.proceed(this);
        }
    }

    @Override
    public void getQuotaRoot(QuotaState state, QuotaManager quotaManager, MailboxStore store,
            String mailboxName) {
        if (quotaManager == null) {
            state.quotaNotSupported(this);
        } else {
            state.proceed(this);
        }
    }

    @Override
    public void setQuota(QuotaState state, QuotaManager quotaManager, MailboxStore store,
            String quotaRoot, Map<String, Long> resourceLimits) {
        if (quotaManager == null) {
            state.quotaNotSupported(this);
        } else {
            state.proceed(this);
        }
    }

    // ============== SelectedHandler (additional methods) ==============

    @Override
    public void status(SelectedStatusState state, MailboxStore store, String mailboxName,
            Set<StatusItem> statusItems) {
        state.proceed(this);
    }

    @Override
    public void close(CloseState state, Mailbox mailbox) {
        state.proceed(this);
    }

    @Override
    public void unselect(CloseState state, Mailbox mailbox) {
        state.proceed(this);
    }

    @Override
    public void expunge(ExpungeState state, Mailbox mailbox) {
        state.proceed(this);
    }

    @Override
    public void uidExpunge(ExpungeState state, Mailbox mailbox, MessageSet uidSet) {
        state.proceed(this);
    }

    @Override
    public void store(StoreState state, Mailbox mailbox, MessageSet messages,
            StoreAction action, Set<Flag> flags, boolean silent) {
        state.proceed(this);
    }

    @Override
    public void uidStore(StoreState state, Mailbox mailbox, MessageSet uidSet,
            StoreAction action, Set<Flag> flags, boolean silent) {
        state.proceed(this);
    }

    @Override
    public void copy(CopyState state, MailboxStore store, Mailbox mailbox,
            MessageSet messages, String targetMailbox) {
        state.proceed(this);
    }

    @Override
    public void uidCopy(CopyState state, MailboxStore store, Mailbox mailbox,
            MessageSet uidSet, String targetMailbox) {
        state.proceed(this);
    }

    @Override
    public void move(MoveState state, MailboxStore store, Mailbox mailbox,
            MessageSet messages, String targetMailbox) {
        state.proceed(this);
    }

    @Override
    public void uidMove(MoveState state, MailboxStore store, Mailbox mailbox,
            MessageSet uidSet, String targetMailbox) {
        state.proceed(this);
    }

    @Override
    public void fetch(FetchState state, Mailbox mailbox, MessageSet messages,
            Set<String> fetchItems) {
        state.proceed(this);
    }

    @Override
    public void uidFetch(FetchState state, Mailbox mailbox, MessageSet uidSet,
            Set<String> fetchItems) {
        state.proceed(this);
    }

    @Override
    public void search(SearchState state, Mailbox mailbox, SearchCriteria criteria) {
        state.proceed(this);
    }

    @Override
    public void uidSearch(SearchState state, Mailbox mailbox, SearchCriteria criteria) {
        state.proceed(this);
    }

}
