/*
 * H2ConnectUdpResponseHandler.java
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

package org.bluezoo.gumdrop.http.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.CapsuleParser;
import org.bluezoo.gumdrop.http.HttpDatagramContext;

/**
 * RFC 9298 -- bridges a generic HTTP/2 Extended CONNECT response ({@link
 * HTTPResponseHandler}) to a {@link ConnectUdpEventHandler}/{@link
 * ConnectUdpSession} pair.
 *
 * <p>Unlike the h3 client (which has its own dedicated {@code
 * H3ClientStream}), h2 responses already route generically through
 * {@link HTTPResponseHandler} -- a {@code 200} to an Extended CONNECT is
 * indistinguishable, at that layer, from a {@code 200} to any other
 * request. This class is what makes it CONNECT-UDP-shaped: {@link
 * #startResponseBody} signals acceptance (called as soon as the server's
 * HEADERS frame arrives without {@code END_STREAM}, i.e. exactly when the
 * tunnel is accepted -- see {@code H2WebSocketResponseHandler}'s own
 * documentation for why HTTP/2 can signal this eagerly, unlike HTTP/3),
 * and {@link #responseBodyContent} parses each DATA frame's bytes as
 * Capsule Protocol capsules (RFC 9297 section 3.2) instead of a plain
 * response body, delivering {@link Capsule#TYPE_DATAGRAM} payloads (RFC
 * 9298 section 5) as UDP payloads. Direct client-side mirror of {@code
 * org.bluezoo.gumdrop.websocket.client.H2WebSocketResponseHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectUdpClient
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
class H2ConnectUdpResponseHandler extends DefaultHTTPResponseHandler {

    private final HTTPRequest request;
    private final ConnectUdpEventHandler eventHandler;
    private final CapsuleParser capsuleParser = new CapsuleParser();

    private boolean opened;
    private boolean failed;

    H2ConnectUdpResponseHandler(HTTPRequest request, ConnectUdpEventHandler eventHandler) {
        this.request = request;
        this.eventHandler = eventHandler;
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
        eventHandler.opened(new H2ClientConnectUdpSession(request));
    }

    @Override
    public void responseBodyContent(ByteBuffer data) {
        if (!opened) {
            return;
        }
        List<Capsule> capsules;
        try {
            capsules = capsuleParser.push(data);
        } catch (CapsuleParser.CapsuleException e) {
            eventHandler.error(e);
            return;
        }
        for (int i = 0; i < capsules.size(); i++) {
            Capsule capsule = capsules.get(i);
            if (capsule.getType() != Capsule.TYPE_DATAGRAM) {
                continue;
            }
            HttpDatagramContext decoded = HttpDatagramContext.decode(ByteBuffer.wrap(capsule.getValue()));
            if (decoded != null && decoded.getContextId() == HttpDatagramContext.REGISTERED_CONTEXT_ID) {
                eventHandler.datagramReceived(decoded.getPayload());
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
     * The {@link ConnectUdpSession} handed to the application -- kept
     * separate from {@link H2ConnectUdpResponseHandler} itself; see
     * {@code org.bluezoo.gumdrop.http.h3.H3ClientConnectUdpResponseHandler}'s
     * own documentation for why.
     */
    private static class H2ClientConnectUdpSession implements ConnectUdpSession {

        private final HTTPRequest request;

        H2ClientConnectUdpSession(HTTPRequest request) {
            this.request = request;
        }

        @Override
        public void sendDatagram(ByteBuffer payload) throws IOException {
            ByteBuffer contextEncoded =
                    HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, payload);
            byte[] contextBytes = new byte[contextEncoded.remaining()];
            contextEncoded.get(contextBytes);
            byte[] capsuleBytes = Capsule.datagram(contextBytes).encode();
            request.requestBodyContent(ByteBuffer.wrap(capsuleBytes));
        }

        @Override
        public void close() {
            request.endRequestBody();
        }
    }
}
