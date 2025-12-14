/*
 * DefaultProtobufHandler.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.telemetry.protobuf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Default implementation of {@link ProtobufHandler} with no-op methods.
 *
 * <p>Extend this class and override only the methods you need. This class
 * also provides helper methods for interpreting raw wire values.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultProtobufHandler implements ProtobufHandler {

    @Override
    public void handleVarint(int fieldNumber, long value) {
        // No-op by default
    }

    @Override
    public void handleFixed64(int fieldNumber, long value) {
        // No-op by default
    }

    @Override
    public void handleFixed32(int fieldNumber, int value) {
        // No-op by default
    }

    @Override
    public void handleBytes(int fieldNumber, ByteBuffer data) {
        // No-op by default
    }

    @Override
    public boolean isMessage(int fieldNumber) {
        // Default: treat all LEN fields as bytes, not messages
        return false;
    }

    @Override
    public void startMessage(int fieldNumber) {
        // No-op by default
    }

    @Override
    public void endMessage() {
        // No-op by default
    }

    // -- Helper methods for value interpretation --

    /**
     * Interprets a varint as a boolean.
     *
     * @param value the varint value
     * @return true if non-zero
     */
    protected static boolean asBool(long value) {
        return value != 0;
    }

    /**
     * Interprets a varint as a signed 32-bit integer using zigzag decoding.
     *
     * @param value the varint value
     * @return the signed value
     */
    protected static int asSInt32(long value) {
        int n = (int) value;
        return (n >>> 1) ^ -(n & 1);
    }

    /**
     * Interprets a varint as a signed 64-bit integer using zigzag decoding.
     *
     * @param value the varint value
     * @return the signed value
     */
    protected static long asSInt64(long value) {
        return (value >>> 1) ^ -(value & 1);
    }

    /**
     * Interprets a fixed64 as a double.
     *
     * @param value the fixed64 value
     * @return the double value
     */
    protected static double asDouble(long value) {
        return Double.longBitsToDouble(value);
    }

    /**
     * Interprets a fixed32 as a float.
     *
     * @param value the fixed32 value
     * @return the float value
     */
    protected static float asFloat(int value) {
        return Float.intBitsToFloat(value);
    }

    /**
     * Interprets bytes as a UTF-8 string.
     *
     * @param data the bytes
     * @return the string
     */
    protected static String asString(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Copies bytes to a new array.
     *
     * @param data the bytes
     * @return the byte array
     */
    protected static byte[] asBytes(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return bytes;
    }
}

