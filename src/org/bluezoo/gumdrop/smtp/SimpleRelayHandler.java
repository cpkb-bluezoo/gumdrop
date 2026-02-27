/*
 * SimpleRelayHandler.java
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
import java.net.InetAddress;

import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.smtp.handler.*;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.smtp.client.SMTPClientProtocolHandler;
import org.bluezoo.gumdrop.smtp.client.handler.*;

/**
 * A simple SMTP relay handler that accepts messages and forwards them.
 *
 * <p>This handler provides a basic MTA relay implementation:
 * <ul>
 *   <li>Accepts all connections, senders, and recipients</li>
 *   <li>Buffers incoming messages in memory</li>
 *   <li>Looks up MX records for recipient domains</li>
 *   <li>Delivers messages via SMTP client</li>
 * </ul>
 *
 * <p><strong>Note:</strong> This is a simple implementation intended for
 * development and testing. Production MTAs require additional features:
 * <ul>
 *   <li>Authentication and access control</li>
 *   <li>Rate limiting and abuse prevention</li>
 *   <li>Persistent queue for retry handling</li>
 *   <li>SPF/DKIM/DMARC validation</li>
 *   <li>Content filtering</li>
 * </ul>
 *
 * <h4>Configuration</h4>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.smtp.SimpleRelayService">
 *   <listener class="org.bluezoo.gumdrop.smtp.SMTPListener" port="25"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SimpleRelayService
 */
