/*
 * ProxyTlvs.java
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

package org.bluezoo.gumdrop;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The type-length-value vectors carried by a PROXY protocol v2 header.
 *
 * <p>Instances are immutable once the header has been parsed. Unknown types
 * are retained and available through {@link #get(int)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ProxyTlvs {

    /** Application-layer protocol negotiation. */
    public static final int PP2_TYPE_ALPN = 0x01;
    /** Authority (SNI-like host name). */
    public static final int PP2_TYPE_AUTHORITY = 0x02;
    /** Opaque unique connection identifier. */
    public static final int PP2_TYPE_UNIQUE_ID = 0x05;
    /** TLS information about the client-facing connection. */
    public static final int PP2_TYPE_SSL = 0x20;
    /** Amazon Web Services vendor TLV. */
    public static final int PP2_TYPE_AWS = 0xEA;
    /** AWS subtype carrying the VPC endpoint ID. */
    public static final int PP2_SUBTYPE_AWS_VPCE_ID = 0x01;

    private final List<Integer> types = new ArrayList<Integer>();
    private final List<byte[]> values = new ArrayList<byte[]>();

    ProxyTlvs() {
    }

    void add(int type, byte[] value) {
        types.add(Integer.valueOf(type));
        values.add(value);
    }

    /**
     * Returns the number of TLVs present.
     *
     * @return the TLV count
     */
    public int size() {
        return types.size();
    }

    /**
     * Returns the value of the first TLV of the given type.
     *
     * @param type the TLV type (0-255)
     * @return a copy of the value, or null if absent
     */
    public byte[] get(int type) {
        byte[] v = find(type);
        return v == null ? null : v.clone();
    }

    private byte[] find(int type) {
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).intValue() == type) {
                return values.get(i);
            }
        }
        return null;
    }

    /**
     * Returns the ALPN protocol, or null if absent.
     *
     * @return the ALPN value
     */
    public String getAlpn() {
        byte[] v = find(PP2_TYPE_ALPN);
        return v == null ? null : new String(v, StandardCharsets.ISO_8859_1);
    }

    /**
     * Returns the authority, or null if absent.
     *
     * @return the authority host name
     */
    public String getAuthority() {
        byte[] v = find(PP2_TYPE_AUTHORITY);
        return v == null ? null : new String(v, StandardCharsets.UTF_8);
    }

    /**
     * Returns the unique connection ID, or null if absent.
     *
     * @return a copy of the ID bytes
     */
    public byte[] getUniqueId() {
        return get(PP2_TYPE_UNIQUE_ID);
    }

    /**
     * Returns the raw PP2_TYPE_SSL value (client byte, verify word and
     * nested sub-TLVs), or null if absent.
     *
     * @return a copy of the SSL TLV value
     */
    public byte[] getSsl() {
        return get(PP2_TYPE_SSL);
    }

    /**
     * Returns the AWS VPC endpoint ID, or null if the AWS TLV is absent or
     * is of a different subtype.
     *
     * @return the VPC endpoint ID, e.g. {@code vpce-0123456789abcdef0}
     */
    public String getAwsVpcEndpointId() {
        byte[] v = find(PP2_TYPE_AWS);
        if (v == null || v.length < 1 || (v[0] & 0xFF) != PP2_SUBTYPE_AWS_VPCE_ID) {
            return null;
        }
        return new String(v, 1, v.length - 1, StandardCharsets.US_ASCII);
    }

}
