/*
 * SOCKSClientHandler.java
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

package org.bluezoo.gumdrop.socks.client;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.UDPTransportFactory;
import org.bluezoo.gumdrop.socks.SOCKSUDPHeader;
import org.bluezoo.gumdrop.util.ByteBufferPool;

import static org.bluezoo.gumdrop.socks.SOCKSConstants.*;

/**
 * Composable client-side SOCKS protocol handler.
 *
 * <p>Wraps an inner {@link ProtocolHandler} and tunnels its connection
 * through a SOCKS proxy. On {@link #connected(Endpoint)}, this handler
 * initiates the SOCKS handshake (version negotiation, optional
 * authentication, then the CONNECT or BIND request). Once the tunnel
 * is established, it calls {@code innerHandler.connected(endpoint)}
 * and forwards all subsequent data transparently.
 *
 * <p>BIND (RFC 1928 §4, SOCKS4/4a and SOCKS5) is supported the same
 * way: the proxy's first reply reports the address it is now listening
 * on, delivered to a {@link BindListener} so the caller can pass it to
 * the remote peer out-of-band (e.g. an FTP PORT command); once a peer
 * connects, the second reply arrives and the wrapped inner handler is
 * connected exactly as for CONNECT.
 *
 * <p>UDP ASSOCIATE (RFC 1928 §7, SOCKS5 only -- SOCKS4 has no
 * equivalent) is also supported: once the association is established,
 * a {@link UDPAssociateListener} is notified of the relay's address,
 * and datagrams may be exchanged with arbitrary destinations via
 * {@link #sendDatagram(InetSocketAddress, ByteBuffer)} and the
 * listener's {@code receive} callback, both using the same RFC 1928 §7
 * header framing ({@link SOCKSUDPHeader}) as the server side.
 *
 * <h4>Usage</h4>
 * <pre>{@code
 * // Connect to smtp.example.com:587 via SOCKS proxy at proxy:1080
 * ClientEndpoint client = new ClientEndpoint(factory, "proxy", 1080);
 * client.connect(new SOCKSClientHandler(
 *     "smtp.example.com", 587,
 *     new SMTPClientProtocolHandler(callback)));
 *
 * // With authentication
 * SOCKSClientConfig config = new SOCKSClientConfig("user", "pass");
 * client.connect(new SOCKSClientHandler(
 *     "smtp.example.com", 587, config,
 *     new SMTPClientProtocolHandler(callback)));
 *
 * // BIND: proxy listens on our behalf; give the reported address to
 * // the remote peer (e.g. via an FTP PORT command) before it connects
 * client.connect(new SOCKSClientHandler(
 *     "ftp.example.com", 0, config,
 *     boundAddress -> sendPortCommandToPeer(boundAddress),
 *     new MyPeerProtocolHandler(callback)));
 *
 * // UDP ASSOCIATE: exchange datagrams with arbitrary destinations
 * // through the proxy's relay
 * SOCKSClientHandler assoc = new SOCKSClientHandler(
 *     config, udpTransportFactory, myUdpAssociateListener);
 * client.connect(assoc);
 * // ... once myUdpAssociateListener.associated(relayAddress) fires:
 * assoc.sendDatagram(destination, payload);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SOCKSClientConfig
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1928">RFC 1928</a> SOCKS Protocol Version 5
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1929">RFC 1929</a> Username/Password Authentication for SOCKS V5
 * @see <a href="https://www.openssh.com/txt/socks4.protocol">SOCKS4 protocol</a>
 * @see <a href="https://www.openssh.com/txt/socks4a.protocol">SOCKS4a protocol</a>
 */
public class SOCKSClientHandler implements ProtocolHandler {

