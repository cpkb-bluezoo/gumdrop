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

import java.io.IOException;
import java.security.Principal;
import java.util.Iterator;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.mime.HeaderLineTooLongException;
import org.bluezoo.gumdrop.mime.HeaderValueTooLongException;

/**
 * Default POP3 handler implementation that accepts all operations.
 *
 * <p>This handler authorises authentication and QUIT, and performs
 * transaction-state mailbox reads using the Mailbox API. Opening the
 * mailbox at authenticate time and closing it on QUIT are delegated to
 * the protocol via {@code state.proceed(this)} so they run on
 * {@link org.bluezoo.gumdrop.StorageExecutor} rather than the SelectorLoop.
 *
 * <p>This handler is used as the default when no custom handler factory
 * is configured on the server. Subclasses can override specific methods
 * to add custom policy logic.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.pop3.DefaultPOP3Service
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
    public void connected(ConnectedState state, Endpoint endpoint) {
        state.acceptConnection(greeting, this);
    }

    @Override
    public void disconnected() {
        // No cleanup needed
    }

    // ============== AuthorizationHandler ==============

    @Override
    public void authenticate(AuthenticateState state, Principal principal,
                             MailboxFactory mailboxFactory) {
        // Authorise only; protocol opens the mailbox via StorageExecutor.
        state.proceed(this);
    }

    // ============== TransactionHandler ==============

    @Override
    public void mailboxStatus(MailboxStatusState state, Mailbox mailbox) {
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
    public void list(ListState state, Mailbox mailbox, int messageNumber) {
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
    public void retrieveMessage(RetrieveState state, Mailbox mailbox, int messageNumber) {
        try {
            MessageDescriptor msg = mailbox.getMessage(messageNumber);
            if (msg == null) {
                state.noSuchMessage(this);
            } else if (mailbox.isDeleted(messageNumber)) {
                state.messageDeleted(this);
            } else {
                // Disk open/load runs in the protocol after proceed().
                state.proceed(msg.getSize(), this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to retrieve message " + messageNumber, e);
            state.error("Unable to retrieve message", this);
        }
    }

    @Override
    public void markDeleted(MarkDeletedState state, Mailbox mailbox, int messageNumber) {
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
    public void reset(ResetState state, Mailbox mailbox) {
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
    public void top(TopState state, Mailbox mailbox, int messageNumber, int lines) {
        try {
            MessageDescriptor msg = mailbox.getMessage(messageNumber);
            if (msg == null) {
                state.noSuchMessage(this);
            } else if (mailbox.isDeleted(messageNumber)) {
                state.messageDeleted(this);
            } else {
                // Disk open/load runs in the protocol after proceed().
                state.proceed(lines, this);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to get TOP for message " + messageNumber, e);
            Throwable cause = e.getCause();
            String msg = (cause instanceof HeaderLineTooLongException)
                ? ResourceBundle.getBundle("org.bluezoo.gumdrop.pop3.L10N")
                    .getString("pop3.err.header_line_too_long")
                : (cause instanceof HeaderValueTooLongException)
                    ? ResourceBundle.getBundle("org.bluezoo.gumdrop.pop3.L10N")
                        .getString("pop3.err.header_value_too_long")
                    : "Unable to get message headers";
            state.error(msg, this);
        }
    }

    @Override
    public void uidl(UidlState state, Mailbox mailbox, int messageNumber) {
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
    public void quit(UpdateState state, Mailbox mailbox) {
        // Authorise quit; protocol closes/expunges via StorageExecutor.
        state.proceed(this);
    }

}
