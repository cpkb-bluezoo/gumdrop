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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.SecurityInfo;

import java.nio.ByteBuffer;

/**
 * Simple echo server for TLS testing.
 * Echoes back whatever data it receives.
 */
public class TLSEchoServer extends TCPListener {

    private int port = 19445;

    @Override
    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    @Override
    protected ProtocolHandler createHandler() {
        return new TLSEchoConnection();
    }

    @Override
    public String getDescription() {
        return "TLSEcho";
    }

    /**
     * Connection that echoes all received data back to the client.
     */
    static class TLSEchoConnection implements ProtocolHandler {

        private Endpoint endpoint;

        @Override
        public void connected(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void receive(ByteBuffer data) {
            if (data.hasRemaining() && endpoint != null) {
                int size = data.remaining();
                ByteBuffer echo = ByteBuffer.allocate(size);
                echo.put(data);
                echo.flip();
                endpoint.send(echo);
            }
        }

        @Override
        public void disconnected() {
            endpoint = null;
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            // No-op
        }

        @Override
        public void error(Exception cause) {
            endpoint = null;
        }
    }
}
