/*
 * H2FlowControl.java
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

package org.bluezoo.gumdrop.http.h2;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * HTTP/2 flow control accounting for both connection-level and per-stream
 * send and receive windows (RFC 9113 section 5.2).
 *
 * <p>RFC 9113 section 5.2 defines the flow control mechanism:
 * <ul>
 * <li>Section 5.2.1: flow control is specific to a connection; hop-by-hop</li>
 * <li>Section 5.2.2: initial window size is 65,535 octets</li>
 * <li>Section 6.9: WINDOW_UPDATE frame increments the window</li>
 * <li>Section 6.9.1: connection-level flow control is separate from
 *     stream-level</li>
 * <li>Section 6.9.2: SETTINGS_INITIAL_WINDOW_SIZE adjusts all stream
 *     windows</li>
 * </ul>
 *
 * <p>This class tracks window sizes and pause state but performs no I/O.
 * The caller is responsible for sending WINDOW_UPDATE frames via
 * {@link H2Writer} and for gating DATA frame writes based on the
 * available send window.
 *
 * <p>All methods assume single-threaded access from the selector loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H2Writer#writeWindowUpdate(int, int)
 */
public class H2FlowControl {

    /** RFC 9113 section 6.9.2: default initial window size. */
    public static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535;

    /**
     * Emit a WINDOW_UPDATE when half the window has been consumed.
     * This avoids sending a WINDOW_UPDATE for every tiny DATA frame
     * while still keeping the peer's send path unblocked.
     */
    private static final double WINDOW_UPDATE_THRESHOLD = 0.5;

    // ── Connection-level windows (RFC 9113 section 6.9.1) ──

    private long connectionSendWindow;
    private long connectionRecvWindow;
    private long connectionRecvConsumed;

    // ── Per-stream state ──

    private int initialSendWindowSize;
    private int initialRecvWindowSize;

    private final Map<Integer, StreamFlowState> streams =
            new HashMap<Integer, StreamFlowState>();

    /**
     * Per-stream flow control state.  Instances are pooled and
     * recycled via {@link #acquireState()} / {@link #releaseState}.
     */
    private static class StreamFlowState {
        long sendWindow;
        long recvWindow;
        long recvConsumed;
        boolean paused;
        long pausedIncrement;
        StreamFlowState poolNext;

        void reset(long sendWindow, long recvWindow) {
            this.sendWindow = sendWindow;
            this.recvWindow = recvWindow;
            this.recvConsumed = 0;
            this.paused = false;
            this.pausedIncrement = 0;
            this.poolNext = null;
        }
    }

    private static final int MAX_POOLED = 64;

    private StreamFlowState poolHead;
    private int poolSize;

    /**
     * Mutable result holder for {@link #onDataReceived}.  Callers
     * should allocate one instance and reuse it across calls to
     * avoid per-frame allocation overhead.
     */
    public static class DataReceivedResult {
        /** Connection-level WINDOW_UPDATE increment, or 0. */
        public int connectionIncrement;
        /** Stream-level WINDOW_UPDATE increment, or 0. */
        public int streamIncrement;

        void set(int connectionIncrement, int streamIncrement) {
            this.connectionIncrement = connectionIncrement;
            this.streamIncrement = streamIncrement;
        }
    }

    /**
     * Creates a new flow control tracker with the default initial
     * window size (65535) for both send and receive windows.
     */
    public H2FlowControl() {
        this(DEFAULT_INITIAL_WINDOW_SIZE);
    }

    /**
     * Creates a new flow control tracker.
     *
     * <p>Per RFC 9113 section 6.9.2, both the connection-level and
     * per-stream initial window sizes start at 65535 octets.
     *
     * @param initialWindowSize the initial window size for both
     *        send and receive windows (from SETTINGS)
     */
    public H2FlowControl(int initialWindowSize) {
        this.initialSendWindowSize = initialWindowSize;
        this.initialRecvWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
        this.connectionSendWindow = DEFAULT_INITIAL_WINDOW_SIZE;
        this.connectionRecvWindow = DEFAULT_INITIAL_WINDOW_SIZE;
    }

    private StreamFlowState acquireState() {
        StreamFlowState state = poolHead;
        if (state != null) {
            poolHead = state.poolNext;
            poolSize--;
            return state;
        }
        return new StreamFlowState();
    }

    private void releaseState(StreamFlowState state) {
        if (poolSize >= MAX_POOLED) {
            return;
        }
        state.poolNext = poolHead;
        poolHead = state;
        poolSize++;
    }

