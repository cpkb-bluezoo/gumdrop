/*
 * SOCKSUDPHeader.java
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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.util.ByteBufferPool;

import static org.bluezoo.gumdrop.socks.SOCKSConstants.*;

/**
 * Codec for the SOCKS5 UDP request header defined in RFC 1928 §7.
 *
 * <pre>
 * +----+------+------+----------+----------+----------+
 * |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
 * +----+------+------+----------+----------+----------+
 * | 2  |  1   |  1   | Variable |    2     | Variable |
 * +----+------+------+----------+----------+----------+
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928#section-7">
 *      RFC 1928 §7</a>
 */
public final class SOCKSUDPHeader {

    private SOCKSUDPHeader() {
    }

    /**
     * Receives the semantic result of parsing one RFC 1928 §7 UDP
     * request header -- a push-style callback, so a well-formed header
     * is delivered as an event rather than a materialized value object.
     */
    public interface Handler {

        /**
         * A well-formed header was parsed. Called at most once per
         * {@link #parse(ByteBuffer, Handler) parse} call, synchronously,
         * before it returns.
         *
         * @param frag the FRAG field (RFC 1928 §7: 0x00 for a standalone datagram)
         * @param address the destination address (ATYP IPv4/IPv6), or null if a hostname was given instead
         * @param hostname the destination hostname (ATYP DOMAINNAME), or null if an address was given instead
         * @param port the destination port
         * @param payload the datagram payload, positioned at the data start; only valid for the duration of this call
         */
        void datagram(byte frag, InetAddress address, String hostname, int port, ByteBuffer payload);
    }

    /**
     * Parses the RFC 1928 §7 UDP request header from the given buffer
     * and, if well-formed, delivers it to {@code handler}.
     *
     * <p>A malformed or too-short datagram is silently dropped -- UDP
     * has no notion of a partial read to wait on the way a TCP stream
     * does, so there is nothing to retry once a single datagram fails
     * to parse.
     *
     * @param data the datagram contents (position at start)
     * @param handler receives the parsed header and payload, if any
     */
    public static void parse(ByteBuffer data, Handler handler) {
        // RSV(2) + FRAG(1) + ATYP(1) = 4 minimum before address
        if (data.remaining() < 4) {
            return;
        }
        int startPos = data.position();

        data.getShort(); // RSV — RFC 1928 §7: reserved, must be 0x0000
        byte frag = data.get();
        byte atyp = data.get();

        InetAddress address = null;
        String hostname = null;

        switch (atyp) {
            case SOCKS5_ATYP_IPV4: {
                // RFC 1928 §5: IPv4 — 4 octets
                if (data.remaining() < 6) { // 4 addr + 2 port
                    data.position(startPos);
                    return;
                }
                byte[] ipv4 = new byte[4];
                data.get(ipv4);
                try {
                    address = InetAddress.getByAddress(ipv4);
                } catch (UnknownHostException e) {
                    return;
                }
                break;
            }
            case SOCKS5_ATYP_DOMAINNAME: {
                // RFC 1928 §5: DOMAINNAME — 1-octet length + FQDN
                if (data.remaining() < 1) {
                    data.position(startPos);
                    return;
                }
                int nameLen = data.get() & 0xFF;
                if (data.remaining() < nameLen + 2) { // name + 2 port
                    data.position(startPos);
                    return;
                }
                byte[] nameBytes = new byte[nameLen];
                data.get(nameBytes);
                hostname = new String(nameBytes, StandardCharsets.US_ASCII);
                break;
            }
            case SOCKS5_ATYP_IPV6: {
                // RFC 1928 §5: IPv6 — 16 octets
                if (data.remaining() < 18) { // 16 addr + 2 port
                    data.position(startPos);
                    return;
                }
                byte[] ipv6 = new byte[16];
                data.get(ipv6);
                try {
                    address = InetAddress.getByAddress(ipv6);
                } catch (UnknownHostException e) {
                    return;
                }
                break;
            }
            default:
                return;
        }

        int port = ((data.get() & 0xFF) << 8) | (data.get() & 0xFF);
        ByteBuffer payload = data.slice();

        handler.datagram(frag, address, hostname, port, payload);
    }

    /**
     * Encodes a UDP response datagram with the RFC 1928 §7 header
     * prepended.
     *
     * <p>The source address of the remote host is encoded as the
     * DST.ADDR/DST.PORT fields so the client knows where the
     * response originated.
     *
     * @param source the remote host's address and port
     * @param payload the response payload data
     * @return a buffer containing header + payload, ready to send; the
     *         caller must release it with {@link ByteBufferPool#release}
     *         once the datagram has been sent
     */
    public static ByteBuffer encode(InetSocketAddress source, ByteBuffer payload) {
        InetAddress addr = source.getAddress();
        byte atyp;
        byte[] addrBytes;

        if (addr instanceof Inet6Address) {
            atyp = SOCKS5_ATYP_IPV6;
            addrBytes = addr.getAddress();
        } else if (addr instanceof Inet4Address) {
            atyp = SOCKS5_ATYP_IPV4;
            addrBytes = addr.getAddress();
        } else {
            atyp = SOCKS5_ATYP_IPV4;
            addrBytes = new byte[4];
        }

        // RSV(2) + FRAG(1) + ATYP(1) + ADDR(var) + PORT(2) + DATA
        int headerLen = 4 + addrBytes.length + 2;
        int payloadLen = payload.remaining();
        ByteBuffer buf = ByteBufferPool.acquire(headerLen + payloadLen);

        buf.putShort(SOCKS5_UDP_RSV);
        buf.put(SOCKS5_UDP_FRAG_STANDALONE);
        buf.put(atyp);
        buf.put(addrBytes);
        buf.putShort((short) source.getPort());
        buf.put(payload);
        buf.flip();

        return buf;
    }

}
