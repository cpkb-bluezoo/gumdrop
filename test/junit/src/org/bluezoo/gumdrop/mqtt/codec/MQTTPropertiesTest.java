/*
 * MQTTPropertiesTest.java
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

package org.bluezoo.gumdrop.mqtt.codec;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.util.List;

public class MQTTPropertiesTest {

    @Test
    public void testEmptyProperties() {
        assertTrue(MQTTProperties.EMPTY.isEmpty());
        assertEquals(0, MQTTProperties.EMPTY.encodedLength());
    }

    @Test
    public void testIntegerPropertyRoundTrip() {
        MQTTProperties props = new MQTTProperties();
        props.setIntegerProperty(MQTTProperties.SESSION_EXPIRY_INTERVAL, 300);
        props.setIntegerProperty(MQTTProperties.RECEIVE_MAXIMUM, 20);

        ByteBuffer buf = ByteBuffer.allocate(256);
        props.encode(buf);
        buf.flip();

        MQTTProperties decoded = MQTTProperties.decode(buf);
        assertEquals(Integer.valueOf(300),
                decoded.getIntegerProperty(MQTTProperties.SESSION_EXPIRY_INTERVAL));
        assertEquals(Integer.valueOf(20),
                decoded.getIntegerProperty(MQTTProperties.RECEIVE_MAXIMUM));
    }

    @Test
    public void testStringPropertyRoundTrip() {
        MQTTProperties props = new MQTTProperties();
        props.setStringProperty(MQTTProperties.CONTENT_TYPE, "application/json");

        ByteBuffer buf = ByteBuffer.allocate(256);
        props.encode(buf);
        buf.flip();

        MQTTProperties decoded = MQTTProperties.decode(buf);
        assertEquals("application/json",
                decoded.getStringProperty(MQTTProperties.CONTENT_TYPE));
    }

    @Test
    public void testBinaryPropertyRoundTrip() {
        MQTTProperties props = new MQTTProperties();
        byte[] data = {0x01, 0x02, 0x03, 0x04};
        props.setBinaryProperty(MQTTProperties.CORRELATION_DATA, data);

        ByteBuffer buf = ByteBuffer.allocate(256);
        props.encode(buf);
        buf.flip();

        MQTTProperties decoded = MQTTProperties.decode(buf);
        assertArrayEquals(data,
                decoded.getBinaryProperty(MQTTProperties.CORRELATION_DATA));
    }

    @Test
    public void testUserPropertiesRoundTrip() {
        MQTTProperties props = new MQTTProperties();
        props.addUserProperty("key1", "value1");
        props.addUserProperty("key2", "value2");

        ByteBuffer buf = ByteBuffer.allocate(256);
        props.encode(buf);
        buf.flip();

        MQTTProperties decoded = MQTTProperties.decode(buf);
        List<String[]> userProps = decoded.getUserProperties();
        assertNotNull(userProps);
        assertEquals(2, userProps.size());
        assertEquals("key1", userProps.get(0)[0]);
        assertEquals("value1", userProps.get(0)[1]);
        assertEquals("key2", userProps.get(1)[0]);
        assertEquals("value2", userProps.get(1)[1]);
    }

    @Test
    public void testBytePropertyRoundTrip() {
        MQTTProperties props = new MQTTProperties();
        props.setIntegerProperty(MQTTProperties.PAYLOAD_FORMAT_INDICATOR, 1);
        props.setIntegerProperty(MQTTProperties.MAXIMUM_QOS, 1);

        ByteBuffer buf = ByteBuffer.allocate(256);
        props.encode(buf);
        buf.flip();

        MQTTProperties decoded = MQTTProperties.decode(buf);
        assertEquals(Integer.valueOf(1),
                decoded.getIntegerProperty(MQTTProperties.PAYLOAD_FORMAT_INDICATOR));
        assertEquals(Integer.valueOf(1),
                decoded.getIntegerProperty(MQTTProperties.MAXIMUM_QOS));
    }

    @Test
    public void testDecodeEmptyProperties() {
        ByteBuffer buf = ByteBuffer.allocate(1);
        buf.put((byte) 0); // property length = 0
        buf.flip();

        MQTTProperties decoded = MQTTProperties.decode(buf);
        assertSame(MQTTProperties.EMPTY, decoded);
    }
}
