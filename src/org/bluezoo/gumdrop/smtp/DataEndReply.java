/*
 * DataEndReply.java
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

/**
 * Results of message content processing completion.
 * Each result corresponds to specific SMTP response codes as defined in RFC 5321.
 * This enum allows SMTP connection handlers to indicate the outcome of message
 * processing without needing to understand SMTP protocol details.
 * 
 * <p>The DATA command has two phases:
 * <ol>
 * <li><strong>Initiation</strong> - Server decides whether to accept message data (see DataStartReply)</li>
 * <li><strong>Completion</strong> - Server processes received message and responds (this enum)</li>
 * </ol>
 * 
 * <p>The results are divided into categories:
 * <ul>
 * <li><strong>Acceptance (2xx codes)</strong> - Message accepted and queued for delivery</li>
 * <li><strong>Temporary rejections (4xx codes)</strong> - Client should retry later</li>
 * <li><strong>Permanent rejections (5xx codes)</strong> - Client should not retry</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DataStartReply
 * @see SMTPConnectionHandler#endData(SMTPConnectionMetadata, DataEndCallback)
 */
public enum DataEndReply {

    /**
     * Accept message for delivery.
     * <p>SMTP Response: {@code 250 2.0.0 Message accepted for delivery}
     * <p>The message has been successfully received, processed, and queued
     * for delivery. This is the standard success response for message acceptance.
     */
    ACCEPT,

    /**
     * Accept message with content modifications.
     * <p>SMTP Response: {@code 250 2.6.0 Message accepted}
     * <p>The message has been accepted but was modified during processing
     * (content filtering, header additions, format conversions, etc.).
     * The client should be aware that the delivered message may differ from what was sent.
     */
    ACCEPT_WITH_MODIFICATIONS,

    /**
     * Permanently reject due to transaction failure.
     * <p>SMTP Response: {@code 554 5.0.0 Transaction failed}
     * <p>The message could not be processed due to a permanent condition
     * such as content policy violations, security threats, or system constraints.
     */
    REJECT_TRANSACTION_FAILED,

    /**
     * Permanently reject because message size exceeds storage allocation.
     * <p>SMTP Response: {@code 552 5.2.2 Message size exceeds storage allocation}
     * <p>The received message is too large for the allocated storage space
     * for this recipient or sender. This indicates a per-user or per-domain limit.
     */
    REJECT_SIZE_EXCEEDED,

    /**
     * Permanently reject because message is too big for system.
     * <p>SMTP Response: {@code 552 5.3.4 Message too big for system}
     * <p>The message exceeds system-wide size limits and cannot be processed.
     * This is different from storage allocation issues - it's an absolute size limit.
     */
    REJECT_MESSAGE_TOO_BIG,

    /**
     * Permanently reject due to content policy violation.
     * <p>SMTP Response: {@code 550 5.7.1 Content rejected}
     * <p>The message content violates policy rules such as spam detection,
     * virus scanning, content filtering, or business compliance requirements.
     */
    REJECT_CONTENT_REJECTED,

    /**
     * Temporarily reject due to local processing error.
     * <p>SMTP Response: {@code 451 4.3.0 Local error in processing}
     * <p>A temporary system error occurred while processing the message
     * (database unavailable, service down, etc.). The client should retry later.
     */
    TEMP_REJECT_LOCAL_ERROR,

    /**
     * Temporarily reject due to insufficient storage.
     * <p>SMTP Response: {@code 452 4.3.1 Insufficient system storage}
     * <p>The server cannot complete message processing due to storage constraints.
     * This is a temporary condition that should resolve when storage becomes available.
     */
    TEMP_REJECT_STORAGE_FULL,

    /**
     * Temporarily reject due to delivery system failure.
     * <p>SMTP Response: {@code 451 4.7.0 Temporary delivery failure}
     * <p>The message was received successfully but cannot be queued for delivery
     * due to temporary issues with the delivery subsystem. The client should retry.
     */
    TEMP_REJECT_DELIVERY_FAILURE
}
