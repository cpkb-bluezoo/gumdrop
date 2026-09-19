/*
 * Dtls12RecordFormat.java
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
 * Shared DTLS 1.2 on-the-wire constants used by {@link Dtls12RecordEngine} and
 * {@link Dtls12HelloVerify}. Kept separate so cookie exchange helpers compile in
 * the early {@code build-core} pass without pulling in handshake offload types.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
