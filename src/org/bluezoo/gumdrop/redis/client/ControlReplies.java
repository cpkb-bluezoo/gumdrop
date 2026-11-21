/*
 * ControlReplies.java
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

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespType;
import org.bluezoo.gumdrop.redis.codec.RespValue;
import org.bluezoo.gumdrop.redis.codec.RespValueBuilder;

/**
 * Handlers for the replies the client reads for itself: the handshake and
 * the cluster's layout. They are not part of the public API, which has no
 * call that returns a value whose size depends on the server's data. A
 * reply that is read whole is built under a fixed cap, and one over it
 * fails.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ControlReplies {

    /** The most a control-plane reply may hold. */
    static final long MAX_CONTROL_REPLY = 4L * 1024 * 1024;

    private ControlReplies() {
    }

    /** A reply of which only the outcome matters; what it holds is ignored. */
    abstract static class Acknowledgement implements ReplyEventHandler {

        /**
         * The command is over.
         *
         * @param error null if it succeeded
         * @param session the session
         */
        abstract void done(String error, RedisSession session);

        @Override
        public void handleStream(ReplyStream stream) {
        }

        @Override
        public void handleComplete(String error, RedisSession session) {
            done(error, session);
        }

        @Override
        public void beginReply(boolean push) {
        }

        @Override
        public void endReply(boolean skipped) {
        }

        @Override
        public ReplyFlow nullValue() {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow integer(long value) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow simpleString(String value) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow error(String message) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow endAggregate() {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow endString() {
            return ReplyFlow.CONTINUE;
        }
    }

    /** A reply built whole, under {@link #MAX_CONTROL_REPLY}. */
    abstract static class WholeReply implements ReplyEventHandler {

        private final RespValueBuilder builder = new RespValueBuilder(MAX_CONTROL_REPLY);

        /**
         * The reply arrived.
         *
         * @param value the reply
         * @param session the session
         */
        abstract void onValue(RespValue value, RedisSession session);

        /**
         * The command failed.
         *
         * @param error the error
         * @param session the session
         */
        abstract void onError(String error, RedisSession session);

        @Override
        public void handleStream(ReplyStream stream) {
        }

        @Override
        public void handleComplete(String error, RedisSession session) {
            if (error != null) {
                onError(error, session);
            } else if (builder.isComplete()) {
                onValue(builder.value(), session);
            } else {
                onError(RedisClientProtocolHandler.L10N.getString("err.reply_shape"), session);
            }
        }

        @Override
        public void beginReply(boolean push) {
            builder.beginReply(push);
        }

        @Override
        public void endReply(boolean skipped) {
            builder.endReply(skipped);
        }

        @Override
        public ReplyFlow nullValue() {
            return builder.nullValue();
        }

        @Override
        public ReplyFlow integer(long value) {
            return builder.integer(value);
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            return builder.doubleValue(value);
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            return builder.booleanValue(value);
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            return builder.bigNumber(digits);
        }

        @Override
        public ReplyFlow simpleString(String value) {
            return builder.simpleString(value);
        }

        @Override
        public ReplyFlow error(String message) {
            return builder.error(message);
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            return builder.beginAggregate(kind, length);
        }

        @Override
        public ReplyFlow endAggregate() {
            return builder.endAggregate();
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            return builder.beginString(kind, length, format);
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            return builder.stringChunk(chunk);
        }

        @Override
        public ReplyFlow endString() {
            return builder.endString();
        }
    }

    /** Passes every event on to another handler, which a subclass can watch. */
    abstract static class Forwarding implements ReplyEventHandler {

        final ReplyEventHandler delegate;

        Forwarding(ReplyEventHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void handleStream(ReplyStream stream) {
            delegate.handleStream(stream);
        }

        @Override
        public void beginReply(boolean push) {
            delegate.beginReply(push);
        }

        @Override
        public void endReply(boolean skipped) {
            delegate.endReply(skipped);
        }

        @Override
        public ReplyFlow nullValue() {
            return delegate.nullValue();
        }

        @Override
        public ReplyFlow integer(long value) {
            return delegate.integer(value);
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            return delegate.doubleValue(value);
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            return delegate.booleanValue(value);
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            return delegate.bigNumber(digits);
        }

        @Override
        public ReplyFlow simpleString(String value) {
            return delegate.simpleString(value);
        }

        @Override
        public ReplyFlow error(String message) {
            return delegate.error(message);
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            return delegate.beginAggregate(kind, length);
        }

        @Override
        public ReplyFlow endAggregate() {
            return delegate.endAggregate();
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            return delegate.beginString(kind, length, format);
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            return delegate.stringChunk(chunk);
        }

        @Override
        public ReplyFlow endString() {
            return delegate.endString();
        }
    }

}
