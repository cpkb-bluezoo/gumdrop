/*
 * SOCKSProtocolHandler.java
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

package org.bluezoo.gumdrop.socks;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.nio.channels.SocketChannel;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPEndpoint;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.auth.GSSAPIServer;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;
import org.bluezoo.gumdrop.socks.handler.BindHandler;
import org.bluezoo.gumdrop.socks.handler.BindState;
import org.bluezoo.gumdrop.socks.handler.ConnectHandler;
import org.bluezoo.gumdrop.socks.handler.ConnectState;

import static org.bluezoo.gumdrop.socks.SOCKSConstants.*;

/**
 * Server-side SOCKS protocol handler.
 *
 * <p>Handles SOCKS4, SOCKS4a, and SOCKS5 client connections through
 * a state machine that auto-detects the protocol version from the
 * first byte, negotiates authentication, parses the request, and
 * establishes a bidirectional relay to the upstream destination.
 *
 * <p>All operations are non-blocking and run on the connection's
 * SelectorLoop thread.
 *
 * <p>Auto-detection relies on the VER byte being either 0x04 (SOCKS4/4a)
 * or 0x05 (SOCKS5) per RFC 1928 §3 first octet.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SOCKSService
 * @see SOCKSRelay
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1928">RFC 1928</a> SOCKS Protocol Version 5
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1929">RFC 1929</a> Username/Password Authentication
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1961">RFC 1961</a> GSS-API Authentication
 * @see <a href="https://www.openssh.com/txt/socks4.protocol">SOCKS4 protocol</a>
 * @see <a href="https://www.openssh.com/txt/socks4a.protocol">SOCKS4a protocol</a>
 */
class SOCKSProtocolHandler implements ProtocolHandler {

