/*
 * MailboxWatcher.java
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

package org.bluezoo.gumdrop.mailbox.index;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches mailbox directories for external changes (mail delivered by
 * another process, or another gumdrop instance sharing the same store) and
 * notifies a listener so a background catch-up index job can be submitted
 * to {@link MailboxIndexer}, even when no live session currently has the
 * mailbox open.
 *
 * <p>Registrations are persistent for the lifetime of the server: once a
 * mailbox's directory is watched (at eager per-store enumeration time, see
 * issue #163), it stays watched rather than being torn down when the last
 * session for that mailbox closes. This trades a per-registered-directory
 * OS watch resource for catching changes made while nobody has the
 * mailbox open; very large deployments with huge numbers of mailboxes may
 * want to bound or evict registrations, which this class does not attempt.
 *
 * <p>A single dedicated thread drains {@link WatchService#take()} and
 * dispatches to listeners, mirroring {@link MailboxIndexer}'s use of one
 * worker rather than a pool - watch dispatch is cheap (it only submits a
 * background indexing job), so no pool is needed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MailboxWatcher implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(MailboxWatcher.class.getName());

    /**
     * Notified when a watched directory reports a change to a file
     * matching this registration's filter.
     */
    public interface ChangeListener {
        void onChange(String fileName);
    }

    private static final class WatchedDir {
        final List<Registration> registrations = new CopyOnWriteArrayList<>();
    }

    private static final class Registration {
        final String fileNameFilter;
        final ChangeListener listener;

        Registration(String fileNameFilter, ChangeListener listener) {
            this.fileNameFilter = fileNameFilter;
            this.listener = listener;
        }
    }

    private final WatchService watchService;
    private final Map<Path, WatchedDir> watched = new ConcurrentHashMap<>();
    private final Thread thread;
    private volatile boolean running = true;

    public MailboxWatcher() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        thread = new Thread(this, "gumdrop-mailbox-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Registers interest in changes under a directory.
     *
     * @param directory the directory to watch (must already exist)
     * @param fileNameFilter if non-null, only notify for changes to a file
     *      with exactly this name; if null, notify for any file in the
     *      directory (used for maildir's {@code cur}/{@code new})
     * @param listener invoked (on this watcher's own thread) for each
     *      matching change
     */
    public void register(Path directory, String fileNameFilter, ChangeListener listener) {
        Path dir = directory.toAbsolutePath().normalize();
        WatchedDir wd;
        synchronized (watched) {
            wd = watched.get(dir);
            if (wd == null) {
                wd = registerDirectory(dir);
                if (wd == null) {
                    return;
                }
                watched.put(dir, wd);
            }
        }
        wd.registrations.add(new Registration(fileNameFilter, listener));
    }

    private WatchedDir registerDirectory(Path dir) {
        try {
            dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            return new WatchedDir();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not watch directory: " + dir, e);
            return null;
        }
    }

    public void shutdown() {
        running = false;
        thread.interrupt();
        try {
            watchService.close();
        } catch (IOException e) {
            // ignore - shutting down anyway
        }
    }

    @Override
    public void run() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                continue;
            }
            Path dir = (Path) key.watchable();
            WatchedDir wd = watched.get(dir.toAbsolutePath().normalize());
            if (wd != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    String changedName = ((Path) event.context()).getFileName().toString();
                    for (Registration r : wd.registrations) {
                        if (r.fileNameFilter == null || r.fileNameFilter.equals(changedName)) {
                            try {
                                r.listener.onChange(changedName);
                            } catch (Exception e) {
                                LOGGER.log(Level.WARNING, "Mailbox watch listener failed", e);
                            }
                        }
                    }
                }
            }
            key.reset();
        }
    }
}
