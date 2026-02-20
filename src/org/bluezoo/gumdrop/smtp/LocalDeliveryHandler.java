/*
 * LocalDeliveryHandler.java
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

package org.bluezoo.gumdrop.smtp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.smtp.handler.*;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

/**
 * SMTP handler that delivers incoming messages to local mailboxes.
 *
 * <p>This handler accepts mail only for recipients in the configured local
 * domain. Messages are delivered to the INBOX of each recipient, using the
 * local-part of the email address as the username.
 *
 * <p>If any recipient is not in the local domain, the message is rejected
 * at RCPT TO time with a "relay denied" error.
 *
 * <h4>Configuration</h4>
 *
 * <p>This handler requires:
 * <ul>
 *   <li>A {@link MailboxFactory} - set via constructor or service</li>
 *   <li>A local domain - the domain this server accepts mail for</li>
 * </ul>
 *
 * <h4>Example</h4>
 *
 * <pre><code>
 * MailboxFactory factory = new MaildirMailboxFactory("/var/mail");
 * LocalDeliveryHandler handler = new LocalDeliveryHandler(factory, "example.com");
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MailboxFactory
 */
public class LocalDeliveryHandler
        implements ClientConnected, HelloHandler, MailFromHandler, 
                   RecipientHandler, MessageDataHandler {

    private static final Logger LOGGER = Logger.getLogger(LocalDeliveryHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private final MailboxFactory mailboxFactory;
    private final String localDomain;
    private final String hostname;

    // Per-message state
    private EmailAddress sender;
    private List<String> recipients;  // Local-parts of recipients
    private ByteArrayOutputStream messageBuffer;

    /**
     * Creates a new local delivery handler.
     *
     * @param mailboxFactory the factory for creating mailbox stores
     * @param localDomain the domain this server accepts mail for
     */
    public LocalDeliveryHandler(MailboxFactory mailboxFactory, String localDomain) {
        this(mailboxFactory, localDomain, "localhost");
    }

    /**
     * Creates a new local delivery handler.
     *
     * @param mailboxFactory the factory for creating mailbox stores
     * @param localDomain the domain this server accepts mail for
     * @param hostname the server hostname for the greeting
     */
    public LocalDeliveryHandler(MailboxFactory mailboxFactory, String localDomain,
                                String hostname) {
        if (mailboxFactory == null) {
            throw new NullPointerException(L10N.getString("err.null_mailbox_factory"));
        }
        if (localDomain == null || localDomain.isEmpty()) {
            throw new IllegalArgumentException(L10N.getString("err.empty_local_domain"));
        }
        this.mailboxFactory = mailboxFactory;
        this.localDomain = localDomain.toLowerCase();
        this.hostname = hostname != null ? hostname : "localhost";
        this.recipients = new ArrayList<String>();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ClientConnected
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void connected(ConnectedState state, Endpoint endpoint) {
        state.acceptConnection(hostname + " ESMTP Service ready", this);
    }

    @Override
    public void disconnected() {
        // Clean up any resources
        resetMessageState();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HelloHandler
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void hello(HelloState state, boolean extended, String hostname) {
        state.acceptHello(this);
    }

    @Override
    public void tlsEstablished(SecurityInfo securityInfo) {
        // TLS established - nothing special to do
    }

    @Override
    public void authenticated(AuthenticateState state, Principal principal) {
        // Accept any authenticated principal for local delivery
        state.accept(this);
    }

    @Override
    public void quit() {
        // Connection will close
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MailFromHandler
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public SMTPPipeline getPipeline() {
        return null;  // No pipeline needed
    }

    @Override
    public void mailFrom(MailFromState state, EmailAddress sender, boolean smtputf8,
                         DeliveryRequirements deliveryRequirements) {
        this.sender = sender;
        this.recipients.clear();
        this.messageBuffer = new ByteArrayOutputStream();
        state.acceptSender(this);
    }

    @Override
    public void reset(ResetState state) {
        resetMessageState();
        state.acceptReset(this);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RecipientHandler
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void rcptTo(RecipientState state, EmailAddress recipient, MailboxFactory factory) {
        // Check if recipient is in local domain
        String domain = recipient.getDomain();
        if (domain == null || !domain.toLowerCase().equals(localDomain)) {
            // Not local - reject as relay denied
            state.rejectRecipientRelayDenied(this);
            return;
        }

        // Accept and record the local-part
        String localPart = recipient.getLocalPart();
        recipients.add(localPart);
        state.acceptRecipient(this);
    }

    @Override
    public void startMessage(MessageStartState state) {
        if (recipients.isEmpty()) {
            // No recipients - shouldn't happen but handle it
            state.rejectMessage("No recipients", this);
            return;
        }

        state.acceptMessage(this);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MessageDataHandler
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void messageContent(ByteBuffer content) {
        // Buffer the message content
        if (messageBuffer != null) {
            byte[] bytes = new byte[content.remaining()];
            content.get(bytes);
            try {
                messageBuffer.write(bytes);
            } catch (IOException e) {
                // ByteArrayOutputStream doesn't throw IOException
                LOGGER.log(Level.WARNING, "Error buffering message content", e);
            }
        }
    }

    @Override
    public void messageComplete(MessageEndState state) {
        // Deliver to all recipients
        byte[] messageData = messageBuffer != null ? messageBuffer.toByteArray() : new byte[0];
        boolean allDelivered = true;
        String errorMessage = null;

        for (int i = 0; i < recipients.size(); i++) {
            String username = recipients.get(i);
            try {
                deliverToMailbox(username, messageData);
                LOGGER.log(Level.FINE, "Delivered message to {0}@{1}",
                        new Object[] { username, localDomain });
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to deliver to " + username, e);
                allDelivered = false;
                if (errorMessage == null) {
                    errorMessage = e.getMessage();
                }
            }
        }

        // Reset state for next message
        resetMessageState();

        if (allDelivered) {
            state.acceptMessageDelivery(null, this);
        } else {
            state.rejectMessageTemporary(errorMessage, this);
        }
    }

    @Override
    public void messageAborted() {
        resetMessageState();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Delivery
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Delivers message data to a user's mailbox.
     */
    private void deliverToMailbox(String username, byte[] messageData) throws IOException {
        MailboxStore store = null;
        Mailbox mailbox = null;

        try {
            store = mailboxFactory.createStore();
            store.open(username);
            mailbox = store.openMailbox("INBOX", false);

            // Append message
            mailbox.startAppendMessage(null, null);
            mailbox.appendMessageContent(ByteBuffer.wrap(messageData));
            mailbox.endAppendMessage();

        } finally {
            if (mailbox != null) {
                try {
                    mailbox.close(true);
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Error closing mailbox", e);
                }
            }
            if (store != null) {
                try {
                    store.close();
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Error closing store", e);
                }
            }
        }
    }

    /**
     * Resets per-message state.
     */
    private void resetMessageState() {
        sender = null;
        recipients.clear();
        messageBuffer = null;
    }

}
