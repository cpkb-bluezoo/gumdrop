/*
 * Control.java
 * Copyright (C) 2026 Chris Burdess
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

import java.util.Arrays;

/**
 * Represents an LDAP Control (RFC 4511 section 4.1.11).
 *
 * <p>Controls provide a mechanism for extending LDAP operations.
 * A control contains an OID identifying the control, a criticality
 * flag, and an optional value:
 *
 * <pre>
 * Control ::= SEQUENCE {
 *     controlType   LDAPOID,
 *     criticality   BOOLEAN DEFAULT FALSE,
 *     controlValue  OCTET STRING OPTIONAL }
 * </pre>
 *
 * <p>Common controls include paged results (OID 1.2.840.113556.1.4.319),
 * server-side sorting, and managed DSA IT.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4511#section-4.1.11">RFC 4511 §4.1.11</a>
 */
public class Control {

    /** Paged results control OID — RFC 2696. */
    public static final String OID_PAGED_RESULTS = "1.2.840.113556.1.4.319";

    /** Server-side sorting request control OID — RFC 2891. */
    public static final String OID_SORT_REQUEST = "1.2.840.113556.1.4.473";

    /** Server-side sorting response control OID — RFC 2891. */
    public static final String OID_SORT_RESPONSE = "1.2.840.113556.1.4.474";

    /** Managed DSA IT control OID — RFC 3296. */
    public static final String OID_MANAGED_DSA_IT = "2.16.840.1.113730.3.4.2";

    private final String oid;
    private final boolean critical;
    private final byte[] value;

    /**
     * Creates a control with no value.
     *
     * @param oid the control OID
     * @param critical whether the control is critical
     */
    public Control(String oid, boolean critical) {
        this(oid, critical, null);
    }

    /**
     * Creates a control with a value.
     *
     * @param oid the control OID
     * @param critical whether the control is critical
     * @param value the control value (may be null)
     */
    public Control(String oid, boolean critical, byte[] value) {
        if (oid == null || oid.isEmpty()) {
            throw new IllegalArgumentException("Control OID must not be null or empty");
        }
        this.oid = oid;
        this.critical = critical;
        this.value = value != null ? Arrays.copyOf(value, value.length) : null;
    }

    /**
     * Returns the control OID.
     *
     * @return the OID identifying this control
     */
    public String getOID() {
        return oid;
    }

    /**
     * Returns whether this control is critical.
     *
     * <p>If a server does not recognise a critical control, it MUST
     * return {@code unavailableCriticalExtension} (RFC 4511 §4.1.11).
     *
     * @return true if the control is critical
     */
    public boolean isCritical() {
        return critical;
    }

    /**
     * Returns the control value.
     *
     * @return the control value bytes, or null if no value
     */
    public byte[] getValue() {
        return value != null ? Arrays.copyOf(value, value.length) : null;
    }

    /**
     * Returns whether the control has a value.
     *
     * @return true if a value is present
     */
    public boolean hasValue() {
        return value != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Control[oid=");
        sb.append(oid);
        if (critical) {
            sb.append(", critical");
        }
        if (value != null) {
            sb.append(", value=").append(value.length).append(" bytes");
        }
        sb.append(']');
        return sb.toString();
    }
}
