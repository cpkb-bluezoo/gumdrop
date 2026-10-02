/*
 * MockWatchService.java
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

package org.bluezoo.gumdrop.servlet;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/**
 * A hand-written mock {@link WatchService} that never signals a key. It
 * lets the hot-deployment and hot-reload watchers be constructed and driven
 * (through their package-private key handling) on a file system, such as the
 * in-memory one, that has no watch service of its own.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MockWatchService implements WatchService {

    private boolean closed;

    @Override
    public void close() throws IOException {
        closed = true;
    }

    /**
     * Returns whether {@link #close()} has been called.
     */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public WatchKey poll() {
        checkOpen();
        return null;
    }

    @Override
    public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
        checkOpen();
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return null;
    }

    @Override
    public WatchKey take() throws InterruptedException {
        checkOpen();
        throw new InterruptedException();
    }

    private void checkOpen() {
        if (closed) {
            throw new ClosedWatchServiceException();
        }
    }
}
