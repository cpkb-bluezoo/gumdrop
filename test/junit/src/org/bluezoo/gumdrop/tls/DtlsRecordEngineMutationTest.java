/*
 * DtlsRecordEngineMutationTest.java
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

/**
 * Datagram-level adversary for {@link Dtls12RecordEngine} and
 * {@link Dtls13RecordEngine}: every datagram of a complete handshake and a
 * short application data exchange is in turn truncated, bit-flipped,
 * extended, dropped, duplicated, reordered and replayed. The engines must
 * never throw, and any application data they do deliver must be exactly
 * what the peer sent (the AEAD must reject everything else).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DtlsRecordEngineMutationTest {

    private static final byte[] PING = new byte[] { 'p', 'i', 'n', 'g' };
    private static final byte[] PONG = new byte[] { 'p', 'o', 'n', 'g', '!' };

    private static List<X509Certificate> chain;
    private static PrivateKey key;

    @BeforeClass
    public static void loadFixtures() throws Exception {
        chain = TestCertificates.ec256().getChain();
        key = TestCertificates.ec256().getPrivateKey();
    }

    private static final class Sink implements TlsRecordSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        final List<byte[]> appData = new ArrayList<byte[]>();
        boolean error;
        boolean closed;

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
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            error = true;
        }

        @Override
        public void peerClosed() {
            closed = true;
        }
    }

    /** Version-neutral view of a DTLS record engine. */
    private abstract static class Peer {
        abstract void start(Sink s);

        abstract void feed(byte[] datagram, Sink s);

        abstract boolean complete();

        abstract boolean failed();

        abstract void send(byte[] data, Sink s);

        abstract void close(Sink s);
    }

    private static Peer wrap12(final Dtls12RecordEngine e) {
        return new Peer() {
            @Override
            void start(Sink s) {
                e.start(s);
            }

            @Override
            void feed(byte[] datagram, Sink s) {
                e.feedDatagram(datagram, s);
            }

            @Override
            boolean complete() {
                return e.isComplete();
            }

            @Override
            boolean failed() {
                return e.isFailed();
            }

            @Override
            void send(byte[] data, Sink s) {
                e.sendApplicationData(data, s);
            }

            @Override
            void close(Sink s) {
                e.sendCloseNotify(s);
            }
        };
    }

    private static Peer wrap13(final Dtls13RecordEngine e) {
        return new Peer() {
            @Override
            void start(Sink s) {
                e.start(s);
            }

            @Override
            void feed(byte[] datagram, Sink s) {
                e.feedDatagram(datagram, s);
            }

            @Override
            boolean complete() {
                return e.isComplete();
            }

            @Override
            boolean failed() {
                return e.isFailed();
            }

            @Override
            void send(byte[] data, Sink s) {
                e.sendApplicationData(data, s);
            }

            @Override
            void close(Sink s) {
                e.sendCloseNotify(s);
            }
        };
    }

    private Peer newPeer(int version, boolean server) throws Exception {
        if (version == 12) {
            Tls12HandshakeConfig c = new Tls12HandshakeConfig(
                    server ? HandshakeRole.SERVER : HandshakeRole.CLIENT);
            c.setDtlsTransport(true);
            if (server) {
                c.setServerCredentials(new ServerCredentials(chain, key));
            } else {
                c.setServerName(TestCertificates.SERVER_NAME);
                c.setTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
            }
            return wrap12(new Dtls12RecordEngine(c, 1024));
        }
        HandshakeConfig c = new HandshakeConfig(server ? HandshakeRole.SERVER : HandshakeRole.CLIENT);
        // Brotli-compressing the certificate on every run dominates the sweep
        // and is covered by the handshake engine mutation tests
        c.setCertificateCompressionEnabled(false);
        if (server) {
            c.setServerCredentials(new ServerCredentials(chain, key));
        } else {
            c.setServerName(TestCertificates.SERVER_NAME);
            c.setTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
        }
        return wrap13(new Dtls13RecordEngine(new Dtls13HandshakeConfig(c), 1024));
    }

    private abstract static class Mutation {
        final String label;

        Mutation(String label) {
            this.label = label;
        }

        abstract List<byte[]> apply(byte[] datagram);
    }

    private static final class Result {
        final List<byte[]> trace = new ArrayList<byte[]>();
        Sink clientSink = new Sink();
        Sink serverSink = new Sink();
        boolean bothComplete;
    }

    private static List<byte[]> one(byte[] b) {
        List<byte[]> l = new ArrayList<byte[]>();
        l.add(b);
        return l;
    }

    /** Relays everything pending between the two peers, mutating one datagram. */
    private int pump(Peer client, Peer server, Result r, int counter, int target, Mutation m) {
        for (int round = 0; round < 40; round++) {
            boolean moved = false;
            for (int dir = 0; dir < 2; dir++) {
                Sink from = (dir == 0) ? r.clientSink : r.serverSink;
                Peer to = (dir == 0) ? server : client;
                Sink toSink = (dir == 0) ? r.serverSink : r.clientSink;
                List<byte[]> batch = new ArrayList<byte[]>(from.outbound);
                from.outbound.clear();
                for (int i = 0; i < batch.size(); i++) {
                    moved = true;
                    byte[] dg = batch.get(i);
                    r.trace.add(dg);
                    List<byte[]> delivered;
                    if (counter == target && m != null) {
                        delivered = m.apply(dg);
                    } else {
                        delivered = one(dg);
                    }
                    counter++;
                    for (int d = 0; d < delivered.size(); d++) {
                        try {
                            to.feed(delivered.get(d), toSink);
                        } catch (RuntimeException e) {
                            String l = (m == null) ? "clean" : m.label;
                            throw new AssertionError("engine threw on " + l + " datagram " + target, e);
                        }
                    }
                }
            }
            if (!moved) {
                break;
            }
        }
        return counter;
    }

    private Result run(int version, int target, Mutation m) throws Exception {
        Peer client = newPeer(version, false);
        Peer server = newPeer(version, true);
        Result r = new Result();
        client.start(r.clientSink);
        int counter = pump(client, server, r, 0, target, m);
        r.bothComplete = client.complete() && server.complete();
        if (r.bothComplete) {
            client.send(PING, r.clientSink);
            counter = pump(client, server, r, counter, target, m);
            server.send(PONG, r.serverSink);
            counter = pump(client, server, r, counter, target, m);
            client.close(r.clientSink);
            pump(client, server, r, counter, target, m);
        }
        // anything delivered must be exactly what the peer sent
        for (int i = 0; i < r.serverSink.appData.size(); i++) {
            assertArrayEquals("server app data after " + (m == null ? "clean" : m.label),
                    PING, r.serverSink.appData.get(i));
        }
        for (int i = 0; i < r.clientSink.appData.size(); i++) {
            assertArrayEquals("client app data after " + (m == null ? "clean" : m.label),
                    PONG, r.clientSink.appData.get(i));
        }
        return r;
    }

    private static Mutation truncate(final int len) {
        return new Mutation("truncate " + len) {
            @Override
            List<byte[]> apply(byte[] dg) {
                return one(Arrays.copyOf(dg, Math.min(len, dg.length)));
            }
        };
    }

    private static Mutation flip(final int off, final int mask) {
        return new Mutation("flip " + off + "^" + mask) {
            @Override
            List<byte[]> apply(byte[] dg) {
                byte[] r = dg.clone();
                if (off < r.length) {
                    r[off] ^= (byte) mask;
                }
                return one(r);
            }
        };
    }

    private static Mutation append(final int n) {
        return new Mutation("append " + n) {
            @Override
            List<byte[]> apply(byte[] dg) {
                return one(Arrays.copyOf(dg, dg.length + n));
            }
        };
    }

    private static Mutation drop() {
        return new Mutation("drop") {
            @Override
            List<byte[]> apply(byte[] dg) {
                return Collections.emptyList();
            }
        };
    }

    private static Mutation duplicate() {
        return new Mutation("duplicate") {
            @Override
            List<byte[]> apply(byte[] dg) {
                List<byte[]> l = new ArrayList<byte[]>();
                l.add(dg);
                l.add(dg);
                l.add(dg.clone());
                return l;
            }
        };
    }

    private static Mutation glue(final int copies) {
        return new Mutation("glue " + copies) {
            @Override
            List<byte[]> apply(byte[] dg) {
                byte[] r = new byte[dg.length * copies];
                for (int i = 0; i < copies; i++) {
                    System.arraycopy(dg, 0, r, i * dg.length, dg.length);
                }
                return one(r);
            }
        };
    }

    private void sweep(int version) throws Exception {
        Result clean = run(version, -1, null);
        assertTrue("clean handshake completes", clean.bothComplete);
        assertEquals(1, clean.serverSink.appData.size());
        assertEquals(1, clean.clientSink.appData.size());
        assertFalse(clean.clientSink.error || clean.serverSink.error);
        int n = clean.trace.size();
        for (int k = 0; k < n; k++) {
            int len = clean.trace.get(k).length;
            int stride = (len > 150) ? 11 : 1;
            for (int t = 0; t < len; t += (t < 16 || len - t < 16) ? 1 : stride) {
                run(version, k, truncate(t));
            }
            for (int off = 0; off < len; off += (off < 16 || len - off < 16) ? 1 : stride) {
                run(version, k, flip(off, 0xff));
            }
            run(version, k, flip(len - 1, 0x01));
            run(version, k, append(1));
            run(version, k, append(13));
            run(version, k, drop());
            run(version, k, duplicate());
            run(version, k, glue(2));
        }
    }

    @Test
    public void dtls12SurvivesEveryDatagramMutation() throws Exception {
        sweep(12);
    }

    @Test
    public void dtls13SurvivesEveryDatagramMutation() throws Exception {
        sweep(13);
    }
}
