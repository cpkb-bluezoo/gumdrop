/*
 * RecipientPolicyResult.java
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
 * Results of recipient policy evaluation for RCPT TO commands.
 * Each result corresponds to specific SMTP response codes as defined in RFC 5321.
 * This enum allows SMTP connection handlers to make policy decisions about recipient
 * addresses without needing to understand SMTP protocol details.
 * 
 * <p>Recipient policy results have more granular control than sender policies because
 * they deal with destination validation, per-user quotas, mailbox existence, and
 * forwarding scenarios.
 * 
 * <p>The results are divided into categories:
 * <ul>
 * <li><strong>Acceptance (2xx codes)</strong> - Recipient is valid</li>
 * <li><strong>Temporary rejections (4xx codes)</strong> - Client should retry later</li>
 * <li><strong>Permanent rejections (5xx codes)</strong> - Client should not retry</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SenderPolicyResult
 * @see SMTPConnectionHandler#rcptTo(String, RcptToCallback)
 */
public enum RecipientPolicyResult {

    /**
     * Accept the recipient address.
     * <p>SMTP Response: {@code 250 2.1.5 Recipient ok}
     * <p>The recipient address is valid, the mailbox exists, and mail can be
     * delivered according to local policy. This is the standard acceptance response.
     */
    ACCEPT,

    /**
     * Accept the recipient but will forward to another location.
     * <p>SMTP Response: {@code 251 2.1.5 User not local; will forward to <path>}
     * <p>The recipient is not handled locally but the server will forward the
     * message to the appropriate destination. This is used for mail forwarding
     * and relay scenarios where the final destination is different.
     */
    ACCEPT_FORWARD,

    /**
     * Temporarily reject due to mailbox being unavailable.
     * <p>SMTP Response: {@code 450 4.2.1 Mailbox temporarily unavailable}
     * <p>The recipient mailbox exists but is temporarily unavailable due to
     * maintenance, user account lock, or system issues. The client should
     * retry later when the mailbox becomes available.
     */
    TEMP_REJECT_UNAVAILABLE,

    /**
     * Temporarily reject due to local processing error.
     * <p>SMTP Response: {@code 451 4.3.0 Local error in processing}
     * <p>A temporary system error occurred while processing the recipient
     * (database unavailable, service down, etc.). This is a transient
     * condition and the client should retry later.
     */
    TEMP_REJECT_SYSTEM_ERROR,

    /**
     * Temporarily reject due to insufficient storage.
     * <p>SMTP Response: {@code 452 4.3.1 Insufficient system storage}
     * <p>The system cannot accept mail for this recipient due to storage
     * constraints. This could be system-wide disk space issues or
     * temporary queue limits. The client should retry later.
     */
    TEMP_REJECT_STORAGE_FULL,

    /**
     * Permanently reject because mailbox does not exist.
     * <p>SMTP Response: {@code 550 5.1.1 Mailbox unavailable}
     * <p>The recipient address does not correspond to a valid mailbox.
     * This is the most common permanent rejection for RCPT TO and indicates
     * the recipient does not exist in the local user database.
     */
    REJECT_MAILBOX_UNAVAILABLE,

    /**
     * Permanently reject because user is not local.
     * <p>SMTP Response: {@code 551 5.1.1 User not local; please try <path>}
     * <p>The recipient is not handled by this server and no forwarding is
     * configured. The client should try a different server. This is different
     * from ACCEPT_FORWARD in that no forwarding will be performed.
     */
    REJECT_USER_NOT_LOCAL,

    /**
     * Permanently reject due to exceeded storage quota.
     * <p>SMTP Response: {@code 552 5.2.2 Mailbox full, quota exceeded}
     * <p>The recipient's individual mailbox quota has been exceeded.
     * This is different from system storage issues (TEMP_REJECT_STORAGE_FULL)
     * as it's a per-user limit that won't resolve without user action.
     */
    REJECT_QUOTA_EXCEEDED,

    /**
     * Permanently reject due to invalid mailbox name.
     * <p>SMTP Response: {@code 553 5.1.3 Mailbox name not allowed}
     * <p>The recipient address format is syntactically valid but violates
     * local naming policies (reserved names, inappropriate content, etc.).
     * This is distinct from syntax errors.
     */
    REJECT_INVALID_MAILBOX,

    /**
     * Permanently reject due to transaction failure.
     * <p>SMTP Response: {@code 554 5.0.0 Transaction failed}
     * <p>The recipient address causes a transaction failure due to severe
     * policy violations, security issues, or other permanent conditions
     * that prevent mail delivery.
     */
    REJECT_TRANSACTION_FAILED,

    /**
     * Permanently reject due to syntax error in recipient address.
     * <p>SMTP Response: {@code 501 5.1.3 Invalid recipient address format}
     * <p>The recipient address does not conform to RFC 5321 syntax
     * requirements. This indicates malformed input or client
     * configuration errors.
     */
    REJECT_SYNTAX_ERROR,

    /**
     * Permanently reject due to relay policy violation.
     * <p>SMTP Response: {@code 551 5.7.1 Relaying denied}
     * <p>The recipient domain is not local and relaying is not permitted
     * for this client. This prevents unauthorized use of the server
     * as an open relay for spam distribution.
     */
    REJECT_RELAY_DENIED,

    /**
     * Permanently reject due to policy violation.
     * <p>SMTP Response: {@code 553 5.7.1 Recipient violates local policy}
     * <p>The recipient address violates local policies such as business rules,
     * compliance requirements, or content restrictions. This is used for
     * policy-based rejections beyond basic format validation.
     */
    REJECT_POLICY_VIOLATION
}