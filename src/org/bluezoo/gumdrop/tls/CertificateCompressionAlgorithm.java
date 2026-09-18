/*
 * CertificateCompressionAlgorithm.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

/**
 * RFC 8879 certificate compression algorithms.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8879">RFC 8879</a>
 */
public enum CertificateCompressionAlgorithm {

    ZLIB(1),
    BROTLI(2);

    private final int id;

    CertificateCompressionAlgorithm(int id) {
        this.id = id;
    }

    /**
     * Returns the wire identifier for this algorithm.
     *
     * @return RFC 8879 algorithm id
     */
    public int getId() {
        return id;
    }

    /**
     * Maps a wire id to an algorithm, or null if unknown.
     *
     * @param id algorithm id from the peer
     * @return the algorithm, or null
     */
    public static CertificateCompressionAlgorithm fromId(int id) {
        if (id == ZLIB.id) {
            return ZLIB;
        }
        if (id == BROTLI.id) {
            return BROTLI;
        }
        return null;
    }
}
