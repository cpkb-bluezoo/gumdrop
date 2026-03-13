/*
 * DoQStreamHandler.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.quic.QuicStreamEndpoint;

/**
 * Protocol handler for a single DNS-over-QUIC stream.
 * RFC 9250 section 4.2: each DoQ query-response exchange uses its own
 * bidirectional QUIC stream. The client sends the DNS query, closes its
 * write side (STREAM FIN), and the server responds on the same stream
 * before closing its own write side.
 *
 * <p>RFC 9250 section 4.2 requires all messages to be encoded with a
 * 2-octet length prefix (same framing as DNS-over-TCP).
 *
 * <p>RFC 9250 section 4.2.1: Message ID MUST be set to 0 on DoQ.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DoQListener
 * @see DNSService
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9250">RFC 9250</a>
 */
final class DoQStreamHandler implements ProtocolHandler {

    private static final Logger LOGGER =
            Logger.getLogger(DoQStreamHandler.class.getName());

    private static final int MAX_DNS_MESSAGE_SIZE = 65535;

    // RFC 9250 section 4.3: DoQ application error codes
    /** No error. RFC 9250 section 4.3.1 */
    static final long DOQ_NO_ERROR = 0x0;
    /** Implementation fault. RFC 9250 section 4.3.2 */
    static final long DOQ_INTERNAL_ERROR = 0x1;
    /** Peer violated protocol. RFC 9250 section 4.3.3 */
    static final long DOQ_PROTOCOL_ERROR = 0x2;
    /** Request cancelled by client. RFC 9250 section 4.3.4 */
    static final long DOQ_REQUEST_CANCELLED = 0x3;
    /** Too many outstanding queries. RFC 9250 section 4.3.5 */
    static final long DOQ_EXCESSIVE_LOAD = 0x4;

    private final DNSService service;
    private Endpoint endpoint;
    private final ByteArrayOutputStream accumulator =
            new ByteArrayOutputStream(512);

    DoQStreamHandler(DNSService service) {
        this.service = service;
    }

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        // QUIC is always secure; nothing special to do
    }

    @Override
    public void receive(ByteBuffer data) {
        int len = data.remaining();
        if (accumulator.size() + len > MAX_DNS_MESSAGE_SIZE) {
            LOGGER.warning(MessageFormat.format(
                    DNSService.L10N.getString("err.doq_message_too_large"),
                    endpoint.getRemoteAddress()));
            // RFC 9250 section 4.3.3: protocol error for oversized message
            resetWithError(DOQ_PROTOCOL_ERROR);
            return;
        }
        byte[] buf = new byte[len];
        data.get(buf);
        accumulator.write(buf, 0, buf.length);
    }

    // RFC 9250 section 4.2: server MAY defer processing until STREAM FIN.
    // On FIN, parse the accumulated query, process, respond, and close.
    @Override
    public void disconnected() {
        if (accumulator.size() == 0) {
            if (endpoint.isOpen()) {
                endpoint.close();
            }
            return;
        }

        try {
            byte[] raw = accumulator.toByteArray();
            // RFC 9250 section 4.2: strip 2-octet length prefix
            if (raw.length < 2) {
                LOGGER.fine(MessageFormat.format(
                        DNSService.L10N.getString("err.doq_malformed"),
                        endpoint.getRemoteAddress()));
                // RFC 9250 section 4.3.3: malformed framing is a protocol error
                resetWithError(DOQ_PROTOCOL_ERROR);
                return;
            }
            int msgLen = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
            if (msgLen > raw.length - 2) {
                LOGGER.fine(MessageFormat.format(
                        DNSService.L10N.getString("err.doq_malformed"),
                        endpoint.getRemoteAddress()));
                // RFC 9250 section 4.3.3: truncated message is a protocol error
                resetWithError(DOQ_PROTOCOL_ERROR);
                return;
            }
            ByteBuffer queryBuf = ByteBuffer.wrap(raw, 2, msgLen);
            DNSMessage query = DNSMessage.parse(queryBuf);

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        DNSService.L10N.getString(
                                "debug.doq_received_query"),
                        query, endpoint.getRemoteAddress()));
            }

            DNSServerMetrics metrics = service.getMetrics();
            if (metrics != null && !query.getQuestions().isEmpty()) {
                DNSQuestion q =
                        (DNSQuestion) query.getQuestions().get(0);
                metrics.queryReceived(q.getType().name(), "doq");
            }

            if (!query.isQuery()
                    || query.getOpcode() != DNSMessage.OPCODE_QUERY) {
                sendResponseAndClose(query.createErrorResponse(
                        DNSMessage.RCODE_NOTIMP));
                return;
            }

            if (query.getQuestions().isEmpty()) {
                sendResponseAndClose(query.createErrorResponse(
                        DNSMessage.RCODE_FORMERR));
                return;
            }

            long startNanos = System.nanoTime();
            DNSMessage response = service.processQuery(query);
            if (metrics != null) {
                double durationMs =
                        (System.nanoTime() - startNanos) / 1_000_000.0;
                metrics.responseSent(
                        DNSService.rcodeToString(response.getRcode()),
                        durationMs, "doq");
            }
            sendResponseAndClose(response);

        } catch (DNSFormatException e) {
            LOGGER.log(Level.FINE, MessageFormat.format(
                    DNSService.L10N.getString("err.doq_malformed"),
                    endpoint.getRemoteAddress()), e);
            // RFC 9250 section 4.3.3: malformed query is a protocol error
            resetWithError(DOQ_PROTOCOL_ERROR);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    DNSService.L10N.getString("err.doq_query_error"),
                    endpoint.getRemoteAddress()), e);
            // RFC 9250 section 4.3.2: unexpected exception is an internal error
            resetWithError(DOQ_INTERNAL_ERROR);
        }
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, MessageFormat.format(
                DNSService.L10N.getString("err.doq_error"),
                endpoint != null ? endpoint.getRemoteAddress() : null),
                cause);
    }

    /**
     * RFC 9250 section 4.3: resets the stream with the given DoQ error code.
     * Falls back to a plain close if the endpoint is not a QUIC stream.
     */
    private void resetWithError(long errorCode) {
        if (endpoint instanceof QuicStreamEndpoint) {
            ((QuicStreamEndpoint) endpoint).resetStream(errorCode);
        } else if (endpoint.isOpen()) {
            endpoint.close();
        }
    }

    // RFC 9250 section 5.4: pad to 128-byte blocks
    private static final int PADDING_BLOCK_SIZE = 128;

    // RFC 9250 section 4.2: server MUST send response on the same stream
    // and indicate STREAM FIN after the last response.
    // RFC 9250 section 4.2: 2-octet length prefix required.
    // RFC 9250 section 5.4: EDNS(0) padding applied.
    private void sendResponseAndClose(DNSMessage response) {
        ByteBuffer payload = response.serialize();
        payload = DNSMessage.padToBlockSize(payload, PADDING_BLOCK_SIZE);
        int len = payload.remaining();
        ByteBuffer framed = ByteBuffer.allocate(2 + len);
        framed.putShort((short) len);
        framed.put(payload);
        framed.flip();
        endpoint.send(framed);
        endpoint.close();
    }
}
