/*
 * MessageParser.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Push-parser for the sections of an AMQP 1.0 message (core
 * specification 3.2), fed with the payload of a delivery in whatever
 * chunks the transfer frames arrive in.
 *
 * <p>A message is a sequence of described sections. This parser is a
 * small state machine:
 * <ul>
 *   <li><b>section start</b>: gathers a section's leading octets one at
 *       a time until it knows what the section is and how long;</li>
 *   <li><b>gathering</b>: for a small section, gathers exactly its
 *       remaining octets (bounded by {@code maxSectionSize}) and
 *       dispatches it decoded;</li>
 *   <li><b>data</b>: for a {@code data} section, forwards its octets to
 *       the handler as they arrive, never accumulating them.</li>
 * </ul>
 * Only the small sections are ever buffered, so a message with a very
 * large binary body uses constant memory. The one limitation is that an
 * {@code amqp-value} or {@code amqp-sequence} body is decoded whole and so
 * is bounded by {@code maxSectionSize}; a large binary payload should use
 * a {@code data} section.
 *
 * <p>Sections must appear in the order the specification requires
 * (header, delivery-annotations, message-annotations, properties,
 * application-properties, body, footer), each at most once, and a body
 * is either {@code data} sections, {@code amqp-sequence} sections or
 * exactly one {@code amqp-value}. Only numeric descriptors are supported.
 *
 * <p>Every call to {@link #receive} consumes its whole buffer. Call
 * {@link #endMessage()} after the last chunk of the delivery.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MessageHandler
 */
public final class MessageParser {

    public static final long SECTION_HEADER = 0x70;
    public static final long SECTION_DELIVERY_ANNOTATIONS = 0x71;
    public static final long SECTION_MESSAGE_ANNOTATIONS = 0x72;
    public static final long SECTION_PROPERTIES = 0x73;
    public static final long SECTION_APPLICATION_PROPERTIES = 0x74;
    public static final long SECTION_DATA = 0x75;
    public static final long SECTION_AMQP_SEQUENCE = 0x76;
    public static final long SECTION_AMQP_VALUE = 0x77;
    public static final long SECTION_FOOTER = 0x78;

    /** Default bound on the size of a section that is decoded whole: 1 MiB. */
    public static final int DEFAULT_MAX_SECTION_SIZE = 1048576;

    private enum State { SECTION, DATA, FAILED }

    // Order stages: a section may only move the message forwards
    private static final int STAGE_START = 0;
    private static final int STAGE_BODY = 6;
    private static final int STAGE_FOOTER = 7;

    private final MessageHandler handler;
    private final int maxSectionSize;
    private State state = State.SECTION;

    private byte[] gathered = new byte[64];
    private int gatheredLength;

    // Known once the section's descriptor has been decoded
    private boolean descriptorKnown;
    private long descriptor;
    private int descriptorEnd;
    private long dataRemaining;

    private int stage = STAGE_START;
    private long bodyKind; // descriptor of the first body section, or 0

    public MessageParser(MessageHandler handler) {
        this(handler, DEFAULT_MAX_SECTION_SIZE);
    }

