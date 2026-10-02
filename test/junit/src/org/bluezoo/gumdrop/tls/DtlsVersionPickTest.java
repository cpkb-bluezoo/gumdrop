/*
 * DtlsVersionPickTest.java
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

import java.util.Collections;
import java.util.LinkedHashMap;

import org.bluezoo.gumdrop.crypto.KeyExchange;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.crypto.SignatureScheme;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link DtlsVersionPick} and {@link DtlsVersion}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DtlsVersionPickTest {

    private static byte[] clientHelloBody() throws Exception {
        HandshakeConfig defaults = new HandshakeConfig(HandshakeRole.CLIENT);
        HandshakeMessages.ClientHelloParams params = new HandshakeMessages.ClientHelloParams();
        params.random = new byte[32];
        params.cipherSuites = defaults.getCipherSuites();
        params.groups = defaults.getNamedGroups();
        params.keyShares = new LinkedHashMap<NamedGroup, byte[]>();
        NamedGroup shareGroup = params.groups.get(0);
        KeyExchange kx = KeyExchange.generate(shareGroup);
        params.keyShares.put(shareGroup, kx.getShareBytes());
        params.signatureAlgorithms = Collections.singletonList(SignatureScheme.ECDSA_SECP256R1_SHA256);
        params.applicationProtocols = Collections.emptyList();
        params.offerTls12Fallback = true;
        byte[] ch = HandshakeMessages.buildClientHelloWithBinder(params, null);
        return HandshakeMessages.extractClientHelloContent(ch);
    }

    /** One DTLS plaintext record carrying a DTLS handshake fragment. */
    private static byte[] record(int contentType, int major, int minor, byte[] payload) {
        byte[] r = new byte[13 + payload.length];
        r[0] = (byte) contentType;
        r[1] = (byte) major;
        r[2] = (byte) minor;
        r[11] = (byte) (payload.length >> 8);
        r[12] = (byte) payload.length;
        System.arraycopy(payload, 0, r, 13, payload.length);
        return r;
    }

    private static byte[] fragment(int type, int totalLen, int off, byte[] data, int from, int len) {
        byte[] f = new byte[12 + len];
        f[0] = (byte) type;
        f[1] = (byte) (totalLen >> 16);
        f[2] = (byte) (totalLen >> 8);
        f[3] = (byte) totalLen;
        f[6] = (byte) (off >> 16);
        f[7] = (byte) (off >> 8);
        f[8] = (byte) off;
        f[9] = (byte) (len >> 16);
        f[10] = (byte) (len >> 8);
        f[11] = (byte) len;
        System.arraycopy(data, from, f, 12, len);
        return f;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Test
    public void findsClientHelloInSingleRecord() throws Exception {
        byte[] body = clientHelloBody();
        byte[] rec = record(22, 0xfe, 0xfd, fragment(1, body.length, 0, body, 0, body.length));
        assertEquals(TlsVersionPick.Picked.V13, DtlsVersionPick.findClientHelloInDtls(rec));
    }

    @Test
    public void findsClientHelloAcrossTwoFragments() throws Exception {
        byte[] body = clientHelloBody();
        int half = body.length / 2;
        byte[] r1 = record(22, 0xfe, 0xfd, fragment(1, body.length, 0, body, 0, half));
        byte[] r2 = record(22, 0xfe, 0xfd,
                fragment(1, body.length, half, body, half, body.length - half));
        byte[] both = concat(r1, r2);
        assertEquals(TlsVersionPick.Picked.V13, DtlsVersionPick.findClientHelloInDtls(both));
        assertNull(DtlsVersionPick.findClientHelloInDtls(r1));
    }

    @Test
    public void everyTruncationReturnsNullOrThrowsNeverCrashes() throws Exception {
        byte[] body = clientHelloBody();
        byte[] rec = record(22, 0xfe, 0xfd, fragment(1, body.length, 0, body, 0, body.length));
        for (int n = 0; n < rec.length; n++) {
            byte[] cut = new byte[n];
            System.arraycopy(rec, 0, cut, 0, n);
            assertNull("prefix " + n, DtlsVersionPick.findClientHelloInDtls(cut));
        }
    }

    @Test
    public void skipsNonHandshakeAndOtherHandshakeTypes() throws Exception {
        byte[] body = clientHelloBody();
        byte[] alert = record(21, 0xfe, 0xfd, new byte[] { 1, 0 });
        byte[] other = record(22, 0xfe, 0xfd, fragment(14, 0, 0, new byte[0], 0, 0));
        byte[] ch = record(22, 0xfe, 0xfd, fragment(1, body.length, 0, body, 0, body.length));
        ch[13 + 5] = 1;
        byte[] all = concat(concat(alert, other), ch);
        assertEquals(TlsVersionPick.Picked.V13, DtlsVersionPick.findClientHelloInDtls(all));
        assertNull(DtlsVersionPick.findClientHelloInDtls(concat(alert, other)));
        assertNull(DtlsVersionPick.findClientHelloInDtls(new byte[0]));
    }

    @Test
    public void nonDtlsRecordIsRejected() {
        byte[] rec = record(22, 3, 3, new byte[4]);
        try {
            DtlsVersionPick.findClientHelloInDtls(rec);
            fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }

    @Test
    public void serverHelloWithoutSupportedVersionsMeansV12() throws Exception {
        // legacy_version fefd, random, session id 0, suite c02f, compression 0, no extensions
        byte[] sh = new byte[2 + 32 + 1 + 2 + 1];
        sh[0] = (byte) 0xfe;
        sh[1] = (byte) 0xfd;
        sh[35] = 0;
        sh[36] = (byte) 0xc0;
        sh[37] = 0x2f;
        byte[] rec = record(22, 0xfe, 0xfd, fragment(2, sh.length, 0, sh, 0, sh.length));
        assertEquals(TlsVersionPick.Picked.V12, DtlsVersionPick.findServerHelloInDtls(rec));
        assertNull(DtlsVersionPick.findClientHelloInDtls(rec));
    }

    @Test
    public void dtlsVersionPolicyMatrix() {
        assertTrue(DtlsVersion.NEGOTIATE.allowsServerPick(TlsVersionPick.Picked.V12));
        assertTrue(DtlsVersion.NEGOTIATE.allowsClientPick(TlsVersionPick.Picked.V13));
        assertTrue(DtlsVersion.DTLS_1_3.allowsServerPick(TlsVersionPick.Picked.V13));
        assertFalse(DtlsVersion.DTLS_1_3.allowsServerPick(TlsVersionPick.Picked.V12));
        assertTrue(DtlsVersion.DTLS_1_2.allowsClientPick(TlsVersionPick.Picked.V12));
        assertFalse(DtlsVersion.DTLS_1_2.allowsClientPick(TlsVersionPick.Picked.V13));
        assertEquals(3, DtlsVersion.values().length);
        assertEquals(DtlsVersion.DTLS_1_2, DtlsVersion.valueOf("DTLS_1_2"));
    }
}