    private static final Logger LOGGER =
            Logger.getLogger(SOCKSClientHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.socks.L10N");

    enum State {
        AWAITING_METHOD_SELECTION,
        AWAITING_AUTH_RESPONSE,
        AWAITING_CONNECT_REPLY_V4,
        AWAITING_CONNECT_REPLY_V5,
        AWAITING_BIND_REPLY1_V4,
        AWAITING_BIND_REPLY2_V4,
        AWAITING_BIND_REPLY1_V5,
        AWAITING_BIND_REPLY2_V5,
        AWAITING_UDP_ASSOCIATE_REPLY,
        UDP_ASSOCIATED,
        TUNNEL_ESTABLISHED
    }

    /**
     * Reports the address a BIND proxy is now listening on, once its
     * first reply (RFC 1928 §4 / SOCKS4 BIND) arrives -- the caller
     * must pass this to the remote peer out-of-band (e.g. an FTP PORT
     * command) so the peer knows where to connect.
     */
    public interface BindListener {
        void bound(InetSocketAddress boundAddress);
    }

    /**
     * Notified of UDP ASSOCIATE (RFC 1928 §7) lifecycle events: once
     * the association is established, once for each datagram received
     * from the relay, and on error.
     */
    public interface UDPAssociateListener {
        void associated(InetSocketAddress relayAddress);
        void receive(InetSocketAddress source, ByteBuffer payload);
        void error(Exception cause);
    }

    private final String destHost;
    private final int destPort;
    private final SOCKSClientConfig config;
    private final ProtocolHandler innerHandler;
    private final byte command;
    private final BindListener bindListener;
    private final UDPTransportFactory udpTransportFactory;
    private final UDPAssociateListener udpAssociateListener;

    private Endpoint endpoint;
    private Endpoint udpEndpoint;
    private State state;

    // Bytes left over from a previous receive() call that weren't enough
    // to complete the current handshake stage (SOCKS is a streaming TCP
    // protocol; a reply can legally arrive split across multiple reads).
    // Each handleXxx below already leaves the buffer position unchanged
    // when it doesn't have enough data yet, so carrying the unconsumed
    // remainder forward and prepending it to the next call is sufficient
    // -- without this, a split reply is silently dropped and the
    // handshake hangs forever waiting for bytes the server already sent.
    private ByteBuffer pendingHandshakeData;

    /**
     * Creates a SOCKS client handler with default config (SOCKS5,
     * no auth).
     *
     * @param destHost the real destination hostname or IP
     * @param destPort the real destination port
     * @param innerHandler the protocol handler to tunnel
     */
    public SOCKSClientHandler(String destHost, int destPort,
                              ProtocolHandler innerHandler) {
        this(destHost, destPort, new SOCKSClientConfig(), innerHandler);
    }

    /**
     * Creates a SOCKS client handler with the given config.
     *
     * @param destHost the real destination hostname or IP
     * @param destPort the real destination port
     * @param config the SOCKS client configuration
     * @param innerHandler the protocol handler to tunnel
     */
    public SOCKSClientHandler(String destHost, int destPort,
                              SOCKSClientConfig config,
                              ProtocolHandler innerHandler) {
        if (destHost == null) {
            throw new NullPointerException("destHost");
        }
        if (innerHandler == null) {
            throw new NullPointerException("innerHandler");
        }
        if (config == null) {
            throw new NullPointerException("config");
        }
        this.destHost = destHost;
        this.destPort = destPort;
        this.config = config;
        this.innerHandler = innerHandler;
        this.command = SOCKS5_CMD_CONNECT;
        this.bindListener = null;
        this.udpTransportFactory = null;
        this.udpAssociateListener = null;
    }

    /**
     * Creates a SOCKS client handler that issues a BIND request (RFC
     * 1928 §4; SOCKS4/4a BIND) instead of CONNECT.
     *
     * <p>{@code host}/{@code port} are the address of the remote peer
     * the proxy should expect an incoming connection from -- the same
     * role CONNECT's destination plays, per the BIND request's
     * DST.ADDR/DST.PORT fields -- or a wildcard address if any peer is
     * acceptable. Once the proxy's first reply reports the address it
     * is now listening on, {@code bindListener} is notified so that
     * address can be handed to the peer out-of-band; once a peer
     * connects and the second reply arrives, {@code innerHandler} is
     * connected exactly as for CONNECT.
     *
     * @param host the expected peer's hostname or IP, or a wildcard address
     * @param port the expected peer's port, or 0
     * @param config the SOCKS client configuration
     * @param bindListener notified of the proxy's listening address
     * @param innerHandler the protocol handler to run once a peer connects
     */
    public SOCKSClientHandler(String host, int port,
                              SOCKSClientConfig config,
                              BindListener bindListener,
                              ProtocolHandler innerHandler) {
        if (host == null) {
            throw new NullPointerException("host");
        }
        if (config == null) {
            throw new NullPointerException("config");
        }
        if (bindListener == null) {
            throw new NullPointerException("bindListener");
        }
        if (innerHandler == null) {
            throw new NullPointerException("innerHandler");
        }
        this.destHost = host;
        this.destPort = port;
        this.config = config;
        this.innerHandler = innerHandler;
        this.command = SOCKS5_CMD_BIND;
        this.bindListener = bindListener;
        this.udpTransportFactory = null;
        this.udpAssociateListener = null;
    }

    /**
     * Creates a SOCKS client handler that issues a UDP ASSOCIATE
     * request (RFC 1928 §7) instead of CONNECT. SOCKS5 only -- SOCKS4
     * has no equivalent command.
     *
     * <p>Once the association is established, {@code listener} is
     * notified of the relay's address; datagrams can then be sent to
     * arbitrary destinations via {@link #sendDatagram(InetSocketAddress,
     * ByteBuffer)} and are delivered back via the listener's {@code
     * receive} callback, both wrapped in the RFC 1928 §7 header
     * ({@link SOCKSUDPHeader}). {@code udpTransportFactory} is used to
     * open the local UDP socket that talks to the relay, on the same
     * SelectorLoop as the TCP control connection; the association's
     * lifetime is tied to that connection staying open (RFC 1928 §7).
     *
     * @param config the SOCKS client configuration
     * @param udpTransportFactory factory for the local UDP socket to the relay
     * @param listener notified of the association and of received datagrams
     */
    public SOCKSClientHandler(SOCKSClientConfig config,
                              UDPTransportFactory udpTransportFactory,
                              UDPAssociateListener listener) {
        if (config == null) {
            throw new NullPointerException("config");
        }
        if (udpTransportFactory == null) {
            throw new NullPointerException("udpTransportFactory");
        }
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        if (config.getVersion() == SOCKSClientConfig.Version.SOCKS4) {
            // RFC 1928 §7 is SOCKS5-only; there is no SOCKS4 equivalent
            // of UDP ASSOCIATE, so an explicit SOCKS4 request here can
            // only be a caller mistake -- fail fast rather than silently
            // negotiating SOCKS5 behind the caller's back.
            throw new IllegalArgumentException(
                    "UDP ASSOCIATE requires SOCKS5 (RFC 1928 §7 has no SOCKS4 equivalent)");
        }
        this.destHost = null;
        this.destPort = 0;
        this.config = config;
        this.innerHandler = null;
        this.command = SOCKS5_CMD_UDP_ASSOCIATE;
        this.bindListener = null;
        this.udpTransportFactory = udpTransportFactory;
        this.udpAssociateListener = listener;
    }

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;

        // The UDP ASSOCIATE constructor already rejects SOCKS4 (RFC 1928
        // §7 has no SOCKS4 equivalent), so this check is never true for it.
        if (config.getVersion() == SOCKSClientConfig.Version.SOCKS4) {
            sendSOCKS4Request();
        } else {
            sendSOCKS5MethodRequest();
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        if (state == State.TUNNEL_ESTABLISHED) {
            innerHandler.receive(data);
            return;
        }

        ByteBuffer buf = data;
        if (pendingHandshakeData != null) {
            buf = ByteBuffer.allocate(pendingHandshakeData.remaining() + data.remaining());
            buf.put(pendingHandshakeData);
            buf.put(data);
            buf.flip();
            pendingHandshakeData = null;
        }

        // Loop rather than a single dispatch: one read can legally
        // contain more than one handshake stage's worth of bytes (e.g.
        // the method-select reply and the auth reply arriving in the
        // same TCP segment), so keep re-dispatching to whatever the
        // current stage is as long as the previous call actually made
        // progress and bytes remain.
        State stateBefore;
        do {
            stateBefore = state;
            switch (state) {
                case AWAITING_METHOD_SELECTION:
                    handleMethodSelection(buf);
                    break;
                case AWAITING_AUTH_RESPONSE:
                    handleAuthResponse(buf);
                    break;
                case AWAITING_CONNECT_REPLY_V4:
                    handleSOCKS4Reply(buf);
                    break;
                case AWAITING_CONNECT_REPLY_V5:
                    handleSOCKS5Reply(buf);
                    break;
                case AWAITING_BIND_REPLY1_V4:
                    handleBindReply1V4(buf);
                    break;
                case AWAITING_BIND_REPLY2_V4:
                    // Same wire shape and the same action on success/failure
                    // as CONNECT's reply.
                    handleSOCKS4Reply(buf);
                    break;
                case AWAITING_BIND_REPLY1_V5:
                    handleBindReply1V5(buf);
                    break;
                case AWAITING_BIND_REPLY2_V5:
                    // Same wire shape and the same action on success/failure
                    // as CONNECT's reply.
                    handleSOCKS5Reply(buf);
                    break;
                case AWAITING_UDP_ASSOCIATE_REPLY:
                    handleUDPAssociateReply(buf);
                    break;
                case UDP_ASSOCIATED:
                    // Control connection stays open for the association's
                    // lifetime (RFC 1928 §7) but carries no further data;
                    // datagrams flow over the separate UDP relay socket.
                    return;
                case TUNNEL_ESTABLISHED:
                    if (buf.hasRemaining()) {
                        innerHandler.receive(buf);
                    }
                    return;
            }
        } while (state != stateBefore && buf.hasRemaining());

        if (buf.hasRemaining()) {
            pendingHandshakeData = ByteBuffer.allocate(buf.remaining());
            pendingHandshakeData.put(buf);
            pendingHandshakeData.flip();
        }
    }

    @Override
    public void disconnected() {
        if (state == State.TUNNEL_ESTABLISHED) {
            innerHandler.disconnected();
        } else if (state == State.UDP_ASSOCIATED) {
            // RFC 1928 §7: the association ends when the TCP control
            // connection closes -- tear down the UDP relay socket too.
            if (udpEndpoint != null) {
                udpEndpoint.close();
            }
        }
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (state == State.TUNNEL_ESTABLISHED) {
            innerHandler.securityEstablished(info);
        }
    }

    @Override
    public void error(Exception cause) {
        if (state == State.TUNNEL_ESTABLISHED) {
            innerHandler.error(cause);
        } else if (state == State.UDP_ASSOCIATED) {
            udpAssociateListener.error(cause);
        } else {
            LOGGER.log(Level.WARNING,
                    L10N.getString("log.client_handshake_error"), cause);
            reportError(new IOException(
                    L10N.getString("err.client_handshake_failed"), cause));
        }
    }

    /**
     * Reports a handshake failure to whichever listener is active for
     * this handler's command -- {@code innerHandler} for CONNECT/BIND,
     * {@code udpAssociateListener} for UDP ASSOCIATE.
     */
    private void reportError(Exception cause) {
        if (command == SOCKS5_CMD_UDP_ASSOCIATE) {
            udpAssociateListener.error(cause);
        } else {
            innerHandler.error(cause);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS4/4a connect
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sends a SOCKS4/4a CONNECT or BIND request, depending on this
     * handler's command. SOCKS4 protocol: VER(0x04) + CD + DSTPORT(2) +
     * DSTIP(4) + USERID + NULL. SOCKS4a: if destination is a hostname
     * or IPv6, uses magic IP 0.0.0.1 and appends hostname after userid
     * NULL terminator.
     */
    private void sendSOCKS4Request() {
        byte cmd = (command == SOCKS5_CMD_BIND) ? SOCKS4_CMD_BIND : SOCKS4_CMD_CONNECT;
        state = (command == SOCKS5_CMD_BIND)
                ? State.AWAITING_BIND_REPLY1_V4 : State.AWAITING_CONNECT_REPLY_V4;

        InetAddress addr = null;
        try {
            addr = InetAddress.getByName(destHost);
        } catch (UnknownHostException e) {
            // Will use SOCKS4a
        }

        boolean useSocks4a = (addr == null) ||
                !(addr instanceof Inet4Address);

        byte[] userid = config.getUsername() != null
                ? config.getUsername().getBytes(StandardCharsets.ISO_8859_1)
                : new byte[0];

        if (useSocks4a) {
            byte[] hostBytes = destHost.getBytes(StandardCharsets.ISO_8859_1);
            ByteBuffer buf = ByteBuffer.allocate(
                    9 + userid.length + 1 + hostBytes.length);
            buf.put(SOCKS4_VERSION);
            buf.put(cmd);
            buf.putShort((short) destPort);
            buf.put((byte) 0); buf.put((byte) 0);
            buf.put((byte) 0); buf.put((byte) 1); // SOCKS4a: magic IP 0.0.0.x (x!=0) triggers server-side DNS
            buf.put(userid);
            buf.put((byte) 0); // null terminator
            buf.put(hostBytes);
            buf.put((byte) 0); // null terminator
            buf.flip();
            endpoint.send(buf);
        } else {
            ByteBuffer buf = ByteBuffer.allocate(9 + userid.length);
            buf.put(SOCKS4_VERSION);
            buf.put(cmd);
            buf.putShort((short) destPort);
            buf.put(addr.getAddress());
            buf.put(userid);
            buf.put((byte) 0); // null terminator
            buf.flip();
            endpoint.send(buf);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 method negotiation
    // ═══════════════════════════════════════════════════════════════════

    /**
     * RFC 1928 §3: client greeting — VER(0x05) + NMETHODS + METHODS.
     */
    private void sendSOCKS5MethodRequest() {
        state = State.AWAITING_METHOD_SELECTION;

        if (config.hasCredentials()) {
            ByteBuffer buf = ByteBuffer.allocate(4);
            buf.put(SOCKS5_VERSION);
            buf.put((byte) 2); // 2 methods
            buf.put(SOCKS5_AUTH_NONE);       // RFC 1928 §3: 0x00 = no auth
            buf.put(SOCKS5_AUTH_USERNAME_PASSWORD);  // RFC 1928 §3: 0x02 = username/password
            buf.flip();
            endpoint.send(buf);
        } else {
            ByteBuffer buf = ByteBuffer.allocate(3);
            buf.put(SOCKS5_VERSION);
            buf.put((byte) 1); // 1 method
            buf.put(SOCKS5_AUTH_NONE);  // RFC 1928 §3: 0x00 = no auth
            buf.flip();
            endpoint.send(buf);
        }
    }

    /**
     * RFC 1928 §3: server responds with VER + METHOD. METHOD=0xFF means no
     * acceptable methods.
     */
    private void handleMethodSelection(ByteBuffer data) {
        if (data.remaining() < 2) {
            return;
        }
        data.get(); // version
        byte method = data.get();

        if (method == SOCKS5_AUTH_NO_ACCEPTABLE) {
            reportError(new IOException(
                    L10N.getString("err.client_no_acceptable_auth")));
            endpoint.close();
            return;
        }

        if (method == SOCKS5_AUTH_USERNAME_PASSWORD) {
            sendUsernamePassword();
        } else if (method == SOCKS5_AUTH_NONE) {
            sendSOCKS5CommandRequest();
        } else {
            reportError(new IOException(MessageFormat.format(
                    L10N.getString("err.client_unsupported_auth"),
                    method & 0xFF)));
            endpoint.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 username/password (RFC 1929)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * RFC 1929 §2: username/password sub-negotiation — VER(0x01) + ULEN +
     * UNAME + PLEN + PASSWD.
     */
    private void sendUsernamePassword() {
        state = State.AWAITING_AUTH_RESPONSE;

        byte[] uBytes = config.getUsername()
                .getBytes(StandardCharsets.UTF_8);
        byte[] pBytes = config.getPassword()
                .getBytes(StandardCharsets.UTF_8);

        ByteBuffer buf = ByteBuffer.allocate(
                3 + uBytes.length + pBytes.length);
        buf.put(SOCKS5_AUTH_USERPASS_VERSION);
        buf.put((byte) uBytes.length);
        buf.put(uBytes);
        buf.put((byte) pBytes.length);
        buf.put(pBytes);
        buf.flip();
        endpoint.send(buf);
    }

    /**
     * RFC 1929 §2: server responds with VER(0x01) + STATUS. STATUS=0x00 is
     * success.
     */
    private void handleAuthResponse(ByteBuffer data) {
        if (data.remaining() < 2) {
            return;
        }
        data.get(); // version
        byte status = data.get();

        if (status != SOCKS5_AUTH_USERPASS_SUCCESS) {
            reportError(new IOException(
                    L10N.getString("err.client_auth_failed")));
            endpoint.close();
            return;
        }
        sendSOCKS5CommandRequest();
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 CONNECT / BIND / UDP ASSOCIATE requests
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Dispatches to the SOCKS5 request for this handler's command, once
     * method negotiation (and auth, if any) has succeeded.
     */
    private void sendSOCKS5CommandRequest() {
        switch (command) {
            case SOCKS5_CMD_BIND:
                sendSOCKS5Request(SOCKS5_CMD_BIND, State.AWAITING_BIND_REPLY1_V5);
                break;
            case SOCKS5_CMD_UDP_ASSOCIATE:
                sendSOCKS5UDPAssociateRequest();
                break;
            default:
                sendSOCKS5Request(SOCKS5_CMD_CONNECT, State.AWAITING_CONNECT_REPLY_V5);
                break;
        }
    }

    /**
     * RFC 1928 §4: CONNECT or BIND request — VER(0x05) + CMD + RSV(0x00) +
     * ATYP + DST.ADDR + DST.PORT.
     */
    private void sendSOCKS5Request(byte cmd, State nextState) {
        state = nextState;

        InetAddress addr = null;
        try {
            addr = InetAddress.getByName(destHost);
        } catch (UnknownHostException e) {
            // Use domain name
        }

        ByteBuffer buf;
        if (addr instanceof Inet4Address) {
            buf = ByteBuffer.allocate(10);
            buf.put(SOCKS5_VERSION);
            buf.put(cmd);
            buf.put((byte) 0x00); // reserved
            buf.put(SOCKS5_ATYP_IPV4);  // RFC 1928 §5: IPv4
            buf.put(addr.getAddress());
            buf.putShort((short) destPort);
        } else if (addr instanceof Inet6Address) {
            buf = ByteBuffer.allocate(22);
            buf.put(SOCKS5_VERSION);
            buf.put(cmd);
            buf.put((byte) 0x00);
            buf.put(SOCKS5_ATYP_IPV6);  // RFC 1928 §5: IPv6
            buf.put(addr.getAddress());
            buf.putShort((short) destPort);
        } else {
            byte[] hostBytes =
                    destHost.getBytes(StandardCharsets.US_ASCII);
            buf = ByteBuffer.allocate(7 + hostBytes.length);
            buf.put(SOCKS5_VERSION);
            buf.put(cmd);
            buf.put((byte) 0x00);
            buf.put(SOCKS5_ATYP_DOMAINNAME);  // RFC 1928 §5: DOMAINNAME — length-prefixed FQDN
            buf.put((byte) hostBytes.length);
            buf.put(hostBytes);
            buf.putShort((short) destPort);
        }
        buf.flip();
        endpoint.send(buf);
    }

    /**
     * RFC 1928 §7: UDP ASSOCIATE request — VER(0x05) + CMD(0x03) + RSV(0x00)
     * + ATYP + DST.ADDR + DST.PORT, where DST.ADDR/DST.PORT are the address
     * the client expects to send UDP datagrams from. That is not yet known
     * here (the local UDP socket to the relay is only opened once this
     * request's reply reports the relay's address), so a wildcard
     * 0.0.0.0:0 is sent, as is common practice: RFC 1928 does not require
     * the field to be accurate, and the relay identifies the client's
     * datagrams by source address regardless.
     */
    private void sendSOCKS5UDPAssociateRequest() {
        state = State.AWAITING_UDP_ASSOCIATE_REPLY;

        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.put(SOCKS5_VERSION);
        buf.put(SOCKS5_CMD_UDP_ASSOCIATE);
        buf.put((byte) 0x00); // reserved
        buf.put(SOCKS5_ATYP_IPV4);
        buf.put(new byte[4]); // 0.0.0.0
        buf.putShort((short) 0);
        buf.flip();
        endpoint.send(buf);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Reply handling
    // ═══════════════════════════════════════════════════════════════════

    /**
     * SOCKS4 protocol §Reply: VN(0x00) + CD + DSTPORT(2) + DSTIP(4).
     * CD=0x5a is granted. Also used for BIND's second reply, which has
     * the identical wire shape and the identical action on success
     * (hand off to the inner handler) and failure.
     */
    private void handleSOCKS4Reply(ByteBuffer data) {
        if (data.remaining() < 8) {
            return;
        }
        data.get(); // null byte
        byte reply = data.get();
        data.position(data.position() + 6); // skip port + addr: not needed here

        if (reply == SOCKS4_REPLY_GRANTED) {
            tunnelEstablished();
        } else {
            reportError(new IOException(MessageFormat.format(
                    L10N.getString("err.client_socks4_rejected"),
                    Integer.toHexString(reply & 0xFF))));
            endpoint.close();
        }
    }

    /**
     * SOCKS4 BIND, first reply: VN(0x00) + CD + DSTPORT(2) + DSTIP(4),
     * reporting the address the proxy is now listening on.
     */
    private void handleBindReply1V4(ByteBuffer data) {
        if (data.remaining() < 8) {
            return;
        }
        data.get(); // null byte
        byte reply = data.get();
        byte[] portBytes = new byte[2];
        data.get(portBytes);
        byte[] addrBytes = new byte[4];
        data.get(addrBytes);

        if (reply != SOCKS4_REPLY_GRANTED) {
            reportError(new IOException(MessageFormat.format(
                    L10N.getString("err.client_socks4_rejected"),
                    Integer.toHexString(reply & 0xFF))));
            endpoint.close();
            return;
        }

        int port = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);
        InetSocketAddress boundAddress = new InetSocketAddress(toInetAddress(addrBytes), port);
        state = State.AWAITING_BIND_REPLY2_V4;
        logBound(boundAddress);
        bindListener.bound(boundAddress);
    }

    /**
     * RFC 1928 §6: reply format — VER + REP + RSV + ATYP + BND.ADDR +
     * BND.PORT. REP=0x00 is succeeded. Also used for BIND's second
     * reply, which has the identical wire shape and the identical
     * action on success (hand off to the inner handler) and failure.
     */
    private void handleSOCKS5Reply(ByteBuffer data) {
        // Minimum: VER(1) + REP(1) + RSV(1) + ATYP(1) + ADDR(var) + PORT(2)
        if (data.remaining() < 4) {
            return;
        }
        int startPos = data.position();
        data.get(); // version
        byte reply = data.get();
        data.get(); // reserved
        byte atyp = data.get();

        int addrLen;
        switch (atyp) {
            case SOCKS5_ATYP_IPV4:  // RFC 1928 §5: IPv4 = 4 octets
                addrLen = 4;
                break;
            case SOCKS5_ATYP_IPV6:  // RFC 1928 §5: IPv6 = 16 octets
                addrLen = 16;
                break;
            case SOCKS5_ATYP_DOMAINNAME:  // RFC 1928 §5: DOMAINNAME = 1-octet length + name
                if (!data.hasRemaining()) {
                    data.position(startPos);
                    return;
                }
                addrLen = (data.get() & 0xFF) + 1;
                data.position(data.position() - 1);
                break;
            default:
                reportError(new IOException(MessageFormat.format(
                        L10N.getString("err.client_unknown_atyp"),
                        atyp & 0xFF)));
                endpoint.close();
                return;
        }

        if (data.remaining() < addrLen + 2) {
            data.position(startPos);
            return;
        }
        data.position(data.position() + addrLen + 2); // skip BND.ADDR + BND.PORT: not needed here

        if (reply == SOCKS5_REPLY_SUCCEEDED) {
            tunnelEstablished();
        } else {
            reportError(new IOException(MessageFormat.format(
                    L10N.getString("err.client_socks5_rejected"),
                    Integer.toHexString(reply & 0xFF))));
            endpoint.close();
        }
    }

    /**
     * RFC 1928 §4, SOCKS5 BIND, first reply: same wire shape as {@link
     * #handleSOCKS5Reply}, but the address is needed here (to report
     * the proxy's listening address) rather than discarded.
     */
    private void handleBindReply1V5(ByteBuffer data) {
        if (data.remaining() < 4) {
            return;
        }
        int startPos = data.position();
        data.get(); // version
        byte reply = data.get();
        data.get(); // reserved
        byte atyp = data.get();

        InetSocketAddress boundAddress;
        switch (atyp) {
            case SOCKS5_ATYP_IPV4: {
                if (data.remaining() < 6) {
                    data.position(startPos);
                    return;
                }
                byte[] addrBytes = new byte[4];
                data.get(addrBytes);
                int port = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);
                boundAddress = new InetSocketAddress(toInetAddress(addrBytes), port);
                break;
            }
            case SOCKS5_ATYP_IPV6: {
                if (data.remaining() < 18) {
                    data.position(startPos);
                    return;
                }
                byte[] addrBytes = new byte[16];
                data.get(addrBytes);
                int port = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);
                boundAddress = new InetSocketAddress(toInetAddress(addrBytes), port);
                break;
            }
            case SOCKS5_ATYP_DOMAINNAME: {
                if (!data.hasRemaining()) {
                    data.position(startPos);
                    return;
                }
                int nameLen = data.get() & 0xFF;
                if (data.remaining() < nameLen + 2) {
                    data.position(startPos);
                    return;
                }
                byte[] nameBytes = new byte[nameLen];
                data.get(nameBytes);
                int port = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);
                // Unresolved: reporting the hostname doesn't require
                // resolving it, and a blocking DNS lookup has no place here.
                boundAddress = InetSocketAddress.createUnresolved(
                        new String(nameBytes, StandardCharsets.US_ASCII), port);
                break;
            }
            default:
                reportError(new IOException(MessageFormat.format(
                        L10N.getString("err.client_unknown_atyp"),
                        atyp & 0xFF)));
                endpoint.close();
                return;
        }

        if (reply != SOCKS5_REPLY_SUCCEEDED) {
            reportError(new IOException(MessageFormat.format(
                    L10N.getString("err.client_socks5_rejected"),
                    Integer.toHexString(reply & 0xFF))));
            endpoint.close();
            return;
        }

        state = State.AWAITING_BIND_REPLY2_V5;
        logBound(boundAddress);
        bindListener.bound(boundAddress);
    }

    /**
     * RFC 1928 §7: UDP ASSOCIATE reply. REP=0x00 is succeeded, with
     * BND.ADDR/BND.PORT the relay's address -- open the local UDP
     * socket to it and notify {@link #udpAssociateListener}. A domain
     * name here is rejected rather than resolved: turning it into a
     * socket address would need a blocking DNS lookup, which has no
     * place in this handler.
     */
    private void handleUDPAssociateReply(ByteBuffer data) {
        if (data.remaining() < 4) {
            return;
        }
        int startPos = data.position();
        data.get(); // version
        byte reply = data.get();
        data.get(); // reserved
        byte atyp = data.get();

        InetAddress addr;
        switch (atyp) {
            case SOCKS5_ATYP_IPV4: {
                if (data.remaining() < 6) {
                    data.position(startPos);
                    return;
                }
                byte[] addrBytes = new byte[4];
                data.get(addrBytes);
                addr = toInetAddress(addrBytes);
                break;
            }
            case SOCKS5_ATYP_IPV6: {
                if (data.remaining() < 18) {
                    data.position(startPos);
                    return;
                }
                byte[] addrBytes = new byte[16];
                data.get(addrBytes);
                addr = toInetAddress(addrBytes);
                break;
            }
            case SOCKS5_ATYP_DOMAINNAME: {
                if (!data.hasRemaining()) {
                    data.position(startPos);
                    return;
                }
                int nameLen = data.get() & 0xFF;
                if (data.remaining() < nameLen + 2) {
                    data.position(startPos);
                    return;
                }
                data.position(data.position() + nameLen + 2); // skip name + port: rejected outright below
                reportError(new IOException(
                        L10N.getString("err.client_udp_relay_unresolved")));
                endpoint.close();
                return;
            }
            default:
                reportError(new IOException(MessageFormat.format(
                        L10N.getString("err.client_unknown_atyp"),
                        atyp & 0xFF)));
                endpoint.close();
                return;
        }

        if (data.remaining() < 2) {
            data.position(startPos);
            return;
        }
        int port = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);

        if (reply != SOCKS5_REPLY_SUCCEEDED) {
            reportError(new IOException(MessageFormat.format(
                    L10N.getString("err.client_socks5_rejected"),
                    Integer.toHexString(reply & 0xFF))));
            endpoint.close();
            return;
        }

        InetSocketAddress relayAddress = new InetSocketAddress(addr, port);
        state = State.UDP_ASSOCIATED;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.client_udp_associated"), relayAddress));
        }
        try {
            udpTransportFactory.connect(addr, port,
                    new UDPRelayHandler(relayAddress), endpoint.getSelectorLoop());
        } catch (IOException e) {
            udpAssociateListener.error(e);
            endpoint.close();
        }
    }

    /**
     * Converts a raw address (4 or 16 bytes) to an {@link InetAddress}.
     */
    private static InetAddress toInetAddress(byte[] addrBytes) {
        try {
            return InetAddress.getByAddress(addrBytes);
        } catch (UnknownHostException e) {
            return null; // only thrown for the wrong array length, which never happens here
        }
    }

    private void logBound(InetSocketAddress boundAddress) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.client_bind_bound"), boundAddress));
        }
    }

    private void tunnelEstablished() {
        state = State.TUNNEL_ESTABLISHED;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.client_tunnel_established"),
                    destHost, destPort));
        }
        innerHandler.connected(endpoint);
    }

    // ═══════════════════════════════════════════════════════════════════
    // UDP ASSOCIATE relay
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sends a datagram to {@code destination} through the established
     * UDP ASSOCIATE relay, wrapped in the RFC 1928 §7 header.
     *
     * @param destination the ultimate destination for the datagram
     * @param payload the datagram payload
     * @throws IllegalStateException if the association isn't established yet
     */
    public void sendDatagram(InetSocketAddress destination, ByteBuffer payload) {
        if (state != State.UDP_ASSOCIATED || udpEndpoint == null) {
            throw new IllegalStateException("UDP association not yet established");
        }
        ByteBuffer encoded = SOCKSUDPHeader.encode(destination, payload);
        try {
            udpEndpoint.send(encoded);
        } finally {
            ByteBufferPool.release(encoded);
        }
    }

    /**
     * Receives datagrams from the UDP relay socket connected to the
     * proxy's relay address, unwraps the RFC 1928 §7 header, and
     * forwards the payload to {@link #udpAssociateListener}.
     */
    private final class UDPRelayHandler implements ProtocolHandler {

        private final InetSocketAddress relayAddress;

        UDPRelayHandler(InetSocketAddress relayAddress) {
            this.relayAddress = relayAddress;
        }

        @Override
        public void connected(Endpoint endpoint) {
            udpEndpoint = endpoint;
            udpAssociateListener.associated(relayAddress);
        }

        @Override
        public void receive(ByteBuffer data) {
            SOCKSUDPHeader.parse(data, new SOCKSUDPHeader.Handler() {
                @Override
                public void datagram(byte frag, InetAddress address,
                        String hostname, int port, ByteBuffer payload) {
                    if (frag != SOCKS5_UDP_FRAG_STANDALONE) {
                        // RFC 1928 §7: fragmentation isn't supported.
                        LOGGER.fine(MessageFormat.format(
                                L10N.getString("log.udp_fragment_dropped"), frag));
                        return;
                    }
                    if (address == null) {
                        return; // hostname source in the relay's own header: not expected in practice
                    }
                    udpAssociateListener.receive(new InetSocketAddress(address, port), payload);
                }
            });
        }

        @Override
        public void disconnected() {
            udpAssociateListener.error(new IOException(
                    L10N.getString("err.client_udp_relay_closed")));
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
            udpAssociateListener.error(cause);
        }
    }

}
