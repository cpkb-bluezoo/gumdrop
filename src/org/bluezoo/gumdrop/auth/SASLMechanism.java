/*
 * SASLMechanism.java
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

package org.bluezoo.gumdrop.auth;

/**
 * Enumeration of supported SASL authentication mechanisms.
 * 
 * <p>Each mechanism has different security properties and requirements:
 * <ul>
 *   <li><b>PLAIN</b> - Simple but requires TLS for security</li>
 *   <li><b>LOGIN</b> - Legacy mechanism, requires TLS</li>
 *   <li><b>CRAM-MD5</b> - Challenge-response, doesn't send password</li>
 *   <li><b>DIGEST-MD5</b> - More secure challenge-response</li>
 *   <li><b>SCRAM-SHA-256</b> - Modern, most secure password-based</li>
 *   <li><b>OAUTHBEARER</b> - OAuth 2.0 token-based</li>
 *   <li><b>GSSAPI</b> - Kerberos-based enterprise SSO</li>
 *   <li><b>EXTERNAL</b> - TLS client certificate</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.iana.org/assignments/sasl-mechanisms/">IANA SASL Mechanisms</a>
 */
public enum SASLMechanism {

    /** Simple Authentication and Security Layer - plain credentials (RFC 4616) */
    PLAIN("PLAIN", false, true),
    
    /** Legacy LOGIN mechanism (non-standard but widely used) */
    LOGIN("LOGIN", false, true),
    
    /** Challenge-Response Authentication Mechanism using MD5 (RFC 2195) */
    CRAM_MD5("CRAM-MD5", true, false),
    
    /** Digest Access Authentication using MD5 (RFC 2831) */
    DIGEST_MD5("DIGEST-MD5", true, false),
    
    /** Salted Challenge Response Authentication Mechanism (RFC 5802, RFC 7677) */
    SCRAM_SHA_256("SCRAM-SHA-256", true, false),
    
    /** OAuth 2.0 Bearer Token (RFC 7628) */
    OAUTHBEARER("OAUTHBEARER", false, true),
    
    /** Generic Security Services API (RFC 4752) */
    GSSAPI("GSSAPI", true, false),
    
    /** External authentication via TLS client certificate (RFC 4422) */
    EXTERNAL("EXTERNAL", false, true);

    private final String mechanismName;
    private final boolean challengeResponse;
    private final boolean requiresTLS;

    SASLMechanism(String mechanismName, boolean challengeResponse, boolean requiresTLS) {
        this.mechanismName = mechanismName;
        this.challengeResponse = challengeResponse;
        this.requiresTLS = requiresTLS;
    }

    /**
     * Returns the SASL mechanism name as used in protocol negotiation.
     * 
     * @return the mechanism name (e.g., "CRAM-MD5")
     */
    public String getMechanismName() {
        return mechanismName;
    }

    /**
     * Returns whether this mechanism uses challenge-response authentication.
     * Challenge-response mechanisms don't send the password directly.
     * 
     * @return true if challenge-response
     */
    public boolean isChallengeResponse() {
        return challengeResponse;
    }

    /**
     * Returns whether this mechanism should only be used over TLS.
     * Mechanisms that send passwords in cleartext require TLS.
     * 
     * @return true if TLS is strongly recommended
     */
    public boolean requiresTLS() {
        return requiresTLS;
    }

    /**
     * Parses a mechanism name to its enum value.
     * 
     * @param name the mechanism name (case-insensitive)
     * @return the mechanism, or null if not recognized
     */
    public static SASLMechanism fromName(String name) {
        if (name == null) {
            return null;
        }
        for (SASLMechanism mech : values()) {
            if (mech.mechanismName.equalsIgnoreCase(name)) {
                return mech;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return mechanismName;
    }

}

