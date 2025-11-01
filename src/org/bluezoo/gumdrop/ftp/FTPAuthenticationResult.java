/*
 * FTPAuthenticationResult.java
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

package org.bluezoo.gumdrop.ftp;

/**
 * Defines the possible outcomes for FTP authentication attempts.
 * Each result corresponds to specific FTP response codes and indicates how the
 * server should respond to USER/PASS/ACCT command sequences.
 *
 * <p>This abstraction allows handler implementations to focus on authentication
 * logic without needing to know FTP protocol response codes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum FTPAuthenticationResult {
    /**
     * User is successfully authenticated and logged in (230).
     * The client now has full access based on user permissions.
     */
    SUCCESS,

    /**
     * User name is okay, password is required (331).
     * This is the normal response to a valid USER command.
     */
    NEED_PASSWORD,

    /**
     * User name is okay, account information is required (332).
     * Some FTP servers require additional account information.
     */
    NEED_ACCOUNT,

    /**
     * Invalid user name (530).
     * The specified user does not exist or is not allowed to connect.
     */
    INVALID_USER,

    /**
     * Invalid password (530).
     * The password provided for the user is incorrect.
     */
    INVALID_PASSWORD,

    /**
     * Invalid account information (530).
     * The account information provided is incorrect or insufficient.
     */
    INVALID_ACCOUNT,

    /**
     * User account is disabled or locked (530).
     * The user exists but is not currently allowed to log in.
     */
    ACCOUNT_DISABLED,

    /**
     * Too many login attempts (421).
     * The client has exceeded the allowed number of authentication attempts.
     */
    TOO_MANY_ATTEMPTS,

    /**
     * Anonymous login not allowed (530).
     * The server does not accept anonymous FTP connections.
     */
    ANONYMOUS_NOT_ALLOWED,

    /**
     * Maximum concurrent users exceeded (421).
     * The server has reached its user connection limit.
     */
    USER_LIMIT_EXCEEDED
}
