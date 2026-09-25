/*
 * Amqp1Encoder.java
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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encodes AMQP 1.0 typed values into a growable buffer.
 *
 * <p>Each {@code write} method picks the most compact encoding the
 * format allows (for example a {@code uint} of 0 is written as the
 * zero-width {@code uint0} form). Unsigned types have dedicated methods
 * because Java has no unsigned primitives; {@link #writeObject(Object)}
 * handles the signed and reference types listed in {@link Amqp1Decoder}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Amqp1Encoder {

    private byte[] data;
    private int size;

    public Amqp1Encoder() {
        this(64);
    }

    public Amqp1Encoder(int initialCapacity) {
        data = new byte[Math.max(initialCapacity, 16)];
    }

    /** Number of bytes written so far. */
    public int size() {
        return size;
    }

    /** Returns the bytes written so far as a buffer ready for reading. */
    public ByteBuffer toByteBuffer() {
        return ByteBuffer.wrap(data, 0, size).slice();
    }

    public byte[] toByteArray() {
        byte[] copy = new byte[size];
        System.arraycopy(data, 0, copy, 0, size);
        return copy;
    }

    private void ensure(int extra) {
        long needed = (long) size + extra;
        if (needed > data.length) {
            long cap = Math.max(needed, (long) data.length * 2);
            if (cap > Integer.MAX_VALUE - 8) {
                cap = Integer.MAX_VALUE - 8;
            }
            if (needed > cap) {
                throw new IllegalStateException("Encoded AMQP value too large");
            }
            byte[] bigger = new byte[(int) cap];
            System.arraycopy(data, 0, bigger, 0, size);
            data = bigger;
        }
    }

    private void put(int b) {
        ensure(1);
        data[size++] = (byte) b;
    }

    private void putShort(int v) {
        ensure(2);
        data[size++] = (byte) (v >>> 8);
        data[size++] = (byte) v;
    }

    private void putInt(int v) {
        ensure(4);
        data[size++] = (byte) (v >>> 24);
        data[size++] = (byte) (v >>> 16);
        data[size++] = (byte) (v >>> 8);
        data[size++] = (byte) v;
    }

    private void putLong(long v) {
        putInt((int) (v >>> 32));
        putInt((int) v);
    }

    private void putBytes(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, data, size, len);
        size += len;
    }

    // ── primitives ──────────────────────────────────────────────────────

    public void writeNull() {
        put(Amqp1Types.NULL);
    }

    public void writeBoolean(boolean v) {
        put(v ? Amqp1Types.BOOLEAN_TRUE : Amqp1Types.BOOLEAN_FALSE);
    }

    public void writeUbyte(int v) {
        if (v < 0 || v > 0xFF) {
            throw new IllegalArgumentException("ubyte out of range: " + v);
        }
        put(Amqp1Types.UBYTE);
        put(v);
    }

    public void writeUshort(int v) {
        if (v < 0 || v > 0xFFFF) {
            throw new IllegalArgumentException("ushort out of range: " + v);
        }
        put(Amqp1Types.USHORT);
        putShort(v);
    }

    public void writeUint(long v) {
        if (v < 0 || v > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("uint out of range: " + v);
        }
        if (v == 0) {
            put(Amqp1Types.UINT_0);
        } else if (v <= 0xFF) {
            put(Amqp1Types.UINT_SMALL);
            put((int) v);
        } else {
            put(Amqp1Types.UINT);
            putInt((int) v);
        }
    }

    /** Writes a {@code ulong}; {@code v} is the unsigned 64-bit pattern. */
    public void writeUlong(long v) {
        if (v == 0) {
            put(Amqp1Types.ULONG_0);
        } else if (Long.compareUnsigned(v, 0xFFL) <= 0) {
            put(Amqp1Types.ULONG_SMALL);
            put((int) v);
        } else {
            put(Amqp1Types.ULONG);
            putLong(v);
        }
    }

    public void writeByte(byte v) {
        put(Amqp1Types.BYTE);
        put(v);
    }

    public void writeShort(short v) {
        put(Amqp1Types.SHORT);
        putShort(v);
    }

    public void writeInt(int v) {
        if (v >= -128 && v <= 127) {
            put(Amqp1Types.INT_SMALL);
            put(v);
        } else {
            put(Amqp1Types.INT);
            putInt(v);
        }
    }

    public void writeLong(long v) {
        if (v >= -128 && v <= 127) {
            put(Amqp1Types.LONG_SMALL);
            put((int) v);
        } else {
            put(Amqp1Types.LONG);
            putLong(v);
        }
    }

    public void writeFloat(float v) {
        put(Amqp1Types.FLOAT);
        putInt(Float.floatToRawIntBits(v));
    }

    public void writeDouble(double v) {
        put(Amqp1Types.DOUBLE);
        putLong(Double.doubleToRawLongBits(v));
    }

    public void writeChar(char v) {
        put(Amqp1Types.CHAR);
        putInt(v);
    }

    /** Writes a {@code timestamp} (milliseconds since the Unix epoch). */
    public void writeTimestamp(long millis) {
        put(Amqp1Types.TIMESTAMP);
        putLong(millis);
    }

    public void writeUuid(UUID v) {
        put(Amqp1Types.UUID);
        putLong(v.getMostSignificantBits());
        putLong(v.getLeastSignificantBits());
    }

    public void writeDecimal(Amqp1Decimal v) {
        byte[] bits = v.getBits();
        switch (bits.length) {
            case 4:
                put(Amqp1Types.DECIMAL32);
                break;
            case 8:
                put(Amqp1Types.DECIMAL64);
                break;
            default:
                put(Amqp1Types.DECIMAL128);
                break;
        }
        putBytes(bits, 0, bits.length);
    }

    public void writeBinary(byte[] v) {
        writeVariable(Amqp1Types.BINARY8, Amqp1Types.BINARY32, v);
    }

    public void writeString(String v) {
        writeVariable(Amqp1Types.STRING8, Amqp1Types.STRING32,
                v.getBytes(StandardCharsets.UTF_8));
    }

    public void writeSymbol(String v) {
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) > 0x7F) {
                throw new IllegalArgumentException("symbol must be ASCII: " + v);
            }
        }
        writeVariable(Amqp1Types.SYMBOL8, Amqp1Types.SYMBOL32,
                v.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeVariable(int code8, int code32, byte[] b) {
        if (b.length <= 0xFF) {
            put(code8);
            put(b.length);
        } else {
            put(code32);
            putInt(b.length);
        }
        putBytes(b, 0, b.length);
    }

    // ── compound types ──────────────────────────────────────────────────

    /**
     * Writes a symbol {@code array}. This is how AMQP represents a field
     * that may hold several symbols (capabilities, locales, SASL
     * mechanisms).
     */
    public void writeSymbolArray(List<String> symbols) {
        Amqp1Encoder body = new Amqp1Encoder();
        boolean wide = false;
        for (String s : symbols) {
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) > 0x7F) {
                    throw new IllegalArgumentException("symbol must be ASCII: " + s);
                }
            }
            if (s.length() > 0xFF) {
                wide = true;
            }
        }
        for (String s : symbols) {
            byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            if (wide) {
                body.putInt(b.length);
            } else {
                body.put(b.length);
            }
            body.putBytes(b, 0, b.length);
        }
        int elementCode = wide ? Amqp1Types.SYMBOL32 : Amqp1Types.SYMBOL8;
        // size covers count + constructor + elements
        int payload = body.size + 1;
        if (!wide && payload + 1 <= 0xFF && symbols.size() <= 0xFF) {
            put(Amqp1Types.ARRAY8);
            put(payload + 1);
            put(symbols.size());
        } else {
            put(Amqp1Types.ARRAY32);
            putInt(payload + 4);
            putInt(symbols.size());
        }
        put(elementCode);
        putBytes(body.data, 0, body.size);
    }

    /** Writes a {@code list} whose elements are encoded with {@link #writeObject}. */
    public void writeList(List<?> list) {
        Amqp1Encoder body = new Amqp1Encoder();
        for (Object o : list) {
            body.writeObject(o);
        }
        writeCompound(Amqp1Types.LIST0, Amqp1Types.LIST8, Amqp1Types.LIST32,
                body.data, body.size, list.size());
    }

    /**
     * Writes a {@code map}. Keys and values are encoded with
     * {@link #writeObject}; iteration order is preserved on the wire.
     */
    public void writeMap(Map<?, ?> map) {
        Amqp1Encoder body = new Amqp1Encoder();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            body.writeObject(e.getKey());
            body.writeObject(e.getValue());
        }
        writeCompound(-1, Amqp1Types.MAP8, Amqp1Types.MAP32,
                body.data, body.size, map.size() * 2);
    }

    /**
     * Writes a compound value whose elements have already been encoded.
     *
     * @param code0 the empty-form code, or -1 if there is none
     */
    void writeCompound(int code0, int code8, int code32, byte[] elements,
            int elementsLength, int count) {
        if (count == 0 && code0 >= 0) {
            put(code0);
        } else if (elementsLength + 1 <= 0xFF && count <= 0xFF) {
            put(code8);
            put(elementsLength + 1);
            put(count);
            putBytes(elements, 0, elementsLength);
        } else {
            put(code32);
            putInt(elementsLength + 4);
            putInt(count);
            putBytes(elements, 0, elementsLength);
        }
    }

    /** Writes the prefix of a described value with a {@code ulong} descriptor. */
    public void writeDescriptor(long descriptor) {
        put(Amqp1Types.DESCRIBED);
        writeUlong(descriptor);
    }

    /** Appends already-encoded bytes verbatim. */
    void writeRaw(byte[] b, int off, int len) {
        putBytes(b, off, len);
    }

    /**
     * Writes any value of a type listed in {@link Amqp1Decoder}. Signed
     * integers are written in their own width; there is no way to
     * express an unsigned type here, use the dedicated methods.
     *
     * @throws IllegalArgumentException for an unsupported Java type
     */
    public void writeObject(Object o) {
        if (o == null) {
            writeNull();
        } else if (o instanceof Boolean) {
            writeBoolean(((Boolean) o).booleanValue());
        } else if (o instanceof Byte) {
            writeByte(((Byte) o).byteValue());
        } else if (o instanceof Short) {
            writeShort(((Short) o).shortValue());
        } else if (o instanceof Integer) {
            writeInt(((Integer) o).intValue());
        } else if (o instanceof Long) {
            writeLong(((Long) o).longValue());
        } else if (o instanceof Float) {
            writeFloat(((Float) o).floatValue());
        } else if (o instanceof Double) {
            writeDouble(((Double) o).doubleValue());
        } else if (o instanceof Character) {
            writeChar(((Character) o).charValue());
        } else if (o instanceof Date) {
            writeTimestamp(((Date) o).getTime());
        } else if (o instanceof UUID) {
            writeUuid((UUID) o);
        } else if (o instanceof Amqp1Decimal) {
            writeDecimal((Amqp1Decimal) o);
        } else if (o instanceof byte[]) {
            writeBinary((byte[]) o);
        } else if (o instanceof String) {
            writeString((String) o);
        } else if (o instanceof Amqp1Symbol) {
            writeSymbol(((Amqp1Symbol) o).getValue());
        } else if (o instanceof List) {
            writeList((List<?>) o);
        } else if (o instanceof Map) {
            writeMap((Map<?, ?>) o);
        } else if (o instanceof Amqp1Described) {
            Amqp1Described d = (Amqp1Described) o;
            // Descriptors are symbols or ulongs, never signed longs
            if (d.getDescriptor() instanceof Long) {
                writeDescriptor(((Long) d.getDescriptor()).longValue());
            } else {
                put(Amqp1Types.DESCRIBED);
                writeObject(d.getDescriptor());
            }
            writeObject(d.getValue());
        } else {
            throw new IllegalArgumentException("Cannot encode "
                    + o.getClass().getName() + " as an AMQP value");
        }
    }
}
