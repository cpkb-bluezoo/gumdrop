/*
 * MqttClientLoopbackIntegrationTest.java
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
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageContent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs {@link MqttClient} against a scripted broker on a loopback socket
 * owned by the test: CONNECT/CONNACK, publish, subscribe, unsubscribe,
 * inbound delivery and DISCONNECT, each step synchronised on the
 * client's callbacks (latches) or on what the broker reads.
 *
 * <p>Integration test: connects the client to a scripted broker on a real
 * loopback socket through a live loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttClientLoopbackIntegrationTest {

    private static final int TIMEOUT_MS = 10000;

    private static final class Events implements MqttClientCallback,
            MqttMessageListener {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch received = new CountDownLatch(1);
        final CountDownLatch lost = new CountDownLatch(1);
        volatile int returnCode = -1;
        volatile int subAckId = -1;
        volatile String topic;
        volatile String payload;

        @Override
        public void connected(boolean sessionPresent, int returnCode) {
            this.returnCode = returnCode;
            connected.countDown();
        }

        @Override
        public void connectionLost(Exception cause) {
            lost.countDown();
        }

        @Override
        public void subscribeAcknowledged(int packetId, int[] grantedQoS) {
            subAckId = packetId;
            subscribed.countDown();
        }

        @Override
        public void publishComplete(int packetId) {
        }

        @Override
        public void messageReceived(String topic, MqttMessageContent content,
                int qos, boolean retain) {
            this.topic = topic;
            this.payload = new String(content.asByteArray(),
                    StandardCharsets.UTF_8);
            received.countDown();
        }
    }

    private Gumdrop gumdrop;
    private ServerSocket broker;
    private Socket accepted;
    private InputStream in;
    private OutputStream out;

    @Before
    public void setUp() throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        broker = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        broker.setSoTimeout(TIMEOUT_MS);
    }

    @After
    public void tearDown() throws Exception {
        if (accepted != null) {
            accepted.close();
        }
        broker.close();
        gumdrop.shutdown();
        gumdrop.join();
    }

    private void acceptClient() throws IOException {
        accepted = broker.accept();
        accepted.setSoTimeout(TIMEOUT_MS);
        in = accepted.getInputStream();
        out = accepted.getOutputStream();
    }

    /** Reads one packet and returns [type byte, body...]. */
    private byte[] readPacket() throws IOException {
        int type = in.read();
        int len = in.read();
        if (type < 0 || len < 0) {
            throw new IOException("broker connection closed");
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(type);
        int remaining = len;
        while (remaining > 0) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("short packet");
            }
            buf.write(b);
            remaining--;
        }
        return buf.toByteArray();
    }

    @Test
    public void fullSessionAgainstScriptedBroker() throws Exception {
        Events events = new Events();
        MqttClient client = new MqttClient(
                InetAddress.getByName("127.0.0.1"), broker.getLocalPort());
        client.setClientId("loop");
        client.setCredentials("user", "pw");
        client.setWill("will/t", new byte[] {1}, QoS.AT_LEAST_ONCE, true);
        client.connect(gumdrop, events, events);
        acceptClient();

        byte[] connect = readPacket();
        assertEquals(0x10, connect[0] & 0xff);
        out.write(new byte[] {0x20, 0x02, 0x00, 0x00});
        out.flush();
        assertTrue(events.connected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(0, events.returnCode);

        int publishId = client.publish("a/b", "hello", QoS.AT_MOST_ONCE);
        assertEquals(0, publishId);
        byte[] pub = readPacket();
        assertEquals(0x30, pub[0] & 0xff);
        assertTrue(new String(pub, StandardCharsets.UTF_8).endsWith("hello"));

        int subId = client.subscribe("a/#", QoS.AT_LEAST_ONCE);
        byte[] sub = readPacket();
        assertEquals(0x82, sub[0] & 0xff);
        out.write(new byte[] {(byte) 0x90, 0x03, (byte) (subId >> 8),
                (byte) subId, 0x01});
        out.flush();
        assertTrue(events.subscribed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(subId, events.subAckId);

        byte[] topic = "a/x".getBytes(StandardCharsets.UTF_8);
        byte[] body = "inbound".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        msg.write(0x30);
        msg.write(2 + topic.length + body.length);
        msg.write(0);
        msg.write(topic.length);
        msg.write(topic);
        msg.write(body);
        out.write(msg.toByteArray());
        out.flush();
        assertTrue(events.received.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals("a/x", events.topic);
        assertEquals("inbound", events.payload);

        int unsubId = client.unsubscribe("a/#");
        byte[] unsub = readPacket();
        assertEquals(0xA2, unsub[0] & 0xff);
        out.write(new byte[] {(byte) 0xB0, 0x02, (byte) (unsubId >> 8),
                (byte) unsubId});
        out.flush();

        client.disconnect();
        byte[] bye = readPacket();
        assertEquals(0xE0, bye[0] & 0xff);
    }

    @Test
    public void connectWithoutTargetFails() throws Exception {
        MqttClient client = new MqttClient();
        try {
            client.connect(gumdrop, new Events(), new Events());
            fail("expected an exception for a missing target");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage() != null);
        } catch (IOException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    @Test
    public void disconnectBeforeConnectIsHarmless() {
        new MqttClient().disconnect();
    }
}
