/*
 * Dtls13CookieValidator.java
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

import java.net.InetSocketAddress;

import org.bluezoo.gumdrop.crypto.CryptoServices;
import org.bluezoo.gumdrop.tls.CookieValidator;
import org.bluezoo.gumdrop.tls.Dtls12HelloVerify;

/**
 * Address-bound RFC 9147 cookie validator for {@link Dtls13Session}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class Dtls13CookieValidator implements CookieValidator {

    private final byte[] secret;
    private final InetSocketAddress source;
    private final CryptoServices crypto;

    Dtls13CookieValidator(byte[] secret, InetSocketAddress source, CryptoServices crypto) {
        this.secret = secret;
        this.source = source;
        this.crypto = crypto;
    }

    @Override
    public byte[] computeCookie(byte[] clientHelloRandom) {
        try {
            return Dtls12HelloVerify.computeCookie(secret, clientHelloRandom, source, crypto);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute DTLS cookie", e);
        }
    }

    @Override
    public boolean validateCookie(byte[] clientHelloRandom, byte[] cookie) {
        return Dtls12HelloVerify.validateCookie(secret, clientHelloRandom, source, cookie, crypto);
    }
}
