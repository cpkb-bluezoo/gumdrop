/*
 * HandshakeMessages.java
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.crypto.SignatureScheme;

/**
 * Wire encoding and decoding of the TLS 1.3 handshake messages and
 * extensions {@link HandshakeEngine} needs for a full 1-RTT handshake
 * plus HelloRetryRequest, session resumption/PSK/0-RTT, and
 * post-handshake NewSessionTicket (RFC 8446 section 4), each message
 * built or parsed as a complete, framed unit (one-octet type, three-octet
 * length, then content -- exactly what
 * {@code CryptoStreamBuffer.receiveAndExtractMessages} already hands the
 * engine, and what the engine hands to {@code Transcript.update}
 * unchanged).
 *
 * <p>Package-private: this is {@link HandshakeEngine}'s own wire layer,
 * not a public API in its own right.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4">RFC 8446 section 4</a>
 */
final class HandshakeMessages {

    static final int HANDSHAKE_TYPE_CLIENT_HELLO = 1;
    static final int HANDSHAKE_TYPE_SERVER_HELLO = 2;
    static final int HANDSHAKE_TYPE_NEW_SESSION_TICKET = 4;
    static final int HANDSHAKE_TYPE_ENCRYPTED_EXTENSIONS = 8;
    static final int HANDSHAKE_TYPE_CERTIFICATE = 11;
    static final int HANDSHAKE_TYPE_CERTIFICATE_REQUEST = 13;
    static final int HANDSHAKE_TYPE_CERTIFICATE_VERIFY = 15;
    static final int HANDSHAKE_TYPE_FINISHED = 20;

    /**
     * RFC 8446 section 4.6.3 -- post-handshake, TCP-TLS-1.3-only (RFC
     * 9001 section 4.6 forbids {@code KeyUpdate} over QUIC entirely,
     * since QUIC has its own separate packet-level key update); see
     * {@link HandshakeEngine#requestKeyUpdate}.
     */
    static final int HANDSHAKE_TYPE_KEY_UPDATE = 24;

    private static final int EXT_SERVER_NAME = 0x0000;
    private static final int EXT_SUPPORTED_GROUPS = 0x000a;
    private static final int EXT_SIGNATURE_ALGORITHMS = 0x000d;
    private static final int EXT_ALPN = 0x0010;
    private static final int EXT_SUPPORTED_VERSIONS = 0x002b;
    private static final int EXT_PRE_SHARED_KEY = 0x0029;
    private static final int EXT_EARLY_DATA = 0x002a;
    private static final int EXT_COOKIE = 0x002c;
    private static final int EXT_PSK_KEY_EXCHANGE_MODES = 0x002d;
    private static final int EXT_KEY_SHARE = 0x0033;

    /** RFC 9001 section 8.2. */
    static final int EXT_QUIC_TRANSPORT_PARAMETERS = 0x0039;

    /** RFC 8446 section 4.2.9 -- the only key exchange mode this engine ever offers. */
    private static final int PSK_DHE_KE = 1;

    private static final int TLS_1_3 = 0x0304;
    private static final int TLS_1_2_LEGACY_VERSION = 0x0303;
    private static final int SERVER_NAME_TYPE_HOST_NAME = 0;

    /**
     * The fixed {@code ServerHello.random} value that marks a message as
     * a HelloRetryRequest rather than a real ServerHello (RFC 8446
     * section 4.1.3) -- {@code SHA-256("HelloRetryRequest")}.
     * Wire-identical to ServerHello (handshake type 2); this is the only
     * thing that distinguishes them.
     */
    static final byte[] HELLO_RETRY_REQUEST_RANDOM = {
        (byte) 0xcf, (byte) 0x21, (byte) 0xad, (byte) 0x74, (byte) 0xe5, (byte) 0x9a, (byte) 0x61, (byte) 0x11,
        (byte) 0xbe, (byte) 0x1d, (byte) 0x8c, (byte) 0x02, (byte) 0x1e, (byte) 0x65, (byte) 0xb8, (byte) 0x91,
        (byte) 0xc2, (byte) 0xa2, (byte) 0x11, (byte) 0x16, (byte) 0x7a, (byte) 0xbb, (byte) 0x8c, (byte) 0x5e,
        (byte) 0x07, (byte) 0x9e, (byte) 0x09, (byte) 0xe2, (byte) 0xc8, (byte) 0xa8, (byte) 0x33, (byte) 0x9c
    };

