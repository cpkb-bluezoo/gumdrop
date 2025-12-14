/*
 * DMARCPolicy.java
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
 * DMARC policy action as specified in the p= tag of a DMARC record.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DMARCValidator
 */
public enum DMARCPolicy {

    /**
     * No specific action requested.
     * The domain owner requests no specific handling of messages that fail.
     */
    NONE("none"),

    /**
     * Quarantine the message.
     * The domain owner requests that messages failing DMARC be treated as
     * suspicious (e.g., moved to spam folder).
     */
    QUARANTINE("quarantine"),

    /**
     * Reject the message.
     * The domain owner requests that messages failing DMARC be rejected
     * outright at SMTP time.
     */
    REJECT("reject");

    private final String value;

    DMARCPolicy(String value) {
        this.value = value;
    }

    /**
     * Returns the policy value string.
     *
     * @return the policy value
     */
    public String getValue() {
        return value;
    }

    /**
     * Parses a policy value from a DMARC record.
     *
     * @param value the policy value string
     * @return the policy, or NONE if not recognized
     */
    public static DMARCPolicy parse(String value) {
        if (value == null) {
            return NONE;
        }
        String lower = value.toLowerCase();
        if ("quarantine".equals(lower)) {
            return QUARANTINE;
        }
        if ("reject".equals(lower)) {
            return REJECT;
        }
        return NONE;
    }

    @Override
    public String toString() {
        return value;
    }

}


