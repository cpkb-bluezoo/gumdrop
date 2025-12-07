/*
 * DataSourceDef.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

package org.bluezoo.gumdrop.servlet;

import java.io.PrintWriter;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.SQLClientInfoException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.sql.DataSource;

import org.xml.sax.Attributes;

/**
 * JDBC DataSource for a web application.
 * This proxies the DriverManager, and provides simple connection pooling
 * facilities.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DataSourceDef extends Resource implements DataSource {

    // There should be a definitive list of these somewhere
    static Map<String,String> DRIVER_SUBPROTOCOLS;
    static {
        DRIVER_SUBPROTOCOLS = new HashMap<>();
        DRIVER_SUBPROTOCOLS.put("com.mysql.cj.jdbc.Driver", "mysql");
        DRIVER_SUBPROTOCOLS.put("org.postgresql.Driver", "postgresql");
        DRIVER_SUBPROTOCOLS.put("oracle.jdbc.OracleDriver", "oracle:thin");
        DRIVER_SUBPROTOCOLS.put("com.microsoft.sqlserver.jdbc.SQLServerDriver", "sqlserver");
        DRIVER_SUBPROTOCOLS.put("com.ibm.db2.jcc.DB2Driver", "db2");
        DRIVER_SUBPROTOCOLS.put("org.apache.derby.jdbc.EmbeddedDriver", "derby");
        DRIVER_SUBPROTOCOLS.put("org.apache.derby.jdbc.ClientDriver", "derby");
        DRIVER_SUBPROTOCOLS.put("org.sqlite.JDBC", "sqlite");
        DRIVER_SUBPROTOCOLS.put("org.mariadb.jdbc.Driver", "mariadb");
        DRIVER_SUBPROTOCOLS.put("org.h2.Driver", "h2");
    }

    String description;
    String name; // JNDI name
    String className; // JDBC driver class
    String serverName; // name of the database server
    int portNumber = -1; // port number of the database server
    String databaseName; // database name
    String user; // user name for database connection
    String password; // password for database connection
    String url; // full JDBC connection URL (alternative to server-name, port-number, database-name)
    int isolationLevel = Connection.TRANSACTION_NONE; // constants in Connection
    int initialPoolSize = 0; // initial number of connections in the pool
    int maxPoolSize = Integer.MAX_VALUE; // max number of connections in the pool
    int minPoolSize = 0; // min number of connections in the pool
    int maxIdleTime = 0; // maximum time a connection can remain idle in the pool (0 = no timeout)
    int maxStatements; // number of prepared statements to be cached
    int transactionIsolation = Connection.TRANSACTION_NONE; // default transaction isolation level
    Properties properties;

    private Driver driver;
    private String driverSubprotocol;
    private Map<Credentials,Queue<Connection>> connectionPools = new ConcurrentHashMap<>();
    private int loginTimeout;
    private PrintWriter logWriter;
    private Thread idleThread;

    @Override public void addProperty(String name, String value) {
        if (properties == null) {
            properties = new Properties();
        }
        properties.setProperty(name, value);
    }

    static int getIsolationLevel(String s) {
        switch (s) {
            case "TRANSACTION_READ_UNCOMMITTED":
                return Connection.TRANSACTION_READ_UNCOMMITTED;
            case "TRANSACTION_READ_COMMITTED":
                return Connection.TRANSACTION_READ_COMMITTED;
            case "TRANSACTION_REPEATABLE_READ":
                return Connection.TRANSACTION_REPEATABLE_READ;
            case "TRANSACTION_SERIALIZABLE":
                return Connection.TRANSACTION_SERIALIZABLE;
            default:
                return Connection.TRANSACTION_NONE;
        }
    }

    @Override public void init(Attributes config) {
        description = config.getValue("description");
        name = config.getValue("name");
        className = config.getValue("class-name");
        serverName = config.getValue("server-name");
        portNumber = initIntValue(config.getValue("port-number"), -1);
        databaseName = config.getValue("database-name");
        user = config.getValue("user");
        password = config.getValue("password");
        url = config.getValue("url");
        isolationLevel = getIsolationLevel(config.getValue("isolation-level"));
        initialPoolSize = initIntValue(config.getValue("initial-pool-size"), 0);
        maxPoolSize = initIntValue(config.getValue("max-pool-size"), Integer.MAX_VALUE);
        minPoolSize = initIntValue(config.getValue("min-pool-size"), 0);
        maxIdleTime = initIntValue(config.getValue("max-idle-time"), 0);
        maxStatements = initIntValue(config.getValue("max-statements"), 0);
        transactionIsolation = getIsolationLevel(config.getValue("transaction-isolation"));
    }

    private int initIntValue(String value, int defaultValue) {
        return (value == null) ? defaultValue : Integer.parseInt(value);
    }

    /**
     * Initialize the connection pool(s). TODO
     */
    @Override synchronized void init() throws ServletException {
        if (minPoolSize < 0) {
            minPoolSize = 0;
        }
        if (maxPoolSize < minPoolSize) {
            maxPoolSize = minPoolSize;
        }
        if (initialPoolSize < minPoolSize) {
            initialPoolSize = minPoolSize;
        }
        if (initialPoolSize > maxPoolSize) {
            initialPoolSize = maxPoolSize;
        }
        if (user == null && properties != null) {
            user = properties.getProperty("user");
        }
        if (password == null && properties != null) {
            password = properties.getProperty("password");
        }
        try {
            Class driverClass = Class.forName(className);
            driver = (Driver) driverClass.newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            throw new ServletException(e);
        }
    }

    @Override synchronized void close() {
        if (idleThread != null) {
            idleThread.interrupt();
            idleThread = null;
        }
        for (Queue<Connection> connectionPool : connectionPools.values()) {
            for (Connection c = connectionPool.poll(); c != null; c = connectionPool.poll()) {
                PooledConnection pc = (PooledConnection) c;
                try {
                    pc.c.close();
                } catch (SQLException e) {
                    String message = Context.L10N.getString("err.closing_connection");
                    Context.LOGGER.warning(message);
                }
            }
        }
    }

    @Override String getName() {
        return name;
    }

    @Override String getClassName() {
        return className;
    }

    @Override String getInterfaceName() {
        return "javax.sql.DataSource";
    }

    @Override Object newInstance() {
        return this;
    }

    Connection createConnection(String user, String password, Queue<Connection> connectionPool) throws SQLException {
        Properties driverProperties = new Properties();
        if (properties != null) {
            driverProperties.putAll(properties);
        }
        if (user != null) {
            driverProperties.setProperty("user", user);
        }
        if (password != null) {
            driverProperties.setProperty("password", password);
        }
        if (url == null && databaseName != null) {
            // Try to construct a URL from the server-name,
            // port-number and database-name. It should have the form
            // jdbc:<subprotocol>://<host>[:</port>]/<database>
            String subprotocol = DRIVER_SUBPROTOCOLS.get(className);
            if (subprotocol == null) {
                String message = Context.L10N.getString("err.unknown_subprotocol");
                message = MessageFormat.format(message, className);
                throw new SQLException(message);
            }
            StringBuilder buf = new StringBuilder("jdbc:");
            buf.append(subprotocol);
            buf.append("://");
            if (serverName != null) {
                buf.append(serverName);
                if (portNumber > 0) {
                    buf.append(':').append(portNumber);
                }
            }
            buf.append('/');
            buf.append(databaseName);
            url = buf.toString();
        }
        DriverManager.setLoginTimeout(loginTimeout); // JDBC drivers are supposed to honor this
        Connection c = driver.connect(url, driverProperties);
        return new PooledConnection(c, connectionPool);
    }

    Queue<Connection> createConnectionPool(String user, String password) throws SQLException {
        Queue<Connection> connectionPool = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < initialPoolSize; i++) {
            connectionPool.add(createConnection(user, password, connectionPool));
        }
        if (maxIdleTime != 0) {
            synchronized (this) {
                if (idleThread == null) {
                    // Create the thread to evict idle connections
                    idleThread = new Thread(new IdleTask(), "datasource-idle-evictor");
                    idleThread.setDaemon(true);
                    idleThread.start();
                }
            }
        }
        return connectionPool;
    }

    /**
     * Evict connections that have been in the pool for longer than
     * maxIdleTime
     */
    class IdleTask implements Runnable {

        public void run() {
            while (!Thread.interrupted()) {
                for (Queue<Connection> connectionPool : connectionPools.values()) {
                    Iterator<Connection> i = connectionPool.iterator();
                    while (i.hasNext()) {
                        PooledConnection pc = (PooledConnection) i.next();
                        long expiryTime = pc.timestamp + (((long) maxIdleTime) * 1000L);
                        if (System.currentTimeMillis() > expiryTime && connectionPool.size() > minPoolSize) {
                            // evict
                            i.remove();
                            try {
                                pc.c.close();
                            } catch (SQLException e) {
                                String message = Context.L10N.getString("err.closing_connection");
                                Context.LOGGER.warning(message);
                            }
                        }
                    }
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                }
            }
        }

    }

    // -- DataSource --

    @Override public Connection getConnection() throws SQLException {
        return getConnection(user, password);
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
        Queue<Connection> connectionPool;
        synchronized (connectionPools) {
            Credentials credentials = new Credentials(username, password);
            connectionPool = connectionPools.get(credentials);
            if (connectionPool == null) {
                connectionPool = createConnectionPool(username, password);
                connectionPools.put(credentials, connectionPool);
            }
        }
        synchronized (connectionPool) {
            Connection c = connectionPool.poll();
            if (c == null) {
                c = createConnection(username, password, connectionPool); // will be returned to pool when closed
            }
            return c;
        }
    }

    @Override public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override public void setLogWriter(PrintWriter out) {
        logWriter = out;
    }

    @Override public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override public void setLoginTimeout(int seconds) {
        loginTimeout = seconds;
    }

    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override public boolean isWrapperFor(Class t) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override public Object unwrap(Class t) throws SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Used as a key for the connection pool map.
     */
    static final class Credentials {

        final String user;
        final String password;
        private int hashCode = -1;

        Credentials(String user, String password) {
            this.user = (user == null) ? null : user.intern();
            this.password = (password == null) ? null : password.intern();
        }

        public int hashCode() {
            if (hashCode == -1) {
                StringBuilder buf = new StringBuilder();
                if (user != null) {
                    buf.append(user);
                }
                buf.append('\u0000');
                if (password != null) {
                    buf.append(password);
                }
                hashCode = buf.hashCode();
            }
            return hashCode;
        }

        public boolean equals(Object other) {
            if (other instanceof Credentials) {
                Credentials credentials = (Credentials) other;
                return credentials.user == user && credentials.password == password;
            }
            return false;
        }

    }

    class PooledConnection implements Connection {

        final Connection c;
        final Queue<Connection> connectionPool;
        final boolean originalAutoCommit;
        long timestamp;
        boolean closed;

        PooledConnection(Connection c, Queue<Connection> connectionPool) throws SQLException {
            this.c = c;
            this.connectionPool = connectionPool;
            originalAutoCommit = c.getAutoCommit();
        }

        // Put in service again
        void init() {
            closed = false;
        }

        // Clean up
        void reset() throws SQLException {
            timestamp = System.currentTimeMillis();
            c.setAutoCommit(originalAutoCommit);
        }

        public Statement createStatement() throws SQLException {
            return c.createStatement();
        }

        public PreparedStatement prepareStatement(String sql) throws SQLException {
            return c.prepareStatement(sql);
        }

        public CallableStatement prepareCall(String sql) throws SQLException {
            return c.prepareCall(sql);
        }

        public String nativeSQL(String sql) throws SQLException {
            return c.nativeSQL(sql);
        }

        public void setAutoCommit(boolean autoCommit) throws SQLException {
            c.setAutoCommit(autoCommit);
        }

        public boolean getAutoCommit() throws SQLException {
            return c.getAutoCommit();
        }

        public void commit() throws SQLException {
            if (!c.getAutoCommit()) {
                c.commit();
            }
        }

        public void rollback() throws SQLException {
            if (!c.getAutoCommit()) {
                c.rollback();
            }
        }

        public void close() throws SQLException {
            closed = true;
            synchronized (connectionPool) {
                if (connectionPool.size() >= maxPoolSize) {
                    c.close();
                } else {
                    reset();
                    connectionPool.add(this);
                }
            }
        }

        public boolean isClosed() throws SQLException {
            return closed;
        }

        public DatabaseMetaData getMetaData() throws SQLException {
            return c.getMetaData();
        }

        public void setReadOnly(boolean readOnly) throws SQLException {
            c.setReadOnly(readOnly);
        }

        public boolean isReadOnly() throws SQLException {
            return c.isReadOnly();
        }

        public void setCatalog(String catalog) throws SQLException {
            c.setCatalog(catalog);
        }

        public String getCatalog() throws SQLException {
            return c.getCatalog();
        }

        public void setTransactionIsolation(int level) throws SQLException {
            c.setTransactionIsolation(level);
        }

        public int getTransactionIsolation() throws SQLException {
            return c.getTransactionIsolation();
        }

        public SQLWarning getWarnings() throws SQLException {
            return c.getWarnings();
        }

        public void clearWarnings() throws SQLException {
            c.clearWarnings();
        }

        public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return c.createStatement(resultSetType, resultSetConcurrency);
        }

        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return c.prepareStatement(sql, resultSetType, resultSetConcurrency);
        }

        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return c.prepareCall(sql, resultSetType, resultSetConcurrency);
        }

        public Map getTypeMap() throws SQLException {
            return c.getTypeMap();
        }

        public void setTypeMap(Map map) throws SQLException {
            c.setTypeMap(map);
        }

        public void setHoldability(int level) throws SQLException {
            c.setHoldability(level);
        }

        public int getHoldability() throws SQLException {
            return c.getHoldability();
        }

        public Savepoint setSavepoint() throws SQLException {
            return c.setSavepoint();
        }

        public Savepoint setSavepoint(String name) throws SQLException {
            return c.setSavepoint(name);
        }

        public void rollback(Savepoint savepoint) throws SQLException {
            c.rollback(savepoint);
        }

        public void releaseSavepoint(Savepoint savepoint) throws SQLException {
            c.releaseSavepoint(savepoint);
        }

        public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
                return c.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
            }

        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return c.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return c.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return c.prepareStatement(sql, autoGeneratedKeys);
        }

        public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return c.prepareStatement(sql, columnIndexes);
        }

        public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return c.prepareStatement(sql, columnNames);
        }

        public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return c.createStruct(typeName, attributes);
        }

        public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return c.createArrayOf(typeName, elements);
        }

        public Properties getClientInfo() throws SQLException {
            return c.getClientInfo();
        }

        public String getClientInfo(String name) throws SQLException {
            return c.getClientInfo(name);
        }

        public void setClientInfo(Properties properties) throws SQLClientInfoException {
            c.setClientInfo(properties);
        }

        public void setClientInfo(String name, String value) throws SQLClientInfoException {
            c.setClientInfo(name, value);
        }

        public boolean isValid(int timeout) throws SQLException {
            return c.isValid(timeout);
        }

        public SQLXML createSQLXML() throws SQLException {
            return c.createSQLXML();
        }

        public NClob createNClob() throws SQLException {
            return c.createNClob();
        }

        public Clob createClob() throws SQLException {
            return c.createClob();
        }

        public Blob createBlob() throws SQLException {
            return c.createBlob();
        }

        public boolean isWrapperFor(Class t) throws SQLException {
            return c.isWrapperFor(t);
        }

        public Object unwrap(Class t) throws SQLException {
            return c.unwrap(t);
        }

        public void setSchema(String schema) throws SQLException {
            c.setSchema(schema);
        }

        public String getSchema() throws SQLException {
            return c.getSchema();
        }

        public void abort(Executor executor) throws SQLException {
            c.abort(executor);
        }

        public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
            c.setNetworkTimeout(executor, milliseconds);
        }

        public int getNetworkTimeout() throws SQLException {
            return c.getNetworkTimeout();
        }
    }

}
