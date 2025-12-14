/*
 * SPFResult.java
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
 * SPF (Sender Policy Framework) check result as defined in RFC 7208.
 *
 * <p>SPF results indicate whether the sending server is authorized to send
 * mail for the domain in the envelope sender (MAIL FROM) address.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7208">RFC 7208 - SPF</a>
 */
public enum SPFResult {

    /**
     * The client is authorized to send mail for the domain.
     * The SPF record explicitly designates the IP as permitted.
     */
    PASS("pass"),

    /**
     * The client is NOT authorized to send mail for the domain.
     * The SPF record explicitly designates the IP as not permitted.
     * Messages should be rejected.
     */
    FAIL("fail"),

    /**
     * The client is probably not authorized (weak fail).
     * The SPF record has a softfail (~all) mechanism.
     * Messages should be accepted but marked.
     */
    SOFTFAIL("softfail"),

    /**
     * The domain explicitly states it makes no assertion.
     * The SPF record uses the "?" qualifier.
     */
    NEUTRAL("neutral"),

    /**
     * No SPF record exists for the domain.
     */
    NONE("none"),

    /**
     * A transient DNS error occurred during lookup.
     * The check should be retried later.
     */
    TEMPERROR("temperror"),

    /**
     * The SPF record is malformed or contains syntax errors.
     * This is a permanent error.
     */
    PERMERROR("permerror");

    private final String value;

    SPFResult(String value) {
        this.value = value;
    }

    /**
     * Returns the lowercase string representation for Authentication-Results.
     *
     * @return the result value string
     */
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

}


