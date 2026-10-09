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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.client.DnsClientTransport;
import org.bluezoo.gumdrop.dns.client.DnsClientTransportHandler;
import org.bluezoo.gumdrop.dns.client.DnsResolver;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryTemp;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for issue #411: {@link DnssecTrustAnchorUpdater}'s RFC
 * 5011 state machine (AddPending/Valid/Missing/Revoked, hold-down
 * timers, the "never leave zero Valid keys" safeguard) and its
 * persistence across restarts.
 *
 * <p>Most tests drive {@link DnssecTrustAnchorUpdater#handleDNSKEYResponse}
 * directly with real RSA-signed fixtures (genuine cryptographic
 * verification, not a mocked check) and a controllable mock clock
 * (overriding {@link DnssecTrustAnchorUpdater#now}) rather than
 * sleeping through real hold-down periods. One end-to-end test drives
 * the whole thing through a real {@link DnsResolver} with a mock
 * transport, to prove {@link DnssecTrustAnchorUpdater#checkNow}'s
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
    private static final int ALGORITHM = DnssecAlgorithm.RSASHA256.getNumber();

    // ── Bootstrap ──

    @Test
    public void testBootstrapPromotesStaticallyTrustedKeyImmediately() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        KeyPair ksk1 = generateKeyPair();
        DnsResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);

        DnsMessage response = buildResponse(Collections.singletonList(ksk1Key),
                sign(Collections.singletonList(ksk1Key), ksk1, ksk1Key.computeKeyTag()));
        updater.handleDNSKEYResponse(ZONE, response);

        List<DnssecTrustAnchorUpdater.TrackedKeyInfo> tracked = updater.getTrackedKeys(ZONE);
        assertEquals(1, tracked.size());
        assertEquals(DnssecTrustAnchorUpdater.KeyState.VALID, tracked.get(0).getState());
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk1Key));
    }

    // ── AddPending -> Valid ──

    @Test
    public void testNewKeyAddedAsPendingThenPromotedAfterHoldDown() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        updater.addHoldDownMs(1000);
        KeyPair ksk1 = generateKeyPair();
        DnsResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        bootstrap(updater, ksk1, ksk1Key);

        KeyPair ksk2 = generateKeyPair();
        DnsResourceRecord ksk2Key = buildKSK(ksk2, 0);
        List<DnsResourceRecord> rrset = Arrays.asList(ksk1Key, ksk2Key);

        updater.fakeNow = 0;
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset,
                sign(rrset, ksk1, ksk1Key.computeKeyTag())));

        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.ADD_PENDING);
        assertFalse(anchor.isDNSKEYTrusted(ZONE, ksk2Key));

        // Not yet past the hold-down: still pending.
        updater.fakeNow = 999;
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset,
                sign(rrset, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.ADD_PENDING);

        // Past the hold-down: promoted.
        updater.fakeNow = 1001;
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset,
                sign(rrset, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.VALID);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk2Key));
    }

    // ── Valid -> Missing -> Valid (reappears) ──

    @Test
    public void testMissingKeyReturnsToValidWithoutNewHoldDown() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        KeyPair ksk1 = generateKeyPair();
        DnsResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        bootstrap(updater, ksk1, ksk1Key);

        KeyPair ksk2 = generateKeyPair();
        DnsResourceRecord ksk2Key = buildKSK(ksk2, 0);
        promoteViaRfc5011(updater, ksk1, ksk1Key, ksk2Key);

        List<DnsResourceRecord> both = Arrays.asList(ksk1Key, ksk2Key);
        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.VALID);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk2Key));

        // ksk2 disappears from the RRset.
        List<DnsResourceRecord> onlyKsk1 = Collections.singletonList(ksk1Key);
        updater.handleDNSKEYResponse(ZONE, buildResponse(onlyKsk1,
                sign(onlyKsk1, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.MISSING);
        assertFalse(anchor.isDNSKEYTrusted(ZONE, ksk2Key));

        // ksk2 reappears: back to Valid immediately, no hold-down wait.
        updater.handleDNSKEYResponse(ZONE, buildResponse(both,
                sign(both, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.VALID);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk2Key));
    }

    @Test
    public void testMissingKeyRemovedAfterRemoveHoldDown() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        updater.removeHoldDownMs(1000);
        KeyPair ksk1 = generateKeyPair();
        DnsResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        updater.fakeNow = 0;
        bootstrap(updater, ksk1, ksk1Key);

        KeyPair ksk2 = generateKeyPair();
        DnsResourceRecord ksk2Key = buildKSK(ksk2, 0);
        promoteViaRfc5011(updater, ksk1, ksk1Key, ksk2Key);

        List<DnsResourceRecord> onlyKsk1 = Collections.singletonList(ksk1Key);
        updater.handleDNSKEYResponse(ZONE, buildResponse(onlyKsk1,
                sign(onlyKsk1, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.MISSING);

        updater.fakeNow = 1001;
        updater.handleDNSKEYResponse(ZONE, buildResponse(onlyKsk1,
                sign(onlyKsk1, ksk1, ksk1Key.computeKeyTag())));

        // ksk2 was purged entirely once its remove hold-down expired --
        // only ksk1 remains tracked.
        assertEquals(1, updater.getTrackedKeys(ZONE).size());
        assertEquals(DnssecTrustAnchorUpdater.KeyState.VALID,
                updater.getTrackedKeys(ZONE).get(0).getState());
    }

    // ── Revocation ──

    @Test
    public void testRevokedKeyWithValidSelfSignatureIsRevokedImmediately() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        KeyPair ksk1 = generateKeyPair();
        DnsResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        bootstrap(updater, ksk1, ksk1Key);

        KeyPair ksk2 = generateKeyPair();
        DnsResourceRecord ksk2Key = buildKSK(ksk2, 0);
        promoteViaRfc5011(updater, ksk1, ksk1Key, ksk2Key);
        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.VALID);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk2Key));

        // RFC 5011 section 5.1: revoke bit set (changes the key tag),
        // signed by a trusted key (ksk1) AND self-signed by ksk2 itself.
        DnsResourceRecord ksk2Revoked = buildKSK(ksk2, 0x0080);
        List<DnsResourceRecord> rrsetWithRevocation = Arrays.asList(ksk1Key, ksk2Revoked);
        DnsResourceRecord trustedSig = sign(rrsetWithRevocation, ksk1, ksk1Key.computeKeyTag());
        DnsResourceRecord selfSig = sign(rrsetWithRevocation, ksk2, ksk2Revoked.computeKeyTag());

        List<DnsResourceRecord> answers = new ArrayList<>(rrsetWithRevocation);
        answers.add(trustedSig);
        answers.add(selfSig);
        updater.handleDNSKEYResponse(ZONE, messageFrom(answers));

        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.REVOKED);
        assertFalse("a revoked key must stop being trusted immediately",
                anchor.isDNSKEYTrusted(ZONE, ksk2Key));
    }

    @Test
    public void testRevocationWithoutValidSelfSignatureIsNotHonored() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        KeyPair ksk1 = generateKeyPair();
        KeyPair ksk2 = generateKeyPair();
        DnsResourceRecord ksk1Key = buildKSK(ksk1, 0);
        DnsResourceRecord ksk2Key = buildKSK(ksk2, 0);
        addStaticAnchor(anchor, ksk1Key);
        addStaticAnchor(anchor, ksk2Key);
        updater.addTrustPoint(ZONE);

        List<DnsResourceRecord> both = Arrays.asList(ksk1Key, ksk2Key);
        updater.handleDNSKEYResponse(ZONE, buildResponse(both,
                sign(both, ksk1, ksk1Key.computeKeyTag())));

        // REVOKE bit set, but only signed by the trusted key -- no
        // self-signature by ksk2 itself. Must not be honored as a
        // revocation (RFC 5011 section 5.1 requires both).
        DnsResourceRecord ksk2Revoked = buildKSK(ksk2, 0x0080);
        List<DnsResourceRecord> rrsetWithRevocation = Arrays.asList(ksk1Key, ksk2Revoked);
        DnsResourceRecord trustedSig = sign(rrsetWithRevocation, ksk1, ksk1Key.computeKeyTag());

        List<DnsResourceRecord> answers = new ArrayList<>(rrsetWithRevocation);
        answers.add(trustedSig);
        updater.handleDNSKEYResponse(ZONE, messageFrom(answers));

        for (DnssecTrustAnchorUpdater.TrackedKeyInfo info : updater.getTrackedKeys(ZONE)) {
            assertNotEquals(DnssecTrustAnchorUpdater.KeyState.REVOKED, info.getState());
        }
    }

    // ── The "never zero Valid keys" safeguard ──

    @Test
    public void testSelfRevocationOfTheOnlyValidKeyIsRejected() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        KeyPair ksk1 = generateKeyPair();
        DnsResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        bootstrap(updater, ksk1, ksk1Key);

        // ksk1 -- the ONLY Valid key -- revokes itself. A single
        // self-signature satisfies both "signed by a trusted key" (it
        // was trusted right up until this) and "self-signed".
        DnsResourceRecord ksk1Revoked = buildKSK(ksk1, 0x0080);
        List<DnsResourceRecord> rrset = Collections.singletonList(ksk1Revoked);
        DnsResourceRecord selfSig = sign(rrset, ksk1, ksk1Revoked.computeKeyTag());

        List<DnsResourceRecord> answers = new ArrayList<>(rrset);
        answers.add(selfSig);
        updater.handleDNSKEYResponse(ZONE, messageFrom(answers));

        // RFC 5011 section 5.1: rejected outright -- ksk1 stays Valid.
        assertKeyState(updater, ksk1Key, DnssecTrustAnchorUpdater.KeyState.VALID);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, ksk1Key));
    }

    // ── Fail-open ──

    @Test
    public void testNoTrustedSignatureLeavesStateUntouched() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        updater.addTrustPoint(ZONE);

        KeyPair untrusted = generateKeyPair();
        DnsResourceRecord untrustedKey = buildKSK(untrusted, 0);
        List<DnsResourceRecord> rrset = Collections.singletonList(untrustedKey);
        // Self-signed by a key nothing trusts yet -- no basis to act on it.
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset,
                sign(rrset, untrusted, untrustedKey.computeKeyTag())));

        assertTrue(updater.getTrackedKeys(ZONE).isEmpty());
    }

    // ── Persistence across restarts (RFC 5011 section 2.3) ──

    @Test
    public void testStateSurvivesRestart() throws Exception {
        Path stateFile = MemoryTemp.createTempFile("rfc5011-test", ".state");
        try {
            DnssecTrustAnchor anchor1 = new DnssecTrustAnchor();
            MockClockUpdater updater1 = new MockClockUpdater(anchor1);
            KeyPair ksk1 = generateKeyPair();
            DnsResourceRecord ksk1Key = buildKSK(ksk1, 0);
            addStaticAnchor(anchor1, ksk1Key);
            updater1.stateFile(stateFile);
            updater1.addTrustPoint(ZONE);
            bootstrap(updater1, ksk1, ksk1Key);
            assertKeyState(updater1, ksk1Key, DnssecTrustAnchorUpdater.KeyState.VALID);

            // Fresh process: new trust anchor store, new updater, same file.
            DnssecTrustAnchor anchor2 = new DnssecTrustAnchor();
            MockClockUpdater updater2 = new MockClockUpdater(anchor2);
            updater2.stateFile(stateFile);

            assertKeyState(updater2, ksk1Key, DnssecTrustAnchorUpdater.KeyState.VALID);
            assertTrue("restored Valid keys must be re-promoted into the trust anchor store",
                    anchor2.isDNSKEYTrusted(ZONE, ksk1Key));
        } finally {
            Files.deleteIfExists(stateFile);
        }
    }

    // ── End-to-end: checkNow's query/response wiring ──

    @Test
    public void testCheckNowQueriesDNSKEYAndAppliesTheResponse() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        KeyPair ksk1 = generateKeyPair();
        DnsResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);

        RecordingTransport transport = new RecordingTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.transport(transport);
        resolver.addServer("203.0.113.53");
        resolver.open();

        DnssecTrustAnchorUpdater updater = new DnssecTrustAnchorUpdater(resolver, anchor);
        updater.addTrustPoint(ZONE);
        updater.checkNow(ZONE);

        assertNotNull("checkNow should have sent a DNSKEY query", transport.lastSent);
        DnsMessage sent = DnsMessage.parse(transport.lastSent);
        assertEquals(ZONE, sent.getQuestions().get(0).getName());
        assertEquals(DnsType.DNSKEY, sent.getQuestions().get(0).getType());

        List<DnsResourceRecord> rrset = Collections.singletonList(ksk1Key);
        DnsMessage response = buildResponse(rrset, sign(rrset, ksk1, ksk1Key.computeKeyTag()));
        response = withId(response, sent.getId());
        transport.handler.onReceive(response.serialize());

        assertKeyState(updater, ksk1Key, DnssecTrustAnchorUpdater.KeyState.VALID);
        resolver.close();
    }

    // ── Edge cases: fail-open paths, signature selection, persistence ──

    private static DnsResourceRecord keyWithFlags(KeyPair kp, int flags) {
        return DnsResourceRecord.dnskey(ZONE, 3600, flags, ALGORITHM, rsaWireFormat(kp.getPublic()));
    }

    private static DnsResourceRecord fakeSig(long inception, long expiration, int keyTag,
            String signer) {
        return DnsResourceRecord.rrsig(ZONE, 3600, DnsType.DNSKEY, ALGORITHM, 1, 3600,
                expiration, inception, keyTag, signer, new byte[] {1, 2, 3});
    }

    @Test
    public void testConfigurationAccessors() {
        DnssecTrustAnchorUpdater updater = new DnssecTrustAnchorUpdater(new DnsResolver(),
                new DnssecTrustAnchor());
        assertEquals(DnssecTrustAnchorUpdater.DEFAULT_HOLD_DOWN_MS, updater.getAddHoldDownMs());
        assertEquals(DnssecTrustAnchorUpdater.DEFAULT_HOLD_DOWN_MS, updater.getRemoveHoldDownMs());
        assertEquals(DnssecTrustAnchorUpdater.DEFAULT_CHECK_INTERVAL_MS, updater.getCheckIntervalMs());
        updater.addHoldDownMs(11);
        updater.removeHoldDownMs(22);
        updater.checkIntervalMs(33);
        assertEquals(11, updater.getAddHoldDownMs());
        assertEquals(22, updater.getRemoveHoldDownMs());
        assertEquals(33, updater.getCheckIntervalMs());
        updater.addTrustPoint("Example.");
        updater.addTrustPoint("example");
        assertEquals(1, updater.getTrustPoints().size());
        assertTrue(updater.getTrackedKeys("unknown.zone").isEmpty());
        try {
            new DnssecTrustAnchorUpdater(null, new DnssecTrustAnchor());
            fail("null resolver");
        } catch (NullPointerException expected) {
            assertEquals("resolver", expected.getMessage());
        }
        try {
            new DnssecTrustAnchorUpdater(new DnsResolver(), null);
            fail("null anchor");
        } catch (NullPointerException expected) {
            assertEquals("trustAnchor", expected.getMessage());
        }
    }

    @Test
    public void testResponseWithoutDnskeyIsIgnored() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        updater.addTrustPoint(ZONE);
        updater.handleDNSKEYResponse(ZONE, messageFrom(Collections.<DnsResourceRecord>emptyList()));
        assertTrue(updater.getTrackedKeys(ZONE).isEmpty());
    }

    @Test
    public void testUnenrolledZoneIsEnrolledByAValidResponse() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        KeyPair ksk = generateKeyPair();
        DnsResourceRecord key = buildKSK(ksk, 0);
        addStaticAnchor(anchor, key);
        bootstrap(updater, ksk, key);
        assertTrue(updater.getTrustPoints().contains(ZONE));
        assertKeyState(updater, key, DnssecTrustAnchorUpdater.KeyState.VALID);
        DnssecTrustAnchorUpdater.TrackedKeyInfo info = updater.getTrackedKeys(ZONE).get(0);
        assertEquals(key.computeKeyTag(), info.getKeyTag());
        assertEquals(0L, info.getStateChangedAt());
    }

    @Test
    public void testZoneSigningKeysAreNotTracked() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        KeyPair ksk = generateKeyPair();
        KeyPair zsk = generateKeyPair();
        DnsResourceRecord kskKey = buildKSK(ksk, 0);
        DnsResourceRecord zskKey = keyWithFlags(zsk, 0x0100);
        addStaticAnchor(anchor, kskKey);
        updater.addTrustPoint(ZONE);
        List<DnsResourceRecord> rrset = Arrays.asList(kskKey, zskKey);
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset,
                sign(rrset, ksk, kskKey.computeKeyTag())));
        assertEquals("only the secure entry point key is tracked", 1,
                updater.getTrackedKeys(ZONE).size());
    }

    @Test
    public void testUnusableSignaturesLeaveStateUntouched() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        KeyPair ksk = generateKeyPair();
        KeyPair other = generateKeyPair();
        DnsResourceRecord key = buildKSK(ksk, 0);
        addStaticAnchor(anchor, key);
        updater.addTrustPoint(ZONE);
        List<DnsResourceRecord> rrset = Collections.singletonList(key);
        long now = System.currentTimeMillis() / 1000;
        int tag = key.computeKeyTag();

        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>(rrset);
        // expired signature
        answers.add(fakeSig(now - 7200, now - 3600, tag, ZONE));
        // signed by a parent zone rather than the trust point
        answers.add(fakeSig(now - 3600, now + 3600, tag, "parent"));
        // no DNSKEY with this tag
        answers.add(fakeSig(now - 3600, now + 3600, (tag + 5) & 0xFFFF, ZONE));
        // right tag, but the signature does not verify
        answers.add(fakeSig(now - 3600, now + 3600, tag, ZONE));
        // right tag, signed with the wrong private key
        answers.add(sign(rrset, other, tag));
        updater.handleDNSKEYResponse(ZONE, messageFrom(answers));
        assertTrue(updater.getTrackedKeys(ZONE).isEmpty());
    }

    @Test
    public void testRevocationOfPendingKeyHonouredAndOfMissingKeyIgnored() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        updater.addHoldDownMs(1000000);
        KeyPair ksk1 = generateKeyPair();
        DnsResourceRecord ksk1Key = buildKSK(ksk1, 0);
        addStaticAnchor(anchor, ksk1Key);
        updater.addTrustPoint(ZONE);
        bootstrap(updater, ksk1, ksk1Key);

        // a new key is pending; a revocation of it signed by itself is honoured
        KeyPair ksk2 = generateKeyPair();
        DnsResourceRecord ksk2Key = buildKSK(ksk2, 0);
        List<DnsResourceRecord> both = Arrays.asList(ksk1Key, ksk2Key);
        updater.handleDNSKEYResponse(ZONE, buildResponse(both,
                sign(both, ksk1, ksk1Key.computeKeyTag())));
        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.ADD_PENDING);
        DnsResourceRecord ksk2Revoked = buildKSK(ksk2, 0x0080);
        List<DnsResourceRecord> withRevocation = Arrays.asList(ksk1Key, ksk2Revoked);
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>(withRevocation);
        answers.add(sign(withRevocation, ksk1, ksk1Key.computeKeyTag()));
        answers.add(sign(withRevocation, ksk2, ksk2Revoked.computeKeyTag()));
        updater.handleDNSKEYResponse(ZONE, messageFrom(answers));
        assertKeyState(updater, ksk2Key, DnssecTrustAnchorUpdater.KeyState.REVOKED);

        // a revoked key for which nothing was ever tracked is not newly tracked
        KeyPair ksk3 = generateKeyPair();
        DnsResourceRecord ksk3Revoked = buildKSK(ksk3, 0x0080);
        List<DnsResourceRecord> unseen = Arrays.asList(ksk1Key, ksk3Revoked);
        updater.handleDNSKEYResponse(ZONE, buildResponse(unseen,
                sign(unseen, ksk1, ksk1Key.computeKeyTag())));
        assertEquals(2, updater.getTrackedKeys(ZONE).size());
    }

    @Test
    public void testStateFileLoadingSkipsBadLinesAndKeepsKnownKeys() throws Exception {
        KeyPair ksk = generateKeyPair();
        DnsResourceRecord key = buildKSK(ksk, 0);
        KeyPair ksk2 = generateKeyPair();
        DnsResourceRecord missingKey = buildKSK(ksk2, 0);
        String valid = ZONE + "\tVALID\t5\t" + Base64.getEncoder().encodeToString(key.getRData());
        String missing = ZONE + "\tMISSING\t7\t"
                + Base64.getEncoder().encodeToString(missingKey.getRData());
        Path stateFile = MemoryTemp.createTempFile("rfc5011-edge", ".state");
        try {
            Files.write(stateFile, Arrays.asList("", "only\ttwo", valid, valid, missing),
                    java.nio.charset.StandardCharsets.US_ASCII);
            DnssecTrustAnchor anchor = new DnssecTrustAnchor();
            MockClockUpdater updater = new MockClockUpdater(anchor);
            updater.stateFile(stateFile);
            assertEquals("the duplicate line is not tracked twice", 2,
                    updater.getTrackedKeys(ZONE).size());
            assertTrue(anchor.isDNSKEYTrusted(ZONE, key));
            assertFalse("a Missing key is tracked but not trusted",
                    anchor.isDNSKEYTrusted(ZONE, missingKey));

            Files.write(stateFile, Arrays.asList(ZONE + "\tBOGUS\t1\tAAAA"),
                    java.nio.charset.StandardCharsets.US_ASCII);
            MockClockUpdater corrupt = new MockClockUpdater(new DnssecTrustAnchor());
            corrupt.stateFile(stateFile);
            assertTrue("corrupt state restores nothing", corrupt.getTrackedKeys(ZONE).isEmpty());
        } finally {
            Files.deleteIfExists(stateFile);
        }
    }

    @Test
    public void testUnwritableStateFileDoesNotBreakTheUpdate() throws Exception {
        Path dir = MemoryTemp.createTempDirectory("rfc5011-dir");
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        MockClockUpdater updater = new MockClockUpdater(anchor);
        updater.stateFile(dir.resolve("missing-parent").resolve("state"));
        KeyPair ksk = generateKeyPair();
        DnsResourceRecord key = buildKSK(ksk, 0);
        addStaticAnchor(anchor, key);
        updater.addTrustPoint(ZONE);
        bootstrap(updater, ksk, key);
        assertKeyState(updater, key, DnssecTrustAnchorUpdater.KeyState.VALID);
    }

    @Test
    public void testCheckNowFailureLeavesStateUntouched() throws Exception {
        DnssecTrustAnchor anchor = new DnssecTrustAnchor();
        DnsResolver resolver = new DnsResolver();
        DnssecTrustAnchorUpdater updater = new DnssecTrustAnchorUpdater(resolver, anchor);
        updater.addTrustPoint(ZONE);
        try {
            updater.checkNow(ZONE);
        } catch (IllegalStateException expected) {
            // an unopened resolver may refuse the query outright
            assertNotNull(expected.getMessage());
        }
        assertTrue(updater.getTrackedKeys(ZONE).isEmpty());
    }

    // ── Helpers ──

    private static void bootstrap(MockClockUpdater updater, KeyPair ksk, DnsResourceRecord kskRecord)
            throws Exception {
        List<DnsResourceRecord> rrset = Collections.singletonList(kskRecord);
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
    private static void promoteViaRfc5011(MockClockUpdater updater, KeyPair trustedKey,
            DnsResourceRecord trustedKeyRecord, DnsResourceRecord newKey) throws Exception {
        long savedHoldDown = updater.getAddHoldDownMs();
        updater.addHoldDownMs(0);
        List<DnsResourceRecord> rrset = Arrays.asList(trustedKeyRecord, newKey);
        DnsResourceRecord rrsig = sign(rrset, trustedKey, trustedKeyRecord.computeKeyTag());
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset, rrsig));
        assertKeyState(updater, newKey, DnssecTrustAnchorUpdater.KeyState.ADD_PENDING);
        updater.handleDNSKEYResponse(ZONE, buildResponse(rrset, rrsig));
        updater.addHoldDownMs(savedHoldDown);
    }

    private static void assertKeyState(DnssecTrustAnchorUpdater updater, DnsResourceRecord key,
            DnssecTrustAnchorUpdater.KeyState expected) {
        byte[] publicKey = key.getDNSKEYPublicKey();
        for (DnssecTrustAnchorUpdater.TrackedKeyInfo info : updater.getTrackedKeys(ZONE)) {
            // Matched by public key material, not tag: a key's tag
            // changes the moment its REVOKE bit is set (RFC 5011
            // section 5.1), so this is the only way to reliably find
            // "the same key" across that flip -- the same identity
            // DnssecTrustAnchorUpdater itself uses internally.
            if (info.getAlgorithm() == key.getDNSKEYAlgorithm()
                    && Arrays.equals(info.getPublicKey(), publicKey)) {
                assertEquals(expected, info.getState());
                return;
            }
        }
        fail("key not tracked: tag=" + key.computeKeyTag());
    }

    private static void addStaticAnchor(DnssecTrustAnchor anchor, DnsResourceRecord dnskey) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(DnsMessage.encodeName(DnssecValidator.canonicalizeName(dnskey.getName())));
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

    private static DnsResourceRecord buildKSK(KeyPair kp, int extraFlags) {
        return DnsResourceRecord.dnskey(ZONE, 3600, 0x0101 | extraFlags,
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

    private static DnsResourceRecord sign(List<DnsResourceRecord> rrset, KeyPair signer, int keyTag)
            throws Exception {
        long now = System.currentTimeMillis() / 1000;
        DnsResourceRecord template = DnsResourceRecord.rrsig(ZONE, 3600, DnsType.DNSKEY,
                ALGORITHM, 1, 3600, now + 3600, now - 3600, keyTag, ZONE, new byte[0]);
        byte[] header = template.getRRSIGHeaderBytes();
        List<byte[]> canonical = DnssecValidator.buildCanonicalRRset(rrset, template);
        ByteArrayOutputStream toSign = new ByteArrayOutputStream();
        toSign.write(header);
        for (byte[] rec : canonical) {
            toSign.write(rec);
        }
        Signature sig = Signature.getInstance(DnssecAlgorithm.RSASHA256.getSignatureAlgorithm());
        sig.initSign(signer.getPrivate());
        sig.update(toSign.toByteArray());
        byte[] signature = sig.sign();
        return DnsResourceRecord.rrsig(ZONE, 3600, DnsType.DNSKEY,
                ALGORITHM, 1, 3600, now + 3600, now - 3600, keyTag, ZONE, signature);
    }

    private static DnsMessage buildResponse(List<DnsResourceRecord> dnskeys, DnsResourceRecord rrsig) {
        List<DnsResourceRecord> answers = new ArrayList<>(dnskeys);
        answers.add(rrsig);
        return messageFrom(answers);
    }

    private static DnsMessage messageFrom(List<DnsResourceRecord> answers) {
        List<DnsQuestion> questions = Collections.singletonList(
                new DnsQuestion(ZONE, DnsType.DNSKEY, DnsClass.IN));
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA;
        return new DnsMessage(1, flags, questions, answers,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
    }

    private static DnsMessage withId(DnsMessage message, int id) {
        return new DnsMessage(id, message.getFlags(), message.getQuestions(), message.getAnswers(),
                message.getAuthorities(), message.getAdditionals());
    }

    /** Overrides {@link DnssecTrustAnchorUpdater#now} for deterministic hold-down tests. */
    private static class MockClockUpdater extends DnssecTrustAnchorUpdater {
        long fakeNow;

        MockClockUpdater(DnssecTrustAnchor anchor) {
            super(dummyResolver(), anchor);
        }

        @Override
        long now() {
            return fakeNow;
        }

        private static DnsResolver dummyResolver() {
            return new DnsResolver();
        }
    }

    private static class RecordingTransport implements DnsClientTransport {
        DnsClientTransportHandler handler;
        ByteBuffer lastSent;

        @Override
        public void open(InetAddress server, int port, SelectorLoop loop,
                         DnsClientTransportHandler handler) {
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
