/*
 * TlsVersionPick.java
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
 * Pick TLS 1.2 vs 1.3 from the first handshake flight on TCP (no mid-connection
 * version change afterward). DTLS uses {@link DtlsVersionPick} with the same
 * {@link Picked} outcome.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TlsVersionPick {

    private static final int TLS12 = 0x0303;
    private static final int TLS13 = 0x0304;
    private static final int DTLS12 = 0xfefd;
    private static final int DTLS13 = 0xfefc;

    private static final int RECORD_HANDSHAKE = 22;
    private static final int HANDSHAKE_CLIENT_HELLO = HandshakeMessages.HANDSHAKE_TYPE_CLIENT_HELLO;
    private static final int HANDSHAKE_SERVER_HELLO = 2;

    private static final int EXT_SUPPORTED_VERSIONS = 0x002b;

    /** Negotiated wire protocol for one connection. */
    public enum Picked {
        V13,
        V12
    }

    private TlsVersionPick() {
    }

    /** Prefer TLS 1.3 when the peer's {@code ClientHello} advertises it. */
    public static Picked pickServerVersion(byte[] clientHelloBody) throws HandshakeFormatException {
        return pickServerVersion(clientHelloBody, false);
    }

    /**
     * As {@link #pickServerVersion(byte[])}; with {@code dtlsTransport} the
     * {@code ClientHello} carries the DTLS {@code cookie} field after
     * {@code legacy_session_id} (RFC 6347 section 4.2.1, RFC 9147 section 5.3).
     */
    static Picked pickServerVersion(byte[] clientHelloBody, boolean dtlsTransport)
            throws HandshakeFormatException {
        if (clientHelloBody.length < 2) {
            throw new HandshakeFormatException("ClientHello too short");
        }
        int legacy = ((clientHelloBody[0] & 0xff) << 8) | (clientHelloBody[1] & 0xff);
        byte[] framed = WireWriter.frameHandshakeMessage(HANDSHAKE_CLIENT_HELLO, clientHelloBody);
        HandshakeMessages.ClientHello parsed = HandshakeMessages.parseClientHello(framed, dtlsTransport);
        if (!parsed.supportedVersionCodes.isEmpty()) {
            if (offersV13(parsed.supportedVersionCodes)) {
                return Picked.V13;
            }
            if (offersV12(parsed.supportedVersionCodes)) {
                return Picked.V12;
            }
            if (legacy >= TLS12 || legacy == DTLS12) {
                return Picked.V12;
            }
            throw new HandshakeFormatException("unsupported TLS versions in ClientHello");
        }
        if (legacy >= TLS12 || legacy == DTLS12) {
            return Picked.V12;
        }
        throw new HandshakeFormatException("unsupported legacy ClientHello version");
    }

    /** Inspect the first {@code ServerHello} body after our {@code ClientHello} was sent. */
    public static Picked pickClientVersion(byte[] serverHelloBody) throws HandshakeFormatException {
        Integer ver = supportedVersionInServerHello(serverHelloBody);
        if (ver != null) {
            if (ver == TLS13 || ver == DTLS13) {
                return Picked.V13;
            }
            if (ver == TLS12 || ver == DTLS12) {
                return Picked.V12;
            }
            throw new HandshakeFormatException("unsupported ServerHello version");
        }
        return Picked.V12;
    }

    /**
     * Buffer until the first complete {@code ClientHello} is present in
     * cleartext TLS records. Returns null if more data is needed.
     */
    public static Picked findClientHelloInRecords(byte[] buf) throws HandshakeFormatException {
        int off = 0;
        while (off + 5 <= buf.length) {
            int typ = buf[off] & 0xff;
            int recLen = ((buf[off + 3] & 0xff) << 8) | (buf[off + 4] & 0xff);
            if (off + 5 + recLen > buf.length) {
                return null;
            }
            if (typ == RECORD_HANDSHAKE) {
                int payloadOff = off + 5;
                int payloadEnd = payloadOff + recLen;
                int p = payloadOff;
                while (p + 4 <= payloadEnd) {
                    int htyp = buf[p] & 0xff;
                    int hlen = handshakeLen(buf, p + 1);
                    if (p + 4 + hlen > payloadEnd) {
                        return null;
                    }
                    if (htyp == HANDSHAKE_CLIENT_HELLO) {
                        byte[] body = new byte[hlen];
                        System.arraycopy(buf, p + 4, body, 0, hlen);
                        return pickServerVersion(body);
                    }
                    p += 4 + hlen;
                }
            }
            off += 5 + recLen;
        }
        return null;
    }

    /**
     * After the client has sent {@code ClientHello}, buffer server records until
     * {@code ServerHello} is complete. Returns null if more data is needed.
     */
    public static Picked findServerHelloInRecords(byte[] buf) throws HandshakeFormatException {
        int off = 0;
        while (off + 5 <= buf.length) {
            int typ = buf[off] & 0xff;
            int recLen = ((buf[off + 3] & 0xff) << 8) | (buf[off + 4] & 0xff);
            if (off + 5 + recLen > buf.length) {
                return null;
            }
            if (typ == RECORD_HANDSHAKE) {
                int payloadOff = off + 5;
                int payloadEnd = payloadOff + recLen;
                int p = payloadOff;
                while (p + 4 <= payloadEnd) {
                    int htyp = buf[p] & 0xff;
                    int hlen = handshakeLen(buf, p + 1);
                    if (p + 4 + hlen > payloadEnd) {
                        return null;
                    }
                    if (htyp == HANDSHAKE_SERVER_HELLO) {
                        byte[] body = new byte[hlen];
                        System.arraycopy(buf, p + 4, body, 0, hlen);
                        return pickClientVersion(body);
                    }
                    p += 4 + hlen;
                }
            }
            off += 5 + recLen;
        }
        return null;
    }

    static Picked pickServerVersionFromTls12ClientHello(Tls12HandshakeMessages.ClientHello ch)
            throws HandshakeFormatException {
        if (ch.supportedVersions != null) {
            if (offersV13(ch.supportedVersions)) {
                return Picked.V13;
            }
            if (offersV12(ch.supportedVersions)) {
                return Picked.V12;
            }
        }
        return Picked.V12;
    }

    private static boolean offersV13(java.util.List<Integer> versions) {
        for (int i = 0; i < versions.size(); i++) {
            int v = versions.get(i);
            if (v == TLS13 || v == DTLS13) {
                return true;
            }
        }
        return false;
    }

    private static boolean offersV12(java.util.List<Integer> versions) {
        for (int i = 0; i < versions.size(); i++) {
            int v = versions.get(i);
            if (v == TLS12 || v == DTLS12) {
                return true;
            }
        }
        return false;
    }

    private static Integer supportedVersionInServerHello(byte[] body) {
        if (body.length < 2 + 32 + 1) {
            return null;
        }
        int i = 2 + 32;
        int sidLen = body[i] & 0xff;
        i += 1 + sidLen + 2 + 1;
        if (i + 2 > body.length) {
            return null;
        }
        int extLen = ((body[i] & 0xff) << 8) | (body[i + 1] & 0xff);
        i += 2;
        if (body.length < i + extLen) {
            return null;
        }
        int extEnd = i + extLen;
        while (i + 4 <= extEnd) {
            int et = ((body[i] & 0xff) << 8) | (body[i + 1] & 0xff);
            int el = ((body[i + 2] & 0xff) << 8) | (body[i + 3] & 0xff);
            i += 4;
            if (i + el > extEnd) {
                break;
            }
            if (et == EXT_SUPPORTED_VERSIONS && el == 2) {
                return ((body[i] & 0xff) << 8) | (body[i + 1] & 0xff);
            }
            i += el;
        }
        return null;
    }

    private static int handshakeLen(byte[] buf, int off) throws HandshakeFormatException {
        if (off + 3 > buf.length) {
            throw new HandshakeFormatException("truncated handshake length");
        }
        return ((buf[off] & 0xff) << 16) | ((buf[off + 1] & 0xff) << 8) | (buf[off + 2] & 0xff);
    }
}
