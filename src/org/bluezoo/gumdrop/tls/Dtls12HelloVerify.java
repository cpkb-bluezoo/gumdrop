/*
 * Dtls12HelloVerify.java
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

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6347 HelloVerifyRequest cookie exchange helpers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Dtls12HelloVerify {

    private Dtls12HelloVerify() {
    }

    public static byte[] computeCookie(byte[] secret, byte[] clientRandom, InetSocketAddress source)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        mac.update(clientRandom);
        if (source != null) {
            if (source.getAddress() != null) {
                mac.update(source.getAddress().getAddress());
            }
            mac.update((byte) ((source.getPort() >> 8) & 0xff));
            mac.update((byte) (source.getPort() & 0xff));
        }
        return mac.doFinal();
    }

    public static boolean validateCookie(byte[] secret, byte[] clientRandom, InetSocketAddress source, byte[] cookie) {
        if (cookie == null || cookie.length == 0) {
            return false;
        }
        try {
            byte[] expected = computeCookie(secret, clientRandom, source);
            return Arrays.equals(expected, cookie);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public static byte[] buildHelloVerifyRequestDatagram(byte[] cookie) {
        WireWriter body = new WireWriter();
        body.u16((Dtls12RecordEngine.DTLS_VERSION_MAJOR << 8) | Dtls12RecordEngine.DTLS_VERSION_MINOR);
        body.opaque8(cookie);
        byte[] messageBody = body.toByteArray();
        byte[] fragment = new byte[Dtls12RecordEngine.FRAGMENT_HEADER_LEN + messageBody.length];
        fragment[0] = (byte) Dtls12RecordEngine.HANDSHAKE_TYPE_HELLO_VERIFY_REQUEST;
        fragment[1] = (byte) ((messageBody.length >> 16) & 0xff);
        fragment[2] = (byte) ((messageBody.length >> 8) & 0xff);
        fragment[3] = (byte) (messageBody.length & 0xff);
        // message_seq 0 for HelloVerifyRequest
        fragment[9] = (byte) ((messageBody.length >> 16) & 0xff);
        fragment[10] = (byte) ((messageBody.length >> 8) & 0xff);
        fragment[11] = (byte) (messageBody.length & 0xff);
        System.arraycopy(messageBody, 0, fragment, Dtls12RecordEngine.FRAGMENT_HEADER_LEN, messageBody.length);

        byte[] record = new byte[Dtls12RecordEngine.RECORD_HEADER_LEN + fragment.length];
        record[0] = 22;
        record[1] = (byte) Dtls12RecordEngine.DTLS_VERSION_MAJOR;
        record[2] = (byte) Dtls12RecordEngine.DTLS_VERSION_MINOR;
        record[11] = (byte) ((fragment.length >> 8) & 0xff);
        record[12] = (byte) (fragment.length & 0xff);
        System.arraycopy(fragment, 0, record, Dtls12RecordEngine.RECORD_HEADER_LEN, fragment.length);
        return record;
    }

    /**
     * Extracts ClientHello random and cookie from an epoch-0 handshake datagram.
     *
     * @return parsed values, or null if not a single complete ClientHello
     */
    public static ClientHelloFields parseClientHelloFields(byte[] datagram) {
        if (datagram.length < Dtls12RecordEngine.RECORD_HEADER_LEN + Dtls12RecordEngine.FRAGMENT_HEADER_LEN + 34) {
            return null;
        }
        if ((datagram[0] & 0xff) != 22) {
            return null;
        }
        int length = ((datagram[11] & 0xff) << 8) | (datagram[12] & 0xff);
        if (Dtls12RecordEngine.RECORD_HEADER_LEN + length > datagram.length) {
            return null;
        }
        byte[] fragment = Arrays.copyOfRange(datagram, Dtls12RecordEngine.RECORD_HEADER_LEN,
                Dtls12RecordEngine.RECORD_HEADER_LEN + length);
        if ((fragment[0] & 0xff) != 1) {
            return null;
        }
        int totalLength = ((fragment[1] & 0xff) << 16) | ((fragment[2] & 0xff) << 8) | (fragment[3] & 0xff);
        int fragmentOffset = ((fragment[6] & 0xff) << 16) | ((fragment[7] & 0xff) << 8) | (fragment[8] & 0xff);
        int fragmentLength = ((fragment[9] & 0xff) << 16) | ((fragment[10] & 0xff) << 8) | (fragment[11] & 0xff);
        if (fragmentOffset != 0 || fragmentLength != totalLength) {
            return null;
        }
        try {
            WireReader body = new WireReader(Arrays.copyOfRange(fragment, Dtls12RecordEngine.FRAGMENT_HEADER_LEN,
                    Dtls12RecordEngine.FRAGMENT_HEADER_LEN + fragmentLength));
            body.u16();
            byte[] random = body.bytes(32);
            body.opaque8();
            byte[] cookie = body.opaque8();
            return new ClientHelloFields(random, cookie);
        } catch (HandshakeFormatException e) {
            return null;
        }
    }

    public static final class ClientHelloFields {
        public final byte[] random;
        public final byte[] cookie;

        ClientHelloFields(byte[] random, byte[] cookie) {
            this.random = random;
            this.cookie = cookie;
        }
    }

}
