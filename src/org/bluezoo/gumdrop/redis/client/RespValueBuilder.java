/*
 * RespValueBuilder.java
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

package org.bluezoo.gumdrop.redis.client;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespDecoder;
import org.bluezoo.gumdrop.redis.codec.RespEventHandler;
import org.bluezoo.gumdrop.redis.codec.RespType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the events of one reply into a {@link RespValue}, under a fixed
 * cap.
 *
 * <p>This is for replies that are small by construction (the handshake, the
 * cluster layout) and is not part of the streaming API: a reply whose size
 * depends on the server's data must go to a handler that streams it. The
 * cap is on what is held, payload bytes together with a fixed allowance for
 * each value. A reply that would pass it is not built: the builder answers
 * {@link ReplyFlow#SKIP} so that the rest is discarded in constant memory,
 * and {@link #isOverflow()} reports it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RespValueBuilder implements RespEventHandler {

    /** What each value counts against the cap, besides its payload. */
    private static final int VALUE_COST = 32;
    private static final int INITIAL_PAYLOAD = 1024;

    private static final class Frame {
        final RespType type;
        final List<RespValue> elements;
        final Map<RespValue, RespValue> entries;
        RespValue pendingKey;

        Frame(RespType type) {
            this.type = type;
            if (type == RespType.MAP) {
                elements = null;
                entries = new LinkedHashMap<RespValue, RespValue>();
            } else {
                elements = new ArrayList<RespValue>();
                entries = null;
            }
        }

        RespValue build() {
            switch (type) {
                case MAP:
                    return RespValue.map(entries);
                case SET:
                    return RespValue.set(elements);
                case PUSH:
                    return RespValue.push(elements);
                default:
                    return RespValue.array(elements);
            }
        }
    }

    private final long maxBytes;
    private long held;
    private boolean overflow;
    private boolean complete;
    private boolean push;
    private RespValue root;
    private final Frame[] frames = new Frame[RespDecoder.MAX_NESTING_DEPTH];
    private int depth;
    // the string being read
    private RespType stringType;
    private String stringFormat;
    private byte[] payload;
    private int payloadLength;
    private long payloadExpected;

    /**
     * Creates a builder.
     *
     * @param maxBytes the most a reply may hold, at least 1
     */
    public RespValueBuilder(long maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException(Long.toString(maxBytes));
        }
        this.maxBytes = maxBytes;
    }

    /**
     * Returns whether a whole reply was built.
     *
     * @return true if {@link #value()} is available
     */
    public boolean isComplete() {
        return complete;
    }

    /**
     * Returns whether the reply was too large to build, and was discarded.
     *
     * @return true if it passed the cap
     */
    public boolean isOverflow() {
        return overflow;
    }

    /**
     * Returns whether the reply was a push.
     *
     * @return true if it was
     */
    public boolean isPush() {
        return push;
    }

    /**
     * Returns the reply built.
     *
     * @return the value, or null unless {@link #isComplete()}
     */
    public RespValue value() {
        return complete ? root : null;
    }

    /** Forgets what has been built. */
    public void clear() {
        held = 0;
        overflow = false;
        complete = false;
        push = false;
        root = null;
        for (int i = 0; i < depth; i++) {
            frames[i] = null;
        }
        depth = 0;
        payload = null;
        payloadLength = 0;
    }

    @Override
    public void beginReply(boolean isPush) {
        clear();
        push = isPush;
    }

    @Override
    public void endReply(boolean skipped) {
        complete = !skipped && !overflow && root != null;
    }

    private ReplyFlow charge(long bytes) {
        held += VALUE_COST + bytes;
        if (held > maxBytes || held < 0) {
            overflow = true;
            return ReplyFlow.SKIP;
        }
        return ReplyFlow.CONTINUE;
    }

    // Adds a completed value to the aggregate being built, or makes it the reply.
    private void add(RespValue value) {
        if (depth == 0) {
            root = value;
            return;
        }
        Frame frame = frames[depth - 1];
        if (frame.type == RespType.MAP) {
            if (frame.pendingKey == null) {
                frame.pendingKey = value;
            } else {
                frame.entries.put(frame.pendingKey, value);
                frame.pendingKey = null;
            }
        } else {
            frame.elements.add(value);
        }
    }

    private ReplyFlow scalar(RespValue value, long bytes) {
        ReplyFlow flow = charge(bytes);
        if (flow == ReplyFlow.CONTINUE) {
            add(value);
        }
        return flow;
    }

    @Override
    public ReplyFlow nullValue() {
        return scalar(RespValue.nullValue(), 0);
    }

    @Override
    public ReplyFlow integer(long value) {
        return scalar(RespValue.integer(value), 0);
    }

    @Override
    public ReplyFlow doubleValue(double value) {
        return scalar(RespValue.doubleValue(value), 0);
    }

    @Override
    public ReplyFlow booleanValue(boolean value) {
        return scalar(RespValue.booleanValue(value), 0);
    }

    @Override
    public ReplyFlow bigNumber(String digits) {
        return scalar(RespValue.bigNumber(digits), digits.length());
    }

    @Override
    public ReplyFlow simpleString(String value) {
        return scalar(RespValue.simpleString(value), value.length());
    }

    @Override
    public ReplyFlow error(String message) {
        return scalar(RespValue.error(message), message.length());
    }

    @Override
    public ReplyFlow beginAggregate(RespType kind, long length) {
        ReplyFlow flow = charge(0);
        if (flow == ReplyFlow.CONTINUE) {
            frames[depth++] = new Frame(kind);
        }
        return flow;
    }

    @Override
    public ReplyFlow endAggregate() {
        Frame frame = frames[--depth];
        frames[depth] = null;
        add(frame.build());
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow beginString(RespType kind, long length, String format) {
        ReplyFlow flow = charge(Math.max(length, 0));
        if (flow != ReplyFlow.CONTINUE) {
            return flow;
        }
        stringType = kind;
        stringFormat = format;
        payloadExpected = length;
        payloadLength = 0;
        // grows with what arrives, so a length claimed by a peer that
        // sends nothing costs little
        int initial = length < 0 ? INITIAL_PAYLOAD
                : (int) Math.min(length, 64 * 1024);
        payload = new byte[initial];
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow stringChunk(ByteBuffer chunk) {
        int n = chunk.remaining();
        if (payloadExpected < 0) {
            // a streamed string: its size is only known as it arrives
            held += n;
            if (held > maxBytes || held < 0) {
                overflow = true;
                return ReplyFlow.SKIP;
            }
        }
        if (payload.length - payloadLength < n) {
            long capacity = Math.max(2L * payload.length, (long) payloadLength + n);
            if (payloadExpected >= 0) {
                capacity = Math.min(capacity, payloadExpected);
            }
            byte[] grown = new byte[(int) capacity];
            System.arraycopy(payload, 0, grown, 0, payloadLength);
            payload = grown;
        }
        chunk.get(payload, payloadLength, n);
        payloadLength += n;
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow endString() {
        byte[] data = payload;
        if (data.length != payloadLength) {
            byte[] exact = new byte[payloadLength];
            System.arraycopy(data, 0, exact, 0, payloadLength);
            data = exact;
        }
        payload = null;
        RespValue value;
        switch (stringType) {
            case BLOB_ERROR:
                value = RespValue.blobError(data);
                break;
            case VERBATIM_STRING:
                value = RespValue.verbatimString(stringFormat, data);
                break;
            default:
                value = RespValue.bulkString(data);
                break;
        }
        add(value);
        return ReplyFlow.CONTINUE;
    }

}
