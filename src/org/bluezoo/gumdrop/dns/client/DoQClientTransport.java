/*
 * DoQClientTransport.java
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

package org.bluezoo.gumdrop.dns.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;

/**
 * DNS-over-QUIC (DoQ) transport for DNS client queries (RFC 9250).
 *
 * <p>Wraps a {@link QuicTransportFactory} configured with ALPN {@code "doq"}.
 * Each DNS query is sent on its own bidirectional QUIC stream. The query
 * message has no length prefix; the stream FIN delimits the message
 * boundary. The response is received on the same stream.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSClientTransport
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9250">RFC 9250</a>
 */
public class DoQClientTransport implements DNSClientTransport {

    private static final int DEFAULT_DOQ_PORT = 853;

    private QuicTransportFactory factory;
    private QuicEngine engine;
    private volatile boolean connected;
    private SelectorLoop loop;
    private DNSClientTransportHandler handler;

    @Override
    public void open(InetAddress server, int port, SelectorLoop loop,
                     DNSClientTransportHandler handler) throws IOException {
        this.loop = loop;
        this.handler = handler;
        if (port <= 0) {
            port = DEFAULT_DOQ_PORT;
        }
        factory = new QuicTransportFactory();
        factory.setApplicationProtocols("doq");
        factory.start();
        engine = factory.connect(server, port,
                new QuicEngine.ConnectionAcceptedHandler() {
                    @Override
                    public void connectionAccepted(
                            QuicConnection conn) {
                        connected = true;
                    }
                },
                loop, null);
    }

    @Override
    public void send(ByteBuffer data) {
        if (!connected) {
            handler.onError(new IOException(
                    "DoQ connection not yet established"));
            return;
        }
        Endpoint stream = engine.openStream(
                new DoQStreamHandler(handler));
        stream.send(data);
        stream.close();
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return engine.scheduleTimer(delayMs, callback);
    }

    @Override
    public void close() {
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * Handles a single DoQ response stream. Accumulates data until the
     * peer sends FIN ({@code disconnected}), then delivers the complete
     * response to the transport handler.
     */
    private static class DoQStreamHandler implements ProtocolHandler {

        private static final int MAX_DNS_MESSAGE_SIZE = 65535;

        private final DNSClientTransportHandler handler;
        private final ByteArrayOutputStream accumulator =
                new ByteArrayOutputStream(512);

        DoQStreamHandler(DNSClientTransportHandler handler) {
            this.handler = handler;
        }

        @Override
        public void connected(Endpoint ep) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void receive(ByteBuffer data) {
            int len = data.remaining();
            if (accumulator.size() + len > MAX_DNS_MESSAGE_SIZE) {
                handler.onError(new IOException(
                        "DoQ response too large"));
                return;
            }
            byte[] buf = new byte[len];
            data.get(buf);
            accumulator.write(buf, 0, buf.length);
        }

        @Override
        public void disconnected() {
            if (accumulator.size() == 0) {
                return;
            }
            byte[] responseBytes = accumulator.toByteArray();
            handler.onReceive(ByteBuffer.wrap(responseBytes));
        }

        @Override
        public void error(Exception cause) {
            handler.onError(cause);
        }
    }

}