    /**
     * @param handler receives the sections
     * @param maxSectionSize the largest section decoded whole; larger
     *      ones are a message error
     */
    public MessageParser(MessageHandler handler, int maxSectionSize) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.handler = handler;
        this.maxSectionSize = maxSectionSize;
    }

    /**
     * Consumes the next chunk of the message.
     *
     * @param chunk message octets; fully consumed on return
     */
    public void receive(ByteBuffer chunk) {
        while (chunk.hasRemaining() && state != State.FAILED) {
            if (state == State.DATA) {
                streamData(chunk);
            } else {
                gatherSection(chunk);
            }
        }
        if (state == State.FAILED) {
            chunk.position(chunk.limit());
        }
    }

    /**
     * Signals that the last octet of the message has been received.
     * Reports an error if it ended part way through a section, otherwise
     * calls {@link MessageHandler#endMessage()}.
     */
    public void endMessage() {
        if (state == State.FAILED) {
            return;
        }
        if (state == State.DATA || gatheredLength > 0) {
            fail("Message ends inside a section");
            return;
        }
        handler.endMessage();
    }

    // ── data sections ──

    private void streamData(ByteBuffer chunk) {
        int n = (int) Math.min(dataRemaining, chunk.remaining());
        int savedLimit = chunk.limit();
        chunk.limit(chunk.position() + n);
        ByteBuffer slice = chunk.slice();
        chunk.position(chunk.limit());
        chunk.limit(savedLimit);
        dataRemaining -= n;
        handler.dataChunk(slice);
        if (dataRemaining == 0) {
            state = State.SECTION;
            handler.endData();
        }
    }

    // ── section gathering ──

    private void gatherSection(ByteBuffer chunk) {
        try {
            while (chunk.hasRemaining() && state == State.SECTION) {
                int want = analyse();
                if (want == 0) {
                    return; // dispatched or switched to streaming
                }
                int n = Math.min(want, chunk.remaining());
                append(chunk, n);
            }
            if (state == State.SECTION) {
                analyse(); // the final octets may complete a section
            }
        } catch (Amqp1ProtocolException e) {
            fail(e.getMessage());
        }
    }

    /**
     * Works out what the gathered octets are.
     *
     * @return the number of further octets wanted, or 0 if the section
     *      was dispatched or a data section began streaming
     */
    private int analyse() throws Amqp1ProtocolException {
        if (gatheredLength == 0) {
            return 1;
        }
        ByteBuffer view = ByteBuffer.wrap(gathered, 0, gatheredLength);
        if (!descriptorKnown) {
            if ((gathered[0] & 0xFF) != Amqp1Types.DESCRIBED) {
                throw new Amqp1ProtocolException("Message section is not a described type");
            }
            view.position(1);
            long dlen = Amqp1Decoder.encodedLength(view);
            if (dlen < 0 || 1 + dlen > gatheredLength) {
                return 1;
            }
            descriptorEnd = 1 + (int) dlen;
            ByteBuffer d = ByteBuffer.wrap(gathered, 1, (int) dlen);
            Object value = Amqp1Decoder.read(d);
            if (!(value instanceof Long)) {
                throw new Amqp1ProtocolException("Unsupported message section descriptor " + value);
            }
            descriptor = ((Long) value).longValue();
            descriptorKnown = true;
            checkOrder(descriptor);
        }
        if (descriptor == SECTION_DATA) {
            return analyseData();
        }
        view.position(0);
        long total = Amqp1Decoder.encodedLength(view);
        if (total < 0) {
            return 1;
        }
        if (total > maxSectionSize) {
            throw new Amqp1ProtocolException("Message section of " + total
                    + " octets exceeds limit " + maxSectionSize);
        }
        if (gatheredLength < total) {
            return (int) total - gatheredLength;
        }
        dispatchSection();
        return 0;
    }

    /** A data section is a binary; once its length is known, stream it. */
    private int analyseData() throws Amqp1ProtocolException {
        if (gatheredLength <= descriptorEnd) {
            return 1;
        }
        int code = gathered[descriptorEnd] & 0xFF;
        int lengthOctets;
        if (code == Amqp1Types.BINARY8) {
            lengthOctets = 1;
        } else if (code == Amqp1Types.BINARY32) {
            lengthOctets = 4;
        } else {
            throw new Amqp1ProtocolException("data section is not a binary");
        }
        int headerLength = descriptorEnd + 1 + lengthOctets;
        if (gatheredLength < headerLength) {
            return headerLength - gatheredLength;
        }
        long length;
        if (lengthOctets == 1) {
            length = gathered[descriptorEnd + 1] & 0xFFL;
        } else {
            length = ByteBuffer.wrap(gathered, descriptorEnd + 1, 4).getInt() & 0xFFFFFFFFL;
        }
        resetSection();
        handler.startData(length);
        if (length == 0) {
            handler.endData();
        } else {
            dataRemaining = length;
            state = State.DATA;
        }
        return 0;
    }

    private void checkOrder(long d) throws Amqp1ProtocolException {
        int s;
        if (d == SECTION_HEADER) {
            s = 1;
        } else if (d == SECTION_DELIVERY_ANNOTATIONS) {
            s = 2;
        } else if (d == SECTION_MESSAGE_ANNOTATIONS) {
            s = 3;
        } else if (d == SECTION_PROPERTIES) {
            s = 4;
        } else if (d == SECTION_APPLICATION_PROPERTIES) {
            s = 5;
        } else if (d == SECTION_DATA || d == SECTION_AMQP_SEQUENCE || d == SECTION_AMQP_VALUE) {
            s = STAGE_BODY;
        } else if (d == SECTION_FOOTER) {
            s = STAGE_FOOTER;
        } else {
            throw new Amqp1ProtocolException("Unknown message section 0x" + Long.toHexString(d));
        }
        if (s == STAGE_BODY) {
            if (stage > STAGE_BODY) {
                throw new Amqp1ProtocolException("Body section after the footer");
            }
            if (bodyKind == 0) {
                bodyKind = d;
            } else if (bodyKind != d || d == SECTION_AMQP_VALUE) {
                throw new Amqp1ProtocolException(d == SECTION_AMQP_VALUE && bodyKind == d
                        ? "More than one amqp-value section"
                        : "Mixed body section types");
            }
        } else if (s <= stage) {
            throw new Amqp1ProtocolException("Message section 0x" + Long.toHexString(d)
                    + " out of order or repeated");
        }
        stage = s;
    }

    @SuppressWarnings("unchecked")
    private void dispatchSection() throws Amqp1ProtocolException {
        Object value = Amqp1Decoder.read(ByteBuffer.wrap(gathered, 0, gatheredLength));
        long d = descriptor;
        resetSection();
        Object inner = ((Amqp1Described) value).getValue();
        if (d == SECTION_HEADER) {
            handler.header(MessageHeader.fromFields(asList(inner, "header")));
        } else if (d == SECTION_PROPERTIES) {
            handler.properties(MessageProperties.fromFields(asList(inner, "properties")));
        } else if (d == SECTION_AMQP_VALUE) {
            handler.amqpValue(inner);
        } else if (d == SECTION_AMQP_SEQUENCE) {
            handler.amqpSequence(asList(inner, "amqp-sequence"));
        } else {
            Map<Object, Object> map = asMap(inner, d);
            if (d == SECTION_DELIVERY_ANNOTATIONS) {
                handler.deliveryAnnotations(map);
            } else if (d == SECTION_MESSAGE_ANNOTATIONS) {
                handler.messageAnnotations(map);
            } else if (d == SECTION_APPLICATION_PROPERTIES) {
                handler.applicationProperties(map);
            } else {
                handler.footer(map);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object v, String name) throws Amqp1ProtocolException {
        if (v instanceof List) {
            return (List<Object>) v;
        }
        throw new Amqp1ProtocolException("The " + name + " section is not a list");
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asMap(Object v, long d) throws Amqp1ProtocolException {
        if (v instanceof Map) {
            return (Map<Object, Object>) v;
        }
        if (v == null) {
            return new LinkedHashMap<Object, Object>();
        }
        throw new Amqp1ProtocolException("Message section 0x" + Long.toHexString(d)
                + " is not a map");
    }

    private void resetSection() {
        gatheredLength = 0;
        descriptorKnown = false;
    }

    private void append(ByteBuffer chunk, int n) throws Amqp1ProtocolException {
        if (gatheredLength + n > maxSectionSize) {
            throw new Amqp1ProtocolException("Message section exceeds limit " + maxSectionSize);
        }
        if (gatheredLength + n > gathered.length) {
            byte[] bigger = new byte[Math.max(gatheredLength + n, gathered.length * 2)];
            System.arraycopy(gathered, 0, bigger, 0, gatheredLength);
            gathered = bigger;
        }
        chunk.get(gathered, gatheredLength, n);
        gatheredLength += n;
    }

    private void fail(String message) {
        state = State.FAILED;
        handler.messageError(message);
    }
}
