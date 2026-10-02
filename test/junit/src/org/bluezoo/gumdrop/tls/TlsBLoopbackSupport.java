/*
 * TlsBLoopbackSupport.java
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

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.TestCertificates;

/**
 * Shared fixtures for the TCP TLS 1.3 / 1.2 record-engine loopback tests:
 * lazily generated EC and RSA self-signed credentials, a recording
 * {@link TlsRecordSink}, and a byte pump between two engines.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class TlsBLoopbackSupport {

    static final String SERVER_NAME = TestCertificates.SERVER_NAME;

    private TlsBLoopbackSupport() {
    }

    static List<X509Certificate> ecChain() throws Exception {
        return TestCertificates.ec256().getChain();
    }

    static PrivateKey ecKey() throws Exception {
        return TestCertificates.ec256().getPrivateKey();
    }

    static List<X509Certificate> rsaChain() throws Exception {
        return TestCertificates.rsa2048().getChain();
    }

    static PrivateKey rsaKey() throws Exception {
        return TestCertificates.rsa2048().getPrivateKey();
    }

    static ServerCredentials ecCredentials() throws Exception {
        return TestCertificates.ec256().credentials();
    }

    static ServerCredentials rsaCredentials() throws Exception {
        return TestCertificates.rsa2048().credentials();
    }

    static javax.net.ssl.X509TrustManager trust(List<X509Certificate> chain) throws Exception {
        return TestCertificates.trustManager(chain);
    }

    /** Records every callback of a {@link TlsRecordSink}. */
    static final class Sink implements TlsRecordSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        final List<byte[]> appData = new ArrayList<byte[]>();
        final List<String> events = new ArrayList<String>();
        boolean complete;
        boolean peerClosed;
        TlsProtocolError error;

        @Override
        public void ciphertextReady(byte[] data) {
            outbound.add(data);
        }

        @Override
        public void applicationDataReady(byte[] plaintext) {
            appData.add(plaintext);
        }

        @Override
        public void handshakeComplete() {
            complete = true;
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            error = err;
            events.add("error: " + err.getMessage());
        }

        @Override
        public void peerClosed() {
            peerClosed = true;
        }

        byte[] appBytes() {
            int n = 0;
            for (int i = 0; i < appData.size(); i++) {
                n += appData.get(i).length;
            }
            byte[] all = new byte[n];
            int pos = 0;
            for (int i = 0; i < appData.size(); i++) {
                byte[] b = appData.get(i);
                System.arraycopy(b, 0, all, pos, b.length);
                pos += b.length;
            }
            return all;
        }

        byte[] takeOutbound() {
            int n = 0;
            for (int i = 0; i < outbound.size(); i++) {
                n += outbound.get(i).length;
            }
            byte[] all = new byte[n];
            int pos = 0;
            for (int i = 0; i < outbound.size(); i++) {
                byte[] b = outbound.get(i);
                System.arraycopy(b, 0, all, pos, b.length);
                pos += b.length;
            }
            outbound.clear();
            return all;
        }
    }

    /** Anything that can be fed ciphertext. */
    interface Peer {
        void feed(byte[] data, int off, int len, TlsRecordSink sink);
    }

    /**
     * Moves bytes between two peers until both outbound queues are empty.
     *
     * @param chunk maximum bytes per feed call (0 = whole buffers)
     */
    static void pump(Sink cs, Peer client, Sink ss, Peer server, int chunk) {
        for (int round = 0; round < 200; round++) {
            boolean moved = false;
            byte[] c = cs.takeOutbound();
            if (c.length > 0) {
                feedChunked(server, c, chunk, ss);
                moved = true;
            }
            byte[] s = ss.takeOutbound();
            if (s.length > 0) {
                feedChunked(client, s, chunk, cs);
                moved = true;
            }
            if (!moved) {
                return;
            }
        }
        throw new IllegalStateException("pump did not settle");
    }

    static void feedChunked(Peer to, byte[] data, int chunk, TlsRecordSink sink) {
        if (chunk <= 0) {
            to.feed(data, 0, data.length, sink);
            return;
        }
        for (int off = 0; off < data.length; off += chunk) {
            int len = Math.min(chunk, data.length - off);
            to.feed(data, off, len, sink);
        }
    }

    static Peer peer(final TlsRecordEngine e) {
        return new Peer() {
            @Override
            public void feed(byte[] data, int off, int len, TlsRecordSink sink) {
                e.feedCiphertext(data, off, len, sink);
            }
        };
    }

    static Peer peer(final Tls12RecordEngine e) {
        return new Peer() {
            @Override
            public void feed(byte[] data, int off, int len, TlsRecordSink sink) {
                e.feedCiphertext(data, off, len, sink);
            }
        };
    }
}
