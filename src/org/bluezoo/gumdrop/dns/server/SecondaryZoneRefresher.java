/*
 * SecondaryZoneRefresher.java
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
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsZoneClient;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Keeps secondary zones aligned with their master (RFC 1034 section 4.3.5):
 * a serial check on start and then on the SOA refresh interval, transfer
 * when the master is ahead (or nothing is loaded), the SOA retry interval
 * after a failure, and expiry once the master has been unreachable for the
 * SOA expire interval. A NOTIFY triggers the same check at once.
 *
 * <p>All state is touched on one selector loop; the timer thread only
 * hands work back to it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SecondaryZoneRefresher {

    /** Delays a task; the default is a daemon timer thread, tests substitute their own. */
    interface Scheduler {
        void schedule(long delayMs, Runnable task);

        void shutdown();
    }

    /** What the handler does with a transferred zone. */
    interface Installer {
        /** The zone currently served for the origin, or {@code null}. */
        MutableZone current(String origin);

        /** Installs the transferred records, replacing or creating the zone. */
        void install(String origin, List<DnsResourceRecord> records);
    }

    /** Retry interval while there is no zone to take one from. */
    static final long DEFAULT_RETRY_MS = 60000L;

    private final Map<String, State> states = new HashMap<String, State>();
    private final ZoneMasterClient client;
    private final Scheduler scheduler;
    private final Installer installer;
    private SelectorLoop loop;
    private java.util.function.LongSupplier clock = new java.util.function.LongSupplier() {
        @Override
        public long getAsLong() {
            return System.currentTimeMillis();
        }
    };

    private static final class State {
        final String origin;
        final InetSocketAddress master;
        long lastSuccessMillis;
        long generation;
        boolean inFlight;
        boolean recheck;
        volatile boolean expired;

        State(String origin, InetSocketAddress master) {
            this.origin = origin;
            this.master = master;
        }
    }

    SecondaryZoneRefresher(ZoneMasterClient client, Scheduler scheduler, Installer installer) {
        this.client = client;
        this.scheduler = scheduler;
        this.installer = installer;
    }

    static Scheduler threadScheduler() {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "zone-refresh-timer");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        return new Scheduler() {
            @Override
            public void schedule(long delayMs, Runnable task) {
                executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            }

            @Override
            public void shutdown() {
                executor.shutdownNow();
            }
        };
    }

    void add(String origin, InetSocketAddress master) {
        states.put(origin, new State(origin, master));
    }

    boolean isSecondary(String origin) {
        return states.containsKey(origin);
    }

    boolean isExpired(String origin) {
        State state = states.get(origin);
        return state != null && state.expired;
    }

    /**
     * Starts checking {@code origin}: the zone, if any, has been loaded
     * (from its file) and counts as fresh until the expire interval passes.
     */
    void begin(String origin, SelectorLoop loop) {
        State state = states.get(origin);
        if (state == null) {
            return;
        }
        this.loop = loop;
        state.lastSuccessMillis = now();
        check(state);
    }

    /** A NOTIFY for {@code origin}: check now. */
    void notified(String origin, SelectorLoop notifyLoop) {
        State state = states.get(origin);
        if (state != null) {
            if (loop == null) {
                loop = notifyLoop;
            }
            check(state);
        }
    }

    void stop() {
        scheduler.shutdown();
    }

    /** Test seam: replaces the wall clock used for the expire interval. */
    void setClock(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    private void check(final State state) {
        if (state.inFlight) {
            state.recheck = true;
            return;
        }
        state.inFlight = true;
        state.recheck = false;
        state.generation++;
        client.querySoaSerial(loop, state.master, state.origin, new ZoneMasterClient.SerialCallback() {
            @Override
            public void onSerial(int serial) {
                MutableZone zone = installer.current(state.origin);
                // RFC 1982 serial arithmetic
                if (zone == null || serial != zone.getSerial() && serial - zone.getSerial() > 0) {
                    transfer(state);
                } else {
                    succeeded(state);
                }
            }

            @Override
            public void onFailure(Exception error) {
                failed(state);
            }
        });
    }

    private void transfer(final State state) {
        client.transfer(loop, state.master, state.origin, new DnsZoneClient.TransferCallback() {
            @Override
            public void onSuccess(List<DnsResourceRecord> records) {
                try {
                    installer.install(state.origin, records);
                } catch (RuntimeException e) {
                    failed(state);
                    return;
                }
                succeeded(state);
            }

            @Override
            public void onFailure(Exception error) {
                failed(state);
            }
        });
    }

    private void succeeded(State state) {
        state.lastSuccessMillis = now();
        state.expired = false;
        finish(state, refreshMs(state));
    }

    private void failed(State state) {
        MutableZone zone = installer.current(state.origin);
        if (zone != null) {
            long expireMs = zone.getSoaData().expire * 1000L;
            if (expireMs > 0 && now() - state.lastSuccessMillis >= expireMs) {
                state.expired = true;
            }
        }
        finish(state, retryMs(state));
    }

    private void finish(final State state, long nextDelayMs) {
        state.inFlight = false;
        if (state.recheck) {
            check(state);
            return;
        }
        final long generation = state.generation;
        scheduler.schedule(nextDelayMs, new Runnable() {
            @Override
            public void run() {
                dispatch(new Runnable() {
                    @Override
                    public void run() {
                        if (generation == state.generation && !state.inFlight) {
                            check(state);
                        }
                    }
                });
            }
        });
    }

    private void dispatch(Runnable task) {
        if (loop != null) {
            loop.invokeLater(task);
        } else {
            task.run();
        }
    }

    private long refreshMs(State state) {
        MutableZone zone = installer.current(state.origin);
        return zone == null ? DEFAULT_RETRY_MS : Math.max(1000L, zone.getSoaData().refresh * 1000L);
    }

    private long retryMs(State state) {
        MutableZone zone = installer.current(state.origin);
        return zone == null ? DEFAULT_RETRY_MS : Math.max(1000L, zone.getSoaData().retry * 1000L);
    }
}
