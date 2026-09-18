/*
 * EncryptedClientHello.java
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
 * RFC 9849 {@code encrypted_client_hello} extension payloads and related
 * server messages ({@code ECHEncryptedExtensions}, {@code ECHHelloRetryRequest}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9849#section-5">RFC 9849 section 5</a>
 */
public final class EncryptedClientHello {

    /** TLS {@code ExtensionType} for Encrypted Client Hello (RFC 9849). */
    public static final int EXTENSION_TYPE = 0xfe0d;

    /** {@code ECHClientHelloType.outer}. */
    public static final int CLIENT_HELLO_TYPE_OUTER = 0;

    /** {@code ECHClientHelloType.inner}. */
    public static final int CLIENT_HELLO_TYPE_INNER = 1;

    /** Length of {@code ECHHelloRetryRequest.confirmation}. */
    public static final int HRR_CONFIRMATION_LENGTH = 8;

    private EncryptedClientHello() {
    }

    /**
     * Outer {@code ECHClientHello} on {@code ClientHelloOuter} (RFC 9849 section 5).
     */
    public static final class Outer {
        public final int kdfId;
        public final int aeadId;
        public final int configId;
        public final byte[] enc;
        public final byte[] payload;

        public Outer(int kdfId, int aeadId, int configId, byte[] enc, byte[] payload) {
            this.kdfId = kdfId;
            this.aeadId = aeadId;
            this.configId = configId;
            this.enc = enc;
            this.payload = payload;
        }
    }

    /**
     * Parses an {@code encrypted_client_hello} extension value on ClientHello.
     */
    public static Parsed parseClientHelloPayload(byte[] extBody) throws HandshakeFormatException {
        if (extBody.length < 1) {
            throw new HandshakeFormatException("encrypted_client_hello is empty");
        }
        WireReader r = new WireReader(extBody);
        int type = r.u8();
        if (type == CLIENT_HELLO_TYPE_INNER) {
            if (r.hasRemaining()) {
                throw new HandshakeFormatException("inner encrypted_client_hello must be empty");
            }
            return Parsed.inner();
        }
        if (type != CLIENT_HELLO_TYPE_OUTER) {
            throw new HandshakeFormatException("Unknown ECHClientHelloType: " + type);
        }
        int kdfId = r.u16();
        int aeadId = r.u16();
        int configId = r.u8();
        byte[] enc = r.opaque16();
        byte[] payload = r.opaque16();
        if (payload.length < 1) {
            throw new HandshakeFormatException("encrypted_client_hello payload is empty");
        }
        if (r.hasRemaining()) {
            throw new HandshakeFormatException("Trailing bytes in outer encrypted_client_hello");
        }
        return Parsed.outer(new Outer(kdfId, aeadId, configId, enc, payload));
    }

    /**
     * Encodes the inner {@code ECHClientHello} (type only).
     */
    public static byte[] encodeClientHelloInner() {
        return new byte[] { (byte) CLIENT_HELLO_TYPE_INNER };
    }

    /**
     * Encodes the outer {@code ECHClientHello}.
     */
    public static byte[] encodeClientHelloOuter(Outer outer) throws HandshakeFormatException {
        if (outer.payload.length < 1) {
            throw new HandshakeFormatException("encrypted_client_hello payload is empty");
        }
        WireWriter w = new WireWriter();
        w.u8(CLIENT_HELLO_TYPE_OUTER);
        w.u16(outer.kdfId);
        w.u16(outer.aeadId);
        w.u8(outer.configId);
        w.opaque16(outer.enc);
        w.opaque16(outer.payload);
        return w.toByteArray();
    }

    /**
     * Encodes {@code ECHEncryptedExtensions} (retry configs in EncryptedExtensions).
     */
    public static byte[] encodeEncryptedExtensions(byte[] echConfigListBytes) throws HandshakeFormatException {
        if (echConfigListBytes.length < 4 || echConfigListBytes.length > 65535) {
            throw new HandshakeFormatException("ECHConfigList length out of range");
        }
        return echConfigListBytes;
    }

    /**
     * Parses {@code ECHEncryptedExtensions} from an EncryptedExtensions extension body.
     */
    public static EchConfig[] parseEncryptedExtensions(byte[] extBody) throws HandshakeFormatException {
        return EchConfig.parseList(extBody);
    }

    /**
     * Encodes {@code ECHHelloRetryRequest.confirmation}.
     */
    public static byte[] encodeHelloRetryRequest(byte[] confirmation) throws HandshakeFormatException {
        if (confirmation.length != HRR_CONFIRMATION_LENGTH) {
            throw new HandshakeFormatException("ECH HRR confirmation must be 8 bytes");
        }
        WireWriter w = new WireWriter();
        w.bytes(confirmation);
        return w.toByteArray();
    }

    /**
     * Parses {@code ECHHelloRetryRequest} on HelloRetryRequest.
     */
    public static byte[] parseHelloRetryRequest(byte[] extBody) throws HandshakeFormatException {
        if (extBody.length != HRR_CONFIRMATION_LENGTH) {
            throw new HandshakeFormatException("ECH HRR confirmation must be 8 bytes");
        }
        WireReader r = new WireReader(extBody);
        return r.bytes(HRR_CONFIRMATION_LENGTH);
    }

    /**
     * Result of parsing ClientHello {@code encrypted_client_hello}.
     */
    public static final class Parsed {
        private final boolean inner;
        private final Outer outer;

        private Parsed(boolean inner, Outer outer) {
            this.inner = inner;
            this.outer = outer;
        }

        static Parsed inner() {
            return new Parsed(true, null);
        }

        static Parsed outer(Outer outer) {
            return new Parsed(false, outer);
        }

        public boolean isInner() {
            return inner;
        }

        public boolean isOuter() {
            return !inner;
        }

        public Outer getOuter() {
            return outer;
        }
    }
}
