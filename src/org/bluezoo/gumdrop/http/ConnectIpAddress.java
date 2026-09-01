/*
 * ConnectIpAddress.java
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

package org.bluezoo.gumdrop.http;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.quic.packet.VarInt;

/**
 * One {@code Assigned Address}/{@code Requested Address} record (RFC
 * 9484 section 4.7): a Request ID, an IP address, and a prefix length --
 * the shared shape of the {@link #TYPE_ADDRESS_ASSIGN} and {@link
 * #TYPE_ADDRESS_REQUEST} capsules' Capsule Value, each of which carries
 * zero or more (one or more, for {@code ADDRESS_REQUEST}) of these
 * records back to back.
 *
 * <pre>{@code
 * Address {
 *   Request ID (i),
 *   IP Version (8),
 *   IP Address (32..128),
 *   IP Prefix Length (8),
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484#section-4.7">RFC 9484 section 4.7</a>
 */
public final class ConnectIpAddress {

    /** {@code ADDRESS_ASSIGN} capsule type (RFC 9484 section 4.7.1): server to client. */
    public static final long TYPE_ADDRESS_ASSIGN = 0x01;

    /** {@code ADDRESS_REQUEST} capsule type (RFC 9484 section 4.7.2): client to server. */
    public static final long TYPE_ADDRESS_REQUEST = 0x02;

    private final long requestId;
    private final InetAddress address;
    private final int prefixLength;

    /**
     * @param requestId the request identifier (RFC 9484 section 4.7:
     *        non-zero and unique within the request, for {@code
     *        ADDRESS_REQUEST}; matches the request being answered, or 0
     *        for a server-initiated assignment, for {@code ADDRESS_ASSIGN})
     * @param address the assigned/requested IPv4 or IPv6 address
     * @param prefixLength the prefix length; must fit the address family
     *        ({@code [0, 32]} for IPv4, {@code [0, 128]} for IPv6)
     */
    public ConnectIpAddress(long requestId, InetAddress address, int prefixLength) {
        if (address == null) {
            throw new IllegalArgumentException("address must not be null");
        }
        int maxPrefix = address instanceof Inet6Address ? 128 : 32;
        if (prefixLength < 0 || prefixLength > maxPrefix) {
            throw new IllegalArgumentException("prefixLength out of range: " + prefixLength);
        }
        this.requestId = requestId;
        this.address = address;
        this.prefixLength = prefixLength;
    }

    /**
     * Returns the request identifier.
     *
     * @return the request ID
     */
    public long getRequestId() {
        return requestId;
    }

    /**
     * Returns the address.
     *
     * @return the IPv4 or IPv6 address
     */
    public InetAddress getAddress() {
        return address;
    }

    /**
     * Returns the prefix length.
     *
     * @return the prefix length
     */
    public int getPrefixLength() {
        return prefixLength;
    }

    /**
     * Decodes a list of {@code Address} records from a {@code
     * ADDRESS_ASSIGN}/{@code ADDRESS_REQUEST} Capsule Value.
     *
     * @param value the capsule value; consumed
     * @return the decoded records, or {@code null} if malformed
     */
    public static List<ConnectIpAddress> decodeList(ByteBuffer value) {
        List<ConnectIpAddress> result = new ArrayList<ConnectIpAddress>();
        try {
            while (value.hasRemaining()) {
                long requestId = VarInt.decode(value);
                if (!value.hasRemaining()) {
                    return null;
                }
                int ipVersion = value.get() & 0xff;
                int addressLength;
                if (ipVersion == 4) {
                    addressLength = 4;
                } else if (ipVersion == 6) {
                    addressLength = 16;
                } else {
                    return null;
                }
                if (value.remaining() < addressLength + 1) {
                    return null;
                }
                byte[] addressBytes = new byte[addressLength];
                value.get(addressBytes);
                int prefixLength = value.get() & 0xff;
                InetAddress address;
                try {
                    address = InetAddress.getByAddress(addressBytes);
                } catch (UnknownHostException e) {
                    return null; // unreachable: addressBytes is always 4 or 16 bytes
                }
                int maxPrefix = ipVersion == 6 ? 128 : 32;
                if (prefixLength > maxPrefix) {
                    return null;
                }
                result.add(new ConnectIpAddress(requestId, address, prefixLength));
            }
        } catch (RuntimeException e) {
            // Malformed varint or truncated record.
            return null;
        }
        return result;
    }

    /**
     * Encodes a list of {@code Address} records into an {@code
     * ADDRESS_ASSIGN}/{@code ADDRESS_REQUEST} Capsule Value.
     *
     * @param entries the records to encode
     * @return the encoded capsule value
     */
    public static ByteBuffer encodeList(List<ConnectIpAddress> entries) {
        int length = 0;
        for (int i = 0; i < entries.size(); i++) {
            ConnectIpAddress entry = entries.get(i);
            length += VarInt.encodedLength(entry.requestId) + 1 + entry.address.getAddress().length + 1;
        }
        ByteBuffer out = ByteBuffer.allocate(length);
        for (int i = 0; i < entries.size(); i++) {
            ConnectIpAddress entry = entries.get(i);
            VarInt.encode(entry.requestId, out);
            byte[] addressBytes = entry.address.getAddress();
            out.put((byte) (addressBytes.length == 16 ? 6 : 4));
            out.put(addressBytes);
            out.put((byte) entry.prefixLength);
        }
        out.flip();
        return out;
    }
}
