/*
 * EchAcceptConfirmation.java
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

import java.util.Arrays;

import org.bluezoo.gumdrop.crypto.Hkdf;

/**
 * RFC 9849 ECH acceptance signals ({@code accept_confirmation},
 * {@code hrr_accept_confirmation}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9849#section-7.2">RFC 9849 section 7.2</a>
 */
public final class EchAcceptConfirmation {

    private static final int CONFIRMATION_LENGTH = 8;

    private EchAcceptConfirmation() {
    }

    /**
     * Computes the 8-byte confirmation embedded in {@code ServerHello.random}.
     */
    public static byte[] computeAcceptConfirmation(CipherSuite suite, byte[] clientHelloInnerRandom,
            byte[] clientHelloInnerFramed, byte[] serverHelloFramedForTranscript) {
        Hkdf hkdf = suite.newHkdf();
        Transcript transcript = Transcript.create(suite);
        transcript.update(clientHelloInnerFramed);
        transcript.update(serverHelloFramedForTranscript);
        byte[] transcriptHash = transcript.hash();
        byte[] secret = hkdf.extract(hkdf.zeroSalt(), clientHelloInnerRandom);
        return hkdf.expandLabel(secret, "ech accept confirmation", transcriptHash, CONFIRMATION_LENGTH);
    }

    /**
     * Returns true when the server accepted ECH (confirmation matches
     * the last 8 bytes of {@code ServerHello.random}).
     */
    public static boolean verifyServerHello(CipherSuite suite, byte[] clientHelloInnerFramed,
            byte[] serverHelloFramed) throws HandshakeFormatException {
        byte[] innerRandom = extractClientHelloRandom(clientHelloInnerFramed);
        byte[] serverHelloForHash = HandshakeMessages.serverHelloWithZeroedAcceptConfirmation(serverHelloFramed);
        byte[] expected = computeAcceptConfirmation(suite, innerRandom, clientHelloInnerFramed, serverHelloForHash);
        byte[] actual = extractServerHelloAcceptConfirmation(serverHelloFramed);
        return Arrays.equals(expected, actual);
    }

    /**
     * Embeds {@code accept_confirmation} into the last 8 bytes of
     * {@code ServerHello.random}.
     */
    public static byte[] embedAcceptConfirmationInServerHello(CipherSuite suite, byte[] clientHelloInnerFramed,
            byte[] serverHelloFramed) throws HandshakeFormatException {
        byte[] innerRandom = extractClientHelloRandom(clientHelloInnerFramed);
        byte[] forHash = HandshakeMessages.serverHelloWithZeroedAcceptConfirmation(serverHelloFramed);
        byte[] confirmation = computeAcceptConfirmation(suite, innerRandom, clientHelloInnerFramed, forHash);
        return HandshakeMessages.serverHelloWithAcceptConfirmation(serverHelloFramed, confirmation);
    }

    /**
     * Computes {@code hrr_accept_confirmation} (RFC 9849 section 7.2.1).
     */
    public static byte[] computeHrrAcceptConfirmation(CipherSuite suite, byte[] clientHelloInnerRandom,
            byte[] clientHelloInnerFramed, byte[] helloRetryRequestFramedForTranscript) {
        Hkdf hkdf = suite.newHkdf();
        Transcript transcript = Transcript.create(suite);
        transcript.update(clientHelloInnerFramed);
        transcript.update(helloRetryRequestFramedForTranscript);
        byte[] transcriptHash = transcript.hash();
        byte[] secret = hkdf.extract(hkdf.zeroSalt(), clientHelloInnerRandom);
        return hkdf.expandLabel(secret, "hrr ech accept confirmation", transcriptHash, CONFIRMATION_LENGTH);
    }

    /**
     * Returns true when the HelloRetryRequest ECH extension confirms acceptance.
     */
    public static boolean verifyHelloRetryRequest(CipherSuite suite, byte[] clientHelloInnerFramed,
            byte[] helloRetryRequestFramed, byte[] echConfirmationExtension) throws HandshakeFormatException {
        byte[] innerRandom = extractClientHelloRandom(clientHelloInnerFramed);
        byte[] hrrForHash = HandshakeMessages.helloRetryRequestWithZeroedEchConfirmation(helloRetryRequestFramed);
        byte[] expected = computeHrrAcceptConfirmation(suite, innerRandom, clientHelloInnerFramed, hrrForHash);
        return Arrays.equals(expected, echConfirmationExtension);
    }

    private static byte[] extractClientHelloRandom(byte[] framedClientHello) throws HandshakeFormatException {
        byte[] body = HandshakeMessages.extractClientHelloContent(framedClientHello);
        if (body.length < 34) {
            throw new HandshakeFormatException("ClientHello too short");
        }
        return Arrays.copyOfRange(body, 2, 34);
    }

    private static byte[] extractServerHelloAcceptConfirmation(byte[] framedServerHello)
            throws HandshakeFormatException {
        byte[] body = HandshakeMessages.extractServerHelloContent(framedServerHello);
        if (body.length < 34) {
            throw new HandshakeFormatException("ServerHello too short");
        }
        return Arrays.copyOfRange(body, 26, 34);
    }
}
