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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Caches the RFC 9110 section 6.6.1 Date response-header value.
 *
 * <p>HTTP dates only require one-second resolution (RFC 9110 section 5.6.7),
 * so formatting the current time from scratch on every response is pure
 * overhead at high request rates. This class keeps the pre-formatted
 * IMF-fixdate string, and the complete pre-encoded "Date: ...\r\n" response
 * header line as ASCII bytes, refreshed once per second on a daemon timer
 * thread - so response paths pay only a volatile read, with no per-response
 * String-to-byte encoding either (see {@link #getLineBytes()}).
 *
 * <p>The timer is created when this class is first referenced (lazy
 * initialization) and its thread is a daemon, so it never prevents JVM
 * shutdown.
 */
public final class HTTPDateCache {

    private static final Logger LOGGER = Logger.getLogger(HTTPDateCache.class.getName());

    private static final byte[] DATE_HEADER_PREFIX =
            "Date: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLF = { (byte) 0x0d, (byte) 0x0a };

    /** Shared thread-safe formatter (see HTTPDateFormat). */
    private static final HTTPDateFormat DATE_FORMAT = new HTTPDateFormat();

    /** Cached IMF-fixdate string for the Date header, refreshed once per second. */
    private static volatile String cachedDate;

    /**
     * Cached, complete "Date: <value>\r\n" response header line as ASCII
     * bytes, refreshed alongside {@link #cachedDate}. A wire-writing caller
     * that already holds a reference to {@link #get()}'s current value can
     * bulk-copy this directly instead of re-encoding that value's
     * characters one at a time on every response.
     */
    private static volatile byte[] cachedDateLineBytes;

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
                try {
                    refresh();
                } catch (RuntimeException e) {
                    // ScheduledExecutorService silently suppresses every
                    // future execution of a periodic task once one
                    // invocation throws - without this, a single bad tick
                    // would freeze the cached Date header at a stale value
                    // forever, with no visible symptom anywhere else.
                    LOGGER.log(Level.WARNING,
                            "Failed to refresh cached Date header value; "
                            + "keeping the previous value until the next tick",
                            e);
                }
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

    /**
     * Returns the cached, complete "Date: &lt;value&gt;\r\n" response
     * header line as ASCII bytes, refreshed once per second alongside
     * {@link #get()}.
     */
    public static byte[] getLineBytes() {
        return cachedDateLineBytes;
    }

    private static void refresh() {
        // System.currentTimeMillis() feeds HTTPDateFormat.format(long)
        // directly - no Date object is allocated just to carry this
        // instant through to the formatter.
        String date = DATE_FORMAT.format(System.currentTimeMillis());
        byte[] dateBytes = date.getBytes(StandardCharsets.US_ASCII);
        byte[] line = new byte[DATE_HEADER_PREFIX.length + dateBytes.length + CRLF.length];
        System.arraycopy(DATE_HEADER_PREFIX, 0, line, 0, DATE_HEADER_PREFIX.length);
        System.arraycopy(dateBytes, 0, line, DATE_HEADER_PREFIX.length, dateBytes.length);
        System.arraycopy(CRLF, 0, line, DATE_HEADER_PREFIX.length + dateBytes.length, CRLF.length);
        cachedDate = date;
        cachedDateLineBytes = line;
    }
}
