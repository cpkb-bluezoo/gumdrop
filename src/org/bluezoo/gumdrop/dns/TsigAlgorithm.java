/*
 * TsigAlgorithm.java
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

package org.bluezoo.gumdrop.dns;

import java.util.Locale;

/**
 * TSIG algorithm names on the wire (RFC 2845, RFC 4635, RFC 8945).
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TsigAlgorithm {

    public static final String WIRE_HMAC_MD5 = "hmac-md5.sig-alg.reg.int.";
    public static final String WIRE_HMAC_SHA1 = "hmac-sha1.";
    public static final String WIRE_HMAC_SHA256 = "hmac-sha256.";

    private TsigAlgorithm() {
    }

    /**
     * Returns the DNS wire algorithm name for a {@link TsigKey} algorithm id.
     */
    public static String toWireName(String algorithmId) {
        if (algorithmId == null) {
            throw new NullPointerException("algorithmId");
        }
        String id = algorithmId.trim().toLowerCase(Locale.ROOT);
        if (id.equals(TsigKey.HMAC_MD5) || id.equals("hmac-md5.sig-alg.reg.int")
                || id.equals(WIRE_HMAC_MD5)) {
            return WIRE_HMAC_MD5;
        }
        if (id.equals(TsigKey.HMAC_SHA1) || id.equals("hmac-sha1")
                || id.equals(WIRE_HMAC_SHA1)) {
            return WIRE_HMAC_SHA1;
        }
        if (id.equals(TsigKey.HMAC_SHA256) || id.equals("hmac-sha256")
                || id.equals(WIRE_HMAC_SHA256)) {
            return WIRE_HMAC_SHA256;
        }
        if (id.endsWith(".")) {
            return id;
        }
        return id + ".";
    }

    /**
     * JCA MAC algorithm name for HMAC.
     */
    public static String toJcaName(String algorithmId) {
        String wire = toWireName(algorithmId);
        if (WIRE_HMAC_SHA256.equalsIgnoreCase(wire)) {
            return "HmacSHA256";
        }
        if (WIRE_HMAC_SHA1.equalsIgnoreCase(wire)) {
            return "HmacSHA1";
        }
        return "HmacMD5";
    }

    /**
     * Returns true if the wire algorithm name matches the configured key.
     */
    public static boolean matchesKey(TsigKey key, String algorithmWireName) {
        if (key == null || algorithmWireName == null) {
            return false;
        }
        return canonicalWire(algorithmWireName)
                .equalsIgnoreCase(canonicalWire(toWireName(key.getAlgorithm())));
    }

    static String canonicalWire(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (!n.endsWith(".")) {
            n = n + ".";
        }
        return n;
    }
}
