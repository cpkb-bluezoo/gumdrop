/*
 * Tls12HandshakeMessages.java
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.crypto.SignatureScheme;

/**
 * Wire encoding and decoding of the TLS 1.2 handshake messages and
 * extensions {@link Tls12HandshakeEngine} needs (RFC 5246 section 7.4,
 * RFC 4492/8422 ECDHE), each message built or parsed as a complete,
 * framed unit (one-octet type, three-octet length, then content).
 *
 * <p>Deliberately self-contained -- no sharing with {@link HandshakeMessages}
 * (TLS 1.3) beyond {@link WireReader}/{@link WireWriter} and a couple of
 * genuinely version-agnostic extension helpers (ALPN). The two protocols'
 * {@code ClientHello} preambles look alike, but {@code Certificate}'s
 * per-entry framing already differs (TLS 1.3 added a per-certificate
 * extensions field TLS 1.2 doesn't have), and every message from
 * {@code ServerKeyExchange} on has no TLS 1.3 equivalent at all.
 *
 * <p>Package-private: this is {@link Tls12HandshakeEngine}'s own wire
 * layer, not a public API in its own right.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5246#section-7.4">RFC 5246 section 7.4</a>
 */
final class Tls12HandshakeMessages {

    static final int HANDSHAKE_TYPE_CLIENT_HELLO = 1;
    static final int HANDSHAKE_TYPE_SERVER_HELLO = 2;
    static final int HANDSHAKE_TYPE_NEW_SESSION_TICKET = 4;
    static final int HANDSHAKE_TYPE_CERTIFICATE = 11;
    static final int HANDSHAKE_TYPE_SERVER_KEY_EXCHANGE = 12;
    static final int HANDSHAKE_TYPE_CERTIFICATE_REQUEST = 13;
    static final int HANDSHAKE_TYPE_SERVER_HELLO_DONE = 14;
    static final int HANDSHAKE_TYPE_CERTIFICATE_VERIFY = 15;
    static final int HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE = 16;
    static final int HANDSHAKE_TYPE_FINISHED = 20;

    private static final int EXT_SERVER_NAME = 0x0000;
    private static final int EXT_SUPPORTED_GROUPS = 0x000a;
    private static final int EXT_EC_POINT_FORMATS = 0x000b;
    private static final int EXT_SIGNATURE_ALGORITHMS = 0x000d;
    private static final int EXT_ALPN = 0x0010;
    private static final int EXT_EXTENDED_MASTER_SECRET = 0x0017;
    private static final int EXT_SESSION_TICKET = 0x0023;
    private static final int EXT_SUPPORTED_VERSIONS = 0x002b;
    private static final int EXT_RENEGOTIATION_INFO = 0xff01;

    static final int TLS_1_2_LEGACY_VERSION = 0x0303;

    /**
     * RFC 5746 section 3.3 -- a pseudo-cipher-suite alternative to the
     * {@code renegotiation_info} extension, for a peer that can't send
     * extensions. This engine's own client never sends it (the extension
     * already covers this engine's own initial handshake), but a server
     * must still recognise it from a peer that does.
     */
    static final int TLS_EMPTY_RENEGOTIATION_INFO_SCSV = 0x00FF;

    private static final int SERVER_NAME_TYPE_HOST_NAME = 0;

    /** RFC 4492 section 5.1.1 -- the only curve this engine speaks. */
    private static final int NAMED_CURVE_SECP256R1 = 23;
    /** RFC 4492 section 5.4. */
    private static final int EC_CURVE_TYPE_NAMED_CURVE = 3;
    /** RFC 4492 section 5.1.2 -- we only ever offer/accept uncompressed. */
    private static final int EC_POINT_FORMAT_UNCOMPRESSED = 0;

    private Tls12HandshakeMessages() {
    }

    // ---- ClientHello (RFC 5246 section 7.4.1.2) ----

