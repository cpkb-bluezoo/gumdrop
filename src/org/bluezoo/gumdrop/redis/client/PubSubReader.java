/*
 * PubSubReader.java
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
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespEventHandler;
import org.bluezoo.gumdrop.redis.codec.RespType;

/**
 * Reads what arrives on a connection that is, or may be, subscribed: the
 * Pub/Sub deliveries and the confirmations of subscriptions, and the pushes
 * of RESP3. On a RESP2 connection a delivery is an array like the reply to
 * a command, so what an array is can be told only from its first element;
 * that is read (it is short), and the rest of the array goes to wherever it
 * belongs, as the events arrive.
 *
 * <p>A message is told to the {@link MessageHandler} as it is read: its
 * channel and pattern whole, bounded by the maximum field length, and its
 * body as chunks. A message with a name too long is dropped and reported,
 * and the next is read as if it had not been. A push that is not a
 * delivery goes to the {@link PushHandler} as events, and a reply that is
 * not a delivery (the answer to a {@code PING} on a subscribed connection,
 * say) to the command it answers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class PubSubReader implements RespEventHandler {

    /** What the reader needs of the connection. */
    interface Host {

        /** The handler of the connection's subscriptions, or null. */
        MessageHandler messageHandler();

        /** The handler of pushes that are not deliveries, or null. */
        PushHandler pushHandler();

        /** The longest identifier to read whole. */
        int maxField();

        /** The handle given to handlers, to resume reading or give up a message. */
        ReplyStream stream();

        /**
         * What was being read is the answer to the command at the head of
         * the queue after all. The returned handler is given what has been
         * read so far, and then what follows.
         */
        RespEventHandler becomeReply();

        /** A subscription was confirmed or ended. */
        void acknowledged(String kind, String name, int count);
    }

    private static final int HEAD = 0;
    private static final int KIND = 1;
    private static final int DELIVERY = 2;
    private static final int ACK = 3;
    private static final int PUSH = 4;
    private static final int REPLY = 5;
    private static final int DROP = 6;

    private final Host host;
    private int phase;
    private boolean push;
    private int depth;

    // the aggregate that holds the delivery, as announced
    private RespType aggregateKind;
    private long aggregateLength;

    // the kind of the delivery, its first element
    private String kind;
    private boolean kindSimple;

    // collecting an identifier or the kind
    private byte[] text = new byte[64];
    private int textLength;
    private boolean overflow;
    private boolean collecting;

    // a delivery: the names read, and where the body is
    private MessageHandler messages;
    private int idsNeeded;
    private int idsRead;
    private final String[] ids = new String[2];
    private boolean inBody;

    // a confirmation
    private int ackElement;
    private String ackName;
    private long ackCount;
    private boolean ackHasCount;

    private PushHandler pushes;
    private RespEventHandler sink;
    private volatile boolean cancelRequested;

    PubSubReader(Host host) {
        this.host = host;
    }

    /** Gives up the message being read; thread-safe. */
    void requestCancel() {
        cancelRequested = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The reply
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void beginReply(boolean isPush) {
        phase = HEAD;
        push = isPush;
        depth = 0;
        cancelRequested = false;
        collecting = false;
        inBody = false;
        sink = null;
        pushes = null;
        messages = host.messageHandler();
    }

    @Override
    public void endReply(boolean skipped) {
        if (phase == PUSH && pushes != null) {
            pushes.endReply(skipped);
        } else if (phase == REPLY) {
            sink.endReply(skipped);
        } else if (phase == HEAD) {
            // nothing came of it
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deciding what the reply is
    // ─────────────────────────────────────────────────────────────────────────

    // Replays the start of the aggregate and its kind to a handler.
    private void replayStart(RespEventHandler to) {
        to.beginAggregate(aggregateKind, aggregateLength);
        if (kind != null) {
            if (kindSimple) {
                to.simpleString(kind);
            } else {
                byte[] bytes = kind.getBytes(StandardCharsets.UTF_8);
                to.beginString(RespType.BULK_STRING, bytes.length, null);
                if (bytes.length > 0) {
                    to.stringChunk(ByteBuffer.wrap(bytes));
                }
                to.endString();
            }
        }
    }

    // The reply is not a delivery. A push is dropped; anything else answers
    // the command at the head of the queue, and is given what has been read.
    private RespEventHandler divert() {
        if (push) {
            phase = DROP;
            return null;
        }
        sink = host.becomeReply();
        boolean started = phase == KIND || depth > 0;
        phase = REPLY;
        if (started) {
            replayStart(sink);
        }
        return sink;
    }

    private void decide() {
        String k = kind;
        MessageHandler mh = messages;
        if (mh != null && depth == 1) {
            if ("message".equals(k) || "smessage".equals(k)) {
                idsNeeded = 1;
                startDelivery();
                return;
            } else if ("pmessage".equals(k)) {
                idsNeeded = 2;
                startDelivery();
                return;
            } else if ("subscribe".equals(k) || "psubscribe".equals(k) || "ssubscribe".equals(k)
                    || "unsubscribe".equals(k) || "punsubscribe".equals(k) || "sunsubscribe".equals(k)) {
                phase = ACK;
                ackElement = 0;
                ackName = null;
                ackHasCount = false;
                return;
            }
        }
        if (push) {
            PushHandler ph = host.pushHandler();
            if (ph == null) {
                phase = DROP;
                return;
            }
            pushes = ph;
            phase = PUSH;
            ph.handleStream(host.stream());
            ph.beginReply(true);
            replayStart(ph);
            return;
        }
        divert();
    }

    private void startDelivery() {
        phase = DELIVERY;
        idsRead = 0;
        inBody = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text: the kind and the identifiers
    // ─────────────────────────────────────────────────────────────────────────

    private void startText(long length) {
        textLength = 0;
        overflow = length > host.maxField();
        collecting = true;
    }

    private void addText(ByteBuffer chunk) {
        int n = chunk.remaining();
        if (!overflow && (long) textLength + n > host.maxField()) {
            overflow = true;
        }
        if (overflow) {
            chunk.position(chunk.limit());
            return;
        }
        if (textLength + n > text.length) {
            byte[] grown = new byte[Math.max(textLength + n, text.length * 2)];
            System.arraycopy(text, 0, grown, 0, textLength);
            text = grown;
        }
        chunk.get(text, textLength, n);
        textLength += n;
    }

    private String text() {
        collecting = false;
        return new String(text, 0, textLength, StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Elements
    // ─────────────────────────────────────────────────────────────────────────

    private ReplyFlow drop() {
        phase = DROP;
        return ReplyFlow.SKIP;
    }

    // An identifier of a delivery is too long: the message is dropped.
    private ReplyFlow tooLong() {
        MessageHandler mh = messages;
        phase = DROP;
        if (mh != null) {
            mh.handleError(MessageFormat.format(
                    RedisClientProtocolHandler.L10N.getString("err.identifier_too_long"),
                    Integer.valueOf(textLength), Integer.valueOf(host.maxField())));
        }
        return ReplyFlow.SKIP;
    }

    // A whole element that is text: a simple string, or a number as text.
    private ReplyFlow scalarText(String value) {
        switch (phase) {
            case HEAD:
                return headScalar(value);
            case KIND:
                if (depth == 1) {
                    kind = value;
                    kindSimple = true;
                    decideAfterKind();
                    return phase == DROP ? ReplyFlow.SKIP : ReplyFlow.CONTINUE;
                }
                return drop();
            case DELIVERY:
                return deliveryElement(value);
            case ACK:
                return ackValue(value, false);
            default:
                return ReplyFlow.SKIP;
        }
    }

    private void decideAfterKind() {
        decide();
    }

    // A top-level element that is not an aggregate.
    private ReplyFlow headScalar(String value) {
        RespEventHandler s = divert();
        if (s == null) {
            return ReplyFlow.SKIP;
        }
        return s.simpleString(value);
    }

    private ReplyFlow deliveryElement(String value) {
        if (depth != 1) {
            return drop();
        }
        if (idsRead < idsNeeded) {
            ids[idsRead++] = value;
            return ReplyFlow.CONTINUE;
        }
        // a body that is a short scalar is delivered as its text
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ReplyFlow begin = beginMessage(bytes.length);
        if (begin == ReplyFlow.SKIP) {
            return begin;
        }
        ReplyFlow body = ReplyFlow.CONTINUE;
        if (bytes.length > 0) {
            body = messages.messageContent(ByteBuffer.wrap(bytes));
            if (body == ReplyFlow.SKIP) {
                return body;
            }
        }
        messages.messageEnd();
        inBody = false;
        return begin == ReplyFlow.PAUSE || body == ReplyFlow.PAUSE ? ReplyFlow.PAUSE : ReplyFlow.CONTINUE;
    }

    private ReplyFlow beginMessage(long length) {
        MessageHeader header;
        if (idsNeeded == 2) {
            header = new MessageHeader(MessageKind.PATTERN, ids[0], ids[1]);
        } else {
            header = new MessageHeader("smessage".equals(kind) ? MessageKind.SHARD : MessageKind.MESSAGE,
                    null, ids[0]);
        }
        inBody = true;
        ReplyFlow flow = messages.messageBegin(header, length, host.stream());
        if (flow == ReplyFlow.SKIP) {
            phase = DROP;
        }
        return flow;
    }

    private ReplyFlow ackValue(String value, boolean number) {
        if (depth != 1) {
            return drop();
        }
        if (ackElement == 0) {
            ackName = value;
        } else if (ackElement == 1) {
            try {
                ackCount = Long.parseLong(value);
                ackHasCount = true;
            } catch (NumberFormatException e) {
                // the server's own count is the one thing the accounting
                // cannot do without: this is not a server to trust
                throw new IllegalStateException("subscription count is not an integer: " + value, e);
            }
        }
        ackElement++;
        return ReplyFlow.CONTINUE;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public ReplyFlow beginAggregate(RespType type, long length) {
        switch (phase) {
            case HEAD:
                if (depth == 0 && (type == RespType.ARRAY || type == RespType.PUSH)) {
                    aggregateKind = type;
                    aggregateLength = length;
                    kind = null;
                    depth = 1;
                    phase = KIND;
                    return ReplyFlow.CONTINUE;
                }
                RespEventHandler s = divert();
                return s == null ? ReplyFlow.SKIP : s.beginAggregate(type, length);
            case KIND:
                {
                    // the first element is not text, so this is not a delivery
                    RespEventHandler to = divert();
                    return to == null ? ReplyFlow.SKIP : to.beginAggregate(type, length);
                }
            case PUSH:
                depth++;
                return flow(pushes.beginAggregate(type, length));
            case REPLY:
                depth++;
                return sink.beginAggregate(type, length);
            case DELIVERY:
            case ACK:
                return drop();
            default:
                return ReplyFlow.SKIP;
        }
    }

    @Override
    public ReplyFlow endAggregate() {
        switch (phase) {
            case PUSH:
                depth--;
                return flow(pushes.endAggregate());
            case REPLY:
                depth--;
                return sink.endAggregate();
            case ACK:
                depth--;
                if (depth == 0 && ackHasCount) {
                    host.acknowledged(kind, ackName, (int) ackCount);
                }
                return ReplyFlow.CONTINUE;
            case DELIVERY:
                depth--;
                return ReplyFlow.CONTINUE;
            default:
                return ReplyFlow.SKIP;
        }
    }

    private ReplyFlow flow(ReplyFlow f) {
        return cancelRequested ? ReplyFlow.SKIP : f;
    }

    @Override
    public ReplyFlow nullValue() {
        switch (phase) {
            case HEAD:
            case KIND: {
                RespEventHandler to = divert();
                return to == null ? ReplyFlow.SKIP : to.nullValue();
            }
            case PUSH:
                return flow(pushes.nullValue());
            case REPLY:
                return sink.nullValue();
            case ACK:
                if (depth == 1 && ackElement == 0) {
                    ackName = null;
                    ackElement++;
                    return ReplyFlow.CONTINUE;
                }
                return drop();
            case DELIVERY:
                if (depth == 1 && idsRead >= idsNeeded) {
                    // a null body is an empty message
                    return deliveryElement("");
                }
                return drop();
            default:
                return ReplyFlow.SKIP;
        }
    }

    @Override
    public ReplyFlow integer(long value) {
        switch (phase) {
            case HEAD:
            case KIND: {
                RespEventHandler to = divert();
                return to == null ? ReplyFlow.SKIP : to.integer(value);
            }
            case PUSH:
                return flow(pushes.integer(value));
            case REPLY:
                return sink.integer(value);
            case ACK:
            case DELIVERY:
                return scalarText(Long.toString(value));
            default:
                return ReplyFlow.SKIP;
        }
    }

    @Override
    public ReplyFlow doubleValue(double value) {
        switch (phase) {
            case HEAD:
            case KIND: {
                RespEventHandler to = divert();
                return to == null ? ReplyFlow.SKIP : to.doubleValue(value);
            }
            case PUSH:
                return flow(pushes.doubleValue(value));
            case REPLY:
                return sink.doubleValue(value);
            case DELIVERY:
                return scalarText(Double.toString(value));
            default:
                return phase == ACK ? drop() : ReplyFlow.SKIP;
        }
    }

    @Override
    public ReplyFlow booleanValue(boolean value) {
        switch (phase) {
            case HEAD:
            case KIND: {
                RespEventHandler to = divert();
                return to == null ? ReplyFlow.SKIP : to.booleanValue(value);
            }
            case PUSH:
                return flow(pushes.booleanValue(value));
            case REPLY:
                return sink.booleanValue(value);
            case DELIVERY:
                return scalarText(value ? "1" : "0");
            default:
                return phase == ACK ? drop() : ReplyFlow.SKIP;
        }
    }

    @Override
    public ReplyFlow bigNumber(String digits) {
        switch (phase) {
            case HEAD:
            case KIND: {
                RespEventHandler to = divert();
                return to == null ? ReplyFlow.SKIP : to.bigNumber(digits);
            }
            case PUSH:
                return flow(pushes.bigNumber(digits));
            case REPLY:
                return sink.bigNumber(digits);
            case DELIVERY:
                return scalarText(digits);
            default:
                return phase == ACK ? drop() : ReplyFlow.SKIP;
        }
    }

    @Override
    public ReplyFlow simpleString(String value) {
        switch (phase) {
            case PUSH:
                return flow(pushes.simpleString(value));
            case REPLY:
                return sink.simpleString(value);
            case HEAD:
            case KIND:
            case DELIVERY:
            case ACK:
                return scalarText(value);
            default:
                return ReplyFlow.SKIP;
        }
    }

    @Override
    public ReplyFlow error(String message) {
        switch (phase) {
            case HEAD:
            case KIND: {
                RespEventHandler to = divert();
                return to == null ? ReplyFlow.SKIP : to.error(message);
            }
            case PUSH:
                return flow(pushes.error(message));
            case REPLY:
                return sink.error(message);
            default:
                return drop();
        }
    }

    @Override
    public ReplyFlow beginString(RespType type, long length, String format) {
        switch (phase) {
            case HEAD: {
                RespEventHandler to = divert();
                return to == null ? ReplyFlow.SKIP : to.beginString(type, length, format);
            }
            case KIND:
                startText(length);
                return ReplyFlow.CONTINUE;
            case PUSH:
                return flow(pushes.beginString(type, length, format));
            case REPLY:
                return sink.beginString(type, length, format);
            case ACK:
                if (depth == 1 && ackElement <= 1) {
                    startText(length);
                    return ReplyFlow.CONTINUE;
                }
                return drop();
            case DELIVERY:
                if (depth != 1 || type == RespType.BLOB_ERROR) {
                    return drop();
                }
                if (idsRead < idsNeeded) {
                    startText(length);
                    return overflow ? tooLong() : ReplyFlow.CONTINUE;
                }
                return beginMessage(length);
            default:
                return ReplyFlow.SKIP;
        }
    }

    @Override
    public ReplyFlow stringChunk(ByteBuffer chunk) {
        switch (phase) {
            case KIND:
                addText(chunk);
                return ReplyFlow.CONTINUE;
            case PUSH:
                return flow(pushes.stringChunk(chunk));
            case REPLY:
                return sink.stringChunk(chunk);
            case ACK:
                addText(chunk);
                return ReplyFlow.CONTINUE;
            case DELIVERY:
                if (collecting) {
                    addText(chunk);
                    return overflow ? tooLong() : ReplyFlow.CONTINUE;
                }
                if (cancelRequested) {
                    return drop();
                }
                {
                    ReplyFlow f = messages.messageContent(chunk);
                    if (f == ReplyFlow.SKIP) {
                        phase = DROP;
                    }
                    return f;
                }
            default:
                return ReplyFlow.SKIP;
        }
    }

    @Override
    public ReplyFlow endString() {
        switch (phase) {
            case KIND:
                if (overflow) {
                    return drop();
                }
                kind = text();
                kindSimple = false;
                decideAfterKind();
                return phase == DROP ? ReplyFlow.SKIP : ReplyFlow.CONTINUE;
            case PUSH:
                return flow(pushes.endString());
            case REPLY:
                return sink.endString();
            case ACK:
                if (overflow) {
                    return drop();
                }
                return ackValue(text(), false);
            case DELIVERY:
                if (collecting) {
                    if (overflow) {
                        return tooLong();
                    }
                    ids[idsRead++] = text();
                    return ReplyFlow.CONTINUE;
                }
                inBody = false;
                if (cancelRequested) {
                    return drop();
                }
                messages.messageEnd();
                return ReplyFlow.CONTINUE;
            default:
                return ReplyFlow.SKIP;
        }
    }

}
