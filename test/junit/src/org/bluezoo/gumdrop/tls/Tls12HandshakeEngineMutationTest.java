/*
 * Tls12HandshakeEngineMutationTest.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Message-level robustness tests for {@link Tls12HandshakeEngine}: a clean
 * handshake is recorded, then replayed with every message truncated at,
 * flipped at, retyped at, dropped from and duplicated in the flight. The
 * engines must never throw, and a damaged handshake must either fail with
 * an alert or still complete consistently.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Tls12HandshakeEngineMutationTest {

    private static final class Rec implements Tls12EventSink {
        final List<byte[]> out = new ArrayList<byte[]>();
        int keys;
        int ccs;
        boolean complete;
        TlsProtocolError error;

        @Override
        public void handshakeDataReady(byte[] data) {
            out.add(data);
        }

        @Override
        public void keysReady(Tls12CipherSuite cipher, DirectionalKeyMaterial client,
                DirectionalKeyMaterial server) {
            keys++;
        }

        @Override
        public void sendChangeCipherSpec() {
            ccs++;
        }

        @Override
        public void handshakeComplete() {
            complete = true;
        }

        @Override
        public void protocolError(TlsProtocolError e) {
            error = e;
        }
    }

    private interface Mutator {
        /** Returns the messages to deliver in place of {@code msg}; null means deliver nothing. */
        List<byte[]> mutate(byte[] msg);
    }

    private static final class Outcome {
        final List<byte[]> trace = new ArrayList<byte[]>();
        Rec cr;
        Rec sr;
        Tls12HandshakeEngine client;
        Tls12HandshakeEngine server;
    }

    private static Tls12HandshakeConfig serverCfg(boolean rsa, boolean mtls, TicketKeys keys) throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        if (rsa) {
            c.setServerCredentials(TlsBLoopbackSupport.rsaCredentials());
        } else {
            c.setServerCredentials(TlsBLoopbackSupport.ecCredentials());
        }
        if (mtls) {
            c.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
            c.setClientTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        }
        c.setTicketKeys(keys);
        return c;
    }

    private static Tls12HandshakeConfig clientCfg(boolean rsa, boolean mtls, Tls12ClientTicketStore store)
            throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        if (rsa) {
            c.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        } else {
            c.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        }
        if (mtls) {
            c.setClientCredentials(TlsBLoopbackSupport.rsaCredentials());
        }
        c.setClientTicketStore(store);
        c.setApplicationProtocols(Collections.singletonList("h2"));
        return c;
    }

    /**
     * Runs one handshake; the {@code target}-th message exchanged (0-based, both
     * directions, in delivery order) is passed through {@code mutator}.
     */
    private static Outcome run(Tls12HandshakeConfig cc, Tls12HandshakeConfig sc, int target, Mutator mutator) {
        Outcome o = new Outcome();
        o.cr = new Rec();
        o.sr = new Rec();
        o.client = new Tls12HandshakeEngine(cc);
        o.server = new Tls12HandshakeEngine(sc);
        o.client.start(o.cr);
        int index = 0;
        for (int round = 0; round < 40; round++) {
            boolean moved = false;
            List<byte[]> cmsgs = new ArrayList<byte[]>(o.cr.out);
            o.cr.out.clear();
            for (int i = 0; i < cmsgs.size(); i++) {
                moved = true;
                List<byte[]> deliver = deliverable(cmsgs.get(i), index, target, mutator, o);
                index++;
                for (int j = 0; j < deliver.size(); j++) {
                    o.server.processMessage(deliver.get(j), o.sr);
                }
            }
            List<byte[]> smsgs = new ArrayList<byte[]>(o.sr.out);
            o.sr.out.clear();
            for (int i = 0; i < smsgs.size(); i++) {
                moved = true;
                List<byte[]> deliver = deliverable(smsgs.get(i), index, target, mutator, o);
                index++;
                for (int j = 0; j < deliver.size(); j++) {
                    o.client.processMessage(deliver.get(j), o.cr);
                }
            }
            if (!moved) {
                break;
            }
        }
        return o;
    }

    private static List<byte[]> deliverable(byte[] msg, int index, int target, Mutator mutator, Outcome o) {
        o.trace.add(msg);
        List<byte[]> one = new ArrayList<byte[]>(1);
        if (index != target || mutator == null) {
            one.add(msg);
            return one;
        }
        List<byte[]> m = mutator.mutate(msg);
        if (m == null) {
            return one;
        }
        return m;
    }

    private static void checkConsistent(Outcome o, String what) {
        if (o.cr.error != null) {
            assertNotNull(what, o.cr.error.getAlert());
            assertTrue(what, o.client.isFailed());
        }
        if (o.sr.error != null) {
            assertNotNull(what, o.sr.error.getAlert());
            assertTrue(what, o.server.isFailed());
        }
        if (o.client.isComplete()) {
            assertFalse(what, o.client.isFailed());
        }
    }

    private static final int[] HEAD = new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
        18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43};

    private static List<Integer> offsets(int len) {
        List<Integer> out = new ArrayList<Integer>();
        if (len <= 96) {
            for (int i = 0; i < len; i++) {
                out.add(Integer.valueOf(i));
            }
            return out;
        }
        for (int i = 0; i < HEAD.length && i < len; i++) {
            out.add(Integer.valueOf(HEAD[i]));
        }
        for (int i = HEAD.length; i < len - 12; i += 17) {
            out.add(Integer.valueOf(i));
        }
        for (int i = Math.max(HEAD.length, len - 12); i < len; i++) {
            out.add(Integer.valueOf(i));
        }
        return out;
    }

    private interface Factory {
        Tls12HandshakeConfig client() throws Exception;

        Tls12HandshakeConfig server() throws Exception;
    }

    private void mutateEverything(Factory f, boolean expectComplete) throws Exception {
        Outcome clean = run(f.client(), f.server(), -1, null);
        assertTrue(clean.cr.out.toString(), clean.client.isComplete() == expectComplete);
        List<byte[]> trace = clean.trace;
        assertTrue(trace.size() >= 4);
        for (int m = 0; m < trace.size(); m++) {
            final byte[] orig = trace.get(m);
            List<Integer> offs = offsets(orig.length);
            for (int k = 0; k < offs.size(); k++) {
                final int off = offs.get(k).intValue();
                String label = "msg " + m + " off " + off;
                Outcome flip = run(f.client(), f.server(), m, new Mutator() {
                    @Override
                    public List<byte[]> mutate(byte[] msg) {
                        byte[] c = msg.clone();
                        if (off < c.length) {
                            c[off] ^= 0x5a;
                        }
                        return Collections.singletonList(c);
                    }
                });
                checkConsistent(flip, "flip " + label);
                Outcome trunc = run(f.client(), f.server(), m, new Mutator() {
                    @Override
                    public List<byte[]> mutate(byte[] msg) {
                        int n = Math.min(off, msg.length);
                        byte[] c = new byte[n];
                        System.arraycopy(msg, 0, c, 0, n);
                        return Collections.singletonList(c);
                    }
                });
                checkConsistent(trunc, "trunc " + label);
            }
            Outcome dropped = run(f.client(), f.server(), m, new Mutator() {
                @Override
                public List<byte[]> mutate(byte[] msg) {
                    return null;
                }
            });
            checkConsistent(dropped, "drop msg " + m);
            Outcome dup = run(f.client(), f.server(), m, new Mutator() {
                @Override
                public List<byte[]> mutate(byte[] msg) {
                    List<byte[]> two = new ArrayList<byte[]>();
                    two.add(msg);
                    two.add(msg);
                    return two;
                }
            });
            checkConsistent(dup, "dup msg " + m);
            Outcome retyped = run(f.client(), f.server(), m, new Mutator() {
                @Override
                public List<byte[]> mutate(byte[] msg) {
                    byte[] c = msg.clone();
                    c[0] = (byte) (c[0] == 20 ? 1 : c[0] + 1);
                    return Collections.singletonList(c);
                }
            });
            checkConsistent(retyped, "retype msg " + m);
            assertTrue("retyped message " + m + " cannot leave a completed client",
                    !retyped.client.isComplete() || retyped.cr.error == null);
        }
    }

    @Test
    public void ecdsaHandshakeSurvivesMutation() throws Exception {
        mutateEverything(new Factory() {
            @Override
            public Tls12HandshakeConfig client() throws Exception {
                return clientCfg(false, false, null);
            }

            @Override
            public Tls12HandshakeConfig server() throws Exception {
                return serverCfg(false, false, null);
            }
        }, true);
    }

    @Test
    public void rsaMutualAuthHandshakeSurvivesMutation() throws Exception {
        mutateEverything(new Factory() {
            @Override
            public Tls12HandshakeConfig client() throws Exception {
                return clientCfg(true, true, null);
            }

            @Override
            public Tls12HandshakeConfig server() throws Exception {
                return serverCfg(true, true, null);
            }
        }, true);
    }

    @Test
    public void ticketIssuingHandshakeSurvivesMutation() throws Exception {
        final TicketKeys keys = new TicketKeys(new byte[16]);
        mutateEverything(new Factory() {
            @Override
            public Tls12HandshakeConfig client() throws Exception {
                return clientCfg(false, false, new Tls12ClientTicketStore());
            }

            @Override
            public Tls12HandshakeConfig server() throws Exception {
                return serverCfg(false, false, keys);
            }
        }, true);
    }

    @Test
    public void resumedHandshakeSurvivesMutation() throws Exception {
        final TicketKeys keys = new TicketKeys(new byte[16]);
        final Tls12ClientTicketStore store = new Tls12ClientTicketStore();
        Outcome first = run(clientCfg(false, false, store), serverCfg(false, false, keys), -1, null);
        assertTrue(first.client.isComplete());
        // A mutated resumed handshake must never throw. A failed run may
        // discard the stored ticket, so each run restores one via a fresh
        // full handshake into its own store.
        Outcome probe = run(clientCfg(false, false, store), serverCfg(false, false, keys), -1, null);
        assertTrue(probe.client.isResumed());
        int total = probe.trace.size();
        for (int m = 0; m < total; m++) {
            byte[] orig = probe.trace.get(m);
            List<Integer> offs = offsets(orig.length);
            for (int k = 0; k < offs.size(); k++) {
                final int off = offs.get(k).intValue();
                Tls12ClientTicketStore s = new Tls12ClientTicketStore();
                Outcome seed = run(clientCfg(false, false, s), serverCfg(false, false, keys), -1, null);
                assertTrue(seed.client.isComplete());
                Outcome flip = run(clientCfg(false, false, s), serverCfg(false, false, keys), m, new Mutator() {
                    @Override
                    public List<byte[]> mutate(byte[] msg) {
                        byte[] c = msg.clone();
                        if (off < c.length) {
                            c[off] ^= 0x21;
                        }
                        return Collections.singletonList(c);
                    }
                });
                checkConsistent(flip, "resumed flip " + m + "/" + off);
                Tls12ClientTicketStore s2 = new Tls12ClientTicketStore();
                run(clientCfg(false, false, s2), serverCfg(false, false, keys), -1, null);
                Outcome trunc = run(clientCfg(false, false, s2), serverCfg(false, false, keys), m,
                        new Mutator() {
                            @Override
                            public List<byte[]> mutate(byte[] msg) {
                                int n = Math.min(off, msg.length);
                                byte[] c = new byte[n];
                                System.arraycopy(msg, 0, c, 0, n);
                                return Collections.singletonList(c);
                            }
                        });
                checkConsistent(trunc, "resumed trunc " + m + "/" + off);
            }
        }
    }

    @Test
    public void messagesAfterFailureAreRejected() throws Exception {
        Rec r = new Rec();
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(serverCfg(false, false, null));
        server.processMessage(new byte[] {1, 0, 0}, r);
        assertNotNull(r.error);
        assertTrue(server.isFailed());
        TlsProtocolError first = r.error;
        server.processMessage(new byte[] {1, 0, 0, 0}, r);
        assertTrue(first == r.error);
        assertTrue(server.isFailed());
    }

    @Test
    public void clientNoteClientHelloSentIgnoresBadWire() throws Exception {
        Rec r = new Rec();
        Tls12HandshakeEngine client = new Tls12HandshakeEngine(clientCfg(false, false, null));
        client.clientNoteClientHelloSent(new byte[] {1, 2, 3});
        client.start(r);
        assertEquals(1, r.out.size());
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(serverCfg(false, false, null));
        server.clientNoteClientHelloSent(r.out.get(0));
        try {
            Rec r2 = new Rec();
            Tls12HandshakeEngine c2 = new Tls12HandshakeEngine(clientCfg(false, false, null));
            c2.clientNoteClientHelloSent(r.out.get(0));
            assertTrue(c2.getClientRandom() != null);
            c2.start(r2);
        } catch (RuntimeException e) {
            fail("unexpected " + e);
        }
    }
}
