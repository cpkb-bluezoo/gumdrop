/*
 * ConnectUdpClientProtocolHandler.java
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
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpDatagramContext;

/**
 * Protocol handler for CONNECT-UDP client connections over HTTP/1.1 (RFC
 * 9298 section 3, RFC 9110 section 7.8).
 *
 * <p>Extends {@link HTTPClientProtocolHandler} exactly the way {@code
 * org.bluezoo.gumdrop.websocket.client.WebSocketClientProtocolHandler}
 * does for WebSocket: before the upgrade, HTTP parsing proceeds normally;
 * once a {@code 101 Switching Protocols} response with {@code Upgrade:
 * connect-udp} is received, this handler takes over all subsequent data
 * on the connection. Unlike WebSocket, there is no RFC 6455-style framed
 * message protocol to switch into -- the tunnel's wire format is the
 * Capsule Protocol (RFC 9297 section 3.2) directly, the same format
 * {@link org.bluezoo.gumdrop.http.h3.H3ClientStream} already dispatches
 * for HTTP/3's capsule fallback and {@code Stream}/{@code
 * ConnectUdpRelay} use server-side.
 *
 * <p>This class is not intended to be used directly. Use {@link
 * ConnectUdpClient} instead.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectUdpClient
 * @see HTTPClientProtocolHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
class ConnectUdpClientProtocolHandler extends HTTPClientProtocolHandler {

    private final ConnectUdpEventHandler eventHandler;

    private volatile boolean connectUdpMode;
    private final CapsuleParser capsuleParser = new CapsuleParser();
    private ClientConnectUdpSession session;

    /**
     * Creates a CONNECT-UDP client protocol handler.
     *
     * @param clientHandler the HTTP client handler for connection lifecycle
     * @param eventHandler the CONNECT-UDP event handler for application events
     * @param host the target host
     * @param port the target port
     * @param secure whether this is a secure (TLS) connection
     */
    ConnectUdpClientProtocolHandler(HTTPClientHandler clientHandler,
                                    ConnectUdpEventHandler eventHandler,
                                    String host, int port,
                                    boolean secure) {
        super(clientHandler, host, port, secure);
        this.eventHandler = eventHandler;
    }

    /**
     * Exposes the inherited Alt-Svc listener hook to {@link
     * ConnectUdpClient}, in the same package but not a subclass of {@link
     * HTTPClientProtocolHandler}. An override cannot narrow the inherited
     * method's access, so this stays {@code protected} -- callers in this
     * package (like {@link ConnectUdpClient}) can still reach it.
     *
     * @param listener the listener, or null to disable
     */
    @Override
    protected void setAltSvcListener(AltSvcListener listener) {
        super.setAltSvcListener(listener);
    }

    /**
     * Returns the active {@link ConnectUdpSession}, or null if the
     * upgrade has not yet completed.
     */
    ConnectUdpSession getConnectUdpSession() {
        return session;
    }

    /** RFC 9298 section 3: validates and switches to CONNECT-UDP tunnel mode. */
    @Override
    protected boolean handleProtocolSwitch(HTTPStatus status, Headers headers) {
        if (!"connect-udp".equalsIgnoreCase(headers.getValue("upgrade"))) {
            return false;
        }

        connectUdpMode = true;
        session = new ClientConnectUdpSession(this);

        // Clean up HTTP state
        currentStream = null;
        parseState = ParseState.IDLE;

        eventHandler.opened(session);

        // Drain any pipelined capsule bytes left in the current receive()
        // call's buffer beyond what the lexer has consumed so far -- see
        // HTTPClientProtocolHandler#currentReceiveBuffer -- after opened(),
        // so the application always sees acceptance before any datagram.
        if (currentReceiveBuffer != null && currentReceiveBuffer.hasRemaining()) {
            dispatchCapsules(currentReceiveBuffer);
        }

        return true;
    }

    /**
     * Tells the base class's {@code receive()} loop to stop once {@link
     * #handleProtocolSwitch} has switched to CONNECT-UDP mode mid-call,
     * since that method already drained the remainder of {@code
     * currentReceiveBuffer} itself.
     */
    @Override
    protected boolean isExternallyHandled() {
        return connectUdpMode;
    }

    /** RFC 9297 section 3.2: routes data to capsule parsing after the upgrade. */
    @Override
    public void receive(ByteBuffer data) {
        if (connectUdpMode) {
            dispatchCapsules(data);
            return;
        }
        super.receive(data);
    }

    @Override
    public void disconnected() {
        if (connectUdpMode) {
            eventHandler.closed();
            return;
        }
        super.disconnected();
    }

    private void dispatchCapsules(ByteBuffer data) {
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

    /**
     * Writes {@code capsuleBytes} directly to the underlying (now
     * tunnelled) connection.
     */
    void sendCapsule(byte[] capsuleBytes) throws IOException {
        if (endpoint == null) {
            throw new IOException("Endpoint not available");
        }
        endpoint.send(ByteBuffer.wrap(capsuleBytes));
    }

    void closeConnectUdp() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    /**
     * The {@link ConnectUdpSession} handed to the application. Kept
     * separate from this class -- see {@code
     * org.bluezoo.gumdrop.http.h3.H3ClientConnectUdpResponseHandler}'s
     * own documentation for why a single class cannot implement both
     * {@link ConnectUdpSession} and an interface that (like {@link
     * HTTPResponseHandler}) also declares a differently-meaning {@code
     * close()} -- not a concern for this particular class today, but kept
     * consistent with the pattern regardless.
     */
    private static class ClientConnectUdpSession implements ConnectUdpSession {

        private final ConnectUdpClientProtocolHandler owner;

        ClientConnectUdpSession(ConnectUdpClientProtocolHandler owner) {
            this.owner = owner;
        }

        @Override
        public void sendDatagram(ByteBuffer payload) throws IOException {
            ByteBuffer contextEncoded =
                    HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID, payload);
            byte[] contextBytes = new byte[contextEncoded.remaining()];
            contextEncoded.get(contextBytes);
            byte[] capsuleBytes = Capsule.datagram(contextBytes).encode();
            owner.sendCapsule(capsuleBytes);
        }

        @Override
        public void close() {
            owner.closeConnectUdp();
        }
    }
}
