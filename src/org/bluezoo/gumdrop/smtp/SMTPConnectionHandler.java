/*
 * SMTPConnectionHandler.java
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

import java.nio.ByteBuffer;

/**
 * Handler interface for SMTP connection events and policy decisions.
 * <p>
 * This interface abstracts away all low-level SMTP protocol details and allows
 * implementers to focus purely on the business logic of mail processing. The handler
 * receives high-level events (sender, recipient, message content) along with rich
 * metadata about the connection context.
 * <p>
 * Key benefits of this abstraction:
 * <ul>
 * <li><em>Protocol Independence</em> - No need to understand SMTP response codes</li>
 * <li><em>Rich Context</em> - Access to client IP, certificates, authentication status</li>
 * <li><em>Policy Focused</em> - Simple enum returns for accept/reject decisions</li>
 * <li><em>Streaming Processing</em> - Efficient handling of large messages</li>
 * <li><em>Connection Lifecycle</em> - Notifications for connection events</li>
 * </ul>
 * <p>
 * Handler instances are created per-connection via a factory pattern to ensure
 * thread safety and state isolation:
 * <pre><code>
 * // Configure the connector with a handler factory
 * SMTPServer server = new SMTPServer();
 * connector.setHandlerFactory(() -&gt; new MyMailHandler(config, dependencies));
 * 
 * // Each connection gets its own handler instance
 * public class MyMailHandler implements SMTPConnectionHandler {
 *     private final Config config;
 *     private String currentSender;  // Connection-specific state is safe
 *     
 *     public MyMailHandler(Config config, Dependencies deps) {
 *         this.config = config;
 *         // Initialize per-connection resources
 *     }
 *     
 *     &#64;Override
 *     public void connected(SMTPConnectionMetadata metadata) {
 *         // This handler is exclusive to this connection/thread
 *         logger.info("Connection from {}", metadata.getClientAddress());
 *     }
 *     
 *     &#64;Override
 *     public void mailFrom(String address, final MailFromCallback callback) {
 *         this.currentSender = address; // Safe to store state
 *         // Perform async policy evaluation
 *         final SelectorLoop loop = connection.getSelectorLoop();
 *         executor.submit(new Runnable() {
 *             public void run() {
 *                 final SenderPolicyResult result = checkSenderReputation(address);
 *                 loop.invokeLater(new Runnable() {
 *                     public void run() {
 *                         callback.mailFromReply(result);
 *                     }
 *                 });
 *             }
 *         });
 *     }
 * }
 * </code></pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SenderPolicyResult
 * @see RecipientPolicyResult
 * @see SMTPConnectionMetadata
 * @see SMTPConnectionHandlerFactory
 */
public interface SMTPConnectionHandler {

    /**
     * Notifies that a new client connection has been established and allows early termination.
     * 
     * <p>This is called immediately after the TCP connection is accepted and any
     * SSL/TLS handshake is completed. It provides an opportunity to perform
     * early connection-level filtering, logging, or initialization.
     * 
     * <p>The return value determines the initial server response:
     * <ul>
     * <li><strong>true</strong> - Accept connection, send 220 banner message</li>
     * <li><strong>false</strong> - Reject connection, send 554 error and close</li>
     * </ul>
     * 
     * <p>The metadata at this point contains:
     * <ul>
     * <li>Client and server addresses</li>
     * <li>SSL/TLS status and certificates</li>
     * <li>Connection timestamp</li>
     * <li>Connector information</li>
     * </ul>
     * 
     * <p>Note: HELO/EHLO and authentication have not yet occurred.
     * 
     * @param metadata connection metadata with client and security information
     * @return true to accept the connection, false to reject with 554 error
     */
    boolean connected(SMTPConnectionMetadata metadata);

    /**
     * Handles HELO/EHLO command and makes greeting policy decisions asynchronously.
     * 
     * <p>This method is called when the client sends a HELO or EHLO command to
     * establish their identity and begin the SMTP session. The handler should
     * evaluate the client's greeting and respond appropriately.
     * 
     * <p>This method returns immediately (non-blocking). The greeting evaluation
     * can be performed asynchronously, allowing hostname validation, policy checks,
     * or other time-consuming operations without blocking the connection thread.
     * The SMTP connection will automatically send the appropriate response to the
     * client when the callback is invoked.
     * 
     * <p>Policy considerations for HELO/EHLO might include:
     * <ul>
     * <li>Hostname validation and verification</li>
     * <li>Client identity and reputation checking</li>
     * <li>Feature availability based on connection context</li>
     * <li>Rate limiting and connection policies</li>
     * <li>Domain-specific greeting customization</li>
     * </ul>
     * 
     * @param extended true if this was an EHLO command, false for HELO
     * @param clientDomain the domain name advertised by the client
     * @param callback callback to invoke with the greeting policy result
     */
    void hello(boolean extended, String clientDomain, HelloCallback callback);

