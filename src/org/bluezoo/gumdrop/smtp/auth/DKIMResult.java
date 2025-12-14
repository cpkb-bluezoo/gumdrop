/*
 * DKIMResult.java
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
 * DKIM (DomainKeys Identified Mail) verification result as defined in RFC 6376.
 *
 * <p>DKIM results indicate whether the cryptographic signature on a message
 * is valid and matches the claimed signing domain.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6376">RFC 6376 - DKIM</a>
 */
public enum DKIMResult {

    /**
     * The message signature was verified successfully.
     * The signature cryptographically matches and the public key was found.
     */
    PASS("pass"),

    /**
     * The message signature verification failed.
     * The signature does not match the message content.
     */
    FAIL("fail"),

    /**
     * No DKIM signature was found in the message.
     */
    NONE("none"),

    /**
     * The public key could not be retrieved (DNS error).
     */
    TEMPERROR("temperror"),

    /**
     * The signature or public key has a permanent problem.
     * This includes malformed signatures, revoked keys, or unsupported algorithms.
     */
    PERMERROR("permerror"),

    /**
     * The signing domain and the From domain do not align.
     * The signature is valid but from a different domain.
     */
    POLICY("policy"),

    /**
     * The signature uses a weak or deprecated algorithm.
     */
    NEUTRAL("neutral");

    private final String value;

    DKIMResult(String value) {
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


