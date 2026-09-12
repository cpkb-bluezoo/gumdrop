/*
 * Tls12EventSink.java
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

/**
 * Events pushed by a {@link Tls12HandshakeEngine} as it progresses --
 * consumed by {@link Tls12RecordEngine}. Mirrors {@link TlsEventSink}'s
 * pull-based, argument-light style, but the events themselves are
 * TLS-1.2-shaped and genuinely differ: {@link #keysReady} carries real
 * key material rather than being a "go pull it from a getter" signal
 * (RFC 5246's key block has no {@code HandshakeConfig}-style secret
 * object to expose one), and {@link #sendChangeCipherSpec} exists at all
 * because TLS 1.2's {@code ChangeCipherSpec} is a real wire event that
 * actually switches an epoch, unlike TLS 1.3's middlebox-compat theater.
 *
 * <p>Certificate chain verification is, like {@link TlsEventSink}'s,
 * deliberately not an asynchronous gate on this interface -- verification
 * runs synchronously inline via {@link org.bluezoo.gumdrop.crypto.CertificateVerifier},
 * the same choice {@link HandshakeEngine} already made.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface Tls12EventSink {

    /**
     * Plaintext handshake message bytes to send, framed by the record
     * layer under whichever epoch is currently active for writing.
     *
     * @param data the handshake message bytes to send
     */
    void handshakeDataReady(byte[] data);

    /**
     * Key material for both directions is ready -- the record layer
     * should cache it, not activate it yet; activation happens only when
     * {@link #sendChangeCipherSpec} fires (this side's write epoch) or a
     * real {@code ChangeCipherSpec} record arrives on the wire (the read
     * epoch, a record-layer-only event this engine never sees directly).
     *
     * @param cipher the negotiated cipher suite
     * @param client the client-direction key material
     * @param server the server-direction key material
     */
    void keysReady(Tls12CipherSuite cipher, DirectionalKeyMaterial client, DirectionalKeyMaterial server);

    /**
     * Emit a real {@code ChangeCipherSpec} record now, then activate
     * <em>this engine's own role's</em> write epoch using the material
     * from {@link #keysReady} -- e.g. the client sends CCS and starts
     * encrypting with the client-direction material; the server sends CCS
     * and starts encrypting with the server-direction material.
     */
    void sendChangeCipherSpec();

    /**
     * The handshake has completed on this side. Negotiated state
     * ({@link Tls12HandshakeEngine#getNegotiatedCipherSuite},
     * {@link Tls12HandshakeEngine#getPeerCertificateChain}, etc.) is
     * available from the engine itself from this point on.
     */
    void handshakeComplete();

    /**
     * A non-fatal-to-the-JVM handshake failure.
     *
     * @param error the failure
     */
    void protocolError(TlsProtocolError error);

}
