/*
 * H2ConnectIpResponseHandler.java
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
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;
import org.bluezoo.gumdrop.http.HttpDatagramContext;

/**
 * RFC 9484 -- bridges a generic HTTP/2 Extended CONNECT response ({@link
 * HTTPResponseHandler}) to a {@link ConnectIpEventHandler}/{@link
 * ConnectIpClientSession} pair.
 *
 * <p>Unlike the h3 client (which has its own dedicated {@code
 * H3ClientStream}, and so gets generic per-capsule-type dispatch for
 * free -- see {@code H3ClientConnectIpResponseHandler}), h2 responses
 * route generically through {@link HTTPResponseHandler} with no such
 * dispatch of their own: {@link #responseBodyContent} parses each DATA
 * frame's bytes as Capsule Protocol capsules (RFC 9297 section 3.2)
 * itself, delivering {@link Capsule#TYPE_DATAGRAM} payloads (RFC 9484
 * section 6) as IP packets and the two server-to-client capsule types
 * (RFC 9484 section 4.7.1/4.7.3) to the event handler directly. Direct
 * client-side mirror of {@link H2ConnectUdpResponseHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectIpClient
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484">RFC 9484</a>
 */
class H2ConnectIpResponseHandler extends DefaultHTTPResponseHandler {

    private final HTTPRequest request;
    private final ConnectIpEventHandler eventHandler;
    private final CapsuleParser capsuleParser = new CapsuleParser();

    private boolean opened;
    private boolean failed;

    H2ConnectIpResponseHandler(HTTPRequest request, ConnectIpEventHandler eventHandler) {
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
                "CONNECT-IP request failed: " + response.getStatus()));
    }

    @Override
    public void startResponseBody() {
        if (failed) {
            return;
        }
        opened = true;
        eventHandler.opened(new H2ClientConnectIpSession(request));
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
            long type = capsule.getType();
            ByteBuffer value = ByteBuffer.wrap(capsule.getValue());
            if (type == Capsule.TYPE_DATAGRAM) {
                HttpDatagramContext decoded = HttpDatagramContext.decode(value);
                if (decoded != null && decoded.getContextId() == HttpDatagramContext.REGISTERED_CONTEXT_ID) {
                    eventHandler.packetReceived(decoded.getPayload());
                }
            } else if (type == ConnectIpAddress.TYPE_ADDRESS_ASSIGN) {
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
     * kept separate from {@link H2ConnectIpResponseHandler} itself; see
     * {@code org.bluezoo.gumdrop.http.h3.H3ClientConnectUdpResponseHandler}'s
     * own documentation for why.
     */
    private static class H2ClientConnectIpSession implements ConnectIpClientSession {

        private final HTTPRequest request;

        H2ClientConnectIpSession(HTTPRequest request) {
            this.request = request;
        }

        @Override
        public void sendPacket(ByteBuffer packet) throws IOException {
            ByteBuffer contextEncoded =
                    HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, packet);
            byte[] contextBytes = new byte[contextEncoded.remaining()];
            contextEncoded.get(contextBytes);
            byte[] capsuleBytes = Capsule.datagram(contextBytes).encode();
            request.requestBodyContent(ByteBuffer.wrap(capsuleBytes));
        }

        @Override
        public void sendAddressRequest(List<ConnectIpAddress> requested) throws IOException {
            ByteBuffer capsuleValue = ConnectIpAddress.encodeList(requested);
            byte[] valueBytes = new byte[capsuleValue.remaining()];
            capsuleValue.get(valueBytes);
            byte[] capsuleBytes = new Capsule(ConnectIpAddress.TYPE_ADDRESS_REQUEST, valueBytes).encode();
            request.requestBodyContent(ByteBuffer.wrap(capsuleBytes));
        }

        @Override
        public void close() {
            request.endRequestBody();
        }
    }
}
