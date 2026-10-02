/*
 * TlsVersionPickWireTest.java
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

import java.io.ByteArrayOutputStream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Wire-level tests for {@link TlsVersionPick}: ClientHello / ServerHello
 * version selection, record scanning across partial and coalesced input.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsVersionPickWireTest {

    private static void u16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xff);
        o.write(v & 0xff);
    }

    /** A minimal ClientHello body; {@code versions} null means no supported_versions extension. */
    private static byte[] clientHelloBody(int legacy, int[] versions) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, legacy);
        for (int i = 0; i < 32; i++) {
            o.write(i);
        }
        o.write(0);
        u16(o, 2);
        u16(o, 0x1301);
        o.write(1);
        o.write(0);
        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        if (versions != null) {
            u16(ext, 0x002b);
            u16(ext, 1 + versions.length * 2);
            ext.write(versions.length * 2);
            for (int i = 0; i < versions.length; i++) {
                u16(ext, versions[i]);
            }
        }
        u16(o, ext.size());
        byte[] e = ext.toByteArray();
        o.write(e, 0, e.length);
        return o.toByteArray();
    }

    private static byte[] serverHelloBody(int[] version, int sidLen) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, 0x0303);
        for (int i = 0; i < 32; i++) {
            o.write(1);
        }
        o.write(sidLen);
        for (int i = 0; i < sidLen; i++) {
            o.write(2);
        }
        u16(o, 0x1301);
        o.write(0);
        if (version == null) {
            return o.toByteArray();
        }
        u16(o, 6);
        u16(o, 0x002b);
        u16(o, 2);
        u16(o, version[0]);
        return o.toByteArray();
    }

    private static byte[] record(int type, byte[] payload) {
        byte[] r = new byte[5 + payload.length];
        r[0] = (byte) type;
        r[1] = 3;
        r[2] = 3;
        r[3] = (byte) (payload.length >> 8);
        r[4] = (byte) payload.length;
        System.arraycopy(payload, 0, r, 5, payload.length);
        return r;
    }

    private static byte[] handshake(int type, byte[] body) {
        byte[] h = new byte[4 + body.length];
        h[0] = (byte) type;
        h[1] = (byte) (body.length >> 16);
        h[2] = (byte) (body.length >> 8);
        h[3] = (byte) body.length;
        System.arraycopy(body, 0, h, 4, body.length);
        return h;
    }

    @Test
    public void serverPicksV13WhenOffered() throws Exception {
        byte[] body = clientHelloBody(0x0303, new int[] {0x0304, 0x0303});
        assertEquals(TlsVersionPick.Picked.V13, TlsVersionPick.pickServerVersion(body));
    }

    @Test
    public void serverPicksV13ForDtls13() throws Exception {
        byte[] body = clientHelloBody(0xfefd, new int[] {0xfefc});
        assertEquals(TlsVersionPick.Picked.V13, TlsVersionPick.pickServerVersion(body));
    }

    @Test
    public void serverPicksV12WhenOnlyV12Offered() throws Exception {
        byte[] body = clientHelloBody(0x0303, new int[] {0x0303});
        assertEquals(TlsVersionPick.Picked.V12, TlsVersionPick.pickServerVersion(body));
        byte[] dtls = clientHelloBody(0xfefd, new int[] {0xfefd});
        assertEquals(TlsVersionPick.Picked.V12, TlsVersionPick.pickServerVersion(dtls));
    }

    @Test
    public void serverFallsBackToLegacyVersionWhenOnlyUnknownVersionsOffered() throws Exception {
        byte[] body = clientHelloBody(0x0303, new int[] {0x0301});
        assertEquals(TlsVersionPick.Picked.V12, TlsVersionPick.pickServerVersion(body));
    }

    @Test
    public void serverRejectsOldLegacyVersionWithUnknownExtension() throws Exception {
        byte[] body = clientHelloBody(0x0301, new int[] {0x0301});
        try {
            TlsVersionPick.pickServerVersion(body);
            fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            // expected
        }
    }

    @Test
    public void serverUsesLegacyVersionWithoutExtension() throws Exception {
        assertEquals(TlsVersionPick.Picked.V12,
                TlsVersionPick.pickServerVersion(clientHelloBody(0x0303, null)));
        assertEquals(TlsVersionPick.Picked.V12,
                TlsVersionPick.pickServerVersion(clientHelloBody(0xfefd, null)));
        try {
            TlsVersionPick.pickServerVersion(clientHelloBody(0x0302, null));
            fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            // expected
        }
    }

    @Test
    public void serverRejectsTruncatedClientHello() throws Exception {
        try {
            TlsVersionPick.pickServerVersion(new byte[] {3});
            fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            // expected
        }
        byte[] body = clientHelloBody(0x0303, new int[] {0x0304});
        byte[] cut = new byte[20];
        System.arraycopy(body, 0, cut, 0, 20);
        try {
            TlsVersionPick.pickServerVersion(cut);
            fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            // expected
        }
    }

    @Test
    public void clientPicksFromServerHello() throws Exception {
        assertEquals(TlsVersionPick.Picked.V13,
                TlsVersionPick.pickClientVersion(serverHelloBody(new int[] {0x0304}, 0)));
        assertEquals(TlsVersionPick.Picked.V13,
                TlsVersionPick.pickClientVersion(serverHelloBody(new int[] {0xfefc}, 32)));
        assertEquals(TlsVersionPick.Picked.V12,
                TlsVersionPick.pickClientVersion(serverHelloBody(new int[] {0x0303}, 0)));
        assertEquals(TlsVersionPick.Picked.V12,
                TlsVersionPick.pickClientVersion(serverHelloBody(new int[] {0xfefd}, 0)));
        assertEquals(TlsVersionPick.Picked.V12,
                TlsVersionPick.pickClientVersion(serverHelloBody(null, 0)));
        assertEquals(TlsVersionPick.Picked.V12,
                TlsVersionPick.pickClientVersion(new byte[10]));
    }

    @Test
    public void clientRejectsUnknownServerHelloVersion() throws Exception {
        try {
            TlsVersionPick.pickClientVersion(serverHelloBody(new int[] {0x0301}, 0));
            fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            // expected
        }
    }

    @Test
    public void clientIgnoresMalformedServerHelloExtensions() throws Exception {
        byte[] body = serverHelloBody(new int[] {0x0304}, 0);
        byte[] cut = new byte[body.length - 1];
        System.arraycopy(body, 0, cut, 0, cut.length);
        assertEquals(TlsVersionPick.Picked.V12, TlsVersionPick.pickClientVersion(cut));
        byte[] cut2 = new byte[body.length - 6];
        System.arraycopy(body, 0, cut2, 0, cut2.length);
        assertEquals(TlsVersionPick.Picked.V12, TlsVersionPick.pickClientVersion(cut2));
        // extension that claims more bytes than the block holds
        byte[] bad = serverHelloBody(new int[] {0x0304}, 0);
        bad[bad.length - 3] = 9;
        assertEquals(TlsVersionPick.Picked.V12, TlsVersionPick.pickClientVersion(bad));
        // some other extension first, then the version
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] head = serverHelloBody(null, 0);
        o.write(head, 0, head.length);
        u16(o, 10);
        u16(o, 0x0010);
        u16(o, 0);
        u16(o, 0x002b);
        u16(o, 2);
        u16(o, 0x0304);
        assertEquals(TlsVersionPick.Picked.V13, TlsVersionPick.pickClientVersion(o.toByteArray()));
    }

    @Test
    public void findClientHelloNeedsCompleteRecord() throws Exception {
        byte[] hs = handshake(1, clientHelloBody(0x0303, new int[] {0x0304}));
        byte[] rec = record(22, hs);
        for (int i = 0; i < rec.length; i++) {
            byte[] part = new byte[i];
            System.arraycopy(rec, 0, part, 0, i);
            assertNull("prefix " + i, TlsVersionPick.findClientHelloInRecords(part));
        }
        assertEquals(TlsVersionPick.Picked.V13, TlsVersionPick.findClientHelloInRecords(rec));
    }

    @Test
    public void findClientHelloSkipsOtherRecordsAndMessages() throws Exception {
        byte[] other = record(20, new byte[] {1});
        byte[] hsOther = handshake(4, new byte[3]);
        byte[] hsHello = handshake(1, clientHelloBody(0x0303, new int[] {0x0303}));
        byte[] both = new byte[hsOther.length + hsHello.length];
        System.arraycopy(hsOther, 0, both, 0, hsOther.length);
        System.arraycopy(hsHello, 0, both, hsOther.length, hsHello.length);
        byte[] rec = record(22, both);
        byte[] all = new byte[other.length + rec.length];
        System.arraycopy(other, 0, all, 0, other.length);
        System.arraycopy(rec, 0, all, other.length, rec.length);
        assertEquals(TlsVersionPick.Picked.V12, TlsVersionPick.findClientHelloInRecords(all));
        assertNull(TlsVersionPick.findClientHelloInRecords(other));
        assertNull(TlsVersionPick.findClientHelloInRecords(record(22, hsOther)));
    }

    @Test
    public void findClientHelloWithHandshakeSpanningRecordIsIncomplete() throws Exception {
        byte[] hs = handshake(1, clientHelloBody(0x0303, new int[] {0x0304}));
        byte[] half = new byte[hs.length - 5];
        System.arraycopy(hs, 0, half, 0, half.length);
        assertNull(TlsVersionPick.findClientHelloInRecords(record(22, half)));
    }

    @Test
    public void findServerHelloVariants() throws Exception {
        byte[] hs = handshake(2, serverHelloBody(new int[] {0x0304}, 0));
        byte[] rec = record(22, hs);
        assertEquals(TlsVersionPick.Picked.V13, TlsVersionPick.findServerHelloInRecords(rec));
        for (int i = 0; i < rec.length; i++) {
            byte[] part = new byte[i];
            System.arraycopy(rec, 0, part, 0, i);
            assertNull("prefix " + i, TlsVersionPick.findServerHelloInRecords(part));
        }
        byte[] ccs = record(20, new byte[] {1});
        assertNull(TlsVersionPick.findServerHelloInRecords(ccs));
        byte[] cert = handshake(11, new byte[2]);
        byte[] two = new byte[cert.length + hs.length];
        System.arraycopy(cert, 0, two, 0, cert.length);
        System.arraycopy(hs, 0, two, cert.length, hs.length);
        assertEquals(TlsVersionPick.Picked.V13, TlsVersionPick.findServerHelloInRecords(record(22, two)));
        byte[] hs12 = handshake(2, serverHelloBody(null, 8));
        assertEquals(TlsVersionPick.Picked.V12, TlsVersionPick.findServerHelloInRecords(record(22, hs12)));
        byte[] half = new byte[hs.length - 2];
        System.arraycopy(hs, 0, half, 0, half.length);
        assertNull(TlsVersionPick.findServerHelloInRecords(record(22, half)));
    }

    @Test
    public void tls12ClientHelloHelper() throws Exception {
        Tls12HandshakeMessages.ClientHello ch = new Tls12HandshakeMessages.ClientHello();
        assertEquals(TlsVersionPick.Picked.V12, TlsVersionPick.pickServerVersionFromTls12ClientHello(ch));
        ch.supportedVersions = new java.util.ArrayList<Integer>();
        ch.supportedVersions.add(0x0303);
        assertEquals(TlsVersionPick.Picked.V12, TlsVersionPick.pickServerVersionFromTls12ClientHello(ch));
        ch.supportedVersions.add(0x0304);
        assertEquals(TlsVersionPick.Picked.V13, TlsVersionPick.pickServerVersionFromTls12ClientHello(ch));
    }
}
