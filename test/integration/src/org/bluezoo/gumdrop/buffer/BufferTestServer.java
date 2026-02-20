/*
 * BufferTestServer.java
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

package org.bluezoo.gumdrop.buffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.SecurityInfo;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A minimal test server for buffer handling integration tests.
 *
 * <p>This server creates {@link BufferTestConnection} instances that look
 * for complete message patterns, testing underflow data handling when
 * messages are split across TCP reads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BufferTestServer extends TCPListener {

    private int port = 19080;

    /** The message pattern to look for. Default is "0123456789". */
    private byte[] messagePattern = "0123456789".getBytes();

    /** Track all created connections for test inspection */
    private final List<BufferTestConnection> connections;

    public BufferTestServer() {
        super();
        this.connections = Collections.synchronizedList(new ArrayList<BufferTestConnection>());
    }

    @Override
    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Sets the message pattern that defines a complete message.
     * The connection will scan for and consume complete instances of this pattern.
     *
     * @param pattern the message pattern bytes
     */
    public void setMessagePattern(byte[] pattern) {
        this.messagePattern = pattern;
    }

    /**
     * Sets the message pattern from a string.
     *
     * @param pattern the message pattern string (US-ASCII encoded)
     */
    public void setMessagePattern(String pattern) {
        try {
            this.messagePattern = pattern.getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            this.messagePattern = pattern.getBytes();
        }
    }

    /**
     * Returns the message pattern that defines a complete message.
     *
     * @return the message pattern bytes
     */
    public byte[] getMessagePattern() {
        return messagePattern;
    }

    @Override
    protected ProtocolHandler createHandler() {
        return new BufferTestConnection(this);
    }

    @Override
    public String getDescription() {
        return "BufferTest";
    }

    void addConnection(BufferTestConnection connection) {
        connections.add(connection);
    }

    /**
     * Returns all connections created by this server.
     * Useful for test assertions.
     *
     * @return unmodifiable list of connections
     */
    public List<BufferTestConnection> getConnections() {
        return Collections.unmodifiableList(connections);
    }

    /**
     * Clears the tracked connections.
     * Call before each test to reset state.
     */
    public void clearConnections() {
        connections.clear();
    }
}
