/*
 * DefaultPOP3Handler.java
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

import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.security.Principal;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default POP3 handler implementation that accepts all operations.
 * 
 * <p>This handler provides passthrough behaviour using the configured
 * Realm and MailboxFactory. It accepts all connections, opens mailboxes
 * for authenticated users, and performs all mailbox operations using the
 * Mailbox API.
 * 
 * <p>This handler is used as the default when no custom handler factory
 * is configured on the server. Subclasses can override specific methods
 * to add custom policy logic.
 * 
 * <p><strong>Behaviour:</strong>
 * <ul>
 *   <li>Accepts all connections with a configurable greeting</li>
 *   <li>Opens the INBOX for authenticated users</li>
 *   <li>Permits all mailbox operations (STAT, LIST, RETR, DELE, etc.)</li>
 *   <li>Commits deletions on QUIT</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DefaultPOP3HandlerFactory
 */
public class DefaultPOP3Handler implements ClientConnected, AuthorizationHandler, 
        TransactionHandler {

    private static final Logger LOGGER = Logger.getLogger(DefaultPOP3Handler.class.getName());

    private final String greeting;

    /**
     * Creates a new DefaultPOP3Handler with the specified greeting.
     * 
     * @param greeting the greeting message to send to clients
     */
    public DefaultPOP3Handler(String greeting) {
        this.greeting = greeting;
    }

    // ============== ClientConnected ==============

    @Override
    public void connected(ConnectionInfo info, ConnectedState state) {
        state.acceptConnection(greeting, this);
    }

    @Override
    public void disconnected() {
        // No cleanup needed
    }

    // ============== AuthorizationHandler ==============

    @Override
    public void authenticate(Principal principal, MailboxFactory mailboxFactory,
                             AuthenticateState state) {
        try {
            MailboxStore store = mailboxFactory.createStore();
            store.open(principal.getName());
            
            // Open INBOX for POP3
            Mailbox mailbox = store.openMailbox("INBOX", false);
            if (mailbox == null) {
                state.reject("Mailbox not available", this);
            } else {
                state.accept(mailbox, this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to open mailbox for " + principal.getName(), e);
            state.reject("Unable to open mailbox", this);
        }
    }

    // ============== TransactionHandler ==============

    @Override
    public void mailboxStatus(Mailbox mailbox, MailboxStatusState state) {
        try {
            int count = mailbox.getMessageCount();
            long size = mailbox.getMailboxSize();
            state.sendStatus(count, size, this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to get mailbox status", e);
            state.error("Unable to get mailbox status", this);
        }
    }

    @Override
    public void list(Mailbox mailbox, int messageNumber, ListState state) {
        try {
            if (messageNumber > 0) {
                // Single message listing
                MessageDescriptor msg = mailbox.getMessage(messageNumber);
                if (msg == null) {
                    state.noSuchMessage(this);
                } else if (mailbox.isDeleted(messageNumber)) {
                    state.messageDeleted(this);
                } else {
                    state.sendListing(messageNumber, msg.getSize(), this);
                }
            } else {
                // All messages listing
                int count = mailbox.getMessageCount();
                ListState.ListWriter writer = state.beginListing(count);
                Iterator<MessageDescriptor> messages = mailbox.getMessageList();
                while (messages.hasNext()) {
                    MessageDescriptor msg = messages.next();
                    int msgNum = msg.getMessageNumber();
                    if (!mailbox.isDeleted(msgNum)) {
                        writer.message(msgNum, msg.getSize());
                    }
                }
                writer.end(this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to list messages", e);
            state.error("Unable to list messages", this);
        }
    }

    @Override
    public void retrieveMessage(Mailbox mailbox, int messageNumber, RetrieveState state) {
        try {
            MessageDescriptor msg = mailbox.getMessage(messageNumber);
            if (msg == null) {
                state.noSuchMessage(this);
            } else if (mailbox.isDeleted(messageNumber)) {
                state.messageDeleted(this);
            } else {
                ReadableByteChannel content = mailbox.getMessageContent(messageNumber);
                state.sendMessage(msg.getSize(), content, this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to retrieve message " + messageNumber, e);
            state.error("Unable to retrieve message", this);
        }
    }

    @Override
    public void markDeleted(Mailbox mailbox, int messageNumber, MarkDeletedState state) {
        try {
            MessageDescriptor msg = mailbox.getMessage(messageNumber);
            if (msg == null) {
                state.noSuchMessage(this);
            } else if (mailbox.isDeleted(messageNumber)) {
                state.alreadyDeleted(this);
            } else {
                mailbox.deleteMessage(messageNumber);
                state.markedDeleted(this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to mark message " + messageNumber + " as deleted", e);
            state.error("Unable to delete message", this);
        }
    }

    @Override
    public void reset(Mailbox mailbox, ResetState state) {
        try {
            mailbox.undeleteAll();
            int count = mailbox.getMessageCount();
            long size = mailbox.getMailboxSize();
            state.resetComplete(count, size, this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to reset mailbox", e);
            state.error("Unable to reset mailbox", this);
        }
    }

    @Override
    public void top(Mailbox mailbox, int messageNumber, int lines, TopState state) {
        try {
            MessageDescriptor msg = mailbox.getMessage(messageNumber);
            if (msg == null) {
                state.noSuchMessage(this);
            } else if (mailbox.isDeleted(messageNumber)) {
                state.messageDeleted(this);
            } else {
                ReadableByteChannel content = mailbox.getMessageTop(messageNumber, lines);
                state.sendTop(content, this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to get TOP for message " + messageNumber, e);
            state.error("Unable to get message headers", this);
        }
    }

    @Override
    public void uidl(Mailbox mailbox, int messageNumber, UidlState state) {
        try {
            if (messageNumber > 0) {
                // Single message UID
                MessageDescriptor msg = mailbox.getMessage(messageNumber);
                if (msg == null) {
                    state.noSuchMessage(this);
                } else if (mailbox.isDeleted(messageNumber)) {
                    state.messageDeleted(this);
                } else {
                    String uid = mailbox.getUniqueId(messageNumber);
                    state.sendUid(messageNumber, uid, this);
                }
            } else {
                // All messages UID listing
                UidlState.UidlWriter writer = state.beginListing();
                Iterator<MessageDescriptor> messages = mailbox.getMessageList();
                while (messages.hasNext()) {
                    MessageDescriptor msg = messages.next();
                    int msgNum = msg.getMessageNumber();
                    if (!mailbox.isDeleted(msgNum)) {
                        String uid = mailbox.getUniqueId(msgNum);
                        writer.message(msgNum, uid);
                    }
                }
                writer.end(this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to get UIDs", e);
            state.error("Unable to get unique identifiers", this);
        }
    }

    @Override
    public void quit(Mailbox mailbox, UpdateState state) {
        try {
            mailbox.close(true); // Expunge on close
            state.commitAndClose();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to commit mailbox changes", e);
            state.updateFailed("Some messages could not be deleted");
        }
    }

}

