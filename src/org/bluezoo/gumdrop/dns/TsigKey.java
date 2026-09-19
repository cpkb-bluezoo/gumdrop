/*
 * TsigKey.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Shared secret TSIG key (RFC 2845).
 */
public final class TsigKey {

    public static final String HMAC_MD5 = "hmac-md5";
    public static final String HMAC_SHA1 = "hmac-sha1";
    public static final String HMAC_SHA256 = "hmac-sha256";

    private final String name;
    private final String algorithm;
    private final byte[] secret;

    public TsigKey(String name, String algorithm, byte[] secret) {
        if (name == null || algorithm == null || secret == null) {
            throw new IllegalArgumentException("name/algorithm/secret");
        }
        this.name = normalizeName(name);
        this.algorithm = algorithm;
        this.secret = secret.clone();
    }

    /**
     * Creates a key from a BIND-style base64 secret string.
     */
    public static TsigKey fromBase64(String name, String algorithm, String base64Secret) {
        byte[] raw = java.util.Base64.getDecoder().decode(base64Secret.trim());
        return new TsigKey(name, algorithm, raw);
    }

    public String getName() {
        return name;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public byte[] getSecret() {
        return secret.clone();
    }

    private static String normalizeName(String name) {
        String n = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (!n.endsWith(".")) {
            n = n + ".";
        }
        return n;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TsigKey)) {
            return false;
        }
        TsigKey other = (TsigKey) obj;
        return name.equals(other.name) && algorithm.equals(other.algorithm)
                && Arrays.equals(secret, other.secret);
    }

    @Override
    public int hashCode() {
        return name.hashCode() ^ algorithm.hashCode();
    }
}
