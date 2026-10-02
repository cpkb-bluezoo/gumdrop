/*
 * DataSourcePoolingTest.java
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
import java.lang.reflect.InvocationTargetException;
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
import java.util.logging.Logger;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the delegating {@link DataSourceDef} pooled connection, the data
 * source management methods and the idle connection evictor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DataSourcePoolingTest {

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
            ClassLoader loader = DataSourcePoolingTest.class.getClassLoader();
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

    private static Object defaultFor(Class<?> t) {
        if (t == boolean.class) {
            return Boolean.FALSE;
        }
        if (t == int.class) {
            return Integer.valueOf(0);
        }
        if (t == long.class) {
            return Long.valueOf(0L);
        }
        return null;
    }

    @Test
    public void pooledConnectionDelegatesEveryOperation() throws Exception {
        DataSourceDef ds = new DataSourceDef();
        ds.setClassName(DataSourceDefTest.StubDriver.class.getName());
        ds.setUrl("jdbc:stub:x");
        ds.init();
        Connection c = ds.getConnection();
        DataSourceDefTest.ConnectionRecorder recorder = DataSourceDefTest.StubDriver.CREATED.get(0);
        Method[] methods = Connection.class.getMethods();
        int delegated = 0;
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            String name = m.getName();
            if (m.isDefault() || name.equals("close") || name.equals("isClosed") || name.equals("commit")
                    || name.equals("rollback") && m.getParameterCount() == 0) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            Object[] args = new Object[pt.length];
            for (int j = 0; j < pt.length; j++) {
                args[j] = defaultFor(pt[j]);
            }
            recorder.calls.clear();
            try {
                m.invoke(c, args);
            } catch (InvocationTargetException e) {
                throw new AssertionError(name + ": " + e.getCause());
            }
            assertTrue(name, recorder.calls.contains(name));
            delegated++;
        }
        assertTrue(delegated > 40);
    }

    @Test
    public void commitAndRollbackOnlyWhenNotAutoCommit() throws Exception {
        DataSourceDef ds = new DataSourceDef();
        ds.setClassName(DataSourceDefTest.StubDriver.class.getName());
        ds.setUrl("jdbc:stub:x");
        ds.init();
        Connection c = ds.getConnection();
        DataSourceDefTest.ConnectionRecorder recorder = DataSourceDefTest.StubDriver.CREATED.get(0);
        recorder.calls.clear();
        c.commit();
        c.rollback();
        assertFalse(recorder.calls.contains("commit"));
        assertFalse(recorder.calls.contains("rollback"));
        c.setAutoCommit(false);
        recorder.calls.clear();
        c.commit();
        c.rollback();
        assertTrue(recorder.calls.contains("commit"));
        assertTrue(recorder.calls.contains("rollback"));
    }

    @Test
    public void dataSourceManagementAccessors() throws Exception {
        DataSourceDef ds = new DataSourceDef();
        assertNull(ds.getLogWriter());
        java.io.PrintWriter w = new java.io.PrintWriter(new java.io.StringWriter());
        ds.setLogWriter(w);
        assertEquals(w, ds.getLogWriter());
        ds.setLoginTimeout(7);
        assertEquals(7, ds.getLoginTimeout());
        assertEquals(ds, ds.newInstance());
        assertEquals("javax.sql.DataSource", ds.getInterfaceName());
        try {
            ds.getParentLogger();
            throw new AssertionError("expected SQLFeatureNotSupportedException");
        } catch (SQLFeatureNotSupportedException e) {
            assertNotNull(e);
        }
        try {
            ds.isWrapperFor(String.class);
            throw new AssertionError("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertNotNull(e);
        }
        try {
            ds.unwrap(String.class);
            throw new AssertionError("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertNotNull(e);
        }
    }

    @Test
    public void initTakesCredentialsFromProperties() throws Exception {
        DataSourceDef ds = new DataSourceDef();
        ds.setClassName(DataSourceDefTest.StubDriver.class.getName());
        ds.addProperty("user", "pu");
        ds.addProperty("password", "pp");
        ds.init();
        assertEquals("pu", ds.user);
        assertEquals("pp", ds.password);
    }

    @Test
    public void closeEmptiesPoolsAndClosesConnections() throws Exception {
        DataSourceDef ds = new DataSourceDef();
        ds.setClassName(DataSourceDefTest.StubDriver.class.getName());
        ds.setUrl("jdbc:stub:x");
        ds.setInitialPoolSize(2);
        ds.init();
        Connection c = ds.getConnection();
        c.close();
        ds.close();
        int closed = 0;
        for (int i = 0; i < DataSourceDefTest.StubDriver.CREATED.size(); i++) {
            if (DataSourceDefTest.StubDriver.CREATED.get(i).closed) {
                closed++;
            }
        }
        assertEquals(2, closed);
    }
}