    private HandshakeMessages() {
    }

    // ---- ClientHello (RFC 8446 section 4.1.2) ----

    /**
     * The inputs to build a ClientHello -- a mutable holder rather than a
     * long positional parameter list, now that PSK/0-RTT fields have
     * pushed the count past ten. Not reused across builds; callers create
     * one per ClientHello (including a followup after HelloRetryRequest).
     */
    static final class ClientHelloParams {
        byte[] random;
        List<CipherSuite> cipherSuites;
        List<NamedGroup> groups;
        Map<NamedGroup, byte[]> keyShares;
        List<SignatureScheme> signatureAlgorithms;
        List<String> applicationProtocols;
        String serverName;
        byte[] quicTransportParameters;
        /** Echoed verbatim from a HelloRetryRequest's cookie extension, or null on an initial ClientHello. */
        byte[] cookie;
        /** Opaque ticket identity to offer, or null for no PSK offer. */
        byte[] pskIdentity;
        int obfuscatedTicketAge;
        /** True to request 0-RTT -- only meaningful alongside a non-null {@link #pskIdentity}. */
        boolean earlyDataRequested;
    }

    /**
     * Builds the truncated encoding of a ClientHello used to compute a
     * PSK binder (RFC 8446 section 4.2.11.2): the complete message
     * through the binders' 2-byte length prefix, but not the binder
     * bytes themselves. Only meaningful when {@code params.pskIdentity}
     * is set.
     *
     * <p>Implemented as the full message built with an all-zero
     * placeholder binder, then truncated -- every length field in a
     * ClientHello with a PSK offer is fixed by the binder's length (32
     * bytes), never its content, so a placeholder produces byte-identical
     * framing to the eventual real message; slicing off exactly the last
     * 33 bytes (a 1-byte binder-entry length plus the 32-byte binder)
     * recovers the truncated form. Server-side binder verification
     * reuses this same fact directly on the raw received bytes rather
     * than rebuilding anything -- see {@link HandshakeEngine}.
     *
     * @param params the ClientHello to build, with {@code pskIdentity} set
     * @return the truncated framed message
     */
    static byte[] buildClientHelloTruncatedForBinder(ClientHelloParams params) {
        byte[] full = buildClientHelloBody(params, new byte[32]);
        return Arrays.copyOfRange(full, 0, full.length - 33);
    }

    /**
     * Builds a complete, framed ClientHello. When {@code params.pskIdentity}
     * is set, {@code binder} must be the real 32-byte PSK binder computed
     * over {@link #buildClientHelloTruncatedForBinder}'s output (via
     * {@link KeySchedule#computePskBinder}); when {@code params.pskIdentity}
     * is null, {@code binder} is ignored (pass null) and no
     * {@code pre_shared_key} extension is written at all -- the same code
     * path serves both the resumptive and full-handshake cases.
     *
     * @param params the ClientHello to build
     * @param binder the real PSK binder, or null if no PSK is offered
     * @return the complete framed message
     */
    static byte[] buildClientHelloWithBinder(ClientHelloParams params, byte[] binder) {
        return buildClientHelloBody(params, params.pskIdentity != null ? binder : null);
    }

