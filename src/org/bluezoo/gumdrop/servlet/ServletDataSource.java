/*
 * ServletDataSource.java
 * Copyright (C) 2005, 2013 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import java.io.PrintWriter;
import java.sql.*;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * JDBC DataSource for a web application.
 * This proxies the DriverManager, and provides simple connection pooling
 * facilities.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletDataSource implements DataSource {

    private final String url;
    private final Properties props;
    private PrintWriter logWriter;
    private int loginTimeout = 0;
    private Collection connectionPool;

    ServletDataSource(String url, Properties props) {
        this.url = url;
        this.props = props;
        connectionPool = new LinkedList();
    }

    public Connection getConnection() throws SQLException {
        synchronized (connectionPool) {
            for (Iterator i = connectionPool.iterator(); i.hasNext(); ) {
                PooledConnection c = (PooledConnection) i.next();
                if (c.available) {
                    c.available = false;
                    return c;
                }
            }
            DriverManager.setLoginTimeout(loginTimeout);
            if (logWriter != null) {
                DriverManager.setLogWriter(logWriter);
            }
            Connection c = DriverManager.getConnection(url, props);
            PooledConnection pc = new PooledConnection(c);
            connectionPool.add(pc);
            return pc;
        }
    }

    public Connection getConnection(String username, String password) throws SQLException {
        synchronized (connectionPool) {
            for (Iterator i = connectionPool.iterator(); i.hasNext(); ) {
                PooledConnection c = (PooledConnection) i.next();
                if (c.available) {
                    c.available = false;
                    return c;
                }
            }
            DriverManager.setLoginTimeout(loginTimeout);
            if (logWriter != null) {
                DriverManager.setLogWriter(logWriter);
            }
            Connection c = DriverManager.getConnection(url, username, password);
            PooledConnection pc = new PooledConnection(c);
            connectionPool.add(pc);
            return pc;
        }
    }

    public PrintWriter getLogWriter() {
        return logWriter;
    }

    public void setLogWriter(PrintWriter out) {
        logWriter = out;
    }

    public int getLoginTimeout() {
        return loginTimeout;
    }

    public void setLoginTimeout(int seconds) {
        loginTimeout = seconds;
    }

    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    void close() {
        synchronized (connectionPool) {
            for (Iterator i = connectionPool.iterator(); i.hasNext(); ) {
                PooledConnection c = (PooledConnection) i.next();
                try {
                    c.c.close();
                } catch (SQLException e) {
                }
            }
            connectionPool.clear();
        }
    }

    public boolean isWrapperFor(Class t) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Object unwrap(Class t) throws SQLException {
        throw new UnsupportedOperationException();
    }

    static class PooledConnection implements Connection {

        final Connection c;
        boolean available;

        PooledConnection(Connection c) {
            this.c = c;
            available = false;
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
            available = true;
        }

        public boolean isClosed() throws SQLException {
            return !available;
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

        public Statement createStatement(int resultSetType, int resultSetConcurrency)
                throws SQLException {
            return c.createStatement(resultSetType, resultSetConcurrency);
        }

        public PreparedStatement prepareStatement(
                String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return c.prepareStatement(sql, resultSetType, resultSetConcurrency);
        }

        public CallableStatement prepareCall(
                String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
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

        public Statement createStatement(
                int resultSetType, int resultSetConcurrency, int resultSetHoldability)
                throws SQLException {
            return c.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        public PreparedStatement prepareStatement(
                String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
                throws SQLException {
            return c.prepareStatement(
                    sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        public CallableStatement prepareCall(
                String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
                throws SQLException {
            return c.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
                throws SQLException {
            return c.prepareStatement(sql, autoGeneratedKeys);
        }

        public PreparedStatement prepareStatement(String sql, int[] columnIndexes)
                throws SQLException {
            return c.prepareStatement(sql, columnIndexes);
        }

        public PreparedStatement prepareStatement(String sql, String[] columnNames)
                throws SQLException {
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
