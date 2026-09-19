/*
 * ZoneStorage.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;

import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads and saves zone files on {@link StorageExecutor} workers.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ZoneStorage {

    private static final Logger LOGGER = Logger.getLogger(ZoneStorage.class.getName());

    private ZoneStorage() {
    }

    static MutableZone loadBlocking(Path path) throws IOException {
        return ZoneFile.load(path).asMutable();
    }

    static void saveBlocking(Path path, MutableZone zone) throws IOException {
        ZoneFileWriter.writeAtomic(path, zone);
    }

    static void loadAsync(StorageExecutor executor, Path path,
                          Executor loopDispatcher,
                          StorageExecutor.Callback<MutableZone> callback) {
        submit(executor, loopDispatcher, new Callable<MutableZone>() {
            @Override
            public MutableZone call() throws IOException {
                return loadBlocking(path);
            }
        }, callback);
    }

    static void saveAsync(StorageExecutor executor, Path path, MutableZone zone,
                          SelectorLoop loop,
                          StorageExecutor.Callback<Void> callback) {
        Executor dispatcher = loopDispatcher(loop);
        submit(executor, dispatcher, new Callable<Void>() {
            @Override
            public Void call() throws IOException {
                saveBlocking(path, zone);
                return null;
            }
        }, callback);
    }

    /**
     * Coalesced save: at most one outstanding write per zone path.
     */
    static final class SaveQueue {

        private final StorageExecutor executor;
        private final Path path;
        private final AtomicBoolean savePending = new AtomicBoolean(false);

        SaveQueue(StorageExecutor executor, Path path) {
            this.executor = executor;
            this.path = path;
        }

        void schedule(MutableZone zone, SelectorLoop loop) {
            if (!savePending.compareAndSet(false, true)) {
                return;
            }
            saveAsync(executor, path, zone, loop, new StorageExecutor.Callback<Void>() {
                @Override
                public void completed(Void result) {
                    savePending.set(false);
                }

                @Override
                public void failed(Throwable error) {
                    savePending.set(false);
                    LOGGER.log(Level.WARNING, MessageFormat.format(
                            DnsServer.L10N.getString("warn.zone_save_failed"),
                            path), error);
                }
            });
        }
    }

    private static <T> void submit(StorageExecutor executor, Executor loopDispatcher,
                                   Callable<T> operation,
                                   StorageExecutor.Callback<T> callback) {
        if (executor == null) {
            try {
                callback.completed(operation.call());
            } catch (Throwable t) {
                callback.failed(t);
            }
            return;
        }
        executor.submit(loopDispatcher, operation, callback);
    }

    static Executor loopDispatcher(final SelectorLoop loop) {
        if (loop == null) {
            return new Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            };
        }
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                loop.invokeLater(command);
            }
        };
    }
}
