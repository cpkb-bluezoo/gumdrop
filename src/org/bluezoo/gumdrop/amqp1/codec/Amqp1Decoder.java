/*
 * Amqp1Decoder.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decodes AMQP 1.0 typed values from a {@link ByteBuffer}.
 *
 * <p>The decoder always operates on a complete value: AMQP performatives
 * and SASL frame bodies arrive whole inside a frame, and the frame parser
 * has already reassembled them, so no resumption state is needed here.
 * Message payloads, which can be arbitrarily large, are not decoded with
 * this class; they are streamed as raw bytes.
 *
 * <p>Java mapping of decoded values:
 * <table class="striped">
 * <caption>AMQP type to Java type</caption>
 * <tr><th>AMQP</th><th>Java</th></tr>
 * <tr><td>null</td><td>{@code null}</td></tr>
 * <tr><td>boolean</td><td>{@link Boolean}</td></tr>
 * <tr><td>ubyte</td><td>{@link Short}</td></tr>
 * <tr><td>ushort</td><td>{@link Integer}</td></tr>
 * <tr><td>uint</td><td>{@link Long}</td></tr>
 * <tr><td>ulong</td><td>{@link Long} (the 64-bit pattern; use
 *     {@link Long#toUnsignedString(long)} for values above 2^63)</td></tr>
 * <tr><td>byte, short, int, long</td><td>{@link Byte}, {@link Short},
 *     {@link Integer}, {@link Long}</td></tr>
 * <tr><td>float, double</td><td>{@link Float}, {@link Double}</td></tr>
 * <tr><td>decimal32/64/128</td><td>{@link Amqp1Decimal}</td></tr>
 * <tr><td>char</td><td>{@link Character} (Basic Multilingual Plane only)</td></tr>
 * <tr><td>timestamp</td><td>{@link Date}</td></tr>
 * <tr><td>uuid</td><td>{@link UUID}</td></tr>
 * <tr><td>binary</td><td>{@code byte[]}</td></tr>
 * <tr><td>string</td><td>{@link String}</td></tr>
 * <tr><td>symbol</td><td>{@link Amqp1Symbol}</td></tr>
 * <tr><td>list, array</td><td>{@link List}</td></tr>
 * <tr><td>map</td><td>{@link LinkedHashMap} (encounter order preserved)</td></tr>
 * <tr><td>described</td><td>{@link Amqp1Described}</td></tr>
 * </table>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Amqp1Decoder {

    /** Guards against stack exhaustion from maliciously nested compounds. */
    private static final int MAX_DEPTH = 32;

    private Amqp1Decoder() {
    }

    /**
     * Reads one value, advancing the buffer past it.
     *
     * @param buf a buffer positioned at the value's constructor
     * @return the decoded value (see the class documentation for the mapping)
     * @throws Amqp1ProtocolException if the value is truncated, has an
     *      unknown format code, or is nested too deeply
     */
    public static Object read(ByteBuffer buf) throws Amqp1ProtocolException {
        return read(buf, 0);
    }

    /**
     * Determines the encoded length of the value at the buffer's
     * position without decoding it or moving the position. This lets a
     * caller that receives data in chunks know how many octets to gather
     * before calling {@link #read(ByteBuffer)}.
     *
     * @param buf a buffer holding the start of a value
     * @return the number of octets the value occupies, or -1 if the
     *      buffer does not yet hold enough of it to tell
     * @throws Amqp1ProtocolException if the leading octets cannot start a value
     */
    public static long encodedLength(ByteBuffer buf) throws Amqp1ProtocolException {
        return encodedLength(buf, buf.position(), 0);
    }

    private static long encodedLength(ByteBuffer buf, int pos, int depth)
            throws Amqp1ProtocolException {
        if (depth > MAX_DEPTH) {
            throw new Amqp1ProtocolException("AMQP value nested too deeply");
        }
        if (pos >= buf.limit()) {
            return -1;
        }
        int code = buf.get(pos) & 0xFF;
        if (code == Amqp1Types.DESCRIBED) {
            long descriptor = encodedLength(buf, pos + 1, depth + 1);
            if (descriptor < 0) {
                return -1;
            }
            if (descriptor > Integer.MAX_VALUE / 2) {
                throw new Amqp1ProtocolException("AMQP descriptor too large");
            }
            long value = encodedLength(buf, pos + 1 + (int) descriptor, depth + 1);
            if (value < 0) {
                return -1;
            }
            return 1 + descriptor + value;
        }
        switch (code >>> 4) {
            case 0x4:
                return 1;
            case 0x5:
                return 2;
            case 0x6:
                return 3;
            case 0x7:
                return 5;
            case 0x8:
                return 9;
            case 0x9:
                return 17;
            case 0xA:
            case 0xC:
            case 0xE:
                if (pos + 1 >= buf.limit()) {
                    return -1;
                }
                return 2L + (buf.get(pos + 1) & 0xFF);
            case 0xB:
            case 0xD:
            case 0xF:
                if (pos + 4 >= buf.limit()) {
                    return -1;
                }
                return 5L + (buf.getInt(pos + 1) & 0xFFFFFFFFL);
            default:
                throw new Amqp1ProtocolException("Unknown AMQP format code 0x"
                        + Integer.toHexString(code));
        }
    }

    private static Object read(ByteBuffer buf, int depth) throws Amqp1ProtocolException {
        if (depth > MAX_DEPTH) {
            throw new Amqp1ProtocolException("AMQP value nested too deeply");
        }
        int code = readCode(buf);
        if (code == Amqp1Types.DESCRIBED) {
            Object descriptor = read(buf, depth + 1);
            Object value = read(buf, depth + 1);
            return new Amqp1Described(descriptor, value);
        }
        return readBody(buf, code, depth);
    }

    private static int readCode(ByteBuffer buf) throws Amqp1ProtocolException {
        need(buf, 1);
        return buf.get() & 0xFF;
    }

    private static void need(ByteBuffer buf, long n) throws Amqp1ProtocolException {
        if (buf.remaining() < n) {
            throw new Amqp1ProtocolException("Truncated AMQP value: need "
                    + n + " bytes, have " + buf.remaining());
        }
    }

    private static Object readBody(ByteBuffer buf, int code, int depth)
            throws Amqp1ProtocolException {
        switch (code) {
            case Amqp1Types.NULL:
                return null;
            case Amqp1Types.BOOLEAN_TRUE:
                return Boolean.TRUE;
            case Amqp1Types.BOOLEAN_FALSE:
                return Boolean.FALSE;
            case Amqp1Types.BOOLEAN:
                need(buf, 1);
                return buf.get() != 0 ? Boolean.TRUE : Boolean.FALSE;
            case Amqp1Types.UINT_0:
                return Long.valueOf(0L);
            case Amqp1Types.ULONG_0:
                return Long.valueOf(0L);
            case Amqp1Types.UBYTE:
                need(buf, 1);
                return Short.valueOf((short) (buf.get() & 0xFF));
            case Amqp1Types.USHORT:
                need(buf, 2);
                return Integer.valueOf(buf.getShort() & 0xFFFF);
            case Amqp1Types.UINT:
                need(buf, 4);
                return Long.valueOf(buf.getInt() & 0xFFFFFFFFL);
            case Amqp1Types.UINT_SMALL:
                need(buf, 1);
                return Long.valueOf(buf.get() & 0xFFL);
            case Amqp1Types.ULONG:
                need(buf, 8);
                return Long.valueOf(buf.getLong());
            case Amqp1Types.ULONG_SMALL:
                need(buf, 1);
                return Long.valueOf(buf.get() & 0xFFL);
            case Amqp1Types.BYTE:
                need(buf, 1);
                return Byte.valueOf(buf.get());
            case Amqp1Types.SHORT:
                need(buf, 2);
                return Short.valueOf(buf.getShort());
            case Amqp1Types.INT:
                need(buf, 4);
                return Integer.valueOf(buf.getInt());
            case Amqp1Types.INT_SMALL:
                need(buf, 1);
                return Integer.valueOf(buf.get());
            case Amqp1Types.LONG:
                need(buf, 8);
                return Long.valueOf(buf.getLong());
            case Amqp1Types.LONG_SMALL:
                need(buf, 1);
                return Long.valueOf(buf.get());
            case Amqp1Types.FLOAT:
                need(buf, 4);
                return Float.valueOf(buf.getFloat());
            case Amqp1Types.DOUBLE:
                need(buf, 8);
                return Double.valueOf(buf.getDouble());
            case Amqp1Types.DECIMAL32:
                return readDecimal(buf, 4);
            case Amqp1Types.DECIMAL64:
                return readDecimal(buf, 8);
            case Amqp1Types.DECIMAL128:
                return readDecimal(buf, 16);
            case Amqp1Types.CHAR:
                need(buf, 4);
                int cp = buf.getInt();
                if (cp < 0 || cp > 0xFFFF) {
                    throw new Amqp1ProtocolException("Unsupported char code point " + cp);
                }
                return Character.valueOf((char) cp);
            case Amqp1Types.TIMESTAMP:
                need(buf, 8);
                return new Date(buf.getLong());
            case Amqp1Types.UUID:
                need(buf, 16);
                return new UUID(buf.getLong(), buf.getLong());
            case Amqp1Types.BINARY8:
                return readBytes(buf, readSize8(buf));
            case Amqp1Types.BINARY32:
                return readBytes(buf, readSize32(buf));
            case Amqp1Types.STRING8:
                return readString(buf, readSize8(buf), StandardCharsets.UTF_8);
            case Amqp1Types.STRING32:
                return readString(buf, readSize32(buf), StandardCharsets.UTF_8);
            case Amqp1Types.SYMBOL8:
                return new Amqp1Symbol(readString(buf, readSize8(buf),
                        StandardCharsets.US_ASCII));
            case Amqp1Types.SYMBOL32:
                return new Amqp1Symbol(readString(buf, readSize32(buf),
                        StandardCharsets.US_ASCII));
            case Amqp1Types.LIST0:
                return new ArrayList<Object>(0);
            case Amqp1Types.LIST8:
                return readList(buf, readSize8(buf), 1, depth);
            case Amqp1Types.LIST32:
                return readList(buf, readSize32(buf), 4, depth);
            case Amqp1Types.MAP8:
                return readMap(buf, readSize8(buf), 1, depth);
            case Amqp1Types.MAP32:
                return readMap(buf, readSize32(buf), 4, depth);
            case Amqp1Types.ARRAY8:
                return readArray(buf, readSize8(buf), 1, depth);
            case Amqp1Types.ARRAY32:
                return readArray(buf, readSize32(buf), 4, depth);
            default:
                throw new Amqp1ProtocolException("Unknown AMQP format code 0x"
                        + Integer.toHexString(code));
        }
    }

    private static int readSize8(ByteBuffer buf) throws Amqp1ProtocolException {
        need(buf, 1);
        return buf.get() & 0xFF;
    }

    private static int readSize32(ByteBuffer buf) throws Amqp1ProtocolException {
        need(buf, 4);
        int size = buf.getInt();
        if (size < 0) {
            throw new Amqp1ProtocolException("AMQP value size too large: "
                    + (size & 0xFFFFFFFFL));
        }
        return size;
    }

    private static Amqp1Decimal readDecimal(ByteBuffer buf, int width)
            throws Amqp1ProtocolException {
        need(buf, width);
        byte[] bits = new byte[width];
        buf.get(bits);
        return new Amqp1Decimal(bits);
    }

    private static byte[] readBytes(ByteBuffer buf, int size) throws Amqp1ProtocolException {
        need(buf, size);
        byte[] b = new byte[size];
        buf.get(b);
        return b;
    }

    private static String readString(ByteBuffer buf, int size,
            java.nio.charset.Charset charset) throws Amqp1ProtocolException {
        return new String(readBytes(buf, size), charset);
    }

    /**
     * Reads the body of a list. {@code size} covers the count field and
     * the elements; the buffer is bounded to that many bytes so a
     * corrupt count cannot read into whatever follows the list.
     */
    private static List<Object> readList(ByteBuffer buf, int size, int width, int depth)
            throws Amqp1ProtocolException {
        ByteBuffer body = slice(buf, size, width);
        long count = readCount(body, width);
        // Every element takes at least one octet
        if (count > body.remaining()) {
            throw new Amqp1ProtocolException("List count " + count
                    + " exceeds available data");
        }
        List<Object> list = new ArrayList<Object>((int) count);
        for (long i = 0; i < count; i++) {
            list.add(read(body, depth + 1));
        }
        return list;
    }

    private static Map<Object, Object> readMap(ByteBuffer buf, int size, int width, int depth)
            throws Amqp1ProtocolException {
        ByteBuffer body = slice(buf, size, width);
        long count = readCount(body, width);
        if (count > body.remaining() || (count & 1) != 0) {
            throw new Amqp1ProtocolException("Invalid map element count " + count);
        }
        Map<Object, Object> map = new LinkedHashMap<Object, Object>();
        for (long i = 0; i < count; i += 2) {
            Object key = read(body, depth + 1);
            Object value = read(body, depth + 1);
            map.put(key, value);
        }
        return map;
    }

    private static List<Object> readArray(ByteBuffer buf, int size, int width, int depth)
            throws Amqp1ProtocolException {
        ByteBuffer body = slice(buf, size, width);
        long count = readCount(body, width);
        if (count > body.remaining()) {
            throw new Amqp1ProtocolException("Array count " + count
                    + " exceeds available data");
        }
        // One shared constructor, possibly described, then bare values
        int code = readCode(body);
        Object descriptor = null;
        boolean described = false;
        if (code == Amqp1Types.DESCRIBED) {
            descriptor = read(body, depth + 1);
            described = true;
            code = readCode(body);
            if (code == Amqp1Types.DESCRIBED) {
                throw new Amqp1ProtocolException("Array constructor cannot nest a described type");
            }
        }
        List<Object> list = new ArrayList<Object>((int) count);
        for (long i = 0; i < count; i++) {
            Object v = readBody(body, code, depth + 1);
            list.add(described ? new Amqp1Described(descriptor, v) : v);
        }
        return list;
    }

    /**
     * Consumes {@code size} bytes from {@code buf}, returning a view of
     * them. {@code size} must at least cover the count field of
     * {@code countWidth} octets.
     */
    private static ByteBuffer slice(ByteBuffer buf, int size, int countWidth)
            throws Amqp1ProtocolException {
        need(buf, size);
        if (size < countWidth) {
            throw new Amqp1ProtocolException("Compound size " + size
                    + " too small for its count field");
        }
        ByteBuffer body = buf.slice();
        body.limit(size);
        buf.position(buf.position() + size);
        return body;
    }

    private static long readCount(ByteBuffer body, int width) {
        if (width == 1) {
            return body.get() & 0xFFL;
        }
        return body.getInt() & 0xFFFFFFFFL;
    }
}
