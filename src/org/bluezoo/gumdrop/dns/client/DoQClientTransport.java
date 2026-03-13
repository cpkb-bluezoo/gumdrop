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
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;

/**
 * DNS-over-QUIC (DoQ) transport for DNS client queries.
 * RFC 9250 section 4.1: ALPN token is "doq".
 * RFC 9250 section 4.1.1: default port is 853.
 * RFC 9250 section 4.2: each query uses a separate bidirectional stream.
 * The client MUST indicate STREAM FIN after the query. All messages MUST
 * use 2-octet length framing (RFC 1035 section 4.2.2).
 * RFC 9250 section 4.2.1: Message ID MUST be set to 0 on DoQ.
 *
 * <p>RFC 9250 section 5.4: implementations MUST protect against traffic
 * analysis by padding messages. EDNS(0) padding (RFC 7830) is added,
 * aligning to 128-byte boundaries as recommended by RFC 8467.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSClientTransport
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9250">RFC 9250</a>
 */
public class DoQClientTransport implements DNSClientTransport {

    // RFC 9250 section 4.1.1
    private static final int DEFAULT_DOQ_PORT = 853;
    // RFC 9250 section 5.4: pad to 128-byte blocks
    private static final int PADDING_BLOCK_SIZE = 128;

    /**
     * RFC 9250 section 4.5: per-server session ticket cache for 0-RTT.
     * Only QUERY and NOTIFY opcodes may be sent as 0-RTT data.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, byte[]>
            sessionTicketCache = new java.util.concurrent.ConcurrentHashMap<>();

    private QuicTransportFactory factory;
    private QuicEngine engine;
    private volatile boolean connected;
    private SelectorLoop loop;
    private DNSClientTransportHandler handler;
    private String serverKey;

    @Override
    public void open(InetAddress server, int port, SelectorLoop loop,
                     DNSClientTransportHandler handler) throws IOException {
        this.loop = loop;
        this.handler = handler;
        if (port <= 0) {
            port = DEFAULT_DOQ_PORT;
        }
        this.serverKey = server.getHostAddress() + ":" + port;
        factory = new QuicTransportFactory();
        // RFC 9250 section 4.1: ALPN token "doq"
        factory.setApplicationProtocols("doq");
        // RFC 9250 section 4.5: enable early data for 0-RTT
        factory.setEarlyDataEnabled(true);
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

    // RFC 9250 section 4.2: client selects a new bidirectional stream for
    // each query, sends the message, and indicates STREAM FIN.
    // RFC 9250 section 4.2: 2-octet length prefix required.
    // RFC 9250 section 4.2.1: Message ID MUST be set to 0.
    @Override
    public void send(ByteBuffer data) {
        if (!connected) {
            handler.onError(new IOException(
                    "DoQ connection not yet established"));
            return;
        }
        // RFC 9250 section 4.2.1: rewrite Message ID to 0
        if (data.remaining() >= 2) {
            int pos = data.position();
            data.put(pos, (byte) 0);
            data.put(pos + 1, (byte) 0);
        }
        // RFC 9250 section 5.4: add EDNS(0) padding
        ByteBuffer padded = DNSMessage.padToBlockSize(data, PADDING_BLOCK_SIZE);
        int len = padded.remaining();
        ByteBuffer framed = ByteBuffer.allocate(2 + len);
        framed.putShort((short) len);
        framed.put(padded);
        framed.flip();
        Endpoint stream = engine.openStream(
                new DoQStreamHandler(handler));
        stream.send(framed);
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
            if (accumulator.size() < 2) {
                return;
            }
            byte[] raw = accumulator.toByteArray();
            // RFC 9250 section 4.2: strip 2-octet length prefix
            int msgLen = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
            if (msgLen > raw.length - 2) {
                handler.onError(new IOException(
                        "DoQ response length mismatch"));
                return;
            }
            handler.onReceive(ByteBuffer.wrap(raw, 2, msgLen));
        }

        @Override
        public void error(Exception cause) {
            handler.onError(cause);
        }
    }

}