    /**
     * Notifies that TLS has been successfully established on this connection.
     * 
     * <p>This method is called after a client STARTTLS command has successfully
     * upgraded the connection to use TLS encryption. The handler can use this
     * opportunity to:
     * <ul>
     * <li>Access client certificate information for authentication or logging</li>
     * <li>Update connection policies based on security level</li>
     * <li>Initialize TLS-specific resources or state</li>
     * <li>Log security upgrade events</li>
     * </ul>
     * 
     * <p>This is a notification-only method. All STARTTLS protocol responses
     * (220 TLS go ahead, 454 TLS not available, etc.) are handled internally
     * by the SMTP connection implementation. The handler cannot influence the
     * STARTTLS response or prevent the TLS upgrade at this level.
     * 
     * <p>The updated metadata will reflect the new secure connection state,
     * including any client certificates that were provided during the TLS
     * handshake process.
     * 
     * @param metadata updated connection metadata with TLS and certificate information
     */
    void tlsStarted(SMTPConnectionMetadata metadata);

    /**
     * Notifies that a client has successfully authenticated.
     * 
     * <p>This method is called when a client has completed SMTP AUTH successfully
     * using any supported authentication mechanism (PLAIN, LOGIN, etc.). The handler
     * can use this notification to:
     * <ul>
     * <li>Log authentication events for security auditing</li>
     * <li>Update user-specific policies or quotas</li>
     * <li>Initialize authenticated session resources</li>
     * <li>Apply user-specific configuration or restrictions</li>
     * </ul>
     * 
     * <p>This is a notification-only method with no return value or callback.
     * Authentication success/failure is handled entirely by the SMTP connection
     * implementation according to the AUTH protocol specifications.
     * 
     * <p>After this notification, subsequent policy decisions (mailFrom, rcptTo, etc.)
     * can assume the connection is authenticated and use the user identity for
     * enhanced policy evaluation.
     * 
     * @param user the authenticated username
     * @param method the authentication method used (e.g., "PLAIN", "LOGIN")
     */
    void authenticated(String user, String method);

    /**
     * Handles MAIL FROM command and makes sender policy decisions asynchronously.
     * 
     * <p>This method is called when the client sends a MAIL FROM command with a
     * sender address. The handler should evaluate the sender against all relevant
     * policies and invoke the callback with the appropriate result.
     * 
     * <p>This method returns immediately (non-blocking). The policy evaluation
     * can be performed asynchronously, allowing database lookups, reputation
     * checks, or other time-consuming operations without blocking the connection
     * thread. The SMTP connection will automatically send the appropriate
     * response to the client when the callback is invoked.
     * 
     * <p>Policy considerations might include:
     * <ul>
     * <li>Sender domain reputation and validation</li>
     * <li>Authentication requirements (for submission ports)</li>
     * <li>Rate limiting per sender or client IP</li>
     * <li>Greylisting for unknown senders</li>
     * <li>Compliance and business rules</li>
     * </ul>
     * 
     * @param senderAddress the email address from MAIL FROM command
     * @param callback callback to invoke with the policy decision result
     */
    void mailFrom(String senderAddress, MailFromCallback callback);

    /**
     * Handles RCPT TO command and makes recipient policy decisions asynchronously.
     * 
     * <p>This method is called for each RCPT TO command with a recipient address.
     * The handler should validate the recipient and invoke the callback with
     * the appropriate result.
     * 
     * <p>This method returns immediately (non-blocking). The recipient validation
     * can be performed asynchronously, allowing mailbox existence checks, quota
     * validations, domain lookups, or other time-consuming operations without
     * blocking the connection thread. The SMTP connection will automatically
     * send the appropriate response to the client when the callback is invoked.
     * 
     * <p>Policy considerations might include:
     * <ul>
     * <li>Mailbox existence and availability</li>
     * <li>User quota limits and storage</li>
     * <li>Relay authorization and domain handling</li>
     * <li>Recipient-specific filtering rules</li>
     * <li>Forwarding and aliasing logic</li>
     * </ul>
     * 
     * @param recipientAddress the email address from RCPT TO command
     * @param callback callback to invoke with the policy decision result
     */
    void rcptTo(String recipientAddress, RcptToCallback callback);

