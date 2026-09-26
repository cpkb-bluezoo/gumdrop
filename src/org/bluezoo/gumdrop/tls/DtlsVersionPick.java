/*
 * DtlsVersionPick.java
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

import java.util.Arrays;

/**
 * Pick DTLS 1.2 vs 1.3 from epoch-0 handshake traffic (RFC 6347 / RFC 9147).
 */
public final class DtlsVersionPick {

    private static final int DTLS_VERSION_MAJOR = 0xfe;
    private static final int DTLS12_MINOR = 0xfd;
    private static final int CONTENT_HANDSHAKE = 22;
    private static final int RECORD_HEADER_LEN = 13;
    private static final int HANDSHAKE_CLIENT_HELLO = 1;
    private static final int HANDSHAKE_SERVER_HELLO = 2;

    private DtlsVersionPick() {
    }

    public static TlsVersionPick.Picked findClientHelloInDtls(byte[] buf) throws HandshakeFormatException {
        return findHandshake(buf, HANDSHAKE_CLIENT_HELLO, true);
    }

    public static TlsVersionPick.Picked findServerHelloInDtls(byte[] buf) throws HandshakeFormatException {
        return findHandshake(buf, HANDSHAKE_SERVER_HELLO, false);
    }

    private static TlsVersionPick.Picked findHandshake(byte[] buf, int wantType, boolean serverPick)
            throws HandshakeFormatException {
        DtlsReassembler reasm = new DtlsReassembler();
        int off = 0;
        while (off < buf.length) {
            if (buf.length - off < RECORD_HEADER_LEN) {
                return null;
            }
            int contentType = buf[off] & 0xff;
            int verMajor = buf[off + 1] & 0xff;
            int verMinor = buf[off + 2] & 0xff;
            if (verMajor != DTLS_VERSION_MAJOR || verMinor != DTLS12_MINOR) {
                throw new HandshakeFormatException("not a DTLS 1.2-shaped plaintext record");
            }
            int recordLength = ((buf[off + 11] & 0xff) << 8) | (buf[off + 12] & 0xff);
            if (off + RECORD_HEADER_LEN + recordLength > buf.length) {
                return null;
            }
            if (contentType == CONTENT_HANDSHAKE) {
                byte[] payload = Arrays.copyOfRange(buf, off + RECORD_HEADER_LEN,
                        off + RECORD_HEADER_LEN + recordLength);
                for (byte[] msg : reasm.addFragment(payload)) {
                    if (msg.length < 4 || (msg[0] & 0xff) != wantType) {
                        continue;
                    }
                    byte[] body = Arrays.copyOfRange(msg, 4, msg.length);
                    if (serverPick) {
                        return TlsVersionPick.pickServerVersion(body);
                    }
                    return TlsVersionPick.pickClientVersion(body);
                }
            }
            off += RECORD_HEADER_LEN + recordLength;
        }
        return null;
    }
}
