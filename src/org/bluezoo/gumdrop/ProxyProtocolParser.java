/*
 * ProxyProtocolParser.java
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

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Push parser for the HAProxy PROXY protocol, versions 1 and 2.
 *
 * <p>Bytes are offered through {@link #receive(ByteBuffer)} in chunks of any
 * size. Only the bytes belonging to the header are consumed from the buffer;
 * once {@code receive} returns true the buffer position is at the first byte
 * of application data (for example a TLS ClientHello), which is never
 * touched. While more input is needed every offered byte is consumed.
 *
 * <p>Parsing fails closed: a first byte that cannot begin a header, a
 * malformed header, an over-long v1 line or a truncated TLV raises an
 * {@link IOException}. Only the first header is consumed, so a second
 * header following it is application data.
 *
 * <p>For v1 {@code UNKNOWN}, v2 {@code LOCAL}, v2 {@code AF_UNSPEC},
 * v2 {@code AF_UNIX} and non-stream transports the header is consumed and
 * {@link #getSource()} and {@link #getDestination()} return null, meaning
 * the connection's own socket addresses stand.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ProxyProtocolParser {

    private static final byte[] V2_SIGNATURE = {
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };
    private static final byte[] V1_PREFIX = {
        'P', 'R', 'O', 'X', 'Y', ' '
    };

    /** Maximum v1 line length including the CRLF (protocol specification). */
    static final int V1_MAX_LENGTH = 107;
    private static final int V2_FIXED_LENGTH = 16;

    private enum State { START, V1_LINE, V2_FIXED, V2_BODY, DONE }

    private State state = State.START;
    private final byte[] scratch = new byte[V1_MAX_LENGTH];
    private int count;
    private byte[] body;
    private int bodyCount;
    private int verCmd;
    private int famProto;

    private InetSocketAddress source;
    private InetSocketAddress destination;
    private ProxyTlvs tlvs;

    /**
     * Consumes header bytes from the buffer.
     *
     * @param buf the buffer, in read mode
     * @return true once the complete header has been consumed
     * @throws IOException if the input is not a valid PROXY header
     */
    boolean receive(ByteBuffer buf) throws IOException {
        while (state != State.DONE && buf.hasRemaining()) {
            switch (state) {
            case START:
                start(buf.get());
                break;
            case V1_LINE:
                v1Byte(buf.get());
                break;
            case V2_FIXED:
                v2FixedByte(buf.get());
                break;
            case V2_BODY:
                int n = Math.min(buf.remaining(), body.length - bodyCount);
                buf.get(body, bodyCount, n);
                bodyCount += n;
                if (bodyCount == body.length) {
                    v2Body();
                    state = State.DONE;
                }
                break;
            default:
                break;
            }
        }
        return state == State.DONE;
    }

    /** Returns the asserted client address, or null if none was conveyed. */
    InetSocketAddress getSource() {
        return source;
    }

    /** Returns the asserted destination address, or null if none was conveyed. */
    InetSocketAddress getDestination() {
        return destination;
    }

    /** Returns the v2 TLVs, or null if there were none. */
    ProxyTlvs getTlvs() {
        return tlvs;
    }

    // -- header detection --

    private void start(byte b) throws IOException {
        scratch[0] = b;
        count = 1;
        if (b == V1_PREFIX[0]) {
            state = State.V1_LINE;
        } else if (b == V2_SIGNATURE[0]) {
            state = State.V2_FIXED;
        } else {
            throw new IOException("Not a PROXY protocol header");
        }
    }

    // -- version 1 --

    private void v1Byte(byte b) throws IOException {
        if (count < V1_PREFIX.length && b != V1_PREFIX[count]) {
            throw new IOException("Not a PROXY protocol v1 header");
        }
        if (b == '\n') {
            if (scratch[count - 1] != '\r') {
                throw new IOException("PROXY v1 line not terminated by CRLF");
            }
            v1Line(count - 1);
            state = State.DONE;
            return;
        }
        if (count == V1_MAX_LENGTH - 1) {
            // Only the LF could still fit.
            throw new IOException("PROXY v1 line too long");
        }
        scratch[count++] = b;
    }

    private void v1Line(int length) throws IOException {
        String line = new String(scratch, 0, length, StandardCharsets.ISO_8859_1);
        String[] t = split(line, ' ');
        if (t.length < 2) {
            throw new IOException("Malformed PROXY v1 header");
        }
        if ("UNKNOWN".equals(t[1])) {
            return;
        }
        boolean v4 = "TCP4".equals(t[1]);
        if (!v4 && !"TCP6".equals(t[1])) {
            throw new IOException("Unsupported PROXY v1 protocol: " + t[1]);
        }
        if (t.length != 6) {
            throw new IOException("Malformed PROXY v1 header");
        }
        InetAddress src = literal(t[2], v4);
        InetAddress dst = literal(t[3], v4);
        source = new InetSocketAddress(src, port(t[4]));
        destination = new InetSocketAddress(dst, port(t[5]));
    }

    private static String[] split(String line, char sep) {
        int parts = 1;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == sep) {
                parts++;
            }
        }
        String[] out = new String[parts];
        int n = 0;
        int start = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == sep) {
                out[n++] = line.substring(start, i);
                start = i + 1;
            }
        }
        return out;
    }

    /** Parses a numeric address literal; never performs a name lookup. */
    private static InetAddress literal(String s, boolean v4) throws IOException {
        try {
            if (v4) {
                String[] p = split(s, '.');
                if (p.length != 4) {
                    throw new IOException("Bad PROXY v1 IPv4 address");
                }
                byte[] a = new byte[4];
                for (int i = 0; i < 4; i++) {
                    a[i] = (byte) decimal(p[i], 3, 255);
                }
                return InetAddress.getByAddress(a);
            }
            if (s.indexOf(':') < 0 || s.length() > 45) {
                throw new IOException("Bad PROXY v1 IPv6 address");
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean ok = c == ':' || c == '.'
                        || (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if (!ok) {
                    throw new IOException("Bad PROXY v1 IPv6 address");
                }
            }
            // Contains ':' and only address characters: parsed as a literal.
            return InetAddress.getByName(s);
        } catch (java.net.UnknownHostException e) {
            throw new IOException("Bad PROXY v1 address", e);
        }
    }

    private static int port(String s) throws IOException {
        return decimal(s, 5, 65535);
    }

    private static int decimal(String s, int maxDigits, int max)
            throws IOException {
        if (s.length() == 0 || s.length() > maxDigits) {
            throw new IOException("Bad number in PROXY v1 header");
        }
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new IOException("Bad number in PROXY v1 header");
            }
            v = v * 10 + (c - '0');
        }
        if (v > max) {
            throw new IOException("Number out of range in PROXY v1 header");
        }
        return v;
    }

    // -- version 2 --

    private void v2FixedByte(byte b) throws IOException {
        if (count < V2_SIGNATURE.length && b != V2_SIGNATURE[count]) {
            throw new IOException("Not a PROXY protocol v2 header");
        }
        scratch[count++] = b;
        if (count < V2_FIXED_LENGTH) {
            return;
        }
        verCmd = scratch[12] & 0xFF;
        famProto = scratch[13] & 0xFF;
        int length = ((scratch[14] & 0xFF) << 8) | (scratch[15] & 0xFF);
        if ((verCmd >> 4) != 2 || (verCmd & 0x0F) > 1) {
            throw new IOException("Unsupported PROXY v2 version or command");
        }
        body = new byte[length];
        bodyCount = 0;
        if (length == 0) {
            v2Body();
            state = State.DONE;
        } else {
            state = State.V2_BODY;
        }
    }

    private void v2Body() throws IOException {
        int family = famProto >> 4;
        int addrLen;
        switch (family) {
        case 0:
            addrLen = 0;
            break;
        case 1:
            addrLen = 12;
            break;
        case 2:
            addrLen = 36;
            break;
        case 3:
            addrLen = 216;
            break;
        default:
            throw new IOException("Unsupported PROXY v2 address family");
        }
        if (body.length < addrLen) {
            throw new IOException("PROXY v2 length shorter than address block");
        }
        boolean proxied = (verCmd & 0x0F) == 1
                && (famProto & 0x0F) == 1
                && (family == 1 || family == 2);
        if (proxied) {
            int n = family == 1 ? 4 : 16;
            try {
                byte[] s = new byte[n];
                byte[] d = new byte[n];
                System.arraycopy(body, 0, s, 0, n);
                System.arraycopy(body, n, d, 0, n);
                int sp = ((body[2 * n] & 0xFF) << 8) | (body[2 * n + 1] & 0xFF);
                int dp = ((body[2 * n + 2] & 0xFF) << 8) | (body[2 * n + 3] & 0xFF);
                source = new InetSocketAddress(InetAddress.getByAddress(s), sp);
                destination = new InetSocketAddress(InetAddress.getByAddress(d), dp);
            } catch (java.net.UnknownHostException e) {
                throw new IOException("Bad PROXY v2 address", e);
            }
        }
        parseTlvs(addrLen);
    }

    private void parseTlvs(int pos) throws IOException {
        while (pos < body.length) {
            if (body.length - pos < 3) {
                throw new IOException("Truncated PROXY v2 TLV header");
            }
            int type = body[pos] & 0xFF;
            int len = ((body[pos + 1] & 0xFF) << 8) | (body[pos + 2] & 0xFF);
            pos += 3;
            if (body.length - pos < len) {
                throw new IOException("Truncated PROXY v2 TLV value");
            }
            byte[] value = new byte[len];
            System.arraycopy(body, pos, value, 0, len);
            pos += len;
            if (tlvs == null) {
                tlvs = new ProxyTlvs();
            }
            tlvs.add(type, value);
        }
    }

}