    /**
     * Handles DATA command initiation and makes policy decisions asynchronously.
     * 
     * <p>This method is called when the client sends a DATA command to begin
     * message transmission. The handler should evaluate whether to accept message
     * data based on current connection state, policies, and resource availability.
     * 
     * <p>This method returns immediately (non-blocking). The policy evaluation
     * can be performed asynchronously, allowing storage checks, policy validations,
     * or other time-consuming operations without blocking the connection thread.
     * The SMTP connection will automatically send the appropriate response to the
     * client when the callback is invoked.
     * 
     * <p>Policy considerations for DATA initiation might include:
     * <ul>
     * <li>Storage space availability and quotas</li>
     * <li>Message size limits and anticipatory rejection</li>
     * <li>Connection state validation (sender, recipients)</li>
     * <li>Rate limiting and throttling policies</li>
     * <li>Security and access control checks</li>
     * </ul>
     * 
     * @param metadata connection context with sender and recipient information
     * @param callback callback to invoke with the policy decision result
     */
    void startData(SMTPConnectionMetadata metadata, DataStartCallback callback);

    /**
     * Receives RFC822 message content for processing.
     * 
     * <p>This method is called with chunks of the raw RFC822 message data as it
     * arrives during the DATA command. The data includes headers and body and
     * may be called multiple times for large messages.
     * 
     * <p>The handler should:
     * <ul>
     * <li>Process the message data efficiently (streaming)</li>
     * <li>Perform content filtering if required</li>
     * <li>Store or forward the message as appropriate</li>
     * <li>Handle any parsing or validation errors gracefully</li>
     * </ul>
     * 
     * <p>Note: The ByteBuffer position and limit indicate the valid data range.
     * The handler should not modify the buffer's position or limit.
     * 
     * @param messageData chunk of RFC822 message data
     */
    void messageContent(ByteBuffer messageData);

    /**
     * Handles completion of message data processing asynchronously.
     * 
     * <p>This method is called when the client has finished sending message data
     * (after receiving the final CRLF.CRLF sequence). The handler should perform
     * final message processing, validation, and delivery preparation.
     * 
     * <p>This method returns immediately (non-blocking). The message processing
     * can be performed asynchronously, allowing content analysis, virus scanning,
     * spam filtering, delivery queueing, or other time-consuming operations without
     * blocking the connection thread. The SMTP connection will automatically send
     * the appropriate response when the callback is invoked.
     * 
     * <p>Processing considerations for message completion might include:
     * <ul>
     * <li>Content analysis and filtering (spam, virus, policy)</li>
     * <li>Message format validation and RFC compliance</li>
     * <li>Delivery queue insertion and routing decisions</li>
     * <li>Final storage operations and commit/rollback</li>
     * <li>Logging and audit trail generation</li>
     * </ul>
     * 
     * @param metadata connection context with complete message information
     * @param callback callback to invoke with the processing result
     */
    void endData(SMTPConnectionMetadata metadata, DataEndCallback callback);

    /**
     * Notifies that the SMTP transaction has been reset.
     * 
     * <p>This is called when the client sends a RSET command or when starting
     * a new transaction after completing a previous one. The handler should
     * clean up any transaction-specific state while preserving connection-level
     * information.
     * 
     * <p>Actions might include:
     * <ul>
     * <li>Clearing sender and recipient lists</li>
     * <li>Resetting message buffers or streams</li>
     * <li>Cleaning up temporary resources</li>
     * <li>Preparing for a new mail transaction</li>
     * </ul>
     */
    void reset();

    /**
     * Notifies that the client connection has been closed.
     * 
     * <p>This is called when the client disconnects (gracefully with QUIT or
     * abruptly due to network issues). The handler should perform final cleanup
     * and resource management.
     * 
     * <p>Cleanup actions might include:
     * <ul>
     * <li>Closing files or streams</li>
     * <li>Updating connection statistics</li>
     * <li>Logging connection summary</li>
     * <li>Releasing any held resources</li>
     * </ul>
     */
    void disconnected();

}
