/*
 * H2Writer.java
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

package org.bluezoo.gumdrop.http.h2;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.text.MessageFormat;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Streaming writer for HTTP/2 frames with NIO-first design.
 *
 * <p>This class provides efficient serialization of HTTP/2 frames to a
 * {@link WritableByteChannel}. It uses an internal buffer and automatically
 * sends data to the channel when the buffer fills beyond a threshold.
 *
 * <p>Key features:
 * <ul>
 *   <li>NIO-first - writes directly to channels</li>
 *   <li>Buffered output for efficiency</li>
 *   <li>Methods for each HTTP/2 frame type</li>
 *   <li>Handles frame header formatting</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * H2Writer writer = new H2Writer(channel);
 *
 * // Send SETTINGS frame
 * writer.writeSettings(false, settings);
 *
 * // Send HEADERS frame
 * writer.writeHeaders(streamId, headerBlock, true, false);
 *
 * // Send DATA frame
 * writer.writeData(streamId, data, true);
 *
 * writer.flush();
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is NOT thread-safe. It is intended for use on a single thread.
 * Callers must synchronize externally if used from multiple threads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H2Parser
 * @see H2FrameHandler
 */
public class H2Writer {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.http.h2.L10N");
    private static final Logger LOGGER = Logger.getLogger(H2Writer.class.getName());

    /** Frame header is always 9 bytes */
    public static final int FRAME_HEADER_LENGTH = 9;

    /** Default buffer capacity */
    private static final int DEFAULT_CAPACITY = 16384 + FRAME_HEADER_LENGTH;

    /** Send threshold as fraction of buffer capacity */
    private static final float SEND_THRESHOLD = 0.75f;

    // SETTINGS parameter identifiers
    public static final int SETTINGS_HEADER_TABLE_SIZE = 0x1;
    public static final int SETTINGS_ENABLE_PUSH = 0x2;
    public static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x3;
    public static final int SETTINGS_INITIAL_WINDOW_SIZE = 0x4;
    public static final int SETTINGS_MAX_FRAME_SIZE = 0x5;
    public static final int SETTINGS_MAX_HEADER_LIST_SIZE = 0x6;

    private final WritableByteChannel channel;
    private ByteBuffer buffer;
    private final int sendThreshold;

    /**
     * Creates a new HTTP/2 frame writer with default buffer capacity.
     *
     * @param out the output stream to write to
     */
    public H2Writer(OutputStream out) {
        this(new OutputStreamChannel(out), DEFAULT_CAPACITY);
    }

    /**
     * Creates a new HTTP/2 frame writer with default buffer capacity.
     *
     * @param channel the channel to write to
     */
    public H2Writer(WritableByteChannel channel) {
        this(channel, DEFAULT_CAPACITY);
    }

