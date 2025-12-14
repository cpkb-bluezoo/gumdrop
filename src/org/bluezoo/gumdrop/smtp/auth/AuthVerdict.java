/*
 * AuthVerdict.java
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

package org.bluezoo.gumdrop.smtp.auth;

/**
 * Combined verdict from email authentication checks.
 *
 * <p>This represents the final decision based on SPF, DKIM, and DMARC
 * results, taking into account DMARC policy and alignment.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum AuthVerdict {

    /**
     * Authentication passed. The message is authenticated and should
     * be delivered normally.
     */
    PASS,

    /**
     * Authentication failed and DMARC policy is reject. The message
     * should be rejected during the SMTP transaction.
     */
    REJECT,

    /**
     * Authentication failed and DMARC policy is quarantine. The message
     * should be accepted but delivered to spam/junk folder.
     */
    QUARANTINE,

    /**
     * No authentication information available or DMARC policy is none.
     * The message should be delivered normally but may be treated with
     * suspicion by downstream filters.
     */
    NONE

}

