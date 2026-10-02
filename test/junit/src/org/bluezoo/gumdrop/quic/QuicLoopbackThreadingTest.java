/*
 * QuicLoopbackThreadingTest.java
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


package org.bluezoo.gumdrop.quic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.junit.Test;

/**
 * A connection's send queues belong to its selector loop's thread: a
 * stream written to from any other thread (an application thread sending a
 * WebSocket message, say) must be handed to the loop rather than touch the
 * queues concurrently with the loop's own flushing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackThreadingTest {

    /** Treats the thread that built it as the loop thread; queues work from others. */
    private static final class DeferringLoop extends SelectorLoop {
        private final Thread loopThread = Thread.currentThread();
        final List<Runnable> deferred = new ArrayList<Runnable>();

        DeferringLoop() {
            super(0);
        }

        @Override
        public Thread getThread() {
            return loopThread;
        }

        @Override
        public void invokeLater(Runnable task) {
            if (Thread.currentThread() == loopThread) {
                task.run();
            } else {
                synchronized (deferred) {
                    deferred.add(task);
                }
            }
        }
    }

    @Test
    public void sendFromAnotherThreadIsDeferredToTheLoop() throws Exception {
        QuicLoopbackFramesTest.Fixture f = QuicLoopbackFramesTest.Fixture.create();
        final Endpoint stream = f.client.conn.openStream(new Rec());
        assertNotNull(stream);
        DeferringLoop loop = new DeferringLoop();
        f.lb.clientEngine.setSelectorLoop(loop);

        Thread other = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
            }
        });
        other.start();
        other.join();

        Map<?, ?> pending = (Map<?, ?>) QuicForger.field(f.client.conn, "pendingStream");
        assertTrue("queues untouched off the loop thread", pending.isEmpty());
        assertEquals(1, loop.deferred.size());
        loop.deferred.get(0).run();
        f.lb.pump();
        assertEquals(3, f.server.bidi.recs.get(0).bytes);
    }
}
