/*
 * RESPEncoder.java
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

package org.bluezoo.gumdrop.redis.codec;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Encodes Redis commands to RESP wire format.
 *
 * <p>Commands are encoded as RESP arrays of bulk strings. This encoder
 * provides convenience methods for building commands with various
 * argument types.
 *
 * <p>This class is thread-safe. Each encoding operation creates a new
 * buffer, so multiple threads can encode commands concurrently.
 *
 * <h4>Usage Example</h4>
 * <pre>{@code
 * RESPEncoder encoder = new RESPEncoder();
 *
 * // Simple command
 * ByteBuffer ping = encoder.encodeCommand("PING");
 *
 * // Command with string arguments
 * ByteBuffer set = encoder.encodeCommand("SET", "key", "value");
 *
 * // Command with mixed arguments
 * ByteBuffer setex = encoder.encode("SETEX", "key", 60, "value");
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RESPEncoder {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final byte[] CRLF = new byte[] { '\r', '\n' };

    /**
     * Creates a new RESP encoder.
     */
    public RESPEncoder() {
    }

    /**
     * Encodes a command with no arguments.
     *
     * @param command the command name
     * @return a ByteBuffer containing the encoded command
     */
    public ByteBuffer encodeCommand(String command) {
        byte[][] parts = new byte[1][];
        parts[0] = command.getBytes(UTF_8);
        return encodeArray(parts);
    }

    /**
     * Encodes a command with string arguments.
     *
     * <p>All arguments are encoded as bulk strings using UTF-8.
     *
     * @param command the command name
     * @param args the command arguments
     * @return a ByteBuffer containing the encoded command
     */
    public ByteBuffer encodeCommand(String command, String[] args) {
        int argc = 1 + args.length;
        byte[][] parts = new byte[argc][];
        parts[0] = command.getBytes(UTF_8);
        for (int i = 0; i < args.length; i++) {
            parts[i + 1] = args[i].getBytes(UTF_8);
        }
        return encodeArray(parts);
    }

    /**
     * Encodes a command with byte array arguments.
     *
     * <p>Use this method when sending binary data.
     *
     * @param command the command name
     * @param args the command arguments as byte arrays
     * @return a ByteBuffer containing the encoded command
     */
    public ByteBuffer encodeCommand(String command, byte[][] args) {
        int argc = 1 + args.length;
        byte[][] parts = new byte[argc][];
        parts[0] = command.getBytes(UTF_8);
        for (int i = 0; i < args.length; i++) {
            parts[i + 1] = args[i];
        }
        return encodeArray(parts);
    }

    /**
     * Encodes a command with mixed argument types.
     *
     * <p>Arguments can be:
     * <ul>
     *   <li>{@code String} - encoded as UTF-8</li>
     *   <li>{@code byte[]} - used directly</li>
     *   <li>{@code Integer} or {@code Long} - converted to string</li>
     *   <li>{@code Double} - converted to string</li>
     *   <li>Other objects - converted via {@code toString()}</li>
     * </ul>
     *
     * @param command the command name
     * @param args the command arguments
     * @return a ByteBuffer containing the encoded command
     */
    public ByteBuffer encode(String command, Object... args) {
        int argc = 1 + args.length;
        byte[][] parts = new byte[argc][];
        parts[0] = command.getBytes(UTF_8);
        for (int i = 0; i < args.length; i++) {
            parts[i + 1] = toBytes(args[i]);
        }
        return encodeArray(parts);
    }

    /**
     * Converts an object to a byte array for encoding.
     */
    private byte[] toBytes(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        if (obj instanceof byte[]) {
            return (byte[]) obj;
        }
        if (obj instanceof String) {
            return ((String) obj).getBytes(UTF_8);
        }
        return obj.toString().getBytes(UTF_8);
    }

    /**
     * Encodes an array of bulk strings.
     */
    private ByteBuffer encodeArray(byte[][] parts) {
        // Calculate total size
        int size = 0;
        String countStr = Integer.toString(parts.length);
        size += 1 + countStr.length() + 2; // *count\r\n
        for (byte[] part : parts) {
            String lenStr = Integer.toString(part.length);
            size += 1 + lenStr.length() + 2; // $length\r\n
            size += part.length + 2; // data\r\n
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        // Write array header
        buffer.put((byte) '*');
        buffer.put(countStr.getBytes(UTF_8));
        buffer.put(CRLF);
        // Write each bulk string
        for (byte[] part : parts) {
            buffer.put((byte) '$');
            String lenStr = Integer.toString(part.length);
            buffer.put(lenStr.getBytes(UTF_8));
            buffer.put(CRLF);
            buffer.put(part);
            buffer.put(CRLF);
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Encodes an inline command (for simple commands without binary data).
     *
     * <p>Inline commands are space-separated and terminated with CRLF.
     * This format is mainly used for interactive sessions and should
     * be avoided in favour of the standard RESP format.
     *
     * @param command the command name
     * @param args the command arguments
     * @return a ByteBuffer containing the encoded inline command
     */
    public ByteBuffer encodeInline(String command, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append(command);
        for (String arg : args) {
            sb.append(' ');
            sb.append(arg);
        }
        sb.append("\r\n");
        byte[] bytes = sb.toString().getBytes(UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

}
