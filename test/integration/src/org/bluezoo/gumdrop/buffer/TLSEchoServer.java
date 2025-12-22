/*
 * TLSEchoServer.java
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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

/**
 * Simple echo server for TLS testing.
 * Echoes back whatever data it receives.
 */
public class TLSEchoServer extends Server {
    
    private int port = 19445;
    
    @Override
    protected int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    @Override
    protected Connection newConnection(SocketChannel channel, SSLEngine engine) {
        return new TLSEchoConnection(channel, engine, isSecure());
    }
    
    @Override
    protected String getDescription() {
        return "TLSEcho";
    }
    
    /**
     * Connection that echoes all received data back to the client.
     */
    static class TLSEchoConnection extends Connection {
        
        TLSEchoConnection(SocketChannel channel, SSLEngine engine, boolean secure) {
            super(engine, secure);
        }
        
        @Override
        public void receive(ByteBuffer data) {
            // Echo everything back - create a copy to send
            if (data.hasRemaining()) {
                int size = data.remaining();
                ByteBuffer echo = ByteBuffer.allocate(size);
                echo.put(data);
                echo.flip();
                send(echo);
            }
        }
        
        @Override
        protected void disconnected() throws IOException {
            // Nothing to clean up
        }
    }
}

