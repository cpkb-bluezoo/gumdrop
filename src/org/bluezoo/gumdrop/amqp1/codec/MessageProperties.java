/*
 * MessageProperties.java
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

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * The {@code properties} message section (core specification 3.2.4):
 * immutable, standard properties of the message.
 *
 * <p>A message-id or correlation-id is one of {@link Long} (an AMQP
 * {@code ulong}), {@link UUID}, {@code byte[]} or {@link String}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MessageProperties {

    private Object messageId;
    private byte[] userId;
    private String to;
    private String subject;
    private String replyTo;
    private Object correlationId;
    private String contentType;
    private String contentEncoding;
    private Date absoluteExpiryTime;
    private Date creationTime;
    private String groupId;
    private Long groupSequence;
    private String replyToGroupId;

    public Object getMessageId() {
        return messageId;
    }

    public void setMessageId(Object messageId) {
        this.messageId = messageId;
    }

    /** The identity of the user that sent the message, as an opaque binary; or {@code null}. */
    public byte[] getUserId() {
        return userId;
    }

    public void setUserId(byte[] userId) {
        this.userId = userId;
    }

    /** The address of the node the message is destined for, or {@code null}. */
    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }

    public Object getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(Object correlationId) {
        this.correlationId = correlationId;
    }

    /** The MIME content type of the body, for example {@code text/plain}. */
    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getContentEncoding() {
        return contentEncoding;
    }

    public void setContentEncoding(String contentEncoding) {
        this.contentEncoding = contentEncoding;
    }

    public Date getAbsoluteExpiryTime() {
        return absoluteExpiryTime;
    }

    public void setAbsoluteExpiryTime(Date absoluteExpiryTime) {
        this.absoluteExpiryTime = absoluteExpiryTime;
    }

    public Date getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Date creationTime) {
        this.creationTime = creationTime;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Long getGroupSequence() {
        return groupSequence;
    }

    public void setGroupSequence(Long groupSequence) {
        this.groupSequence = groupSequence;
    }

    public String getReplyToGroupId() {
        return replyToGroupId;
    }

    public void setReplyToGroupId(String replyToGroupId) {
        this.replyToGroupId = replyToGroupId;
    }

    void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addMessageId(messageId)
                .addBinary(userId)
                .addString(to)
                .addString(subject)
                .addString(replyTo)
                .addMessageId(correlationId)
                .addSymbol(contentType)
                .addSymbol(contentEncoding)
                .addTimestamp(absoluteExpiryTime == null ? null : Long.valueOf(absoluteExpiryTime.getTime()))
                .addTimestamp(creationTime == null ? null : Long.valueOf(creationTime.getTime()))
                .addString(groupId)
                .addUint(groupSequence)
                .addString(replyToGroupId)
                .writeTo(out, MessageParser.SECTION_PROPERTIES);
    }

    private static Object messageId(List<Object> f, int index, String name)
            throws Amqp1ProtocolException {
        Object v = Fields.get(f, index);
        if (v == null || v instanceof Long || v instanceof UUID
                || v instanceof byte[] || v instanceof String) {
            return v;
        }
        throw new Amqp1ProtocolException("Field '" + name + "' has an invalid type "
                + v.getClass().getSimpleName());
    }

    private static Date timestamp(List<Object> f, int index, String name)
            throws Amqp1ProtocolException {
        Object v = Fields.get(f, index);
        if (v == null || v instanceof Date) {
            return (Date) v;
        }
        throw new Amqp1ProtocolException("Field '" + name + "' should be a timestamp");
    }

    static MessageProperties fromFields(List<Object> f) throws Amqp1ProtocolException {
        MessageProperties p = new MessageProperties();
        p.messageId = messageId(f, 0, "message-id");
        p.userId = Fields.binary(f, 1, "user-id");
        p.to = Fields.string(f, 2, "to");
        p.subject = Fields.string(f, 3, "subject");
        p.replyTo = Fields.string(f, 4, "reply-to");
        p.correlationId = messageId(f, 5, "correlation-id");
        p.contentType = Fields.symbol(f, 6, "content-type");
        p.contentEncoding = Fields.symbol(f, 7, "content-encoding");
        p.absoluteExpiryTime = timestamp(f, 8, "absolute-expiry-time");
        p.creationTime = timestamp(f, 9, "creation-time");
        p.groupId = Fields.string(f, 10, "group-id");
        p.groupSequence = Fields.unsigned(f, 11, "group-sequence");
        p.replyToGroupId = Fields.string(f, 12, "reply-to-group-id");
        return p;
    }
}