    /**
     * Creates a new HTTP/2 frame writer with specified buffer capacity.
     *
     * @param channel the channel to write to
     * @param bufferCapacity initial buffer capacity in bytes
     */
    public H2Writer(WritableByteChannel channel, int bufferCapacity) {
        if (channel == null) {
            throw new IllegalArgumentException(L10N.getString("err.channel_null"));
        }
        this.channel = channel;
        this.buffer = ByteBuffer.allocate(bufferCapacity);
        this.sendThreshold = (int) (bufferCapacity * SEND_THRESHOLD);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame Writing Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a DATA frame.
     *
     * @param streamId the stream identifier (must be non-zero)
     * @param data the data payload
     * @param endStream true if this is the last frame for this stream
     * @throws IOException if there is an error writing
     */
    public void writeData(int streamId, ByteBuffer data, boolean endStream)
            throws IOException {
        writeData(streamId, data, endStream, 0);
    }

    /**
     * Writes a DATA frame with optional padding.
     *
     * @param streamId the stream identifier (must be non-zero)
     * @param data the data payload
     * @param endStream true if this is the last frame for this stream
     * @param padLength padding length (0 for no padding)
     * @throws IOException if there is an error writing
     */
    public void writeData(int streamId, ByteBuffer data, boolean endStream, int padLength)
            throws IOException {
        if (streamId == 0) {
            throw new IllegalArgumentException(L10N.getString("err.data_stream_id"));
        }

        int flags = endStream ? H2FrameHandler.FLAG_END_STREAM : 0;
        int payloadLength = data.remaining();

        if (padLength > 0) {
            flags |= H2FrameHandler.FLAG_PADDED;
            payloadLength += 1 + padLength; // pad length field + padding
        }

        writeFrameHeader(payloadLength, H2FrameHandler.TYPE_DATA, flags, streamId);

        if (padLength > 0) {
            ensureCapacity(1);
            buffer.put((byte) padLength);
        }

        writePayload(data);

        if (padLength > 0) {
            ensureCapacity(padLength);
            for (int i = 0; i < padLength; i++) {
                buffer.put((byte) 0);
            }
        }

        sendIfNeeded();
        logFrame("DATA", streamId, payloadLength, flags);
    }

    /**
     * Writes a HEADERS frame.
     *
     * @param streamId the stream identifier (must be non-zero)
     * @param headerBlock the HPACK-encoded header block
     * @param endStream true if this is the last frame for this stream
     * @param endHeaders true if this completes the header block
     * @throws IOException if there is an error writing
     */
    public void writeHeaders(int streamId, ByteBuffer headerBlock,
            boolean endStream, boolean endHeaders) throws IOException {
        writeHeaders(streamId, headerBlock, endStream, endHeaders, 0, 0, 0, false);
    }

    /**
     * Writes a HEADERS frame with priority information.
     *
     * @param streamId the stream identifier (must be non-zero)
     * @param headerBlock the HPACK-encoded header block
     * @param endStream true if this is the last frame for this stream
     * @param endHeaders true if this completes the header block
     * @param dependsOn stream dependency (0 for none)
     * @param weight priority weight (1-256)
     * @param exclusive true for exclusive dependency
     * @throws IOException if there is an error writing
     */
    public void writeHeaders(int streamId, ByteBuffer headerBlock,
            boolean endStream, boolean endHeaders,
            int dependsOn, int weight, boolean exclusive) throws IOException {
        writeHeaders(streamId, headerBlock, endStream, endHeaders, 0, dependsOn, weight, exclusive);
    }

    /**
     * Writes a HEADERS frame with all options.
     *
     * @param streamId the stream identifier (must be non-zero)
     * @param headerBlock the HPACK-encoded header block
     * @param endStream true if this is the last frame for this stream
     * @param endHeaders true if this completes the header block
     * @param padLength padding length (0 for no padding)
     * @param dependsOn stream dependency (0 for none)
     * @param weight priority weight (1-256, ignored if dependsOn is 0)
     * @param exclusive true for exclusive dependency
     * @throws IOException if there is an error writing
     */
    public void writeHeaders(int streamId, ByteBuffer headerBlock,
            boolean endStream, boolean endHeaders, int padLength,
            int dependsOn, int weight, boolean exclusive) throws IOException {
        if (streamId == 0) {
            throw new IllegalArgumentException(L10N.getString("err.headers_stream_id"));
        }

        int flags = 0;
        if (endStream) {
            flags |= H2FrameHandler.FLAG_END_STREAM;
        }
        if (endHeaders) {
            flags |= H2FrameHandler.FLAG_END_HEADERS;
        }

        int payloadLength = headerBlock.remaining();
        boolean hasPriority = dependsOn > 0;

        if (padLength > 0) {
            flags |= H2FrameHandler.FLAG_PADDED;
            payloadLength += 1 + padLength;
        }

        if (hasPriority) {
            flags |= H2FrameHandler.FLAG_PRIORITY;
            payloadLength += 5; // dependency(4) + weight(1)
        }

        writeFrameHeader(payloadLength, H2FrameHandler.TYPE_HEADERS, flags, streamId);

        if (padLength > 0) {
            ensureCapacity(1);
            buffer.put((byte) padLength);
        }

        if (hasPriority) {
            ensureCapacity(5);
            int dep = dependsOn;
            if (exclusive) {
                dep |= 0x80000000;
            }
            buffer.put((byte) ((dep >> 24) & 0xff));
            buffer.put((byte) ((dep >> 16) & 0xff));
            buffer.put((byte) ((dep >> 8) & 0xff));
            buffer.put((byte) (dep & 0xff));
            buffer.put((byte) ((weight - 1) & 0xff));
        }

        writePayload(headerBlock);

        if (padLength > 0) {
            ensureCapacity(padLength);
            for (int i = 0; i < padLength; i++) {
                buffer.put((byte) 0);
            }
        }

        sendIfNeeded();
        logFrame("HEADERS", streamId, payloadLength, flags);
    }

    /**
     * Writes a PRIORITY frame.
     *
     * @param streamId the stream identifier (must be non-zero)
     * @param dependsOn stream dependency
     * @param weight priority weight (1-256)
     * @param exclusive true for exclusive dependency
     * @throws IOException if there is an error writing
     */
    public void writePriority(int streamId, int dependsOn, int weight, boolean exclusive)
            throws IOException {
        if (streamId == 0) {
            throw new IllegalArgumentException(L10N.getString("err.priority_stream_id"));
        }

        writeFrameHeader(5, H2FrameHandler.TYPE_PRIORITY, 0, streamId);

        ensureCapacity(5);
        int dep = dependsOn;
        if (exclusive) {
            dep |= 0x80000000;
        }
        buffer.put((byte) ((dep >> 24) & 0xff));
        buffer.put((byte) ((dep >> 16) & 0xff));
        buffer.put((byte) ((dep >> 8) & 0xff));
        buffer.put((byte) (dep & 0xff));
        buffer.put((byte) ((weight - 1) & 0xff));

        sendIfNeeded();
        logFrame("PRIORITY", streamId, 5, 0);
    }

    /**
     * Writes a RST_STREAM frame.
     *
     * @param streamId the stream identifier (must be non-zero)
     * @param errorCode the error code
     * @throws IOException if there is an error writing
     */
    public void writeRstStream(int streamId, int errorCode) throws IOException {
        if (streamId == 0) {
            throw new IllegalArgumentException(L10N.getString("err.rst_stream_stream_id"));
        }

        writeFrameHeader(4, H2FrameHandler.TYPE_RST_STREAM, 0, streamId);

        ensureCapacity(4);
        buffer.put((byte) ((errorCode >> 24) & 0xff));
        buffer.put((byte) ((errorCode >> 16) & 0xff));
        buffer.put((byte) ((errorCode >> 8) & 0xff));
        buffer.put((byte) (errorCode & 0xff));

        sendIfNeeded();
        logFrame("RST_STREAM", streamId, 4, 0);
    }

    /**
     * Writes an empty SETTINGS frame (acknowledgement).
     *
     * @throws IOException if there is an error writing
     */
    public void writeSettingsAck() throws IOException {
        writeFrameHeader(0, H2FrameHandler.TYPE_SETTINGS, H2FrameHandler.FLAG_ACK, 0);
        sendIfNeeded();
        logFrame("SETTINGS", 0, 0, H2FrameHandler.FLAG_ACK);
    }

    /**
     * Writes a SETTINGS frame with the specified parameters.
     *
     * @param settings map of setting ID to value
     * @throws IOException if there is an error writing
     */
    public void writeSettings(Map<Integer, Integer> settings) throws IOException {
        int payloadLength = settings.size() * 6; // Each setting is 6 bytes

        writeFrameHeader(payloadLength, H2FrameHandler.TYPE_SETTINGS, 0, 0);

        for (Map.Entry<Integer, Integer> entry : settings.entrySet()) {
            int id = entry.getKey();
            int value = entry.getValue();

            ensureCapacity(6);
            buffer.put((byte) ((id >> 8) & 0xff));
            buffer.put((byte) (id & 0xff));
            buffer.put((byte) ((value >> 24) & 0xff));
            buffer.put((byte) ((value >> 16) & 0xff));
            buffer.put((byte) ((value >> 8) & 0xff));
            buffer.put((byte) (value & 0xff));
        }

        sendIfNeeded();
        logFrame("SETTINGS", 0, payloadLength, 0);
    }

    /**
     * Writes a PUSH_PROMISE frame.
     *
     * @param streamId the stream identifier (must be non-zero)
     * @param promisedStreamId the promised stream identifier
     * @param headerBlock the HPACK-encoded header block
     * @param endHeaders true if this completes the header block
     * @throws IOException if there is an error writing
     */
    public void writePushPromise(int streamId, int promisedStreamId,
            ByteBuffer headerBlock, boolean endHeaders) throws IOException {
        if (streamId == 0) {
            throw new IllegalArgumentException(L10N.getString("err.push_promise_stream_id"));
        }

        int flags = endHeaders ? H2FrameHandler.FLAG_END_HEADERS : 0;
        int payloadLength = 4 + headerBlock.remaining(); // promised ID + header block

        writeFrameHeader(payloadLength, H2FrameHandler.TYPE_PUSH_PROMISE, flags, streamId);

        ensureCapacity(4);
        buffer.put((byte) ((promisedStreamId >> 24) & 0x7f)); // reserved bit
        buffer.put((byte) ((promisedStreamId >> 16) & 0xff));
        buffer.put((byte) ((promisedStreamId >> 8) & 0xff));
        buffer.put((byte) (promisedStreamId & 0xff));

        writePayload(headerBlock);

        sendIfNeeded();
        logFrame("PUSH_PROMISE", streamId, payloadLength, flags);
    }

    /**
     * Writes a PING frame.
     *
     * @param data the 8-byte opaque data
     * @param ack true if this is a PING acknowledgement
     * @throws IOException if there is an error writing
     */
    public void writePing(long data, boolean ack) throws IOException {
        int flags = ack ? H2FrameHandler.FLAG_ACK : 0;

        writeFrameHeader(8, H2FrameHandler.TYPE_PING, flags, 0);

        ensureCapacity(8);
        buffer.put((byte) ((data >> 56) & 0xff));
        buffer.put((byte) ((data >> 48) & 0xff));
        buffer.put((byte) ((data >> 40) & 0xff));
        buffer.put((byte) ((data >> 32) & 0xff));
        buffer.put((byte) ((data >> 24) & 0xff));
        buffer.put((byte) ((data >> 16) & 0xff));
        buffer.put((byte) ((data >> 8) & 0xff));
        buffer.put((byte) (data & 0xff));

        sendIfNeeded();
        logFrame("PING", 0, 8, flags);
    }

    /**
     * Writes a GOAWAY frame.
     *
     * @param lastStreamId the last stream ID the sender will accept
     * @param errorCode the error code
     * @throws IOException if there is an error writing
     */
    public void writeGoaway(int lastStreamId, int errorCode) throws IOException {
        writeGoaway(lastStreamId, errorCode, null);
    }

    /**
     * Writes a GOAWAY frame with additional debug data.
     *
     * @param lastStreamId the last stream ID the sender will accept
     * @param errorCode the error code
     * @param debugData optional debug data (may be null)
     * @throws IOException if there is an error writing
     */
    public void writeGoaway(int lastStreamId, int errorCode, ByteBuffer debugData)
            throws IOException {
        int payloadLength = 8; // lastStreamId(4) + errorCode(4)
        if (debugData != null) {
            payloadLength += debugData.remaining();
        }

        writeFrameHeader(payloadLength, H2FrameHandler.TYPE_GOAWAY, 0, 0);

        ensureCapacity(8);
        buffer.put((byte) ((lastStreamId >> 24) & 0x7f)); // reserved bit
        buffer.put((byte) ((lastStreamId >> 16) & 0xff));
        buffer.put((byte) ((lastStreamId >> 8) & 0xff));
        buffer.put((byte) (lastStreamId & 0xff));
        buffer.put((byte) ((errorCode >> 24) & 0xff));
        buffer.put((byte) ((errorCode >> 16) & 0xff));
        buffer.put((byte) ((errorCode >> 8) & 0xff));
        buffer.put((byte) (errorCode & 0xff));

        if (debugData != null) {
            writePayload(debugData);
        }

        sendIfNeeded();
        logFrame("GOAWAY", 0, payloadLength, 0);
    }

    /**
     * Writes a WINDOW_UPDATE frame.
     *
     * @param streamId the stream identifier (0 for connection-level)
     * @param increment the window size increment
     * @throws IOException if there is an error writing
     */
    public void writeWindowUpdate(int streamId, int increment) throws IOException {
        if (increment <= 0 || increment > 0x7FFFFFFF) {
            String msg = MessageFormat.format(L10N.getString("err.window_update_invalid"), increment);
            throw new IllegalArgumentException(msg);
        }

        writeFrameHeader(4, H2FrameHandler.TYPE_WINDOW_UPDATE, 0, streamId);

        ensureCapacity(4);
        buffer.put((byte) ((increment >> 24) & 0x7f)); // reserved bit
        buffer.put((byte) ((increment >> 16) & 0xff));
        buffer.put((byte) ((increment >> 8) & 0xff));
        buffer.put((byte) (increment & 0xff));

        sendIfNeeded();
        logFrame("WINDOW_UPDATE", streamId, 4, 0);
    }

    /**
     * Writes a CONTINUATION frame.
     *
     * @param streamId the stream identifier (must be non-zero)
     * @param headerBlock the HPACK-encoded header block fragment
     * @param endHeaders true if this completes the header block
     * @throws IOException if there is an error writing
     */
    public void writeContinuation(int streamId, ByteBuffer headerBlock, boolean endHeaders)
            throws IOException {
        if (streamId == 0) {
            throw new IllegalArgumentException(L10N.getString("err.continuation_stream_id"));
        }

        int flags = endHeaders ? H2FrameHandler.FLAG_END_HEADERS : 0;
        int payloadLength = headerBlock.remaining();

        writeFrameHeader(payloadLength, H2FrameHandler.TYPE_CONTINUATION, flags, streamId);
        writePayload(headerBlock);

        sendIfNeeded();
        logFrame("CONTINUATION", streamId, payloadLength, flags);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flush and Close
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Flushes any buffered data to the channel.
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
     * Note: This does NOT close the underlying channel.
     *
     * @throws IOException if there is an error flushing data
     */
    public void close() throws IOException {
        flush();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal Methods
    // ─────────────────────────────────────────────────────────────────────────

    private void writeFrameHeader(int length, int type, int flags, int streamId) {
        ensureCapacity(FRAME_HEADER_LENGTH);
        buffer.put((byte) ((length >> 16) & 0xff));
        buffer.put((byte) ((length >> 8) & 0xff));
        buffer.put((byte) (length & 0xff));
        buffer.put((byte) (type & 0xff));
        buffer.put((byte) (flags & 0xff));
        buffer.put((byte) ((streamId >> 24) & 0x7f)); // reserved bit
        buffer.put((byte) ((streamId >> 16) & 0xff));
        buffer.put((byte) ((streamId >> 8) & 0xff));
        buffer.put((byte) (streamId & 0xff));
    }

    private void writePayload(ByteBuffer payload) {
        if (payload.remaining() > buffer.remaining()) {
            ensureCapacity(payload.remaining());
        }
        buffer.put(payload);
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

    private void logFrame(String type, int streamId, int length, int flags) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Wrote " + type + " frame: stream=" + streamId +
                ", length=" + length + ", flags=" + flags);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OutputStream Adapter
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adapter that wraps an OutputStream as a WritableByteChannel.
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
                throw new IOException(L10N.getString("err.channel_closed"));
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

