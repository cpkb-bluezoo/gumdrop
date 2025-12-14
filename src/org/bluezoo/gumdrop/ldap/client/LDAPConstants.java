/*
 * LDAPConstants.java
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

package org.bluezoo.gumdrop.ldap.client;

/**
 * LDAP protocol constants.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class LDAPConstants {

    // Default ports
    /** Default LDAP port (389). */
    public static final int DEFAULT_PORT = 389;
    /** Default LDAPS port (636). */
    public static final int DEFAULT_SECURE_PORT = 636;

    // Protocol version
    /** LDAP protocol version 3. */
    public static final int LDAP_VERSION_3 = 3;

    // Message tags (application class, constructed)
    /** BindRequest tag (0x60). */
    public static final int TAG_BIND_REQUEST = 0x60;
    /** BindResponse tag (0x61). */
    public static final int TAG_BIND_RESPONSE = 0x61;
    /** UnbindRequest tag (0x42). */
    public static final int TAG_UNBIND_REQUEST = 0x42;
    /** SearchRequest tag (0x63). */
    public static final int TAG_SEARCH_REQUEST = 0x63;
    /** SearchResultEntry tag (0x64). */
    public static final int TAG_SEARCH_RESULT_ENTRY = 0x64;
    /** SearchResultDone tag (0x65). */
    public static final int TAG_SEARCH_RESULT_DONE = 0x65;
    /** SearchResultReference tag (0x73). */
    public static final int TAG_SEARCH_RESULT_REFERENCE = 0x73;
    /** ModifyRequest tag (0x66). */
    public static final int TAG_MODIFY_REQUEST = 0x66;
    /** ModifyResponse tag (0x67). */
    public static final int TAG_MODIFY_RESPONSE = 0x67;
    /** AddRequest tag (0x68). */
    public static final int TAG_ADD_REQUEST = 0x68;
    /** AddResponse tag (0x69). */
    public static final int TAG_ADD_RESPONSE = 0x69;
    /** DelRequest tag (0x4A). */
    public static final int TAG_DEL_REQUEST = 0x4A;
    /** DelResponse tag (0x6B). */
    public static final int TAG_DEL_RESPONSE = 0x6B;
    /** ModifyDNRequest tag (0x6C). */
    public static final int TAG_MODIFY_DN_REQUEST = 0x6C;
    /** ModifyDNResponse tag (0x6D). */
    public static final int TAG_MODIFY_DN_RESPONSE = 0x6D;
    /** CompareRequest tag (0x6E). */
    public static final int TAG_COMPARE_REQUEST = 0x6E;
    /** CompareResponse tag (0x6F). */
    public static final int TAG_COMPARE_RESPONSE = 0x6F;
    /** AbandonRequest tag (0x50). */
    public static final int TAG_ABANDON_REQUEST = 0x50;
    /** ExtendedRequest tag (0x77). */
    public static final int TAG_EXTENDED_REQUEST = 0x77;
    /** ExtendedResponse tag (0x78). */
    public static final int TAG_EXTENDED_RESPONSE = 0x78;
    /** IntermediateResponse tag (0x79). */
    public static final int TAG_INTERMEDIATE_RESPONSE = 0x79;

    // Context-specific tags within messages
    /** Simple authentication context tag. */
    public static final int TAG_AUTH_SIMPLE = 0x80;
    /** SASL authentication context tag. */
    public static final int TAG_AUTH_SASL = 0xA3;
    /** Server SASL credentials context tag. */
    public static final int TAG_SERVER_SASL_CREDS = 0x87;
    /** Referral context tag. */
    public static final int TAG_REFERRAL = 0xA3;
    /** Controls context tag. */
    public static final int TAG_CONTROLS = 0xA0;

    // Extended operation OIDs
    /** STARTTLS extended operation OID. */
    public static final String OID_STARTTLS = "1.3.6.1.4.1.1466.20037";
    /** Password modify extended operation OID. */
    public static final String OID_PASSWORD_MODIFY = "1.3.6.1.4.1.4203.1.11.1";
    /** Who Am I? extended operation OID. */
    public static final String OID_WHO_AM_I = "1.3.6.1.4.1.4203.1.11.3";

    // Search filter tags
    /** AND filter tag. */
    public static final int FILTER_AND = 0xA0;
    /** OR filter tag. */
    public static final int FILTER_OR = 0xA1;
    /** NOT filter tag. */
    public static final int FILTER_NOT = 0xA2;
    /** Equality match filter tag. */
    public static final int FILTER_EQUALITY = 0xA3;
    /** Substrings filter tag. */
    public static final int FILTER_SUBSTRINGS = 0xA4;
    /** Greater or equal filter tag. */
    public static final int FILTER_GREATER_OR_EQUAL = 0xA5;
    /** Less or equal filter tag. */
    public static final int FILTER_LESS_OR_EQUAL = 0xA6;
    /** Present filter tag. */
    public static final int FILTER_PRESENT = 0x87;
    /** Approximate match filter tag. */
    public static final int FILTER_APPROX = 0xA8;
    /** Extensible match filter tag. */
    public static final int FILTER_EXTENSIBLE = 0xA9;

    private LDAPConstants() {
    }
}

