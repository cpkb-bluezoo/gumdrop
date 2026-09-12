/*
 * KeySchedule.java
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

import org.bluezoo.gumdrop.crypto.Hkdf;

/**
 * The TLS 1.3 key schedule (RFC 8446 section 7.1): the ordered sequence
 * of HKDF-Extract/HKDF-Expand-Label steps from the (EC)DHE shared secret
 * down to the handshake and application traffic secrets, plus the
 * {@code Finished} verify-data construction (RFC 8446 section 4.4.4) and
 * the resumption-PSK/binder/early-traffic derivations a 0-RTT handshake
 * needs.
 *
 * <p>{@link #deriveEarlySecret} extracts from a real PSK when one is
 * given, or from an all-zero PSK when not, matching RFC 8446 section
 * 7.1's "If a given secret is not available, then the 0-value ... is
 * used" rule -- the latter is every full (non-resumed) handshake's case,
 * and also a resumed handshake's fallback when the server does not
 * select the client's offered PSK (the client must derive
 * {@code application_traffic_secret_0} and everything downstream from
 * the zero-PSK early secret in that case, even though the real PSK was
 * used to compute the ClientHello's binder -- gumdrop's port of a real
 * bug hopf's own implementation found and fixed).
 *
 * <p>Callers drive this class in the fixed order the key schedule
 * requires: {@link #deriveEarlySecret}, then (once the (EC)DHE shared
 * secret and the ClientHello..ServerHello transcript hash are known)
 * {@link #deriveHandshakeSecret}, then (once the shared secret is no
 * longer needed) {@link #deriveMasterSecret}, then (once the
 * ClientHello..server-Finished transcript hash is known)
 * {@link #deriveApplicationTrafficSecrets}. {@link #computePskBinder} and
 * {@link #deriveEarlyTrafficSecret} are typically called on a separate,
 * throwaway instance bound to a resumption ticket's remembered cipher
 * suite -- computed before the real, negotiated-suite instance for this
 * handshake even exists (see {@link HandshakeEngine#start}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-7.1">RFC 8446 section 7.1</a>
 */
public final class KeySchedule {

    private final Hkdf hkdf;
    private final int hashLength;
    private final byte[] emptyHash;

    private byte[] earlySecret;
    private byte[] handshakeSecret;
    private byte[] masterSecret;

    private byte[] clientHandshakeTrafficSecret;
    private byte[] serverHandshakeTrafficSecret;
    private byte[] clientApplicationTrafficSecret;
    private byte[] serverApplicationTrafficSecret;

    /**
     * Creates a key schedule for a negotiated cipher suite.
     *
     * @param suite the negotiated cipher suite
     */
    public KeySchedule(CipherSuite suite) {
        this.hkdf = suite.newHkdf();
        this.hashLength = suite.getHashLength();
        this.emptyHash = Transcript.create(suite).hash();
    }

    /**
     * RFC 8446 section 7.1's first step: {@code Early Secret =
     * HKDF-Extract(0, PSK)}. Must be called before any other derivation.
     *
     * @param psk the resumption PSK, or null for a full (non-resumed)
     *            handshake, or when the server did not select the
     *            client's offered PSK -- either way falling back to the
     *            RFC-mandated all-zero PSK
     * @return the early secret, also retained internally for
     *         {@link #deriveHandshakeSecret}'s salt derivation
     */
    public byte[] deriveEarlySecret(byte[] psk) {
        byte[] ikm = (psk != null) ? psk : new byte[hashLength];
        earlySecret = hkdf.extract(new byte[hashLength], ikm);
        return earlySecret;
    }

    /**
     * RFC 8446 section 7.1's second step: derives the Handshake Secret
     * from the (EC)DHE shared secret, and from it the two handshake
     * traffic secrets.
     *
     * @param sharedSecret the (EC)DHE shared secret from {@link org.bluezoo.gumdrop.crypto.KeyExchange}
     * @param clientHelloThroughServerHello the transcript hash of
     *        ClientHello through ServerHello
     */
    public void deriveHandshakeSecret(byte[] sharedSecret, byte[] clientHelloThroughServerHello) {
        byte[] salt = deriveSecret(earlySecret, "derived", emptyHash);
        handshakeSecret = hkdf.extract(salt, sharedSecret);
        clientHandshakeTrafficSecret = deriveSecret(handshakeSecret, "c hs traffic", clientHelloThroughServerHello);
        serverHandshakeTrafficSecret = deriveSecret(handshakeSecret, "s hs traffic", clientHelloThroughServerHello);
    }

