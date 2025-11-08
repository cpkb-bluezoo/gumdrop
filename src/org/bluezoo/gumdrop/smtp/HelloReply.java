/*
 * HelloReply.java
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

/**
 * Results of HELO/EHLO command evaluation.
 * Each result corresponds to specific SMTP response codes as defined in RFC 5321.
 * This enum allows SMTP connection handlers to respond to client greeting commands
 * without needing to understand SMTP protocol details.
 * 
 * <p>Both HELO and EHLO commands establish the client's identity and begin the SMTP
 * session. The key differences:
 * <ul>
 * <li><strong>HELO</strong> - Basic SMTP greeting, expects simple 250 response</li>
 * <li><strong>EHLO</strong> - Extended SMTP greeting, expects 250 response with extension list</li>
 * </ul>
 * 
 * <p>The results are divided into categories:
 * <ul>
 * <li><strong>Success (2xx codes)</strong> - Greeting accepted, session established</li>
 * <li><strong>Temporary failures (4xx codes)</strong> - Client should retry later</li>
 * <li><strong>Permanent failures (5xx codes)</strong> - Client should not retry</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HelloCallback
 * @see SMTPConnectionHandler#hello(boolean, String, HelloCallback)
 */
public enum HelloReply {

    /**
     * Accept HELO command with basic greeting.
     * <p>SMTP Response: {@code 250 <domain> Hello <client-domain>}
     * <p>The client's HELO greeting is accepted. This establishes the client's
     * identity and begins a basic SMTP session without extended features.
     */
    ACCEPT_HELO,

    /**
     * Accept EHLO command with extended features.
     * <p>SMTP Response: {@code 250-<domain> Hello <client-domain>} followed by
     * extension lines and final {@code 250 <last-extension>}
     * <p>The client's EHLO greeting is accepted. This establishes the client's
     * identity and begins an extended SMTP session with advertised features
     * such as SIZE, STARTTLS, AUTH, etc.
     */
    ACCEPT_EHLO,

    /**
     * Permanently reject due to command not implemented.
     * <p>SMTP Response: {@code 504 5.5.2 Command not implemented}
     * <p>The server does not implement the requested command (typically EHLO
     * on servers that only support basic SMTP). The client should retry with
     * the supported command variant if available.
     */
    REJECT_NOT_IMPLEMENTED,

    /**
     * Permanently reject due to syntax error.
     * <p>SMTP Response: {@code 501 5.0.0 Syntax: HELO/EHLO hostname}
     * <p>The command syntax is invalid, typically due to missing or malformed
     * hostname parameter. The client must correct the syntax to proceed.
     */
    REJECT_SYNTAX_ERROR,

    /**
     * Temporarily reject due to service unavailable.
     * <p>SMTP Response: {@code 421 4.3.0 Service not available, closing transmission channel}
     * <p>The server is temporarily unable to process the greeting due to
     * resource constraints, maintenance, or other temporary conditions.
     * The client should retry later.
     */
    TEMP_REJECT_SERVICE_UNAVAILABLE
}
