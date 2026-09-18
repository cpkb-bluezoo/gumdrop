/*
 * DoTProtocolHandler.java
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

package org.bluezoo.gumdrop.dns;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.dns.server.DnsServer;

/**
 * Protocol handler for DNS-over-TLS (DoT) connections.
 * RFC 7858 section 3.3: all messages MUST use the 2-octet length field
 * from RFC 1035 section 4.2.2. Pipelining of multiple queries over a
 * single TLS connection is supported (RFC 7858 section 3.3).
 *
 * <p>A single DoT connection may carry multiple sequential
 * query-response pairs. The handler accumulates incoming bytes until
 * a complete length-prefixed message is available, then delegates to
 * {@link DnsServer#processQuery(DnsMessage, org.bluezoo.gumdrop.SelectorLoop, DnsQueryCallback)}.
 *
 * <p>RFC 7858 section 3.4: connections SHOULD be reused for multiple
 * queries. Idle connections may be closed by either party.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DoTListener
 * @see org.bluezoo.gumdrop.dns.server.DnsServer
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7858">RFC 7858</a>
 */
final class DoTProtocolHandler implements ProtocolHandler {

    private static final Logger LOGGER =
            Logger.getLogger(DoTProtocolHandler.class.getName());

    // RFC 1035 section 4.2.2: 2-byte big-endian length prefix
    private static final int LENGTH_PREFIX_SIZE = 2;
    // RFC 1035 section 4.2.2: max DNS message size is 65535 octets
    private static final int MAX_DNS_MESSAGE_SIZE = 65535;

    private final org.bluezoo.gumdrop.dns.server.DnsServer server;
    private Endpoint endpoint;
    private ByteBuffer accumulator;

    DoTProtocolHandler(org.bluezoo.gumdrop.dns.server.DnsServer server) {
        this.server = server;
        this.accumulator = ByteBuffer.allocate(4096);
        this.accumulator.flip();
    }

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    DnsServer.L10N.getString("debug.dot_connected"),
                    endpoint.getRemoteAddress()));
        }
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    DnsServer.L10N.getString("debug.dot_tls_established"),
                    endpoint.getRemoteAddress()));
        }
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
                LOGGER.warning(MessageFormat.format(
                        DnsServer.L10N.getString("err.dot_bad_length"),
                        Integer.valueOf(messageLength),
                        endpoint.getRemoteAddress()));
                endpoint.close();
                return;
            }

            if (accumulator.remaining() < messageLength) {
                accumulator.reset();
                break;
            }

            ByteBuffer messageBuf = accumulator.slice();
            messageBuf.limit(messageLength);
            accumulator.position(accumulator.position() + messageLength);

            processMessage(messageBuf);
        }

        compactAccumulator();
    }

    @Override
    public void disconnected() {
        if (LOGGER.isLoggable(Level.FINE)) {
            SocketAddress remote = (endpoint != null)
                    ? endpoint.getRemoteAddress() : null;
            LOGGER.fine(MessageFormat.format(
                    DnsServer.L10N.getString("debug.dot_disconnected"),
                    remote));
        }
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, MessageFormat.format(
                DnsServer.L10N.getString("err.dot_error"),
                endpoint != null ? endpoint.getRemoteAddress() : null),
                cause);
    }

    private void processMessage(ByteBuffer messageBuf) {
        try {
            final DnsMessage query = DnsMessage.parse(messageBuf);

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        DnsServer.L10N.getString(
                                "debug.dot_received_query"),
                        query, endpoint.getRemoteAddress()));
            }

            final DnsServerMetrics metrics = server.getMetrics();
            if (metrics != null && !query.getQuestions().isEmpty()) {
                DnsQuestion q =
                        query.getQuestions().get(0);
                metrics.queryReceived(q.getType().name(), "dot");
            }

            if (!DnsServer.isStandardQuery(query)) {
                sendResponse(server.respondToNonQueryOpcode(query));
                return;
            }

            if (query.getQuestions().isEmpty()) {
                sendResponse(query.createErrorResponse(
                        DnsMessage.RCODE_FORMERR));
                return;
            }

            final long startNanos = System.nanoTime();
            server.processQuery(query, endpoint.getSelectorLoop(),
                    new DnsQueryCallback() {
                        @Override
                        public void onResponse(DnsMessage response) {
                            if (metrics != null) {
                                double durationMs = (System.nanoTime()
                                        - startNanos) / 1_000_000.0;
                                metrics.responseSent(
                                        DnsServer.rcodeToString(
                                                response.getRcode()),
                                        durationMs, "dot");
                            }
                            sendResponse(response);
                        }

                        @Override
                        public void onError(String error) {
                            sendResponse(query.createErrorResponse(
                                    DnsMessage.RCODE_SERVFAIL));
                        }
                    });

        } catch (DnsFormatException e) {
            LOGGER.log(Level.FINE, MessageFormat.format(
                    DnsServer.L10N.getString("err.dot_malformed"),
                    endpoint.getRemoteAddress()), e);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    DnsServer.L10N.getString("err.dot_query_error"),
                    endpoint.getRemoteAddress()), e);
        }
    }

    // RFC 7858 section 3.3 / RFC 1035 section 4.2.2: response is
    // framed with a 2-byte big-endian length prefix before the DNS message.
    private void sendResponse(DnsMessage response) {
        ByteBuffer payload = response.serialize();
        int length = payload.remaining();

        ByteBuffer frame = ByteBuffer.allocate(
                LENGTH_PREFIX_SIZE + length);
        frame.put((byte) ((length >> 8) & 0xFF));
        frame.put((byte) (length & 0xFF));
        frame.put(payload);
        frame.flip();

        endpoint.send(frame);
    }

    private void appendToAccumulator(ByteBuffer data) {
        int needed = accumulator.remaining() + data.remaining();
        if (needed > accumulator.capacity()) {
            ByteBuffer bigger = ByteBuffer.allocate(
                    Math.max(needed, accumulator.capacity() * 2));
            bigger.put(accumulator);
            bigger.put(data);
            bigger.flip();
            accumulator = bigger;
        } else {
            accumulator.compact();
            accumulator.put(data);
            accumulator.flip();
        }
    }

    private void compactAccumulator() {
        if (!accumulator.hasRemaining()) {
            accumulator.clear();
            accumulator.flip();
        }
    }
}
