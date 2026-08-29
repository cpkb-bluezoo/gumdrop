/*
 * BasicPropertiesTest.java
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

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Date;

import static org.junit.Assert.*;

public class BasicPropertiesTest {

    @Test
    public void testEmptyProperties() throws AMQPProtocolException {
        BasicProperties props = new BasicProperties();
        ByteBuffer encoded = props.encode(0L);
        BasicProperties.Header header = BasicProperties.decode(encoded);

        assertEquals(0L, header.getBodySize());
        assertNull(header.getProperties().getContentType());
        assertFalse(encoded.hasRemaining());
    }

    @Test
    public void testAllScalarProperties() throws AMQPProtocolException {
        Date ts = new Date((System.currentTimeMillis() / 1000L) * 1000L);
        BasicProperties props = new BasicProperties()
                .withContentType("application/json")
                .withContentEncoding("utf-8")
                .withDeliveryMode((byte) 2)
                .withPriority((byte) 5)
                .withCorrelationId("corr-123")
                .withReplyTo("reply-queue")
                .withExpiration("60000")
                .withMessageId("msg-456")
                .withTimestamp(ts)
                .withType("order.created")
                .withUserId("guest")
                .withAppId("my-app");

        ByteBuffer encoded = props.encode(12345L);
        BasicProperties.Header header = BasicProperties.decode(encoded);
        BasicProperties decoded = header.getProperties();

        assertEquals(12345L, header.getBodySize());
        assertEquals("application/json", decoded.getContentType());
        assertEquals("utf-8", decoded.getContentEncoding());
        assertEquals(Byte.valueOf((byte) 2), decoded.getDeliveryMode());
        assertEquals(Byte.valueOf((byte) 5), decoded.getPriority());
        assertEquals("corr-123", decoded.getCorrelationId());
        assertEquals("reply-queue", decoded.getReplyTo());
        assertEquals("60000", decoded.getExpiration());
        assertEquals("msg-456", decoded.getMessageId());
        assertEquals(ts, decoded.getTimestamp());
        assertEquals("order.created", decoded.getType());
        assertEquals("guest", decoded.getUserId());
        assertEquals("my-app", decoded.getAppId());
        assertFalse(encoded.hasRemaining());
    }

    @Test
    public void testHeadersTable() throws AMQPProtocolException {
        FieldTable headers = new FieldTable().put("x-retry-count", 3).put("x-source", "test");
        BasicProperties props = new BasicProperties().withHeaders(headers);

        ByteBuffer encoded = props.encode(0L);
        BasicProperties decoded = BasicProperties.decode(encoded).getProperties();

        assertEquals(3, decoded.getHeaders().get("x-retry-count"));
        assertEquals("test", decoded.getHeaders().get("x-source"));
    }

    @Test
    public void testOnlySetPropertiesAreEncoded() throws AMQPProtocolException {
        BasicProperties props = new BasicProperties().withContentType("text/plain");
        ByteBuffer encoded = props.encode(0L);
        BasicProperties decoded = BasicProperties.decode(encoded).getProperties();

        assertEquals("text/plain", decoded.getContentType());
        assertNull(decoded.getContentEncoding());
        assertNull(decoded.getDeliveryMode());
        assertNull(decoded.getMessageId());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Issue #310: encode() must UTF-8 encode each set property string
    // exactly once, not once to compute the property-list size and again
    // to write it.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testEncodeUtf8sEachSetPropertyStringExactlyOnce() {
        BasicProperties props = new BasicProperties()
                .withContentType("application/json")
                .withCorrelationId("corr-1")
                .withReplyTo("reply-q")
                .withMessageId("msg-1");
        // 4 distinct property strings.
        FieldTable.utf8EncodeCountForTesting.set(0);
        props.encode(0L);
        assertEquals("encode() should UTF-8 encode each set property string exactly once",
                4, FieldTable.utf8EncodeCountForTesting.get());
    }

    @Test
    public void testEncodeWithHeadersCountsHeaderStringsOnce() {
        FieldTable headers = new FieldTable().put("x-retry", "yes");
        BasicProperties props = new BasicProperties()
                .withContentType("text/plain")
                .withHeaders(headers);
        // "text/plain" (1) + headers key "x-retry" (1) + headers value "yes" (1) = 3.
        FieldTable.utf8EncodeCountForTesting.set(0);
        props.encode(0L);
        assertEquals(3, FieldTable.utf8EncodeCountForTesting.get());
    }

    @Test(expected = AMQPProtocolException.class)
    public void testWrongClassIdRejected() throws AMQPProtocolException {
        ByteBuffer buf = ByteBuffer.allocate(14);
        buf.putShort((short) 99); // not 60 (basic)
        buf.putShort((short) 0);
        buf.putLong(0L);
        buf.putShort((short) 0);
        buf.flip();
        BasicProperties.decode(buf);
    }
}
