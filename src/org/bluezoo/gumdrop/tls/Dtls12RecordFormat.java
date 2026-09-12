/*
 * Dtls12RecordFormat.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

/**
 * Shared DTLS 1.2 on-the-wire constants used by {@link Dtls12RecordEngine} and
 * {@link Dtls12HelloVerify}. Kept separate so cookie exchange helpers compile in
 * the early {@code build-core} pass without pulling in handshake offload types.
 */
final class Dtls12RecordFormat {

    static final int DTLS_VERSION_MAJOR = 0xfe;
    static final int DTLS_VERSION_MINOR = 0xfd;
    static final int RECORD_HEADER_LEN = 13;
    static final int FRAGMENT_HEADER_LEN = 12;
    static final int HANDSHAKE_TYPE_HELLO_VERIFY_REQUEST = 3;

    private Dtls12RecordFormat() {
    }
}