    private static final Logger LOGGER =
            Logger.getLogger(SOCKSProtocolHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.socks.L10N");

    enum State {
        VERSION_DETECT,
        SOCKS4_REQUEST,
        SOCKS5_METHOD_NEGOTIATION,
        SOCKS5_AUTH_USERNAME_PASSWORD,
        SOCKS5_AUTH_GSSAPI,
        SOCKS5_REQUEST,
        CONNECT_AUTHORIZE,
        CONNECTING_UPSTREAM,
        BIND_WAITING,
        RELAY,
        UDP_ASSOCIATED,
        CLOSED
    }

    private final SOCKSListener listener;
    private final SOCKSService service;

    private Endpoint endpoint;
    private State state = State.VERSION_DETECT;
    private ConnectHandler connectHandler;
    private BindHandler bindHandler;
    private long connectionTimeMillis;

    // SOCKS5 authentication state
    private String authenticatedUser;
    private byte selectedAuthMethod;
    private GSSAPIServer.GSSAPIExchange gssapiExchange;

    // Relay
    private SOCKSRelay relay;
    private SOCKSUDPRelay udpRelay;
    private SOCKSBindRelay bindRelay;

    SOCKSProtocolHandler(SOCKSListener listener, SOCKSService service) {
        this.listener = listener;
        this.service = service;
    }

    void setConnectHandler(ConnectHandler handler) {
        this.connectHandler = handler;
    }

    void setBindHandler(BindHandler handler) {
        this.bindHandler = handler;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ProtocolHandler lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;
        this.connectionTimeMillis = System.currentTimeMillis();
        SOCKSServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.connectionOpened();
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.client_connected"),
                    endpoint.getRemoteAddress()));
        }
    }

    @Override
    public void disconnected() {
        if (relay != null) {
            relay.clientDisconnected();
        }
        if (udpRelay != null) {
            udpRelay.close();
        }
        if (bindRelay != null) {
            bindRelay.close();
        }
        if (gssapiExchange != null) {
            gssapiExchange.dispose();
            gssapiExchange = null;
        }
        SOCKSServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            double durationMs = (double) (System.currentTimeMillis()
                    - connectionTimeMillis);
            metrics.connectionClosed(durationMs);
        }
        state = State.CLOSED;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("log.client_disconnected"));
        }
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("log.tls_established"));
        }
    }

    @Override
    public void error(Exception cause) {
        if (state != State.CLOSED) {
            LOGGER.log(Level.WARNING,
                    L10N.getString("log.connection_error"), cause);
            close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Data receive — state machine dispatch
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void receive(ByteBuffer data) {
        try {
            switch (state) {
                case VERSION_DETECT:
                    handleVersionDetect(data);
                    break;
                case SOCKS4_REQUEST:
                    handleSOCKS4Request(data);
                    break;
                case SOCKS5_METHOD_NEGOTIATION:
                    handleSOCKS5MethodNegotiation(data);
                    break;
                case SOCKS5_AUTH_USERNAME_PASSWORD:
                    handleSOCKS5UsernamePassword(data);
                    break;
                case SOCKS5_AUTH_GSSAPI:
                    handleSOCKS5GSSAPI(data);
                    break;
                case SOCKS5_REQUEST:
                    handleSOCKS5Request(data);
                    break;
                case RELAY:
                    relay.clientData(data);
                    break;
                case BIND_WAITING:
                    // RFC 1928 §4: TCP control connection is idle
                    // while waiting for incoming BIND connection
                    break;
                case UDP_ASSOCIATED:
                    // RFC 1928 §7: TCP control connection is idle
                    // while UDP association is active; ignore TCP data
                    break;
                case CONNECT_AUTHORIZE:
                case CONNECTING_UPSTREAM:
                    // Data arrived while waiting for async operations;
                    // should not happen in normal flow
                    break;
                case CLOSED:
                    break;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString("log.protocol_error"), e);
            close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Version detection
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Detects protocol version from the first octet.
     * RFC 1928 §3 defines the SOCKS5 initial client greeting as VER + NMETHODS + METHODS;
     * SOCKS4 sends VER(0x04) immediately followed by the request.
     * The version byte (first octet) distinguishes the protocol.
     */
    private void handleVersionDetect(ByteBuffer data) {
        if (!data.hasRemaining()) {
            return;
        }
        byte version = data.get(data.position());
        if (version == SOCKS4_VERSION) {
            state = State.SOCKS4_REQUEST;
            handleSOCKS4Request(data);
        } else if (version == SOCKS5_VERSION) {
            state = State.SOCKS5_METHOD_NEGOTIATION;
            handleSOCKS5MethodNegotiation(data);
        } else {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("log.unsupported_version"),
                        version & 0xFF));
            }
            close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS4/4a request parsing
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Parses SOCKS4/4a request. SOCKS4 protocol: request format is
     * VER, CD, DSTPORT, DSTIP, USERID, NULL.
     */
    private void handleSOCKS4Request(ByteBuffer data) {
        // Minimum SOCKS4 request: VER(1) + CMD(1) + PORT(2) + IP(4) +
        //                         USERID(variable) + NULL(1) = 9
        if (data.remaining() < 9) {
            return;
        }

        int startPos = data.position();
        data.get(); // version byte (0x04), already checked
        byte cmd = data.get();
        int port = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);
        byte[] ipBytes = new byte[4];
        data.get(ipBytes);

        // Read null-terminated userid
        String userid = readNullTerminatedString(data);
        if (userid == null) {
            data.position(startPos);
            return; // incomplete
        }

        // SOCKS4a protocol: DSTIP 0.0.0.x (x!=0) signals hostname follows after userid NULL terminator
        boolean isSocks4a = ipBytes[0] == 0 && ipBytes[1] == 0
                && ipBytes[2] == 0 && ipBytes[3] != 0;
        String hostname = null;
        InetAddress address = null;

        if (isSocks4a) {
            hostname = readNullTerminatedString(data);
            if (hostname == null) {
                data.position(startPos);
                return; // incomplete
            }
        } else {
            try {
                address = InetAddress.getByAddress(ipBytes);
            } catch (UnknownHostException e) {
                sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
                close();
                return;
            }
        }

        // SOCKS4 protocol: CD=1 is CONNECT, CD=2 is BIND
        if (cmd == SOCKS4_CMD_BIND) {
            SOCKSRequest bindRequest = new SOCKSRequest(
                    SOCKS4_VERSION, cmd, address, hostname, port,
                    userid, null);
            handleBind(bindRequest);
            return;
        }

        if (cmd != SOCKS4_CMD_CONNECT) {
            sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
            close();
            return;
        }

        SOCKSRequest request = new SOCKSRequest(
                SOCKS4_VERSION, cmd, address, hostname, port,
                userid, null);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.request_received"),
                    request));
        }

        processConnectRequest(request);
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 method negotiation (RFC 1928 section 3)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * RFC 1928 §3: client sends VER(0x05) + NMETHODS + METHODS;
     * server selects one method and responds VER + METHOD.
     */
    private void handleSOCKS5MethodNegotiation(ByteBuffer data) {
        // VER(1) + NMETHODS(1) + METHODS(variable)
        if (data.remaining() < 2) {
            return;
        }
        int startPos = data.position();
        data.get(); // version (0x05)
        int nMethods = data.get() & 0xFF;
        if (data.remaining() < nMethods) {
            data.position(startPos);
            return;
        }

        boolean[] offered = new boolean[256];
        for (int i = 0; i < nMethods; i++) {
            offered[data.get() & 0xFF] = true;
        }

        Realm realm = listener.getRealm();
        GSSAPIServer gssapi = listener.getGSSAPIServer();

        // RFC 1928 §3 method values: 0x00=NO AUTH, 0x01=GSSAPI, 0x02=USERNAME/PASSWORD, 0xFF=NO ACCEPTABLE
        if (realm != null && gssapi != null
                && offered[SOCKS5_AUTH_GSSAPI & 0xFF]) {
            selectedAuthMethod = SOCKS5_AUTH_GSSAPI;
            state = State.SOCKS5_AUTH_GSSAPI;
            try {
                gssapiExchange = gssapi.createExchange();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("log.gssapi_init_failed"), e);
                sendSOCKS5MethodSelection(SOCKS5_AUTH_NO_ACCEPTABLE);
                close();
                return;
            }
        } else if (realm != null
                && offered[SOCKS5_AUTH_USERNAME_PASSWORD & 0xFF]) {
            selectedAuthMethod = SOCKS5_AUTH_USERNAME_PASSWORD;
            state = State.SOCKS5_AUTH_USERNAME_PASSWORD;
        } else if (realm == null && offered[SOCKS5_AUTH_NONE & 0xFF]) {
            selectedAuthMethod = SOCKS5_AUTH_NONE;
            state = State.SOCKS5_REQUEST;
        } else if (realm != null && offered[SOCKS5_AUTH_NONE & 0xFF]) {
            // Realm configured but client only offers no-auth
            selectedAuthMethod = SOCKS5_AUTH_NONE;
            state = State.SOCKS5_REQUEST;
        } else {
            sendSOCKS5MethodSelection(SOCKS5_AUTH_NO_ACCEPTABLE);
            close();
            return;
        }

        sendSOCKS5MethodSelection(selectedAuthMethod);
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 username/password auth (RFC 1929)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * RFC 1929 §2: sub-negotiation format is VER(0x01) + ULEN + UNAME + PLEN + PASSWD.
     * Server responds with VER(0x01) + STATUS (0x00=success).
     */
    private void handleSOCKS5UsernamePassword(ByteBuffer data) {
        // VER(1) + ULEN(1) + UNAME(var) + PLEN(1) + PASSWD(var)
        if (data.remaining() < 2) {
            return;
        }
        int startPos = data.position();
        byte ver = data.get();
        if (ver != SOCKS5_AUTH_USERPASS_VERSION) {
            sendSOCKS5AuthResult(SOCKS5_AUTH_USERPASS_FAILURE);
            close();
            return;
        }

        int uLen = data.get() & 0xFF;
        if (data.remaining() < uLen + 1) {
            data.position(startPos);
            return;
        }
        byte[] uBytes = new byte[uLen];
        data.get(uBytes);
        String username = new String(uBytes, StandardCharsets.UTF_8);

        int pLen = data.get() & 0xFF;
        if (data.remaining() < pLen) {
            data.position(startPos);
            return;
        }
        byte[] pBytes = new byte[pLen];
        data.get(pBytes);
        String password = new String(pBytes, StandardCharsets.UTF_8);

        Realm realm = listener.getRealm();
        SOCKSServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt("username_password");
        }
        if (realm != null && realm.passwordMatch(username, password)) {
            authenticatedUser = username;
            if (metrics != null) {
                metrics.authSuccess();
            }
            sendSOCKS5AuthResult(SOCKS5_AUTH_USERPASS_SUCCESS);
            state = State.SOCKS5_REQUEST;
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("log.auth_success"), username));
            }
        } else {
            if (metrics != null) {
                metrics.authFailure();
            }
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("log.auth_failure"), username));
            }
            sendSOCKS5AuthResult(SOCKS5_AUTH_USERPASS_FAILURE);
            close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 GSSAPI auth (RFC 1961)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * RFC 1961 §3: GSSAPI sub-negotiation frame is VER(0x01) + MTYP(0x01) + LEN(2) + TOKEN(variable).
     * Multi-round-trip exchange until context is established (RFC 1961 §4).
     */
    private void handleSOCKS5GSSAPI(ByteBuffer data) {
        // RFC 1961: VER(1) + MTYP(1) + LEN(2) + TOKEN(var)
        if (data.remaining() < 4) {
            return;
        }
        int startPos = data.position();
        byte ver = data.get();
        byte mtyp = data.get();
        int tokenLen = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);

        if (ver != SOCKS5_GSSAPI_VERSION || mtyp != SOCKS5_GSSAPI_MSG_AUTH) {
            sendSOCKS5GSSAPIFailure();
            close();
            return;
        }
        if (data.remaining() < tokenLen) {
            data.position(startPos);
            return;
        }

        byte[] clientToken = new byte[tokenLen];
        data.get(clientToken);

        SOCKSServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt("gssapi");
        }

        try {
            byte[] serverToken = gssapiExchange.acceptToken(clientToken);

            if (gssapiExchange.isContextEstablished()) {
                // Send final token if any, then transition
                if (serverToken != null && serverToken.length > 0) {
                    sendSOCKS5GSSAPIToken(serverToken);
                }
                // Extract authenticated principal via security layer
                // negotiation. The server offers SECURITY_LAYER_NONE
                // only — RFC 1961 §5 per-message encapsulation is
                // intentionally unsupported; TLS at the transport
                // layer provides equivalent confidentiality and
                // integrity protection.
                Realm realm = listener.getRealm();
                if (realm != null) {
                    String gssPrincipal = gssapiExchange
                            .validateSecurityLayerResponse(
                                    gssapiExchange
                                            .generateSecurityLayerChallenge());
                    authenticatedUser =
                            realm.mapKerberosPrincipal(gssPrincipal);
                }
                gssapiExchange.dispose();
                gssapiExchange = null;
                state = State.SOCKS5_REQUEST;
                if (metrics != null) {
                    metrics.authSuccess();
                }
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            L10N.getString("log.gssapi_auth_success"),
                            authenticatedUser));
                }
            } else {
                if (serverToken != null && serverToken.length > 0) {
                    sendSOCKS5GSSAPIToken(serverToken);
                }
            }
        } catch (IOException e) {
            if (metrics != null) {
                metrics.authFailure();
            }
            LOGGER.log(Level.WARNING,
                    L10N.getString("log.gssapi_auth_failed"), e);
            sendSOCKS5GSSAPIFailure();
            close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 request parsing (RFC 1928 section 4)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * RFC 1928 §4: request format is VER + CMD + RSV + ATYP + DST.ADDR + DST.PORT.
     * Address types defined in RFC 1928 §5.
     */
    private void handleSOCKS5Request(ByteBuffer data) {
        // VER(1) + CMD(1) + RSV(1) + ATYP(1) + DST.ADDR(var) + DST.PORT(2)
        if (data.remaining() < 4) {
            return;
        }
        int startPos = data.position();
        data.get(); // version (0x05)
        byte cmd = data.get();
        data.get(); // reserved

        byte atyp = data.get();
        InetAddress address = null;
        String hostname = null;

        switch (atyp) {
            case SOCKS5_ATYP_IPV4: { // RFC 1928 §5: IPv4 — 4 octets
                if (data.remaining() < 6) { // 4 + port(2)
                    data.position(startPos);
                    return;
                }
                byte[] ipv4 = new byte[4];
                data.get(ipv4);
                try {
                    address = InetAddress.getByAddress(ipv4);
                } catch (UnknownHostException e) {
                    sendSOCKS5Reply(SOCKS5_REPLY_HOST_UNREACHABLE, null);
                    close();
                    return;
                }
                break;
            }
            case SOCKS5_ATYP_DOMAINNAME: { // RFC 1928 §5: DOMAINNAME — 1-octet length + FQDN
                if (data.remaining() < 1) {
                    data.position(startPos);
                    return;
                }
                int nameLen = data.get() & 0xFF;
                if (data.remaining() < nameLen + 2) { // name + port(2)
                    data.position(startPos);
                    return;
                }
                byte[] nameBytes = new byte[nameLen];
                data.get(nameBytes);
                hostname = new String(nameBytes,
                        StandardCharsets.US_ASCII);
                break;
            }
            case SOCKS5_ATYP_IPV6: { // RFC 1928 §5: IPv6 — 16 octets
                if (data.remaining() < 18) { // 16 + port(2)
                    data.position(startPos);
                    return;
                }
                byte[] ipv6 = new byte[16];
                data.get(ipv6);
                try {
                    address = InetAddress.getByAddress(ipv6);
                } catch (UnknownHostException e) {
                    sendSOCKS5Reply(SOCKS5_REPLY_HOST_UNREACHABLE, null);
                    close();
                    return;
                }
                break;
            }
            default:
                sendSOCKS5Reply(SOCKS5_REPLY_ADDRESS_TYPE_NOT_SUPPORTED,
                        null);
                close();
                return;
        }

        int port = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);

        if (cmd == SOCKS5_CMD_BIND) {
            // RFC 1928 §4: CMD=0x02 BIND
            SOCKSRequest bindRequest = new SOCKSRequest(
                    SOCKS5_VERSION, cmd, address, hostname, port,
                    null, authenticatedUser);
            handleBind(bindRequest);
            return;
        }
        if (cmd == SOCKS5_CMD_UDP_ASSOCIATE) {
            // RFC 1928 §4: CMD=0x03 UDP ASSOCIATE (§7 defines the relay)
            handleUDPAssociate(address);
            return;
        }
        if (cmd != SOCKS5_CMD_CONNECT) {
            sendSOCKS5Reply(SOCKS5_REPLY_COMMAND_NOT_SUPPORTED, null);
            close();
            return;
        }

        SOCKSRequest request = new SOCKSRequest(
                SOCKS5_VERSION, cmd, address, hostname, port,
                null, authenticatedUser);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.request_received"),
                    request));
        }

        processConnectRequest(request);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Connect request processing (common for SOCKS4/4a/5)
    // ═══════════════════════════════════════════════════════════════════

    private void processConnectRequest(final SOCKSRequest request) {
        state = State.CONNECT_AUTHORIZE;

        SOCKSServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            String version = socksVersionLabel(request);
            metrics.connectRequest(version);
        }

        if (connectHandler != null) {
            connectHandler.handleConnect(
                    new ConnectStateImpl(request), request, endpoint);
        } else {
            authorizeAndConnect(request);
        }
    }

    private void authorizeAndConnect(SOCKSRequest request) {
        if (request.getHost() != null && request.getAddress() == null) {
            resolveAndConnect(request);
        } else {
            connectToDestination(request, request.getAddress());
        }
    }

    private void resolveAndConnect(final SOCKSRequest request) {
        SelectorLoop loop = endpoint.getSelectorLoop();
        DNSResolver resolver = DNSResolver.forLoop(loop);
        resolver.resolve(request.getHost(), new ResolveCallback() {
            @Override
            public void onResolved(List<InetAddress> addresses) {
                InetAddress resolved = addresses.get(0);
                connectToDestination(request, resolved);
            }

            @Override
            public void onError(String error) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            L10N.getString("log.dns_resolution_failed"),
                            request.getHost(), error));
                }
                if (request.getVersion() == SOCKS4_VERSION) {
                    sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
                } else {
                    sendSOCKS5Reply(SOCKS5_REPLY_HOST_UNREACHABLE, null);
                }
                close();
            }
        });
    }

    private void connectToDestination(SOCKSRequest request,
                                      InetAddress resolved) {
        if (!service.isDestinationAllowed(resolved)) {
            SOCKSServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                metrics.destinationBlocked();
            }
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("log.destination_blocked"),
                        resolved));
            }
            if (request.getVersion() == SOCKS4_VERSION) {
                sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
            } else {
                sendSOCKS5Reply(SOCKS5_REPLY_NOT_ALLOWED, null);
            }
            close();
            return;
        }

        if (!service.acquireRelay()) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(L10N.getString("log.max_relays_reached"));
            }
            if (request.getVersion() == SOCKS4_VERSION) {
                sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
            } else {
                sendSOCKS5Reply(SOCKS5_REPLY_GENERAL_FAILURE, null);
            }
            close();
            return;
        }

        state = State.CONNECTING_UPSTREAM;
        initiateUpstreamConnection(request, resolved);
    }

    private void initiateUpstreamConnection(final SOCKSRequest request,
                                            final InetAddress resolved) {
        try {
            TCPTransportFactory factory = new TCPTransportFactory();
            factory.start();

            SelectorLoop loop = endpoint.getSelectorLoop();
            ClientEndpoint client = new ClientEndpoint(
                    factory, loop, resolved, request.getPort());

            relay = new SOCKSRelay(endpoint, service,
                    getServerMetrics(),
                    service.getRelayIdleTimeoutMs());

            client.connect(new ProtocolHandler() {
                @Override
                public void connected(Endpoint upstream) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine(MessageFormat.format(
                                L10N.getString("log.upstream_connected"),
                                resolved.getHostAddress(),
                                request.getPort()));
                    }
                    relay.upstreamConnected(upstream);
                    sendConnectSuccess(request, resolved);
                    state = State.RELAY;
                }

                @Override
                public void receive(ByteBuffer data) {
                    relay.upstreamData(data);
                }

                @Override
                public void disconnected() {
                    relay.upstreamDisconnected();
                }

                @Override
                public void securityEstablished(SecurityInfo info) {
                    // TLS on upstream established
                }

                @Override
                public void error(Exception cause) {
                    if (state == State.CONNECTING_UPSTREAM) {
                        LOGGER.log(Level.FINE,
                                L10N.getString("log.upstream_connect_failed"),
                                cause);
                        service.releaseRelay();
                        if (request.getVersion() == SOCKS4_VERSION) {
                            sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
                        } else {
                            sendSOCKS5Reply(
                                    SOCKS5_REPLY_CONNECTION_REFUSED, null);
                        }
                        close();
                    } else {
                        relay.upstreamDisconnected();
                    }
                }
            });
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString("log.upstream_connect_failed"), e);
            service.releaseRelay();
            if (request.getVersion() == SOCKS4_VERSION) {
                sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
            } else {
                sendSOCKS5Reply(SOCKS5_REPLY_GENERAL_FAILURE, null);
            }
            close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BIND (RFC 1928 §4 CMD=0x02, SOCKS4 CD=2)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Handles a SOCKS BIND request. Optionally delegates to a custom
     * {@link BindHandler} for authorization, then binds a listening
     * port and waits for an incoming connection.
     *
     * <p>RFC 1928 §4: "it is expected that a SOCKS server will use
     * DST.ADDR and DST.PORT in evaluating the BIND request."
     */
    private void handleBind(final SOCKSRequest request) {
        SOCKSServerMetrics mtr = getServerMetrics();
        if (mtr != null) {
            String version = socksVersionLabel(request);
            mtr.bindRequest(version);
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.bind_request"),
                    endpoint.getRemoteAddress(),
                    request.getHost() != null
                            ? request.getHost()
                            : (request.getAddress() != null
                                    ? request.getAddress()
                                            .getHostAddress()
                                    : "0.0.0.0"),
                    request.getPort()));
        }

        if (bindHandler != null) {
            bindHandler.handleBind(
                    new BindStateImpl(request), request, endpoint);
        } else {
            authorizeAndBind(request);
        }
    }

    private void authorizeAndBind(SOCKSRequest request) {
        if (!service.acquireRelay()) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(L10N.getString("log.max_relays_reached"));
            }
            if (request.getVersion() == SOCKS4_VERSION) {
                sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
            } else {
                sendSOCKS5Reply(SOCKS5_REPLY_GENERAL_FAILURE, null);
            }
            close();
            return;
        }

        initiateBind(request);
    }

    private void initiateBind(final SOCKSRequest request) {
        InetAddress expectedPeer = request.getAddress();
        if (expectedPeer != null && expectedPeer.isAnyLocalAddress()) {
            expectedPeer = null;
        }

        try {
            bindRelay = new SOCKSBindRelay(endpoint, service,
                    service.getRelayIdleTimeoutMs(),
                    expectedPeer,
                    new SOCKSBindRelay.Callback() {
                        @Override
                        public void bindAccepted(
                                SocketChannel sc,
                                InetSocketAddress peerAddress) {
                            onBindAccepted(request, sc, peerAddress);
                        }

                        @Override
                        public void bindFailed(byte replyCode) {
                            onBindFailed(request, replyCode);
                        }
                    });

            InetSocketAddress bound = bindRelay.start();

            // Reply 1: BND.ADDR:BND.PORT of the listen socket
            if (request.getVersion() == SOCKS4_VERSION) {
                sendSOCKS4ReplyWithAddr(SOCKS4_REPLY_GRANTED,
                        bound.getAddress(), bound.getPort());
            } else {
                sendSOCKS5ReplyWithPort(SOCKS5_REPLY_SUCCEEDED,
                        bound.getAddress(), bound.getPort());
            }
            state = State.BIND_WAITING;

        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString("log.bind_failed"), e);
            service.releaseRelay();
            if (request.getVersion() == SOCKS4_VERSION) {
                sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
            } else {
                sendSOCKS5Reply(SOCKS5_REPLY_GENERAL_FAILURE, null);
            }
            close();
        }
    }

    /**
     * Called by SOCKSBindRelay on the control SelectorLoop thread
     * when an incoming connection has been accepted and validated.
     */
    private void onBindAccepted(SOCKSRequest request,
                                SocketChannel sc,
                                InetSocketAddress peerAddress) {
        try {
            // Reply 2: BND.ADDR:BND.PORT of the connecting peer
            if (request.getVersion() == SOCKS4_VERSION) {
                sendSOCKS4ReplyWithAddr(SOCKS4_REPLY_GRANTED,
                        peerAddress.getAddress(),
                        peerAddress.getPort());
            } else {
                sendSOCKS5ReplyWithPort(SOCKS5_REPLY_SUCCEEDED,
                        peerAddress.getAddress(),
                        peerAddress.getPort());
            }

            TCPTransportFactory factory = new TCPTransportFactory();
            factory.start();

            relay = new SOCKSRelay(endpoint, service,
                    getServerMetrics(),
                    service.getRelayIdleTimeoutMs());

            ProtocolHandler upstreamHandler = new ProtocolHandler() {
                @Override
                public void connected(Endpoint upstream) {
                    relay.upstreamConnected(upstream);
                    state = State.RELAY;
                }

                @Override
                public void receive(ByteBuffer data) {
                    relay.upstreamData(data);
                }

                @Override
                public void disconnected() {
                    relay.upstreamDisconnected();
                }

                @Override
                public void securityEstablished(SecurityInfo info) {
                    // No action needed
                }

                @Override
                public void error(Exception cause) {
                    relay.upstreamDisconnected();
                }
            };

            SelectorLoop loop = endpoint.getSelectorLoop();
            TCPEndpoint peerEndpoint =
                    factory.createServerEndpoint(sc, upstreamHandler);
            loop.registerTCP(sc, peerEndpoint);
            upstreamHandler.connected(peerEndpoint);

        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString("log.bind_failed"), e);
            if (request.getVersion() == SOCKS4_VERSION) {
                sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
            } else {
                sendSOCKS5Reply(SOCKS5_REPLY_GENERAL_FAILURE, null);
            }
            close();
        }
    }

    /**
     * Called by SOCKSBindRelay when the bind fails (timeout or
     * peer rejected).
     */
    private void onBindFailed(SOCKSRequest request, byte replyCode) {
        if (request.getVersion() == SOCKS4_VERSION) {
            sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
        } else {
            sendSOCKS5Reply(replyCode, null);
        }
        close();
    }

    // ═══════════════════════════════════════════════════════════════════
    // UDP ASSOCIATE (RFC 1928 §4 CMD=0x03, §7)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Handles a SOCKS5 UDP ASSOCIATE request. Creates a
     * {@link SOCKSUDPRelay} with two ephemeral UDP ports and replies
     * with the client-facing BND.ADDR:BND.PORT.
     *
     * <p>RFC 1928 §7: the client's DST.ADDR and DST.PORT indicate
     * the address from which the client expects to send UDP datagrams.
     * If the address is all zeros (0.0.0.0 or ::), the TCP connection's
     * remote address is used for source validation.
     */
    private void handleUDPAssociate(InetAddress address) {
        SOCKSServerMetrics mtr = getServerMetrics();
        if (mtr != null) {
            mtr.connectRequest("5");
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.udp_associate_request"),
                    endpoint.getRemoteAddress()));
        }

        if (!service.acquireRelay()) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(L10N.getString("log.max_relays_reached"));
            }
            sendSOCKS5Reply(SOCKS5_REPLY_GENERAL_FAILURE, null);
            close();
            return;
        }

        // RFC 1928 §7: determine expected client source IP.
        // If DST.ADDR is all zeros, use the TCP connection's remote
        // address for source validation.
        InetAddress expectedClient = address;
        if (expectedClient == null || expectedClient.isAnyLocalAddress()) {
            InetSocketAddress tcpRemote =
                    (InetSocketAddress) endpoint.getRemoteAddress();
            expectedClient = tcpRemote.getAddress();
        }

        try {
            udpRelay = new SOCKSUDPRelay(endpoint, service,
                    mtr, service.getRelayIdleTimeoutMs(),
                    expectedClient);
            InetSocketAddress bound = udpRelay.start();

            sendSOCKS5ReplyWithPort(SOCKS5_REPLY_SUCCEEDED,
                    bound.getAddress(), bound.getPort());
            state = State.UDP_ASSOCIATED;

        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString("log.upstream_connect_failed"), e);
            service.releaseRelay();
            sendSOCKS5Reply(SOCKS5_REPLY_GENERAL_FAILURE, null);
            close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Reply sending
    // ═══════════════════════════════════════════════════════════════════

    private void sendConnectSuccess(SOCKSRequest request,
                                    InetAddress bound) {
        if (request.getVersion() == SOCKS4_VERSION) {
            sendSOCKS4Reply(SOCKS4_REPLY_GRANTED);
        } else {
            sendSOCKS5Reply(SOCKS5_REPLY_SUCCEEDED, bound);
        }
    }

    private void sendSOCKS4Reply(byte reply) {
        // SOCKS4 protocol §Reply: VN(0x00) + CD + DSTPORT + DSTIP
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put((byte) 0x00); // reply version
        buf.put(reply);
        buf.putShort((short) 0); // port (ignored)
        buf.putInt(0); // address (ignored)
        buf.flip();
        endpoint.send(buf);
    }

    /**
     * SOCKS4 reply with explicit address and port. Used by BIND
     * where the reply must carry BND.PORT and BND.IP.
     */
    private void sendSOCKS4ReplyWithAddr(byte reply,
                                         InetAddress addr,
                                         int port) {
        byte[] addrBytes = (addr != null)
                ? addr.getAddress() : new byte[4];
        if (addrBytes.length != 4) {
            addrBytes = new byte[4];
        }
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put((byte) 0x00); // reply version
        buf.put(reply);
        buf.putShort((short) port);
        buf.put(addrBytes);
        buf.flip();
        endpoint.send(buf);
    }

    private void sendSOCKS5MethodSelection(byte method) {
        // RFC 1928 §3: server method selection response: VER + METHOD
        ByteBuffer buf = ByteBuffer.allocate(2);
        buf.put(SOCKS5_VERSION);
        buf.put(method);
        buf.flip();
        endpoint.send(buf);
    }

    private void sendSOCKS5AuthResult(byte status) {
        // RFC 1929 §2: server auth response: VER + STATUS
        ByteBuffer buf = ByteBuffer.allocate(2);
        buf.put(SOCKS5_AUTH_USERPASS_VERSION);
        buf.put(status);
        buf.flip();
        endpoint.send(buf);
    }

    private void sendSOCKS5GSSAPIToken(byte[] token) {
        // RFC 1961 §3: server auth token: VER + MTYP + LEN + TOKEN
        ByteBuffer buf = ByteBuffer.allocate(4 + token.length);
        buf.put(SOCKS5_GSSAPI_VERSION);
        buf.put(SOCKS5_GSSAPI_MSG_AUTH);
        buf.putShort((short) token.length);
        buf.put(token);
        buf.flip();
        endpoint.send(buf);
    }

    private void sendSOCKS5GSSAPIFailure() {
        // RFC 1961 §4: context establishment failure
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.put(SOCKS5_GSSAPI_VERSION);
        buf.put(SOCKS5_GSSAPI_STATUS_FAILURE);
        buf.putShort((short) 0);
        buf.flip();
        endpoint.send(buf);
    }

    /**
     * RFC 1928 §6: reply format is VER + REP + RSV + ATYP + BND.ADDR + BND.PORT.
     */
    private void sendSOCKS5Reply(byte reply, InetAddress bindAddr) {
        int boundPort = 0;
        if (endpoint.getLocalAddress() instanceof InetSocketAddress) {
            boundPort = ((InetSocketAddress) endpoint.getLocalAddress())
                    .getPort();
        }
        sendSOCKS5ReplyWithPort(reply, bindAddr, boundPort);
    }

    /**
     * RFC 1928 §6: reply with an explicit BND.ADDR and BND.PORT.
     * Used by UDP ASSOCIATE to report the client-facing UDP port.
     */
    private void sendSOCKS5ReplyWithPort(byte reply,
                                         InetAddress bindAddr,
                                         int bindPort) {
        byte atyp;
        byte[] addrBytes;
        if (bindAddr instanceof Inet6Address) {
            atyp = SOCKS5_ATYP_IPV6;
            addrBytes = bindAddr.getAddress();
        } else if (bindAddr instanceof Inet4Address) {
            atyp = SOCKS5_ATYP_IPV4;
            addrBytes = bindAddr.getAddress();
        } else {
            atyp = SOCKS5_ATYP_IPV4;
            addrBytes = new byte[4];
        }

        ByteBuffer buf = ByteBuffer.allocate(
                4 + addrBytes.length + 2);
        buf.put(SOCKS5_VERSION);
        buf.put(reply);
        buf.put((byte) 0x00); // reserved
        buf.put(atyp);
        buf.put(addrBytes);
        buf.putShort((short) bindPort);
        buf.flip();
        endpoint.send(buf);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════════════

    private String readNullTerminatedString(ByteBuffer data) {
        int start = data.position();
        while (data.hasRemaining()) {
            if (data.get() == 0x00) {
                int end = data.position() - 1;
                int len = end - start;
                byte[] bytes = new byte[len];
                int savedPos = data.position();
                data.position(start);
                data.get(bytes);
                data.position(savedPos);
                return new String(bytes,
                        StandardCharsets.ISO_8859_1);
            }
        }
        return null; // incomplete
    }

    private void close() {
        state = State.CLOSED;
        if (endpoint != null && endpoint.isOpen()) {
            endpoint.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Telemetry
    // ═══════════════════════════════════════════════════════════════════

    private SOCKSServerMetrics getServerMetrics() {
        return listener != null ? listener.getMetrics() : null;
    }

    private static String socksVersionLabel(SOCKSRequest request) {
        if (request.getVersion() == SOCKS4_VERSION) {
            return request.getHost() != null ? "4a" : "4";
        }
        return "5";
    }

    // ═══════════════════════════════════════════════════════════════════
    // ConnectState implementation (async authorization callback)
    // ═══════════════════════════════════════════════════════════════════

    private final class ConnectStateImpl implements ConnectState {

        private final SOCKSRequest request;

        ConnectStateImpl(SOCKSRequest request) {
            this.request = request;
        }

        @Override
        public void allow() {
            authorizeAndConnect(request);
        }

        @Override
        public void deny(int replyCode) {
            if (request.getVersion() == SOCKS4_VERSION) {
                sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
            } else {
                sendSOCKS5Reply((byte) replyCode, null);
            }
            close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BindState implementation (async authorization callback)
    // ═══════════════════════════════════════════════════════════════════

    private final class BindStateImpl implements BindState {

        private final SOCKSRequest request;

        BindStateImpl(SOCKSRequest request) {
            this.request = request;
        }

        @Override
        public void allow() {
            authorizeAndBind(request);
        }

        @Override
        public void deny(int replyCode) {
            if (request.getVersion() == SOCKS4_VERSION) {
                sendSOCKS4Reply(SOCKS4_REPLY_REJECTED);
            } else {
                sendSOCKS5Reply((byte) replyCode, null);
            }
            close();
        }
    }

}
