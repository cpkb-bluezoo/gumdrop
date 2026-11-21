/*
 * ValueDecoder.java
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
import org.bluezoo.gumdrop.redis.codec.RespException;
import org.bluezoo.gumdrop.redis.codec.RespType;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Test support: a {@link RespDecoder} that assembles each reply into a
 * {@link RespValue}, so that tests can compare what was decoded with what
 * was expected. A value is available from {@link #next()} once its reply is
 * complete, and an error in the data is raised there after the values read
 * before it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ValueDecoder {

    private static final long CAP = 1L << 30;

    private final RespDecoder decoder = new RespDecoder();
    private final RespValueBuilder builder = new RespValueBuilder(CAP);
    private final Deque<RespValue> values = new ArrayDeque<RespValue>();
    private RespException failure;

    private final RespEventHandler collector = new Collector();

    /** Creates a decoder. */
    public ValueDecoder() {
    }

    /**
     * Creates a decoder.
     *
     * @param ignored kept for the signature of the decoder this replaces
     */
    public ValueDecoder(int ignored) {
    }

    /**
     * Gives the decoder the bytes that have arrived.
     *
     * @param data the bytes
     */
    public void receive(ByteBuffer data) {
        try {
            decoder.receive(data, collector);
        } catch (RespException e) {
            failure = e;
        }
    }

    /**
     * Returns the next complete value.
     *
     * @return the value, or null if none is complete yet
     * @throws RespException if the data was malformed
     */
    public RespValue next() throws RespException {
        if (!values.isEmpty()) {
            return values.poll();
        }
        if (failure != null) {
            throw failure;
        }
        return null;
    }

    /** Resets the decoder. */
    public void reset() {
        decoder.reset();
        values.clear();
        failure = null;
    }

    private final class Collector extends DelegatingHandler {
        Collector() {
            super(builder);
        }

        @Override
        public void endReply(boolean skipped) {
            builder.endReply(skipped);
            if (builder.isComplete()) {
                values.add(builder.value());
            }
        }
    }

    /** Passes every event to another handler. */
    abstract static class DelegatingHandler implements RespEventHandler {
        private final RespEventHandler target;

        DelegatingHandler(RespEventHandler target) {
            this.target = target;
        }

        @Override
        public void beginReply(boolean push) {
            target.beginReply(push);
        }

        @Override
        public void endReply(boolean skipped) {
            target.endReply(skipped);
        }

        @Override
        public ReplyFlow nullValue() {
            return target.nullValue();
        }

        @Override
        public ReplyFlow integer(long value) {
            return target.integer(value);
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            return target.doubleValue(value);
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            return target.booleanValue(value);
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            return target.bigNumber(digits);
        }

        @Override
        public ReplyFlow simpleString(String value) {
            return target.simpleString(value);
        }

        @Override
        public ReplyFlow error(String message) {
            return target.error(message);
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            return target.beginAggregate(kind, length);
        }

        @Override
        public ReplyFlow endAggregate() {
            return target.endAggregate();
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            return target.beginString(kind, length, format);
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            return target.stringChunk(chunk);
        }

        @Override
        public ReplyFlow endString() {
            return target.endString();
        }
    }

}