    // ── Stream lifecycle ──

    /**
     * Registers a new stream with initial window sizes.
     *
     * @param streamId the stream identifier
     */
    public void openStream(int streamId) {
        StreamFlowState state = acquireState();
        state.reset(initialSendWindowSize, initialRecvWindowSize);
        streams.put(streamId, state);
    }

    /**
     * Removes a closed stream's flow control state.
     *
     * @param streamId the stream identifier
     */
    public void closeStream(int streamId) {
        StreamFlowState state = streams.remove(streamId);
        if (state != null) {
            releaseState(state);
        }
    }

    // ── Receive side ──

    /**
     * Called when a DATA frame is received on the given stream.
     * Decrements both connection-level and per-stream receive windows
     * and populates {@code result} with the WINDOW_UPDATE increments
     * that should be sent.
     *
     * <p>A zero increment means no WINDOW_UPDATE is needed at that
     * level.  For a paused stream, the stream-level increment will be
     * zero; the deferred increment is accumulated internally and
     * returned when the stream is resumed via {@link #resumeStream}.
     *
     * @param streamId the stream identifier
     * @param length the number of DATA bytes received
     * @param result reusable result holder (fields are overwritten)
     */
    public void onDataReceived(int streamId, int length,
            DataReceivedResult result) {
        if (length <= 0) {
            result.set(0, 0);
            return;
        }

        // Connection-level accounting
        connectionRecvWindow -= length;
        connectionRecvConsumed += length;
        int connIncrement = 0;
        if (connectionRecvConsumed >=
                (long) (DEFAULT_INITIAL_WINDOW_SIZE * WINDOW_UPDATE_THRESHOLD)) {
            connIncrement = (int) connectionRecvConsumed;
            connectionRecvWindow += connIncrement;
            connectionRecvConsumed = 0;
        }

        // Per-stream accounting
        StreamFlowState state = streams.get(streamId);
        if (state == null) {
            result.set(connIncrement, 0);
            return;
        }
        state.recvWindow -= length;
        state.recvConsumed += length;

        int streamIncrement = 0;
        if (state.paused) {
            state.pausedIncrement += state.recvConsumed;
            state.recvConsumed = 0;
        } else if (state.recvConsumed >=
                (long) (initialRecvWindowSize * WINDOW_UPDATE_THRESHOLD)) {
            streamIncrement = (int) state.recvConsumed;
            state.recvWindow += streamIncrement;
            state.recvConsumed = 0;
        }

        result.set(connIncrement, streamIncrement);
    }

    // ── Send side ──

    /**
     * Called when a WINDOW_UPDATE frame is received (RFC 9113 section 6.9).
     * Increments the send window for the connection (streamId 0) or the
     * given stream.
     *
     * <p>RFC 9113 section 6.9.1: a change to SETTINGS_INITIAL_WINDOW_SIZE
     * can cause the flow-control window to become negative; a
     * WINDOW_UPDATE that causes the window to exceed 2^31-1 MUST be
     * treated as a flow-control error.
     *
     * @param streamId the stream identifier (0 for connection-level)
     * @param increment the window size increment
     * @return true if the increment caused a flow-control error
     *         (window overflow beyond 2^31-1)
     */
    public boolean onWindowUpdate(int streamId, int increment) {
        if (streamId == 0) {
            connectionSendWindow += increment;
            // RFC 9113 section 6.9.1: overflow is a connection error
            return connectionSendWindow > 0x7FFFFFFFL;
        }
        StreamFlowState state = streams.get(streamId);
        if (state != null) {
            state.sendWindow += increment;
            return state.sendWindow > 0x7FFFFFFFL;
        }
        return false;
    }

    /**
     * Returns the available send window for the given stream,
     * capped by the connection-level send window (RFC 9113 section 6.9).
     *
     * <p>A sender MUST NOT send a flow-controlled frame with a length
     * that exceeds the space available in either the connection or
     * stream flow-control window.
     *
     * @param streamId the stream identifier
     * @return the number of bytes that can be sent, or 0
     */
    public int availableSendWindow(int streamId) {
        StreamFlowState state = streams.get(streamId);
        if (state == null) {
            return 0;
        }
        long available = Math.min(connectionSendWindow, state.sendWindow);
        if (available <= 0) {
            return 0;
        }
        return (int) Math.min(available, Integer.MAX_VALUE);
    }

