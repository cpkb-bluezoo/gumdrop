/*
 * RecordingEventHandler.java
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

package org.bluezoo.gumdrop.redis.codec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Test support: records the events a {@link RespDecoder} reports as text, so
 * that a sequence can be compared with what was expected. Adjacent chunks of
 * one string are joined into one event, as how the payload is split depends
 * on how the input was, and not on what it says.
 *
 * <p>A subclass can answer {@link #flowAfter(String)} to pause or skip.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RecordingEventHandler implements RespEventHandler {

    private final List<String> events = new ArrayList<String>();
    private StringBuilder chunks;

    /**
     * Returns the events so far.
     *
     * @return the events, in order
     */
    public List<String> events() {
        flushChunks();
        return events;
    }

    /**
     * What to do after an event. By default, go on.
     *
     * @param event the event just recorded
     * @return the flow to return to the decoder
     */
    protected ReplyFlow flowAfter(String event) {
        return ReplyFlow.CONTINUE;
    }

    private void flushChunks() {
        if (chunks != null) {
            events.add("chunk:" + chunks);
            chunks = null;
        }
    }

    private ReplyFlow record(String event) {
        flushChunks();
        events.add(event);
        return flowAfter(event);
    }

    /**
     * Hex text of bytes.
     *
     * @param bytes the bytes
     * @return their hex digits
     */
    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            sb.append(Character.forDigit((bytes[i] >> 4) & 0xf, 16));
            sb.append(Character.forDigit(bytes[i] & 0xf, 16));
        }
        return sb.toString();
    }

    @Override
    public void beginReply(boolean push) {
        record(push ? "begin:push" : "begin");
    }

    @Override
    public void endReply(boolean skipped) {
        record(skipped ? "end:skipped" : "end");
    }

    @Override
    public ReplyFlow nullValue() {
        return record("null");
    }

    @Override
    public ReplyFlow integer(long value) {
        return record("int:" + value);
    }

    @Override
    public ReplyFlow doubleValue(double value) {
        return record("double:" + value);
    }

    @Override
    public ReplyFlow booleanValue(boolean value) {
        return record("bool:" + value);
    }

    @Override
    public ReplyFlow bigNumber(String digits) {
        return record("big:" + digits);
    }

    @Override
    public ReplyFlow simpleString(String value) {
        return record("simple:" + value);
    }

    @Override
    public ReplyFlow error(String message) {
        return record("error:" + message);
    }

    @Override
    public ReplyFlow beginAggregate(RespType kind, long length) {
        return record("agg:" + kind + ":" + length);
    }

    @Override
    public ReplyFlow endAggregate() {
        return record("endagg");
    }

    @Override
    public ReplyFlow beginString(RespType kind, long length, String format) {
        return record("str:" + kind + ":" + length + (format == null ? "" : ":" + format));
    }

    @Override
    public ReplyFlow stringChunk(ByteBuffer chunk) {
        byte[] bytes = new byte[chunk.remaining()];
        chunk.duplicate().get(bytes);
        if (chunks == null) {
            chunks = new StringBuilder();
        }
        chunks.append(hex(bytes));
        return flowAfter("chunk:" + hex(bytes));
    }

    @Override
    public ReplyFlow endString() {
        return record("endstr");
    }

}
