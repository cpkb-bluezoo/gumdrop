/*
 * AmqpClientProtocolHandlerEdgeTest.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.amqp.AmqpBits;
import org.bluezoo.gumdrop.amqp.AmqpFrame;
import org.bluezoo.gumdrop.amqp.AmqpFrameParser;
import org.bluezoo.gumdrop.amqp.AmqpMethod;
import org.bluezoo.gumdrop.amqp.BasicProperties;
import org.bluezoo.gumdrop.amqp.FieldTable;
import org.bluezoo.gumdrop.auth.SaslClientMechanism;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Protocol violation, unsolicited reply and optional-listener branches of
 * {@link AmqpClientProtocolHandler}: every reply the broker may send without
 * a matching request must be reported as a protocol error and close the
 * endpoint, content frames must follow their delivery, SASL failures must be
 * surfaced, and absent listeners must be tolerated. The broker is played by
 * hand-built frames over an in-memory endpoint.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AmqpClientProtocolHandlerEdgeTest {

    private static final class Ready implements ConnectionReady {
        ClientHandshake handshake;
        final List<Exception> errors = new ArrayList<Exception>();
        final List<String> closed = new ArrayList<String>();
        int disconnected;

        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void handleStart(FieldTable serverProperties, String mechanisms, String locales,
                ClientHandshake handshake) {
            this.handshake = handshake;
        }

        @Override
        public void onConnectionClosed(int replyCode, String replyText) {
            closed.add(replyCode + " " + replyText);
        }

        @Override
        public void onDisconnected() {
            disconnected++;
        }

        @Override
        public void onError(Exception cause) {
            errors.add(cause);
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }
    }

    private static final class Mechanism implements SaslClientMechanism {
        final boolean initial;
        final int failAtStep;
        int step;

        Mechanism(boolean initial, int failAtStep) {
            this.initial = initial;
            this.failAtStep = failAtStep;
        }

        @Override
        public String getMechanismName() {
            return "X-EDGE";
        }

        @Override
        public boolean hasInitialResponse() {
            return initial;
        }

        @Override
        public byte[] evaluateChallenge(byte[] challenge) throws IOException {
            step++;
            if (step == failAtStep) {
                throw new IOException("sasl refused step " + step);
            }
            return new byte[0];
        }

        @Override
        public boolean isComplete() {
            return false;
        }
    }

    private static final class Deliveries implements DeliveryHandler {
        int starts;
        int completes;
        final StringBuilder body = new StringBuilder();

        @Override
        public void onDeliveryStart(String consumerTag, long deliveryTag, boolean redelivered,
                String exchange, String routingKey) {
            starts++;
        }

        @Override
        public void onDeliveryProperties(BasicProperties properties, long bodySize) {
        }

        @Override
        public void onDeliveryBodyChunk(ByteBuffer chunk) {
            byte[] b = new byte[chunk.remaining()];
            chunk.get(b);
            body.append(new String(b, StandardCharsets.US_ASCII));
        }

        @Override
        public void onDeliveryComplete() {
            completes++;
        }
    }

    private BinaryRecordingEndpoint endpoint;
    private Ready ready;
    private AmqpClientProtocolHandler handler;
    private ClientConnection connection;
    private ClientChannel channel;

    @Before
    public void setUp() {
        endpoint = new BinaryRecordingEndpoint();
        ready = new Ready();
        handler = new AmqpClientProtocolHandler(ready);
    }

    // ── frame builders ──

    private static ByteBuffer method(int channel, int classId, int methodId, ByteBuffer rest) {
        int extra = rest != null ? rest.remaining() : 0;
        ByteBuffer args = ByteBuffer.allocate(4 + extra);
        args.putShort((short) classId);
        args.putShort((short) methodId);
        if (rest != null) {
            args.put(rest);
        }
        args.flip();
        return AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channel, args);
    }

    private static ByteBuffer method(int channel, int classId, int methodId) {
        return method(channel, classId, methodId, null);
    }

    private static ByteBuffer startFrame() {
        FieldTable props = new FieldTable().put("product", "Edge");
        ByteBuffer encoded = props.encode();
        ByteBuffer rest = ByteBuffer.allocate(2 + 4 + encoded.remaining() + 4 + 5 + 4 + 5);
        rest.put((byte) 0);
        rest.put((byte) 9);
        rest.putInt(encoded.remaining());
        rest.put(encoded);
        FieldTable.putLongString(rest, "PLAIN");
        FieldTable.putLongString(rest, "en_US");
        rest.flip();
        return method(0, AmqpMethod.CLASS_CONNECTION, AmqpMethod.CONNECTION_START, rest);
    }

    private static ByteBuffer tuneFrame(int channelMax, long frameMax, int heartbeat) {
        ByteBuffer rest = ByteBuffer.allocate(8);
        rest.putShort((short) channelMax);
        rest.putInt((int) frameMax);
        rest.putShort((short) heartbeat);
        rest.flip();
        return method(0, AmqpMethod.CLASS_CONNECTION, AmqpMethod.CONNECTION_TUNE, rest);
    }

    private static ByteBuffer openOkFrame() {
        ByteBuffer rest = ByteBuffer.allocate(1);
        FieldTable.putShortString(rest, "");
        rest.flip();
        return method(0, AmqpMethod.CLASS_CONNECTION, AmqpMethod.CONNECTION_OPEN_OK, rest);
    }

    private static ByteBuffer channelOpenOkFrame(int channel) {
        ByteBuffer rest = ByteBuffer.allocate(4);
        rest.putInt(0);
        rest.flip();
        return method(channel, AmqpMethod.CLASS_CHANNEL, AmqpMethod.CHANNEL_OPEN_OK, rest);
    }

    private static ByteBuffer closeFrame(int channel, int classId, int methodId, int code, String text) {
        ByteBuffer rest = ByteBuffer.allocate(2 + FieldTable.shortStringEncodedSize(text) + 4);
        rest.putShort((short) code);
        FieldTable.putShortString(rest, text);
        rest.putShort((short) 0);
        rest.putShort((short) 0);
        rest.flip();
        return method(channel, classId, methodId, rest);
    }

    private static ByteBuffer consumeOkFrame(int channel, String tag) {
        ByteBuffer rest = ByteBuffer.allocate(FieldTable.shortStringEncodedSize(tag));
        FieldTable.putShortString(rest, tag);
        rest.flip();
        return method(channel, AmqpMethod.CLASS_BASIC, AmqpMethod.BASIC_CONSUME_OK, rest);
    }

    private static ByteBuffer deliverFrame(int channel, String tag, long deliveryTag) {
        ByteBuffer rest = ByteBuffer.allocate(FieldTable.shortStringEncodedSize(tag) + 8 + 1 + 1 + 1);
        FieldTable.putShortString(rest, tag);
        rest.putLong(deliveryTag);
        rest.put(AmqpBits.pack(false));
        FieldTable.putShortString(rest, "");
        FieldTable.putShortString(rest, "");
        rest.flip();
        return method(channel, AmqpMethod.CLASS_BASIC, AmqpMethod.BASIC_DELIVER, rest);
    }

    private static ByteBuffer headerFrame(int channel, long bodySize) {
        return AmqpFrame.encode(AmqpFrame.TYPE_HEADER, channel, new BasicProperties().encode(bodySize));
    }

    private static ByteBuffer bodyFrame(int channel, String content) {
        return AmqpFrame.encode(AmqpFrame.TYPE_BODY, channel,
                ByteBuffer.wrap(content.getBytes(StandardCharsets.US_ASCII)));
    }

    private static ByteBuffer flowFrame(int channel, boolean active) {
        ByteBuffer rest = ByteBuffer.allocate(1);
        rest.put(AmqpBits.pack(active));
        rest.flip();
        return method(channel, AmqpMethod.CLASS_CHANNEL, AmqpMethod.CHANNEL_FLOW, rest);
    }

    private static ByteBuffer ackFrame(int channel, int methodId, long tag) {
        ByteBuffer rest = ByteBuffer.allocate(9);
        rest.putLong(tag);
        rest.put(AmqpBits.pack(false, false));
        rest.flip();
        return method(channel, AmqpMethod.CLASS_BASIC, methodId, rest);
    }

    private void feed(ByteBuffer frame) {
        handler.receive(frame);
    }

    // ── session set-up ──

    private void handshakeTo(final boolean openChannelToo) {
        handler.connected(endpoint);
        feed(startFrame());
        ready.handshake.startOk("guest", "guest", new TuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) {
                tuned.open("/", new OpenHandler() {
                    @Override
                    public void handleOpenOk(ClientConnection conn) {
                        connection = conn;
                        if (openChannelToo) {
                            conn.channelOpen(1, new ChannelOpenHandler() {
                                @Override
                                public void handleChannelOpenOk(ClientChannel ch) {
                                    channel = ch;
                                }
                            });
                        }
                    }
                });
            }
        });
        feed(tuneFrame(0, 131072, 0));
        feed(openOkFrame());
        if (openChannelToo) {
            feed(channelOpenOkFrame(1));
            assertNotNull(channel);
        }
    }

    private void assertErrors(int count) {
        assertEquals(ready.errors.toString(), count, ready.errors.size());
    }

    // ── connection level ──

    @Test
    public void constructorRejectsNullHandler() {
        try {
            new AmqpClientProtocolHandler(null);
            fail("null handler accepted");
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void framingErrorsBeforeConnectingOnlyReachTheHandler() {
        handler.frameError("garbage");
        handler.heartbeatFrame();
        assertErrors(1);
        assertTrue(endpoint.getAllBytes().length == 0);
    }

    @Test
    public void wrongClassOrMethodOnChannelZeroIsAProtocolError() {
        handler.connected(endpoint);
        feed(method(0, AmqpMethod.CLASS_CHANNEL, AmqpMethod.CHANNEL_OPEN_OK));
        assertErrors(1);
        feed(method(0, AmqpMethod.CLASS_CONNECTION, 99));
        assertErrors(2);
        assertEquals(2, endpoint.getCloseCount());
    }

    @Test
    public void handshakeMethodsOutOfSequenceAreProtocolErrors() {
        handler.connected(endpoint);
        feed(method(0, AmqpMethod.CLASS_CONNECTION, AmqpMethod.CONNECTION_SECURE));
        feed(tuneFrame(0, 0, 0));
        feed(openOkFrame());
        feed(method(0, AmqpMethod.CLASS_CONNECTION, AmqpMethod.CONNECTION_CLOSE_OK));
        assertErrors(4);
        feed(startFrame());
        ready.handshake.startOk("guest", "guest", new TuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) {
            }
        });
        feed(startFrame());
        assertErrors(5);
    }

    @Test
    public void zeroFrameMaxInTuneFallsBackToTheDefault() {
        handler.connected(endpoint);
        feed(startFrame());
        ready.handshake.startOk("guest", "guest", new TuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) {
                assertEquals(AmqpFrameParser.DEFAULT_MAX_FRAME_SIZE, frameMax);
            }
        });
        endpoint.clearWrites();
        feed(tuneFrame(5, 0, 30));
        ByteBuffer sent = ByteBuffer.wrap(endpoint.getAllBytes());
        sent.position(AmqpFrame.HEADER_SIZE);
        assertEquals(AmqpMethod.CLASS_CONNECTION, sent.getShort() & 0xFFFF);
        assertEquals(AmqpMethod.CONNECTION_TUNE_OK, sent.getShort() & 0xFFFF);
        assertEquals(5, sent.getShort() & 0xFFFF);
        assertEquals(AmqpFrameParser.DEFAULT_MAX_FRAME_SIZE, sent.getInt());
        assertEquals(30, sent.getShort() & 0xFFFF);
    }

    @Test
    public void brokerInitiatedConnectionCloseIsAcknowledgedAndReported() {
        handshakeTo(false);
        endpoint.clearWrites();
        feed(closeFrame(0, AmqpMethod.CLASS_CONNECTION, AmqpMethod.CONNECTION_CLOSE, 320, "shutdown"));
        assertEquals(1, ready.closed.size());
        assertEquals("320 shutdown", ready.closed.get(0));
        assertTrue(endpoint.getAllBytes().length > 0);
    }

    @Test
    public void clientInitiatedConnectionCloseCompletesOnCloseOk() {
        handshakeTo(false);
        final int[] done = new int[1];
        connection.close(200, "bye", new CloseHandler() {
            @Override
            public void handleCloseOk() {
                done[0]++;
            }
        });
        feed(method(0, AmqpMethod.CLASS_CONNECTION, AmqpMethod.CONNECTION_CLOSE_OK));
        assertEquals(1, done[0]);
        assertErrors(0);
        feed(method(0, AmqpMethod.CLASS_CONNECTION, AmqpMethod.CONNECTION_CLOSE_OK));
        assertErrors(1);
    }

    // ── SASL ──

    @Test
    public void mechanismWithoutInitialResponseSendsAnEmptyStartOk() {
        handler.connected(endpoint);
        feed(startFrame());
        endpoint.clearWrites();
        ready.handshake.startOk(new Mechanism(false, 0), new TuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) {
            }
        });
        ByteBuffer sent = ByteBuffer.wrap(endpoint.getAllBytes());
        sent.position(AmqpFrame.HEADER_SIZE + 2);
        assertEquals(AmqpMethod.CONNECTION_START_OK, sent.getShort() & 0xFFFF);
        assertErrors(0);
    }

    @Test
    public void mechanismFailingOnItsInitialResponseClosesTheConnection() {
        handler.connected(endpoint);
        feed(startFrame());
        ready.handshake.startOk(new Mechanism(true, 1), new TuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) {
            }
        });
        assertErrors(1);
        assertEquals(1, endpoint.getCloseCount());
    }

    @Test
    public void mechanismFailingOnASecondRoundClosesTheConnection() {
        handler.connected(endpoint);
        feed(startFrame());
        ready.handshake.startOk(new Mechanism(true, 2), new TuneHandler() {
            @Override
            public void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned tuned) {
            }
        });
        ByteBuffer rest = ByteBuffer.allocate(4 + 3);
        rest.putInt(3);
        rest.put(new byte[] {1, 2, 3});
        rest.flip();
        feed(method(0, AmqpMethod.CLASS_CONNECTION, AmqpMethod.CONNECTION_SECURE, rest));
        assertErrors(1);
        assertEquals(1, endpoint.getCloseCount());
    }

    // ── channels ──

    @Test
    public void channelIdsInUseCannotBeOpenedAgain() {
        handshakeTo(true);
        try {
            connection.channelOpen(1, new ChannelOpenHandler() {
                @Override
                public void handleChannelOpenOk(ClientChannel ch) {
                }
            });
            fail("open channel reused");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        connection.channelOpen(2, new ChannelOpenHandler() {
            @Override
            public void handleChannelOpenOk(ClientChannel ch) {
            }
        });
        try {
            connection.channelOpen(2, new ChannelOpenHandler() {
                @Override
                public void handleChannelOpenOk(ClientChannel ch) {
                }
            });
            fail("pending channel reused");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void unsolicitedRepliesAreProtocolErrors() {
        handshakeTo(true);
        int[][] unsolicited = {
            {AmqpMethod.CLASS_CHANNEL, AmqpMethod.CHANNEL_OPEN_OK},
            {AmqpMethod.CLASS_CHANNEL, AmqpMethod.CHANNEL_CLOSE_OK},
            {AmqpMethod.CLASS_CHANNEL, AmqpMethod.CHANNEL_FLOW_OK},
            {AmqpMethod.CLASS_EXCHANGE, AmqpMethod.EXCHANGE_DECLARE_OK},
            {AmqpMethod.CLASS_QUEUE, AmqpMethod.QUEUE_DECLARE_OK},
            {AmqpMethod.CLASS_QUEUE, AmqpMethod.QUEUE_BIND_OK},
            {AmqpMethod.CLASS_BASIC, AmqpMethod.BASIC_CONSUME_OK},
            {AmqpMethod.CLASS_BASIC, AmqpMethod.BASIC_CANCEL_OK},
            {AmqpMethod.CLASS_TX, AmqpMethod.TX_SELECT_OK},
            {AmqpMethod.CLASS_TX, AmqpMethod.TX_COMMIT_OK},
            {AmqpMethod.CLASS_TX, AmqpMethod.TX_ROLLBACK_OK},
            {AmqpMethod.CLASS_CONFIRM, AmqpMethod.CONFIRM_SELECT_OK},
            {AmqpMethod.CLASS_CHANNEL, 99},
            {99, 1},
        };
        for (int i = 0; i < unsolicited.length; i++) {
            feed(method(1, unsolicited[i][0], unsolicited[i][1]));
            assertErrors(i + 1);
        }
    }

    @Test
    public void methodsOnAnUnknownChannelAreProtocolErrorsButContentFramesAreDropped() {
        handshakeTo(false);
        feed(method(9, AmqpMethod.CLASS_EXCHANGE, AmqpMethod.EXCHANGE_DECLARE_OK));
        assertErrors(1);
        feed(headerFrame(9, 0));
        feed(bodyFrame(9, "x"));
        assertErrors(1);
    }

    @Test
    public void brokerClosingAChannelWithoutAListenerOrForAnUnknownChannelIsAcknowledged() {
        handshakeTo(true);
        endpoint.clearWrites();
        feed(closeFrame(1, AmqpMethod.CLASS_CHANNEL, AmqpMethod.CHANNEL_CLOSE, 406, "precondition"));
        assertTrue(endpoint.getAllBytes().length > 0);
        endpoint.clearWrites();
        feed(closeFrame(7, AmqpMethod.CLASS_CHANNEL, AmqpMethod.CHANNEL_CLOSE, 406, "never opened"));
        assertTrue(endpoint.getAllBytes().length > 0);
        assertErrors(0);
    }

    @Test
    public void clientInitiatedChannelCloseDropsTheChannel() {
        handshakeTo(true);
        final int[] done = new int[1];
        channel.close(200, "bye", new ChannelCloseHandler() {
            @Override
            public void handleChannelCloseOk() {
                done[0]++;
            }
        });
        feed(method(1, AmqpMethod.CLASS_CHANNEL, AmqpMethod.CHANNEL_CLOSE_OK));
        assertEquals(1, done[0]);
        feed(method(1, AmqpMethod.CLASS_EXCHANGE, AmqpMethod.EXCHANGE_DECLARE_OK));
        assertErrors(1);
    }

    @Test
    public void brokerFlowAndConfirmsWithoutListenersAreTolerated() {
        handshakeTo(true);
        endpoint.clearWrites();
        feed(flowFrame(1, false));
        assertTrue(endpoint.getAllBytes().length > 0);
        feed(ackFrame(1, AmqpMethod.BASIC_ACK, 4));
        feed(ackFrame(1, AmqpMethod.BASIC_NACK, 5));
        assertErrors(0);
    }

    // ── deliveries ──

    private Deliveries consume() {
        Deliveries deliveries = new Deliveries();
        channel.basicConsume("q", "tag", true, false, null, deliveries, new ConsumeHandler() {
            @Override
            public void handleConsumeOk(String consumerTag) {
            }
        });
        feed(consumeOkFrame(1, "tag"));
        return deliveries;
    }

    @Test
    public void contentFramesWithoutADeliveryAreProtocolErrors() {
        handshakeTo(true);
        consume();
        feed(headerFrame(1, 3));
        assertErrors(1);
        feed(bodyFrame(1, "abc"));
        assertErrors(2);
    }

    @Test
    public void bodyBeforeHeaderAndOverlongBodiesAreProtocolErrors() {
        handshakeTo(true);
        Deliveries deliveries = consume();
        feed(deliverFrame(1, "tag", 1));
        feed(bodyFrame(1, "abc"));
        assertErrors(1);
        feed(headerFrame(1, 2));
        feed(bodyFrame(1, "abc"));
        assertErrors(2);
        assertEquals(0, deliveries.completes);
    }

    @Test
    public void aSecondDeliveryWhileOneIsInProgressIsAProtocolError() {
        handshakeTo(true);
        Deliveries deliveries = consume();
        feed(deliverFrame(1, "tag", 1));
        feed(deliverFrame(1, "tag", 2));
        assertErrors(1);
        assertEquals(1, deliveries.starts);
        feed(headerFrame(1, 2));
        feed(bodyFrame(1, "ok"));
        assertEquals(1, deliveries.completes);
        assertEquals("ok", deliveries.body.toString());
    }

    @Test
    public void malformedMethodArgumentsAreProtocolErrors() {
        handshakeTo(true);
        feed(method(1, AmqpMethod.CLASS_BASIC, AmqpMethod.BASIC_DELIVER));
        assertErrors(1);
        feed(method(0, AmqpMethod.CLASS_CONNECTION, AmqpMethod.CONNECTION_CLOSE));
        assertErrors(2);
    }

    @Test
    public void methodFrameTooShortForItsIdsIsAProtocolError() {
        handshakeTo(true);
        feed(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 1, ByteBuffer.wrap(new byte[] {0, 60})));
        assertErrors(1);
        feed(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, ByteBuffer.allocate(0)));
        assertErrors(2);
    }

    @Test
    public void truncatedContentHeaderIsAProtocolError() {
        handshakeTo(true);
        consume();
        feed(deliverFrame(1, "tag", 1));
        feed(AmqpFrame.encode(AmqpFrame.TYPE_HEADER, 1, ByteBuffer.wrap(new byte[] {0, 60, 0})));
        assertErrors(1);
    }

    @Test
    public void disconnectionAndTransportErrorsAreForwarded() {
        handshakeTo(false);
        handler.error(new IOException("reset"));
        assertErrors(1);
        handler.securityEstablished(null);
        handler.disconnected();
        assertEquals(1, ready.disconnected);
        assertFalse(endpoint.getAllBytes().length == 0);
    }
}