    /**
     * Consumes send window on both the connection and the stream
     * after sending DATA.
     *
     * @param streamId the stream identifier
     * @param length the number of DATA bytes sent
     */
    public void consumeSendWindow(int streamId, int length) {
        connectionSendWindow -= length;
        StreamFlowState state = streams.get(streamId);
        if (state != null) {
            state.sendWindow -= length;
        }
    }

    /**
     * Returns true if the given stream has a blocked send window
     * (either stream-level or connection-level is zero or negative).
     *
     * @param streamId the stream identifier
     * @return true if the stream cannot currently send DATA
     */
    public boolean isSendBlocked(int streamId) {
        return availableSendWindow(streamId) <= 0;
    }

    /**
     * Invokes the consumer for each stream that currently has available
     * send window (both stream-level and connection-level are positive).
     * Useful after a connection-level WINDOW_UPDATE to find streams
     * that may resume sending.
     *
     * @param consumer called with each unblocked stream ID
     */
    public void forEachUnblockedStream(IntConsumer consumer) {
        if (connectionSendWindow <= 0) {
            return;
        }
        for (Map.Entry<Integer, StreamFlowState> entry : streams.entrySet()) {
            if (entry.getValue().sendWindow > 0) {
                consumer.accept(entry.getKey().intValue());
            }
        }
    }

    // ── Pause / Resume (receive-side backpressure) ──
    // RFC 9113 section 6.9: a receiver MAY use WINDOW_UPDATE to
    // implement backpressure by withholding increments.

    /**
     * Pauses a stream, causing WINDOW_UPDATE increments for that
     * stream to be withheld.  The peer will eventually exhaust its
     * send window and stop sending DATA (RFC 9113 section 6.9).
     *
     * @param streamId the stream identifier
     */
    public void pauseStream(int streamId) {
        StreamFlowState state = streams.get(streamId);
        if (state != null) {
            state.paused = true;
        }
    }

    /**
     * Resumes a paused stream.  Returns the accumulated
     * WINDOW_UPDATE increment that was withheld while the stream
     * was paused.  The caller should send this as a WINDOW_UPDATE
     * frame for the stream.
     *
     * @param streamId the stream identifier
     * @return the deferred increment to send, or 0
     */
    public int resumeStream(int streamId) {
        StreamFlowState state = streams.get(streamId);
        if (state == null || !state.paused) {
            return 0;
        }
        state.paused = false;
        int increment = (int) state.pausedIncrement;
        state.pausedIncrement = 0;
        if (increment > 0) {
            state.recvWindow += increment;
        }
        // Also flush any unconsumed data that was below the threshold
        if (state.recvConsumed > 0) {
            increment += (int) state.recvConsumed;
            state.recvWindow += state.recvConsumed;
            state.recvConsumed = 0;
        }
        return increment;
    }

    /**
     * Returns true if the given stream is paused.
     *
     * @param streamId the stream identifier
     * @return true if paused
     */
    public boolean isStreamPaused(int streamId) {
        StreamFlowState state = streams.get(streamId);
        return state != null && state.paused;
    }

    // ── SETTINGS updates ──

    /**
     * Adjusts all open stream send windows when
     * {@code SETTINGS_INITIAL_WINDOW_SIZE} changes.
     * Per RFC 9113 section 6.9.2, the difference between the new
     * and old values is applied to every open stream's send window.
     *
     * @param newSize the new initial window size
     * @return true if any stream's send window would overflow 2^31-1
     */
    public boolean onSettingsInitialWindowSize(int newSize) {
        int delta = newSize - initialSendWindowSize;
        initialSendWindowSize = newSize;
        if (delta == 0) {
            return false;
        }
        boolean overflow = false;
        for (StreamFlowState state : streams.values()) {
            state.sendWindow += delta;
            if (state.sendWindow > 0x7FFFFFFFL) {
                overflow = true;
            }
        }
        return overflow;
    }

    /**
     * Returns the current initial send window size (the value from
     * the peer's SETTINGS).
     *
     * @return the initial window size for new streams
     */
    public int getInitialSendWindowSize() {
        return initialSendWindowSize;
    }

    /**
     * Returns the connection-level send window.
     *
     * @return bytes remaining in the connection send window
     */
    public long getConnectionSendWindow() {
        return connectionSendWindow;
    }

    /**
     * Returns the connection-level receive window.  A negative value
     * indicates the peer has violated flow control.
     *
     * @return bytes remaining in the connection receive window
     */
    public long getConnectionRecvWindow() {
        return connectionRecvWindow;
    }
}