    private static byte[] buildClientHelloBody(ClientHelloParams params, byte[] binder) {
        WireWriter w = new WireWriter();
        w.u16(TLS_1_2_LEGACY_VERSION);
        w.bytes(params.random);
        w.opaque8(new byte[0]);

        WireWriter cs = new WireWriter();
        for (int i = 0; i < params.cipherSuites.size(); i++) {
            cs.u16(params.cipherSuites.get(i).getCode());
        }
        w.opaque16(cs.toByteArray());

        w.opaque8(new byte[] { 0 });

        WireWriter ext = new WireWriter();
        if (params.serverName != null) {
            writeServerNameExtension(ext, params.serverName);
        }
        writeSupportedGroupsExtension(ext, params.groups);
        writeSignatureAlgorithmsExtension(ext, params.signatureAlgorithms);
        if (!params.applicationProtocols.isEmpty()) {
            writeAlpnExtension(ext, params.applicationProtocols);
        }
        writeSupportedVersionsClientExtension(ext);
        writeKeyShareClientExtension(ext, params.groups, params.keyShares);
        if (params.quicTransportParameters != null) {
            writeExtension(ext, EXT_QUIC_TRANSPORT_PARAMETERS, params.quicTransportParameters);
        }
        if (params.earlyDataRequested) {
            writeExtension(ext, EXT_EARLY_DATA, new byte[0]);
        }
        if (params.pskIdentity != null) {
            WireWriter modes = new WireWriter();
            modes.opaque8(new byte[] { PSK_DHE_KE });
            writeExtension(ext, EXT_PSK_KEY_EXCHANGE_MODES, modes.toByteArray());
        }
        if (params.cookie != null) {
            writeExtension(ext, EXT_COOKIE, params.cookie);
        }
        if (params.pskIdentity != null) {
            // pre_shared_key MUST be the last extension (RFC 8446 section 4.2.11).
            WireWriter identity = new WireWriter();
            identity.opaque16(params.pskIdentity);
            identity.u32(params.obfuscatedTicketAge);
            WireWriter idList = new WireWriter();
            idList.opaque16(identity.toByteArray());

            WireWriter binderEntry = new WireWriter();
            binderEntry.opaque8(binder);
            WireWriter binders = new WireWriter();
            binders.opaque16(binderEntry.toByteArray());

            WireWriter pskExt = new WireWriter();
            pskExt.bytes(idList.toByteArray());
            pskExt.bytes(binders.toByteArray());
            writeExtension(ext, EXT_PRE_SHARED_KEY, pskExt.toByteArray());
        }
        w.opaque16(ext.toByteArray());

        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_CLIENT_HELLO, w.toByteArray());
    }

    /** The fields of a parsed ClientHello this engine actually needs. */
    static final class ClientHello {
        byte[] random;
        byte[] legacySessionId;
        List<CipherSuite> cipherSuites = new ArrayList<CipherSuite>();
        List<NamedGroup> supportedGroups = new ArrayList<NamedGroup>();
        List<SignatureScheme> signatureAlgorithms = new ArrayList<SignatureScheme>();
        Map<NamedGroup, byte[]> keyShares = new LinkedHashMap<NamedGroup, byte[]>();
        List<String> alpnProtocols = new ArrayList<String>();
        String serverName;
        byte[] quicTransportParameters;
        boolean supportsTls13;
        /** From the {@code pre_shared_key} extension's single identity, or null if not offered. */
        byte[] pskIdentity;
        int obfuscatedTicketAge;
        /** The single binder entry corresponding to {@link #pskIdentity}, or null. */
        byte[] pskBinder;
        boolean earlyDataRequested;
        /** Echoed from a prior HelloRetryRequest, or null. */
        byte[] cookie;
    }

    static ClientHello parseClientHello(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_CLIENT_HELLO);
        WireReader body = r.slice(r.u24());
        return parseClientHelloBody(body);
    }

    private static ClientHello parseClientHelloBody(WireReader body) throws HandshakeFormatException {
        ClientHello ch = new ClientHello();
        body.u16();
        ch.random = body.bytes(32);
        ch.legacySessionId = body.opaque8();
        WireReader csr = new WireReader(body.opaque16());
        while (csr.hasRemaining()) {
            CipherSuite suite = CipherSuite.fromCode(csr.u16());
            if (suite != null) {
                ch.cipherSuites.add(suite);
            }
        }
        body.opaque8();

        WireReader er = new WireReader(body.opaque16());
        while (er.hasRemaining()) {
            int extType = er.u16();
            byte[] extBody = er.opaque16();
            parseClientHelloExtension(ch, extType, extBody);
        }
        return ch;
    }

    private static void parseClientHelloExtension(ClientHello ch, int extType, byte[] extBody)
            throws HandshakeFormatException {
        switch (extType) {
            case EXT_SUPPORTED_GROUPS: {
                WireReader gr = new WireReader(new WireReader(extBody).opaque16());
                while (gr.hasRemaining()) {
                    NamedGroup g = NamedGroup.fromCode(gr.u16());
                    if (g != null) {
                        ch.supportedGroups.add(g);
                    }
                }
                break;
            }
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
            case EXT_KEY_SHARE: {
                WireReader kr = new WireReader(new WireReader(extBody).opaque16());
                while (kr.hasRemaining()) {
                    NamedGroup g = NamedGroup.fromCode(kr.u16());
                    byte[] share = kr.opaque16();
                    if (g != null) {
                        ch.keyShares.put(g, share);
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
            case EXT_SUPPORTED_VERSIONS: {
                WireReader vr = new WireReader(new WireReader(extBody).opaque8());
                while (vr.hasRemaining()) {
                    if (vr.u16() == TLS_1_3) {
                        ch.supportsTls13 = true;
                    }
                }
                break;
            }
            case EXT_QUIC_TRANSPORT_PARAMETERS:
                ch.quicTransportParameters = extBody;
                break;
            case EXT_EARLY_DATA:
                ch.earlyDataRequested = true;
                break;
            case EXT_COOKIE:
                ch.cookie = extBody;
                break;
            case EXT_PRE_SHARED_KEY: {
                // offered_psks: PskIdentity identities<7..2^16-1>; PskBinderEntry binders<33..2^16-1>;
                // This engine only ever offers one identity (matching hopf); take the first of each.
                WireReader outer = new WireReader(extBody);
                WireReader idr = new WireReader(outer.opaque16());
                if (idr.hasRemaining()) {
                    ch.pskIdentity = idr.opaque16();
                    ch.obfuscatedTicketAge = idr.u32();
                }
                WireReader br = new WireReader(outer.opaque16());
                if (br.hasRemaining()) {
                    ch.pskBinder = br.opaque8();
                }
                break;
            }
            default:
                break;
        }
    }

    // ---- ServerHello (RFC 8446 section 4.1.3) ----

    static byte[] buildServerHello(byte[] random, byte[] legacySessionIdEcho, CipherSuite cipherSuite,
            NamedGroup group, byte[] keyShareBytes, boolean pskSelected) {
        WireWriter w = new WireWriter();
        w.u16(TLS_1_2_LEGACY_VERSION);
        w.bytes(random);
        w.opaque8(legacySessionIdEcho);
        w.u16(cipherSuite.getCode());
        w.u8(0);

        WireWriter ext = new WireWriter();
        WireWriter sv = new WireWriter();
        sv.u16(TLS_1_3);
        writeExtension(ext, EXT_SUPPORTED_VERSIONS, sv.toByteArray());

        WireWriter ks = new WireWriter();
        ks.u16(group.getCode());
        ks.opaque16(keyShareBytes);
        writeExtension(ext, EXT_KEY_SHARE, ks.toByteArray());

        if (pskSelected) {
            // This engine only ever offers/accepts one PSK identity, index 0.
            WireWriter idx = new WireWriter();
            idx.u16(0);
            writeExtension(ext, EXT_PRE_SHARED_KEY, idx.toByteArray());
        }

        w.opaque16(ext.toByteArray());
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_SERVER_HELLO, w.toByteArray());
    }

    static final class ServerHello {
        byte[] random;
        CipherSuite cipherSuite;
        NamedGroup keyShareGroup;
        byte[] keyShareData;
        boolean selectedTls13;
        boolean pskSelected;
    }

    /**
     * Returns whether a ServerHello-shaped message (handshake type 2) is
     * actually a HelloRetryRequest -- the two are wire-identical except
     * for the fixed {@link #HELLO_RETRY_REQUEST_RANDOM} value, so callers
     * must check this before choosing between {@link #parseServerHello}
     * and {@link #parseHelloRetryRequest}.
     *
     * @param fullMessage the complete framed message
     * @return true if this is a HelloRetryRequest
     */
    static boolean isHelloRetryRequest(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_SERVER_HELLO);
        WireReader body = r.slice(r.u24());
        body.u16();
        byte[] random = body.bytes(32);
        return Arrays.equals(random, HELLO_RETRY_REQUEST_RANDOM);
    }

    static ServerHello parseServerHello(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_SERVER_HELLO);
        WireReader body = r.slice(r.u24());

        ServerHello sh = new ServerHello();
        body.u16();
        sh.random = body.bytes(32);
        body.opaque8();
        sh.cipherSuite = CipherSuite.fromCode(body.u16());
        body.u8();

        WireReader er = new WireReader(body.opaque16());
        while (er.hasRemaining()) {
            int extType = er.u16();
            byte[] extBody = er.opaque16();
            if (extType == EXT_KEY_SHARE) {
                WireReader kr = new WireReader(extBody);
                sh.keyShareGroup = NamedGroup.fromCode(kr.u16());
                sh.keyShareData = kr.opaque16();
            } else if (extType == EXT_SUPPORTED_VERSIONS) {
                WireReader vr = new WireReader(extBody);
                sh.selectedTls13 = (vr.u16() == TLS_1_3);
            } else if (extType == EXT_PRE_SHARED_KEY) {
                sh.pskSelected = true;
            }
        }
        return sh;
    }

    // ---- HelloRetryRequest (RFC 8446 section 4.1.4) ----

    /**
     * Builds a HelloRetryRequest -- wire-identical to ServerHello (same
     * handshake type, 2) but with {@code random} fixed to
     * {@link #HELLO_RETRY_REQUEST_RANDOM} and a {@code key_share}
     * extension carrying only the requested group's code (RFC 8446
     * section 4.2.8's {@code KeyShareHelloRetryRequest} -- no key bytes,
     * unlike a real ServerHello's {@code key_share}).
     *
     * @param legacySessionIdEcho the client's {@code legacy_session_id}, echoed back
     * @param cipherSuite the cipher suite (RFC 8446 section 4.1.4 still requires one)
     * @param selectedGroup the group the followup ClientHello must offer a share for
     * @param cookie a cookie to echo back verbatim in the followup ClientHello, or null
     * @return the complete framed message
     */
    static byte[] buildHelloRetryRequest(byte[] legacySessionIdEcho, CipherSuite cipherSuite,
            NamedGroup selectedGroup, byte[] cookie) {
        WireWriter w = new WireWriter();
        w.u16(TLS_1_2_LEGACY_VERSION);
        w.bytes(HELLO_RETRY_REQUEST_RANDOM);
        w.opaque8(legacySessionIdEcho);
        w.u16(cipherSuite.getCode());
        w.u8(0);

        WireWriter ext = new WireWriter();
        WireWriter sv = new WireWriter();
        sv.u16(TLS_1_3);
        writeExtension(ext, EXT_SUPPORTED_VERSIONS, sv.toByteArray());

        WireWriter ks = new WireWriter();
        ks.u16(selectedGroup.getCode());
        writeExtension(ext, EXT_KEY_SHARE, ks.toByteArray());

        if (cookie != null) {
            writeExtension(ext, EXT_COOKIE, cookie);
        }
        w.opaque16(ext.toByteArray());
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_SERVER_HELLO, w.toByteArray());
    }

    static final class HelloRetryRequest {
        CipherSuite cipherSuite;
        NamedGroup selectedGroup;
        byte[] cookie;
    }

    static HelloRetryRequest parseHelloRetryRequest(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_SERVER_HELLO);
        WireReader body = r.slice(r.u24());

        HelloRetryRequest hrr = new HelloRetryRequest();
        body.u16();
        body.bytes(32);
        body.opaque8();
        hrr.cipherSuite = CipherSuite.fromCode(body.u16());
        body.u8();

        WireReader er = new WireReader(body.opaque16());
        while (er.hasRemaining()) {
            int extType = er.u16();
            byte[] extBody = er.opaque16();
            if (extType == EXT_KEY_SHARE) {
                WireReader kr = new WireReader(extBody);
                hrr.selectedGroup = NamedGroup.fromCode(kr.u16());
            } else if (extType == EXT_COOKIE) {
                hrr.cookie = extBody;
            }
        }
        return hrr;
    }

    // ---- EncryptedExtensions (RFC 8446 section 4.3.1) ----

    static byte[] buildEncryptedExtensions(String selectedAlpn, byte[] quicTransportParameters,
            boolean earlyDataAccepted) {
        WireWriter ext = new WireWriter();
        if (selectedAlpn != null) {
            List<String> single = new ArrayList<String>();
            single.add(selectedAlpn);
            writeAlpnExtension(ext, single);
        }
        if (quicTransportParameters != null) {
            writeExtension(ext, EXT_QUIC_TRANSPORT_PARAMETERS, quicTransportParameters);
        }
        if (earlyDataAccepted) {
            writeExtension(ext, EXT_EARLY_DATA, new byte[0]);
        }
        WireWriter w = new WireWriter();
        w.opaque16(ext.toByteArray());
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_ENCRYPTED_EXTENSIONS, w.toByteArray());
    }

    static final class EncryptedExtensions {
        String selectedAlpn;
        byte[] quicTransportParameters;
        boolean earlyDataAccepted;
    }

    static EncryptedExtensions parseEncryptedExtensions(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_ENCRYPTED_EXTENSIONS);
        WireReader body = r.slice(r.u24());

        EncryptedExtensions ee = new EncryptedExtensions();
        WireReader er = new WireReader(body.opaque16());
        while (er.hasRemaining()) {
            int extType = er.u16();
            byte[] extBody = er.opaque16();
            if (extType == EXT_ALPN) {
                WireReader ar = new WireReader(new WireReader(extBody).opaque16());
                if (ar.hasRemaining()) {
                    ee.selectedAlpn = new String(ar.opaque8(), StandardCharsets.US_ASCII);
                }
            } else if (extType == EXT_QUIC_TRANSPORT_PARAMETERS) {
                ee.quicTransportParameters = extBody;
            } else if (extType == EXT_EARLY_DATA) {
                ee.earlyDataAccepted = true;
            }
        }
        return ee;
    }

    // ---- NewSessionTicket (RFC 8446 section 4.6.1) ----

    /**
     * Builds a NewSessionTicket. Post-handshake -- never joins the
     * handshake transcript, unlike every other message here.
     *
     * @param lifetimeSeconds the ticket lifetime, seconds
     * @param ageAdd the random {@code ticket_age_add} value
     * @param nonce the per-ticket random nonce, input to the resumption PSK derivation
     * @param ticket the opaque ticket identity (this server's own sealed payload)
     * @param maxEarlyDataSize the maximum 0-RTT data this ticket may be used for, or 0 for none
     * @return the complete framed message
     */
    static byte[] buildNewSessionTicket(int lifetimeSeconds, int ageAdd, byte[] nonce, byte[] ticket,
            int maxEarlyDataSize) {
        WireWriter body = new WireWriter();
        body.u32(lifetimeSeconds);
        body.u32(ageAdd);
        body.opaque8(nonce);
        body.opaque16(ticket);

        WireWriter ext = new WireWriter();
        if (maxEarlyDataSize > 0) {
            WireWriter ed = new WireWriter();
            ed.u32(maxEarlyDataSize);
            writeExtension(ext, EXT_EARLY_DATA, ed.toByteArray());
        }
        body.opaque16(ext.toByteArray());

        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_NEW_SESSION_TICKET, body.toByteArray());
    }

    static final class NewSessionTicket {
        int lifetimeSeconds;
        int ageAdd;
        byte[] nonce;
        byte[] ticket;
        int maxEarlyDataSize;
    }

    static NewSessionTicket parseNewSessionTicket(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_NEW_SESSION_TICKET);
        WireReader body = r.slice(r.u24());

        NewSessionTicket nst = new NewSessionTicket();
        nst.lifetimeSeconds = body.u32();
        nst.ageAdd = body.u32();
        nst.nonce = body.opaque8();
        nst.ticket = body.opaque16();

        WireReader er = new WireReader(body.opaque16());
        while (er.hasRemaining()) {
            int extType = er.u16();
            byte[] extBody = er.opaque16();
            if (extType == EXT_EARLY_DATA) {
                nst.maxEarlyDataSize = new WireReader(extBody).u32();
            }
        }
        return nst;
    }

    // ---- CertificateRequest (RFC 8446 section 4.3.2) ----

    /**
     * Builds a {@code CertificateRequest}, offering every
     * {@link SignatureScheme} -- this engine's mTLS support, unlike a real
     * peer's, never needs to consult what it offers here (it always signs
     * with whatever scheme its own key type maps to), only to send a
     * spec-conforming message. The context is always empty; it exists on
     * the wire purely so the client's {@code Certificate} response can
     * echo it back (RFC 8446 section 4.3.2), which this engine's own
     * client does even though it never receives more than one
     * {@code CertificateRequest} per handshake to need it for.
     *
     * @return the complete framed message
     */
    static byte[] buildCertificateRequest() {
        WireWriter body = new WireWriter();
        body.opaque8(new byte[0]);

        WireWriter ext = new WireWriter();
        List<SignatureScheme> schemes = new ArrayList<SignatureScheme>();
        for (SignatureScheme scheme : SignatureScheme.values()) {
            schemes.add(scheme);
        }
        writeSignatureAlgorithmsExtension(ext, schemes);
        body.opaque16(ext.toByteArray());

        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_CERTIFICATE_REQUEST, body.toByteArray());
    }

    /**
     * Parses a {@code CertificateRequest}, returning only its context --
     * the client's own signature algorithm selection is driven by its own
     * key type, not by what the server's {@code signature_algorithms}
     * extension lists, so there is nothing else in this message this
     * engine's client needs.
     *
     * @param fullMessage the complete framed message
     * @return the certificate request context, to echo back in the
     *         client's {@code Certificate} response
     */
    static byte[] parseCertificateRequest(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_CERTIFICATE_REQUEST);
        WireReader body = r.slice(r.u24());
        return body.opaque8();
    }

    // ---- Certificate (RFC 8446 section 4.4.2) ----

    /**
     * Builds a {@code Certificate} message. {@code context} is the
     * {@code certificate_request_context} (RFC 8446 section 4.4.2) --
     * always empty for the server's own unsolicited {@code Certificate},
     * or the exact bytes {@link #parseCertificateRequest} returned when
     * this is a client's response to one (in practice also always empty,
     * since {@link #buildCertificateRequest} never sends anything else --
     * threaded through explicitly anyway, rather than hardcoded, for
     * correctness against a non-gumdrop peer's {@code CertificateRequest}).
     *
     * @param context the certificate request context
     * @param derChain the DER-encoded certificate chain, leaf first
     * @return the complete framed message
     */
    static byte[] buildCertificate(byte[] context, List<byte[]> derChain) {
        WireWriter body = new WireWriter();
        body.opaque8(context);

        WireWriter certList = new WireWriter();
        for (int i = 0; i < derChain.size(); i++) {
            certList.opaque24(derChain.get(i));
            certList.opaque16(new byte[0]);
        }
        body.opaque24(certList.toByteArray());

        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_CERTIFICATE, body.toByteArray());
    }

    static List<byte[]> parseCertificate(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_CERTIFICATE);
        WireReader body = r.slice(r.u24());

        body.opaque8();
        WireReader cr = new WireReader(body.opaque24());
        List<byte[]> chain = new ArrayList<byte[]>();
        while (cr.hasRemaining()) {
            chain.add(cr.opaque24());
            cr.opaque16();
        }
        return chain;
    }

    /**
     * Parses just a {@code Certificate} message's
     * {@code certificate_request_context} -- used server-side to check a
     * client's response actually echoes the context its
     * {@code CertificateRequest} sent (RFC 8446 section 4.4.2: "This
     * field ... MUST match that of the CertificateRequest").
     *
     * @param fullMessage the complete framed message
     * @return the certificate request context
     */
    static byte[] parseCertificateContext(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_CERTIFICATE);
        WireReader body = r.slice(r.u24());
        return body.opaque8();
    }

    // ---- CertificateVerify (RFC 8446 section 4.4.3) ----

    static byte[] buildCertificateVerify(SignatureScheme scheme, byte[] signature) {
        WireWriter body = new WireWriter();
        body.u16(scheme.getCode());
        body.opaque16(signature);
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_CERTIFICATE_VERIFY, body.toByteArray());
    }

    static final class CertificateVerify {
        SignatureScheme scheme;
        byte[] signature;
    }

    static CertificateVerify parseCertificateVerify(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_CERTIFICATE_VERIFY);
        WireReader body = r.slice(r.u24());

        CertificateVerify cv = new CertificateVerify();
        int schemeCode = body.u16();
        cv.scheme = SignatureScheme.fromCode(schemeCode);
        if (cv.scheme == null) {
            throw new HandshakeFormatException("Unsupported signature scheme: 0x"
                    + Integer.toHexString(schemeCode));
        }
        cv.signature = body.opaque16();
        return cv;
    }

    /**
     * The exact bytes a {@code CertificateVerify} signature covers (RFC
     * 8446 section 4.4.3): 64 spaces, a role-specific context string, a
     * zero separator byte, then the transcript hash up to (not
     * including) this message.
     *
     * @param serverRole true if signing/verifying the server's
     *                   CertificateVerify, false for the client's
     * @param transcriptHash the transcript hash up to this message
     * @return the exact bytes to sign or verify
     */
    static byte[] certificateVerifySignedContent(boolean serverRole, byte[] transcriptHash) {
        WireWriter w = new WireWriter();
        byte[] pad = new byte[64];
        Arrays.fill(pad, (byte) 0x20);
        w.bytes(pad);
        String context = serverRole ? "TLS 1.3, server CertificateVerify" : "TLS 1.3, client CertificateVerify";
        w.bytes(context.getBytes(StandardCharsets.US_ASCII));
        w.u8(0);
        w.bytes(transcriptHash);
        return w.toByteArray();
    }

    // ---- Finished (RFC 8446 section 4.4.4) ----

    static byte[] buildFinished(byte[] verifyData) {
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_FINISHED, verifyData);
    }

    static byte[] parseFinished(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_FINISHED);
        return r.bytes(r.u24());
    }

    // ---- KeyUpdate (RFC 8446 section 4.6.3) ----

    /** {@code update_not_requested} -- the peer need not reciprocate. */
    static final int KEY_UPDATE_NOT_REQUESTED = 0;

    /** {@code update_requested} -- the peer MUST send its own KeyUpdate before further application data. */
    static final int KEY_UPDATE_REQUESTED = 1;

    /**
     * Builds a {@code KeyUpdate} message: a single-octet
     * {@code KeyUpdateRequest} body. Post-handshake -- never joins the
     * handshake transcript.
     *
     * @param kind {@link #KEY_UPDATE_NOT_REQUESTED} or {@link #KEY_UPDATE_REQUESTED}
     * @return the complete framed message
     */
    static byte[] buildKeyUpdate(int kind) {
        return WireWriter.frameHandshakeMessage(HANDSHAKE_TYPE_KEY_UPDATE, new byte[] { (byte) kind });
    }

    /**
     * Parses a {@code KeyUpdate} message's {@code KeyUpdateRequest} value.
     *
     * @param fullMessage the complete framed message
     * @return the raw {@code KeyUpdateRequest} value -- callers must check
     *         it is {@link #KEY_UPDATE_NOT_REQUESTED} or
     *         {@link #KEY_UPDATE_REQUESTED} themselves; RFC 8446 section
     *         4.6.3 defines no other value, but this method does not
     *         reject one, since "malformed" here is a caller-level
     *         protocol decision (which alert to raise), not a wire
     *         framing error
     * @throws HandshakeFormatException if the body is not exactly one octet
     */
    static int parseKeyUpdate(byte[] fullMessage) throws HandshakeFormatException {
        WireReader r = new WireReader(fullMessage);
        requireType(r, HANDSHAKE_TYPE_KEY_UPDATE);
        WireReader body = r.slice(r.u24());
        int kind = body.u8();
        if (body.hasRemaining()) {
            throw new HandshakeFormatException("KeyUpdate body longer than one octet");
        }
        return kind;
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

    private static void writeSupportedGroupsExtension(WireWriter ext, List<NamedGroup> groups) {
        WireWriter list = new WireWriter();
        for (int i = 0; i < groups.size(); i++) {
            list.u16(groups.get(i).getCode());
        }
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

    // Package-private (not private): RFC 7301's ProtocolNameList wire
    // encoding is identical for TLS 1.2 and 1.3, so Tls12HandshakeMessages
    // reuses this directly rather than duplicating it.
    static void writeAlpnExtension(WireWriter ext, List<String> protocols) {
        WireWriter list = new WireWriter();
        for (int i = 0; i < protocols.size(); i++) {
            list.opaque8(protocols.get(i).getBytes(StandardCharsets.US_ASCII));
        }
        WireWriter body = new WireWriter();
        body.opaque16(list.toByteArray());
        writeExtension(ext, EXT_ALPN, body.toByteArray());
    }

    private static void writeSupportedVersionsClientExtension(WireWriter ext) {
        WireWriter versions = new WireWriter();
        versions.u16(TLS_1_3);
        WireWriter body = new WireWriter();
        body.opaque8(versions.toByteArray());
        writeExtension(ext, EXT_SUPPORTED_VERSIONS, body.toByteArray());
    }

    private static void writeKeyShareClientExtension(WireWriter ext, List<NamedGroup> groups,
            Map<NamedGroup, byte[]> keyShares) {
        WireWriter list = new WireWriter();
        for (int i = 0; i < groups.size(); i++) {
            NamedGroup group = groups.get(i);
            byte[] share = keyShares.get(group);
            if (share == null) {
                continue;
            }
            list.u16(group.getCode());
            list.opaque16(share);
        }
        WireWriter body = new WireWriter();
        body.opaque16(list.toByteArray());
        writeExtension(ext, EXT_KEY_SHARE, body.toByteArray());
    }

}
