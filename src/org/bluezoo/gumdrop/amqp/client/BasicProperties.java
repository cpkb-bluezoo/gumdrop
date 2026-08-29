/*
 * BasicProperties.java
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

package org.bluezoo.gumdrop.amqp.client;

import java.nio.ByteBuffer;
import java.util.Date;

/**
 * AMQP 0-9-1 {@code basic} class properties (content-header, class ID 60).
 *
 * <p>All properties are optional; only those actually set are encoded,
 * governed by a leading property-flags bitmask (AMQP 0-9-1 §4.2.6.1 /
 * §2.3.5's content-header-frame). Construct via the fluent {@code with*}
 * setters, e.g.:
 * <pre>{@code
 * BasicProperties props = new BasicProperties()
 *         .withContentType("application/json")
 *         .withDeliveryMode((byte) 2)  // persistent
 *         .withMessageId(UUID.randomUUID().toString());
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rabbitmq.com/resources/specs/amqp0-9-1.pdf">AMQP 0-9-1 specification</a>
 */
public final class BasicProperties {

    /** Class ID for the {@code basic} class, used in the content-header frame. */
    public static final int CLASS_ID = 60;

    // Property-flags bits, MSB-first as encoded on the wire (bit 15 down to bit 2 used).
    private static final int FLAG_CONTENT_TYPE = 0x8000;
    private static final int FLAG_CONTENT_ENCODING = 0x4000;
    private static final int FLAG_HEADERS = 0x2000;
    private static final int FLAG_DELIVERY_MODE = 0x1000;
    private static final int FLAG_PRIORITY = 0x0800;
    private static final int FLAG_CORRELATION_ID = 0x0400;
    private static final int FLAG_REPLY_TO = 0x0200;
    private static final int FLAG_EXPIRATION = 0x0100;
    private static final int FLAG_MESSAGE_ID = 0x0080;
    private static final int FLAG_TIMESTAMP = 0x0040;
    private static final int FLAG_TYPE = 0x0020;
    private static final int FLAG_USER_ID = 0x0010;
    private static final int FLAG_APP_ID = 0x0008;
    private static final int FLAG_RESERVED = 0x0004;
    /** Continuation bit: another 16-bit flags word follows (never set for basic — 14 props fit in one). */
    private static final int FLAG_CONTINUATION = 0x0001;

    private String contentType;
    private String contentEncoding;
    private FieldTable headers;
    private Byte deliveryMode;
    private Byte priority;
    private String correlationId;
    private String replyTo;
    private String expiration;
    private String messageId;
    private Date timestamp;
    private String type;
    private String userId;
    private String appId;
    private String reserved; // historically "cluster-id"

    public BasicProperties withContentType(String v) { this.contentType = v; return this; }
    public BasicProperties withContentEncoding(String v) { this.contentEncoding = v; return this; }
    public BasicProperties withHeaders(FieldTable v) { this.headers = v; return this; }
    public BasicProperties withDeliveryMode(byte v) { this.deliveryMode = v; return this; }
    public BasicProperties withPriority(byte v) { this.priority = v; return this; }
    public BasicProperties withCorrelationId(String v) { this.correlationId = v; return this; }
    public BasicProperties withReplyTo(String v) { this.replyTo = v; return this; }
    public BasicProperties withExpiration(String v) { this.expiration = v; return this; }
    public BasicProperties withMessageId(String v) { this.messageId = v; return this; }
    public BasicProperties withTimestamp(Date v) { this.timestamp = v; return this; }
    public BasicProperties withType(String v) { this.type = v; return this; }
    public BasicProperties withUserId(String v) { this.userId = v; return this; }
    public BasicProperties withAppId(String v) { this.appId = v; return this; }
    public BasicProperties withReserved(String v) { this.reserved = v; return this; }

    public String getContentType() { return contentType; }
    public String getContentEncoding() { return contentEncoding; }
    public FieldTable getHeaders() { return headers; }
    public Byte getDeliveryMode() { return deliveryMode; }
    public Byte getPriority() { return priority; }
    public String getCorrelationId() { return correlationId; }
    public String getReplyTo() { return replyTo; }
    public String getExpiration() { return expiration; }
    public String getMessageId() { return messageId; }
    public Date getTimestamp() { return timestamp; }
    public String getType() { return type; }
    public String getUserId() { return userId; }
    public String getAppId() { return appId; }
    public String getReserved() { return reserved; }

    /**
     * Decoded content-header frame: the body size the following content-body
     * frame(s) will total, plus the properties.
     */
    public static final class Header {
        private final long bodySize;
        private final BasicProperties properties;

        Header(long bodySize, BasicProperties properties) {
            this.bodySize = bodySize;
            this.properties = properties;
        }

        public long getBodySize() { return bodySize; }
        public BasicProperties getProperties() { return properties; }
    }

