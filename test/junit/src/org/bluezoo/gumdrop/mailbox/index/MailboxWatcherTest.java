/*
 * MailboxWatcherTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Dispatch logic of {@link MailboxWatcher} driven by a fake
 * {@link WatchService}: no file system, no OS event latency. Behaviour
 * against the real {@code WatchService} is covered by
 * {@code MailboxWatcherIntegrationTest}.
 *
 * <p>Waits use latches bounded only as a hang guard; events are delivered
 * synchronously to the watcher's thread by the fake.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MailboxWatcherTest {

    private static final long HANG_GUARD_SECONDS = 30;

    private static final class FakeEvent implements WatchEvent<Path> {
        private final Kind<Path> kind;
        private final Path context;

        FakeEvent(Kind<Path> kind, Path context) {
            this.kind = kind;
            this.context = context;
        }

        @Override public Kind<Path> kind() { return kind; }
        @Override public int count() { return 1; }
        @Override public Path context() { return context; }
    }

    private static final class FakeKey implements WatchKey {
        private final Path dir;
        private final List<WatchEvent<?>> events;
        int resets;

        FakeKey(Path dir, List<WatchEvent<?>> events) {
            this.dir = dir;
            this.events = events;
        }

        @Override public boolean isValid() { return true; }
        @Override public List<WatchEvent<?>> pollEvents() { return events; }
        @Override public boolean reset() { resets++; return true; }
        @Override public void cancel() { }
        @Override public java.nio.file.Watchable watchable() { return dir; }
    }

    /** Hands queued keys to the watcher thread; close() wakes and stops it. */
    private static final class FakeWatchService implements WatchService {
        private final BlockingQueue<WatchKey> queue = new LinkedBlockingQueue<WatchKey>();
        private static final WatchKey POISON = new FakeKey(null, null);
        private volatile boolean closed;

        void deliver(Path dir, WatchEvent.Kind<Path> kind, String name) {
            List<WatchEvent<?>> events = new ArrayList<WatchEvent<?>>();
            events.add(new FakeEvent(kind, Paths.get(name)));
            queue.add(new FakeKey(dir, events));
        }

        FakeKey deliverKey(Path dir, List<WatchEvent<?>> events) {
            FakeKey key = new FakeKey(dir, events);
            queue.add(key);
            return key;
        }

        @Override
        public WatchKey take() throws InterruptedException {
            if (closed) {
                throw new ClosedWatchServiceException();
            }
            WatchKey key = queue.take();
            if (key == POISON || closed) {
                throw new ClosedWatchServiceException();
            }
            return key;
        }

        @Override public WatchKey poll() { return queue.poll(); }
        @Override public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }

        @Override
        public void close() {
            closed = true;
            queue.add(POISON);
        }
    }

    /** Registers directories without touching the file system. */
    private static final class FakeRegistrar implements MailboxWatcher.DirectoryRegistrar {
        final List<Path> registered = new CopyOnWriteArrayList<Path>();
        boolean fail;

        @Override
        public void register(Path dir) throws IOException {
            if (fail) {
                throw new IOException("cannot watch");
            }
            registered.add(dir);
        }
    }

    private FakeWatchService service;
    private FakeRegistrar registrar;
    private MailboxWatcher watcher;
    private Path dir;

    @Before
    public void setUp() {
        service = new FakeWatchService();
        registrar = new FakeRegistrar();
        watcher = new MailboxWatcher(service, registrar);
        dir = Paths.get("mailbox-watcher-test-dir").toAbsolutePath().normalize();
    }

    @After
    public void tearDown() {
        watcher.shutdown();
    }

    private static void await(CountDownLatch latch) throws Exception {
        if (!latch.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)) {
            throw new TimeoutException("event not dispatched");
        }
    }

    private static final class Recorder implements MailboxWatcher.ChangeListener {
        final List<String> names = new CopyOnWriteArrayList<String>();
        final CountDownLatch first = new CountDownLatch(1);

        @Override
        public void onChange(String name) {
            names.add(name);
            first.countDown();
        }
    }

    @Test
    public void matchingFileNameIsDispatched() throws Exception {
        Recorder r = new Recorder();
        watcher.register(dir, "target.txt", r);
        service.deliver(dir, StandardWatchEventKinds.ENTRY_CREATE, "target.txt");
        await(r.first);
        assertEquals(Collections.singletonList("target.txt"), r.names);
    }

    @Test
    public void nonMatchingFileNameIsNotDispatched() throws Exception {
        Recorder target = new Recorder();
        Recorder canary = new Recorder();
        watcher.register(dir, "target.txt", target);
        watcher.register(dir, "other.txt", canary);
        service.deliver(dir, StandardWatchEventKinds.ENTRY_CREATE, "other.txt");
        await(canary.first);
        assertTrue("events are handled in order, so target would have fired already",
                target.names.isEmpty());
    }

    @Test
    public void nullFilterMatchesAnyFile() throws Exception {
        Recorder r = new Recorder();
        watcher.register(dir, null, r);
        service.deliver(dir, StandardWatchEventKinds.ENTRY_CREATE, "anything.txt");
        await(r.first);
        assertEquals("anything.txt", r.names.get(0));
    }

    @Test
    public void allListenersOnADirectoryAreNotified() throws Exception {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        watcher.register(dir, "shared.txt", a);
        watcher.register(dir, "shared.txt", b);
        service.deliver(dir, StandardWatchEventKinds.ENTRY_MODIFY, "shared.txt");
        await(a.first);
        await(b.first);
    }

    @Test
    public void directoryIsRegisteredOnceHoweverManyListeners() {
        watcher.register(dir, "a", new Recorder());
        watcher.register(dir, "b", new Recorder());
        assertEquals(1, registrar.registered.size());
    }

    @Test
    public void failedDirectoryRegistrationIsTolerated() throws Exception {
        registrar.fail = true;
        watcher.register(dir, "a", new Recorder());
        assertTrue(registrar.registered.isEmpty());
    }

    @Test
    public void createModifyAndDeleteAllDispatch() throws Exception {
        final CountDownLatch three = new CountDownLatch(3);
        watcher.register(dir, null, new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) { three.countDown(); }
        });
        service.deliver(dir, StandardWatchEventKinds.ENTRY_CREATE, "f");
        service.deliver(dir, StandardWatchEventKinds.ENTRY_MODIFY, "f");
        service.deliver(dir, StandardWatchEventKinds.ENTRY_DELETE, "f");
        await(three);
    }

    @Test
    public void overflowEventsAreSkipped() throws Exception {
        Recorder r = new Recorder();
        watcher.register(dir, null, r);
        List<WatchEvent<?>> events = new ArrayList<WatchEvent<?>>();
        // OVERFLOW carries no usable path context
        events.add(new WatchEvent<Object>() {
            @Override public Kind<Object> kind() { return StandardWatchEventKinds.OVERFLOW; }
            @Override public int count() { return 1; }
            @Override public Object context() { return null; }
        });
        events.add(new FakeEvent(StandardWatchEventKinds.ENTRY_CREATE, Paths.get("real.txt")));
        service.deliverKey(dir, events);
        await(r.first);
        assertEquals(Collections.singletonList("real.txt"), r.names);
    }

    @Test
    public void listenerFailureDoesNotStopDispatch() throws Exception {
        watcher.register(dir, null, new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) {
                throw new IllegalStateException("listener bug");
            }
        });
        final CountDownLatch both = new CountDownLatch(2);
        final List<String> seen = new CopyOnWriteArrayList<String>();
        watcher.register(dir, null, new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) {
                seen.add(name);
                both.countDown();
            }
        });
        service.deliver(dir, StandardWatchEventKinds.ENTRY_CREATE, "one");
        service.deliver(dir, StandardWatchEventKinds.ENTRY_CREATE, "two");
        await(both);
        assertEquals(Arrays.asList("one", "two"), seen);
    }

    @Test
    public void keyIsResetSoFurtherEventsArrive() throws Exception {
        final CountDownLatch both = new CountDownLatch(2);
        watcher.register(dir, null, new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) { both.countDown(); }
        });
        List<WatchEvent<?>> events = new ArrayList<WatchEvent<?>>();
        events.add(new FakeEvent(StandardWatchEventKinds.ENTRY_CREATE, Paths.get("x")));
        FakeKey key = service.deliverKey(dir, events);
        service.deliver(dir, StandardWatchEventKinds.ENTRY_CREATE, "y");
        await(both);
        // the second delivery is only processed after the first key's
        // events were drained, and reset() runs before the next take()
        assertTrue("key must be reset after its events are drained", key.resets >= 1);
    }

    @Test
    public void eventsForUnwatchedDirectoriesAreIgnored() throws Exception {
        Recorder r = new Recorder();
        watcher.register(dir, null, r);
        Path other = Paths.get("unwatched-dir").toAbsolutePath().normalize();
        service.deliver(other, StandardWatchEventKinds.ENTRY_CREATE, "ignored");
        service.deliver(dir, StandardWatchEventKinds.ENTRY_CREATE, "seen");
        await(r.first);
        assertEquals(Collections.singletonList("seen"), r.names);
    }

    @Test
    public void shutdownStopsTheWatcherThread() throws Exception {
        watcher.shutdown();
        watcher.awaitTermination();
        Recorder r = new Recorder();
        watcher.register(dir, "afterShutdown.txt", r);
        service.deliver(dir, StandardWatchEventKinds.ENTRY_CREATE, "afterShutdown.txt");
        assertEquals("no events are dispatched after shutdown", 1, r.first.getCount());
    }
}
