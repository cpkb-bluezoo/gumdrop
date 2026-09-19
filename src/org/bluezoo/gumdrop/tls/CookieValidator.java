/*
 * CookieValidator.java
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

package org.bluezoo.gumdrop.tls;

/**
 * RFC 9147 section 5.2 anti-amplification cookie validation for DTLS 1.3
 * HelloRetryRequest. Address binding lives in concrete implementations.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