    /**
     * Encodes this as a complete content-header frame payload (class-id,
     * weight, body-size, property-flags, property-list) — everything that
     * goes inside an {@link AMQPFrame#TYPE_HEADER} frame's payload.
     *
     * @param bodySize total size in bytes of the message body that will
     *      follow in one or more content-body frames
     */
    public ByteBuffer encode(long bodySize) {
        int flags = flags();

        // Encode each set string property once here, up front, and reuse
        // the resulting bytes for both sizing (propertyListSize) and
        // writing below -- rather than deriving the size from the string
        // and separately re-encoding it to write, as issue #310 found.
        // headers.encode() is similarly called just once, its result
        // reused for both its length prefix and its bytes.
        byte[] contentTypeBytes = contentType != null ? FieldTable.utf8Bytes(contentType) : null;
        byte[] contentEncodingBytes = contentEncoding != null ? FieldTable.utf8Bytes(contentEncoding) : null;
        ByteBuffer encodedHeaders = headers != null ? headers.encode() : null;
        byte[] correlationIdBytes = correlationId != null ? FieldTable.utf8Bytes(correlationId) : null;
        byte[] replyToBytes = replyTo != null ? FieldTable.utf8Bytes(replyTo) : null;
        byte[] expirationBytes = expiration != null ? FieldTable.utf8Bytes(expiration) : null;
        byte[] messageIdBytes = messageId != null ? FieldTable.utf8Bytes(messageId) : null;
        byte[] typeBytes = type != null ? FieldTable.utf8Bytes(type) : null;
        byte[] userIdBytes = userId != null ? FieldTable.utf8Bytes(userId) : null;
        byte[] appIdBytes = appId != null ? FieldTable.utf8Bytes(appId) : null;
        byte[] reservedBytes = reserved != null ? FieldTable.utf8Bytes(reserved) : null;

        int size = 2 + 2 + 8 + 2 + propertyListSize(contentTypeBytes, contentEncodingBytes, encodedHeaders,
                correlationIdBytes, replyToBytes, expirationBytes, messageIdBytes, typeBytes, userIdBytes,
                appIdBytes, reservedBytes);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) CLASS_ID);
        buf.putShort((short) 0); // weight, always 0
        buf.putLong(bodySize);
        buf.putShort((short) flags);
        if (contentTypeBytes != null) {
            FieldTable.putShortString(buf, contentTypeBytes);
        }
        if (contentEncodingBytes != null) {
            FieldTable.putShortString(buf, contentEncodingBytes);
        }
        if (encodedHeaders != null) {
            buf.putInt(encodedHeaders.remaining());
            buf.put(encodedHeaders);
        }
        if (deliveryMode != null) {
            buf.put(deliveryMode);
        }
        if (priority != null) {
            buf.put(priority);
        }
        if (correlationIdBytes != null) {
            FieldTable.putShortString(buf, correlationIdBytes);
        }
        if (replyToBytes != null) {
            FieldTable.putShortString(buf, replyToBytes);
        }
        if (expirationBytes != null) {
            FieldTable.putShortString(buf, expirationBytes);
        }
        if (messageIdBytes != null) {
            FieldTable.putShortString(buf, messageIdBytes);
        }
        if (timestamp != null) {
            buf.putLong(timestamp.getTime() / 1000L);
        }
        if (typeBytes != null) {
            FieldTable.putShortString(buf, typeBytes);
        }
        if (userIdBytes != null) {
            FieldTable.putShortString(buf, userIdBytes);
        }
        if (appIdBytes != null) {
            FieldTable.putShortString(buf, appIdBytes);
        }
        if (reservedBytes != null) {
            FieldTable.putShortString(buf, reservedBytes);
        }
        buf.flip();
        return buf;
    }

    private int flags() {
        int flags = 0;
        if (contentType != null) {
            flags |= FLAG_CONTENT_TYPE;
        }
        if (contentEncoding != null) {
            flags |= FLAG_CONTENT_ENCODING;
        }
        if (headers != null) {
            flags |= FLAG_HEADERS;
        }
        if (deliveryMode != null) {
            flags |= FLAG_DELIVERY_MODE;
        }
        if (priority != null) {
            flags |= FLAG_PRIORITY;
        }
        if (correlationId != null) {
            flags |= FLAG_CORRELATION_ID;
        }
        if (replyTo != null) {
            flags |= FLAG_REPLY_TO;
        }
        if (expiration != null) {
            flags |= FLAG_EXPIRATION;
        }
        if (messageId != null) {
            flags |= FLAG_MESSAGE_ID;
        }
        if (timestamp != null) {
            flags |= FLAG_TIMESTAMP;
        }
        if (type != null) {
            flags |= FLAG_TYPE;
        }
        if (userId != null) {
            flags |= FLAG_USER_ID;
        }
        if (appId != null) {
            flags |= FLAG_APP_ID;
        }
        if (reserved != null) {
            flags |= FLAG_RESERVED;
        }
        return flags;
    }

    // Takes each property's already-encoded UTF-8 bytes (or null if the
    // property is unset), rather than the String itself, so sizing never
    // re-encodes what encode() already encoded once (issue #310).
    private int propertyListSize(byte[] contentTypeBytes, byte[] contentEncodingBytes, ByteBuffer encodedHeaders,
            byte[] correlationIdBytes, byte[] replyToBytes, byte[] expirationBytes, byte[] messageIdBytes,
            byte[] typeBytes, byte[] userIdBytes, byte[] appIdBytes, byte[] reservedBytes) {
        int size = 0;
        if (contentTypeBytes != null) {
            size += FieldTable.shortStringEncodedSize(contentTypeBytes);
        }
        if (contentEncodingBytes != null) {
            size += FieldTable.shortStringEncodedSize(contentEncodingBytes);
        }
        if (encodedHeaders != null) {
            size += 4 + encodedHeaders.remaining();
        }
        if (deliveryMode != null) {
            size += 1;
        }
        if (priority != null) {
            size += 1;
        }
        if (correlationIdBytes != null) {
            size += FieldTable.shortStringEncodedSize(correlationIdBytes);
        }
        if (replyToBytes != null) {
            size += FieldTable.shortStringEncodedSize(replyToBytes);
        }
        if (expirationBytes != null) {
            size += FieldTable.shortStringEncodedSize(expirationBytes);
        }
        if (messageIdBytes != null) {
            size += FieldTable.shortStringEncodedSize(messageIdBytes);
        }
        if (timestamp != null) {
            size += 8;
        }
        if (typeBytes != null) {
            size += FieldTable.shortStringEncodedSize(typeBytes);
        }
        if (userIdBytes != null) {
            size += FieldTable.shortStringEncodedSize(userIdBytes);
        }
        if (appIdBytes != null) {
            size += FieldTable.shortStringEncodedSize(appIdBytes);
        }
        if (reservedBytes != null) {
            size += FieldTable.shortStringEncodedSize(reservedBytes);
        }
        return size;
    }

    /**
     * Decodes a complete content-header frame payload (as delivered inside
     * an {@link AMQPFrame#TYPE_HEADER} frame).
     */
    public static Header decode(ByteBuffer buf) throws AMQPProtocolException {
        if (buf.remaining() < 14) {
            throw new AMQPProtocolException("Truncated content-header frame");
        }
        int classId = buf.getShort() & 0xFFFF;
        if (classId != CLASS_ID) {
            throw new AMQPProtocolException(
                    "Unsupported content-header class-id " + classId + " (only 'basic' (60) is supported)");
        }
        buf.getShort(); // weight, ignored
        long bodySize = buf.getLong();

        int flags = buf.getShort() & 0xFFFF;
        while ((flags & FLAG_CONTINUATION) != 0) {
            // Reserved for future extensibility; basic never sets this today.
            flags = buf.getShort() & 0xFFFF;
        }

        BasicProperties props = new BasicProperties();
        if ((flags & FLAG_CONTENT_TYPE) != 0) {
            props.contentType = FieldTable.getShortString(buf);
        }
        if ((flags & FLAG_CONTENT_ENCODING) != 0) {
            props.contentEncoding = FieldTable.getShortString(buf);
        }
        if ((flags & FLAG_HEADERS) != 0) {
            int len = buf.getInt();
            props.headers = FieldTable.decode(buf, len);
        }
        if ((flags & FLAG_DELIVERY_MODE) != 0) {
            props.deliveryMode = buf.get();
        }
        if ((flags & FLAG_PRIORITY) != 0) {
            props.priority = buf.get();
        }
        if ((flags & FLAG_CORRELATION_ID) != 0) {
            props.correlationId = FieldTable.getShortString(buf);
        }
        if ((flags & FLAG_REPLY_TO) != 0) {
            props.replyTo = FieldTable.getShortString(buf);
        }
        if ((flags & FLAG_EXPIRATION) != 0) {
            props.expiration = FieldTable.getShortString(buf);
        }
        if ((flags & FLAG_MESSAGE_ID) != 0) {
            props.messageId = FieldTable.getShortString(buf);
        }
        if ((flags & FLAG_TIMESTAMP) != 0) {
            props.timestamp = new Date(buf.getLong() * 1000L);
        }
        if ((flags & FLAG_TYPE) != 0) {
            props.type = FieldTable.getShortString(buf);
        }
        if ((flags & FLAG_USER_ID) != 0) {
            props.userId = FieldTable.getShortString(buf);
        }
        if ((flags & FLAG_APP_ID) != 0) {
            props.appId = FieldTable.getShortString(buf);
        }
        if ((flags & FLAG_RESERVED) != 0) {
            props.reserved = FieldTable.getShortString(buf);
        }
        return new Header(bodySize, props);
    }
}
