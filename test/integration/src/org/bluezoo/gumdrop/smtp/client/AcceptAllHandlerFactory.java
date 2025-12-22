/*
 * AcceptAllHandlerFactory.java
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

package org.bluezoo.gumdrop.smtp.client;

import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.TLSInfo;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.DeliveryRequirements;
import org.bluezoo.gumdrop.smtp.SMTPPipeline;
import org.bluezoo.gumdrop.smtp.handler.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SMTP handler factory for integration testing that accepts all messages.
 * 
 * <p>This factory creates handlers that:
 * <ul>
 *   <li>Accept all connections</li>
 *   <li>Accept all MAIL FROM addresses</li>
 *   <li>Accept all RCPT TO addresses</li>
 *   <li>Accept all messages and capture their content</li>
 *   <li>Support STARTTLS and AUTH PLAIN/LOGIN</li>
 * </ul>
 * 
 * <p>Received messages can be retrieved for verification using {@link #getReceivedMessages()}.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AcceptAllHandlerFactory implements ClientConnectedFactory {

    private static final Logger logger = Logger.getLogger(AcceptAllHandlerFactory.class.getName());

    /** Queue of received messages for test verification */
    private final ConcurrentLinkedQueue<ReceivedMessage> receivedMessages = new ConcurrentLinkedQueue<>();
    
    /** Counter for generating unique queue IDs */
    private final AtomicInteger queueIdCounter = new AtomicInteger(1);
    
    /** Expected test credentials (username -> password) */
    private String expectedUsername = "testuser";
    private String expectedPassword = "testpass";

    @Override
    public ClientConnected createHandler() {
        return new AcceptAllHandler();
    }

    /**
     * Returns all received messages.
     */
    public List<ReceivedMessage> getReceivedMessages() {
        return new ArrayList<>(receivedMessages);
    }

    /**
     * Clears all received messages.
     */
    public void clearMessages() {
        receivedMessages.clear();
    }

    /**
     * Returns the number of received messages.
     */
    public int getMessageCount() {
        return receivedMessages.size();
    }

    /**
     * Sets the expected credentials for authentication.
     */
    public void setCredentials(String username, String password) {
        this.expectedUsername = username;
        this.expectedPassword = password;
    }

    /**
     * Container for a received message with envelope information.
     */
    public static class ReceivedMessage {
        private final EmailAddress sender;
        private final List<EmailAddress> recipients;
        private final byte[] content;
        private final String queueId;
        private final boolean tlsActive;
        private final String authenticatedUser;

        ReceivedMessage(EmailAddress sender, List<EmailAddress> recipients, 
                        byte[] content, String queueId, boolean tlsActive, String authenticatedUser) {
            this.sender = sender;
            this.recipients = Collections.unmodifiableList(new ArrayList<>(recipients));
            this.content = content;
            this.queueId = queueId;
            this.tlsActive = tlsActive;
            this.authenticatedUser = authenticatedUser;
        }

        public EmailAddress getSender() { return sender; }
        public List<EmailAddress> getRecipients() { return recipients; }
        public byte[] getContent() { return content; }
        public String getContentAsString() { return new String(content); }
        public String getQueueId() { return queueId; }
        public boolean isTlsActive() { return tlsActive; }
        public String getAuthenticatedUser() { return authenticatedUser; }
    }

    /**
     * Handler that accepts all commands and captures message data.
     */
    private class AcceptAllHandler implements ClientConnected, HelloHandler, 
            MailFromHandler, RecipientHandler, MessageDataHandler {

        private boolean tlsActive = false;
        private String authenticatedUser = null;
        
        // Current envelope
        private EmailAddress currentSender;
        private List<EmailAddress> currentRecipients = new ArrayList<>();
        private ByteArrayOutputStream currentMessageData = new ByteArrayOutputStream();

        @Override
        public void connected(ConnectionInfo info, ConnectedState state) {
            tlsActive = info.isSecure();
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Client connected from " + info.getRemoteAddress() + 
                           (tlsActive ? " (TLS)" : ""));
            }
            state.acceptConnection("test.example.com ESMTP AcceptAll", this);
        }

        @Override
        public void disconnected() {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Client disconnected");
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // HelloHandler
        // ─────────────────────────────────────────────────────────────────────

        @Override
        public void hello(boolean extended, String hostname, HelloState state) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine((extended ? "EHLO" : "HELO") + " from " + hostname);
            }
            state.acceptHello(this);
        }

        @Override
        public void tlsEstablished(TLSInfo tlsInfo) {
            tlsActive = true;
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("TLS established: " + tlsInfo.getProtocol() + "/" + tlsInfo.getCipherSuite());
            }
        }

        @Override
        public void authenticated(Principal principal, AuthenticateState state) {
            authenticatedUser = principal.getName();
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Authenticated: " + authenticatedUser);
            }
            state.accept(this);
        }

        @Override
        public void quit() {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("QUIT received");
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // MailFromHandler
        // ─────────────────────────────────────────────────────────────────────

        @Override
        public SMTPPipeline getPipeline() {
            return null; // No pipeline needed for basic testing
        }

        @Override
        public void mailFrom(EmailAddress sender, boolean smtputf8,
                             DeliveryRequirements deliveryRequirements,
                             MailFromState state) {
            currentSender = sender;
            currentRecipients.clear();
            currentMessageData.reset();
            
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("MAIL FROM: " + (sender != null ? sender.getAddress() : "<>"));
            }
            state.acceptSender(this);
        }

        @Override
        public void reset(ResetState state) {
            currentSender = null;
            currentRecipients.clear();
            currentMessageData.reset();
            
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("RSET");
            }
            state.acceptReset(this);
        }

        // ─────────────────────────────────────────────────────────────────────
        // RecipientHandler
        // ─────────────────────────────────────────────────────────────────────

        @Override
        public void rcptTo(EmailAddress recipient, MailboxFactory factory, RecipientState state) {
            currentRecipients.add(recipient);
            
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("RCPT TO: " + recipient.getAddress());
            }
            state.acceptRecipient(this);
        }

        @Override
        public void startMessage(MessageStartState state) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Starting message transfer");
            }
            state.acceptMessage(this);
        }

        // ─────────────────────────────────────────────────────────────────────
        // MessageDataHandler
        // ─────────────────────────────────────────────────────────────────────

        @Override
        public void messageContent(ByteBuffer content) {
            byte[] bytes = new byte[content.remaining()];
            content.get(bytes);
            try {
                currentMessageData.write(bytes);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error buffering message content", e);
            }
        }

        @Override
        public void messageComplete(MessageEndState state) {
            String queueId = String.format("Q%06d", queueIdCounter.getAndIncrement());
            byte[] content = currentMessageData.toByteArray();
            
            ReceivedMessage msg = new ReceivedMessage(
                currentSender,
                currentRecipients,
                content,
                queueId,
                tlsActive,
                authenticatedUser
            );
            receivedMessages.add(msg);
            
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Message complete: " + queueId + " (" + content.length + " bytes)");
            }
            
            // Clear envelope for next message
            currentSender = null;
            currentRecipients = new ArrayList<>();
            currentMessageData.reset();
            
            state.acceptMessageDelivery(queueId, this);
        }

        @Override
        public void messageAborted() {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Message aborted");
            }
            currentSender = null;
            currentRecipients.clear();
            currentMessageData.reset();
        }
    }
}

