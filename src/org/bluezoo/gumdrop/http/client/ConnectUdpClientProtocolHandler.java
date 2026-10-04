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
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.CapsuleParser;
import org.bluezoo.gumdrop.http.HttpMessageHandler;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpDatagramContext;

/**
 * Protocol handler for CONNECT-UDP client connections over HTTP/1.1 (RFC
 * 9298 section 3, RFC 9110 section 7.8).
 *
 * <p>Extends {@link HttpClientProtocolHandler} exactly the way {@code
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
 * @see HttpClientProtocolHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
class ConnectUdpClientProtocolHandler extends HttpClientProtocolHandler {

    private final ConnectUdpEventHandler eventHandler;

    private volatile boolean connectUdpMode;
    // the Upgrade token of the 101 response being switched on
    private String switchUpgrade;
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
    ConnectUdpClientProtocolHandler(HttpClientHandler clientHandler,
                                    ConnectUdpEventHandler eventHandler,
                                    String host, int port,
                                    boolean secure) {
        super(clientHandler, host, port, secure);
        this.eventHandler = eventHandler;
    }

    /**
     * Exposes the inherited Alt-Svc listener hook to {@link
     * ConnectUdpClient}, in the same package but not a subclass of {@link
     * HttpClientProtocolHandler}. An override cannot narrow the inherited
     * method's access, so this stays {@code protected} -- callers in this
     * package (like {@link ConnectUdpClient}) can still reach it.
     *
     * @param listener the listener, or null to disable
     */
    @Override
    public void setAltSvcListener(AltSvcListener listener) {
        super.setAltSvcListener(listener);
    }

    /**
     * Returns the active {@link ConnectUdpSession}, or null if the
     * upgrade has not yet completed.
     */
    ConnectUdpSession getConnectUdpSession() {
        return session;
    }

    /**
     * Keeps the {@code Upgrade} token of the 101 response, which says what
     * the server switched to.
     */
    @Override
    protected HttpMessageHandler protocolSwitchEvents() {
        switchUpgrade = null;
        return new DefaultHttpResponseHandler() {
            @Override
            public void header(String name, ByteBuffer value) {
                if (switchUpgrade == null && "upgrade".equalsIgnoreCase(name)) {
                    byte[] octets = new byte[value.remaining()];
                    value.duplicate().get(octets);
                    switchUpgrade = new String(octets, StandardCharsets.ISO_8859_1).trim();
                }
            }
        };
    }

    /** RFC 9298 section 3: validates and switches to CONNECT-UDP tunnel mode. */
    @Override
    protected boolean handleProtocolSwitch(HttpStatus status) {
        if (!"connect-udp".equalsIgnoreCase(switchUpgrade)) {
            return false;
        }

        connectUdpMode = true;
        session = new ClientConnectUdpSession(this);

        // Clean up HTTP state
        currentStream = null;
        parseState = ParseState.IDLE;

        eventHandler.opened(session);

        // Any capsule bytes that followed the upgrade response in the same
        // buffer are re-dispatched to receive() by the HTTP layer once this
        // hook returns. They must not be read here: while a lexer token is
        // being delivered, currentReceiveBuffer is narrowed to that token (the
        // blank line ending the headers), not to the bytes after it.

        return true;
    }

    /**
     * Tells the base class's {@code receive()} loop to stop lexing HTTP once
     * {@link #handleProtocolSwitch} has switched to CONNECT-UDP mode mid-call,
     * and to re-dispatch the remainder of the buffer to {@link #receive}.
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
     * HttpResponseHandler}) also declares a differently-meaning {@code
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
