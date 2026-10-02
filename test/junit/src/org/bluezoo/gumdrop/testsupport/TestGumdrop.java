/*
 * TestGumdrop.java
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

package org.bluezoo.gumdrop.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.bluezoo.gumdrop.Gumdrop;

/**
 * Factory for a {@link Gumdrop} that never starts a thread, for unit tests
 * that need the runtime only as a bag of services (a worker loop, the
 * storage and crypto executors, the client registry).
 *
 * <p>The worker loop is an {@link InlineSelectorLoop} and the storage and
 * crypto work runs on the calling thread, so everything a handler offloads
 * completes before the offloading call returns. A test that wants to control
 * when offloaded work runs passes its own capturing {@link Executor} to
 * {@link #create(Executor)} and runs the captured tasks by hand.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TestGumdrop {

    private TestGumdrop() {
    }

    /**
     * Executor that runs each task immediately on the calling thread.
     */
    public static final class DirectExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    /**
     * Executor that holds each task until the test runs it, to assert that
     * work was offloaded and to control exactly when it completes.
     */
    public static final class QueuedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        /**
         * Returns the number of tasks waiting to be run.
         *
         * @return the queued task count
         */
        public int pendingCount() {
            return tasks.size();
        }

        /**
         * Runs queued tasks, including any they queue in turn, until none
         * are left.
         *
         * @return the number of tasks run
         */
        public int runAll() {
            int run = 0;
            while (!tasks.isEmpty()) {
                Runnable task = tasks.remove(0);
                task.run();
                run++;
            }
            return run;
        }
    }

    /**
     * Creates a thread-free Gumdrop whose offloaded work runs inline.
     *
     * @return a new, not yet started instance
     */
    public static Gumdrop create() {
        return create(new DirectExecutor());
    }

    /**
     * Creates a thread-free Gumdrop whose offloaded storage and crypto work
     * is handed to {@code offload}.
     *
     * @param offload where storage and crypto tasks are run
     * @return a new, not yet started instance
     */
    public static Gumdrop create(Executor offload) {
        InlineSelectorLoop loop = new InlineSelectorLoop();
        return Gumdrop.embedded(loop, offload);
    }
}
