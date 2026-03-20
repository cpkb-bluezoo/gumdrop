/*
 * SOCKSRequest.java
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

import java.net.InetAddress;

/**
 * A parsed SOCKS connection request from a client.
 *
 * <p>Holds the protocol version, command, destination address (as an
 * {@link InetAddress} and/or hostname), destination port, and for
 * SOCKS4/4a the userid field.
 *
 * <p>For SOCKS4a and SOCKS5 domain-name requests, {@link #getHost()}
 * returns the unresolved hostname and {@link #getAddress()} returns
 * {@code null} until the server resolves the name.
 *
 * <p>Relevant specifications:
 * <ul>
 *   <li>SOCKS4 protocol: Request format (VER, CD, DSTPORT, DSTIP, USERID)</li>
 *   <li>SOCKS4a: Hostname appended after USERID when DSTIP=0.0.0.x (x≠0)</li>
 *   <li>RFC 1928: SOCKS Protocol Version 5</li>
 *   <li>RFC 1929: Username/Password Authentication for SOCKS V5</li>
 *   <li>RFC 1961: GSS-API Authentication for SOCKS V5</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1928#section-4">RFC 1928 §4 - Requests</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/draft-ietf-aft-socks-protocol">SOCKS4 protocol</a>
 */
public final class SOCKSRequest {

    /** SOCKS4 §Request VER / RFC 1928 §4 VER */
    private final byte version;
    /** SOCKS4 §Request CD / RFC 1928 §4 CMD */
    private final byte command;
    /** SOCKS4 §Request DSTIP / RFC 1928 §4+§5 DST.ADDR (ATYP 0x01/0x04) */
    private final InetAddress address;
    /** SOCKS4a hostname (when DSTIP=0.0.0.x) / RFC 1928 §4+§5 DST.ADDR (ATYP 0x03) */
    private final String host;
    /** SOCKS4 §Request DSTPORT / RFC 1928 §4 DST.PORT */
    private final int port;
    /** SOCKS4 §Request USERID (null-terminated) */
    private final String userid;
    /** RFC 1929 §2 (username/password) or RFC 1961 §4 (GSS-API principal) */
    private final String authenticatedUser;

    /**
     * Creates a SOCKS request.
     *
     * @param version the SOCKS version (0x04 or 0x05); SOCKS4 §Request VER / RFC 1928 §4 VER
     * @param command the command byte; SOCKS4 §Request CD / RFC 1928 §4 CMD
     * @param address the resolved destination address, or null if
     *                only a hostname was provided; SOCKS4 §Request DSTIP / RFC 1928 §4+§5 DST.ADDR
     * @param host the destination hostname, or null if only an
     *             address was provided; SOCKS4a (DSTIP=0.0.0.x) / RFC 1928 §4+§5 DST.ADDR (ATYP 0x03)
     * @param port the destination port; SOCKS4 §Request DSTPORT / RFC 1928 §4 DST.PORT
     * @param userid the SOCKS4 userid field, or null for SOCKS5; SOCKS4 §Request USERID
     * @param authenticatedUser the authenticated username from SOCKS5
     *                          auth, or null if no authentication; RFC 1929 §2 / RFC 1961 §4
     */
    public SOCKSRequest(byte version, byte command,
                        InetAddress address, String host,
                        int port, String userid,
                        String authenticatedUser) {
        this.version = version;
        this.command = command;
        this.address = address;
        this.host = host;
        this.port = port;
        this.userid = userid;
        this.authenticatedUser = authenticatedUser;
    }

    /**
     * Returns the SOCKS protocol version (0x04 or 0x05).
     * SOCKS4 §Request VER field; RFC 1928 §4 VER field.
     *
     * @return the version byte
     */
    public byte getVersion() {
        return version;
    }

    /**
     * Returns the command byte.
     * SOCKS4 §Request CD field; RFC 1928 §4 CMD field.
     *
     * @return the command (CONNECT, BIND, or UDP ASSOCIATE)
     */
    public byte getCommand() {
        return command;
    }

    /**
     * Returns the resolved destination address, or {@code null} if
     * the request specified a hostname that has not yet been resolved.
     * SOCKS4 §Request DSTIP; RFC 1928 §4+§5 DST.ADDR (ATYP 0x01 IPv4, 0x04 IPv6).
     *
     * @return the destination address, or null
     */
    public InetAddress getAddress() {
        return address;
    }

    /**
     * Returns the destination hostname from a SOCKS4a or SOCKS5
     * domain-name request, or {@code null} if the request specified
     * a numeric address.
     * SOCKS4a: hostname appended after USERID when DSTIP=0.0.0.x (x≠0);
     * RFC 1928 §4+§5 DST.ADDR (ATYP 0x03 domain name).
     *
     * @return the destination hostname, or null
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the destination port.
     * SOCKS4 §Request DSTPORT; RFC 1928 §4 DST.PORT.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the SOCKS4/4a userid field, or {@code null} for SOCKS5
     * requests.
     * SOCKS4 §Request USERID (null-terminated string).
     *
     * @return the userid, or null
     */
    public String getUserid() {
        return userid;
    }

    /**
     * Returns the username authenticated during SOCKS5 authentication,
     * or {@code null} if no authentication was performed or for
     * SOCKS4 requests.
     * RFC 1929 §2 (username/password auth); RFC 1961 §4 (GSS-API principal).
     *
     * @return the authenticated username, or null
     */
    public String getAuthenticatedUser() {
        return authenticatedUser;
    }

    /**
     * Returns whether this is a SOCKS4a request (SOCKS4 with
     * server-side DNS resolution).
     * SOCKS4a: DSTIP=0.0.0.x with x≠0 signals that the hostname is
     * appended after USERID for server-side DNS resolution.
     *
     * @return true if SOCKS4a
     */
    public boolean isSOCKS4a() {
        return version == SOCKSConstants.SOCKS4_VERSION && host != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SOCKS");
        if (version == SOCKSConstants.SOCKS4_VERSION) {
            sb.append(host != null ? "4a" : "4");
        } else {
            sb.append("5");
        }
        sb.append(' ');
        // SOCKS4_CMD_BIND == SOCKS5_CMD_BIND == 0x02
        switch (command) {
            case SOCKSConstants.SOCKS5_CMD_BIND:
                sb.append("BIND");
                break;
            case SOCKSConstants.SOCKS5_CMD_UDP_ASSOCIATE:
                sb.append("UDP ASSOCIATE");
                break;
            default:
                sb.append("CONNECT");
                break;
        }
        sb.append(' ');
        if (host != null) {
            sb.append(host);
        } else if (address != null) {
            sb.append(address.getHostAddress());
        }
        sb.append(':').append(port);
        if (authenticatedUser != null) {
            sb.append(" user=").append(authenticatedUser);
        } else if (userid != null) {
            sb.append(" userid=").append(userid);
        }
        return sb.toString();
    }

}
