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
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageSet;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.mailbox.StoreAction;
import org.bluezoo.gumdrop.quota.QuotaManager;

import java.io.IOException;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default IMAP handler implementation that accepts all operations.
 * 
 * <p>This handler provides passthrough behaviour using the configured
 * Realm and MailboxFactory. It is used as the default when no custom
 * handler factory is configured on the server.
 * 
 * <p>All operations are accepted and performed using the Mailbox API.
 * Subclasses can override specific methods to add custom policy logic.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultIMAPHandler implements ClientConnected, NotAuthenticatedHandler,
        AuthenticatedHandler, SelectedHandler {

    private static final Logger LOGGER = Logger.getLogger(DefaultIMAPHandler.class.getName());

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
        try {
            MailboxStore store = factory.createStore();
            store.open(principal.getName());
            state.accept(store, this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to open mailbox store for " + principal.getName(), e);
            state.reject("Mailbox unavailable", this);
        }
    }

    // ============== AuthenticatedHandler ==============

    @Override
    public void select(SelectState state, MailboxStore store, String mailboxName) {
        try {
            Mailbox mailbox = store.openMailbox(mailboxName, false);
            if (mailbox == null) {
                state.mailboxNotFound("Mailbox not found", this);
            } else {
                state.selectOk(mailbox, false, Flag.allFlags(), this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to select mailbox: " + mailboxName, e);
            state.selectFailed("Cannot open mailbox", this);
        }
    }

    @Override
    public void examine(SelectState state, MailboxStore store, String mailboxName) {
        try {
            Mailbox mailbox = store.openMailbox(mailboxName, true);
            if (mailbox == null) {
                state.mailboxNotFound("Mailbox not found", this);
            } else {
                state.selectOk(mailbox, true, Flag.allFlags(), this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to examine mailbox: " + mailboxName, e);
            state.selectFailed("Cannot open mailbox", this);
        }
    }

    @Override
    public void create(CreateState state, MailboxStore store, String mailboxName) {
        try {
            store.createMailbox(mailboxName);
            state.created(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create mailbox: " + mailboxName, e);
            state.cannotCreate("Cannot create mailbox", this);
        }
    }

    @Override
    public void delete(DeleteState state, MailboxStore store, String mailboxName) {
        try {
            store.deleteMailbox(mailboxName);
            state.deleted(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to delete mailbox: " + mailboxName, e);
            state.cannotDelete("Cannot delete mailbox", this);
        }
    }

    @Override
    public void rename(RenameState state, MailboxStore store, String oldName, String newName) {
        try {
            store.renameMailbox(oldName, newName);
            state.renamed(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to rename mailbox: " + oldName + " to " + newName, e);
            state.cannotRename("Cannot rename mailbox", this);
        }
    }

    @Override
    public void subscribe(SubscribeState state, MailboxStore store, String mailboxName) {
        try {
            store.subscribe(mailboxName);
            state.subscribed(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to subscribe to mailbox: " + mailboxName, e);
            state.subscribeFailed("Cannot subscribe", this);
        }
    }

    @Override
    public void unsubscribe(SubscribeState state, MailboxStore store, String mailboxName) {
        try {
            store.unsubscribe(mailboxName);
            state.subscribed(this); // Same response for both subscribe and unsubscribe
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to unsubscribe from mailbox: " + mailboxName, e);
            state.subscribeFailed("Cannot unsubscribe", this);
        }
    }

    @Override
    public void list(ListState state, MailboxStore store, String reference, String pattern) {
        try {
            List<String> mailboxes = store.listMailboxes(reference, pattern);
            String delimiter = String.valueOf(store.getHierarchyDelimiter());
            for (String name : mailboxes) {
                Set<MailboxAttribute> attrs = store.getMailboxAttributes(name);
                state.listEntry(attrs, delimiter, name);
            }
            state.listComplete(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to list mailboxes", e);
            state.listFailed("Cannot list mailboxes", this);
        }
    }

    @Override
    public void lsub(ListState state, MailboxStore store, String reference, String pattern) {
        try {
            List<String> mailboxes = store.listSubscribed(reference, pattern);
            String delimiter = String.valueOf(store.getHierarchyDelimiter());
            for (String name : mailboxes) {
                Set<MailboxAttribute> attrs = store.getMailboxAttributes(name);
                state.listEntry(attrs, delimiter, name);
            }
            state.listComplete(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to list subscribed mailboxes", e);
            state.listFailed("Cannot list subscribed mailboxes", this);
        }
    }

    @Override
    public void status(AuthenticatedStatusState state, MailboxStore store,
            String mailboxName, Set<StatusItem> statusItems) {
        state.proceed(this);
    }

    @Override
    public void append(AppendState state, MailboxStore store, String mailboxName,
            Set<Flag> flags, OffsetDateTime internalDate) {
        try {
            Mailbox mailbox = store.openMailbox(mailboxName, false);
            if (mailbox == null) {
                state.tryCreate(this);
            } else {
                // For append, we need an AppendDataHandler - use a simple inline one
                state.readyForData(mailbox, new DefaultAppendDataHandler(this));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to open mailbox for append: " + mailboxName, e);
            state.appendFailed("Cannot open mailbox", this);
        }
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
        try {
            mailbox.close(true); // Expunge on close
            state.closed(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to close mailbox", e);
            state.closeFailed("Cannot close mailbox", this);
        }
    }

    @Override
    public void unselect(CloseState state, Mailbox mailbox) {
        try {
            mailbox.close(false); // Don't expunge
            state.closed(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to unselect mailbox", e);
            state.closeFailed("Cannot unselect mailbox", this);
        }
    }

    @Override
    public void expunge(ExpungeState state, Mailbox mailbox) {
        try {
            // Expunge messages marked as deleted
            for (int i = mailbox.getMessageCount(); i >= 1; i--) {
                Set<Flag> flags = mailbox.getFlags(i);
                if (flags.contains(Flag.DELETED)) {
                    mailbox.deleteMessage(i);
                    state.messageExpunged(i);
                }
            }
            state.expungeComplete(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to expunge mailbox", e);
            state.expungeFailed("Cannot expunge mailbox", this);
        }
    }

    @Override
    public void uidExpunge(ExpungeState state, Mailbox mailbox, MessageSet uidSet) {
        // UID EXPUNGE only expunges messages in the specified UID set
        // For now, just do a regular expunge (TODO: filter by UID set)
        expunge(state, mailbox);
    }

    @Override
    public void store(StoreState state, Mailbox mailbox, MessageSet messages,
            StoreAction action, Set<Flag> flags, boolean silent) {
        try {
            int msgCount = mailbox.getMessageCount();
            List<MessageSet.Range> ranges = messages.getRanges();
            for (MessageSet.Range range : ranges) {
                long startVal = range.getStart();
                long endVal = range.getEnd();
                int start = (startVal == MessageSet.WILDCARD) ? msgCount : (int) startVal;
                int end = (endVal == MessageSet.WILDCARD) ? msgCount : (int) endVal;
                for (int msgNum = start; msgNum <= end; msgNum++) {
                    boolean add = (action == StoreAction.ADD);
                    mailbox.setFlags(msgNum, flags, add);
                    if (!silent) {
                        state.flagsUpdated(msgNum, mailbox.getFlags(msgNum));
                    }
                }
            }
            state.storeComplete(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to store flags", e);
            state.storeFailed("Cannot store flags", this);
        }
    }

    @Override
    public void uidStore(StoreState state, Mailbox mailbox, MessageSet uidSet,
            StoreAction action, Set<Flag> flags, boolean silent) {
        // TODO: Implement UID-based store
        state.storeFailed("UID STORE not yet implemented", this);
    }

    @Override
    public void copy(CopyState state, MailboxStore store, Mailbox mailbox,
            MessageSet messages, String targetMailbox) {
        // TODO: Implement COPY
        state.copyFailed("COPY not yet implemented", this);
    }

    @Override
    public void uidCopy(CopyState state, MailboxStore store, Mailbox mailbox,
            MessageSet uidSet, String targetMailbox) {
        // TODO: Implement UID COPY
        state.copyFailed("UID COPY not yet implemented", this);
    }

    @Override
    public void move(MoveState state, MailboxStore store, Mailbox mailbox,
            MessageSet messages, String targetMailbox) {
        // TODO: Implement MOVE
        state.moveFailed("MOVE not yet implemented", this);
    }

    @Override
    public void uidMove(MoveState state, MailboxStore store, Mailbox mailbox,
            MessageSet uidSet, String targetMailbox) {
        // TODO: Implement UID MOVE
        state.moveFailed("UID MOVE not yet implemented", this);
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

    // ============== Default AppendDataHandler ==============

    /**
     * Simple AppendDataHandler that just accepts the data.
     */
    private static class DefaultAppendDataHandler implements AppendDataHandler {
        private final DefaultIMAPHandler parent;

        DefaultAppendDataHandler(DefaultIMAPHandler parent) {
            this.parent = parent;
        }

        @Override
        public void appendData(Mailbox mailbox, java.nio.ByteBuffer data) {
            // Data is accumulated by the connection - nothing to do here
        }

        @Override
        public void appendComplete(AppendCompleteState state, Mailbox mailbox) {
            state.appended(parent);
        }
    }

}
