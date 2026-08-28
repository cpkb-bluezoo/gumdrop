/*
 * HTTPDateCache.java
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

package org.bluezoo.gumdrop.http;

import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Caches the RFC 9110 section 6.6.1 Date response-header value.
 *
 * <p>HTTP dates only require one-second resolution (RFC 9110 section 5.6.7),
 * so formatting the current time from scratch on every response is pure
 * overhead at high request rates. This class keeps the pre-formatted
 * IMF-fixdate string in a volatile field and refreshes it once per second
 * on a daemon timer thread, so response paths only pay a volatile read.
 *
 * <p>The timer is created when this class is first referenced (lazy
 * initialization) and its thread is a daemon, so it never prevents JVM
 * shutdown.
 */
public final class HTTPDateCache {

    /** Shared thread-safe formatter (see HTTPDateFormat). */
    private static final HTTPDateFormat DATE_FORMAT = new HTTPDateFormat();

    /** Cached IMF-fixdate string for the Date header, refreshed once per second. */
    private static volatile String cachedDate;

    /** Daemon scheduler refreshing the cached date. */
    private static final ScheduledExecutorService REFRESHER =
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "gumdrop-http-date-cache");
                thread.setDaemon(true);
                return thread;
            }
        });

    static {
        refresh();
        REFRESHER.scheduleAtFixedRate(new Runnable() {
            public void run() {
                refresh();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private HTTPDateCache() {
    }

    /**
     * Returns the cached IMF-fixdate string for the Date header.
     * The value is at most about one second stale, which RFC 9110
     * section 5.6.7 permits.
     */
    public static String get() {
        return cachedDate;
    }

    private static void refresh() {
        cachedDate = DATE_FORMAT.format(new Date());
    }
}
