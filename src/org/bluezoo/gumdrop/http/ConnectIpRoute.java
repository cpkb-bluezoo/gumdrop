/*
 * ConnectIpRoute.java
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

/**
 * One {@code IP Address Range} record (RFC 9484 section 4.7.3): the
 * shape of the {@link #TYPE_ROUTE_ADVERTISEMENT} capsule's Capsule
 * Value, which carries zero or more of these records, ordered and
 * non-overlapping, back to back.
 *
 * <pre>{@code
 * IP Address Range {
 *   IP Version (8),
 *   Start IP Address (32..128),
 *   End IP Address (32..128),
 *   IP Protocol (8),
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484#section-4.7.3">RFC 9484 section 4.7.3</a>
 */
public final class ConnectIpRoute {

    /** {@code ROUTE_ADVERTISEMENT} capsule type (RFC 9484 section 4.7.3): server to client. */
    public static final long TYPE_ROUTE_ADVERTISEMENT = 0x03;

    /** {@link #getIpProtocol} value meaning "all IP protocols". */
    public static final int IP_PROTOCOL_ALL = 0;

    private final InetAddress startAddress;
    private final InetAddress endAddress;
    private final int ipProtocol;

    /**
     * @param startAddress the first address in the range (inclusive)
     * @param endAddress the last address in the range (inclusive); must
     *        be the same address family as {@code startAddress}
     * @param ipProtocol the advertised Internet Protocol Number in
     *        {@code [0, 255]}, or {@link #IP_PROTOCOL_ALL}
     */
    public ConnectIpRoute(InetAddress startAddress, InetAddress endAddress, int ipProtocol) {
        if (startAddress == null || endAddress == null) {
            throw new IllegalArgumentException("addresses must not be null");
        }
        if ((startAddress instanceof Inet6Address) != (endAddress instanceof Inet6Address)) {
            throw new IllegalArgumentException("start and end addresses must be the same IP version");
        }
        if (ipProtocol < 0 || ipProtocol > 255) {
            throw new IllegalArgumentException("ipProtocol out of range: " + ipProtocol);
        }
        this.startAddress = startAddress;
        this.endAddress = endAddress;
        this.ipProtocol = ipProtocol;
    }

    /**
     * Returns the first address in the range (inclusive).
     *
     * @return the start address
     */
    public InetAddress getStartAddress() {
        return startAddress;
    }

    /**
     * Returns the last address in the range (inclusive).
     *
     * @return the end address
     */
    public InetAddress getEndAddress() {
        return endAddress;
    }

    /**
     * Returns the advertised Internet Protocol Number, or {@link
     * #IP_PROTOCOL_ALL}.
     *
     * @return the IP protocol
     */
    public int getIpProtocol() {
        return ipProtocol;
    }

    /**
     * Decodes a list of {@code IP Address Range} records from a {@code
     * ROUTE_ADVERTISEMENT} Capsule Value.
     *
     * @param value the capsule value; consumed
     * @return the decoded records, or {@code null} if malformed
     */
    public static List<ConnectIpRoute> decodeList(ByteBuffer value) {
        List<ConnectIpRoute> result = new ArrayList<ConnectIpRoute>();
        while (value.hasRemaining()) {
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
            if (value.remaining() < (addressLength * 2) + 1) {
                return null;
            }
            byte[] startBytes = new byte[addressLength];
            value.get(startBytes);
            byte[] endBytes = new byte[addressLength];
            value.get(endBytes);
            int ipProtocol = value.get() & 0xff;
            InetAddress startAddress;
            InetAddress endAddress;
            try {
                startAddress = InetAddress.getByAddress(startBytes);
                endAddress = InetAddress.getByAddress(endBytes);
            } catch (UnknownHostException e) {
                return null; // unreachable: address bytes are always 4 or 16 bytes
            }
            result.add(new ConnectIpRoute(startAddress, endAddress, ipProtocol));
        }
        return result;
    }

    /**
     * Encodes a list of {@code IP Address Range} records into a {@code
     * ROUTE_ADVERTISEMENT} Capsule Value.
     *
     * @param entries the records to encode
     * @return the encoded capsule value
     */
    public static ByteBuffer encodeList(List<ConnectIpRoute> entries) {
        int length = 0;
        for (int i = 0; i < entries.size(); i++) {
            ConnectIpRoute entry = entries.get(i);
            length += 1 + entry.startAddress.getAddress().length + entry.endAddress.getAddress().length + 1;
        }
        ByteBuffer out = ByteBuffer.allocate(length);
        for (int i = 0; i < entries.size(); i++) {
            ConnectIpRoute entry = entries.get(i);
            byte[] startBytes = entry.startAddress.getAddress();
            byte[] endBytes = entry.endAddress.getAddress();
            out.put((byte) (startBytes.length == 16 ? 6 : 4));
            out.put(startBytes);
            out.put(endBytes);
            out.put((byte) entry.ipProtocol);
        }
        out.flip();
        return out;
    }
}
