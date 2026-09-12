/*
 * TlsRecordSink.java
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
 * Events pushed by a {@link TlsRecordEngine} -- the TCP-facing
 * counterpart of {@link TlsEventSink}, matching its same pull-based,
 * argument-light style: an outcome fires here, and the caller pulls
 * whatever detail it needs (negotiated suite, ALPN, peer certificate
 * chain, resumption status) from {@link TlsRecordEngine}'s own getters,
 * which delegate to the wrapped {@link HandshakeEngine}'s.
 *
 * <p>No certificate-verification-requested counterpart -- like
 * {@link TlsEventSink}, this engine verifies chains synchronously inline,
 * not as an asynchronous gate. No session-ticket-received or 0-RTT
 * counterpart either: plain PSK resumption (ticket issuance and
 * presentation, no early data) works over TCP with no changes, but this
 * milestone doesn't surface a received ticket to the embedder or attempt
 * 0-RTT for the TCP record layer.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface TlsRecordSink {

    /**
     * TLS record bytes to write to the socket.
     *
     * @param data the record bytes
     */
    void ciphertextReady(byte[] data);

    /**
     * Decrypted application data (a post-handshake {@code ApplicationData}
     * record's plaintext).
     *
     * @param plaintext the decrypted application data
     */
    void applicationDataReady(byte[] plaintext);

    /**
     * The handshake has finished: {@link TlsRecordEngine#sendApplicationData}
     * is now usable, and every {@link HandshakeEngine} getter
     * {@link TlsRecordEngine} delegates to reflects the completed
     * handshake.
     */
    void handshakeComplete();

    /**
     * A non-fatal-to-the-JVM failure -- a malformed or unauthenticated
     * record, a handshake failure, or a fatal alert the peer sent. The
     * engine does not throw for these; this is how they are reported.
     *
     * @param error the failure
     */
    void protocolError(TlsProtocolError error);

    /**
     * The peer sent {@code close_notify}. Default no-op since not every
     * caller needs to distinguish this from any other teardown.
     */
    default void peerClosed() {
    }

}
