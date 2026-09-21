/*
 * InlineSelectorLoop.java
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

import org.bluezoo.gumdrop.SelectorLoop;

/**
 * {@link SelectorLoop} that runs {@link #invokeLater(Runnable)} tasks on the
 * calling thread, for unit tests that drive protocol handlers without a live
 * worker loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class InlineSelectorLoop extends SelectorLoop {

    /** While a task runs, treat the caller as this loop's thread (see {@link #getThread()}). */
    private static final ThreadLocal<Thread> EXECUTING = new ThreadLocal<Thread>();

    public InlineSelectorLoop() {
        super(0);
    }

    @Override
    public Thread getThread() {
        Thread executing = EXECUTING.get();
        return executing != null ? executing : super.getThread();
    }

    @Override
    public void invokeLater(Runnable task) {
        if (Thread.currentThread() == getThread()) {
            task.run();
            return;
        }
        EXECUTING.set(Thread.currentThread());
        try {
            task.run();
        } finally {
            EXECUTING.remove();
        }
    }
}
