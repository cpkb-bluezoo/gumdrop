/*
 * StatelessResetToken.java
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

package org.bluezoo.gumdrop.quic.cid;

import java.util.Arrays;

import org.bluezoo.gumdrop.quic.tls.Hkdf;

/**
 * Stateless reset token generation (RFC 9000 section 10.3).
 *
 * <p>RFC 9000 does not mandate a specific algorithm, only that the token
 * be "difficult to guess" and reproducible from the connection ID
 * without persisted per-connection state -- so that an endpoint that has
 * lost a connection's state (e.g. after a restart) can still recognise
 * or generate a valid stateless reset for a connection ID it issued.
 * This follows the common approach (also used by quiche, quinn, and
 * ngtcp2): HMAC-SHA-256 of the connection ID under a static, per-process
 * key, truncated to the 16 bytes RFC 9000 section 19.15 requires.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-10.3">RFC 9000 section 10.3</a>
 */
public final class StatelessResetToken {

    /** RFC 9000 section 19.15: the fixed token length in bytes. */
    public static final int LENGTH = 16;

    private StatelessResetToken() {
    }

    /**
     * Deterministically derives a stateless reset token for a connection ID.
     *
     * @param staticKey this endpoint's static key, kept secret and stable
     *                  across connections (and ideally across restarts)
     * @param connectionId the connection ID the token is for
     * @return the 16-byte stateless reset token
     */
    public static byte[] generate(byte[] staticKey, byte[] connectionId) {
        byte[] fullHash = Hkdf.sha256().extract(staticKey, connectionId);
        return Arrays.copyOf(fullHash, LENGTH);
    }
}
