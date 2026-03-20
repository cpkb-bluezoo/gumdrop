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

import static org.bluezoo.gumdrop.socks.SOCKSConstants.*;

/**
 * Composable client-side SOCKS protocol handler.
 *
 * <p>Wraps an inner {@link ProtocolHandler} and tunnels its connection
 * through a SOCKS proxy. On {@link #connected(Endpoint)}, this handler
 * initiates the SOCKS handshake (version negotiation, optional
 * authentication, CONNECT request). Once the tunnel is established,
 * it calls {@code innerHandler.connected(endpoint)} and forwards all
 * subsequent data transparently.
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
        TUNNEL_ESTABLISHED
    }

    private final String destHost;
    private final int destPort;
    private final SOCKSClientConfig config;
    private final ProtocolHandler innerHandler;

    private Endpoint endpoint;
    private State state;

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
    }

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;

        SOCKSClientConfig.Version version = config.getVersion();
        if (version == SOCKSClientConfig.Version.SOCKS4) {
            sendSOCKS4Connect();
        } else {
            sendSOCKS5MethodRequest();
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        switch (state) {
            case AWAITING_METHOD_SELECTION:
                handleMethodSelection(data);
                break;
            case AWAITING_AUTH_RESPONSE:
                handleAuthResponse(data);
                break;
            case AWAITING_CONNECT_REPLY_V4:
                handleSOCKS4Reply(data);
                break;
            case AWAITING_CONNECT_REPLY_V5:
                handleSOCKS5Reply(data);
                break;
            case TUNNEL_ESTABLISHED:
                innerHandler.receive(data);
                break;
        }
    }

    @Override
    public void disconnected() {
        if (state == State.TUNNEL_ESTABLISHED) {
            innerHandler.disconnected();
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
        } else {
            LOGGER.log(Level.WARNING,
                    L10N.getString("log.client_handshake_error"), cause);
            innerHandler.error(new IOException(
                    L10N.getString("err.client_handshake_failed"), cause));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS4/4a connect
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sends a SOCKS4/4a CONNECT request. SOCKS4 protocol: VER(0x04) + CD(0x01)
     * + DSTPORT(2) + DSTIP(4) + USERID + NULL. SOCKS4a: if destination is a
     * hostname or IPv6, uses magic IP 0.0.0.1 and appends hostname after userid
     * NULL terminator.
     */
    private void sendSOCKS4Connect() {
        state = State.AWAITING_CONNECT_REPLY_V4;

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
            buf.put(SOCKS4_CMD_CONNECT);
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
            buf.put(SOCKS4_CMD_CONNECT);
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
            innerHandler.error(new IOException(
                    L10N.getString("err.client_no_acceptable_auth")));
            endpoint.close();
            return;
        }

        if (method == SOCKS5_AUTH_USERNAME_PASSWORD) {
            sendUsernamePassword();
        } else if (method == SOCKS5_AUTH_NONE) {
            sendSOCKS5ConnectRequest();
        } else {
            innerHandler.error(new IOException(MessageFormat.format(
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
            innerHandler.error(new IOException(
                    L10N.getString("err.client_auth_failed")));
            endpoint.close();
            return;
        }
        sendSOCKS5ConnectRequest();
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 CONNECT request
    // ═══════════════════════════════════════════════════════════════════

    /**
     * RFC 1928 §4: CONNECT request — VER(0x05) + CMD(0x01) + RSV(0x00) + ATYP
     * + DST.ADDR + DST.PORT.
     */
    private void sendSOCKS5ConnectRequest() {
        state = State.AWAITING_CONNECT_REPLY_V5;

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
            buf.put(SOCKS5_CMD_CONNECT);
            buf.put((byte) 0x00); // reserved
            buf.put(SOCKS5_ATYP_IPV4);  // RFC 1928 §5: IPv4
            buf.put(addr.getAddress());
            buf.putShort((short) destPort);
        } else if (addr instanceof Inet6Address) {
            buf = ByteBuffer.allocate(22);
            buf.put(SOCKS5_VERSION);
            buf.put(SOCKS5_CMD_CONNECT);
            buf.put((byte) 0x00);
            buf.put(SOCKS5_ATYP_IPV6);  // RFC 1928 §5: IPv6
            buf.put(addr.getAddress());
            buf.putShort((short) destPort);
        } else {
            byte[] hostBytes =
                    destHost.getBytes(StandardCharsets.US_ASCII);
            buf = ByteBuffer.allocate(7 + hostBytes.length);
            buf.put(SOCKS5_VERSION);
            buf.put(SOCKS5_CMD_CONNECT);
            buf.put((byte) 0x00);
            buf.put(SOCKS5_ATYP_DOMAINNAME);  // RFC 1928 §5: DOMAINNAME — length-prefixed FQDN
            buf.put((byte) hostBytes.length);
            buf.put(hostBytes);
            buf.putShort((short) destPort);
        }
        buf.flip();
        endpoint.send(buf);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Reply handling
    // ═══════════════════════════════════════════════════════════════════

    /**
     * SOCKS4 protocol §Reply: VN(0x00) + CD + DSTPORT(2) + DSTIP(4).
     * CD=0x5a is granted.
     */
    private void handleSOCKS4Reply(ByteBuffer data) {
        if (data.remaining() < 8) {
            return;
        }
        data.get(); // null byte
        byte reply = data.get();
        data.position(data.position() + 6); // skip port + addr

        // SOCKS4 protocol: CD=0x5a granted, 0x5b rejected
        if (reply == SOCKS4_REPLY_GRANTED) {
            tunnelEstablished();
        } else {
            innerHandler.error(new IOException(MessageFormat.format(
                    L10N.getString("err.client_socks4_rejected"),
                    Integer.toHexString(reply & 0xFF))));
            endpoint.close();
        }
    }

    /**
     * RFC 1928 §6: reply format — VER + REP + RSV + ATYP + BND.ADDR +
     * BND.PORT. REP=0x00 is succeeded.
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
                innerHandler.error(new IOException(MessageFormat.format(
                        L10N.getString("err.client_unknown_atyp"),
                        atyp & 0xFF)));
                endpoint.close();
                return;
        }

        if (data.remaining() < addrLen + 2) {
            data.position(startPos);
            return;
        }
        data.position(data.position() + addrLen + 2); // skip BND.ADDR + BND.PORT

        if (reply == SOCKS5_REPLY_SUCCEEDED) {
            tunnelEstablished();
        } else {
            innerHandler.error(new IOException(MessageFormat.format(
                    L10N.getString("err.client_socks5_rejected"),
                    Integer.toHexString(reply & 0xFF))));
            endpoint.close();
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

}
