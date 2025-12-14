/*
 * LDAPResultCode.java
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
 * LDAP result codes as defined in RFC 4511.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum LDAPResultCode {

    /** The operation completed successfully. */
    SUCCESS(0, "success"),

    /** An internal server error occurred. */
    OPERATIONS_ERROR(1, "operationsError"),

    /** The server encountered a protocol error. */
    PROTOCOL_ERROR(2, "protocolError"),

    /** A time limit was exceeded. */
    TIME_LIMIT_EXCEEDED(3, "timeLimitExceeded"),

    /** A size limit was exceeded. */
    SIZE_LIMIT_EXCEEDED(4, "sizeLimitExceeded"),

    /** The compare operation returned false. */
    COMPARE_FALSE(5, "compareFalse"),

    /** The compare operation returned true. */
    COMPARE_TRUE(6, "compareTrue"),

    /** The requested authentication method is not supported. */
    AUTH_METHOD_NOT_SUPPORTED(7, "authMethodNotSupported"),

    /** Strong authentication is required. */
    STRONGER_AUTH_REQUIRED(8, "strongerAuthRequired"),

    /** A referral was returned. */
    REFERRAL(10, "referral"),

    /** An administrative limit was exceeded. */
    ADMIN_LIMIT_EXCEEDED(11, "adminLimitExceeded"),

    /** A critical extension is unavailable. */
    UNAVAILABLE_CRITICAL_EXTENSION(12, "unavailableCriticalExtension"),

    /** Confidentiality is required. */
    CONFIDENTIALITY_REQUIRED(13, "confidentialityRequired"),

    /** SASL bind in progress. */
    SASL_BIND_IN_PROGRESS(14, "saslBindInProgress"),

    /** The specified attribute does not exist. */
    NO_SUCH_ATTRIBUTE(16, "noSuchAttribute"),

    /** An undefined attribute type was specified. */
    UNDEFINED_ATTRIBUTE_TYPE(17, "undefinedAttributeType"),

    /** An inappropriate matching rule was specified. */
    INAPPROPRIATE_MATCHING(18, "inappropriateMatching"),

    /** A constraint violation occurred. */
    CONSTRAINT_VIOLATION(19, "constraintViolation"),

    /** The attribute or value already exists. */
    ATTRIBUTE_OR_VALUE_EXISTS(20, "attributeOrValueExists"),

    /** Invalid attribute syntax. */
    INVALID_ATTRIBUTE_SYNTAX(21, "invalidAttributeSyntax"),

    /** The specified object does not exist. */
    NO_SUCH_OBJECT(32, "noSuchObject"),

    /** An alias problem occurred. */
    ALIAS_PROBLEM(33, "aliasProblem"),

    /** Invalid DN syntax. */
    INVALID_DN_SYNTAX(34, "invalidDNSyntax"),

    /** The entry is a leaf. */
    IS_LEAF(35, "isLeaf"),

    /** An alias dereferencing problem occurred. */
    ALIAS_DEREFERENCING_PROBLEM(36, "aliasDereferencingProblem"),

    /** Inappropriate authentication. */
    INAPPROPRIATE_AUTHENTICATION(48, "inappropriateAuthentication"),

    /** Invalid credentials provided. */
    INVALID_CREDENTIALS(49, "invalidCredentials"),

    /** Insufficient access rights. */
    INSUFFICIENT_ACCESS_RIGHTS(50, "insufficientAccessRights"),

    /** The server is busy. */
    BUSY(51, "busy"),

    /** The server is unavailable. */
    UNAVAILABLE(52, "unavailable"),

    /** The server is unwilling to perform the operation. */
    UNWILLING_TO_PERFORM(53, "unwillingToPerform"),

    /** A loop was detected. */
    LOOP_DETECT(54, "loopDetect"),

    /** A naming violation occurred. */
    NAMING_VIOLATION(64, "namingViolation"),

    /** An object class violation occurred. */
    OBJECT_CLASS_VIOLATION(65, "objectClassViolation"),

    /** The operation is not allowed on a non-leaf entry. */
    NOT_ALLOWED_ON_NON_LEAF(66, "notAllowedOnNonLeaf"),

    /** The operation is not allowed on an RDN. */
    NOT_ALLOWED_ON_RDN(67, "notAllowedOnRDN"),

    /** The entry already exists. */
    ENTRY_ALREADY_EXISTS(68, "entryAlreadyExists"),

    /** Object class modifications are prohibited. */
    OBJECT_CLASS_MODS_PROHIBITED(69, "objectClassModsProhibited"),

    /** The operation affects multiple DSAs. */
    AFFECTS_MULTIPLE_DSAS(71, "affectsMultipleDSAs"),

    /** An unknown error occurred. */
    OTHER(80, "other");

    private final int code;
    private final String name;

    LDAPResultCode(int code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * Returns the numeric result code.
     *
     * @return the result code
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the RFC name for this result code.
     *
     * @return the result code name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns whether this result code indicates success.
     *
     * @return true if successful
     */
    public boolean isSuccess() {
        return code == 0;
    }

    /**
     * Returns the LDAPResultCode for the given numeric code.
     *
     * @param code the numeric code
     * @return the result code, or OTHER if unknown
     */
    public static LDAPResultCode fromCode(int code) {
        for (LDAPResultCode rc : values()) {
            if (rc.code == code) {
                return rc;
            }
        }
        return OTHER;
    }

    @Override
    public String toString() {
        return name + " (" + code + ")";
    }
}

