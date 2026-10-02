/*
 * DataSourceDefTest.java
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

import org.junit.Before;
import org.junit.Test;
import org.xml.sax.helpers.AttributesImpl;

import jakarta.servlet.ServletException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link DataSourceDef} against a stub JDBC driver whose connections
 * are dynamic proxies, covering configuration, pooling and URL derivation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DataSourceDefTest {

    /** Records every call made on a stub connection. */
    static final class ConnectionRecorder implements InvocationHandler {
        final List<String> calls = new ArrayList<String>();
        boolean autoCommit = true;
        boolean closed;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            calls.add(name);
            if ("getAutoCommit".equals(name)) {
                return Boolean.valueOf(autoCommit);
            }
            if ("setAutoCommit".equals(name)) {
                autoCommit = ((Boolean) args[0]).booleanValue();
                return null;
            }
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            Class<?> type = method.getReturnType();
            if (type == boolean.class) {
                return Boolean.FALSE;
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

    /** Stub driver that hands out recorded proxy connections. */
    public static class StubDriver implements Driver {
        static final List<ConnectionRecorder> CREATED = new ArrayList<ConnectionRecorder>();
        static final List<String> URLS = new ArrayList<String>();
        static final List<Properties> PROPS = new ArrayList<Properties>();

        public StubDriver() {
        }

        @Override
        public Connection connect(String url, Properties info) {
            URLS.add(url);
            PROPS.add(info);
            ConnectionRecorder recorder = new ConnectionRecorder();
            CREATED.add(recorder);
            return (Connection) Proxy.newProxyInstance(DataSourceDefTest.class.getClassLoader(),
                    new Class<?>[] { Connection.class }, recorder);
        }

        @Override public boolean acceptsURL(String url) { return true; }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    @Before
    public void setUp() {
        StubDriver.CREATED.clear();
        StubDriver.URLS.clear();
        StubDriver.PROPS.clear();
    }

    private static DataSourceDef newDataSource() throws ServletException {
        DataSourceDef ds = new DataSourceDef();
        ds.setName("jdbc/test");
        ds.setClassName(StubDriver.class.getName());
        ds.setUrl("jdbc:stub:test");
        ds.setUser("u");
        ds.setPassword("p");
        return ds;
    }

    @Test
    public void testIsolationLevelNames() {
        assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED,
                DataSourceDef.getIsolationLevel("TRANSACTION_READ_UNCOMMITTED"));
        assertEquals(Connection.TRANSACTION_READ_COMMITTED,
                DataSourceDef.getIsolationLevel("TRANSACTION_READ_COMMITTED"));
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ,
                DataSourceDef.getIsolationLevel("TRANSACTION_REPEATABLE_READ"));
        assertEquals(Connection.TRANSACTION_SERIALIZABLE,
                DataSourceDef.getIsolationLevel("TRANSACTION_SERIALIZABLE"));
        assertEquals(Connection.TRANSACTION_NONE, DataSourceDef.getIsolationLevel("bogus"));
    }

    @Test
    public void testInitFromAttributes() {
        AttributesImpl atts = new AttributesImpl();
        String[][] pairs = {
            { "description", "d" }, { "name", "jdbc/a" }, { "class-name", "a.Driver" },
            { "server-name", "db.example.org" }, { "port-number", "5432" }, { "database-name", "app" },
            { "user", "u" }, { "password", "p" }, { "url", "jdbc:x" },
            { "isolation-level", "TRANSACTION_SERIALIZABLE" }, { "initial-pool-size", "2" },
            { "max-pool-size", "9" }, { "min-pool-size", "1" }, { "max-idle-time", "30" },
            { "max-statements", "5" }, { "transaction-isolation", "TRANSACTION_READ_COMMITTED" },
        };
        for (int i = 0; i < pairs.length; i++) {
            atts.addAttribute("", pairs[i][0], pairs[i][0], "CDATA", pairs[i][1]);
        }
        DataSourceDef ds = new DataSourceDef();
        ds.init(atts);
        assertEquals("jdbc/a", ds.getName());
        assertEquals("a.Driver", ds.getClassName());
        assertEquals("javax.sql.DataSource", ds.getInterfaceName());
        assertEquals(5432, ds.portNumber);
        assertEquals(9, ds.maxPoolSize);
        assertEquals(2, ds.initialPoolSize);
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, ds.isolationLevel);
        assertSame(ds, ds.newInstance());
    }

    @Test
    public void testInitNormalisesPoolSizesAndPicksCredentialsFromProperties() throws Exception {
        DataSourceDef ds = new DataSourceDef();
        ds.setClassName(StubDriver.class.getName());
        ds.setMinPoolSize(-3);
        ds.setMaxPoolSize(1);
        ds.setInitialPoolSize(5);
        ds.addProperty("user", "pu");
        ds.addProperty("password", "pp");
        ds.init();
        assertEquals(0, ds.minPoolSize);
        assertEquals(1, ds.initialPoolSize);
        assertEquals("pu", ds.user);
        assertEquals("pp", ds.password);
        DataSourceDef growing = new DataSourceDef();
        growing.setClassName(StubDriver.class.getName());
        growing.setMinPoolSize(4);
        growing.setMaxPoolSize(2);
        growing.init();
        assertEquals(4, growing.maxPoolSize);
        assertEquals(4, growing.initialPoolSize);
    }

    @Test
    public void testInitFailsForMissingDriver() {
        DataSourceDef ds = new DataSourceDef();
        ds.setClassName("no.such.Driver");
        try {
            ds.init();
            fail("expected ServletException");
        } catch (ServletException e) {
            assertTrue(e.getCause() instanceof ClassNotFoundException);
        }
    }

    @Test
    public void testConnectionsArePooledAndReused() throws Exception {
        DataSourceDef ds = newDataSource();
        ds.setInitialPoolSize(1);
        ds.init();
        Connection c1 = ds.getConnection();
        assertNotNull(c1);
        assertEquals(1, StubDriver.CREATED.size());
        Connection c2 = ds.getConnection();
        assertEquals(2, StubDriver.CREATED.size());
        assertNotSame(c1, c2);
        assertFalse(c1.isClosed());
        c1.close();
        assertTrue(c1.isClosed());
        Connection again = ds.getConnection();
        assertNotNull(again);
        assertEquals("jdbc:stub:test", StubDriver.URLS.get(0));
        assertEquals("u", StubDriver.PROPS.get(0).getProperty("user"));
        assertEquals("p", StubDriver.PROPS.get(0).getProperty("password"));
        ds.close();
    }

    @Test
    public void testSeparatePoolsPerCredentials() throws Exception {
        DataSourceDef ds = newDataSource();
        ds.init();
        Connection a = ds.getConnection("alice", "x");
        Connection b = ds.getConnection("alice", "x");
        Connection c = ds.getConnection("bob", null);
        Connection d = ds.getConnection(null, null);
        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);
        assertNotNull(d);
        assertEquals(4, StubDriver.CREATED.size());
        assertEquals("alice", StubDriver.PROPS.get(0).getProperty("user"));
        assertNull(StubDriver.PROPS.get(3).getProperty("user"));
    }

    @Test
    public void testUrlDerivedFromServerAndDatabase() throws Exception {
        DataSourceDef ds = new DataSourceDef();
        ds.setClassName(StubDriver.class.getName());
        ds.init();
        DataSourceDef.DRIVER_SUBPROTOCOLS.put(StubDriver.class.getName(), "stub");
        try {
            ds.setServerName("db.example.org");
            ds.setPortNumber(1234);
            ds.setDatabaseName("app");
            ds.getConnection();
            assertEquals("jdbc:stub://db.example.org:1234/app", StubDriver.URLS.get(0));
            DataSourceDef bare = new DataSourceDef();
            bare.setClassName(StubDriver.class.getName());
            bare.setDatabaseName("only");
            bare.init();
            bare.getConnection();
            assertEquals("jdbc:stub:///only", StubDriver.URLS.get(1));
        } finally {
            DataSourceDef.DRIVER_SUBPROTOCOLS.remove(StubDriver.class.getName());
        }
    }

    @Test
    public void testUnknownSubprotocolFails() throws Exception {
        DataSourceDef ds = new DataSourceDef();
        ds.setClassName(StubDriver.class.getName());
        ds.setDatabaseName("db");
        ds.init();
        try {
            ds.getConnection();
            fail("expected SQLException");
        } catch (SQLException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testPooledConnectionDelegates() throws Exception {
        DataSourceDef ds = newDataSource();
        ds.init();
        Connection c = ds.getConnection();
        c.setAutoCommit(false);
        assertFalse(c.getAutoCommit());
        c.commit();
        c.rollback();
        c.createStatement();
        c.prepareStatement("select 1");
        c.prepareCall("call x()");
        c.nativeSQL("sql");
        c.getMetaData();
        c.setReadOnly(true);
        c.isReadOnly();
        c.setCatalog("cat");
        c.getCatalog();
        c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        c.getTransactionIsolation();
        c.getWarnings();
        c.clearWarnings();
        c.setHoldability(1);
        c.getHoldability();
        c.setSavepoint();
        c.setSavepoint("sp");
        c.getTypeMap();
        ConnectionRecorder recorder = StubDriver.CREATED.get(0);
        assertTrue(recorder.calls.contains("commit"));
        assertTrue(recorder.calls.contains("rollback"));
        assertTrue(recorder.calls.contains("prepareCall"));
        c.setAutoCommit(true);
        c.commit();
        c.rollback();
    }

    @Test
    public void testDataSourceBoilerplate() throws Exception {
        DataSourceDef ds = new DataSourceDef();
        assertNull(ds.getLogWriter());
        java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.StringWriter());
        ds.setLogWriter(pw);
        assertSame(pw, ds.getLogWriter());
        ds.setLoginTimeout(7);
        assertEquals(7, ds.getLoginTimeout());
        try {
            ds.getParentLogger();
            fail("expected SQLFeatureNotSupportedException");
        } catch (SQLFeatureNotSupportedException e) {
            assertNull(e.getMessage());
        }
        try {
            ds.unwrap(Object.class);
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertNull(e.getMessage());
        }
        try {
            ds.isWrapperFor(Object.class);
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertNull(e.getMessage());
        }
        ds.setDescription("d");
        ds.setServerName("s");
        ds.setIsolationLevel(1);
        ds.setMaxIdleTime(0);
        ds.setMaxStatements(3);
        ds.setTransactionIsolation(2);
        ds.close();
    }

    @Test
    public void testCredentialsEquality() {
        DataSourceDef.Credentials a = new DataSourceDef.Credentials("u", "p");
        DataSourceDef.Credentials b = new DataSourceDef.Credentials(new String("u"), new String("p"));
        DataSourceDef.Credentials c = new DataSourceDef.Credentials("u", null);
        DataSourceDef.Credentials d = new DataSourceDef.Credentials(null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), a.hashCode());
        assertFalse(a.equals(c));
        assertFalse(a.equals("u"));
        assertFalse(c.equals(d));
        assertEquals(d, new DataSourceDef.Credentials(null, null));
    }

    @Test
    public void testCredentialsHashCodeMatchesForEqualValues() {
        DataSourceDef.Credentials a = new DataSourceDef.Credentials("u", "p");
        DataSourceDef.Credentials b = new DataSourceDef.Credentials(new String("u"), new String("p"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testClosedConnectionIsReusedAndOpenAgain() throws Exception {
        DataSourceDef ds = newDataSource();
        ds.init();
        Connection c1 = ds.getConnection();
        c1.close();
        assertTrue(c1.isClosed());
        Connection c2 = ds.getConnection();
        assertSame(c1, c2);
        assertEquals(1, StubDriver.CREATED.size());
        assertFalse(c2.isClosed());
    }

    @Test
    public void testMaxPoolSizeCapsIdleConnections() throws Exception {
        DataSourceDef ds = newDataSource();
        ds.setMaxPoolSize(1);
        ds.init();
        Connection c1 = ds.getConnection();
        Connection c2 = ds.getConnection();
        c1.close();
        c2.close();
        assertEquals(2, StubDriver.CREATED.size());
        int closed = 0;
        for (int i = 0; i < StubDriver.CREATED.size(); i++) {
            if (StubDriver.CREATED.get(i).closed) {
                closed++;
            }
        }
        assertEquals(1, closed);
        ds.getConnection();
        assertEquals(2, StubDriver.CREATED.size());
    }

    @Test
    public void testIsolationLevelNullIsNone() {
        assertEquals(Connection.TRANSACTION_NONE, DataSourceDef.getIsolationLevel(null));
        DataSourceDef ds = new DataSourceDef();
        ds.init(new AttributesImpl());
        assertEquals(Connection.TRANSACTION_NONE, ds.isolationLevel);
    }
}
