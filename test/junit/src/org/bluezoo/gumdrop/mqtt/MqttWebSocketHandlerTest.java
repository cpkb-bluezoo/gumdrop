/*
 * MqttWebSocketHandlerTest.java
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

package org.bluezoo.gumdrop.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.mqtt.codec.ConnectPacket;
import org.bluezoo.gumdrop.mqtt.codec.MqttPacketEncoder;
import org.bluezoo.gumdrop.mqtt.codec.MqttVersion;
import org.bluezoo.gumdrop.mqtt.server.SubscriptionManager;
import org.bluezoo.gumdrop.mqtt.server.WillManager;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.bluezoo.gumdrop.websocket.WebSocketSession;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link MqttWebSocketHandler}: MQTT-over-WebSocket framing using a
 * stub {@link WebSocketSession}, plus the endpoint adapter contract.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttWebSocketHandlerTest {

    private static final class StubSession implements WebSocketSession {
        final List<byte[]> binary = new ArrayList<byte[]>();
        boolean open = true;
        boolean failSend;
        boolean failClose;
        int closes;

        @Override
        public void sendText(String message) {
        }

        @Override
        public void sendBinary(ByteBuffer data) throws IOException {
            if (failSend) {
                throw new IOException("send failed");
            }
            byte[] b = new byte[data.remaining()];
            data.get(b);
            binary.add(b);
        }

        @Override
        public void sendPing(ByteBuffer payload) {
        }

        @Override
        public void close() throws IOException {
            closes++;
            open = false;
            if (failClose) {
                throw new IOException("close failed");
            }
        }

        @Override
        public void close(int code, String reason) throws IOException {
            close();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }
    }

    private StubSession session;
    private MqttWebSocketHandler handler;

    @Before
    public void setUp() {
        session = new StubSession();
        handler = new MqttWebSocketHandler(new MqttListener(),
                new SubscriptionManager(), new WillManager(),
                new InMemoryMessageStore());
    }

    private static ByteBuffer connectPacket() {
        ConnectPacket p = new ConnectPacket();
        p.setVersion(MqttVersion.V3_1_1);
        p.setClientId("ws");
        p.setCleanSession(true);
        return MqttPacketEncoder.encodeConnect(p);
    }

    @Test
    public void binaryConnectProducesConnAckOverWebSocket() {
        handler.opened(session);
        handler.binaryMessageReceived(session, connectPacket());
        assertEquals(1, session.binary.size());
        assertEquals(0x20, session.binary.get(0)[0] & 0xff);
        handler.closed(1000, "bye");
    }

    @Test
    public void binaryBeforeOpenIsIgnored() {
        handler.binaryMessageReceived(session, connectPacket());
        assertTrue(session.binary.isEmpty());
        handler.closed(1000, "none");
        handler.error(new RuntimeException("x"));
    }

    @Test
    public void textMessageIsIgnored() {
        handler.opened(session);
        handler.textMessageReceived(session, "hello");
        assertTrue(session.binary.isEmpty());
        handler.closed(1000, "bye");
    }

    @Test
    public void errorClosesSession() {
        handler.opened(session);
        handler.error(new IOException("wire"));
        assertFalse(session.open);
        handler.error(new Error("fatal"));
    }

    @Test
    public void adapterReportsClosedWhenSendFails() {
        MqttWebSocketHandler.WebSocketEndpointAdapter a =
                new MqttWebSocketHandler.WebSocketEndpointAdapter(session);
        session.failSend = true;
        a.send(ByteBuffer.wrap(new byte[] {1}));
        assertFalse(a.isOpen());
        assertTrue(a.isClosing());
    }

    @Test
    public void adapterCloseSwallowsIoException() {
        MqttWebSocketHandler.WebSocketEndpointAdapter a =
                new MqttWebSocketHandler.WebSocketEndpointAdapter(session);
        session.failClose = true;
        a.close();
        assertFalse(a.isOpen());
        assertEquals(1, session.closes);
    }

    @Test
    public void adapterStaticProperties() {
        MqttWebSocketHandler.WebSocketEndpointAdapter a =
                new MqttWebSocketHandler.WebSocketEndpointAdapter(session);
        assertNull(a.getLocalAddress());
        assertNull(a.getRemoteAddress());
        assertFalse(a.isSecure());
        assertNull(a.getSecurityInfo());
        assertNull(a.getSelectorLoop());
        assertNull(a.getTrace());
        assertFalse(a.isTelemetryEnabled());
        assertNull(a.getTelemetryConfig());
        a.pauseRead();
        a.resumeRead();
        try {
            a.startTLS();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // TLS lives at the HTTP layer
        }
    }

    @Test
    public void adapterExecuteAndWriteReadyRunInline() {
        MqttWebSocketHandler.WebSocketEndpointAdapter a =
                new MqttWebSocketHandler.WebSocketEndpointAdapter(session);
        final int[] count = new int[1];
        Runnable r = new Runnable() {
            @Override
            public void run() {
                count[0]++;
            }
        };
        a.execute(r);
        a.onWriteReady(r);
        a.onWriteReady(null);
        assertEquals(2, count[0]);
    }
}
