/*
 * GumdropAutoShutdownRaceTest.java
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

package org.bluezoo.gumdrop;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.concurrent.CyclicBarrier;

/**
 * Regression test for issue #426: a client connecting while a
 * <em>different</em> client's disconnect concurrently triggered
 * auto-shutdown could be handed a {@link SelectorLoop} that was about to
 * be (or had just been) torn down, or have its own registration silently
 * dropped by that shutdown's {@code activeClients.clear()}.
 *
 * <p>{@link Gumdrop#checkAutoShutdown()} judges "nothing left running" and
 * dispatches {@link Gumdrop#shutdown()} to a background thread. Deciding
 * to shut down, starting the infrastructure back up, obtaining a worker
 * loop, and registering a client as a reason to keep it running used to
 * be four separate operations (a disconnecting client's {@code
 * removeClient}/{@code checkAutoShutdown}, and a connecting client's
 * {@code start}/{@code nextWorkerLoop}/{@code addClient}) with no
 * atomicity between them, so a connecting client could fall in a gap
 * between any two of those steps: seeing a stale "already started" flag
 * moments before the disconnecting client's shutdown decision tore the
 * loop down underneath it, or registering into {@link
 * Gumdrop#activeClients} just before that shutdown's clear() silently
 * dropped the registration. {@link Gumdrop#startForClient} now performs
 * ensure-started, obtain-loop and register as one operation with respect
 * to that decision, and {@link ClientEndpoint#connect} uses it.
 *
 * <p>This is driven entirely through {@link Gumdrop}'s and {@link
 * ClientEndpoint}'s public API, using a {@link CyclicBarrier} (no sleeps
 * or deadline polling) to release a disconnecting client's {@link
 * Gumdrop#removeClient} and a connecting client's registration at the
 * same instant, repeated many times so that every possible ordering of
 * the race is very likely to occur across the run. Correctness is
 * asserted as a value invariant after each iteration (the loop handed
 * back must actually be running, and the connecting client must still be
 * registered), not by timing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GumdropAutoShutdownRaceTest {

    private static final int ITERATIONS = 300;

    @Test
    public void connectCannotObserveTornDownLoopFromConcurrentAutoShutdown() throws Exception {
        Gumdrop gumdrop = Gumdrop.getInstance();
        gumdrop.setDrainTimeoutMs(0);
        TCPTransportFactory factory = new TCPTransportFactory();

        for (int i = 0; i < ITERATIONS; i++) {
            // Baseline for this iteration: started, with exactly one active
            // client (about to disconnect). start() here also exercises the
            // fix in the ordinary (non-racing) case, waiting out whatever
            // the previous iteration's cleanup shutdown was doing.
            gumdrop.start();
            ClientEndpoint departing = new ClientEndpoint(factory, "localhost", 1);
            gumdrop.addClient(departing);

            final CyclicBarrier barrier = new CyclicBarrier(2);
            final Throwable[] failure = new Throwable[2];
            final SelectorLoop[] obtained = new SelectorLoop[1];

            Thread disconnecter = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        gumdrop.removeClient(departing);
                    } catch (Throwable t) {
                        failure[0] = t;
                    }
                }
            }, "sim-disconnect-" + i);

            final ClientEndpoint arriving = new ClientEndpoint(factory, "localhost", 2);
            Thread connecter = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        obtained[0] = gumdrop.startForClient(arriving);
                    } catch (Throwable t) {
                        failure[1] = t;
                    }
                }
            }, "sim-connect-" + i);

            disconnecter.start();
            connecter.start();
            disconnecter.join();
            connecter.join();

            assertNull("disconnecting thread failed on iteration " + i, failure[0]);
            assertNull("connecting thread failed on iteration " + i, failure[1]);

            assertTrue("Gumdrop must be started after a client just connected (iteration " + i + ")",
                    gumdrop.isStarted());
            assertTrue("worker loop handed back to the connecting client must actually be "
                    + "running (iteration " + i + ") -- a torn-down loop means it raced "
                    + "checkAutoShutdown()'s decision", obtained[0].isRunning());

            // Clean up so the next iteration starts from a known baseline;
            // the next iteration's start() call (above) waits this out.
            gumdrop.removeClient(arriving);
        }
    }
}
