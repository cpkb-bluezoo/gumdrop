/*
 * TlsDeferredDispatchTest.java
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
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link Tls12DeferredDispatch} and {@link Tls13DeferredDispatch}:
 * immediate delivery when not deferring, queued delivery when deferring,
 * and the fixed slot capacities.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsDeferredDispatchTest {

    private static final class StubOffload implements HandshakeAsyncOffload {
        boolean deferring;
        final List<Runnable> queued = new ArrayList<Runnable>();

        @Override
        public Object lock() {
            return this;
        }

        @Override
        public boolean isBusy() {
            return false;
        }

        @Override
        public boolean isDeferring() {
            return deferring;
        }

        @Override
        public void dispatch(Runnable call) {
            if (deferring) {
                queued.add(call);
            } else {
                call.run();
            }
        }

        @Override
        public void submit(BatchProcessor processor, CompletionHandler onDone, FailureHandler onFailure) {
            processor.process();
            onDone.onBatchDone();
        }

        void flush() {
            List<Runnable> copy = new ArrayList<Runnable>(queued);
            queued.clear();
            for (int i = 0; i < copy.size(); i++) {
                copy.get(i).run();
            }
        }
    }

    private static HandshakeAsyncScheduler scheduler(StubOffload offload) {
        HandshakeAsyncScheduler.Runner runner = new HandshakeAsyncScheduler.Runner() {
            @Override
            public void runStart() {
            }

            @Override
            public void runInputs(List<HandshakeInput> inputs) {
            }
        };
        HandshakeAsyncOffload.FailureHandler failure = new HandshakeAsyncOffload.FailureHandler() {
            @Override
            public void failed(Throwable error) {
            }
        };
        return new HandshakeAsyncScheduler(offload, runner, failure);
    }

    private static final class Target13 implements Tls13DeferredDispatch.Target {
        final List<String> log = new ArrayList<String>();

        @Override
        public void deliverHandshakeData(byte[] data) {
            log.add("data" + data.length);
        }

        @Override
        public void deliverHandshakeSecretsReady() {
            log.add("hs");
        }

        @Override
        public void deliverApplicationSecretsReady() {
            log.add("app");
        }

        @Override
        public void deliverKeyUpdate(KeyUpdateDirection direction, byte[] newSecret) {
            log.add("ku" + direction);
        }

        @Override
        public void deliverProtocolError(TlsProtocolError error) {
            log.add("err" + error.getMessage());
        }

        @Override
        public void deliverPeerClosed() {
            log.add("closed");
        }
    }

    private static final class Target12 implements Tls12DeferredDispatch.Target {
        final List<String> log = new ArrayList<String>();

        @Override
        public void deliverHandshakeData(byte[] data) {
            log.add("data" + data.length);
        }

        @Override
        public void deliverKeysReady(Tls12CipherSuite cipher, DirectionalKeyMaterial client,
                DirectionalKeyMaterial server) {
            log.add("keys");
        }

        @Override
        public void deliverChangeCipherSpec() {
            log.add("ccs");
        }

        @Override
        public void deliverHandshakeComplete() {
            log.add("done");
        }

        @Override
        public void deliverProtocolError(TlsProtocolError error) {
            log.add("err" + error.getMessage());
        }
    }

    private static TlsProtocolError err(String m) {
        return new TlsProtocolError(AlertDescription.INTERNAL_ERROR, m);
    }

    @Test
    public void tls13DeliversImmediatelyWhenNotDeferring() {
        StubOffload off = new StubOffload();
        Target13 t = new Target13();
        Tls13DeferredDispatch d = new Tls13DeferredDispatch(scheduler(off), t);
        d.handshakeDataReady(new byte[2]);
        d.handshakeSecretsReady();
        d.applicationSecretsReady();
        d.applicationTrafficSecretUpdated(KeyUpdateDirection.READ, new byte[1]);
        d.protocolError(err("x"));
        d.peerClosed();
        assertEquals("[data2, hs, app, kuREAD, errx, closed]", t.log.toString());
        assertTrue(off.queued.isEmpty());
    }

    @Test
    public void tls13QueuesWhileDeferringAndKeepsOrder() {
        StubOffload off = new StubOffload();
        off.deferring = true;
        Target13 t = new Target13();
        Tls13DeferredDispatch d = new Tls13DeferredDispatch(scheduler(off), t);
        d.handshakeDataReady(new byte[3]);
        d.handshakeSecretsReady();
        d.applicationSecretsReady();
        d.applicationTrafficSecretUpdated(KeyUpdateDirection.WRITE, new byte[1]);
        d.protocolError(err("y"));
        d.peerClosed();
        assertTrue(t.log.isEmpty());
        assertEquals(6, off.queued.size());
        off.deferring = false;
        off.flush();
        assertEquals("[data3, hs, app, kuWRITE, erry, closed]", t.log.toString());
    }

    @Test
    public void tls13SlotCapacitiesAndReset() {
        StubOffload off = new StubOffload();
        off.deferring = true;
        Target13 t = new Target13();
        Tls13DeferredDispatch d = new Tls13DeferredDispatch(scheduler(off), t);
        for (int i = 0; i < 12; i++) {
            d.handshakeDataReady(new byte[1]);
        }
        try {
            d.handshakeDataReady(new byte[1]);
            fail();
        } catch (IllegalStateException expected) {
            // expected
        }
        d.protocolError(err("a"));
        d.protocolError(err("b"));
        try {
            d.protocolError(err("c"));
            fail();
        } catch (IllegalStateException expected) {
            // expected
        }
        d.applicationTrafficSecretUpdated(KeyUpdateDirection.READ, new byte[1]);
        d.applicationTrafficSecretUpdated(KeyUpdateDirection.READ, new byte[1]);
        try {
            d.applicationTrafficSecretUpdated(KeyUpdateDirection.READ, new byte[1]);
            fail();
        } catch (IllegalStateException expected) {
            // expected
        }
        d.resetSlots();
        d.handshakeDataReady(new byte[1]);
        d.protocolError(err("d"));
        d.applicationTrafficSecretUpdated(KeyUpdateDirection.READ, new byte[1]);
    }

    @Test
    public void tls12DeliversImmediatelyWhenNotDeferring() {
        StubOffload off = new StubOffload();
        Target12 t = new Target12();
        Tls12DeferredDispatch d = new Tls12DeferredDispatch(scheduler(off), t);
        d.handshakeDataReady(new byte[2]);
        d.keysReady(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, null, null);
        d.sendChangeCipherSpec();
        d.handshakeComplete();
        d.protocolError(err("z"));
        assertEquals("[data2, keys, ccs, done, errz]", t.log.toString());
    }

    @Test
    public void tls12QueuesWhileDeferring() {
        StubOffload off = new StubOffload();
        off.deferring = true;
        Target12 t = new Target12();
        Tls12DeferredDispatch d = new Tls12DeferredDispatch(scheduler(off), t);
        d.handshakeDataReady(new byte[4]);
        d.keysReady(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, null, null);
        d.sendChangeCipherSpec();
        d.handshakeComplete();
        d.protocolError(err("w"));
        assertTrue(t.log.isEmpty());
        off.deferring = false;
        off.flush();
        assertEquals("[data4, keys, ccs, done, errw]", t.log.toString());
    }

    @Test
    public void tls12SlotCapacitiesAndReset() {
        StubOffload off = new StubOffload();
        off.deferring = true;
        Target12 t = new Target12();
        Tls12DeferredDispatch d = new Tls12DeferredDispatch(scheduler(off), t);
        for (int i = 0; i < 12; i++) {
            d.handshakeDataReady(new byte[1]);
        }
        try {
            d.handshakeDataReady(new byte[1]);
            fail();
        } catch (IllegalStateException expected) {
            // expected
        }
        d.protocolError(err("a"));
        d.protocolError(err("b"));
        try {
            d.protocolError(err("c"));
            fail();
        } catch (IllegalStateException expected) {
            // expected
        }
        d.keysReady(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, null, null);
        try {
            d.keysReady(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, null, null);
            fail();
        } catch (IllegalStateException expected) {
            // expected
        }
        d.resetSlots();
        d.keysReady(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, null, null);
        d.handshakeDataReady(new byte[1]);
        d.protocolError(err("e"));
        assertSame(off.queued.get(off.queued.size() - 1), off.queued.get(off.queued.size() - 1));
    }
}
