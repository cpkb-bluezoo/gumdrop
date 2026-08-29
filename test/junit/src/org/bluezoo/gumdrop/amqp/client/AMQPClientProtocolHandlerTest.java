/*
 * AMQPClientProtocolHandlerTest.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.amqp.client.handler.ChannelClosedListener;
import org.bluezoo.gumdrop.amqp.client.handler.ClientChannel;
import org.bluezoo.gumdrop.amqp.client.handler.ClientConnection;
import org.bluezoo.gumdrop.amqp.client.handler.ClientHandshake;
import org.bluezoo.gumdrop.amqp.client.handler.ClientTuned;
import org.bluezoo.gumdrop.amqp.client.handler.ConnectionReady;
import org.bluezoo.gumdrop.amqp.client.handler.DeliveryHandler;
import org.bluezoo.gumdrop.amqp.client.handler.FlowListener;
import org.bluezoo.gumdrop.amqp.client.handler.PublishBody;
import org.bluezoo.gumdrop.amqp.client.handler.ServerCancelHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerChannelCloseHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerChannelOpenHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerConfirmSelectHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerConsumeHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerExchangeDeclareHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerFlowHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerOpenHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerQueueBindHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerQueueDeclareHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTuneHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxCommitHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxRollbackHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxSelectHandler;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Unit tests for the connection/channel handshake state machine, using
 * an in-process stub {@link Endpoint} — no real broker or socket
 * involved, per issue #154's "no real broker in the test environment"
 * requirement (a fuller fake in-process AMQP server for
 * end-to-end/integration-style testing is separate follow-up work).
 */
public class AMQPClientProtocolHandlerTest {

    private StubEndpoint endpoint;
    private AMQPClientProtocolHandler handler;
    private RecordingHandler recording;

    @Before
    public void setUp() {
        endpoint = new StubEndpoint();
        recording = new RecordingHandler();
        handler = new AMQPClientProtocolHandler(recording);
    }

    private void connect() {
        handler.connected(endpoint);
    }

    private ByteBuffer lastSentFrame() {
        assertFalse("expected a frame to have been sent", endpoint.sent.isEmpty());
        return endpoint.sent.get(endpoint.sent.size() - 1);
    }

    private void feed(ByteBuffer frame) {
        handler.receive(frame);
    }

