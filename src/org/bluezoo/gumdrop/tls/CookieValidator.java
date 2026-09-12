/*
 * CookieValidator.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

/**
 * RFC 9147 section 5.2 anti-amplification cookie validation for DTLS 1.3
 * HelloRetryRequest. Address binding lives in concrete implementations.
 */
public interface CookieValidator {

    /**
     * Computes a cookie for the given ClientHello random.
     *
     * @param clientHelloRandom the ClientHello random bytes
     * @return the cookie bytes
     */
    byte[] computeCookie(byte[] clientHelloRandom);

    /**
     * Validates a cookie echoed in a followup ClientHello.
     *
     * @param clientHelloRandom the ClientHello random bytes
     * @param cookie the cookie from the ClientHello
     * @return true if valid
     */
    boolean validateCookie(byte[] clientHelloRandom, byte[] cookie);
}
