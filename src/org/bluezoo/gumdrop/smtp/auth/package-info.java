/*
 * package-info.java
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

/**
 * Email authentication (SPF, DKIM, DMARC) for SMTP.
 *
 * <p>This package provides asynchronous, non-blocking implementations of
 * the major email authentication standards:
 *
 * <ul>
 *   <li><b>SPF</b> (RFC 7208) - Sender Policy Framework validates that
 *       the sending server is authorized by the domain</li>
 *   <li><b>DKIM</b> (RFC 6376) - DomainKeys Identified Mail provides
 *       cryptographic verification of message integrity</li>
 *   <li><b>DMARC</b> (RFC 7489) - Domain-based Message Authentication
 *       combines SPF and DKIM with domain alignment checks</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <p>{@link org.bluezoo.gumdrop.smtp.auth.AuthPipeline} implements
 * {@link org.bluezoo.gumdrop.smtp.SMTPPipeline}, integrating seamlessly
 * with the SMTP endpoint handler. When associated with a session:
 *
 * <ul>
 *   <li>SPF check runs automatically on MAIL FROM</li>
 *   <li>Raw message bytes stream to the pipeline for DKIM body hashing</li>
 *   <li>DKIM signature verification runs at end-of-data</li>
 *   <li>DMARC policy evaluation runs after DKIM completes</li>
 * </ul>
 *
 * <p>The pipeline handles all the complexity internally:
 *
 * <ul>
 *   <li>Parses message headers via {@link org.bluezoo.gumdrop.mime.rfc5322.MessageParser}</li>
 *   <li>Collects DKIM-Signature headers for verification</li>
 *   <li>Scans raw bytes for CRLFCRLF to detect header/body boundary</li>
 *   <li>Computes body hash from raw bytes (not decoded content)</li>
 *   <li>Extracts From domain for DMARC alignment</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <p>Return an AuthPipeline from your MailFromHandler's getPipeline() method:
 *
 * <pre><code>
 * // Handler implements interfaces from org.bluezoo.gumdrop.smtp.handler:
 * // ClientConnected, HelloHandler, MailFromHandler, RecipientHandler, MessageDataHandler
 * public class MyMailHandler implements ... {
 *
 *     private final DNSResolver resolver;
 *     private AuthPipeline authPipeline;
 *     private MessageEndState pendingMessageState;
 *
 *     public MyMailHandler(DNSResolver resolver) {
 *         this.resolver = resolver;
 *     }
 *
 *     &#64;Override
 *     public SMTPPipeline getPipeline() {
 *         InetAddress clientIP = getClientIP();
 *         String heloHost = getHeloHost();
 *
 *         // Build authentication pipeline
 *         AuthPipeline.Builder builder = new AuthPipeline.Builder(
 *             resolver, clientIP, heloHost);
 *
 *         // SPF callback - invoked when sender is set
 *         builder.onSPF(new SPFCallback() {
 *             &#64;Override
 *             public void spfResult(SPFResult result, String explanation) {
 *                 // SPF result available - can log or store for later
 *             }
 *         });
 *
 *         // DMARC callback - invoked at end-of-data with final verdict
 *         builder.onDMARC(new DMARCCallback() {
 *             &#64;Override
 *             public void dmarcResult(DMARCResult result, DMARCPolicy policy,
 *                                     String fromDomain, AuthVerdict verdict) {
 *                 // Make final delivery decision based on auth results
 *                 if (verdict == AuthVerdict.REJECT) {
 *                     pendingMessageState.rejectMessagePolicy("DMARC reject", MyMailHandler.this);
 *                 } else {
 *                     pendingMessageState.acceptMessageDelivery(null, MyMailHandler.this);
 *                 }
 *             }
 *         });
 *
 *         authPipeline = builder.build();
 *         return authPipeline;
 *     }
 *
 *     &#64;Override
 *     public void messageComplete(MessageEndState state) {
 *         // Store state - DMARCCallback will invoke the accept/reject
 *         this.pendingMessageState = state;
 *         // Authentication results arrive via callbacks
 *     }
 * }
 * </code></pre>
 *
 * <h2>Callback Timing</h2>
 *
 * <table border="1">
 *   <tr><th>Callback</th><th>When Invoked</th></tr>
 *   <tr><td>SPFCallback</td><td>During MAIL FROM, after DNS lookup</td></tr>
 *   <tr><td>DKIMCallback</td><td>At end-of-data, after signature verified</td></tr>
 *   <tr><td>DMARCCallback</td><td>At end-of-data, after DKIM, with alignment result and verdict</td></tr>
 * </table>
 *
 * <h2>Message Handler Integration</h2>
 *
 * <p>To process message content while authentication runs in parallel,
 * register your own MessageHandler:
 *
 * <pre><code>
 * builder.messageHandler(new MessageHandler() {
 *     &#64;Override
 *     public void header(String name, String value) {
 *         // Receive parsed headers
 *     }
 *
 *     &#64;Override
 *     public void bodyContent(ByteBuffer data) {
 *         // Receive decoded body content
 *     }
 *
 *     &#64;Override
 *     public void addressHeader(String name, List&lt;EmailAddress&gt; addresses) {
 *         // Receive parsed address headers (From, To, etc.)
 *     }
 * });
 * </code></pre>
 *
 * <p>Your handler receives events from a composite handler that also
 * feeds the authentication checks. This avoids parsing the message twice.
 *
 * <h2>AuthVerdict</h2>
 *
 * <p>The {@link org.bluezoo.gumdrop.smtp.auth.DMARCCallback} receives an
 * {@link org.bluezoo.gumdrop.smtp.auth.AuthVerdict} representing the combined
 * authentication decision:
 *
 * <ul>
 *   <li><b>PASS</b> - Authentication passed, deliver normally</li>
 *   <li><b>REJECT</b> - DMARC policy is reject, refuse message</li>
 *   <li><b>QUARANTINE</b> - DMARC policy is quarantine, deliver to spam</li>
 *   <li><b>NONE</b> - No authentication info or policy is none</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp.SMTPPipeline
 * @see org.bluezoo.gumdrop.smtp.auth.AuthPipeline
 * @see org.bluezoo.gumdrop.smtp.auth.AuthVerdict
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7208">RFC 7208 - SPF</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6376">RFC 6376 - DKIM</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489">RFC 7489 - DMARC</a>
 */
package org.bluezoo.gumdrop.smtp.auth;
