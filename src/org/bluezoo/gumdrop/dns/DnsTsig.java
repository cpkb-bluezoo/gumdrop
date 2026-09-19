/*
 * DnsTsig.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * TSIG signing and verification (RFC 2845, RFC 4635 HMAC-SHA256).
 */
public final class DnsTsig {

    private static final int FUDGE = 300;

    private DnsTsig() {
    }

    /**
     * Returns a copy of {@code message} with a TSIG record appended.
     */
    public static DnsMessage sign(DnsMessage message, TsigKey key) throws IOException {
        ByteBuffer wire = message.serialize();
        byte[] msgBytes = new byte[wire.remaining()];
        wire.get(msgBytes);
        byte[] mac = mac(key, msgBytes);
        DnsResourceRecord tsig = tsigRecord(key, mac, message.getId(), 0);
        List<DnsResourceRecord> additionals = new ArrayList<DnsResourceRecord>(
                message.getAdditionals());
        additionals.add(tsig);
        return new DnsMessage(message.getId(), message.getFlags(),
                message.getQuestions(), message.getAnswers(),
                message.getAuthorities(), additionals);
    }

    /**
     * Verifies the TSIG on {@code message}. Returns true if valid or absent
     * when {@code optional} is true.
     */
    public static boolean verify(DnsMessage message, TsigKey key, boolean optional) {
        DnsResourceRecord tsig = message.getTsigRecord();
        if (tsig == null) {
            return optional;
        }
        if (key == null || !key.getName().equalsIgnoreCase(normalize(tsig.getName()))) {
            return false;
        }
        try {
            List<DnsResourceRecord> without = new ArrayList<DnsResourceRecord>();
            for (int i = 0; i < message.getAdditionals().size(); i++) {
                DnsResourceRecord rr = message.getAdditionals().get(i);
                if (rr.getRawType() != DnsType.TSIG.getValue()) {
                    without.add(rr);
                }
            }
            DnsMessage unsigned = new DnsMessage(message.getId(), message.getFlags(),
                    message.getQuestions(), message.getAnswers(),
                    message.getAuthorities(), without);
            ByteBuffer wire = unsigned.serialize();
            byte[] msgBytes = new byte[wire.remaining()];
            wire.get(msgBytes);
            byte[] expected = mac(key, msgBytes);
            byte[] actual = extractMac(tsig.getRData());
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] mac(TsigKey key, byte[] msg) {
        try {
            String alg = key.getAlgorithm();
            String jca = TsigKey.HMAC_SHA256.equals(alg) ? "HmacSHA256" : "HmacMD5";
            Mac mac = Mac.getInstance(jca);
            mac.init(new SecretKeySpec(key.getSecret(), jca));
            return mac.doFinal(msg);
        } catch (Exception e) {
            throw new IllegalStateException("TSIG MAC failed", e);
        }
    }

    private static DnsResourceRecord tsigRecord(TsigKey key, byte[] mac,
            int origId, int error) throws IOException {
        ByteArrayOutputStream rdata = new ByteArrayOutputStream();
        byte[] algName = DnsMessage.encodeName(key.getAlgorithm() + ".");
        rdata.write(algName);
        writeUint48(rdata, System.currentTimeMillis() / 1000L);
        writeUint16(rdata, FUDGE);
        writeUint16(rdata, error);
        writeUint16(rdata, 0);
        writeUint16(rdata, mac.length);
        rdata.write(mac);
        writeUint16(rdata, origId);
        writeUint16(rdata, 0);
        return new DnsResourceRecord(key.getName(), DnsType.TSIG, DnsClass.ANY,
                0, rdata.toByteArray());
    }

    private static byte[] extractMac(byte[] rdata) {
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        skipName(buf);
        buf.get(new byte[6]);
        buf.getShort();
        buf.getShort();
        buf.getShort();
        int macLen = buf.getShort() & 0xFFFF;
        byte[] mac = new byte[macLen];
        buf.get(mac);
        return mac;
    }

    private static void skipName(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            int len = buf.get() & 0xFF;
            if (len == 0) {
                return;
            }
            if ((len & 0xC0) == 0xC0) {
                buf.get();
                return;
            }
            buf.position(buf.position() + len);
        }
    }

    private static void writeUint16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeUint48(ByteArrayOutputStream out, long seconds) {
        out.write((int) ((seconds >> 40) & 0xFF));
        out.write((int) ((seconds >> 32) & 0xFF));
        out.write((int) ((seconds >> 24) & 0xFF));
        out.write((int) ((seconds >> 16) & 0xFF));
        out.write((int) ((seconds >> 8) & 0xFF));
        out.write((int) (seconds & 0xFF));
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private static String normalize(String name) {
        String n = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (!n.endsWith(".")) {
            n = n + ".";
        }
        return n;
    }
}
