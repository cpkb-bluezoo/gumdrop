/*
 * DnsTsigFramingEdgeTest.java
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

package org.bluezoo.gumdrop.dns;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Edge and failure branches of {@link TsigKey}, {@link TsigAlgorithm},
 * {@link DnsTsig} and {@link DnsTcpFraming}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsTsigFramingEdgeTest {

    private static TsigKey key(String name) {
        return TsigKey.fromBase64(name, TsigKey.HMAC_SHA256, "c2VjcmV0");
    }

    private static DnsMessage query() {
        return DnsMessage.createQuery(11, "a.example.com.", DnsType.A);
    }

    // ---- TsigKey ----

    @Test
    public void keyNormalisesNameAndClonesSecret() {
        byte[] secret = new byte[] {1, 2, 3};
        TsigKey k = new TsigKey("  KeY ", TsigKey.HMAC_SHA256, secret);
        assertEquals("key.", k.getName());
        assertEquals(TsigKey.HMAC_SHA256, k.getAlgorithm());
        secret[0] = 9;
        assertEquals(1, k.getSecret()[0]);
        k.getSecret()[1] = 7;
        assertEquals(2, k.getSecret()[1]);
    }

    @Test
    public void keyRejectsNulls() {
        byte[] s = new byte[1];
        try {
            new TsigKey(null, "a", s);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new TsigKey("n", null, s);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new TsigKey("n", "a", null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void keyEqualsAndHashCode() {
        TsigKey a = key("k.");
        TsigKey same = key("K");
        assertEquals(a, a);
        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertFalse(a.equals(null));
        assertFalse(a.equals("k."));
        assertFalse(a.equals(key("other.")));
        assertFalse(a.equals(TsigKey.fromBase64("k.", TsigKey.HMAC_SHA256, "b3RoZXI=")));
    }

    // ---- TsigAlgorithm ----

    @Test
    public void onlyHmacSha256HasAJcaName() {
        assertEquals("HmacSHA256", TsigAlgorithm.toJcaName("hmac-sha256"));
        String[] other = {"hmac-md5", "hmac-sha1", "custom"};
        for (int i = 0; i < other.length; i++) {
            try {
                TsigAlgorithm.toJcaName(other[i]);
                fail(other[i] + " must have no JCA name");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void algorithmWireNames() {
        assertEquals(TsigAlgorithm.WIRE_HMAC_SHA256, TsigAlgorithm.toWireName(" hmac-sha256 "));
        assertEquals(TsigAlgorithm.WIRE_HMAC_SHA256, TsigAlgorithm.toWireName("hmac-sha256."));
        assertEquals("custom.", TsigAlgorithm.toWireName("custom"));
        assertEquals("custom.", TsigAlgorithm.toWireName("custom."));
    }

    @Test(expected = NullPointerException.class)
    public void algorithmNullIdRejected() {
        TsigAlgorithm.toWireName(null);
    }

    @Test
    public void algorithmMatchesKey() {
        TsigKey k = key("k.");
        assertTrue(TsigAlgorithm.matchesKey(k, "HMAC-SHA256"));
        assertTrue(TsigAlgorithm.matchesKey(k, "hmac-sha256."));
        assertFalse(TsigAlgorithm.matchesKey(k, "hmac-sha1."));
        assertFalse(TsigAlgorithm.matchesKey(null, "hmac-sha256."));
        assertFalse(TsigAlgorithm.matchesKey(k, null));
    }

    // ---- DnsTsig ----

    @Test(expected = NullPointerException.class)
    public void signRequiresKey() throws Exception {
        DnsTsig.sign(query(), null, 1000L, 300);
    }

    @Test
    public void signWithDefaultClockVerifies() throws Exception {
        TsigKey k = key("k.");
        DnsMessage signed = DnsTsig.sign(query(), k);
        assertTrue(DnsTsig.verify(signed, k, false));
    }

    @Test
    public void verifyAbsentTsigFollowsOptional() {
        DnsMessage plain = query();
        assertTrue(DnsTsig.verify(plain, key("k."), true));
        assertFalse(DnsTsig.verify(plain, key("k."), false));
    }

    @Test
    public void verifyNullKeyFails() throws Exception {
        DnsMessage signed = DnsTsig.sign(query(), key("k."));
        assertFalse(DnsTsig.verify(signed, null, true));
    }

    @Test
    public void verifyWrongKeyNameFails() throws Exception {
        DnsMessage signed = DnsTsig.sign(query(), key("k."));
        assertFalse(DnsTsig.verify(signed, key("other."), false));
    }

    @Test
    public void verifyWrongSecretFails() throws Exception {
        DnsMessage signed = DnsTsig.sign(query(), key("k."));
        TsigKey other = TsigKey.fromBase64("k.", TsigKey.HMAC_SHA256, "b3RoZXI=");
        assertFalse(DnsTsig.verify(signed, other, false));
    }

    @Test
    public void verifyStaleTimeFails() throws Exception {
        TsigKey k = key("k.");
        DnsMessage signed = DnsTsig.sign(query(), k, 1000L, 60);
        assertFalse(DnsTsig.verify(signed, k, false));
    }

    @Test
    public void verifyGarbledTsigRdataFails() {
        DnsResourceRecord bad = new DnsResourceRecord("k.", DnsType.TSIG,
                DnsClass.ANY, 0, new byte[] {0, 1, 2});
        DnsMessage m = query();
        List<DnsResourceRecord> add = new ArrayList<DnsResourceRecord>();
        add.add(bad);
        DnsMessage withBad = new DnsMessage(m.getId(), m.getFlags(), m.getQuestions(),
                m.getAnswers(), m.getAuthorities(), add);
        assertFalse(DnsTsig.verify(withBad, key("k."), false));
    }

    @Test
    public void signResponseWithoutRequestTsigIsNoOp() throws Exception {
        DnsMessage resp = query().createErrorResponse(DnsMessage.RCODE_NOERROR);
        assertSame(resp, DnsTsig.signResponse(resp, key("k."), query()));
        DnsMessage signedReq = DnsTsig.sign(query(), key("k."));
        assertSame(resp, DnsTsig.signResponse(resp, null, signedReq));
        assertSame(resp, DnsTsig.signResponse(resp, key("k."), query(), 1000L, 300));
        assertSame(resp, DnsTsig.signResponse(resp, null, signedReq, 1000L, 300));
    }

    @Test
    public void signResponseWithDefaultClockVerifies() throws Exception {
        TsigKey k = key("k.");
        DnsMessage req = DnsTsig.sign(query(), k);
        DnsMessage resp = DnsTsig.signResponse(
                req.createErrorResponse(DnsMessage.RCODE_NOERROR), k, req);
        assertTrue(DnsTsig.verifyResponse(resp, k, req));
    }

    @Test
    public void verifyResponseFailureBranches() throws Exception {
        TsigKey k = key("k.");
        DnsMessage req = DnsTsig.sign(query(), k);
        DnsMessage unsigned = req.createErrorResponse(DnsMessage.RCODE_NOERROR);
        assertFalse(DnsTsig.verifyResponse(unsigned, k, req));
        DnsMessage resp = DnsTsig.signResponse(unsigned, k, req);
        assertFalse(DnsTsig.verifyResponse(resp, k, query()));
        assertFalse(DnsTsig.verifyResponse(resp, null, req));
        assertFalse(DnsTsig.verifyResponse(resp, key("other."), req));
        TsigKey wrongSecret = TsigKey.fromBase64("k.", TsigKey.HMAC_SHA256, "b3RoZXI=");
        assertFalse(DnsTsig.verifyResponse(resp, wrongSecret, req));
        DnsMessage stale = DnsTsig.signResponse(unsigned, k, req, 1000L, 60);
        assertFalse(DnsTsig.verifyResponse(stale, k, req));
    }

    @Test
    public void sequenceSigningAndVerificationEdges() throws Exception {
        TsigKey k = key("k.");
        List<DnsMessage> responses = new ArrayList<DnsMessage>();
        responses.add(query().createErrorResponse(DnsMessage.RCODE_NOERROR));
        // unsigned request: sign is a pass-through, verify depends on key
        assertSame(responses, DnsTsig.signResponseSequence(responses, k, query()));
        assertSame(responses, DnsTsig.signResponseSequence(responses, null, query()));
        assertTrue(DnsTsig.verifyResponseSequence(responses, null, query()));
        assertFalse(DnsTsig.verifyResponseSequence(responses, k, query()));

        DnsMessage req = DnsTsig.sign(query(), k);
        assertFalse(DnsTsig.verifyResponseSequence(responses, null, req));
        assertFalse(DnsTsig.verifyResponseSequence(null, k, req));
        assertFalse(DnsTsig.verifyResponseSequence(
                Collections.<DnsMessage>emptyList(), k, req));
        // unsigned member breaks the chain
        assertFalse(DnsTsig.verifyResponseSequence(responses, k, req));
        List<DnsMessage> signed = DnsTsig.signResponseSequence(responses, k, req);
        assertTrue(DnsTsig.verifyResponseSequence(signed, k, req));
    }

    // ---- DnsTcpFraming ----

    @Test
    public void framedRoundTrip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] msg = new byte[] {1, 2, 3, 4};
        DnsTcpFraming.writeFramed(out, msg);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertArrayEquals(msg, DnsTcpFraming.readFramed(in));
        assertNull(DnsTcpFraming.readFramed(in));
    }

    @Test
    public void writeFramedRejectsOversize() throws Exception {
        try {
            DnsTcpFraming.writeFramed(new ByteArrayOutputStream(),
                    new byte[DnsTcpFraming.MAX_DNS_MESSAGE_SIZE + 1]);
            fail();
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void readFramedRejectsTruncatedPrefixLengthAndBody() throws Exception {
        try {
            DnsTcpFraming.readFramed(new ByteArrayInputStream(new byte[] {0}));
            fail();
        } catch (IOException expected) {
            // expected
        }
        try {
            DnsTcpFraming.readFramed(new ByteArrayInputStream(new byte[] {0, 0}));
            fail();
        } catch (IOException expected) {
            // expected
        }
        try {
            DnsTcpFraming.readFramed(new ByteArrayInputStream(new byte[] {0, 5, 1, 2}));
            fail();
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void readFullyHandlesOffset() throws Exception {
        byte[] buf = new byte[5];
        DnsTcpFraming.readFully(new ByteArrayInputStream(new byte[] {9, 8}), buf, 2, 2);
        assertEquals(9, buf[2]);
        assertEquals(8, buf[3]);
    }

    @Test
    public void readAllFramedMessagesEdges() throws Exception {
        assertTrue(DnsTcpFraming.readAllFramedMessages(new byte[0]).isEmpty());
        try {
            DnsTcpFraming.readAllFramedMessages(new byte[] {0});
            fail();
        } catch (IOException expected) {
            // expected
        }
        try {
            DnsTcpFraming.readAllFramedMessages(new byte[] {0, 0});
            fail();
        } catch (IOException expected) {
            // expected
        }
    }
}
