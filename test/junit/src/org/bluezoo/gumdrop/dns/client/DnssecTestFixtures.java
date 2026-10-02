/*
 * DnssecTestFixtures.java
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
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.EdECPoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers for building signed DNSSEC test data: key generation, DNSKEY/DS
 * construction, RRSIG signing, NSEC and NSEC3 records and a canned-response
 * resolver.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DnssecTestFixtures {

    private DnssecTestFixtures() {
    }

    /** A generated signing key and its DNSKEY record. */
    public static final class TestKey {
        public final PrivateKey privateKey;
        public final DnsResourceRecord dnskey;
        public final int algorithm;
        public final int keyTag;

        public TestKey(PrivateKey privateKey, DnsResourceRecord dnskey, int algorithm) {
            this.privateKey = privateKey;
            this.dnskey = dnskey;
            this.algorithm = algorithm;
            this.keyTag = dnskey.computeKeyTag();
        }
    }

    public static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static byte[] fixed(BigInteger v, int size) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[size];
        int srcStart = 0;
        if (raw.length > size) {
            srcStart = raw.length - size;
        }
        int copy = raw.length - srcStart;
        System.arraycopy(raw, srcStart, out, size - copy, copy);
        return out;
    }

    public static TestKey ecdsaP256(String zone, int flags) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = gen.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        byte[] x = fixed(pub.getW().getAffineX(), 32);
        byte[] y = fixed(pub.getW().getAffineY(), 32);
        byte[] wire = new byte[64];
        System.arraycopy(x, 0, wire, 0, 32);
        System.arraycopy(y, 0, wire, 32, 32);
        int alg = DnssecAlgorithm.ECDSAP256SHA256.getNumber();
        DnsResourceRecord rr = DnsResourceRecord.dnskey(zone, 300, flags, alg, wire);
        return new TestKey(kp.getPrivate(), rr, alg);
    }

    public static TestKey ecdsaP384(String zone, int flags) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair kp = gen.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        byte[] x = fixed(pub.getW().getAffineX(), 48);
        byte[] y = fixed(pub.getW().getAffineY(), 48);
        byte[] wire = new byte[96];
        System.arraycopy(x, 0, wire, 0, 48);
        System.arraycopy(y, 0, wire, 48, 48);
        int alg = DnssecAlgorithm.ECDSAP384SHA384.getNumber();
        DnsResourceRecord rr = DnsResourceRecord.dnskey(zone, 300, flags, alg, wire);
        return new TestKey(kp.getPrivate(), rr, alg);
    }

    public static TestKey ed25519(String zone, int flags) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = gen.generateKeyPair();
        EdECPublicKey pub = (EdECPublicKey) kp.getPublic();
        EdECPoint point = pub.getPoint();
        byte[] be = fixed(point.getY(), 32);
        byte[] wire = new byte[32];
        for (int i = 0; i < 32; i++) {
            wire[i] = be[31 - i];
        }
        if (point.isXOdd()) {
            wire[31] = (byte) (wire[31] | 0x80);
        }
        int alg = DnssecAlgorithm.ED25519.getNumber();
        DnsResourceRecord rr = DnsResourceRecord.dnskey(zone, 300, flags, alg, wire);
        return new TestKey(kp.getPrivate(), rr, alg);
    }

    public static TestKey rsaSha256(String zone, int flags) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(1024);
        KeyPair kp = gen.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        byte[] exp = pub.getPublicExponent().toByteArray();
        byte[] mod = pub.getModulus().toByteArray();
        if (mod[0] == 0) {
            byte[] trimmed = new byte[mod.length - 1];
            System.arraycopy(mod, 1, trimmed, 0, trimmed.length);
            mod = trimmed;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(exp.length);
        out.write(exp, 0, exp.length);
        out.write(mod, 0, mod.length);
        int alg = DnssecAlgorithm.RSASHA256.getNumber();
        DnsResourceRecord rr = DnsResourceRecord.dnskey(zone, 300, flags,
                alg, out.toByteArray());
        return new TestKey(kp.getPrivate(), rr, alg);
    }

    /** Converts a DER ECDSA signature to the DNSSEC raw r||s form. */
    private static byte[] derToRaw(byte[] der, int size) {
        int pos = 2;
        if ((der[1] & 0x80) != 0) {
            pos = 3;
        }
        int rl = der[pos + 1] & 0xFF;
        byte[] r = new byte[rl];
        System.arraycopy(der, pos + 2, r, 0, rl);
        int sp = pos + 2 + rl;
        int sl = der[sp + 1] & 0xFF;
        byte[] s = new byte[sl];
        System.arraycopy(der, sp + 2, s, 0, sl);
        byte[] rf = fixed(new BigInteger(1, r), size);
        byte[] sf = fixed(new BigInteger(1, s), size);
        byte[] out = new byte[size * 2];
        System.arraycopy(rf, 0, out, 0, size);
        System.arraycopy(sf, 0, out, size, size);
        return out;
    }

    public static DnsResourceRecord sign(List<DnsResourceRecord> rrset, TestKey key,
                                  String signer, long inception, long expiration)
            throws Exception {
        DnsResourceRecord first = rrset.get(0);
        DnsType covered = first.getType();
        String owner = first.getName();
        int labels = 0;
        String trimmed = owner;
        if (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.isEmpty()) {
            labels = 1;
            for (int i = 0; i < trimmed.length(); i++) {
                if (trimmed.charAt(i) == '.') {
                    labels++;
                }
            }
        }
        DnsResourceRecord empty = DnsResourceRecord.rrsig(owner, 300, covered,
                key.algorithm, labels, first.getTTL(), expiration, inception,
                key.keyTag, signer, new byte[0]);
        byte[] header = empty.getRRSIGHeaderBytes();
        List<byte[]> canonical = DnssecValidator.buildCanonicalRRset(rrset, empty);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(header, 0, header.length);
        for (int i = 0; i < canonical.size(); i++) {
            byte[] rec = canonical.get(i);
            data.write(rec, 0, rec.length);
        }
        DnssecAlgorithm alg = DnssecAlgorithm.fromNumber(key.algorithm);
        String sigName = alg.getSignatureAlgorithm();
        Signature sig = Signature.getInstance(sigName);
        sig.initSign(key.privateKey);
        sig.update(data.toByteArray());
        byte[] sigBytes = sig.sign();
        if (key.algorithm == DnssecAlgorithm.ECDSAP256SHA256.getNumber()) {
            sigBytes = derToRaw(sigBytes, 32);
        } else if (key.algorithm == DnssecAlgorithm.ECDSAP384SHA384.getNumber()) {
            sigBytes = derToRaw(sigBytes, 48);
        }
        return DnsResourceRecord.rrsig(owner, 300, covered, key.algorithm,
                labels, first.getTTL(), expiration, inception, key.keyTag,
                signer, sigBytes);
    }

    public static DnsResourceRecord signCurrent(List<DnsResourceRecord> rrset,
                                         TestKey key, String signer)
            throws Exception {
        long n = now();
        return sign(rrset, key, signer, n - 3600, n + 3600);
    }

    /** Builds a DS record for the key using the given digest type (2 = SHA-256). */
    public static DnsResourceRecord ds(TestKey key, int digestType) throws Exception {
        String digestAlg = DnssecAlgorithm.dsDigestAlgorithm(digestType);
        MessageDigest md = MessageDigest.getInstance(digestAlg);
        String name = key.dnskey.getName();
        String lower = name.toLowerCase();
        if (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        md.update(DnsMessage.encodeName(lower));
        md.update(key.dnskey.getRData());
        byte[] digest = md.digest();
        byte[] rdata = new byte[4 + digest.length];
        rdata[0] = (byte) (key.keyTag >> 8);
        rdata[1] = (byte) key.keyTag;
        rdata[2] = (byte) key.algorithm;
        rdata[3] = (byte) digestType;
        System.arraycopy(digest, 0, rdata, 4, digest.length);
        return new DnsResourceRecord(name, DnsType.DS, DnsClass.IN, 300, rdata);
    }

    /** Builds a DS record with an arbitrary raw digest type and digest bytes. */
    public static DnsResourceRecord rawDs(String name, int tag, int alg, int digestType,
                                   byte[] digest) {
        byte[] rdata = new byte[4 + digest.length];
        rdata[0] = (byte) (tag >> 8);
        rdata[1] = (byte) tag;
        rdata[2] = (byte) alg;
        rdata[3] = (byte) digestType;
        System.arraycopy(digest, 0, rdata, 4, digest.length);
        return new DnsResourceRecord(name, DnsType.DS, DnsClass.IN, 300, rdata);
    }

    private static byte[] typeBitmap(int[] types) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] maxByte = new int[256];
        byte[][] bits = new byte[256][];
        for (int i = 0; i < types.length; i++) {
            int w = types[i] >> 8;
            int low = types[i] & 0xFF;
            if (bits[w] == null) {
                bits[w] = new byte[32];
            }
            bits[w][low >> 3] = (byte) (bits[w][low >> 3] | (0x80 >> (low & 7)));
            if ((low >> 3) + 1 > maxByte[w]) {
                maxByte[w] = (low >> 3) + 1;
            }
        }
        for (int w = 0; w < 256; w++) {
            if (bits[w] != null) {
                out.write(w);
                out.write(maxByte[w]);
                out.write(bits[w], 0, maxByte[w]);
            }
        }
        return out.toByteArray();
    }

    public static DnsResourceRecord nsec(String owner, String next, int[] types) {
        byte[] nameWire = DnsMessage.encodeName(next);
        byte[] map = typeBitmap(types);
        byte[] rdata = new byte[nameWire.length + map.length];
        System.arraycopy(nameWire, 0, rdata, 0, nameWire.length);
        System.arraycopy(map, 0, rdata, nameWire.length, map.length);
        return new DnsResourceRecord(owner, DnsType.NSEC, DnsClass.IN, 300, rdata);
    }

    public static DnsResourceRecord nsec3(String owner, int hashAlg, int iterations,
                                   byte[] salt, byte[] nextHash, int[] types) {
        byte[] map = typeBitmap(types);
        byte[] rdata = new byte[5 + salt.length + 1 + nextHash.length + map.length];
        rdata[0] = (byte) hashAlg;
        rdata[1] = 0;
        rdata[2] = (byte) (iterations >> 8);
        rdata[3] = (byte) iterations;
        rdata[4] = (byte) salt.length;
        System.arraycopy(salt, 0, rdata, 5, salt.length);
        int pos = 5 + salt.length;
        rdata[pos] = (byte) nextHash.length;
        System.arraycopy(nextHash, 0, rdata, pos + 1, nextHash.length);
        System.arraycopy(map, 0, rdata, pos + 1 + nextHash.length, map.length);
        return new DnsResourceRecord(owner, DnsType.NSEC3, DnsClass.IN, 300, rdata);
    }

    public static DnsMessage message(List<DnsResourceRecord> answers,
                              List<DnsResourceRecord> authorities) {
        return new DnsMessage(1, 0, Collections.<DnsQuestion>emptyList(),
                answers, authorities, Collections.<DnsResourceRecord>emptyList());
    }

    public static List<DnsResourceRecord> list(DnsResourceRecord... records) {
        List<DnsResourceRecord> out = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < records.length; i++) {
            out.add(records[i]);
        }
        return out;
    }

    /** A resolver that replies from canned messages instead of using the network. */
    public static final class CannedResolver extends DnsResolver {
        private final Map<String, DnsMessage> responses = new HashMap<String, DnsMessage>();
        private final Map<String, String> errors = new HashMap<String, String>();
        public final List<String> asked = new ArrayList<String>();

        private static String key(String name, DnsType type) {
            String n = name.toLowerCase();
            if (n.endsWith(".")) {
                n = n.substring(0, n.length() - 1);
            }
            return n + "/" + type.name();
        }

        public void put(String name, DnsType type, DnsMessage response) {
            responses.put(key(name, type), response);
        }

        public void putError(String name, DnsType type, String error) {
            errors.put(key(name, type), error);
        }

        @Override
        public void query(String name, DnsType type, DnsQueryCallback callback) {
            String key = key(name, type);
            asked.add(key);
            String error = errors.get(key);
            if (error != null) {
                callback.onError(error);
                return;
            }
            DnsMessage response = responses.get(key);
            if (response == null) {
                callback.onError("no canned response for " + key);
                return;
            }
            callback.onResponse(response);
        }
    }
}
