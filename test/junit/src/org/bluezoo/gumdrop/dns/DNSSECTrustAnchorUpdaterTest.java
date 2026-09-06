/*
 * DNSSECTrustAnchorUpdaterTest.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.client.DNSClientTransport;
import org.bluezoo.gumdrop.dns.client.DNSClientTransportHandler;
import org.bluezoo.gumdrop.dns.client.DNSResolver;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for issue #411: {@link DNSSECTrustAnchorUpdater}'s RFC
 * 5011 state machine (AddPending/Valid/Missing/Revoked, hold-down
 * timers, the "never leave zero Valid keys" safeguard) and its
 * persistence across restarts.
 *
 * <p>Most tests drive {@link DNSSECTrustAnchorUpdater#handleDNSKEYResponse}
 * directly with real RSA-signed fixtures (genuine cryptographic
 * verification, not a mocked check) and a controllable fake clock
 * (overriding {@link DNSSECTrustAnchorUpdater#now}) rather than
 * sleeping through real hold-down periods. One end-to-end test drives
 * the whole thing through a real {@link DNSResolver} with a mock
 * transport, to prove {@link DNSSECTrustAnchorUpdater#checkNow}'s
 * wiring (query out, response in) actually works.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSSECTrustAnchorUpdaterTest {

    // Already canonical (no trailing dot) -- handleDNSKEYResponse is
    // called directly by most tests below, and (unlike checkNow, its
    // one production caller) does not canonicalize its zone parameter
    // itself, so tests must pass it pre-canonicalized.
    private static final String ZONE = "example";
    private static final int ALGORITHM = DNSSECAlgorithm.RSASHA256.getNumber();

    // ── Bootstrap ──

    @Test
    public void testBootstrapPromotesStaticallyTrustedKeyImmediately() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        FakeClockUpdater updater = new FakeClockUpdater(anchor);
        KeyPair ksk1 = generateKeyPair();
        DNSResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);

        DNSMessage response = buildResponse(Collections.singletonList(ksk1Key),
                sign(Collections.singletonList(ksk1Key), ksk1, ksk1Key.computeKeyTag()));
        updater.handleDNSKEYResponse(ZONE, response);

        List<DNSSECTrustAnchorUpdater.TrackedKeyInfo> tracked = updater.getTrackedKeys(ZONE);
        assertEquals(1, tracked.size());
        assertEquals(DNSSECTrustAnchorUpdater.KeyState.VALID, tracked.get(0).getState());
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk1Key));
    }

    // ── AddPending -> Valid ──

    @Test
    public void testNewKeyAddedAsPendingThenPromotedAfterHoldDown() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        FakeClockUpdater updater = new FakeClockUpdater(anchor);
        updater.setAddHoldDownMs(1000);
        KeyPair ksk1 = generateKeyPair();
        DNSResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        bootstrap(updater, ksk1, ksk1Key);

        KeyPair ksk2 = generateKeyPair();
        DNSResourceRecord ksk2Key = buildKSK(ksk2, 0);
        List<DNSResourceRecord> rrset = Arrays.asList(ksk1Key, ksk2Key);

        updater.fakeNow = 0;
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset,
                sign(rrset, ksk1, ksk1Key.computeKeyTag())));

        assertKeyState(updater, ksk2Key, DNSSECTrustAnchorUpdater.KeyState.ADD_PENDING);
        assertFalse(anchor.isDNSKEYTrusted(ZONE, ksk2Key));

        // Not yet past the hold-down: still pending.
        updater.fakeNow = 999;
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset,
                sign(rrset, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DNSSECTrustAnchorUpdater.KeyState.ADD_PENDING);

        // Past the hold-down: promoted.
        updater.fakeNow = 1001;
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset,
                sign(rrset, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DNSSECTrustAnchorUpdater.KeyState.VALID);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk2Key));
    }

    // ── Valid -> Missing -> Valid (reappears) ──

    @Test
    public void testMissingKeyReturnsToValidWithoutNewHoldDown() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        FakeClockUpdater updater = new FakeClockUpdater(anchor);
        KeyPair ksk1 = generateKeyPair();
        DNSResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        bootstrap(updater, ksk1, ksk1Key);

        KeyPair ksk2 = generateKeyPair();
        DNSResourceRecord ksk2Key = buildKSK(ksk2, 0);
        promoteViaRfc5011(updater, ksk1, ksk1Key, ksk2Key);

        List<DNSResourceRecord> both = Arrays.asList(ksk1Key, ksk2Key);
        assertKeyState(updater, ksk2Key, DNSSECTrustAnchorUpdater.KeyState.VALID);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk2Key));

        // ksk2 disappears from the RRset.
        List<DNSResourceRecord> onlyKsk1 = Collections.singletonList(ksk1Key);
        updater.handleDNSKEYResponse(ZONE, buildResponse(onlyKsk1,
                sign(onlyKsk1, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DNSSECTrustAnchorUpdater.KeyState.MISSING);
        assertFalse(anchor.isDNSKEYTrusted(ZONE, ksk2Key));

        // ksk2 reappears: back to Valid immediately, no hold-down wait.
        updater.handleDNSKEYResponse(ZONE, buildResponse(both,
                sign(both, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DNSSECTrustAnchorUpdater.KeyState.VALID);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk2Key));
    }

    @Test
    public void testMissingKeyRemovedAfterRemoveHoldDown() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        FakeClockUpdater updater = new FakeClockUpdater(anchor);
        updater.setRemoveHoldDownMs(1000);
        KeyPair ksk1 = generateKeyPair();
        DNSResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        updater.fakeNow = 0;
        bootstrap(updater, ksk1, ksk1Key);

        KeyPair ksk2 = generateKeyPair();
        DNSResourceRecord ksk2Key = buildKSK(ksk2, 0);
        promoteViaRfc5011(updater, ksk1, ksk1Key, ksk2Key);

        List<DNSResourceRecord> onlyKsk1 = Collections.singletonList(ksk1Key);
        updater.handleDNSKEYResponse(ZONE, buildResponse(onlyKsk1,
                sign(onlyKsk1, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DNSSECTrustAnchorUpdater.KeyState.MISSING);

        updater.fakeNow = 1001;
        updater.handleDNSKEYResponse(ZONE, buildResponse(onlyKsk1,
                sign(onlyKsk1, ksk1, ksk1Key.computeKeyTag())));

        // ksk2 was purged entirely once its remove hold-down expired --
        // only ksk1 remains tracked.
        assertEquals(1, updater.getTrackedKeys(ZONE).size());
        assertEquals(DNSSECTrustAnchorUpdater.KeyState.VALID,
                updater.getTrackedKeys(ZONE).get(0).getState());
    }

    // ── Revocation ──

    @Test
    public void testRevokedKeyWithValidSelfSignatureIsRevokedImmediately() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        FakeClockUpdater updater = new FakeClockUpdater(anchor);
        KeyPair ksk1 = generateKeyPair();
        DNSResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        bootstrap(updater, ksk1, ksk1Key);

        KeyPair ksk2 = generateKeyPair();
        DNSResourceRecord ksk2Key = buildKSK(ksk2, 0);
        promoteViaRfc5011(updater, ksk1, ksk1Key, ksk2Key);
        assertKeyState(updater, ksk2Key, DNSSECTrustAnchorUpdater.KeyState.VALID);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk2Key));

        // RFC 5011 section 5.1: revoke bit set (changes the key tag),
        // signed by a trusted key (ksk1) AND self-signed by ksk2 itself.
        DNSResourceRecord ksk2Revoked = buildKSK(ksk2, 0x0080);
        List<DNSResourceRecord> rrsetWithRevocation = Arrays.asList(ksk1Key, ksk2Revoked);
        DNSResourceRecord trustedSig = sign(rrsetWithRevocation, ksk1, ksk1Key.computeKeyTag());
        DNSResourceRecord selfSig = sign(rrsetWithRevocation, ksk2, ksk2Revoked.computeKeyTag());

        List<DNSResourceRecord> answers = new ArrayList<>(rrsetWithRevocation);
        answers.add(trustedSig);
        answers.add(selfSig);
        updater.handleDNSKEYResponse(ZONE, messageFrom(answers));

        assertKeyState(updater, ksk2Key, DNSSECTrustAnchorUpdater.KeyState.REVOKED);
        assertFalse("a revoked key must stop being trusted immediately",
                anchor.isDNSKEYTrusted(ZONE, ksk2Key));
    }

    @Test
    public void testRevocationWithoutValidSelfSignatureIsNotHonored() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        FakeClockUpdater updater = new FakeClockUpdater(anchor);
        KeyPair ksk1 = generateKeyPair();
        KeyPair ksk2 = generateKeyPair();
        DNSResourceRecord ksk1Key = buildKSK(ksk1, 0);
        DNSResourceRecord ksk2Key = buildKSK(ksk2, 0);
        addStaticAnchor(anchor, ksk1Key);
        addStaticAnchor(anchor, ksk2Key);
        updater.addTrustPoint(ZONE);

        List<DNSResourceRecord> both = Arrays.asList(ksk1Key, ksk2Key);
        updater.handleDNSKEYResponse(ZONE, buildResponse(both,
                sign(both, ksk1, ksk1Key.computeKeyTag())));

        // REVOKE bit set, but only signed by the trusted key -- no
        // self-signature by ksk2 itself. Must not be honored as a
        // revocation (RFC 5011 section 5.1 requires both).
        DNSResourceRecord ksk2Revoked = buildKSK(ksk2, 0x0080);
        List<DNSResourceRecord> rrsetWithRevocation = Arrays.asList(ksk1Key, ksk2Revoked);
        DNSResourceRecord trustedSig = sign(rrsetWithRevocation, ksk1, ksk1Key.computeKeyTag());

        List<DNSResourceRecord> answers = new ArrayList<>(rrsetWithRevocation);
        answers.add(trustedSig);
        updater.handleDNSKEYResponse(ZONE, messageFrom(answers));

        for (DNSSECTrustAnchorUpdater.TrackedKeyInfo info : updater.getTrackedKeys(ZONE)) {
            assertNotEquals(DNSSECTrustAnchorUpdater.KeyState.REVOKED, info.getState());
        }
    }

    // ── The "never zero Valid keys" safeguard ──

    @Test
    public void testSelfRevocationOfTheOnlyValidKeyIsRejected() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        FakeClockUpdater updater = new FakeClockUpdater(anchor);
        KeyPair ksk1 = generateKeyPair();
        DNSResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        bootstrap(updater, ksk1, ksk1Key);

        // ksk1 -- the ONLY Valid key -- revokes itself. A single
        // self-signature satisfies both "signed by a trusted key" (it
        // was trusted right up until this) and "self-signed".
        DNSResourceRecord ksk1Revoked = buildKSK(ksk1, 0x0080);
        List<DNSResourceRecord> rrset = Collections.singletonList(ksk1Revoked);
        DNSResourceRecord selfSig = sign(rrset, ksk1, ksk1Revoked.computeKeyTag());

        List<DNSResourceRecord> answers = new ArrayList<>(rrset);
        answers.add(selfSig);
        updater.handleDNSKEYResponse(ZONE, messageFrom(answers));

        // RFC 5011 section 5.1: rejected outright -- ksk1 stays Valid.
        assertKeyState(updater, ksk1Key, DNSSECTrustAnchorUpdater.KeyState.VALID);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk1Key));
    }

    // ── Fail-open ──

    @Test
    public void testNoTrustedSignatureLeavesStateUntouched() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        FakeClockUpdater updater = new FakeClockUpdater(anchor);
        updater.addTrustPoint(ZONE);

        KeyPair untrusted = generateKeyPair();
        DNSResourceRecord untrustedKey = buildKSK(untrusted, 0);
        List<DNSResourceRecord> rrset = Collections.singletonList(untrustedKey);
        // Self-signed by a key nothing trusts yet -- no basis to act on it.
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset,
                sign(rrset, untrusted, untrustedKey.computeKeyTag())));

        assertTrue(updater.getTrackedKeys(ZONE).isEmpty());
    }

    // ── Persistence across restarts (RFC 5011 section 2.3) ──

    @Test
    public void testStateSurvivesRestart() throws Exception {
        File stateFile = File.createTempFile("rfc5011-test", ".state");
        stateFile.deleteOnExit();
        try {
            DNSSECTrustAnchor anchor1 = new DNSSECTrustAnchor();
            FakeClockUpdater updater1 = new FakeClockUpdater(anchor1);
            KeyPair ksk1 = generateKeyPair();
            DNSResourceRecord ksk1Key = buildKSK(ksk1, 0);
            addStaticAnchor(anchor1, ksk1Key);
            updater1.setStateFile(stateFile);
            updater1.addTrustPoint(ZONE);
            bootstrap(updater1, ksk1, ksk1Key);
            assertKeyState(updater1, ksk1Key, DNSSECTrustAnchorUpdater.KeyState.VALID);

            // Fresh process: new trust anchor store, new updater, same file.
            DNSSECTrustAnchor anchor2 = new DNSSECTrustAnchor();
            FakeClockUpdater updater2 = new FakeClockUpdater(anchor2);
            updater2.setStateFile(stateFile);

            assertKeyState(updater2, ksk1Key, DNSSECTrustAnchorUpdater.KeyState.VALID);
            assertTrue("restored Valid keys must be re-promoted into the trust anchor store",
                    anchor2.isDNSKEYTrusted(ZONE, ksk1Key));
        } finally {
            stateFile.delete();
        }
    }

    // ── End-to-end: checkNow's query/response wiring ──

    @Test
    public void testCheckNowQueriesDNSKEYAndAppliesTheResponse() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        KeyPair ksk1 = generateKeyPair();
        DNSResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);

        RecordingTransport transport = new RecordingTransport();
        DNSResolver resolver = new DNSResolver();
        resolver.setTransport(transport);
        resolver.addServer("203.0.113.53");
        resolver.open();

        DNSSECTrustAnchorUpdater updater = new DNSSECTrustAnchorUpdater(resolver, anchor);
        updater.addTrustPoint(ZONE);
        updater.checkNow(ZONE);

        assertNotNull("checkNow should have sent a DNSKEY query", transport.lastSent);
        DNSMessage sent = DNSMessage.parse(transport.lastSent);
        assertEquals(ZONE, sent.getQuestions().get(0).getName());
        assertEquals(DNSType.DNSKEY, sent.getQuestions().get(0).getType());

        List<DNSResourceRecord> rrset = Collections.singletonList(ksk1Key);
        DNSMessage response = buildResponse(rrset, sign(rrset, ksk1, ksk1Key.computeKeyTag()));
        response = withId(response, sent.getId());
        transport.handler.onReceive(response.serialize());

        assertKeyState(updater, ksk1Key, DNSSECTrustAnchorUpdater.KeyState.VALID);
        resolver.close();
    }

    // ── Helpers ──

    private static void bootstrap(FakeClockUpdater updater, KeyPair ksk, DNSResourceRecord kskRecord)
            throws Exception {
        List<DNSResourceRecord> rrset = Collections.singletonList(kskRecord);
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset,
                sign(rrset, ksk, kskRecord.computeKeyTag())));
    }

    /**
     * Promotes {@code newKey} from unseen to Valid via the genuine RFC
     * 5011 AddPending path (not a second static anchor, which would
     * leave it permanently trusted regardless of what the state
     * machine later does to it -- exactly the backdoor these tests
     * must avoid to meaningfully exercise Missing/Revoked behaviour).
     * Uses a zero Add hold-down so two rounds are enough: one to
     * observe the key as new, one to notice the (zero-length)
     * hold-down has already elapsed.
     */
    private static void promoteViaRfc5011(FakeClockUpdater updater, KeyPair trustedKey,
            DNSResourceRecord trustedKeyRecord, DNSResourceRecord newKey) throws Exception {
        long savedHoldDown = updater.getAddHoldDownMs();
        updater.setAddHoldDownMs(0);
        List<DNSResourceRecord> rrset = Arrays.asList(trustedKeyRecord, newKey);
        DNSResourceRecord rrsig = sign(rrset, trustedKey, trustedKeyRecord.computeKeyTag());
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset, rrsig));
        assertKeyState(updater, newKey, DNSSECTrustAnchorUpdater.KeyState.ADD_PENDING);
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset, rrsig));
        updater.setAddHoldDownMs(savedHoldDown);
    }

    private static void assertKeyState(DNSSECTrustAnchorUpdater updater, DNSResourceRecord key,
            DNSSECTrustAnchorUpdater.KeyState expected) {
        byte[] publicKey = key.getDNSKEYPublicKey();
        for (DNSSECTrustAnchorUpdater.TrackedKeyInfo info : updater.getTrackedKeys(ZONE)) {
            // Matched by public key material, not tag: a key's tag
            // changes the moment its REVOKE bit is set (RFC 5011
            // section 5.1), so this is the only way to reliably find
            // "the same key" across that flip -- the same identity
            // DNSSECTrustAnchorUpdater itself uses internally.
            if (info.getAlgorithm() == key.getDNSKEYAlgorithm()
                    && Arrays.equals(info.getPublicKey(), publicKey)) {
                assertEquals(expected, info.getState());
                return;
            }
        }
        fail("key not tracked: tag=" + key.computeKeyTag());
    }

    private static void addStaticAnchor(DNSSECTrustAnchor anchor, DNSResourceRecord dnskey) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(DNSMessage.encodeName(DNSSECValidator.canonicalizeName(dnskey.getName())));
        md.update(dnskey.getRData());
        byte[] digest = md.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02X", b));
        }
        anchor.addAnchor(ZONE, dnskey.computeKeyTag(), ALGORITHM, 2, hex.toString());
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);
        return kpg.generateKeyPair();
    }

    private static DNSResourceRecord buildKSK(KeyPair kp, int extraFlags) {
        return DNSResourceRecord.dnskey(ZONE, 3600, 0x0101 | extraFlags,
                ALGORITHM, rsaWireFormat(kp.getPublic()));
    }

    private static byte[] rsaWireFormat(PublicKey pub) {
        RSAPublicKey rsa = (RSAPublicKey) pub;
        byte[] exponent = stripLeadingZero(rsa.getPublicExponent().toByteArray());
        byte[] modulus = stripLeadingZero(rsa.getModulus().toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (exponent.length < 256) {
            out.write(exponent.length);
        } else {
            out.write(0);
            out.write((exponent.length >> 8) & 0xFF);
            out.write(exponent.length & 0xFF);
        }
        out.write(exponent, 0, exponent.length);
        out.write(modulus, 0, modulus.length);
        return out.toByteArray();
    }

    private static byte[] stripLeadingZero(byte[] b) {
        if (b.length > 1 && b[0] == 0) {
            return Arrays.copyOfRange(b, 1, b.length);
        }
        return b;
    }

    private static DNSResourceRecord sign(List<DNSResourceRecord> rrset, KeyPair signer, int keyTag)
            throws Exception {
        long now = System.currentTimeMillis() / 1000;
        DNSResourceRecord template = DNSResourceRecord.rrsig(ZONE, 3600, DNSType.DNSKEY,
                ALGORITHM, 1, 3600, now + 3600, now - 3600, keyTag, ZONE, new byte[0]);
        byte[] header = template.getRRSIGHeaderBytes();
        List<byte[]> canonical = DNSSECValidator.buildCanonicalRRset(rrset, template);
        ByteArrayOutputStream toSign = new ByteArrayOutputStream();
        toSign.write(header);
        for (byte[] rec : canonical) {
            toSign.write(rec);
        }
        Signature sig = Signature.getInstance(DNSSECAlgorithm.RSASHA256.getSignatureAlgorithm());
        sig.initSign(signer.getPrivate());
        sig.update(toSign.toByteArray());
        byte[] signature = sig.sign();
        return DNSResourceRecord.rrsig(ZONE, 3600, DNSType.DNSKEY,
                ALGORITHM, 1, 3600, now + 3600, now - 3600, keyTag, ZONE, signature);
    }

    private static DNSMessage buildResponse(List<DNSResourceRecord> dnskeys, DNSResourceRecord rrsig) {
        List<DNSResourceRecord> answers = new ArrayList<>(dnskeys);
        answers.add(rrsig);
        return messageFrom(answers);
    }

    private static DNSMessage messageFrom(List<DNSResourceRecord> answers) {
        List<DNSQuestion> questions = Collections.singletonList(
                new DNSQuestion(ZONE, DNSType.DNSKEY, DNSClass.IN));
        int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RD | DNSMessage.FLAG_RA;
        return new DNSMessage(1, flags, questions, answers,
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());
    }

    private static DNSMessage withId(DNSMessage message, int id) {
        return new DNSMessage(id, message.getFlags(), message.getQuestions(), message.getAnswers(),
                message.getAuthorities(), message.getAdditionals());
    }

    /** Overrides {@link DNSSECTrustAnchorUpdater#now} for deterministic hold-down tests. */
    private static class FakeClockUpdater extends DNSSECTrustAnchorUpdater {
        long fakeNow;

        FakeClockUpdater(DNSSECTrustAnchor anchor) {
            super(dummyResolver(), anchor);
        }

        @Override
        long now() {
            return fakeNow;
        }

        private static DNSResolver dummyResolver() {
            return new DNSResolver();
        }
    }

    private static class RecordingTransport implements DNSClientTransport {
        DNSClientTransportHandler handler;
        ByteBuffer lastSent;

        @Override
        public void open(InetAddress server, int port, SelectorLoop loop,
                         DNSClientTransportHandler handler) {
            this.handler = handler;
        }

        @Override
        public void send(ByteBuffer data) {
            lastSent = ByteBuffer.allocate(data.remaining());
            lastSent.put(data.duplicate());
            lastSent.flip();
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            return new TimerHandle() {
                @Override public void cancel() { }
                @Override public boolean isCancelled() { return false; }
            };
        }

        @Override
        public void close() {
        }
    }
}