    /**
     * RFC 8446 section 7.1's third step: {@code Master Secret =
     * HKDF-Extract(Derive-Secret(Handshake Secret, "derived", ""), 0)}.
     */
    public void deriveMasterSecret() {
        byte[] salt = deriveSecret(handshakeSecret, "derived", emptyHash);
        masterSecret = hkdf.extract(salt, new byte[hashLength]);
    }

    /**
     * Derives the two application traffic secrets from the Master
     * Secret.
     *
     * @param clientHelloThroughServerFinished the transcript hash of
     *        ClientHello through the server's Finished message
     */
    public void deriveApplicationTrafficSecrets(byte[] clientHelloThroughServerFinished) {
        clientApplicationTrafficSecret = deriveSecret(masterSecret, "c ap traffic", clientHelloThroughServerFinished);
        serverApplicationTrafficSecret = deriveSecret(masterSecret, "s ap traffic", clientHelloThroughServerFinished);
    }

    /**
     * RFC 8446 section 7.1: {@code Derive-Secret(Master Secret, "res
     * master", ClientHello...client Finished)} -- the input a resumption
     * ticket's PSK is derived from. Its transcript span includes the
     * client's own {@code Finished}, unlike {@link #deriveApplicationTrafficSecrets}'s
     * span -- callers must call this only once that message has joined
     * the transcript, not right after the server's own {@code Finished}.
     *
     * @param clientHelloThroughClientFinished the transcript hash of
     *        ClientHello through the client's Finished message
     * @return the resumption master secret
     */
    public byte[] deriveResumptionMasterSecret(byte[] clientHelloThroughClientFinished) {
        return deriveSecret(masterSecret, "res master", clientHelloThroughClientFinished);
    }

    /**
     * RFC 8446 section 4.6.1: {@code HKDF-Expand-Label(resumption_master_secret,
     * "resumption", ticket_nonce, Hash.length)} -- the resumption PSK
     * presented in a future handshake's {@code pre_shared_key} extension,
     * one per issued ticket (each with its own random nonce, so multiple
     * tickets from one connection yield unlinkable PSKs).
     *
     * @param resumptionMasterSecret this connection's resumption master
     *        secret, from {@link #deriveResumptionMasterSecret}
     * @param ticketNonce the ticket's random nonce
     * @return the resumption PSK
     */
    public byte[] deriveResumptionPsk(byte[] resumptionMasterSecret, byte[] ticketNonce) {
        return hkdf.expandLabel(resumptionMasterSecret, "resumption", ticketNonce, hashLength);
    }

    /**
     * RFC 8446 section 4.2.11: {@code Derive-Secret(Early Secret, "res
     * binder", "")} -- the base key a PSK binder's HMAC is computed
     * under. {@code earlySecretForPsk} must be the early secret derived
     * ({@link #deriveEarlySecret}) from the same PSK the binder is for,
     * not necessarily this instance's own {@link #deriveEarlySecret}
     * result -- see {@link #computePskBinder}, which handles that for
     * the common case directly.
     *
     * @param earlySecretForPsk the early secret derived from the PSK
     *        this binder authenticates
     * @return the resumption binder key
     */
    public byte[] deriveResumptionBinderKey(byte[] earlySecretForPsk) {
        return deriveSecret(earlySecretForPsk, "res binder", emptyHash);
    }

    /**
     * RFC 8446 section 4.2.11: computes a ClientHello's PSK binder --
     * {@code HMAC(Derive-Secret(HKDF-Extract(0, PSK), "res binder", ""),
     * Transcript-Hash(Truncated ClientHello))}. A convenience combining
     * {@link #deriveEarlySecret}, {@link #deriveResumptionBinderKey}, and
     * {@link #computeFinishedVerifyData} for the one PSK this call is
     * for, without disturbing this instance's own (possibly
     * differently-keyed, e.g. zero-PSK) {@link #deriveEarlySecret} state.
     *
     * @param psk the PSK to bind to (never null -- there is no binder
     *            without a PSK)
     * @param truncatedClientHelloHash the transcript hash of the
     *        ClientHello up to (not including) the binder bytes
     *        themselves
     * @return the binder value, {@code hashLength} bytes
     */
    public byte[] computePskBinder(byte[] psk, byte[] truncatedClientHelloHash) {
        byte[] earlySecretForPsk = hkdf.extract(new byte[hashLength], psk);
        byte[] binderKey = deriveResumptionBinderKey(earlySecretForPsk);
        return computeFinishedVerifyData(binderKey, truncatedClientHelloHash);
    }

