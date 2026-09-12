/*
 * Dtls13CookieValidator.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop;

import java.net.InetSocketAddress;

import org.bluezoo.gumdrop.tls.CookieValidator;
import org.bluezoo.gumdrop.tls.Dtls12HelloVerify;

/**
 * Address-bound RFC 9147 cookie validator for {@link Dtls13Session}.
 */
final class Dtls13CookieValidator implements CookieValidator {

    private final byte[] secret;
    private final InetSocketAddress source;

    Dtls13CookieValidator(byte[] secret, InetSocketAddress source) {
        this.secret = secret;
        this.source = source;
    }

    @Override
    public byte[] computeCookie(byte[] clientHelloRandom) {
        try {
            return Dtls12HelloVerify.computeCookie(secret, clientHelloRandom, source);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute DTLS cookie", e);
        }
    }

    @Override
    public boolean validateCookie(byte[] clientHelloRandom, byte[] cookie) {
        return Dtls12HelloVerify.validateCookie(secret, clientHelloRandom, source, cookie);
    }
}
