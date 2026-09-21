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

package org.bluezoo.gumdrop.smtp.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;

import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.security.Principal;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.smtp.DeliveryRequirements;
import org.bluezoo.gumdrop.smtp.SmtpPipeline;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.smtp.client.SmtpClientProtocolHandler;
import org.bluezoo.gumdrop.smtp.client.*;

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
 * <p><strong>Security warning:</strong> This handler is an <em>open
 * relay</em>: it accepts mail from any sender to any recipient and
 * forwards it without authentication or access control. Exposing it to
 * untrusted networks will result in spam abuse. It is intended only for
 * development, testing, and closed internal environments. Production
 * deployments must add:
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
 * SmtpServer server = SmtpServer.compose()
 *         .listener(new SmtpListener().port(25).bindWildcard())
 *         .sessionProvider(new SimpleRelaySessionProvider()
 *                 .hostname("relay.example.com"))
 *         .server();
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321#section-3.7">RFC 5321 §3.7</a> (mail relay)
 * @see org.bluezoo.gumdrop.smtp.server.SimpleRelaySessionProvider
 */
public class SimpleRelayHandler implements ClientConnected, HelloHandler,
        MailFromHandler, RecipientHandler, MessageDataHandler {

    /** Default SMTP port for outbound relay delivery (RFC 5321). */
    public static final int DEFAULT_DELIVERY_PORT = 25;

    private static final Logger LOGGER = Logger.getLogger(SimpleRelayHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private final DnsResolver dnsResolver;
    private final String localHostname;
    private final int deliveryPort;

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
    public SimpleRelayHandler(DnsResolver dnsResolver, String localHostname) {
        this(dnsResolver, localHostname, DEFAULT_DELIVERY_PORT);
    }

    /**
     * Creates a new relay handler with a non-default outbound SMTP port.
     *
     * @param dnsResolver the DNS resolver for MX lookups
     * @param localHostname the local hostname for EHLO
     * @param deliveryPort the TCP port for outbound SMTP delivery to MX hosts
     */
    public SimpleRelayHandler(DnsResolver dnsResolver, String localHostname,
                              int deliveryPort) {
        if (deliveryPort <= 0 || deliveryPort > 65535) {
            throw new IllegalArgumentException("deliveryPort: " + deliveryPort);
        }
        this.dnsResolver = dnsResolver;
        this.localHostname = localHostname;
        this.deliveryPort = deliveryPort;
        this.recipients = new ArrayList<EmailAddress>();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ClientConnected
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void connected(ConnectedState state, Endpoint endpoint) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.client_connected"), endpoint.getRemoteAddress()));
        }
        state.acceptConnection(localHostname + " ESMTP SimpleRelay", this);
    }

    @Override
    public void disconnected() {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("relay.fine.client_disconnected"));
        }
        resetTransaction();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HelloHandler
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void hello(HelloState state, boolean extended, String hostname) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.client_helo"), hostname));
        }
        state.acceptHello(this);
    }

    @Override
    public void tlsEstablished(SecurityInfo securityInfo) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.tls_established"), securityInfo.getProtocol(), securityInfo.getCipherSuite()));
        }
    }

    @Override
    public void authenticated(AuthenticateState state, Principal principal) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.authenticated"), principal.getName()));
        }
        state.accept(this);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MailFromHandler
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public SmtpPipeline getPipeline() {
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
            LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.mail_from"), sender != null ? sender : "<>"));
            if (deliveryRequirements != null && !deliveryRequirements.isEmpty()) {
                logDeliveryRequirements(deliveryRequirements);
            }
        }
        
        // Validate delivery requirements we can't fulfill
        // FUTURERELEASE requires a scheduled queue, which this simple relay doesn't have
        if (deliveryRequirements != null && deliveryRequirements.isFutureRelease()) {
            // A production MTA would store the message in a scheduled queue
            // and release it at getReleaseTime(). This simple relay doesn't
            // support holding messages, so we reject.
            state.rejectSenderPolicy("FUTURERELEASE not supported by this relay", this);
            return;
        }
        
        // DELIVERBY would require deadline tracking and bounce generation
        // We accept it but log a warning that we won't enforce the deadline
        if (deliveryRequirements != null && deliveryRequirements.hasDeliverByDeadline()) {
            // A production MTA would track the deadline and bounce the
            // message if it cannot be delivered in time. This simple relay
            // accepts but doesn't enforce the deadline.
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(L10N.getString("warn.deliverby_not_enforced"));
            }
        }
        
        // MT-PRIORITY would require a priority queue for message processing
        // We accept it but don't prioritize
        if (deliveryRequirements != null && deliveryRequirements.hasPriority()) {
            // A production MTA would use priority queues to process higher
            // priority messages first. This simple relay uses FIFO ordering.
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("relay.fine.mt_priority"),
                        deliveryRequirements.getPriority()));
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
            LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.rcpt_to"), recipient));
        }
        state.acceptRecipient(this);
    }

    @Override
    public void startMessage(MessageStartState state) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.data_started"), recipients.size()));
        }
        state.acceptMessage(this);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MessageDataHandler
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void messageContent(ByteBuffer content) {
        // Content is captured via the pipeline, not this method
    }

    @Override
    public void messageComplete(MessageEndState state) {
        byte[] messageData = pipeline.getMessageData();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.message_complete"), messageData.length));
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
            LOGGER.warning(L10N.getString("warn.message_aborted"));
        }
        resetTransaction();
    }

    @Override
    public boolean wantsPause() {
        return false;
    }

    @Override
    public void setResumeCallback(Runnable callback) {
        // Relay handler never pauses
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
    private static class MessageBufferPipeline implements SmtpPipeline {

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
                LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.mx_lookup"), domain));
            }

            // Look up MX records
            dnsResolver.queryMX(domain, new MXQueryCallback(domain, domainRecipients));
        }

        void deliveryComplete() {
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info(MessageFormat.format(
                        L10N.getString("info.delivery_complete"), successCount, failCount));
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
        private class MXQueryCallback implements DnsQueryCallback {

            private final String domain;
            private final List<EmailAddress> domainRecipients;

            MXQueryCallback(String domain, List<EmailAddress> recipients) {
                this.domain = domain;
                this.domainRecipients = recipients;
            }

            @Override
            public void onResponse(DnsMessage response) {
                List<DnsResourceRecord> answers = response.getAnswers();
                List<MXRecord> mxRecords = new ArrayList<MXRecord>();

                for (DnsResourceRecord rr : answers) {
                    if (rr.getType() == DnsType.MX) {
                        mxRecords.add(new MXRecord(rr.getMXPreference(), rr.getMXExchange()));
                    }
                }

                if (mxRecords.isEmpty()) {
                    // No MX records - try A record fallback
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.no_mx_records"), domain));
                    }
                    deliverToDomain(domain, domainRecipients);
                } else {
                    // Sort by preference (lower = higher priority)
                    Collections.sort(mxRecords);
                    String mxHost = mxRecords.get(0).exchange;
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine(MessageFormat.format(L10N.getString("relay.fine.mx_host"), domain, mxHost));
                    }
                    deliverToDomain(mxHost, domainRecipients);
                }
            }

            @Override
            public void onError(String error) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.mx_lookup_failed"), domain, error));
                failCount += domainRecipients.size();
                currentDomainIndex++;
                deliverNext();
            }
        }

        void deliverToDomain(String host, List<EmailAddress> domainRecipients) {
            try {
                TcpTransportFactory factory = new TcpTransportFactory();
                factory.start();
                DeliveryHandler handler = new DeliveryHandler(domainRecipients);
                SmtpClientProtocolHandler endpointHandler =
                        new SmtpClientProtocolHandler(handler);
                ClientEndpoint endpoint = new ClientEndpoint(
                        factory, host, deliveryPort);
                endpoint.setDnsResolver(dnsResolver);
                endpoint.connect(dnsResolver.getSelectorLoop().getGumdrop(), endpointHandler);
            } catch (IOException e) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.cannot_connect"), host, e.getMessage()));
                failCount += domainRecipients.size();
                currentDomainIndex++;
                deliverNext();
            }
        }

        /**
         * SMTP client handler for delivery.
         */
        private class DeliveryHandler implements RemoteGreeting, EhloReplyHandler,
                HeloReplyHandler, StarttlsReplyHandler,
                MailFromReplyHandler, RcptToReplyHandler,
                DataReplyHandler, MessageReplyHandler {

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
                    LOGGER.warning(MessageFormat.format(
                            L10N.getString("warn.delivery_error"), cause.getMessage()));
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

            // ReplyHandler

            @Override
            public void handleServiceClosing(String message) {
                if (!completed) {
                    completed = true;
                    LOGGER.warning(MessageFormat.format(
                            L10N.getString("warn.service_closing"), message));
                    failCount += domainRecipients.size();
                    currentDomainIndex++;
                    deliverNext();
                }
            }

            // RemoteGreeting

            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo(localHostname, this);
            }

            @Override
            public void handleServiceUnavailable(String message) {
                if (!completed) {
                    completed = true;
                    LOGGER.warning(MessageFormat.format(
                            L10N.getString("warn.service_unavailable"), message));
                    failCount += domainRecipients.size();
                    currentDomainIndex++;
                    deliverNext();
                }
            }

            // EhloReplyHandler

            @Override
            public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                                   List<String> authMethods, boolean pipelining) {
                // Check REQUIRETLS constraint
                if (requiresTls() && !tlsEstablished) {
                    if (starttls) {
                        // Upgrade to TLS before proceeding
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.fine(L10N.getString("relay.fine.requiretls_upgrading"));
                        }
                        session.starttls(this);
                        return;
                    } else {
                        // Server doesn't support STARTTLS but message requires it
                        // Must bounce the message rather than deliver insecurely
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.warning(MessageFormat.format(
                                    L10N.getString("warn.requiretls_no_starttls"),
                                    domainRecipients.size()));
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
                
                // DSN parameters (RET, ENVID) are accepted but not propagated
                // to the next hop. A full MTA would extend ClientSession.mailFrom()
                // to forward delivery options.
                
                session.mailFrom(sender, this);
            }

            @Override
            public void handleEhloNotSupported(ClientHelloState hello) {
                // HELO doesn't support STARTTLS, so if REQUIRETLS is set we must fail
                if (requiresTls()) {
                    LOGGER.warning(L10N.getString("warn.requiretls_no_esmtp"));
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

            // StarttlsReplyHandler

            @Override
            public void handleTlsEstablished(ClientPostTls postTls) {
                tlsEstablished = true;
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(L10N.getString("relay.fine.tls_reissue_ehlo"));
                }
                // Must re-EHLO after STARTTLS per RFC 3207
                postTls.ehlo(localHostname, this);
            }

            @Override
            public void handleTlsUnavailable(ClientSession session) {
                // TLS failed but was required
                if (requiresTls()) {
                    LOGGER.warning(L10N.getString("warn.requiretls_handshake_failed"));
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

            // HeloReplyHandler

            @Override
            public void handleHelo(ClientSession session) {
                session.mailFrom(sender, this);
            }

            // handleTemporaryFailure for EHLO/HELO is in EhloReplyHandler section
            // handlePermanentFailure is shared with other interfaces

            // MailFromReplyHandler

            @Override
            public void handleMailFromOk(ClientEnvelope envelope) {
                // Add first recipient
                envelope.rcptTo(domainRecipients.get(recipientIndex++), this);
            }

            // Note: handleTemporaryFailure(ClientSession) and handlePermanentFailure(String)
            // are shared between MailFromReplyHandler and MessageReplyHandler
            // and are defined in the MessageReplyHandler section below

            // RcptToReplyHandler

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
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.rcpt_temp_failure"), domainRecipients.get(recipientIndex - 1)));
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
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.rcpt_rejected"), domainRecipients.get(recipientIndex - 1)));
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

            // DataReplyHandler

            @Override
            public void handleReadyForData(ClientMessageData data) {
                // Write message content
                ByteBuffer content = ByteBuffer.wrap(messageData);
                data.writeContent(content);
                data.endMessage(this);
            }

            @Override
            public void handleTemporaryFailure(ClientEnvelopeReady envelope) {
                LOGGER.warning(L10N.getString("warn.data_temp_failure"));
                envelope.quit();
                failCount += domainRecipients.size() - failCount;
                currentDomainIndex++;
                deliverNext();
            }

            @Override
            public void handlePermanentFailure(String message) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.data_permanent_failure"), message));
                failCount += domainRecipients.size() - failCount;
                currentDomainIndex++;
                deliverNext();
            }

            // MessageReplyHandler

            @Override
            public void handleMessageAccepted(String queueId, ClientSession session) {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info(MessageFormat.format(
                            L10N.getString("info.message_accepted"), queueId));
                }
                successCount += domainRecipients.size() - failCount;
                session.quit();
                currentDomainIndex++;
                deliverNext();
            }

            @Override
            public void handleTemporaryFailure(ClientSession session) {
                LOGGER.warning(L10N.getString("warn.message_temp_failure"));
                session.quit();
                failCount += domainRecipients.size() - failCount;
                currentDomainIndex++;
                deliverNext();
            }

            @Override
            public void handlePermanentFailure(String message, ClientSession session) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.message_permanent_failure"), message));
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

