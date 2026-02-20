/*
 * package-info.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

/**
 * SMTP (Simple Mail Transfer Protocol) server implementation.
 *
 * <p>This package provides a full-featured SMTP server for receiving email,
 * with support for modern extensions including STARTTLS, authentication,
 * and message size limits.
 *
 * <h2>Staged Handler Pattern</h2>
 *
 * <p>The SMTP server uses a staged handler pattern that guides implementers
 * through the protocol flow with type-safe interfaces. Each stage provides
 * only the operations valid for that protocol state, making out-of-order
 * responses impossible at compile time.
 *
 * <h3>Handler Interfaces (what you implement)</h3>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.ClientConnected} - Entry point for new connections</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.HelloHandler} - Receives HELO/EHLO, STARTTLS, AUTH</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.HelloHandler#tlsEstablished} - Receives post-STARTTLS notification</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.MailFromHandler} - Receives MAIL FROM commands</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.RecipientHandler} - Receives RCPT TO, DATA/BDAT</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.MessageDataHandler} - Receives message completion</li>
 * </ul>
 *
 * <h3>State Interfaces (provided by SMTPProtocolHandler)</h3>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.ConnectedState} - Accept/reject connection</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.HelloState} - Accept/reject greeting</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.AuthenticateState} - Auth challenge/success/failure</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.MailFromState} - Accept/reject sender</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.RecipientState} - Accept/reject recipient</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.MessageStartState} - Accept/reject message start</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.MessageEndState} - Accept/reject message</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.handler.ResetState} - Handle RSET</li>
 * </ul>
 *
 * <h2>Example Handler</h2>
 *
 * <pre>{@code
 * public class MyMailHandler implements ClientConnected, HelloHandler, 
 *                                        MailFromHandler, RecipientHandler,
 *                                        MessageDataHandler {
 *     
 *     public void connected(ConnectedState state, Endpoint endpoint) {
 *         state.acceptConnection("mail.example.com ESMTP", this);
 *     }
 *     
 *     public void hello(HelloState state, boolean extended, String hostname) {
 *         state.acceptHello(this);  // transitions to MailFromHandler
 *     }
 *     
 *     public void mailFrom(MailFromState state, EmailAddress sender, boolean smtputf8,
 *                          DeliveryRequirements delivery) {
 *         state.acceptSender(this);  // transitions to RecipientHandler
 *     }
 *     
 *     public void rcptTo(RecipientState state, EmailAddress recipient, MailboxFactory factory) {
 *         state.acceptRecipient(this);
 *     }
 *     
 *     public void startMessage(MessageStartState state) {
 *         state.acceptMessage(this);  // transitions to MessageDataHandler
 *     }
 *     
 *     public void messageComplete(MessageEndState state) {
 *         state.acceptMessageDelivery("queued-123", this);
 *     }
 *     
 *     // Other required methods...
 * }
 * }</pre>
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.smtp.SMTPService} - Abstract base for
 *       SMTP application services; owns configuration and creates
 *       per-connection handlers</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.SMTPListener} - TCP transport
 *       listener for SMTP connections</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.SMTPProtocolHandler} - Handles
 *       a single SMTP session and command processing</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.SMTPPipeline} - Generic interface
 *       for message processing pipelines</li>
 * </ul>
 *
 * <h2>Pipeline Architecture</h2>
 *
 * <p>Gumdrop provides a pipeline mechanism for processing messages as they
 * stream through the SMTP server. The {@link org.bluezoo.gumdrop.smtp.SMTPPipeline}
 * interface allows pluggable processing without modifying core SMTP code:
 *
 * <pre>{@code
 * public interface SMTPPipeline {
 *     void mailFrom(EmailAddress sender);
 *     void rcptTo(EmailAddress recipient);
 *     WritableByteChannel getMessageChannel();  // or null
 *     void endData();
 *     void reset();
 * }
 * }</pre>
 *
 * <p>The pipeline is obtained from {@link org.bluezoo.gumdrop.smtp.handler.MailFromHandler#getPipeline()}
 * when a sender is accepted. The endpoint handler automatically:
 * <ul>
 *   <li>Calls {@code mailFrom()} when sender is accepted</li>
 *   <li>Calls {@code rcptTo()} for each accepted recipient</li>
 *   <li>Writes raw message bytes to the channel returned by {@code getMessageChannel()}</li>
 *   <li>Calls {@code endData()} when the message is complete</li>
 *   <li>Calls {@code reset()} on RSET or connection close</li>
 * </ul>
 *
 * <p>This enables various processing pipelines such as:
 * <ul>
 *   <li>Email authentication (SPF, DKIM, DMARC) - see {@link org.bluezoo.gumdrop.smtp.auth.AuthPipeline}</li>
 *   <li>Content filtering and virus scanning</li>
 *   <li>Message archiving and journaling</li>
 *   <li>Rate limiting and quota enforcement</li>
 * </ul>
 *
 * <h2>SMTP Extensions Supported</h2>
 *
 * <ul>
 *   <li>STARTTLS (RFC 3207) - Upgrade to TLS encryption</li>
 *   <li>AUTH (RFC 4954) - SASL authentication</li>
 *   <li>SIZE (RFC 1870) - Message size declaration</li>
 *   <li>8BITMIME (RFC 6152) - 8-bit MIME transport</li>
 *   <li>SMTPUTF8 (RFC 6531) - Internationalized email addresses and headers</li>
 *   <li>PIPELINING (RFC 2920) - Command pipelining</li>
 *   <li>CHUNKING (RFC 3030) - BDAT command for chunked content transfer</li>
 *   <li>BINARYMIME (RFC 3030) - Binary message content (requires BDAT)</li>
 *   <li>ENHANCEDSTATUSCODES (RFC 2034) - Enhanced status codes</li>
 *   <li>DSN (RFC 3461) - Delivery Status Notifications</li>
 *   <li>LIMITS (RFC 9422) - Advertise recipient and transaction limits</li>
 *   <li>REQUIRETLS (RFC 8689) - Require TLS for message transmission</li>
 *   <li>MT-PRIORITY (RFC 6710) - Message transfer priority</li>
 *   <li>FUTURERELEASE (RFC 4865) - Hold message for future delivery</li>
 *   <li>DELIVERBY (RFC 2852) - Delivery deadline specification</li>
 *   <li>XCLIENT (Postfix) - Proxy protocol for passing original client info</li>
 * </ul>
 *
 * <h2>Delivery Requirements</h2>
 *
 * <p>Several extensions allow senders to specify delivery requirements that
 * handlers must respect when relaying messages. These are collected in the
 * {@link org.bluezoo.gumdrop.smtp.DeliveryRequirements} interface:
 *
 * <ul>
 *   <li><b>REQUIRETLS</b> - Message must only traverse TLS connections</li>
 *   <li><b>MT-PRIORITY</b> - Relative priority (-9 lowest to +9 highest)</li>
 *   <li><b>FUTURERELEASE</b> - Hold until specified time (HOLDFOR/HOLDUNTIL)</li>
 *   <li><b>DELIVERBY</b> - Delivery deadline with return/notify action</li>
 *   <li><b>DSN envelope</b> - Return type (RET) and envelope ID (ENVID)</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 *
 * <pre>{@code
 * <realm id="mailRealm" class="org.bluezoo.gumdrop.auth.BasicRealm">
 *   <property name="href">mail-users.xml</property>
 * </realm>
 *
 * <service class="com.example.MySmtpService">
 *   <property name="realm" ref="#mailRealm"/>
 *   <listener class="org.bluezoo.gumdrop.smtp.SMTPListener"
 *           port="25"
 *           keystore-file="server.p12"
 *           keystore-pass="changeit"/>
 * </service>
 * }</pre>
 *
 * <h2>Security</h2>
 *
 * <ul>
 *   <li>TLS encryption via STARTTLS or implicit TLS (port 465)</li>
 *   <li>SASL authentication (PLAIN, LOGIN, CRAM-MD5, etc.)</li>
 *   <li>Rate limiting for protection against abuse</li>
 * </ul>
 *
 * <h2>Email Authentication</h2>
 *
 * <p>The {@link org.bluezoo.gumdrop.smtp.auth} subpackage provides
 * asynchronous implementations of SPF, DKIM, and DMARC validation
 * through {@link org.bluezoo.gumdrop.smtp.auth.AuthPipeline}:
 *
 * <ul>
 *   <li>SPF (RFC 7208) - Sender Policy Framework validation</li>
 *   <li>DKIM (RFC 6376) - DomainKeys Identified Mail signature verification</li>
 *   <li>DMARC (RFC 7489) - Domain-based Message Authentication policy</li>
 * </ul>
 *
 * <p>The authentication pipeline integrates seamlessly - handlers register
 * callbacks for each check type and receive results at the appropriate time
 * (SPF at MAIL FROM, DKIM/DMARC at end-of-data).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp.SMTPListener
 * @see org.bluezoo.gumdrop.smtp.handler.ClientConnected
 * @see org.bluezoo.gumdrop.smtp.SMTPPipeline
 * @see org.bluezoo.gumdrop.smtp.handler
 * @see org.bluezoo.gumdrop.smtp.client
 * @see org.bluezoo.gumdrop.smtp.auth
 */
package org.bluezoo.gumdrop.smtp;
