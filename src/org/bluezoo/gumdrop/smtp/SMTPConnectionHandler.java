/*
 * SMTPConnectionHandler.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
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
 * SMTPConnector connector = new SMTPConnector();
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
 *     public SenderPolicyResult mailFrom(String address, SMTPConnectionMetadata metadata) {
 *         this.currentSender = address; // Safe to store state
 *         if (metadata.isAuthenticated()) {
 *             return SenderPolicyResult.ACCEPT;
 *         }
 *         return checkSenderReputation(address, metadata.getClientAddress());
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
     * Notifies that a new client connection has been established.
     * 
     * <p>This is called immediately after the TCP connection is accepted and any
     * SSL/TLS handshake is completed. It provides an opportunity to perform
     * early connection-level filtering, logging, or initialization.
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
     */
    void connected(SMTPConnectionMetadata metadata);

    /**
     * Handles MAIL FROM command and makes sender policy decisions.
     * 
     * <p>This method is called when the client sends a MAIL FROM command with a
     * sender address. The handler should evaluate the sender against all relevant
     * policies and return the appropriate result.
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
     * @param metadata complete connection context including authentication status
     * @return policy decision indicating whether to accept or reject the sender
     */
    SenderPolicyResult mailFrom(String senderAddress, SMTPConnectionMetadata metadata);

    /**
     * Handles RCPT TO command and makes recipient policy decisions.
     * 
     * <p>This method is called for each RCPT TO command with a recipient address.
     * The handler should validate the recipient and determine if mail can be
     * delivered to this address.
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
     * @param metadata complete connection context including sender information
     * @return policy decision indicating how to handle the recipient
     */
    RecipientPolicyResult rcptTo(String recipientAddress, SMTPConnectionMetadata metadata);

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
     * @param metadata connection context with sender and recipient information
     */
    void messageContent(ByteBuffer messageData, SMTPConnectionMetadata metadata);

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
     * 
     * @param metadata current connection context
     */
    void reset(SMTPConnectionMetadata metadata);

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
     * 
     * @param metadata final connection context with duration and statistics
     */
    void disconnected(SMTPConnectionMetadata metadata);

}
