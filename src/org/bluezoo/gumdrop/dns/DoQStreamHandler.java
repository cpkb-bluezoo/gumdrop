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

/**
 * Protocol handler for a single DNS-over-QUIC stream.
 *
 * <p>Per RFC 9250, each DoQ query-response exchange uses its own
 * bidirectional QUIC stream. The client sends the DNS query (no
 * length prefix), closes its write side (FIN), and the server
 * responds on the same stream before closing its own write side.
 *
 * <p>Unlike DNS-over-TCP/TLS, DoQ does not use a two-byte length
 * prefix because each stream carries exactly one message and the
 * stream FIN delimits the message boundary.
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
            endpoint.close();
            return;
        }
        byte[] buf = new byte[len];
        data.get(buf);
        accumulator.write(buf, 0, buf.length);
    }

    /**
     * Called when the peer sends FIN (done writing). At this point
     * the complete DNS query has been received. Parse it, process,
     * send the response, and close the stream.
     */
    @Override
    public void disconnected() {
        if (accumulator.size() == 0) {
            if (endpoint.isOpen()) {
                endpoint.close();
            }
            return;
        }

        try {
            byte[] queryBytes = accumulator.toByteArray();
            ByteBuffer queryBuf = ByteBuffer.wrap(queryBytes);
            DNSMessage query = DNSMessage.parse(queryBuf);

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        DNSService.L10N.getString(
                                "debug.doq_received_query"),
                        query, endpoint.getRemoteAddress()));
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

            DNSMessage response = service.processQuery(query);
            sendResponseAndClose(response);

        } catch (DNSFormatException e) {
            LOGGER.log(Level.FINE, MessageFormat.format(
                    DNSService.L10N.getString("err.doq_malformed"),
                    endpoint.getRemoteAddress()), e);
            if (endpoint.isOpen()) {
                endpoint.close();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    DNSService.L10N.getString("err.doq_query_error"),
                    endpoint.getRemoteAddress()), e);
            if (endpoint.isOpen()) {
                endpoint.close();
            }
        }
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, MessageFormat.format(
                DNSService.L10N.getString("err.doq_error"),
                endpoint != null ? endpoint.getRemoteAddress() : null),
                cause);
    }

    private void sendResponseAndClose(DNSMessage response) {
        ByteBuffer payload = response.serialize();
        endpoint.send(payload);
        endpoint.close();
    }
}
