/*
 * FTPAuthenticationResult.java
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

package org.bluezoo.gumdrop.ftp;

/**
 * Defines the possible outcomes for FTP authentication attempts.
 * Each result maps to the reply codes specified in RFC 959 section 4.2 for
 * the USER/PASS/ACCT command sequence (section 4.1.1).
 *
 * <p>This abstraction allows handler implementations to focus on authentication
 * logic without needing to know FTP protocol response codes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum FTPAuthenticationResult {

    /** User logged in, proceed. RFC 959 reply 230. */
    SUCCESS,

    /** User name okay, need password. RFC 959 reply 331. */
    NEED_PASSWORD,

    /** Need account for login. RFC 959 reply 332. */
    NEED_ACCOUNT,

    /** Not logged in — invalid user. RFC 959 reply 530. */
    INVALID_USER,

    /** Not logged in — invalid password. RFC 959 reply 530. */
    INVALID_PASSWORD,

    /** Not logged in — invalid account. RFC 959 reply 530. */
    INVALID_ACCOUNT,

    /** Not logged in — account disabled. RFC 959 reply 530. */
    ACCOUNT_DISABLED,

    /**
     * Service not available — too many login attempts.
     * RFC 959 reply 421.
     */
    TOO_MANY_ATTEMPTS,

    /** Not logged in — anonymous not allowed. RFC 959 reply 530. */
    ANONYMOUS_NOT_ALLOWED,

    /**
     * Service not available — user limit exceeded.
     * RFC 959 reply 421.
     */
    USER_LIMIT_EXCEEDED

}