    /** Drives the full handshake + opens channel 1, returning it. */
    private ClientChannel openChannel() {
        final List<ClientChannel> opened = new ArrayList<>();
        connect();
        feed(serverStartFrame());
        recording.lastHandshake.startOk("guest", "guest", new ServerTuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) {
                tuned.open("/", new ServerOpenHandler() {
                    @Override
                    public void handleOpenOk(ClientConnection connection) {
                        connection.channelOpen(1, new ServerChannelOpenHandler() {
                            @Override
                            public void handleChannelOpenOk(ClientChannel channel) {
                                opened.add(channel);
                            }
                        });
                    }
                });
            }
        });
        feed(serverTuneFrame(0, 131072, 0));
        feed(serverOpenOkFrame());
        feed(serverChannelOpenOkFrame(1));
        assertEquals(1, opened.size());
        return opened.get(0);
    }

    /** Extracts the method payload (past the frame envelope and class/method IDs) of the last sent frame. */
    private ByteBuffer lastSentMethodArgs(int expectedClassId, int expectedMethodId) {
        ByteBuffer buf = lastSentFrame().duplicate();
        buf.position(AMQPFrame.HEADER_SIZE);
        assertEquals(expectedClassId, buf.getShort() & 0xFFFF);
        assertEquals(expectedMethodId, buf.getShort() & 0xFFFF);
        return buf;
    }

    private static ByteBuffer serverStartFrame() {
        FieldTable serverProps = new FieldTable().put("product", "TestBroker");
        ByteBuffer args = ByteBuffer.allocate(4 + 2 + 4 + serverProps.encodedContentSize()
                + 4 + 5 + 4 + 5);
        args.putShort((short) AMQPMethod.CLASS_CONNECTION);
        args.putShort((short) AMQPMethod.CONNECTION_START);
        args.put((byte) 0);
        args.put((byte) 9);
        ByteBuffer encodedProps = serverProps.encode();
        args.putInt(encodedProps.remaining());
        args.put(encodedProps);
        FieldTable.putLongString(args, "PLAIN");
        FieldTable.putLongString(args, "en_US");
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, 0, args);
    }

    private static ByteBuffer serverTuneFrame(int channelMax, long frameMax, int heartbeat) {
        ByteBuffer args = ByteBuffer.allocate(4 + 2 + 4 + 2);
        args.putShort((short) AMQPMethod.CLASS_CONNECTION);
        args.putShort((short) AMQPMethod.CONNECTION_TUNE);
        args.putShort((short) channelMax);
        args.putInt((int) frameMax);
        args.putShort((short) heartbeat);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, 0, args);
    }

    private static ByteBuffer serverOpenOkFrame() {
        ByteBuffer args = ByteBuffer.allocate(4 + 1);
        args.putShort((short) AMQPMethod.CLASS_CONNECTION);
        args.putShort((short) AMQPMethod.CONNECTION_OPEN_OK);
        FieldTable.putShortString(args, "");
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, 0, args);
    }

    private static ByteBuffer serverChannelOpenOkFrame(int channel) {
        ByteBuffer args = ByteBuffer.allocate(4 + 4);
        args.putShort((short) AMQPMethod.CLASS_CHANNEL);
        args.putShort((short) AMQPMethod.CHANNEL_OPEN_OK);
        args.putInt(0); // reserved-1 longstr, empty
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer serverChannelCloseFrame(int channel, int replyCode, String replyText) {
        ByteBuffer args = ByteBuffer.allocate(4 + 2 + FieldTable.shortStringEncodedSize(replyText) + 4);
        args.putShort((short) AMQPMethod.CLASS_CHANNEL);
        args.putShort((short) AMQPMethod.CHANNEL_CLOSE);
        args.putShort((short) replyCode);
        FieldTable.putShortString(args, replyText);
        args.putShort((short) 0);
        args.putShort((short) 0);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    @Test
    public void testProtocolHeaderSentOnConnect() {
        connect();
        assertEquals(1, endpoint.sent.size());
        byte[] header = new byte[8];
        endpoint.sent.get(0).duplicate().get(header);
        assertArrayEquals(new byte[] { 'A', 'M', 'Q', 'P', 0, 0, 9, 1 }, header);
    }

    @Test
    public void testStartTriggersOnConnectedThenHandleStart() {
        connect();
        feed(serverStartFrame());

        assertTrue(recording.connectedCalled);
        assertNotNull(recording.lastServerProperties);
        assertEquals("TestBroker", recording.lastServerProperties.get("product"));
        assertEquals("PLAIN", recording.lastMechanisms);
        assertNotNull(recording.lastHandshake);
    }

    @Test
    public void testStartOkSentAfterCredentials() {
        connect();
        feed(serverStartFrame());
        recording.lastHandshake.startOk("guest", "guest",
                new ServerTuneHandler() {
                    @Override
                    public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned state) { }
                });

        ByteBuffer sent = lastSentFrame();
        // Skip the AMQP frame header to inspect the method payload.
        sent = sent.duplicate();
        sent.position(AMQPFrame.HEADER_SIZE);
        int classId = sent.getShort() & 0xFFFF;
        int methodId = sent.getShort() & 0xFFFF;
        assertEquals(AMQPMethod.CLASS_CONNECTION, classId);
        assertEquals(AMQPMethod.CONNECTION_START_OK, methodId);
    }

    @Test
    public void testTuneOkSentAutomaticallyOnTune() {
        connect();
        feed(serverStartFrame());
        recording.lastHandshake.startOk("guest", "guest", new ServerTuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned state) { }
        });

        feed(serverTuneFrame(2047, 131072, 60));

        ByteBuffer sent = lastSentFrame().duplicate();
        sent.position(AMQPFrame.HEADER_SIZE);
        assertEquals(AMQPMethod.CLASS_CONNECTION, sent.getShort() & 0xFFFF);
        assertEquals(AMQPMethod.CONNECTION_TUNE_OK, sent.getShort() & 0xFFFF);
        assertEquals(2047, sent.getShort() & 0xFFFF);
        assertEquals(131072, sent.getInt());
        assertEquals(60, sent.getShort() & 0xFFFF);
    }

    @Test
    public void testFullHandshakeToOpenConnection() {
        final List<ClientConnection> connections = new ArrayList<>();

        connect();
        feed(serverStartFrame());
        recording.lastHandshake.startOk("guest", "guest", new ServerTuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) {
                tuned.open("/", new ServerOpenHandler() {
                    @Override
                    public void handleOpenOk(ClientConnection connection) {
                        connections.add(connection);
                    }
                });
            }
        });
        feed(serverTuneFrame(2047, 131072, 60));
        feed(serverOpenOkFrame());

        assertEquals(1, connections.size());
    }

    @Test
    public void testChannelOpenRoundTrip() {
        final List<ClientChannel> openedChannels = new ArrayList<>();

        connect();
        feed(serverStartFrame());
        recording.lastHandshake.startOk("guest", "guest", new ServerTuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) {
                tuned.open("/", new ServerOpenHandler() {
                    @Override
                    public void handleOpenOk(ClientConnection connection) {
                        connection.channelOpen(1, new ServerChannelOpenHandler() {
                            @Override
                            public void handleChannelOpenOk(ClientChannel channel) {
                                openedChannels.add(channel);
                            }
                        });
                    }
                });
            }
        });
        feed(serverTuneFrame(0, 131072, 0));
        feed(serverOpenOkFrame());
        feed(serverChannelOpenOkFrame(1));

        assertEquals(1, openedChannels.size());
        assertEquals(1, openedChannels.get(0).getChannelId());
    }

    @Test
    public void testUnsolicitedChannelCloseNotifiesListener() {
        final List<ClientChannel> openedChannels = new ArrayList<>();

        connect();
        feed(serverStartFrame());
        recording.lastHandshake.startOk("guest", "guest", new ServerTuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) {
                tuned.open("/", new ServerOpenHandler() {
                    @Override
                    public void handleOpenOk(ClientConnection connection) {
                        connection.channelOpen(1, new ServerChannelOpenHandler() {
                            @Override
                            public void handleChannelOpenOk(ClientChannel channel) {
                                openedChannels.add(channel);
                            }
                        });
                    }
                });
            }
        });
        feed(serverTuneFrame(0, 131072, 0));
        feed(serverOpenOkFrame());
        feed(serverChannelOpenOkFrame(1));

        final int[] closedCode = new int[1];
        final String[] closedText = new String[1];
        openedChannels.get(0).setCloseListener(new ChannelClosedListener() {
            @Override
            public void onChannelClosed(int code, String text) {
                closedCode[0] = code;
                closedText[0] = text;
            }
        });

        feed(serverChannelCloseFrame(1, 404, "NOT_FOUND - no queue"));

        assertEquals(404, closedCode[0]);
        assertEquals("NOT_FOUND - no queue", closedText[0]);

        // The client must ack with channel.close-ok.
        ByteBuffer sent = lastSentFrame().duplicate();
        sent.position(AMQPFrame.HEADER_SIZE);
        assertEquals(AMQPMethod.CLASS_CHANNEL, sent.getShort() & 0xFFFF);
        assertEquals(AMQPMethod.CHANNEL_CLOSE_OK, sent.getShort() & 0xFFFF);
    }

    @Test
    public void testMalformedFrameReportsErrorAndClosesEndpoint() {
        connect();
        // Bad frame-end octet.
        ByteBuffer bad = ByteBuffer.allocate(AMQPFrame.OVERHEAD);
        bad.put((byte) AMQPFrame.TYPE_METHOD);
        bad.putShort((short) 0);
        bad.putInt(0);
        bad.put((byte) 0x00);
        bad.flip();

        feed(bad);

        assertNotNull(recording.lastError);
        assertTrue(endpoint.closed);
    }

    @Test
    public void testHeartbeatIsEchoed() {
        connect();
        endpoint.sent.clear();
        feed(AMQPFrame.encodeHeartbeat());

        assertEquals(1, endpoint.sent.size());
        ByteBuffer sent = endpoint.sent.get(0).duplicate();
        assertEquals(AMQPFrame.OVERHEAD, sent.remaining());
        assertEquals(AMQPFrame.TYPE_HEARTBEAT, sent.get() & 0xFF);
    }

    private static ByteBuffer serverExchangeDeclareOkFrame(int channel) {
        ByteBuffer args = ByteBuffer.allocate(4);
        args.putShort((short) AMQPMethod.CLASS_EXCHANGE);
        args.putShort((short) AMQPMethod.EXCHANGE_DECLARE_OK);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer serverQueueDeclareOkFrame(int channel, String queue,
            long messageCount, long consumerCount) {
        ByteBuffer args = ByteBuffer.allocate(4 + FieldTable.shortStringEncodedSize(queue) + 4 + 4);
        args.putShort((short) AMQPMethod.CLASS_QUEUE);
        args.putShort((short) AMQPMethod.QUEUE_DECLARE_OK);
        FieldTable.putShortString(args, queue);
        args.putInt((int) messageCount);
        args.putInt((int) consumerCount);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer serverQueueBindOkFrame(int channel) {
        ByteBuffer args = ByteBuffer.allocate(4);
        args.putShort((short) AMQPMethod.CLASS_QUEUE);
        args.putShort((short) AMQPMethod.QUEUE_BIND_OK);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer serverConsumeOkFrame(int channel, String consumerTag) {
        ByteBuffer args = ByteBuffer.allocate(4 + FieldTable.shortStringEncodedSize(consumerTag));
        args.putShort((short) AMQPMethod.CLASS_BASIC);
        args.putShort((short) AMQPMethod.BASIC_CONSUME_OK);
        FieldTable.putShortString(args, consumerTag);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer serverCancelOkFrame(int channel, String consumerTag) {
        ByteBuffer args = ByteBuffer.allocate(4 + FieldTable.shortStringEncodedSize(consumerTag));
        args.putShort((short) AMQPMethod.CLASS_BASIC);
        args.putShort((short) AMQPMethod.BASIC_CANCEL_OK);
        FieldTable.putShortString(args, consumerTag);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer serverDeliverFrame(int channel, String consumerTag, long deliveryTag,
            boolean redelivered, String exchange, String routingKey) {
        ByteBuffer args = ByteBuffer.allocate(4 + FieldTable.shortStringEncodedSize(consumerTag)
                + 8 + 1 + FieldTable.shortStringEncodedSize(exchange)
                + FieldTable.shortStringEncodedSize(routingKey));
        args.putShort((short) AMQPMethod.CLASS_BASIC);
        args.putShort((short) AMQPMethod.BASIC_DELIVER);
        FieldTable.putShortString(args, consumerTag);
        args.putLong(deliveryTag);
        args.put(AMQPBits.pack(redelivered));
        FieldTable.putShortString(args, exchange);
        FieldTable.putShortString(args, routingKey);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer serverHeaderFrame(int channel, long bodySize, BasicProperties properties) {
        BasicProperties props = (properties != null) ? properties : new BasicProperties();
        return AMQPFrame.encode(AMQPFrame.TYPE_HEADER, channel, props.encode(bodySize));
    }

    private static ByteBuffer serverBodyFrame(int channel, byte[] content) {
        return AMQPFrame.encode(AMQPFrame.TYPE_BODY, channel, ByteBuffer.wrap(content));
    }

    // ── exchange / queue declare, bind ──

    @Test
    public void testDiagnosticBackToBackDeclaresWithoutWaiting() {
        ClientChannel channel = openChannel();
        final boolean[] exchangeOk = new boolean[1];
        final boolean[] queueOk = new boolean[1];

        // Issue two RPCs back-to-back WITHOUT waiting for the first's reply
        // in between, exactly like RecoverableChannelImpl.rebind()'s replay
        // loop does.
        channel.exchangeDeclare("ex", "topic", true, false, null, new ServerExchangeDeclareHandler() {
            @Override public void handleExchangeDeclareOk() { exchangeOk[0] = true; }
        });
        channel.queueDeclare("q", true, false, false, null, new ServerQueueDeclareHandler() {
            @Override public void handleQueueDeclareOk(String queue, long mc, long cc) { queueOk[0] = true; }
        });

        feed(serverExchangeDeclareOkFrame(1));
        feed(serverQueueDeclareOkFrame(1, "q", 0, 0));

        assertTrue("exchange.declare-ok callback must fire", exchangeOk[0]);
        assertTrue("queue.declare-ok callback must fire", queueOk[0]);
    }

    @Test
    public void testExchangeDeclareRoundTrip() {
        ClientChannel channel = openChannel();
        final boolean[] ok = new boolean[1];

        channel.exchangeDeclare("my-exchange", "topic", true, false, null, new ServerExchangeDeclareHandler() {
            @Override public void handleExchangeDeclareOk() { ok[0] = true; }
        });

        lastSentMethodArgs(AMQPMethod.CLASS_EXCHANGE, AMQPMethod.EXCHANGE_DECLARE);
        assertFalse(ok[0]);
        feed(serverExchangeDeclareOkFrame(1));
        assertTrue(ok[0]);
    }

    @Test
    public void testQueueDeclareRoundTrip() {
        ClientChannel channel = openChannel();
        final List<String> queueNames = new ArrayList<>();
        final long[] counts = new long[2];

        channel.queueDeclare("my-queue", true, false, false, null, new ServerQueueDeclareHandler() {
            @Override
            public void handleQueueDeclareOk(String queue, long msgCount, long consumerCount) {
                queueNames.add(queue);
                counts[0] = msgCount;
                counts[1] = consumerCount;
            }
        });

        feed(serverQueueDeclareOkFrame(1, "my-queue", 7, 2));

        assertEquals(List.of("my-queue"), queueNames);
        assertEquals(7L, counts[0]);
        assertEquals(2L, counts[1]);
    }

    @Test
    public void testQueueBindRoundTrip() {
        ClientChannel channel = openChannel();
        final boolean[] ok = new boolean[1];

        channel.queueBind("my-queue", "my-exchange", "my.routing.key", null, new ServerQueueBindHandler() {
            @Override public void handleQueueBindOk() { ok[0] = true; }
        });

        lastSentMethodArgs(AMQPMethod.CLASS_QUEUE, AMQPMethod.QUEUE_BIND);
        feed(serverQueueBindOkFrame(1));
        assertTrue(ok[0]);
    }

    // ── publish (streaming outbound body) ──

    @Test
    public void testPublishSendsMethodAndHeaderFramesImmediately() {
        ClientChannel channel = openChannel();
        endpoint.sent.clear();

        BasicProperties props = new BasicProperties().withContentType("text/plain");
        PublishBody body = channel.basicPublish("my-exchange", "my.key", false, props, 11);

        assertEquals(2, endpoint.sent.size());
        ByteBuffer methodFrame = endpoint.sent.get(0).duplicate();
        assertEquals(AMQPFrame.TYPE_METHOD, methodFrame.get() & 0xFF);
        ByteBuffer headerFrame = endpoint.sent.get(1).duplicate();
        assertEquals(AMQPFrame.TYPE_HEADER, headerFrame.get() & 0xFF);
    }

    @Test
    public void testPublishBodyStreamedInMultipleChunksNotBufferedWhole() {
        ClientChannel channel = openChannel();
        PublishBody body = channel.basicPublish("ex", "rk", false, null, 11);
        endpoint.sent.clear();

        body.writeBody(ByteBuffer.wrap("hello ".getBytes(StandardCharsets.US_ASCII)));
        body.writeBody(ByteBuffer.wrap("world".getBytes(StandardCharsets.US_ASCII)));
        body.complete();

        // Each writeBody() call must produce its own content-body frame —
        // proof the two chunks were sent as they arrived, never
        // concatenated into one buffer first.
        assertEquals(2, endpoint.sent.size());
        assertBodyFrameContains(endpoint.sent.get(0), "hello ");
        assertBodyFrameContains(endpoint.sent.get(1), "world");
    }

    @Test(expected = IllegalStateException.class)
    public void testPublishCompleteRejectsShortWrite() {
        ClientChannel channel = openChannel();
        PublishBody body = channel.basicPublish("ex", "rk", false, null, 100);
        body.writeBody(ByteBuffer.wrap("too short".getBytes(StandardCharsets.US_ASCII)));
        body.complete();
    }

    @Test(expected = IllegalStateException.class)
    public void testPublishWriteRejectsOverrun() {
        ClientChannel channel = openChannel();
        PublishBody body = channel.basicPublish("ex", "rk", false, null, 5);
        body.writeBody(ByteBuffer.wrap("way too long".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void testPublishWithZeroLengthBodyCompletesImmediately() {
        ClientChannel channel = openChannel();
        PublishBody body = channel.basicPublish("ex", "rk", false, null, 0);
        body.complete(); // must not throw
    }

    private void assertBodyFrameContains(ByteBuffer frame, String expected) {
        ByteBuffer f = frame.duplicate();
        f.position(AMQPFrame.HEADER_SIZE);
        byte[] data = new byte[f.remaining() - 1]; // minus frame-end
        f.get(data);
        assertEquals(expected, new String(data, StandardCharsets.US_ASCII));
    }

    // ── consume (streaming inbound delivery) ──

    @Test
    public void testConsumeRegistersConsumerAndRoutesDeliveries() {
        ClientChannel channel = openChannel();
        final List<String> consumeTags = new ArrayList<>();
        final RecordingDeliveryHandler delivery = new RecordingDeliveryHandler();

        channel.basicConsume("my-queue", "", false, false, null, delivery,
                new ServerConsumeHandler() {
                    @Override public void handleConsumeOk(String consumerTag) { consumeTags.add(consumerTag); }
                });
        feed(serverConsumeOkFrame(1, "ctag-1"));
        assertEquals(List.of("ctag-1"), consumeTags);

        feed(serverDeliverFrame(1, "ctag-1", 42L, false, "ex", "rk"));
        assertEquals("ctag-1", delivery.consumerTag);
        assertEquals(42L, delivery.deliveryTag);
        assertFalse(delivery.propertiesReceived);

        feed(serverHeaderFrame(1, 11, new BasicProperties().withContentType("text/plain")));
        assertTrue(delivery.propertiesReceived);
        assertEquals(11L, delivery.bodySize);
        assertFalse(delivery.completed);

        feed(serverBodyFrame(1, "hello ".getBytes(StandardCharsets.US_ASCII)));
        feed(serverBodyFrame(1, "world".getBytes(StandardCharsets.US_ASCII)));

        assertEquals("hello world", delivery.receivedBody());
        assertTrue(delivery.completed);

        // Chunks must have arrived as two separate callbacks, not one
        // concatenated buffer, proving the body was streamed.
        assertEquals(2, delivery.chunkCount);
    }

    @Test
    public void testConsumeWithZeroLengthBodyCompletesOnHeader() {
        ClientChannel channel = openChannel();
        RecordingDeliveryHandler delivery = new RecordingDeliveryHandler();
        channel.basicConsume("q", "", false, false, null, delivery, new ServerConsumeHandler() {
            @Override public void handleConsumeOk(String consumerTag) { }
        });
        feed(serverConsumeOkFrame(1, "ctag-1"));

        feed(serverDeliverFrame(1, "ctag-1", 1L, false, "ex", "rk"));
        feed(serverHeaderFrame(1, 0, null));

        assertTrue(delivery.completed);
        assertEquals(0, delivery.chunkCount);
    }

    @Test
    public void testAckNackRejectSendCorrectMethods() {
        ClientChannel channel = openChannel();

        channel.basicAck(1L, false);
        lastSentMethodArgs(AMQPMethod.CLASS_BASIC, AMQPMethod.BASIC_ACK);

        channel.basicNack(2L, true, true);
        lastSentMethodArgs(AMQPMethod.CLASS_BASIC, AMQPMethod.BASIC_NACK);

        channel.basicReject(3L, false);
        lastSentMethodArgs(AMQPMethod.CLASS_BASIC, AMQPMethod.BASIC_REJECT);
    }

    @Test
    public void testCancelRoundTrip() {
        ClientChannel channel = openChannel();
        RecordingDeliveryHandler delivery = new RecordingDeliveryHandler();
        channel.basicConsume("q", "", false, false, null, delivery, new ServerConsumeHandler() {
            @Override public void handleConsumeOk(String consumerTag) { }
        });
        feed(serverConsumeOkFrame(1, "ctag-1"));

        final List<String> cancelled = new ArrayList<>();
        channel.basicCancel("ctag-1", new ServerCancelHandler() {
            @Override public void handleCancelOk(String consumerTag) { cancelled.add(consumerTag); }
        });
        feed(serverCancelOkFrame(1, "ctag-1"));

        assertEquals(List.of("ctag-1"), cancelled);
    }

    @Test
    public void testDeliverForUnknownConsumerTagIsProtocolError() {
        ClientChannel channel = openChannel();
        feed(serverDeliverFrame(1, "no-such-consumer", 1L, false, "ex", "rk"));
        assertNotNull(recording.lastError);
    }

    // ── transactions ──

    private static ByteBuffer serverTxOkFrame(int channel, int methodId) {
        ByteBuffer args = ByteBuffer.allocate(4);
        args.putShort((short) AMQPMethod.CLASS_TX);
        args.putShort((short) methodId);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    @Test
    public void testTxSelectCommitRollbackRoundTrip() {
        ClientChannel channel = openChannel();

        final boolean[] selected = new boolean[1];
        channel.txSelect(new ServerTxSelectHandler() {
            @Override public void handleTxSelectOk() { selected[0] = true; }
        });
        lastSentMethodArgs(AMQPMethod.CLASS_TX, AMQPMethod.TX_SELECT);
        feed(serverTxOkFrame(1, AMQPMethod.TX_SELECT_OK));
        assertTrue(selected[0]);

        final boolean[] committed = new boolean[1];
        channel.txCommit(new ServerTxCommitHandler() {
            @Override public void handleTxCommitOk() { committed[0] = true; }
        });
        lastSentMethodArgs(AMQPMethod.CLASS_TX, AMQPMethod.TX_COMMIT);
        feed(serverTxOkFrame(1, AMQPMethod.TX_COMMIT_OK));
        assertTrue(committed[0]);

        final boolean[] rolledBack = new boolean[1];
        channel.txRollback(new ServerTxRollbackHandler() {
            @Override public void handleTxRollbackOk() { rolledBack[0] = true; }
        });
        lastSentMethodArgs(AMQPMethod.CLASS_TX, AMQPMethod.TX_ROLLBACK);
        feed(serverTxOkFrame(1, AMQPMethod.TX_ROLLBACK_OK));
        assertTrue(rolledBack[0]);
    }

    @Test
    public void testUnsolicitedTxOkIsProtocolError() {
        openChannel();
        feed(serverTxOkFrame(1, AMQPMethod.TX_COMMIT_OK));
        assertNotNull(recording.lastError);
    }

    // ── flow control ──

    private static ByteBuffer serverFlowFrame(int channel, boolean active) {
        ByteBuffer args = ByteBuffer.allocate(5);
        args.putShort((short) AMQPMethod.CLASS_CHANNEL);
        args.putShort((short) AMQPMethod.CHANNEL_FLOW);
        args.put(AMQPBits.pack(active));
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer serverFlowOkFrame(int channel, boolean active) {
        ByteBuffer args = ByteBuffer.allocate(5);
        args.putShort((short) AMQPMethod.CLASS_CHANNEL);
        args.putShort((short) AMQPMethod.CHANNEL_FLOW_OK);
        args.put(AMQPBits.pack(active));
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    @Test
    public void testBrokerInitiatedFlowIsAckedAndNotifiesListener() {
        ClientChannel channel = openChannel();
        final List<Boolean> flowEvents = new ArrayList<>();
        channel.setFlowListener(new FlowListener() {
            @Override public void onFlow(boolean active) { flowEvents.add(active); }
        });

        feed(serverFlowFrame(1, false));

        assertEquals(List.of(false), flowEvents);
        ByteBuffer sent = lastSentMethodArgs(AMQPMethod.CLASS_CHANNEL, AMQPMethod.CHANNEL_FLOW_OK);
        assertFalse(AMQPBits.unpack(sent.get(), 0));

        feed(serverFlowFrame(1, true));
        assertEquals(List.of(false, true), flowEvents);
    }

    @Test
    public void testClientInitiatedFlowRoundTrip() {
        ClientChannel channel = openChannel();
        final List<Boolean> results = new ArrayList<>();

        channel.flow(false, new ServerFlowHandler() {
            @Override public void handleFlowOk(boolean active) { results.add(active); }
        });
        lastSentMethodArgs(AMQPMethod.CLASS_CHANNEL, AMQPMethod.CHANNEL_FLOW);
        feed(serverFlowOkFrame(1, false));

        assertEquals(List.of(false), results);
    }

    // ── publisher confirms ──

    private static ByteBuffer serverConfirmSelectOkFrame(int channel) {
        ByteBuffer args = ByteBuffer.allocate(4);
        args.putShort((short) AMQPMethod.CLASS_CONFIRM);
        args.putShort((short) AMQPMethod.CONFIRM_SELECT_OK);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer serverBasicAckFrame(int channel, long deliveryTag, boolean multiple) {
        ByteBuffer args = ByteBuffer.allocate(4 + 8 + 1);
        args.putShort((short) AMQPMethod.CLASS_BASIC);
        args.putShort((short) AMQPMethod.BASIC_ACK);
        args.putLong(deliveryTag);
        args.put(AMQPBits.pack(multiple));
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer serverBasicNackFrame(int channel, long deliveryTag, boolean multiple) {
        ByteBuffer args = ByteBuffer.allocate(4 + 8 + 1);
        args.putShort((short) AMQPMethod.CLASS_BASIC);
        args.putShort((short) AMQPMethod.BASIC_NACK);
        args.putLong(deliveryTag);
        args.put(AMQPBits.pack(multiple, false));
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, channel, args);
    }

    @Test
    public void testConfirmSelectRoundTrip() {
        ClientChannel channel = openChannel();
        final boolean[] ok = new boolean[1];

        channel.confirmSelect(new ServerConfirmSelectHandler() {
            @Override public void handleConfirmSelectOk() { ok[0] = true; }
        });
        lastSentMethodArgs(AMQPMethod.CLASS_CONFIRM, AMQPMethod.CONFIRM_SELECT);
        assertFalse(ok[0]);
        feed(serverConfirmSelectOkFrame(1));
        assertTrue(ok[0]);
    }

    @Test
    public void testPublishSequenceNumbersStartAtZeroBeforeConfirmSelect() {
        ClientChannel channel = openChannel();
        PublishBody body = channel.basicPublish("ex", "rk", false, null, 0);
        assertEquals(0L, body.getSequenceNumber());
    }

    @Test
    public void testPublishSequenceNumbersIncrementAfterConfirmSelect() {
        ClientChannel channel = openChannel();
        channel.confirmSelect(new ServerConfirmSelectHandler() {
            @Override public void handleConfirmSelectOk() { }
        });
        feed(serverConfirmSelectOkFrame(1));

        PublishBody first = channel.basicPublish("ex", "rk", false, null, 0);
        PublishBody second = channel.basicPublish("ex", "rk", false, null, 0);
        PublishBody third = channel.basicPublish("ex", "rk", false, null, 0);

        assertEquals(1L, first.getSequenceNumber());
        assertEquals(2L, second.getSequenceNumber());
        assertEquals(3L, third.getSequenceNumber());
    }

    @Test
    public void testConfirmAckAndNackRoutedToListener() {
        ClientChannel channel = openChannel();
        channel.confirmSelect(new ServerConfirmSelectHandler() {
            @Override public void handleConfirmSelectOk() { }
        });
        feed(serverConfirmSelectOkFrame(1));

        final List<long[]> acks = new ArrayList<>();
        final List<long[]> nacks = new ArrayList<>();
        channel.setConfirmListener(new org.bluezoo.gumdrop.amqp.client.handler.ConfirmListener() {
            @Override
            public void onAck(long sequenceNumber, boolean multiple) {
                acks.add(new long[] { sequenceNumber, multiple ? 1 : 0 });
            }

            @Override
            public void onNack(long sequenceNumber, boolean multiple) {
                nacks.add(new long[] { sequenceNumber, multiple ? 1 : 0 });
            }
        });

        channel.basicPublish("ex", "rk", false, null, 0);
        channel.basicPublish("ex", "rk", false, null, 0);
        channel.basicPublish("ex", "rk", false, null, 0);

        feed(serverBasicAckFrame(1, 2L, true)); // acks sequence 1 and 2
        feed(serverBasicNackFrame(1, 3L, false));

        assertEquals(1, acks.size());
        assertArrayEquals(new long[] { 2L, 1 }, acks.get(0));
        assertEquals(1, nacks.size());
        assertArrayEquals(new long[] { 3L, 0 }, nacks.get(0));
    }

    @Test
    public void testConfirmAckWithoutListenerDoesNotError() {
        ClientChannel channel = openChannel();
        channel.confirmSelect(new ServerConfirmSelectHandler() {
            @Override public void handleConfirmSelectOk() { }
        });
        feed(serverConfirmSelectOkFrame(1));
        channel.basicPublish("ex", "rk", false, null, 0);

        feed(serverBasicAckFrame(1, 1L, false));

        assertNull(recording.lastError);
    }

    private static final class RecordingDeliveryHandler implements DeliveryHandler {
        String consumerTag;
        long deliveryTag;
        boolean propertiesReceived;
        long bodySize;
        boolean completed;
        int chunkCount;
        private final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();

        @Override
        public void onDeliveryStart(String consumerTag, long deliveryTag, boolean redelivered,
                String exchange, String routingKey) {
            this.consumerTag = consumerTag;
            this.deliveryTag = deliveryTag;
        }

        @Override
        public void onDeliveryProperties(BasicProperties properties, long bodySize) {
            this.propertiesReceived = true;
            this.bodySize = bodySize;
        }

        @Override
        public void onDeliveryBodyChunk(ByteBuffer chunk) {
            chunkCount++;
            byte[] data = new byte[chunk.remaining()];
            chunk.get(data);
            body.writeBytes(data);
        }

        @Override
        public void onDeliveryComplete() {
            completed = true;
        }

        String receivedBody() {
            return new String(body.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    // ── SASL mechanisms (issue #188) ──

    private static ByteBuffer serverSecureFrame(byte[] challenge) {
        ByteBuffer args = ByteBuffer.allocate(4 + 4 + challenge.length);
        args.putShort((short) AMQPMethod.CLASS_CONNECTION);
        args.putShort((short) AMQPMethod.CONNECTION_SECURE);
        args.putInt(challenge.length);
        args.put(challenge);
        args.flip();
        return AMQPFrame.encode(AMQPFrame.TYPE_METHOD, 0, args);
    }

    /** A trivial two-step mechanism: sends an empty initial response, then echoes the challenge back. */
    private static final class TwoStepMechanism implements org.bluezoo.gumdrop.auth.SASLClientMechanism {
        private int step;
        private boolean complete;

        @Override
        public String getMechanismName() { return "X-TWOSTEP"; }

        @Override
        public boolean hasInitialResponse() { return true; }

        @Override
        public byte[] evaluateChallenge(byte[] challenge) {
            step++;
            if (step == 1) {
                return new byte[0];
            }
            complete = true;
            return challenge;
        }

        @Override
        public boolean isComplete() { return complete; }
    }

    @Test
    public void testStartOkSentWithAMQPLainMechanism() throws AMQPProtocolException {
        connect();
        feed(serverStartFrame());
        recording.lastHandshake.startOk(new AMQPLainClientMechanism("guest", "guest"),
                new ServerTuneHandler() {
                    @Override
                    public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) { }
                });

        ByteBuffer sent = lastSentMethodArgs(AMQPMethod.CLASS_CONNECTION, AMQPMethod.CONNECTION_START_OK);
        int tableLen = sent.getInt();
        FieldTable clientProperties = FieldTable.decode(sent, tableLen);
        assertEquals("gumdrop", clientProperties.get("product"));
        String mechanism = FieldTable.getShortString(sent);
        assertEquals("AMQPLAIN", mechanism);
    }

    @Test
    public void testMultiStepMechanismRespondsToConnectionSecure() {
        connect();
        feed(serverStartFrame());
        final List<Boolean> tuned = new ArrayList<>();
        recording.lastHandshake.startOk(new TwoStepMechanism(), new ServerTuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tunedState) {
                tuned.add(Boolean.TRUE);
            }
        });

        // First frame out is start-ok with an empty initial response.
        ByteBuffer startOkArgs = lastSentMethodArgs(AMQPMethod.CLASS_CONNECTION, AMQPMethod.CONNECTION_START_OK);

        // Broker asks for another round via connection.secure.
        byte[] challenge = "round-two".getBytes(StandardCharsets.US_ASCII);
        feed(serverSecureFrame(challenge));

        ByteBuffer secureOkArgs = lastSentMethodArgs(AMQPMethod.CLASS_CONNECTION, AMQPMethod.CONNECTION_SECURE_OK);
        int len = secureOkArgs.getInt();
        byte[] echoed = new byte[len];
        secureOkArgs.get(echoed);
        assertArrayEquals(challenge, echoed);

        assertTrue("handshake must not be tuned before connection.tune arrives", tuned.isEmpty());
        feed(serverTuneFrame(0, 131072, 0));
        assertEquals(1, tuned.size());
    }

    @Test
    public void testGssapiStyleMechanismOffloadedToExecutor() throws Exception {
        connect();
        feed(serverStartFrame());
        final CountDownLatch evaluated = new CountDownLatch(1);
        final List<Thread> evaluatedOn = new ArrayList<>();
        org.bluezoo.gumdrop.auth.SASLClientMechanism recordingMechanism =
                new org.bluezoo.gumdrop.auth.SASLClientMechanism() {
                    private boolean complete;

                    @Override
                    public String getMechanismName() { return "X-RECORD"; }

                    @Override
                    public boolean hasInitialResponse() { return true; }

                    @Override
                    public byte[] evaluateChallenge(byte[] challenge) {
                        evaluatedOn.add(Thread.currentThread());
                        complete = true;
                        evaluated.countDown();
                        return new byte[0];
                    }

                    @Override
                    public boolean isComplete() { return complete; }
                };

        final Thread testThread = Thread.currentThread();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            recording.lastHandshake.startOk(recordingMechanism,
                    new ServerTuneHandler() {
                        @Override
                        public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) { }
                    },
                    executor);
            assertTrue("challenge evaluation must complete on executor",
                    evaluated.await(5, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue("executor must finish dispatching start-ok back onto the endpoint",
                    executor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            if (!executor.isShutdown()) {
                executor.shutdown();
            }
        }

        assertEquals(1, evaluatedOn.size());
        assertNotEquals("challenge evaluation must be offloaded off the calling thread",
                testThread, evaluatedOn.get(0));

        ByteBuffer sent = lastSentMethodArgs(AMQPMethod.CLASS_CONNECTION, AMQPMethod.CONNECTION_START_OK);
        int tableLen = sent.getInt();
        FieldTable.decode(sent, tableLen);
        assertEquals("X-RECORD", FieldTable.getShortString(sent));
    }

    // ── Stubs ──

    private static final class RecordingHandler implements ConnectionReady {
        boolean connectedCalled;
        FieldTable lastServerProperties;
        String lastMechanisms;
        ClientHandshake lastHandshake;
        Exception lastError;

        @Override
        public void onConnected(Endpoint endpoint) {
            connectedCalled = true;
        }

        @Override
        public void handleStart(FieldTable serverProperties, String mechanisms, String locales,
                ClientHandshake handshake) {
            this.lastServerProperties = serverProperties;
            this.lastMechanisms = mechanisms;
            this.lastHandshake = handshake;
        }

        @Override
        public void onConnectionClosed(int replyCode, String replyText) {
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onError(Exception cause) {
            this.lastError = cause;
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }
    }

    private static final class StubEndpoint implements Endpoint {
        final List<ByteBuffer> sent = new ArrayList<>();
        boolean closed;

        @Override
        public void send(ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            sent.add(ByteBuffer.wrap(copy));
        }

        @Override
        public boolean isOpen() { return !closed; }

        @Override
        public boolean isClosing() { return false; }

        @Override
        public void close() { closed = true; }

        @Override
        public SocketAddress getLocalAddress() {
            return new InetSocketAddress("localhost", 5672);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 50000);
        }

        @Override
        public boolean isSecure() { return false; }

        @Override
        public SecurityInfo getSecurityInfo() { return null; }

        @Override
        public void startTLS() { }

        @Override
        public void pauseRead() { }

        @Override
        public void resumeRead() { }

        @Override
        public void onWriteReady(Runnable callback) { }

        @Override
        public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() { return null; }

        @Override
        public void execute(Runnable task) { task.run(); }

        @Override
        public void setTrace(org.bluezoo.gumdrop.telemetry.Trace trace) { }

        @Override
        public org.bluezoo.gumdrop.telemetry.Trace getTrace() { return null; }

        @Override
        public boolean isTelemetryEnabled() { return false; }

        @Override
        public org.bluezoo.gumdrop.telemetry.TelemetryConfig getTelemetryConfig() { return null; }

        @Override
        public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
    }
}
