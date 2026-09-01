/*
 * H3ClientConnectIpResponseHandler.java
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

package org.bluezoo.gumdrop.http.h3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;
import org.bluezoo.gumdrop.http.HttpDatagramContext;
import org.bluezoo.gumdrop.http.client.ConnectIpClientSession;
import org.bluezoo.gumdrop.http.client.ConnectIpEventHandler;
import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPResponse;

/**
 * RFC 9484 -- bridges a generic HTTP/3 Extended CONNECT response ({@link
 * org.bluezoo.gumdrop.http.client.HTTPResponseHandler}) to a {@link
 * ConnectIpEventHandler}/{@link ConnectIpClientSession} pair.
 *
 * <p>{@link H3ClientStream} has no notion of CONNECT-IP at all -- it
 * always calls the ordinary {@link org.bluezoo.gumdrop.http.client.HTTPResponseHandler}
 * callback sequence, and this class is what reinterprets that sequence as
 * an IP tunnel: {@link #startResponseBody} signals acceptance (called as
 * soon as headers are known complete -- see {@link H3ClientStream}'s own
 * documentation on why HTTP/3 signals this eagerly for any Extended
 * CONNECT); {@link #datagramReceived}/{@link #wantsDatagrams} deliver
 * HTTP Datagrams -- decoded per RFC 9484 section 6 (Context ID 0) -- as
 * IP packets, however they arrive (native QUIC DATAGRAM or capsule
 * fallback; {@link H3ClientStream} already dispatches both the same
 * way); and {@link #capsuleReceived} handles the two server-to-client
 * capsule types (RFC 9484 section 4.7.1/4.7.3) using the same generic
 * per-capsule dispatch {@link H3ClientStream#dispatchCapsules} already
 * gives every non-DATAGRAM capsule type. Direct client-side mirror of
 * {@link H3ClientConnectUdpResponseHandler}, including keeping the
 * {@link ConnectIpClientSession} handed to the application as a separate
 * nested class -- see that class's own documentation for why.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTP3ClientHandler#connectIp
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484">RFC 9484</a>
 */
class H3ClientConnectIpResponseHandler extends DefaultHTTPResponseHandler {

    private final ConnectIpEventHandler eventHandler;

    // Bound by HTTP3ClientHandler.connectIp immediately after both this
    // handler and its H3ClientStream are constructed -- see
    // H3ClientWebSocketResponseHandler.bindStream's own documentation for
    // why construction can't just take this in the constructor.
    private H3ClientConnectIpSession session;

    private boolean opened;
    private boolean failed;

    H3ClientConnectIpResponseHandler(ConnectIpEventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

    void bindStream(H3ClientStream stream) {
        this.session = new H3ClientConnectIpSession(stream);
    }

    @Override
    public void ok(HTTPResponse response) {
        // Nothing to do yet -- acceptance is signalled from
        // startResponseBody(), once headers are known complete.
    }

    @Override
    public void error(HTTPResponse response) {
        failed = true;
        eventHandler.error(new IOException(
                "CONNECT-IP request failed: " + response.getStatus()));
    }

    @Override
    public void startResponseBody() {
        if (failed) {
            return;
        }
        opened = true;
        eventHandler.opened(session);
    }

    @Override
    public boolean wantsDatagrams() {
        return true;
    }

    @Override
    public void datagramReceived(ByteBuffer data) {
        HttpDatagramContext decoded = HttpDatagramContext.decode(data);
        if (decoded == null || decoded.getContextId() != HttpDatagramContext.REGISTERED_CONTEXT_ID) {
            return;
        }
        eventHandler.packetReceived(decoded.getPayload());
    }

    @Override
    public void capsuleReceived(long type, ByteBuffer value) {
        if (type == ConnectIpAddress.TYPE_ADDRESS_ASSIGN) {
            List<ConnectIpAddress> assigned = ConnectIpAddress.decodeList(value);
            if (assigned != null) {
                eventHandler.addressAssigned(assigned);
            }
        } else if (type == ConnectIpRoute.TYPE_ROUTE_ADVERTISEMENT) {
            List<ConnectIpRoute> routes = ConnectIpRoute.decodeList(value);
            if (routes != null) {
                eventHandler.routeAdvertised(routes);
            }
        }
    }

    @Override
    public void endResponseBody() {
        if (opened) {
            eventHandler.closed();
        }
    }

    @Override
    public void failed(Exception ex) {
        if (!failed) {
            failed = true;
            eventHandler.error(ex);
        }
    }

    /**
     * The {@link ConnectIpClientSession} handed to the application --
     * kept separate from {@link H3ClientConnectIpResponseHandler} itself;
     * see that class's own documentation for why.
     */
    private static class H3ClientConnectIpSession implements ConnectIpClientSession {

        private final H3ClientStream stream;

        H3ClientConnectIpSession(H3ClientStream stream) {
            this.stream = stream;
        }

        @Override
        public void sendPacket(ByteBuffer packet) throws IOException {
            sendCapsuleFrame(HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, packet));
        }

        @Override
        public void sendAddressRequest(List<ConnectIpAddress> requested) throws IOException {
            ByteBuffer capsuleValue = ConnectIpAddress.encodeList(requested);
            sendCapsuleBytes(ConnectIpAddress.TYPE_ADDRESS_REQUEST, capsuleValue);
        }

        private void sendCapsuleFrame(ByteBuffer datagramPayload) throws IOException {
            byte[] payloadBytes = new byte[datagramPayload.remaining()];
            datagramPayload.get(payloadBytes);
            byte[] capsuleBytes = Capsule.datagram(payloadBytes).encode();
            writeRawData(ByteBuffer.wrap(capsuleBytes));
        }

        private void sendCapsuleBytes(long type, ByteBuffer value) throws IOException {
            byte[] valueBytes = new byte[value.remaining()];
            value.get(valueBytes);
            byte[] capsuleBytes = new Capsule(type, valueBytes).encode();
            writeRawData(ByteBuffer.wrap(capsuleBytes));
        }

        private void writeRawData(ByteBuffer frameData) throws IOException {
            if (stream.isClosed()) {
                throw new IOException("Stream closed");
            }
            stream.sendRawData(frameData);
        }

        @Override
        public void close() {
            stream.closeStream();
        }
    }
}
