/*
 * SmallMessages.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.redis.client.MessageHandler;
import org.bluezoo.gumdrop.redis.client.MessageHeader;
import org.bluezoo.gumdrop.redis.client.ReplyStream;
import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Accumulates each Pub/Sub message that is known to be small, in the
 * application's own handler where the limit is visible. A message over the
 * limit is skipped: the rest of that message is discarded as it arrives,
 * the next is not disturbed, and {@link #handleError} is told.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class SmallMessages implements MessageHandler {

    /** The largest message accepted, in bytes. */
    static final int LIMIT = 64 * 1024;

    private MessageHeader header;
    private ByteArrayOutputStream body;
    private boolean tooLarge;

    /** A message on a subscribed channel. */
    public void handleMessage(String channel, byte[] message) {
    }

    /** A message that matched a subscribed pattern. */
    public void handlePatternMessage(String pattern, String channel, byte[] message) {
    }

    @Override
    public ReplyFlow messageBegin(MessageHeader h, long length, ReplyStream stream) {
        if (length > LIMIT) {
            handleError("message larger than " + LIMIT + " bytes on " + h.getChannel());
            return ReplyFlow.SKIP;
        }
        header = h;
        body = new ByteArrayOutputStream();
        tooLarge = false;
        return ReplyFlow.CONTINUE;
    }

    @Override
    public ReplyFlow messageContent(ByteBuffer chunk) {
        if (body.size() + chunk.remaining() > LIMIT) {
            handleError("message larger than " + LIMIT + " bytes on " + header.getChannel());
            return ReplyFlow.SKIP;
        }
        byte[] bytes = new byte[chunk.remaining()];
        chunk.get(bytes);
        body.write(bytes, 0, bytes.length);
        return ReplyFlow.CONTINUE;
    }

    @Override
    public void messageEnd() {
        byte[] message = body.toByteArray();
        if (header.getPattern() != null) {
            handlePatternMessage(header.getPattern(), header.getChannel(), message);
        } else {
            handleMessage(header.getChannel(), message);
        }
    }
}
