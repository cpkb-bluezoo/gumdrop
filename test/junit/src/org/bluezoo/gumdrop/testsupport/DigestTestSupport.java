/*
 * DigestTestSupport.java
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

package org.bluezoo.gumdrop.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.bluezoo.util.ByteArrays;

/**
 * Independent HTTP Digest computations for tests (RFC 7616). Deliberately
 * does not use any production code so that tests verify it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DigestTestSupport {

    private DigestTestSupport() {
    }

    /** Lowercase hex of the given digest algorithm over the string. */
    public static String hex(String algorithm, String data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return ByteArrays.toHexString(
                    md.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** H(username:realm:password) using SHA-256. */
    public static String ha1(String user, String realm, String password) {
        return hex("SHA-256", user + ":" + realm + ":" + password);
    }

    /** Digest response with qop=auth for the given algorithm (e.g. SHA-256 or MD5). */
    public static String response(String algorithm, String ha1Hex, String nonce,
            String nc, String cnonce, String qop, String method, String uri) {
        String ha2 = hex(algorithm, method + ":" + uri);
        return hex(algorithm, ha1Hex + ":" + nonce + ":" + nc + ":" + cnonce
                + ":" + qop + ":" + ha2);
    }

    /** SHA-256 response with qop=auth. */
    public static String response(String ha1Hex, String nonce, String nc,
            String cnonce, String method, String uri) {
        return response("SHA-256", ha1Hex, nonce, nc, cnonce, "auth",
                method, uri);
    }
}
