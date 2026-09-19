/*
 * DnsTsig.java
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
/**
 * TSIG signing and verification (RFC 2845, RFC 4635, RFC 8945).
 *
 * <p>MAC input for requests is the unsigned message (TSIG stripped, AR count
 * adjusted, optional ID substitution) followed by TSIG variables: key name,
 * class ANY, TTL 0, algorithm name, time signed, fudge, error, other len,
 * other data. Responses prepend the request MAC (RFC 8945 section 4.3.1).
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DnsTsig {

    private static final int DEFAULT_FUDGE = 300;

    private DnsTsig() {
    }

    /**
     * Returns a copy of {@code message} with a TSIG record appended.
     */
    public static DnsMessage sign(DnsMessage message, TsigKey key) throws IOException {
        return sign(message, key, System.currentTimeMillis() / 1000L, DEFAULT_FUDGE);
    }

    static DnsMessage sign(DnsMessage message, TsigKey key, long timeSignedSeconds,
                           int fudge) throws IOException {
        if (key == null) {
            throw new NullPointerException("key");
        }
        int originalId = message.getId();
        byte[] tsigVariables = buildTsigVariables(key.getName(),
                TsigAlgorithm.toWireName(key.getAlgorithm()), timeSignedSeconds,
                fudge, 0, 0, null);
        byte[] msgWire = messageWireForMac(message, originalId, null);
        byte[] mac = computeMac(key, concat(msgWire, tsigVariables));
        DnsResourceRecord tsig = tsigRecord(key, mac, originalId, timeSignedSeconds,
                fudge, 0, 0, null);
        List<DnsResourceRecord> additionals = new ArrayList<DnsResourceRecord>(
                message.getAdditionals());
        additionals.add(tsig);
        return new DnsMessage(message.getId(), message.getFlags(),
                message.getQuestions(), message.getAnswers(),
                message.getAuthorities(), additionals);
    }

    /**
     * Signs an UPDATE (or other) response, including the request MAC from
     * {@code request}'s TSIG. No-op when the request had no TSIG.
     */
    public static DnsMessage signResponse(DnsMessage response, TsigKey key,
                                          DnsMessage request) throws IOException {
        if (request.getTsigRecord() == null || key == null) {
            return response;
        }
        ParsedTsig requestTsig = ParsedTsig.parse(request.getTsigRecord().getRData());
        return signResponse(response, key, requestTsig,
                System.currentTimeMillis() / 1000L, DEFAULT_FUDGE);
    }

    static DnsMessage signResponse(DnsMessage response, TsigKey key,
                                   DnsMessage request, long timeSignedSeconds,
                                   int fudge) throws IOException {
        if (request.getTsigRecord() == null || key == null) {
            return response;
        }
        ParsedTsig requestTsig = ParsedTsig.parse(request.getTsigRecord().getRData());
        return signResponse(response, key, requestTsig, timeSignedSeconds, fudge);
    }

    private static DnsMessage signResponse(DnsMessage response, TsigKey key,
                                           ParsedTsig requestTsig,
                                           long timeSignedSeconds, int fudge)
            throws IOException {
        byte[] requestMacWire = encodeRequestMacWire(requestTsig.mac);
        byte[] tsigVariables = buildTsigVariables(key.getName(),
                TsigAlgorithm.toWireName(key.getAlgorithm()), timeSignedSeconds,
                fudge, 0, 0, null);
        byte[] msgWire = messageWireForMac(response, response.getId(), requestMacWire);
        byte[] mac = computeMac(key, concat(msgWire, tsigVariables));
        DnsResourceRecord tsig = tsigRecord(key, mac, response.getId(), timeSignedSeconds,
                fudge, 0, 0, null);
        List<DnsResourceRecord> additionals = new ArrayList<DnsResourceRecord>(
                response.getAdditionals());
        additionals.add(tsig);
        return new DnsMessage(response.getId(), response.getFlags(),
                response.getQuestions(), response.getAnswers(),
                response.getAuthorities(), additionals);
    }

    /**
     * Signs each message in a TCP zone transfer (AXFR/IXFR) sequence. Each
     * response MAC chains from the previous signed message (RFC 8945).
     */
    public static List<DnsMessage> signResponseSequence(List<DnsMessage> responses,
                                                        TsigKey key,
                                                        DnsMessage request)
            throws IOException {
        if (request.getTsigRecord() == null || key == null) {
            return responses;
        }
        List<DnsMessage> signed = new ArrayList<DnsMessage>(responses.size());
        DnsMessage previous = request;
        for (int i = 0; i < responses.size(); i++) {
            DnsMessage one = signResponse(responses.get(i), key, previous);
            signed.add(one);
            previous = one;
        }
        return signed;
    }

    /**
     * Verifies TSIG on each message in a TCP zone transfer response sequence.
     */
    public static boolean verifyResponseSequence(List<DnsMessage> responses,
                                                 TsigKey key,
                                                 DnsMessage request) {
        if (request.getTsigRecord() == null) {
            return key == null;
        }
        if (key == null || responses == null || responses.isEmpty()) {
            return false;
        }
        DnsMessage previous = request;
        for (int i = 0; i < responses.size(); i++) {
            DnsMessage response = responses.get(i);
            if (!verifyResponse(response, key, previous)) {
                return false;
            }
            previous = response;
        }
        return true;
    }

    /**
     * Verifies the TSIG on a response that answers a TSIG-signed {@code request}.
     */
    public static boolean verifyResponse(DnsMessage response, TsigKey key,
                                         DnsMessage request) {
        if (response.getTsigRecord() == null || request.getTsigRecord() == null
                || key == null) {
            return false;
        }
        if (!TsigAlgorithm.canonicalWire(key.getName())
                .equalsIgnoreCase(TsigAlgorithm.canonicalWire(
                        response.getTsigRecord().getName()))) {
            return false;
        }
        try {
            ParsedTsig requestTsig = ParsedTsig.parse(
                    request.getTsigRecord().getRData());
            ParsedTsig responseTsig = ParsedTsig.parse(
                    response.getTsigRecord().getRData());
            if (!TsigAlgorithm.matchesKey(key, responseTsig.algorithm)) {
                return false;
            }
            if (!timeWithinFudge(responseTsig.timeSigned, responseTsig.fudge)) {
                return false;
            }
            byte[] requestMacWire = encodeRequestMacWire(requestTsig.mac);
            byte[] tsigVariables = buildTsigVariables(response.getTsigRecord().getName(),
                    responseTsig.algorithm, responseTsig.timeSigned, responseTsig.fudge,
                    responseTsig.error, responseTsig.otherLen, responseTsig.otherData);
            byte[] msgWire = messageWireForMac(response, responseTsig.originalId,
                    requestMacWire);
            byte[] mac = computeMac(key, concat(msgWire, tsigVariables));
            return constantTimeEquals(mac, responseTsig.mac);
        } catch (Exception e) {
            return false;
        }
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
        if (key == null) {
            return false;
        }
        if (!TsigAlgorithm.canonicalWire(key.getName())
                .equalsIgnoreCase(TsigAlgorithm.canonicalWire(tsig.getName()))) {
            return false;
        }
        try {
            ParsedTsig parsed = ParsedTsig.parse(tsig.getRData());
            if (!TsigAlgorithm.matchesKey(key, parsed.algorithm)) {
                return false;
            }
            if (!timeWithinFudge(parsed.timeSigned, parsed.fudge)) {
                return false;
            }
            byte[] tsigVariables = buildTsigVariables(tsig.getName(), parsed.algorithm,
                    parsed.timeSigned, parsed.fudge, parsed.error,
                    parsed.otherLen, parsed.otherData);
            byte[] msgWire = messageWireForMac(message, parsed.originalId, null);
            byte[] mac = computeMac(key, concat(msgWire, tsigVariables));
            return constantTimeEquals(mac, parsed.mac);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean timeWithinFudge(long timeSigned, int fudge) {
        long now = System.currentTimeMillis() / 1000L;
        long delta = Math.abs(now - timeSigned);
        return delta <= (long) fudge + DEFAULT_FUDGE;
    }

    private static byte[] messageWireForMac(DnsMessage message, int headerId,
                                            byte[] requestMacWire) throws IOException {
        List<DnsResourceRecord> additionals = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < message.getAdditionals().size(); i++) {
            DnsResourceRecord rr = message.getAdditionals().get(i);
            if (rr.getRawType() != DnsType.TSIG.getValue()) {
                additionals.add(rr);
            }
        }
        DnsMessage unsigned = new DnsMessage(headerId, message.getFlags(),
                message.getQuestions(), message.getAnswers(),
                message.getAuthorities(), additionals);
        byte[] msg = unsigned.serialize().array();
        if (requestMacWire != null && requestMacWire.length > 0) {
            return concat(requestMacWire, msg);
        }
        return msg;
    }

    /**
     * RFC 8945 section 4.3.3 TSIG variables (partial RR + RDATA prefix).
     */
    private static byte[] encodeRequestMacWire(byte[] mac) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUint16(out, mac.length);
        out.write(mac);
        return out.toByteArray();
    }

    private static byte[] buildTsigVariables(String keyName, String algorithmWire,
                                           long timeSigned, int fudge, int error,
                                           int otherLen, byte[] otherData)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(DnsMessage.encodeName(canonicalName(keyName)));
        writeUint16(out, DnsClass.ANY.getValue());
        writeUint32(out, 0);
        out.write(DnsMessage.encodeName(canonicalName(algorithmWire)));
        writeUint48(out, timeSigned);
        writeUint16(out, fudge);
        writeUint16(out, error);
        writeUint16(out, otherLen);
        if (otherLen > 0 && otherData != null) {
            out.write(otherData, 0, otherLen);
        }
        return out.toByteArray();
    }

    private static DnsResourceRecord tsigRecord(TsigKey key, byte[] mac,
                                                int originalId, long timeSigned,
                                                int fudge, int error, int otherLen,
                                                byte[] otherData) throws IOException {
        ByteArrayOutputStream rdata = new ByteArrayOutputStream();
        rdata.write(DnsMessage.encodeName(canonicalName(
                TsigAlgorithm.toWireName(key.getAlgorithm()))));
        writeUint48(rdata, timeSigned);
        writeUint16(rdata, fudge);
        writeUint16(rdata, mac.length);
        rdata.write(mac);
        writeUint16(rdata, originalId);
        writeUint16(rdata, error);
        writeUint16(rdata, otherLen);
        if (otherLen > 0 && otherData != null) {
            rdata.write(otherData, 0, otherLen);
        }
        return new DnsResourceRecord(canonicalName(key.getName()), DnsType.TSIG,
                DnsClass.ANY, 0, rdata.toByteArray());
    }

    private static byte[] computeMac(TsigKey key, byte[] input) {
        try {
            String jca = TsigAlgorithm.toJcaName(key.getAlgorithm());
            Mac mac = Mac.getInstance(jca);
            mac.init(new SecretKeySpec(key.getSecret(), jca));
            return mac.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("TSIG MAC failed", e);
        }
    }

    private static String canonicalName(String name) {
        return TsigAlgorithm.canonicalWire(name);
    }

    private static byte[] concat(byte[] a, byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(a.length + b.length);
        out.write(a);
        out.write(b);
        return out.toByteArray();
    }

    private static void writeUint16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeUint32(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >> 24) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) (v & 0xFF));
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

    private static final class ParsedTsig {
        final String algorithm;
        final long timeSigned;
        final int fudge;
        final byte[] mac;
        final int originalId;
        final int error;
        final int otherLen;
        final byte[] otherData;

        private ParsedTsig(String algorithm, long timeSigned, int fudge, byte[] mac,
                           int originalId, int error, int otherLen, byte[] otherData) {
            this.algorithm = algorithm;
            this.timeSigned = timeSigned;
            this.fudge = fudge;
            this.mac = mac;
            this.originalId = originalId;
            this.error = error;
            this.otherLen = otherLen;
            this.otherData = otherData;
        }

        static ParsedTsig parse(byte[] rdata) {
            ByteBuffer buf = ByteBuffer.wrap(rdata);
            ByteBuffer original = buf.duplicate();
            String algorithm = DnsMessage.decodeName(buf, original);
            long timeSigned = readUint48(buf);
            int fudge = buf.getShort() & 0xFFFF;
            int macLen = buf.getShort() & 0xFFFF;
            byte[] mac = new byte[macLen];
            buf.get(mac);
            int origId = buf.getShort() & 0xFFFF;
            int error = buf.getShort() & 0xFFFF;
            int otherLen = buf.getShort() & 0xFFFF;
            byte[] otherData = null;
            if (otherLen > 0) {
                otherData = new byte[otherLen];
                buf.get(otherData);
            }
            return new ParsedTsig(TsigAlgorithm.canonicalWire(algorithm), timeSigned,
                    fudge, mac, origId, error, otherLen, otherData);
        }

        private static long readUint48(ByteBuffer buf) {
            long v = 0;
            for (int i = 0; i < 6; i++) {
                v = (v << 8) | (buf.get() & 0xFF);
            }
            return v;
        }
    }
}
