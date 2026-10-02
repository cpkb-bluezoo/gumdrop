/*
 * MqttClientStoreFailureTest.java
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

package org.bluezoo.gumdrop.mqtt.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.mqtt.codec.ConnectPacket;
import org.bluezoo.gumdrop.mqtt.codec.MqttPacketEncoder;
import org.bluezoo.gumdrop.mqtt.codec.MqttProperties;
import org.bluezoo.gumdrop.mqtt.codec.MqttVersion;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageContent;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageWriter;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.junit.Test;

/**
 * Drives {@link MqttClientProtocolHandler} against message stores whose
 * writers fail, and checks transport-level callbacks without a registered
 * callback: failures must be swallowed (logged), never thrown into the
 * parser, and no message may reach the listener.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttClientStoreFailureTest {

    private static final MqttVersion V = MqttVersion.V3_1_1;

    private static final class FailingStore implements MqttMessageStore {
        final boolean failWrite;
        final boolean failCommit;
        int discards;

        FailingStore(boolean failWrite, boolean failCommit) {
            this.failWrite = failWrite;
            this.failCommit = failCommit;
        }

        @Override
        public MqttMessageWriter createWriter() {
            return new MqttMessageWriter() {
                @Override
                public int write(ByteBuffer src) throws IOException {
                    if (failWrite) {
                        throw new IOException("write failed");
                    }
                    int n = src.remaining();
                    src.position(src.limit());
                    return n;
                }

                @Override
                public MqttMessageContent commit() throws IOException {
                    throw new IOException("commit failed");
                }

                @Override
                public void discard() throws IOException {
                    discards++;
                    throw new IOException("discard failed");
                }

                @Override
                public boolean isOpen() {
                    return true;
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class Listener implements MqttMessageListener {
        final List<String> topics = new ArrayList<String>();

        @Override
        public void messageReceived(String topic, MqttMessageContent content,
                int qos, boolean retain) {
            topics.add(topic);
        }
    }

    private static MqttClientProtocolHandler handler(MqttMessageStore store,
            MqttClientCallback callback, MqttMessageListener listener) {
        ConnectPacket p = new ConnectPacket();
        p.setVersion(V);
        p.setClientId("client");
        p.setCleanSession(true);
        p.setKeepAlive(0);
        return new MqttClientProtocolHandler(p, callback, listener, store);
    }

    private static void inboundPublish(MqttClientProtocolHandler h) {
        h.receive(MqttPacketEncoder.encodeConnAck(false, 0,
                MqttProperties.EMPTY, V));
        h.receive(MqttPacketEncoder.encodePublish("t", 0, false, false, 0,
                "payload".getBytes(StandardCharsets.UTF_8),
                MqttProperties.EMPTY, V));
    }

    @Test
    public void writeFailureDiscardsAndDropsMessage() {
        FailingStore store = new FailingStore(true, false);
        Listener listener = new Listener();
        MqttClientProtocolHandler h = handler(store, null, listener);
        h.connected(new BinaryRecordingEndpoint());
        inboundPublish(h);
        assertEquals(1, store.discards);
        assertTrue(listener.topics.isEmpty());
    }

    @Test
    public void commitFailureDropsMessage() {
        FailingStore store = new FailingStore(false, true);
        Listener listener = new Listener();
        MqttClientProtocolHandler h = handler(store, null, listener);
        h.connected(new BinaryRecordingEndpoint());
        inboundPublish(h);
        assertEquals(0, store.discards);
        assertTrue(listener.topics.isEmpty());
    }

    @Test
    public void transportEventsWithoutCallbackAreTolerated() {
        MqttClientProtocolHandler h = handler(new FailingStore(false, false),
                null, null);
        h.connected(new BinaryRecordingEndpoint());
        h.securityEstablished(null);
        h.disconnected();
    }
}
