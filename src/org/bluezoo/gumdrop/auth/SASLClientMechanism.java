/*
 * SASLClientMechanism.java
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

package org.bluezoo.gumdrop.auth;

import java.io.IOException;

/**
 * Client-side SASL mechanism for driving challenge-response exchanges.
 *
 * <p>Unlike {@code javax.security.sasl.SaslClient}, this interface is
 * non-blocking and uses only gumdrop's own cryptographic primitives
 * ({@link SASLUtils}), making it safe to call from the NIO event loop.
 *
 * <p>Obtain instances via {@link SASLUtils#createClient}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SASLUtils#createClient
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4422">RFC 4422: SASL</a>
 */
public interface SASLClientMechanism {

    /**
     * Returns the IANA-registered mechanism name (e.g. {@code "DIGEST-MD5"}).
     *
     * @return the mechanism name
     */
    String getMechanismName();

    /**
     * Returns whether this mechanism produces an initial response
     * before the server sends a challenge.
     *
     * @return true if the client sends data in the first message
     */
    boolean hasInitialResponse();

    /**
     * Evaluates a server challenge and produces the next client response.
     *
     * <p>For the initial response (when {@link #hasInitialResponse()} is
     * true), pass an empty array as the challenge.
     *
     * @param challenge the server's challenge bytes (empty for initial)
     * @return the client response, or empty array if no data to send
     * @throws IOException if the challenge is malformed or evaluation fails
     */
    byte[] evaluateChallenge(byte[] challenge) throws IOException;

    /**
     * Returns whether the SASL exchange has completed.
     *
     * @return true if no further challenges are expected
     */
    boolean isComplete();

}
