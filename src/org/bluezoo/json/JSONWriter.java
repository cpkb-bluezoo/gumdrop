/*
 * JSONWriter.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of jsonparser, a JSON parsing library for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/jsonparser/
 *
 * jsonparser is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * jsonparser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with jsonparser.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Streaming JSON writer with NIO-first design.
 * <p>
 * This class provides an efficient, streaming approach to JSON serialization
 * that writes to a {@link WritableByteChannel}. The writer uses an internal
 * buffer and automatically sends chunks to the channel when the buffer fills
 * beyond a threshold.
 * <p>
 * This class does not perform well-formedness checking on its input: the
 * user of the class is required to supply events in the correct order and
 * close objects and arrays they open. However, it will escape characters
 * supplied in string data.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Write to a file
 * FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
 * JSONWriter writer = new JSONWriter(channel);
 * 
 * writer.writeStartObject();
 * writer.writeKey("name");
 * writer.writeString("Alice");
 * writer.writeKey("age");
 * writer.writeNumber(30);
 * writer.writeEndObject();
 * writer.close();
 * 
 * // Or write to an OutputStream
 * OutputStream out = ...;
 * JSONWriter writer = new JSONWriter(Channels.newChannel(out));
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is NOT thread-safe. It is intended for use on a single thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSONWriter {

    private static final byte[] TRUE_BYTES = "true".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FALSE_BYTES = "false".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NULL_BYTES = "null".getBytes(StandardCharsets.UTF_8);

    private static final int DEFAULT_CAPACITY = 4096;
    private static final float SEND_THRESHOLD = 0.75f;

    private final WritableByteChannel channel;
    private ByteBuffer buffer;
    private final int sendThreshold;
    private final IndentConfig indentConfig;

    private enum State { INIT0, INIT, AFTER_KEY, AFTER_VALUE, IN_ARRAY, IN_OBJECT }
    private State state = State.INIT0;
    private int depth = 0;

    /**
     * Creates a new JSON writer with default capacity (4KB) and no indentation.
     *
     * @param out the output stream to write to
     */
    public JSONWriter(OutputStream out) {
        this(new OutputStreamChannel(out), DEFAULT_CAPACITY, null);
    }

    /**
     * Creates a new JSON writer with default capacity and optional indentation.
     *
     * @param out the output stream to write to
     * @param indentConfig the indentation configuration, or null for no indentation
     */
    public JSONWriter(OutputStream out, IndentConfig indentConfig) {
        this(new OutputStreamChannel(out), DEFAULT_CAPACITY, indentConfig);
    }

    /**
     * Creates a new JSON writer with default capacity (4KB) and no indentation.
     *
     * @param channel the channel to write to
     */
    public JSONWriter(WritableByteChannel channel) {
        this(channel, DEFAULT_CAPACITY, null);
    }

    /**
     * Creates a new JSON writer with specified buffer capacity and no indentation.
     *
     * @param channel the channel to write to
     * @param bufferCapacity initial buffer capacity in bytes
     */
    public JSONWriter(WritableByteChannel channel, int bufferCapacity) {
        this(channel, bufferCapacity, null);
    }

    /**
     * Creates a new JSON writer with specified buffer capacity and optional indentation.
     *
     * @param channel the channel to write to
     * @param bufferCapacity initial buffer capacity in bytes
     * @param indentConfig the indentation configuration, or null for no indentation
     */
    public JSONWriter(WritableByteChannel channel, int bufferCapacity, IndentConfig indentConfig) {
        this.channel = channel;
        this.buffer = ByteBuffer.allocate(bufferCapacity);
        this.sendThreshold = (int) (bufferCapacity * SEND_THRESHOLD);
        this.indentConfig = indentConfig;
    }

    /**
     * Writes the start of a JSON object '{'.
     * This must be matched by a corresponding {@link #writeEndObject()} call.
     *
     * @throws IOException if there is an error writing data
     */
    public void writeStartObject() throws IOException {
        if (indentConfig != null) {
            writeIndentedValueStart();
        } else {
            writeValueSeparatorIfNeeded();
        }
        ensureCapacity(1);
        buffer.put((byte) '{');
        state = State.INIT;
        depth++;
        sendIfNeeded();
    }

    /**
     * Writes the end of a JSON object '}'.
     *
     * @throws IOException if there is an error writing data
     */
    public void writeEndObject() throws IOException {
        depth--;
        if (indentConfig != null) {
            writeIndent();
        }
        ensureCapacity(1);
        buffer.put((byte) '}');
        state = State.AFTER_VALUE;
        sendIfNeeded();
    }

    /**
     * Writes the start of a JSON array '['.
     * This must be matched by a corresponding {@link #writeEndArray()} call.
     *
     * @throws IOException if there is an error writing data
     */
    public void writeStartArray() throws IOException {
        if (indentConfig != null) {
            writeIndentedValueStart();
        } else {
            writeValueSeparatorIfNeeded();
        }
        ensureCapacity(1);
        buffer.put((byte) '[');
        state = State.INIT;
        depth++;
        sendIfNeeded();
    }

    /**
     * Writes the end of a JSON array ']'.
     *
     * @throws IOException if there is an error writing data
     */
    public void writeEndArray() throws IOException {
        depth--;
        if (indentConfig != null) {
            writeIndent();
        }
        ensureCapacity(1);
        buffer.put((byte) ']');
        state = State.AFTER_VALUE;
        sendIfNeeded();
    }

    /**
     * Writes an object key (property name).
     *
     * @param key the key name
     * @throws IOException if there is an error writing data
     */
    public void writeKey(String key) throws IOException {
        if (indentConfig != null) {
            writeIndentedValueStart();
        } else {
            if (state == State.AFTER_VALUE) {
                ensureCapacity(1);
                buffer.put((byte) ',');
            }
        }
        writeQuotedString(key);
        ensureCapacity(1);
        buffer.put((byte) ':');
        state = State.AFTER_KEY;
        sendIfNeeded();
    }

    /**
     * Writes a string value.
     *
     * @param value the string value
     * @throws IOException if there is an error writing data
     */
    public void writeString(String value) throws IOException {
        if (indentConfig != null) {
            writeIndentedValueStart();
        } else {
            writeValueSeparatorIfNeeded();
        }
        writeQuotedString(value);
        state = State.AFTER_VALUE;
        sendIfNeeded();
    }

    /**
     * Writes a number value.
     *
     * @param value the number value
     * @throws IOException if there is an error writing data
     */
    public void writeNumber(Number value) throws IOException {
        if (indentConfig != null) {
            writeIndentedValueStart();
        } else {
            writeValueSeparatorIfNeeded();
        }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        ensureCapacity(bytes.length);
        buffer.put(bytes);
        state = State.AFTER_VALUE;
        sendIfNeeded();
    }

    /**
     * Writes a boolean value.
     *
     * @param value the boolean value
     * @throws IOException if there is an error writing data
     */
    public void writeBoolean(boolean value) throws IOException {
        if (indentConfig != null) {
            writeIndentedValueStart();
        } else {
            writeValueSeparatorIfNeeded();
        }
        byte[] bytes = value ? TRUE_BYTES : FALSE_BYTES;
        ensureCapacity(bytes.length);
        buffer.put(bytes);
        state = State.AFTER_VALUE;
        sendIfNeeded();
    }

    /**
     * Writes a null value.
     *
     * @throws IOException if there is an error writing data
     */
    public void writeNull() throws IOException {
        if (indentConfig != null) {
            writeIndentedValueStart();
        } else {
            writeValueSeparatorIfNeeded();
        }
        ensureCapacity(NULL_BYTES.length);
        buffer.put(NULL_BYTES);
        state = State.AFTER_VALUE;
        sendIfNeeded();
    }

    /**
     * Flushes any buffered data to the channel.
     * <p>
     * This sends any remaining data in the buffer to the channel,
     * even if the buffer is not full.
     *
     * @throws IOException if there is an error sending data
     */
    public void flush() throws IOException {
        if (buffer.position() > 0) {
            send();
        }
    }

    /**
     * Flushes and closes the writer.
     * <p>
     * After calling this method, the writer should not be used again.
     * Note: This does NOT close the underlying channel - the caller is
     * responsible for closing the channel.
     *
     * @throws IOException if there is an error flushing data
     */
    public void close() throws IOException {
        flush();
    }

    private void writeValueSeparatorIfNeeded() {
        if (state == State.AFTER_VALUE) {
            ensureCapacity(1);
            buffer.put((byte) ',');
        }
    }

    private void writeIndentedValueStart() {
        switch (state) {
            case AFTER_KEY:
                ensureCapacity(1);
                buffer.put((byte) ' ');
                break;
            case AFTER_VALUE:
                ensureCapacity(1);
                buffer.put((byte) ',');
                // Fall through
            case INIT:
                writeIndent();
                break;
            case INIT0:
                // First value, no newline needed
                break;
        }
    }

    private void writeIndent() {
        int indentSize = indentConfig.getIndentCount() * depth;
        ensureCapacity(1 + indentSize);
        buffer.put((byte) '\n');
        byte indentByte = (byte) indentConfig.getIndentChar();
        for (int i = 0; i < indentSize; i++) {
            buffer.put(indentByte);
        }
    }

    private void writeQuotedString(String s) {
        // Use CharsetEncoder for proper UTF-8 encoding (handles surrogate pairs)
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        
        // Estimate size: quotes + encoded bytes + potential escapes
        int estimatedSize = 2 + bytes.length + (bytes.length / 10);
        ensureCapacity(estimatedSize);

        buffer.put((byte) '"');

        // Process the string character by character for escaping
        for (int i = 0; i < s.length(); ) {
            int codePoint = s.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            
            // Check if we need more space (worst case: 6 bytes for unicode escape or 4 bytes for UTF-8)
            if (buffer.remaining() < 12) {
                growBuffer(buffer.capacity() * 2);
            }

            // Check for characters that need escaping
            if (codePoint == '"') {
                buffer.put((byte) '\\');
                buffer.put((byte) '"');
            } else if (codePoint == '\\') {
                buffer.put((byte) '\\');
                buffer.put((byte) '\\');
            } else if (codePoint == '\b') {
                buffer.put((byte) '\\');
                buffer.put((byte) 'b');
            } else if (codePoint == '\f') {
                buffer.put((byte) '\\');
                buffer.put((byte) 'f');
            } else if (codePoint == '\n') {
                buffer.put((byte) '\\');
                buffer.put((byte) 'n');
            } else if (codePoint == '\r') {
                buffer.put((byte) '\\');
                buffer.put((byte) 'r');
            } else if (codePoint == '\t') {
                buffer.put((byte) '\\');
                buffer.put((byte) 't');
            } else if (codePoint < 0x20) {
                // Control character - escape as unicode
                buffer.put((byte) '\\');
                buffer.put((byte) 'u');
                buffer.put((byte) '0');
                buffer.put((byte) '0');
                buffer.put((byte) hexChar((codePoint >> 4) & 0xF));
                buffer.put((byte) hexChar(codePoint & 0xF));
            } else if (codePoint < 0x80) {
                // ASCII
                buffer.put((byte) codePoint);
            } else {
                // Non-ASCII - encode as UTF-8
                writeUtf8CodePoint(codePoint);
            }
            
            i += charCount;
        }

        buffer.put((byte) '"');
    }

    private void writeUtf8CodePoint(int codePoint) {
        if (codePoint < 0x80) {
            // 1-byte UTF-8 (ASCII)
            buffer.put((byte) codePoint);
        } else if (codePoint < 0x800) {
            // 2-byte UTF-8
            buffer.put((byte) (0xC0 | (codePoint >> 6)));
            buffer.put((byte) (0x80 | (codePoint & 0x3F)));
        } else if (codePoint < 0x10000) {
            // 3-byte UTF-8
            buffer.put((byte) (0xE0 | (codePoint >> 12)));
            buffer.put((byte) (0x80 | ((codePoint >> 6) & 0x3F)));
            buffer.put((byte) (0x80 | (codePoint & 0x3F)));
        } else {
            // 4-byte UTF-8 (for supplementary characters like emojis)
            buffer.put((byte) (0xF0 | (codePoint >> 18)));
            buffer.put((byte) (0x80 | ((codePoint >> 12) & 0x3F)));
            buffer.put((byte) (0x80 | ((codePoint >> 6) & 0x3F)));
            buffer.put((byte) (0x80 | (codePoint & 0x3F)));
        }
    }

    private char hexChar(int n) {
        return (char) (n < 10 ? '0' + n : 'a' + (n - 10));
    }

    private void ensureCapacity(int needed) {
        if (buffer.remaining() < needed) {
            growBuffer(Math.max(buffer.capacity() * 2, buffer.position() + needed));
        }
    }

    private void growBuffer(int newCapacity) {
        ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
        buffer.flip();
        newBuffer.put(buffer);
        buffer = newBuffer;
    }

    private void sendIfNeeded() throws IOException {
        if (buffer.position() >= sendThreshold) {
            send();
        }
    }

    private void send() throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        buffer.clear();
    }

    /**
     * Package-private adapter that wraps an OutputStream as a WritableByteChannel.
     */
    static class OutputStreamChannel implements WritableByteChannel {
        
        private final OutputStream out;
        private boolean open = true;

        OutputStreamChannel(OutputStream out) {
            this.out = out;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (!open) {
                throw new IOException("Channel is closed");
            }
            int written = src.remaining();
            while (src.hasRemaining()) {
                out.write(src.get());
            }
            return written;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            if (open) {
                open = false;
                out.close();
            }
        }
    }
    
}

