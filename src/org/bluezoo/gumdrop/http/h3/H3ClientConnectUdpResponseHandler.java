/*
 * H3ClientConnectUdpResponseHandler.java
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

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.HttpDatagramContext;
import org.bluezoo.gumdrop.http.client.ConnectUdpEventHandler;
import org.bluezoo.gumdrop.http.client.ConnectUdpSession;
import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPResponse;

/**
 * RFC 9298 -- bridges a generic HTTP/3 Extended CONNECT response ({@link
 * org.bluezoo.gumdrop.http.client.HTTPResponseHandler}) to a {@link
 * ConnectUdpEventHandler}/{@link ConnectUdpSession} pair.
 *
 * <p>{@link H3ClientStream} has no notion of CONNECT-UDP at all -- it
 * always calls the ordinary {@link org.bluezoo.gumdrop.http.client.HTTPResponseHandler}
 * callback sequence, and this class is what reinterprets that sequence as
 * a UDP tunnel: {@link #startResponseBody} signals acceptance (called as
 * soon as headers are known complete -- see {@link H3ClientStream}'s own
 * documentation on why HTTP/3 signals this eagerly for any Extended
 * CONNECT), and {@link #datagramReceived}/{@link #wantsDatagrams} deliver
 * HTTP Datagrams -- decoded per RFC 9298 section 5 (Context ID 0, no
 * capsule type) -- as UDP payloads, however they arrive (native QUIC
 * DATAGRAM or capsule fallback; {@link H3ClientStream} already dispatches
 * both the same way). The {@link ConnectUdpSession} handed to the
 * application is a separate nested class -- not this class itself --
 * exactly like {@link H3ClientWebSocketResponseHandler} keeps its
 * WebSocket-session adapter separate from the response handler: {@link
 * ConnectUdpSession} and {@link org.bluezoo.gumdrop.http.client.HTTPResponseHandler}
 * both declare a no-arg {@code close()} with opposite meanings (an
 * application-requested tunnel close vs. a framework notification that
 * the response is complete), so one class cannot implement both without
 * the two colliding into a single, wrongly-overloaded method.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTP3ClientHandler#connectUdp
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
class H3ClientConnectUdpResponseHandler extends DefaultHTTPResponseHandler {

    private final ConnectUdpEventHandler eventHandler;

    // Bound by HTTP3ClientHandler.connectUdp immediately after both this
    // handler and its H3ClientStream are constructed -- see
    // H3ClientWebSocketResponseHandler.bindStream's own documentation for
    // why construction can't just take this in the constructor.
    private H3ClientConnectUdpSession session;

    private boolean opened;
    private boolean failed;

    H3ClientConnectUdpResponseHandler(ConnectUdpEventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

    void bindStream(H3ClientStream stream) {
        this.session = new H3ClientConnectUdpSession(stream);
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
                "CONNECT-UDP request failed: " + response.getStatus()));
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
        eventHandler.datagramReceived(decoded.getPayload());
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
     * The {@link ConnectUdpSession} handed to the application -- kept
     * separate from {@link H3ClientConnectUdpResponseHandler} itself; see
     * that class's own documentation for why.
     */
    private static class H3ClientConnectUdpSession implements ConnectUdpSession {

        private final H3ClientStream stream;

        H3ClientConnectUdpSession(H3ClientStream stream) {
            this.stream = stream;
        }

        @Override
        public void sendDatagram(ByteBuffer payload) throws IOException {
            if (stream.isClosed()) {
                throw new IOException("Stream closed");
            }
            ByteBuffer contextEncoded =
                    HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, payload);
            byte[] contextBytes = new byte[contextEncoded.remaining()];
            contextEncoded.get(contextBytes);
            byte[] capsuleBytes = Capsule.datagram(contextBytes).encode();
            stream.sendRawData(ByteBuffer.wrap(capsuleBytes));
        }

        @Override
        public void close() {
            stream.closeStream();
        }
    }
}
