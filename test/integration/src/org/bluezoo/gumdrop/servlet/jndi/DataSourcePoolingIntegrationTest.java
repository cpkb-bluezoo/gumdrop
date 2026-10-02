/*
 * DataSourcePoolingIntegrationTest.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the delegating {@link DataSourceDef} pooled connection, the data
 * source management methods and the idle connection evictor.
 *
 * <p>Integration test: runs the idle evictor on a real thread using the real
 * clock and its sleep interval.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DataSourcePoolingIntegrationTest {

    /** Recorder whose connections signal a latch when closed. */
    static final class LatchRecorder implements InvocationHandler {
        final List<String> calls = new ArrayList<String>();
        final CountDownLatch closedLatch = new CountDownLatch(1);

        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            calls.add(name);
            if (name.equals("close")) {
                closedLatch.countDown();
                return null;
            }
            Class<?> type = method.getReturnType();
            if (type == boolean.class) {
                return Boolean.TRUE;
            }
            if (type == int.class) {
                return Integer.valueOf(0);
            }
            if (type == long.class) {
                return Long.valueOf(0L);
            }
            return null;
        }
    }

    /** Driver handing out latch-recording connections. */
    public static class LatchDriver implements Driver {
        static final List<LatchRecorder> CREATED = new ArrayList<LatchRecorder>();

        public LatchDriver() {
        }

        public Connection connect(String url, Properties info) {
            LatchRecorder r = new LatchRecorder();
            CREATED.add(r);
            ClassLoader loader = DataSourcePoolingIntegrationTest.class.getClassLoader();
            Object o = Proxy.newProxyInstance(loader, new Class<?>[] { Connection.class }, r);
            return (Connection) o;
        }

        public boolean acceptsURL(String url) {
            return true;
        }

        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        public int getMajorVersion() {
            return 1;
        }

        public int getMinorVersion() {
            return 0;
        }

        public boolean jdbcCompliant() {
            return false;
        }

        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    @Before
    public void setUp() {
        LatchDriver.CREATED.clear();
        DataSourceDefTest.StubDriver.CREATED.clear();
    }

    @Test
    public void idleEvictorClosesExpiredConnectionsAndStopsOnInterrupt() throws Exception {
        DataSourceDef ds = new DataSourceDef();
        ds.setClassName(LatchDriver.class.getName());
        ds.setUrl("jdbc:latch:x");
        ds.setInitialPoolSize(1);
        ds.init();
        // creates the pool with one pre-opened connection whose timestamp is
        // the epoch, so it is already past any idle limit
        Connection busy = ds.getConnection();
        Connection idleOne = ds.getConnection();
        busy.close();
        idleOne.close();
        ds.setMaxIdleTime(1);
        // the first connection created has timestamp zero
        LatchRecorder expired = LatchDriver.CREATED.get(0);
        final Runnable task = ds.new IdleTask();
        Thread t = new Thread(task, "test-idle-evictor");
        t.setDaemon(true);
        t.start();
        assertTrue(expired.closedLatch.await(10, TimeUnit.SECONDS));
        t.interrupt();
        t.join(10000L);
        assertFalse("evictor must stop when interrupted", t.isAlive());
    }
}
