/*
 * WholeMessages.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Test support: a {@link MessageHandler} that accumulates each message and
 * reports it whole, so that tests can check what was delivered.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class WholeMessages implements MessageHandler {

    private MessageHeader header;
    private ByteArrayOutputStream body;

    /** A message on a subscribed channel. */
    public void handleMessage(String channel, byte[] message) {
    }

    /** A message that matched a pattern. */
    public void handlePatternMessage(String pattern, String channel, byte[] message) {
    }

    /** A message on a shard channel. */
    public void handleShardMessage(String channel, byte[] message) {
    }

    @Override
    public ReplyFlow messageBegin(MessageHeader h, long length, ReplyStream stream) {
        header = h;
        body = new ByteArrayOutputStream();
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow messageContent(ByteBuffer chunk) {
        byte[] b = new byte[chunk.remaining()];
        chunk.get(b);
        body.write(b, 0, b.length);
        return ReplyFlow.CONTINUE;
    }

    @Override
    public void messageEnd() {
        byte[] message = body.toByteArray();
        switch (header.getKind()) {
            case PATTERN:
                handlePatternMessage(header.getPattern(), header.getChannel(), message);
                break;
            case SHARD:
                handleShardMessage(header.getChannel(), message);
                break;
            default:
                handleMessage(header.getChannel(), message);
                break;
        }
    }
}
