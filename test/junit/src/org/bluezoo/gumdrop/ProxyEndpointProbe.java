/*
 * ProxyEndpointProbe.java
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

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.net.SocketAddress;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.StubSocketChannel;

/**
 * Test-only public window onto the package-private PROXY protocol hooks of
 * {@link TcpEndpoint}, so protocol tests in other packages can put a real
 * endpoint behind a PROXY listener without any network.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ProxyEndpointProbe {

    private ProxyEndpointProbe() {
    }

    /**
     * Creates and connects an endpoint, as the accept loop does for a
     * listener with PROXY protocol on: the handler is not connected yet.
     *
     * @param handler the protocol handler
     * @param listener the accepting listener
     * @param local the socket's local address
     * @param remote the socket's remote address, the relay
     * @return the endpoint, waiting for its PROXY header
     * @throws IOException if initialisation fails
     */
    public static TcpEndpoint accept(ProtocolHandler handler, TcpListener listener,
            SocketAddress local, SocketAddress remote) throws IOException {
        TcpEndpoint endpoint = new TcpEndpoint(handler);
        endpoint.setChannel(new StubSocketChannel(local, remote));
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        endpoint.setListener(listener, remote);
        endpoint.init();
        endpoint.requireProxyHeader(5000);
        endpoint.connected();
        return endpoint;
    }

    /**
     * Delivers bytes as though they had been read from the socket.
     *
     * @param endpoint the endpoint
     * @param data the bytes
     */
    public static void push(TcpEndpoint endpoint, byte[] data) {
        endpoint.netIn.put(data);
        endpoint.netIn.flip();
        endpoint.processInbound();
    }

    /**
     * Returns the bytes queued for sending so far, as ISO-8859-1 text.
     *
     * @param endpoint the endpoint
     * @return the queued output
     */
    public static String queued(TcpEndpoint endpoint) {
        java.nio.ByteBuffer out = endpoint.getNetOut();
        if (out == null) {
            return "";
        }
        java.nio.ByteBuffer copy = out.duplicate();
        copy.flip();
        byte[] b = new byte[copy.remaining()];
        copy.get(b);
        return new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
