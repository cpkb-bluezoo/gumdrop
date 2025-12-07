/*
 * SenderPolicyResult.java
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
 * Results of sender policy evaluation for MAIL FROM commands.
 * Each result corresponds to specific SMTP response codes as defined in RFC 5321.
 * This enum allows SMTP connection handlers to make policy decisions about sender
 * addresses without needing to understand SMTP protocol details.
 * 
 * <p>The results are divided into two categories:
 * <ul>
 * <li><strong>Temporary rejections (4xx codes)</strong> - Client should retry later</li>
 * <li><strong>Permanent rejections (5xx codes)</strong> - Client should not retry</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RecipientPolicyResult
 * @see SMTPConnectionHandler#mailFrom(String, MailFromCallback)
 */
public enum SenderPolicyResult {

    /**
     * Accept the sender address.
     * <p>SMTP Response: {@code 250 2.1.0 Sender ok}
     * <p>The sender address is valid and acceptable according to local policy.
     * The SMTP transaction may proceed to the RCPT TO phase.
     */
    ACCEPT,

    /**
     * Temporarily reject due to greylisting policy.
     * <p>SMTP Response: {@code 450 4.7.1 Greylisting in effect, please try again later}
     * <p>This is a temporary rejection used in greylisting systems where first-time
     * senders are asked to retry after a delay (typically 15+ minutes). Legitimate
     * mail servers will retry, while spam sources typically will not.
     */
    TEMP_REJECT_GREYLIST,

    /**
     * Temporarily reject due to rate limiting.
     * <p>SMTP Response: {@code 450 4.7.1 Rate limit exceeded, please try again later}
     * <p>The sender has exceeded configured rate limits (messages per hour, etc.).
     * This is a temporary condition and the sender should retry later when the
     * rate limit window has reset.
     */
    TEMP_REJECT_RATE_LIMIT,

    /**
     * Permanently reject due to blocked domain policy.
     * <p>SMTP Response: {@code 550 5.1.1 Sender domain blocked by policy}
     * <p>The sender's domain is on a blocklist or violates local policy.
     * This is a permanent rejection - the sender should not retry.
     */
    REJECT_BLOCKED_DOMAIN,

    /**
     * Permanently reject due to invalid or non-existent domain.
     * <p>SMTP Response: {@code 550 5.1.1 Sender domain does not exist}
     * <p>The sender's domain does not exist in DNS or fails domain validation.
     * This indicates a configuration error or fraudulent sender.
     */
    REJECT_INVALID_DOMAIN,

    /**
     * Permanently reject due to policy violation.
     * <p>SMTP Response: {@code 553 5.7.1 Sender address violates local policy}
     * <p>The sender address violates local content policies, business rules,
     * or regulatory compliance requirements. This is distinct from format errors.
     */
    REJECT_POLICY_VIOLATION,

    /**
     * Permanently reject due to poor reputation or spam classification.
     * <p>SMTP Response: {@code 554 5.7.1 Sender has poor reputation}
     * <p>The sender has been identified as a spam source by reputation services
     * (Spamhaus, SURBL, etc.) or internal reputation tracking. This helps
     * prevent bulk spam and malicious email.
     */
    REJECT_SPAM_REPUTATION,

    /**
     * Permanently reject due to syntax error in sender address.
     * <p>SMTP Response: {@code 501 5.1.3 Invalid sender address format}
     * <p>The sender address does not conform to RFC 5321 syntax requirements
     * or violates local address format policies. This indicates a client
     * configuration error or malformed input.
     */
    REJECT_SYNTAX_ERROR,

    /**
     * Permanently reject due to relay policy violation.
     * <p>SMTP Response: {@code 551 5.7.1 Relaying denied}
     * <p>The sender is attempting to use this server as a relay but does not
     * have permission. This prevents the server from being used as an open
     * relay for spam distribution.
     */
    REJECT_RELAY_DENIED,

    /**
     * Temporarily reject due to insufficient system storage.
     * <p>SMTP Response: {@code 452 4.3.1 Insufficient system storage}
     * <p>The server is temporarily unable to accept mail due to storage
     * constraints (disk full, queue limits, etc.). This is a temporary
     * condition that should resolve when storage is available.
     */
    REJECT_STORAGE_FULL
}