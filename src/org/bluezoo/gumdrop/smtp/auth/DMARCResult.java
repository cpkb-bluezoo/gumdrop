/*
 * DMARCResult.java
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
 * DMARC (Domain-based Message Authentication, Reporting and Conformance)
 * evaluation result as defined in RFC 7489.
 *
 * <p>DMARC combines SPF and DKIM results with domain alignment checks to
 * determine the overall authentication status of a message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489">RFC 7489 - DMARC</a>
 */
public enum DMARCResult {

    /**
     * The message passes DMARC authentication.
     * At least one of SPF or DKIM passed with proper domain alignment.
     */
    PASS("pass"),

    /**
     * The message fails DMARC authentication.
     * Neither SPF nor DKIM passed with proper alignment.
     */
    FAIL("fail"),

    /**
     * No DMARC policy exists for the domain.
     */
    NONE("none"),

    /**
     * A transient error occurred during evaluation.
     */
    TEMPERROR("temperror"),

    /**
     * A permanent error occurred (malformed policy, etc.).
     */
    PERMERROR("permerror");

    private final String value;

    DMARCResult(String value) {
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