    /** The inputs to build a ClientHello. */
    static final class ClientHelloParams {
        byte[] random;
        /** Empty for a full handshake; a fresh random value to offer resumption. */
        byte[] sessionId;
        List<Tls12CipherSuite> cipherSuites;
        String serverName;
        List<SignatureScheme> signatureAlgorithms;
        List<String> applicationProtocols;
        /**
         * {@code null} omits the {@code session_ticket} extension entirely;
         * empty advertises support with nothing cached; non-empty attempts
         * resumption with a cached ticket (RFC 5077 section 3.2/3.4).
         */
        byte[] sessionTicket;
        /** DTLS-only: cookie between session_id and cipher_suites. */
        byte[] dtlsCookie;
        /** When true, emit/read the DTLS cookie field (possibly empty). */
        boolean dtlsTransport;
    }

    static byte[] buildClientHello(ClientHelloParams params) {
        WireWriter w = new WireWriter();
        w.u16(TLS_1_2_LEGACY_VERSION);
        w.bytes(params.random);
        w.opaque8(params.sessionId);
        if (params.dtlsTransport) {
            w.opaque8(params.dtlsCookie != null ? params.dtlsCookie : new byte[0]);
        }

        WireWriter cs = new WireWriter();
        for (int i = 0; i < params.cipherSuites.size(); i++) {
            cs.u16(params.cipherSuites.get(i).getCode());
        }
        w.opaque16(cs.toByteArray());

        w.opaque8(new byte[] { 0 }); // compression_methods: [null]

        WireWriter ext = new WireWriter();
        writeExtension(ext, EXT_RENEGOTIATION_INFO, new byte[] { 0 });
        writeExtension(ext, EXT_EXTENDED_MASTER_SECRET, new byte[0]);
        WireWriter sv = new WireWriter();
        sv.opaque8(new byte[] { (byte) (TLS_1_2_LEGACY_VERSION >> 8), (byte) TLS_1_2_LEGACY_VERSION });
        writeExtension(ext, EXT_SUPPORTED_VERSIONS, sv.toByteArray());
        writeSupportedGroupsExtension(ext);
        writeExtension(ext, EXT_EC_POINT_FORMATS, new byte[] { 1, EC_POINT_FORMAT_UNCOMPRESSED });
        writeSignatureAlgorithmsExtension(ext, params.signatureAlgorithms);
        if (params.serverName != null) {
            writeServerNameExtension(ext, params.serverName);
        }
        if (params.applicationProtocols != null && !params.applicationProtocols.isEmpty()) {
            HandshakeMessages.writeAlpnExtension(ext, params.applicationProtocols);
        }
        if (params.sessionTicket != null) {
            writeExtension(ext, EXT_SESSION_TICKET, params.sessionTicket);
        }

        w.opaque16(ext.toByteArray());
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_CLIENT_HELLO, w.toByteArray());
    }

    /** The fields of a parsed ClientHello this engine needs. */
    static final class ClientHello {
        byte[] random;
        byte[] sessionId;
        List<Tls12CipherSuite> cipherSuites = new ArrayList<Tls12CipherSuite>();
        /**
         * True if the client offered {@link #TLS_EMPTY_RENEGOTIATION_INFO_SCSV}
         * among its cipher suites -- checked separately from
         * {@link #cipherSuites} since it is not a real suite this engine
         * could ever select, just a signal a peer without extension
         * support might send instead of {@code renegotiation_info}.
         */
        boolean emptyRenegotiationScsvOffered;
        /**
         * Parsed but not currently consulted -- this engine's own signing
         * choice is driven by its configured key type only, not the
         * peer's offer (mirrors {@link HandshakeMessages.ClientHello}'s
         * equivalent field and {@code Tls12HandshakeEngine}'s reasoning).
         */
        List<SignatureScheme> signatureAlgorithms = new ArrayList<SignatureScheme>();
        List<String> alpnProtocols = new ArrayList<String>();
        String serverName;
        /** {@code null} if absent; empty if advertised with nothing offered; else an offered ticket. */
        byte[] sessionTicket;
        boolean extendedMasterSecret;
        /** Raw {@code renegotiation_info} extension_data, or null if absent. */
        byte[] renegotiationInfo;
        /** Raw two-octet version codes from {@code supported_versions}, or null if absent. */
        List<Integer> supportedVersions;
        /** Present only in DTLS ClientHello (RFC 6347 section 4.2.1). */
        byte[] dtlsCookie;
    }

    static ClientHello parseClientHello(byte[] fullMessage) throws HandshakeFormatException {
        return parseClientHello(fullMessage, false);
    }

    static ClientHello parseClientHello(byte[] fullMessage, boolean dtlsTransport)
            throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_CLIENT_HELLO);
        WireReader body = r.slice(r.u24());
        return parseClientHelloBody(body, dtlsTransport);
    }

    private static ClientHello parseClientHelloBody(WireReader body) throws HandshakeFormatException {
        return parseClientHelloBody(body, false);
    }

    private static ClientHello parseClientHelloBody(WireReader body, boolean dtlsTransport)
            throws HandshakeFormatException {
        ClientHello ch = new ClientHello();
        body.u16(); // legacy_version -- the caller already committed to TLS 1.2 before dispatch
        ch.random = body.bytes(32);
        ch.sessionId = body.opaque8();
        if (dtlsTransport) {
            ch.dtlsCookie = body.opaque8();
        }

        WireReader csr = new WireReader(body.opaque16());
        while (csr.hasRemaining()) {
            int code = csr.u16();
            if (code == TLS_EMPTY_RENEGOTIATION_INFO_SCSV) {
                ch.emptyRenegotiationScsvOffered = true;
                continue;
            }
            Tls12CipherSuite suite = Tls12CipherSuite.fromCode(code);
            if (suite != null) {
                ch.cipherSuites.add(suite);
            }
        }
        body.opaque8(); // compression_methods, ignored

        if (body.hasRemaining()) {
            WireReader er = new WireReader(body.opaque16());
            while (er.hasRemaining()) {
                int extType = er.u16();
                byte[] extBody = er.opaque16();
                parseClientHelloExtension(ch, extType, extBody);
            }
        }
        return ch;
    }

    private static void parseClientHelloExtension(ClientHello ch, int extType, byte[] extBody)
            throws HandshakeFormatException {
        switch (extType) {
            case EXT_SIGNATURE_ALGORITHMS: {
                WireReader sr = new WireReader(new WireReader(extBody).opaque16());
                while (sr.hasRemaining()) {
                    SignatureScheme s = SignatureScheme.fromCode(sr.u16());
                    if (s != null) {
                        ch.signatureAlgorithms.add(s);
                    }
                }
                break;
            }
            case EXT_SERVER_NAME: {
                WireReader nr = new WireReader(new WireReader(extBody).opaque16());
                while (nr.hasRemaining()) {
                    int nameType = nr.u8();
                    byte[] nameBytes = nr.opaque16();
                    if (nameType == SERVER_NAME_TYPE_HOST_NAME && ch.serverName == null) {
                        ch.serverName = new String(nameBytes, StandardCharsets.US_ASCII);
                    }
                }
                break;
            }
            case EXT_ALPN: {
                WireReader ar = new WireReader(new WireReader(extBody).opaque16());
                while (ar.hasRemaining()) {
                    ch.alpnProtocols.add(new String(ar.opaque8(), StandardCharsets.US_ASCII));
                }
                break;
            }
            case EXT_SESSION_TICKET:
                ch.sessionTicket = extBody;
                break;
            case EXT_EXTENDED_MASTER_SECRET:
                ch.extendedMasterSecret = true;
                break;
            case EXT_RENEGOTIATION_INFO:
                ch.renegotiationInfo = extBody;
                break;
            case EXT_SUPPORTED_VERSIONS: {
                WireReader vr = new WireReader(new WireReader(extBody).opaque8());
                ch.supportedVersions = new ArrayList<Integer>();
                while (vr.hasRemaining()) {
                    ch.supportedVersions.add(vr.u16());
                }
                break;
            }
            default:
                break;
        }
    }

    // ---- ServerHello (RFC 5246 section 7.4.1.3) ----

    /**
     * Builds a ServerHello. {@code sessionTicket} true signals RFC 5077
     * section 3.2's echo -- the server MUST send it here for a client to
     * know to expect a {@code NewSessionTicket} message later in this same
     * handshake; a spec-conformant client otherwise treats an
     * unadvertised one as a protocol violation.
     */
    static byte[] buildServerHello(byte[] random, byte[] sessionId, Tls12CipherSuite cipherSuite,
            boolean sessionTicket, boolean extendedMasterSecret, String alpnProtocol) {
        WireWriter w = new WireWriter();
        w.u16(TLS_1_2_LEGACY_VERSION);
        w.bytes(random);
        w.opaque8(sessionId);
        w.u16(cipherSuite.getCode());
        w.u8(0); // compression_method: null

        WireWriter ext = new WireWriter();
        writeExtension(ext, EXT_RENEGOTIATION_INFO, new byte[] { 0 });
        if (extendedMasterSecret) {
            writeExtension(ext, EXT_EXTENDED_MASTER_SECRET, new byte[0]);
        }
        writeExtension(ext, EXT_EC_POINT_FORMATS, new byte[] { 1, EC_POINT_FORMAT_UNCOMPRESSED });
        if (sessionTicket) {
            writeExtension(ext, EXT_SESSION_TICKET, new byte[0]);
        }
        if (alpnProtocol != null) {
            List<String> single = new ArrayList<String>(1);
            single.add(alpnProtocol);
            HandshakeMessages.writeAlpnExtension(ext, single);
        }
        w.opaque16(ext.toByteArray());
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_SERVER_HELLO, w.toByteArray());
    }

    /** The fields of a parsed ServerHello. */
    static final class ServerHello {
        byte[] random;
        byte[] sessionId;
        Tls12CipherSuite cipherSuite;
        /** RFC 5077 section 3.2 -- true if a {@code NewSessionTicket} message follows later in this handshake. */
        boolean sessionTicketOffered;
        boolean extendedMasterSecret;
        byte[] renegotiationInfo;
        String alpnProtocol;
    }

    static ServerHello parseServerHello(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_SERVER_HELLO);
        WireReader body = r.slice(r.u24());

        ServerHello sh = new ServerHello();
        body.u16();
        sh.random = body.bytes(32);
        sh.sessionId = body.opaque8();
        int suiteCode = body.u16();
        sh.cipherSuite = Tls12CipherSuite.fromCode(suiteCode);
        if (sh.cipherSuite == null) {
            throw new HandshakeFormatException("Unsupported cipher suite in ServerHello: " + suiteCode);
        }
        body.u8(); // compression_method, always null here

        if (body.hasRemaining()) {
            WireReader er = new WireReader(body.opaque16());
            while (er.hasRemaining()) {
                int extType = er.u16();
                byte[] extBody = er.opaque16();
                switch (extType) {
                    case EXT_SESSION_TICKET:
                        sh.sessionTicketOffered = true;
                        break;
                    case EXT_EXTENDED_MASTER_SECRET:
                        sh.extendedMasterSecret = true;
                        break;
                    case EXT_RENEGOTIATION_INFO:
                        sh.renegotiationInfo = extBody;
                        break;
                    case EXT_ALPN: {
                        WireReader ar = new WireReader(new WireReader(extBody).opaque16());
                        if (ar.hasRemaining()) {
                            sh.alpnProtocol = new String(ar.opaque8(), StandardCharsets.US_ASCII);
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        }
        return sh;
    }

    // ---- Certificate (RFC 5246 section 7.4.2 -- no per-entry extensions, unlike TLS 1.3) ----

    static byte[] buildCertificate(List<byte[]> derChain) {
        WireWriter certList = new WireWriter();
        for (int i = 0; i < derChain.size(); i++) {
            certList.opaque24(derChain.get(i));
        }
        WireWriter w = new WireWriter();
        w.opaque24(certList.toByteArray());
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_CERTIFICATE, w.toByteArray());
    }

    static List<byte[]> parseCertificate(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_CERTIFICATE);
        WireReader body = r.slice(r.u24());
        WireReader certList = new WireReader(body.opaque24());
        List<byte[]> chain = new ArrayList<byte[]>();
        while (certList.hasRemaining()) {
            chain.add(certList.opaque24());
        }
        return chain;
    }

    // ---- ServerKeyExchange (RFC 4492 section 5.4 -- named-curve ECDHE only) ----

    /** The {@code ServerECDHParams} bytes the signature actually covers. */
    static byte[] serverEcdhParamsBytes(byte[] ecPoint) {
        WireWriter w = new WireWriter();
        w.u8(EC_CURVE_TYPE_NAMED_CURVE);
        w.u16(NAMED_CURVE_SECP256R1);
        w.opaque8(ecPoint);
        return w.toByteArray();
    }

    static byte[] buildServerKeyExchange(byte[] ecPoint, SignatureScheme scheme, byte[] signature) {
        WireWriter w = new WireWriter();
        w.bytes(serverEcdhParamsBytes(ecPoint));
        w.u16(scheme.getCode());
        w.opaque16(signature);
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_SERVER_KEY_EXCHANGE, w.toByteArray());
    }

    /** Parsed ServerKeyExchange (named-curve secp256r1 ECDHE only -- the only key exchange this engine speaks). */
    static final class ServerKeyExchange {
        byte[] ecPoint;
        SignatureScheme scheme;
        byte[] signature;
        /** The exact {@code ServerECDHParams} bytes the signature covers, for verification. */
        byte[] signedParams;
    }

    static ServerKeyExchange parseServerKeyExchange(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_SERVER_KEY_EXCHANGE);
        WireReader body = r.slice(r.u24());

        int curveType = body.u8();
        if (curveType != EC_CURVE_TYPE_NAMED_CURVE) {
            throw new HandshakeFormatException("Unsupported ECCurveType (explicit curves not supported): " + curveType);
        }
        int curve = body.u16();
        if (curve != NAMED_CURVE_SECP256R1) {
            throw new HandshakeFormatException("Unsupported named curve: " + curve);
        }
        byte[] ecPoint = body.opaque8();
        int schemeCode = body.u16();
        SignatureScheme scheme = SignatureScheme.fromCode(schemeCode);
        if (scheme == null) {
            throw new HandshakeFormatException("Unrecognised SignatureAndHashAlgorithm: " + schemeCode);
        }
        byte[] signature = body.opaque16();

        ServerKeyExchange ske = new ServerKeyExchange();
        ske.ecPoint = ecPoint;
        ske.scheme = scheme;
        ske.signature = signature;
        ske.signedParams = serverEcdhParamsBytes(ecPoint);
        return ske;
    }

    // ---- CertificateRequest (RFC 5246 section 7.4.4 -- mTLS) ----

    static byte[] buildCertificateRequest(List<SignatureScheme> signatureAlgorithms) {
        WireWriter w = new WireWriter();
        w.opaque8(new byte[] { 1, 64 }); // ClientCertificateType: rsa_sign(1), ecdsa_sign(64) (RFC 4492 section 5.5)
        WireWriter sigAlgs = new WireWriter();
        for (int i = 0; i < signatureAlgorithms.size(); i++) {
            sigAlgs.u16(signatureAlgorithms.get(i).getCode());
        }
        w.opaque16(sigAlgs.toByteArray());
        w.opaque16(new byte[0]); // certificate_authorities: empty (accept any CA)
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_CERTIFICATE_REQUEST, w.toByteArray());
    }

    /**
     * Validates a CertificateRequest body without extracting anything from
     * it -- this engine always offers the caller's single configured
     * client certificate (or none) regardless of {@code certificate_authorities}
     * or the requested types, so only well-formedness matters.
     */
    static void parseCertificateRequest(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_CERTIFICATE_REQUEST);
        WireReader body = r.slice(r.u24());
        body.opaque8();
        new WireReader(body.opaque16());
        body.opaque16();
    }

    // ---- ServerHelloDone (RFC 5246 section 7.4.5 -- empty body) ----

    static byte[] buildServerHelloDone() {
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_SERVER_HELLO_DONE, new byte[0]);
    }

    static void parseServerHelloDone(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_SERVER_HELLO_DONE);
        int len = r.u24();
        if (len != 0) {
            throw new HandshakeFormatException("ServerHelloDone must have an empty body, got " + len + " bytes");
        }
    }

    // ---- ClientKeyExchange (RFC 4492 section 5.7 -- ECDHE only) ----

    static byte[] buildClientKeyExchange(byte[] ecPoint) {
        WireWriter w = new WireWriter();
        w.opaque8(ecPoint);
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE, w.toByteArray());
    }

    static byte[] parseClientKeyExchange(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE);
        WireReader body = r.slice(r.u24());
        return body.opaque8();
    }

    // ---- CertificateVerify (RFC 5246 section 7.4.8) ----

    static byte[] buildCertificateVerify(SignatureScheme scheme, byte[] signature) {
        WireWriter w = new WireWriter();
        w.u16(scheme.getCode());
        w.opaque16(signature);
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_CERTIFICATE_VERIFY, w.toByteArray());
    }

    static final class CertificateVerify {
        SignatureScheme scheme;
        byte[] signature;
    }

    static CertificateVerify parseCertificateVerify(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_CERTIFICATE_VERIFY);
        WireReader body = r.slice(r.u24());
        int schemeCode = body.u16();
        SignatureScheme scheme = SignatureScheme.fromCode(schemeCode);
        if (scheme == null) {
            throw new HandshakeFormatException("Unrecognised SignatureAndHashAlgorithm: " + schemeCode);
        }
        byte[] signature = body.opaque16();
        CertificateVerify cv = new CertificateVerify();
        cv.scheme = scheme;
        cv.signature = signature;
        return cv;
    }

    // ---- Finished (RFC 5246 section 7.4.9) ----

    static byte[] buildFinished(byte[] verifyData) {
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_FINISHED, verifyData);
    }

    static byte[] parseFinished(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_FINISHED);
        WireReader body = r.slice(r.u24());
        return body.bytes(body.remaining());
    }

    // ---- NewSessionTicket (RFC 5077 section 3.3) ----

    static byte[] buildNewSessionTicket(long lifetimeHintSeconds, byte[] ticket) {
        WireWriter w = new WireWriter();
        w.u32((int) lifetimeHintSeconds);
        w.opaque16(ticket);
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_NEW_SESSION_TICKET, w.toByteArray());
    }

    static final class NewSessionTicket {
        long lifetimeHintSeconds;
        byte[] ticket;
    }

    static NewSessionTicket parseNewSessionTicket(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_NEW_SESSION_TICKET);
        WireReader body = r.slice(r.u24());
        NewSessionTicket nst = new NewSessionTicket();
        nst.lifetimeHintSeconds = body.u32() & 0xFFFFFFFFL;
        nst.ticket = body.opaque16();
        return nst;
    }

    // ---- shared helpers ----

    private static void requireType(WireReader r, int expectedType) throws HandshakeFormatException {
        int type = r.u8();
        if (type != expectedType) {
            throw new HandshakeFormatException("Expected handshake type " + expectedType + ", got " + type);
        }
    }

    private static void writeExtension(WireWriter out, int type, byte[] body) {
        out.u16(type);
        out.opaque16(body);
    }

    private static void writeServerNameExtension(WireWriter ext, String serverName) {
        WireWriter name = new WireWriter();
        name.u8(SERVER_NAME_TYPE_HOST_NAME);
        name.opaque16(serverName.getBytes(StandardCharsets.US_ASCII));
        WireWriter list = new WireWriter();
        list.opaque16(name.toByteArray());
        writeExtension(ext, EXT_SERVER_NAME, list.toByteArray());
    }

    private static void writeSupportedGroupsExtension(WireWriter ext) {
        WireWriter list = new WireWriter();
        list.u16(NAMED_CURVE_SECP256R1);
        WireWriter body = new WireWriter();
        body.opaque16(list.toByteArray());
        writeExtension(ext, EXT_SUPPORTED_GROUPS, body.toByteArray());
    }

    private static void writeSignatureAlgorithmsExtension(WireWriter ext, List<SignatureScheme> schemes) {
        WireWriter list = new WireWriter();
        for (int i = 0; i < schemes.size(); i++) {
            list.u16(schemes.get(i).getCode());
        }
        WireWriter body = new WireWriter();
        body.opaque16(list.toByteArray());
        writeExtension(ext, EXT_SIGNATURE_ALGORITHMS, body.toByteArray());
    }

}