    /**
     * RFC 8446 section 7.1: {@code Derive-Secret(Early Secret, "c e
     * traffic", ClientHello)} -- the 0-RTT client-to-server traffic
     * secret. Like {@link #computePskBinder}, derives its own early
     * secret from {@code psk} rather than relying on this instance's
     * {@link #deriveEarlySecret} state.
     *
     * @param psk the resumption PSK 0-RTT data is protected under
     * @param clientHelloHash the transcript hash of the (complete, with
     *        real binder) ClientHello
     * @return the client early traffic secret
     */
    public byte[] deriveEarlyTrafficSecret(byte[] psk, byte[] clientHelloHash) {
        byte[] earlySecretForPsk = hkdf.extract(new byte[hashLength], psk);
        return deriveSecret(earlySecretForPsk, "c e traffic", clientHelloHash);
    }

    /**
     * Ratchets one direction's application traffic secret forward for a
     * post-handshake {@code KeyUpdate} (RFC 8446 section 7.2): {@code
     * application_traffic_secret_N+1 = HKDF-Expand-Label(application_traffic_secret_N,
     * "traffic upd", "", Hash.length)}.
     *
     * @param currentSecret the current application traffic secret for one direction
     * @return the next application traffic secret for that direction
     */
    public byte[] updateTrafficSecret(byte[] currentSecret) {
        return hkdf.expandLabel(currentSecret, "traffic upd", new byte[0], hashLength);
    }

    /**
     * Ratchets {@link #getClientApplicationTrafficSecret}'s value forward
     * in place via {@link #updateTrafficSecret}, so it (and every later
     * call to the getter) reflects the new value.
     *
     * @return the new client application traffic secret
     */
    public byte[] updateClientApplicationTrafficSecret() {
        clientApplicationTrafficSecret = updateTrafficSecret(clientApplicationTrafficSecret);
        return clientApplicationTrafficSecret;
    }

    /**
     * Ratchets {@link #getServerApplicationTrafficSecret}'s value forward
     * in place via {@link #updateTrafficSecret}, so it (and every later
     * call to the getter) reflects the new value.
     *
     * @return the new server application traffic secret
     */
    public byte[] updateServerApplicationTrafficSecret() {
        serverApplicationTrafficSecret = updateTrafficSecret(serverApplicationTrafficSecret);
        return serverApplicationTrafficSecret;
    }

    /**
     * RFC 8446 section 7.1's {@code Derive-Secret(Secret, Label,
     * Messages)}, taking the transcript hash of {@code Messages} as a
     * precomputed argument rather than the messages themselves.
     *
     * @param secret the secret to derive from
     * @param label the label, without the {@code "tls13 "} prefix
     * @param transcriptHash the transcript hash to bind the derivation to
     * @return the derived secret, {@code hashLength} bytes
     */
    public byte[] deriveSecret(byte[] secret, String label, byte[] transcriptHash) {
        return hkdf.expandLabel(secret, label, transcriptHash, hashLength);
    }

    /**
     * Computes a {@code Finished} message's verify-data (RFC 8446
     * section 4.4.4): {@code HMAC(finished_key, Transcript-Hash(...))},
     * where {@code finished_key = HKDF-Expand-Label(BaseKey, "finished",
     * "", Hash.length)}.
     *
     * @param baseKey the relevant handshake traffic secret --
     *        {@link #getClientHandshakeTrafficSecret} for the client's
     *        own Finished, {@link #getServerHandshakeTrafficSecret} for
     *        the server's
     * @param transcriptHash the transcript hash up to (not including)
     *        this Finished message
     * @return the verify-data
     */
    public byte[] computeFinishedVerifyData(byte[] baseKey, byte[] transcriptHash) {
        byte[] finishedKey = hkdf.expandLabel(baseKey, "finished", new byte[0], hashLength);
        return hkdf.hmac(finishedKey, transcriptHash);
    }

    /**
     * Returns the client handshake traffic secret, once
     * {@link #deriveHandshakeSecret} has been called.
     *
     * @return the client handshake traffic secret
     */
    public byte[] getClientHandshakeTrafficSecret() {
        return clientHandshakeTrafficSecret;
    }

    /**
     * Returns the server handshake traffic secret, once
     * {@link #deriveHandshakeSecret} has been called.
     *
     * @return the server handshake traffic secret
     */
    public byte[] getServerHandshakeTrafficSecret() {
        return serverHandshakeTrafficSecret;
    }

    /**
     * Returns the client application traffic secret, once
     * {@link #deriveApplicationTrafficSecrets} has been called.
     *
     * @return the client application traffic secret
     */
    public byte[] getClientApplicationTrafficSecret() {
        return clientApplicationTrafficSecret;
    }

    /**
     * Returns the server application traffic secret, once
     * {@link #deriveApplicationTrafficSecrets} has been called.
     *
     * @return the server application traffic secret
     */
    public byte[] getServerApplicationTrafficSecret() {
        return serverApplicationTrafficSecret;
    }

}
