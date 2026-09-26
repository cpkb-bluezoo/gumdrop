/*
 * ListenerBindCheck.java
 * Copyright (C) 2025 Chris Burdess
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

import java.util.List;

/**
 * Fails a test at once when a listener could not bind its port.
 *
 * <p>Probing a port to see whether it accepts connections cannot tell the
 * test's own listener from a stale one still holding the port, so a failed
 * bind used to surface later as a refused connection or a missing result.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ListenerBindCheck {

    /**
     * Binding is queued to the accept loop when a listener is added, so a
     * failure can be recorded shortly after a port probe has already
     * succeeded (against a stale listener); wait this long before trusting it.
     */
    private static final long GRACE_MS = 300;

    private ListenerBindCheck() {
    }

    /**
     * Throws if any listener of {@code gumdrop} failed to bind.
     *
     * @param gumdrop the runtime the test started
     * @throws IllegalStateException naming each listener that failed
     */
    public static void assertBound(Gumdrop gumdrop) throws InterruptedException {
        long deadline = System.currentTimeMillis() + GRACE_MS;
        List<String> failures = gumdrop.getBindFailures();
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            failures = gumdrop.getBindFailures();
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Listener failed to bind: " + failures);
        }
    }
}
