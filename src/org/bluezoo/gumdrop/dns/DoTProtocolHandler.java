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
import java.util.List;
import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.dns.server.DnsServer;
import org.bluezoo.gumdrop.dns.DnsQueryTransport;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.SelectorLoop;

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

    private EventLogger events() {
        return (endpoint != null ? endpoint.getTelemetryConfig() : new TelemetryConfig()).getLogger(DoTProtocolHandler.class, DnsServer.L10N);
    }

    // RFC 1035 section 4.2.2: 2-byte big-endian length prefix
    private static final int LENGTH_PREFIX_SIZE = 2;
    // RFC 1035 section 4.2.2: max DNS message size is 65535 octets
    private static final int MAX_DNS_MESSAGE_SIZE = 65535;

    /** The default most queries in flight on one connection. */
    static final int DEFAULT_MAX_IN_FLIGHT = 128;

    private final org.bluezoo.gumdrop.dns.server.DnsServer server;
    private final String metricsTransport;
    private final int maxInFlight;
    private Endpoint endpoint;
    private ByteBuffer accumulator;

    // Queries handed to the server whose responses have not been sent. A
    // client may pipeline any number; once this reaches maxInFlight the
    // connection is not read until a response is sent, so a client cannot
    // make the server hold unbounded work for it.
    private int inFlight;
    private boolean readPaused;
    private boolean closed;

    // what handlers keep for this connection; released when it closes
    private final ConnectionAttributes attributes = new ConnectionAttributes();

    DoTProtocolHandler(org.bluezoo.gumdrop.dns.server.DnsServer server) {
        this(server, "dot");
    }

    DoTProtocolHandler(org.bluezoo.gumdrop.dns.server.DnsServer server,
                       String metricsTransport) {
        this(server, metricsTransport, DEFAULT_MAX_IN_FLIGHT);
    }

    /**
     * @param maxInFlight the most queries in flight on the connection at
     *                    once; 0 for no limit
     */
    DoTProtocolHandler(org.bluezoo.gumdrop.dns.server.DnsServer server,
                       String metricsTransport, int maxInFlight) {
        this.server = server;
        this.metricsTransport = metricsTransport;
        this.maxInFlight = maxInFlight;
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
        // may fire before connected() for an initially-secure connection
        if (endpoint != null && LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    DnsServer.L10N.getString("debug.dot_tls_established"),
                    endpoint.getRemoteAddress()));
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        appendToAccumulator(data);
        drain();
    }

    // Starts the queries that are waiting in the accumulator, as far as the
    // limit on queries in flight allows. What is left stays buffered; a
    // completed query calls this again.
    private void drain() {
        while (!closed && accumulator.remaining() >= LENGTH_PREFIX_SIZE) {
            if (atLimit()) {
                pauseReading();
                break;
            }
            accumulator.mark();
            int messageLength =
                    ((accumulator.get() & 0xFF) << 8)
                    | (accumulator.get() & 0xFF);

            if (messageLength <= 0
                    || messageLength > MAX_DNS_MESSAGE_SIZE) {
                events().warn("err.dot_bad_length")
                        .attr("message_length", messageLength)
                        .attr("remote_address", String.valueOf(endpoint.getRemoteAddress())).emit();
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
        if (atLimit()) {
            // no room for what may arrive next
            pauseReading();
        }
    }

    private boolean atLimit() {
        return maxInFlight > 0 && inFlight >= maxInFlight;
    }

    private void pauseReading() {
        if (!readPaused && !closed) {
            readPaused = true;
            endpoint.pauseRead();
            DnsServerMetrics metrics = server.getMetrics();
            if (metrics != null) {
                metrics.connectionBackpressure(metricsTransport);
            }
        }
    }

    // A response has been sent for a query that was in flight.
    private void queryCompleted() {
        if (inFlight > 0) {
            inFlight--;
        }
        if (closed || !readPaused || atLimit()) {
            return;
        }
        // room again: start the queries already read, and only if that
        // still leaves room read more, so that a client keeping the
        // connection full does not make it stop and start for every query
        drain();
        if (!atLimit()) {
            readPaused = false;
            endpoint.resumeRead();
        }
    }

    @Override
    public void disconnected() {
        closed = true;
        attributes.close();
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
        events().warn("err.dot_error")
                .attr("remote_address", String.valueOf(endpoint != null ? endpoint.getRemoteAddress() : null))
                .thrown(cause).emit();
    }

    private void processMessage(ByteBuffer messageBuf) {
        inProgress = null;
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
                metrics.queryReceived(q.getType().name(), metricsTransport);
            }

            if (!DnsServer.isStandardQuery(query)) {
                final long startNanos = System.nanoTime();
                started(metrics);
                server.dispatchNonQueryOpcode(query, endpoint.getSelectorLoop(),
                        queryContext(), framedCallback(query, startNanos));
                return;
            }

            if (query.getQuestions().isEmpty()) {
                sendResponse(query.createErrorResponse(
                        DnsMessage.RCODE_FORMERR));
                return;
            }

            final long startNanos = System.nanoTime();
            started(metrics);
            server.processQuery(query, endpoint.getSelectorLoop(),
                    queryContext(), framedCallback(query, startNanos));

        } catch (DnsFormatException e) {
            LOGGER.log(Level.FINE, MessageFormat.format(
                    DnsServer.L10N.getString("err.dot_malformed"),
                    endpoint.getRemoteAddress()), e);
        } catch (Exception e) {
            events().warn("err.dot_query_error")
                    .attr("remote_address", String.valueOf(endpoint.getRemoteAddress()))
                    .thrown(e).emit();
            if (inProgress != null) {
                // the query never got going, so it holds no place
                inProgress.finish();
            }
        }
    }

    // A query is being handed to the server.
    private void started(DnsServerMetrics metrics) {
        inFlight++;
        if (metrics != null) {
            metrics.connectionInFlight(metricsTransport, inFlight);
        }
    }

    /** The context of the connection a message arrived on. */
    private DnsQueryContext queryContext() {
        return new DefaultDnsQueryContext(metricsTransport, DnsQueryTransport.FRAMED_TCP,
                endpoint.getRemoteAddress(), endpoint.getLocalAddress(),
                securityOf(endpoint), attributes);
    }

    /** Security parameters of a secure endpoint; null for plain TCP. */
    private static SecurityInfo securityOf(Endpoint endpoint) {
        return endpoint.isSecure() ? endpoint.getSecurityInfo() : null;
    }

    private DnsQueryCallback framedCallback(final DnsMessage query,
                                            final long startNanos) {
        inProgress = new FramedCallback(query, startNanos);
        return inProgress;
    }

    // The query being handed to the server; its place is released if that fails.
    private FramedCallback inProgress;

    /** Sends a query's response(s) framed, and releases its place among those in flight. */
    private final class FramedCallback implements DnsQueryCallback {

        private final DnsMessage query;
        private final long startNanos;
        private boolean done;

        FramedCallback(DnsMessage query, long startNanos) {
            this.query = query;
            this.startNanos = startNanos;
        }

        @Override
        public void onResponse(DnsMessage response) {
            recordMetrics(response, startNanos);
            sendResponse(response);
            finish();
        }

        @Override
        public void onResponseSequence(List<DnsMessage> responses) {
            recordMetrics(responses.get(responses.size() - 1), startNanos);
            for (int i = 0; i < responses.size(); i++) {
                sendResponse(responses.get(i));
            }
            finish();
        }

        @Override
        public void onError(String error) {
            sendResponse(query.createErrorResponse(
                    DnsMessage.RCODE_SERVFAIL));
            finish();
        }

        // each query counts once, whichever way it ends
        void finish() {
            if (!done) {
                done = true;
                queryCompleted();
            }
        }
    }

    private void recordMetrics(DnsMessage response, long startNanos) {
        DnsServerMetrics metrics = server.getMetrics();
        if (metrics != null) {
            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            metrics.responseSent(
                    DnsServer.rcodeToString(response.getRcode()),
                    durationMs, metricsTransport);
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
