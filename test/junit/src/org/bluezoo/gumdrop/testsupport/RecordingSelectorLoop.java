/*
 * RecordingSelectorLoop.java
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

import org.bluezoo.gumdrop.SelectorLoop;

/**
 * {@link SelectorLoop} that never runs anything itself: tasks passed to
 * {@link #tryInvokeLater(Runnable)} are recorded so a test can assert that work
 * was handed to the loop rather than done by the caller, and then run them
 * (on the test thread) with {@link #runRecorded()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class RecordingSelectorLoop extends SelectorLoop {

    private final List<Runnable> tasks = new ArrayList<Runnable>();

    public RecordingSelectorLoop() {
        super(0);
    }

    @Override
    public boolean tryInvokeLater(Runnable task) {
        synchronized (tasks) {
            tasks.add(task);
        }
        return true;
    }

    /**
     * @return the number of tasks recorded and not yet run
     */
    public int recordedCount() {
        synchronized (tasks) {
            return tasks.size();
        }
    }

    /**
     * Runs and forgets every recorded task, in order, on the calling thread.
     */
    public void runRecorded() {
        List<Runnable> copy;
        synchronized (tasks) {
            copy = new ArrayList<Runnable>(tasks);
            tasks.clear();
        }
        for (Runnable task : copy) {
            task.run();
        }
    }
}