public class SimpleRelayHandler implements ClientConnected, HelloHandler,
        MailFromHandler, RecipientHandler, MessageDataHandler {

    private static final Logger LOGGER = Logger.getLogger(SimpleRelayHandler.class.getName());

    private final DNSResolver dnsResolver;
    private final String localHostname;

    // Transaction state
    private EmailAddress sender;
    private DeliveryRequirements deliveryRequirements;
    private List<EmailAddress> recipients;
    private MessageBufferPipeline pipeline;

    /**
     * Creates a new relay handler.
     *
     * @param dnsResolver the DNS resolver for MX lookups
     * @param localHostname the local hostname for EHLO
     */
    public SimpleRelayHandler(DNSResolver dnsResolver, String localHostname) {
        this.dnsResolver = dnsResolver;
        this.localHostname = localHostname;
        this.recipients = new ArrayList<EmailAddress>();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ClientConnected
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void connected(ConnectedState state, Endpoint endpoint) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Client connected from " + endpoint.getRemoteAddress());
        }
        state.acceptConnection(localHostname + " ESMTP SimpleRelay", this);
    }

    @Override
    public void disconnected() {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Client disconnected");
        }
        resetTransaction();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HelloHandler
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void hello(HelloState state, boolean extended, String hostname) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Client HELO/EHLO: " + hostname);
        }
        state.acceptHello(this);
    }

    @Override
    public void tlsEstablished(SecurityInfo securityInfo) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("TLS established: " + securityInfo.getProtocol() + " " + securityInfo.getCipherSuite());
        }
    }

    @Override
    public void authenticated(AuthenticateState state, Principal principal) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Authenticated: " + principal.getName());
        }
        state.accept(this);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MailFromHandler
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public SMTPPipeline getPipeline() {
        pipeline = new MessageBufferPipeline();
        return pipeline;
    }

    @Override
    public void mailFrom(MailFromState state, EmailAddress sender, boolean smtputf8,
                         DeliveryRequirements deliveryRequirements) {
        this.sender = sender;
        this.deliveryRequirements = deliveryRequirements;
        this.recipients.clear();
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("MAIL FROM: " + (sender != null ? sender : "<>"));
            if (deliveryRequirements != null && !deliveryRequirements.isEmpty()) {
                logDeliveryRequirements(deliveryRequirements);
            }
        }
        
        // Validate delivery requirements we can't fulfill
        // FUTURERELEASE requires a scheduled queue, which this simple relay doesn't have
        if (deliveryRequirements != null && deliveryRequirements.isFutureRelease()) {
            // TODO: A production MTA would store the message in a scheduled queue
            // and release it at getReleaseTime(). This simple relay doesn't support
            // holding messages, so we must reject.
            state.rejectSenderPolicy("FUTURERELEASE not supported by this relay", this);
            return;
        }
        
        // DELIVERBY would require deadline tracking and bounce generation
        // We accept it but log a warning that we won't enforce the deadline
        if (deliveryRequirements != null && deliveryRequirements.hasDeliverByDeadline()) {
            // TODO: A production MTA would track the deadline and bounce the message
            // if it cannot be delivered in time. This simple relay accepts but doesn't
            // enforce the deadline.
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("DELIVERBY specified but deadline enforcement not implemented");
            }
        }
        
        // MT-PRIORITY would require a priority queue for message processing
        // We accept it but don't prioritize
        if (deliveryRequirements != null && deliveryRequirements.hasPriority()) {
            // TODO: A production MTA would use priority queues to process higher
            // priority messages first. This simple relay uses FIFO ordering.
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("MT-PRIORITY=" + deliveryRequirements.getPriority() + 
                           " (priority queuing not implemented)");
            }
        }
        
        state.acceptSender(this);
    }
    
    private void logDeliveryRequirements(DeliveryRequirements req) {
        StringBuilder sb = new StringBuilder("Delivery requirements:");
        if (req.isRequireTls()) {
            sb.append(" REQUIRETLS");
        }
        if (req.hasPriority()) {
            sb.append(" MT-PRIORITY=").append(req.getPriority());
        }
        if (req.isFutureRelease()) {
            sb.append(" FUTURERELEASE=").append(req.getReleaseTime());
        }
        if (req.hasDeliverByDeadline()) {
            sb.append(" DELIVERBY=").append(req.getDeliverByDeadline());
            sb.append(req.isDeliverByReturn() ? " (R)" : " (N)");
        }
        if (req.getDsnReturn() != null) {
            sb.append(" RET=").append(req.getDsnReturn());
        }
        if (req.getDsnEnvelopeId() != null) {
            sb.append(" ENVID=").append(req.getDsnEnvelopeId());
        }
        LOGGER.fine(sb.toString());
    }

    @Override
    public void reset(ResetState state) {
        resetTransaction();
        state.acceptReset(this);
    }

    @Override
    public void quit() {
        resetTransaction();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecipientHandler
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void rcptTo(RecipientState state, EmailAddress recipient, MailboxFactory factory) {
        recipients.add(recipient);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("RCPT TO: " + recipient);
        }
        state.acceptRecipient(this);
    }

    @Override
    public void startMessage(MessageStartState state) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("DATA/BDAT started, " + recipients.size() + " recipients");
        }
        state.acceptMessage(this);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MessageDataHandler
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void messageContent(java.nio.ByteBuffer content) {
        // Content is captured via the pipeline, not this method
    }

    @Override
    public void messageComplete(MessageEndState state) {
        byte[] messageData = pipeline.getMessageData();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Message complete: " + messageData.length + " bytes");
        }

        // Group recipients by domain for delivery
        Map<String, List<EmailAddress>> byDomain = groupByDomain(recipients);

        // Deliver to each domain
        DeliveryContext ctx = new DeliveryContext(state, byDomain, messageData, deliveryRequirements);
        ctx.deliverNext();
    }

    @Override
    public void messageAborted() {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning("Message aborted");
        }
        resetTransaction();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper methods
    // ─────────────────────────────────────────────────────────────────────────

    private void resetTransaction() {
        sender = null;
        deliveryRequirements = null;
        recipients.clear();
        pipeline = null;
    }

    private Map<String, List<EmailAddress>> groupByDomain(List<EmailAddress> recipients) {
        Map<String, List<EmailAddress>> byDomain = new HashMap<String, List<EmailAddress>>();
        for (EmailAddress rcpt : recipients) {
            String domain = rcpt.getDomain().toLowerCase();
            List<EmailAddress> list = byDomain.get(domain);
            if (list == null) {
                list = new ArrayList<EmailAddress>();
                byDomain.put(domain, list);
            }
            list.add(rcpt);
        }
        return byDomain;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message buffer pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pipeline that buffers message content to memory.
     */
    private static class MessageBufferPipeline implements SMTPPipeline {

        private final ByteArrayOutputStream buffer;
        private final BufferChannel channel;

        MessageBufferPipeline() {
            this.buffer = new ByteArrayOutputStream(8192);
            this.channel = new BufferChannel(buffer);
        }

        @Override
        public void mailFrom(EmailAddress sender) {
            // No-op
        }

        @Override
        public void rcptTo(EmailAddress recipient) {
            // No-op
        }

        @Override
        public WritableByteChannel getMessageChannel() {
            return channel;
        }

        @Override
        public void endData() {
            // No-op - message is ready
        }

        @Override
        public void reset() {
            buffer.reset();
        }

        byte[] getMessageData() {
            return buffer.toByteArray();
        }
    }

    /**
     * WritableByteChannel wrapper for ByteArrayOutputStream.
     */
    private static class BufferChannel implements WritableByteChannel {

        private final ByteArrayOutputStream buffer;
        private boolean open = true;

        BufferChannel(ByteArrayOutputStream buffer) {
            this.buffer = buffer;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            int remaining = src.remaining();
            if (src.hasArray()) {
                buffer.write(src.array(), src.arrayOffset() + src.position(), remaining);
                src.position(src.limit());
            } else {
                byte[] temp = new byte[remaining];
                src.get(temp);
                buffer.write(temp);
            }
            return remaining;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delivery context
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Manages asynchronous delivery to multiple domains.
     */
    private class DeliveryContext {

        private final MessageEndState endState;
        private final List<String> domains;
        private final Map<String, List<EmailAddress>> recipientsByDomain;
        private final byte[] messageData;
        private final DeliveryRequirements requirements;
        private int currentDomainIndex;
        private int successCount;
        private int failCount;

        DeliveryContext(MessageEndState endState,
                        Map<String, List<EmailAddress>> recipientsByDomain,
                        byte[] messageData,
                        DeliveryRequirements requirements) {
            this.endState = endState;
            this.recipientsByDomain = recipientsByDomain;
            this.domains = new ArrayList<String>(recipientsByDomain.keySet());
            this.messageData = messageData;
            this.requirements = requirements;
            this.currentDomainIndex = 0;
            this.successCount = 0;
            this.failCount = 0;
        }
        
        boolean requiresTls() {
            return requirements != null && requirements.isRequireTls();
        }

        void deliverNext() {
            if (currentDomainIndex >= domains.size()) {
                // All deliveries complete
                deliveryComplete();
                return;
            }

            String domain = domains.get(currentDomainIndex);
            List<EmailAddress> domainRecipients = recipientsByDomain.get(domain);

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Looking up MX for domain: " + domain);
            }

            // Look up MX records
            dnsResolver.queryMX(domain, new MXQueryCallback(domain, domainRecipients));
        }

        void deliveryComplete() {
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("Delivery complete: " + successCount + " success, " + failCount + " failed");
            }

            if (failCount > 0 && successCount == 0) {
                endState.rejectMessageTemporary("Delivery failed to all recipients",
                        SimpleRelayHandler.this);
            } else {
                endState.acceptMessageDelivery(null, SimpleRelayHandler.this);
            }
            resetTransaction();
        }

        /**
         * Callback for MX lookup.
         */
        private class MXQueryCallback implements DNSQueryCallback {

            private final String domain;
            private final List<EmailAddress> domainRecipients;

            MXQueryCallback(String domain, List<EmailAddress> recipients) {
                this.domain = domain;
                this.domainRecipients = recipients;
            }

            @Override
            public void onResponse(DNSMessage response) {
                List<DNSResourceRecord> answers = response.getAnswers();
                List<MXRecord> mxRecords = new ArrayList<MXRecord>();

                for (DNSResourceRecord rr : answers) {
                    if (rr.getType() == DNSType.MX) {
                        mxRecords.add(new MXRecord(rr.getMXPreference(), rr.getMXExchange()));
                    }
                }

                if (mxRecords.isEmpty()) {
                    // No MX records - try A record fallback
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine("No MX records for " + domain + ", using domain directly");
                    }
                    deliverToDomain(domain, domainRecipients);
                } else {
                    // Sort by preference (lower = higher priority)
                    java.util.Collections.sort(mxRecords);
                    String mxHost = mxRecords.get(0).exchange;
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine("MX for " + domain + ": " + mxHost);
                    }
                    deliverToDomain(mxHost, domainRecipients);
                }
            }

            @Override
            public void onError(String error) {
                LOGGER.warning("MX lookup failed for " + domain + ": " + error);
                failCount += domainRecipients.size();
                currentDomainIndex++;
                deliverNext();
            }
        }

        void deliverToDomain(String host, List<EmailAddress> domainRecipients) {
            try {
                TCPTransportFactory factory = new TCPTransportFactory();
                factory.start();
                DeliveryHandler handler = new DeliveryHandler(domainRecipients);
                SMTPClientProtocolHandler endpointHandler =
                        new SMTPClientProtocolHandler(handler);
                ClientEndpoint endpoint = new ClientEndpoint(
                        factory, host, 25);
                endpoint.connect(endpointHandler);
            } catch (IOException e) {
                LOGGER.warning("Cannot connect to " + host + ": " + e.getMessage());
                failCount += domainRecipients.size();
                currentDomainIndex++;
                deliverNext();
            }
        }

        /**
         * SMTP client handler for delivery.
         */
        private class DeliveryHandler implements ServerGreeting, ServerEhloReplyHandler,
                ServerHeloReplyHandler, ServerStarttlsReplyHandler,
                ServerMailFromReplyHandler, ServerRcptToReplyHandler,
                ServerDataReplyHandler, ServerMessageReplyHandler {

            private final List<EmailAddress> domainRecipients;
            private int recipientIndex;
            private boolean completed;
            private boolean tlsEstablished;

            DeliveryHandler(List<EmailAddress> recipients) {
                this.domainRecipients = recipients;
                this.recipientIndex = 0;
                this.completed = false;
                this.tlsEstablished = false;
            }

            // ClientHandler methods

            @Override
            public void onConnected(Endpoint endpoint) {
                // Handled by handleGreeting
            }

            @Override
            public void onError(Exception cause) {
                if (!completed) {
                    completed = true;
                    LOGGER.warning("Delivery error: " + cause.getMessage());
                    failCount += domainRecipients.size();
                    currentDomainIndex++;
                    deliverNext();
                }
            }

            @Override
            public void onDisconnected() {
                // Normal disconnect after quit
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                // Security upgrade completed
            }

            // ServerReplyHandler

            @Override
            public void handleServiceClosing(String message) {
                if (!completed) {
                    completed = true;
                    LOGGER.warning("Service closing: " + message);
                    failCount += domainRecipients.size();
                    currentDomainIndex++;
                    deliverNext();
                }
            }

            // ServerGreeting

            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo(localHostname, this);
            }

            @Override
            public void handleServiceUnavailable(String message) {
                if (!completed) {
                    completed = true;
                    LOGGER.warning("Service unavailable: " + message);
                    failCount += domainRecipients.size();
                    currentDomainIndex++;
                    deliverNext();
                }
            }

            // ServerEhloReplyHandler

            @Override
            public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                                   List<String> authMethods, boolean pipelining) {
                // Check REQUIRETLS constraint
                if (requiresTls() && !tlsEstablished) {
                    if (starttls) {
                        // Upgrade to TLS before proceeding
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.fine("REQUIRETLS: upgrading connection to TLS");
                        }
                        session.starttls(this);
                        return;
                    } else {
                        // Server doesn't support STARTTLS but message requires it
                        // Must bounce the message rather than deliver insecurely
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.warning("REQUIRETLS: server does not support STARTTLS, " +
                                          "bouncing message for " + domainRecipients.size() + 
                                          " recipients");
                        }
                        session.quit();
                        if (!completed) {
                            completed = true;
                            failCount += domainRecipients.size();
                            currentDomainIndex++;
                            deliverNext();
                        }
                        return;
                    }
                }
                
                // TODO: Forward DSN parameters (RET, ENVID) to the next hop.
                // This would require extending ClientSession.mailFrom() to accept
                // delivery options. For now, DSN parameters are accepted but not
                // propagated.
                
                session.mailFrom(sender, this);
            }

            @Override
            public void handleEhloNotSupported(ClientHelloState hello) {
                // HELO doesn't support STARTTLS, so if REQUIRETLS is set we must fail
                if (requiresTls()) {
                    LOGGER.warning("REQUIRETLS: server doesn't support ESMTP, bouncing message");
                    hello.quit();
                    if (!completed) {
                        completed = true;
                        failCount += domainRecipients.size();
                        currentDomainIndex++;
                        deliverNext();
                    }
                    return;
                }
                // Fall back to HELO
                hello.helo(localHostname, this);
            }

            // ServerStarttlsReplyHandler

            @Override
            public void handleTlsEstablished(ClientPostTls postTls) {
                tlsEstablished = true;
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("TLS established, re-issuing EHLO");
                }
                // Must re-EHLO after STARTTLS per RFC 3207
                postTls.ehlo(localHostname, this);
            }

            @Override
            public void handleTlsUnavailable(ClientSession session) {
                // TLS failed but was required
                if (requiresTls()) {
                    LOGGER.warning("REQUIRETLS: TLS handshake failed, bouncing message");
                    session.quit();
                    if (!completed) {
                        completed = true;
                        failCount += domainRecipients.size();
                        currentDomainIndex++;
                        deliverNext();
                    }
                    return;
                }
                // TLS not required, continue without it
                session.mailFrom(sender, DeliveryHandler.this);
            }

            // ServerHeloReplyHandler

            @Override
            public void handleHelo(ClientSession session) {
                session.mailFrom(sender, this);
            }

            // handleTemporaryFailure for EHLO/HELO is in ServerEhloReplyHandler section
            // handlePermanentFailure is shared with other interfaces

            // ServerMailFromReplyHandler

            @Override
            public void handleMailFromOk(ClientEnvelope envelope) {
                // Add first recipient
                envelope.rcptTo(domainRecipients.get(recipientIndex++), this);
            }

            // Note: handleTemporaryFailure(ClientSession) and handlePermanentFailure(String)
            // are shared between ServerMailFromReplyHandler and ServerMessageReplyHandler
            // and are defined in the ServerMessageReplyHandler section below

            // ServerRcptToReplyHandler

            @Override
            public void handleRcptToOk(ClientEnvelopeReady envelope) {
                if (recipientIndex < domainRecipients.size()) {
                    // More recipients to add
                    envelope.rcptTo(domainRecipients.get(recipientIndex++), this);
                } else {
                    // All recipients added, send DATA
                    envelope.data(this);
                }
            }

            @Override
            public void handleTemporaryFailure(ClientEnvelopeState state) {
                LOGGER.warning("RCPT TO temporary failure for " +
                        domainRecipients.get(recipientIndex - 1));
                failCount++;
                if (recipientIndex < domainRecipients.size()) {
                    state.rcptTo(domainRecipients.get(recipientIndex++), this);
                } else if (state.hasAcceptedRecipients()) {
                    ((ClientEnvelopeReady) state).data(this);
                } else {
                    state.quit();
                    currentDomainIndex++;
                    deliverNext();
                }
            }

            @Override
            public void handleRecipientRejected(ClientEnvelopeState state) {
                LOGGER.warning("RCPT TO rejected for " +
                        domainRecipients.get(recipientIndex - 1));
                failCount++;
                if (recipientIndex < domainRecipients.size()) {
                    state.rcptTo(domainRecipients.get(recipientIndex++), this);
                } else if (state.hasAcceptedRecipients()) {
                    ((ClientEnvelopeReady) state).data(this);
                } else {
                    state.quit();
                    currentDomainIndex++;
                    deliverNext();
                }
            }

            // ServerDataReplyHandler

            @Override
            public void handleReadyForData(ClientMessageData data) {
                // Write message content
                ByteBuffer content = ByteBuffer.wrap(messageData);
                data.writeContent(content);
                data.endMessage(this);
            }

            @Override
            public void handleTemporaryFailure(ClientEnvelopeReady envelope) {
                LOGGER.warning("DATA temporary failure");
                envelope.quit();
                failCount += domainRecipients.size() - failCount;
                currentDomainIndex++;
                deliverNext();
            }

            @Override
            public void handlePermanentFailure(String message) {
                LOGGER.warning("DATA permanent failure: " + message);
                failCount += domainRecipients.size() - failCount;
                currentDomainIndex++;
                deliverNext();
            }

            // ServerMessageReplyHandler

            @Override
            public void handleMessageAccepted(String queueId, ClientSession session) {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("Message accepted, queue ID: " + queueId);
                }
                successCount += domainRecipients.size() - failCount;
                session.quit();
                currentDomainIndex++;
                deliverNext();
            }

            @Override
            public void handleTemporaryFailure(ClientSession session) {
                LOGGER.warning("Message temporary failure");
                session.quit();
                failCount += domainRecipients.size() - failCount;
                currentDomainIndex++;
                deliverNext();
            }

            @Override
            public void handlePermanentFailure(String message, ClientSession session) {
                LOGGER.warning("Message permanent failure: " + message);
                session.quit();
                failCount += domainRecipients.size() - failCount;
                currentDomainIndex++;
                deliverNext();
            }
        }
    }

    /**
     * MX record with preference for sorting.
     */
    private static class MXRecord implements Comparable<MXRecord> {
        final int preference;
        final String exchange;

        MXRecord(int preference, String exchange) {
            this.preference = preference;
            this.exchange = exchange;
        }

        @Override
        public int compareTo(MXRecord other) {
            return Integer.compare(this.preference, other.preference);
        }
    }

}

