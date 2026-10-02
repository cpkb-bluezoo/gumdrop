/*
 * HTTPDateFormatIntegrationTest.java
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

package org.bluezoo.gumdrop.http;

import org.junit.Before;
import org.junit.Test;

import java.text.ParsePosition;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Integration tests for {@link HttpDateFormat}.
 *
 * <p>Integration test: formats and parses concurrently from a real thread pool.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPDateFormatIntegrationTest {

    private HttpDateFormat format;
    private Calendar calendar;
    
    @Before
    public void setUp() {
        format = new HttpDateFormat();
        calendar = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
    }
    
    

        
    
    
    
    
    
    
    
    
    
    
    
    
    
    @Test(timeout = 30000)
    public void testConcurrentFormatAndParseIsThreadSafe() throws Exception {
        // A single instance is shared as a static field and invoked from many
        // worker/selector threads; before the per-thread calendar fix this raced
        // on DateFormat's mutable calendar, producing corrupt output or throwing.
        final HttpDateFormat shared = new HttpDateFormat();

        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
        c.clear();
        c.set(2024, Calendar.NOVEMBER, 15, 12, 30, 45);
        final Date date = c.getTime();
        final String expected = shared.format(date);
        final String parseInput = "Fri, 15 Nov 2024 12:30:45 GMT";

        int threads = 16;
        final int iterations = 5000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final AtomicReference<String> corrupt = new AtomicReference<String>();
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        try {
            final CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                pool.submit(new Runnable() {
                    public void run() {
                        try {
                            for (int i = 0; i < iterations; i++) {
                                String s = shared.format(date);
                                if (!expected.equals(s)) {
                                    corrupt.compareAndSet(null, s);
                                    return;
                                }
                                Date d = shared.parse(parseInput, new ParsePosition(0));
                                if (d == null || d.getTime() != date.getTime()) {
                                    corrupt.compareAndSet(null, "parse=" + d);
                                    return;
                                }
                            }
                        } catch (Throwable ex) {
                            thrown.compareAndSet(null, ex);
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            done.await();
        } finally {
            pool.shutdownNow();
        }

        assertNull("format/parse threw under concurrency: " + thrown.get(), thrown.get());
        assertNull("corrupted result under concurrency; expected '" + expected
                + "' got '" + corrupt.get() + "'", corrupt.get());
    }
}
