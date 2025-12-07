/*
 * DataStartReply.java
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
 * Results of DATA command initiation policy evaluation.
 * Each result corresponds to specific SMTP response codes as defined in RFC 5321.
 * This enum allows SMTP connection handlers to make policy decisions about whether
 * to accept message data without needing to understand SMTP protocol details.
 * 
 * <p>The DATA command has two phases:
 * <ol>
 * <li><strong>Initiation</strong> - Server decides whether to accept message data (this enum)</li>
 * <li><strong>Completion</strong> - Server processes received message and responds (see DataEndReply)</li>
 * </ol>
 * 
 * <p>The results are divided into categories:
 * <ul>
 * <li><strong>Acceptance (3xx codes)</strong> - Ready to receive message data</li>
 * <li><strong>Temporary rejections (4xx codes)</strong> - Client should retry later</li>
 * <li><strong>Permanent rejections (5xx codes)</strong> - Client should not retry</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DataEndReply
 * @see SMTPConnectionHandler#startData(SMTPConnectionMetadata, DataStartCallback)
 */
public enum DataStartReply {

    /**
     * Accept and ready to receive message data.
     * <p>SMTP Response: {@code 354 Enter mail, end with "." on a line by itself}
     * <p>The server is ready to receive the message content. The client should
     * proceed to send the RFC822 message data, ending with CRLF.CRLF sequence.
     */
    ACCEPT,

    /**
     * Permanently reject due to transaction failure.
     * <p>SMTP Response: {@code 554 5.0.0 Transaction failed}
     * <p>The DATA command cannot be processed due to a permanent condition
     * such as policy violations, security restrictions, or system constraints.
     */
    REJECT_TRANSACTION_FAILED,

    /**
     * Permanently reject due to bad command sequence.
     * <p>SMTP Response: {@code 503 5.0.0 Bad sequence of commands}
     * <p>The DATA command was issued at an inappropriate time, such as before
     * establishing valid sender and recipients, or when already in DATA mode.
     */
    REJECT_BAD_SEQUENCE,

    /**
     * Permanently reject due to access denied.
     * <p>SMTP Response: {@code 550 5.7.1 Access denied}
     * <p>The client does not have permission to send message data, typically
     * due to authentication requirements, IP restrictions, or policy violations.
     */
    REJECT_ACCESS_DENIED,

    /**
     * Permanently reject because anticipated message size exceeds limits.
     * <p>SMTP Response: {@code 552 5.2.3 Message size limit exceeded}
     * <p>The server has determined that the message will likely exceed configured
     * size limits based on connection context, sender patterns, or policy rules.
     * This is a preemptive rejection before receiving data.
     */
    REJECT_MESSAGE_TOO_LARGE,

    /**
     * Temporarily reject due to local processing error.
     * <p>SMTP Response: {@code 451 4.3.0 Local error in processing}
     * <p>A temporary system error occurred while preparing to receive message data
     * (database unavailable, service down, etc.). The client should retry later.
     */
    TEMP_REJECT_LOCAL_ERROR,

    /**
     * Temporarily reject due to insufficient storage.
     * <p>SMTP Response: {@code 452 4.3.1 Insufficient system storage}
     * <p>The server cannot accept message data due to storage constraints
     * (disk space, queue limits, memory). This is a temporary condition
     * that should resolve when resources become available.
     */
    TEMP_REJECT_STORAGE_FULL
}
