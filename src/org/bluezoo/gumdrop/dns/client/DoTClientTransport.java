/*
 * DoTClientTransport.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPEndpoint;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;

/**
 * DNS-over-TLS (DoT) transport for DNS client queries (RFC 7858).
 *
 * <p>Wraps a {@link TCPTransportFactory} configured for TLS.
 * Each DNS message is framed with a 2-byte length prefix per the
 * standard DNS-over-TCP framing (RFC 1035 section 4.2.2). The
 * connection is persistent and can carry multiple queries.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSClientTransport
 */
public class DoTClientTransport implements DNSClientTransport {

    private static final int DEFAULT_DOT_PORT = 853;
    private static final int LENGTH_PREFIX_SIZE = 2;
    private static final int MAX_DNS_MESSAGE_SIZE = 65535;

    private TCPEndpoint endpoint;

    @Override
    public void open(InetAddress server, int port, SelectorLoop loop,
                     DNSClientTransportHandler handler) throws IOException {
        TCPTransportFactory factory = new TCPTransportFactory();
        factory.setSecure(true);
        factory.start();
        if (port <= 0) {
            port = DEFAULT_DOT_PORT;
        }
        this.endpoint = factory.connect(server, port,
                new DoTProtocolHandler(handler), loop);
    }

    @Override
    public void send(ByteBuffer data) {
        int length = data.remaining();
        ByteBuffer frame = ByteBuffer.allocate(LENGTH_PREFIX_SIZE + length);
        frame.put((byte) ((length >> 8) & 0xFF));
        frame.put((byte) (length & 0xFF));
        frame.put(data);
        frame.flip();
        endpoint.send(frame);
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return endpoint.scheduleTimer(delayMs, callback);
    }

    @Override
    public void close() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    /**
     * Handles TCP/TLS framing and delegates reassembled DNS messages to
     * the transport handler.
     */
    private static class DoTProtocolHandler implements ProtocolHandler {

        private final DNSClientTransportHandler handler;
        private ByteBuffer accumulator;

        DoTProtocolHandler(DNSClientTransportHandler handler) {
            this.handler = handler;
        }

        @Override
        public void connected(Endpoint ep) {
        }

        @Override
        public void receive(ByteBuffer data) {
            appendToAccumulator(data);

            while (accumulator.remaining() >= LENGTH_PREFIX_SIZE) {
                accumulator.mark();
                int messageLength =
                        ((accumulator.get() & 0xFF) << 8)
                        | (accumulator.get() & 0xFF);
                if (messageLength <= 0
                        || messageLength > MAX_DNS_MESSAGE_SIZE) {
                    handler.onError(new IOException(
                            "DoT invalid message length: " + messageLength));
                    return;
                }
                if (accumulator.remaining() < messageLength) {
                    accumulator.reset();
                    break;
                }
                ByteBuffer messageBuf = accumulator.slice();
                messageBuf.limit(messageLength);
                accumulator.position(
                        accumulator.position() + messageLength);
                handler.onReceive(messageBuf);
            }
            compact();
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
            handler.onError(cause);
        }

        private void appendToAccumulator(ByteBuffer data) {
            if (accumulator == null) {
                accumulator = ByteBuffer.allocate(data.remaining());
                accumulator.put(data);
                accumulator.flip();
            } else {
                ByteBuffer newBuf = ByteBuffer.allocate(
                        accumulator.remaining() + data.remaining());
                newBuf.put(accumulator);
                newBuf.put(data);
                newBuf.flip();
                accumulator = newBuf;
            }
        }

        private void compact() {
            if (accumulator != null && !accumulator.hasRemaining()) {
                accumulator = null;
            } else if (accumulator != null && accumulator.position() > 0) {
                accumulator.compact();
                accumulator.flip();
            }
        }
    }

}
