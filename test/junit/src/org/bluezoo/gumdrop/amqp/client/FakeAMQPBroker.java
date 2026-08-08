/*
 * FakeAMQPBroker.java
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A minimal, in-process AMQP 0-9-1 broker for testing {@link
 * AMQPClientProtocolHandler} / {@link AMQPClientRecovery} end to end over
 * a real socket — deliberately not a real broker (issue #154 explicitly
 * asks for no real broker dependency in the test environment), just
 * enough of the protocol to exercise connect, channel open, exchange/
 * queue declare, bind, publish, consume, ack, publisher confirms, and
 * (via {@link #disconnectAll()}) a forced-disconnect-then-recover
 * scenario.
 *
 * <p>Uses plain blocking {@code java.net} sockets and one thread per
 * connection — this is test support, not a production server, so it
 * doesn't need gumdrop's async I/O machinery. It reuses gumdrop's own
 * AMQP wire-format codec ({@link AMQPFrame}, {@link AMQPFrameParser},
 * {@link FieldTable}, {@link BasicProperties}, and the package-private
 * {@code *Methods} classes) rather than a second, parallel
 * implementation.
 *
 * <p>Routing is deliberately simplistic: the default exchange ({@code
 * ""}) routes directly to the queue named by the routing key (standard
 * AMQP behavior); any other exchange routes by exact routing-key match
 * against queues bound to it via {@code queue.bind} — sufficient for
 * exercising the client, not a real exchange-type implementation
 * ({@code fanout}/{@code topic} wildcard matching aren't implemented).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FakeAMQPBroker implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(FakeAMQPBroker.class.getName());

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private volatile boolean running = true;

    private final List<BrokerConnection> connections = new CopyOnWriteArrayList<BrokerConnection>();
    private final Map<String, BrokerQueue> queues = new ConcurrentHashMap<String, BrokerQueue>();
    /** exchange -> routing key -> queue names bound to it. */
    private final Map<String, Map<String, List<String>>> bindings =
            new ConcurrentHashMap<String, Map<String, List<String>>>();

    private volatile String requiredUsername;
    private volatile String requiredPassword;

    FakeAMQPBroker() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptThread = new Thread(this::acceptLoop, "fake-amqp-broker-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int getPort() {
        return serverSocket.getLocalPort();
    }

    /** If set, {@code connection.start-ok} with different credentials is rejected. */
    void requireCredentials(String username, String password) {
        this.requiredUsername = username;
        this.requiredPassword = password;
    }

    /** Number of messages currently sitting in {@code queue} (delivered-but-unacked messages are not counted). */
    int queueDepth(String queue) {
        BrokerQueue q = queues.get(queue);
        return (q == null) ? 0 : q.backlogSize();
    }

    /**
     * Forcibly closes every currently-open connection's socket without
     * any AMQP-level close handshake — simulates a network drop /
     * broker crash, for testing client reconnect.
     */
    void disconnectAll() {
        for (BrokerConnection c : connections) {
            c.forceClose();
        }
    }

    int connectionCount() {
        return connections.size();
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            // ignore
        }
        disconnectAll();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                BrokerConnection conn = new BrokerConnection(socket);
                connections.add(conn);
                Thread t = new Thread(conn, "fake-amqp-broker-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.FINE, "Accept loop stopped", e);
                }
            }
        }
    }

    // ── Queue / message model ──

    private static final class StoredMessage {
        final String exchange;
        final String routingKey;
        final BasicProperties properties;
        final byte[] body;

        StoredMessage(String exchange, String routingKey, BasicProperties properties, byte[] body) {
            this.exchange = exchange;
            this.routingKey = routingKey;
            this.properties = properties;
            this.body = body;
        }
    }

    private final class BrokerQueue {
        private final List<StoredMessage> backlog = new ArrayList<StoredMessage>();
        private final List<BrokerConnection.Consumer> consumers =
                new CopyOnWriteArrayList<BrokerConnection.Consumer>();

        synchronized void publish(StoredMessage msg) {
            BrokerConnection.Consumer target = nextConsumer();
            if (target != null) {
                target.deliver(msg);
            } else {
                backlog.add(msg);
            }
        }

        synchronized void addConsumer(BrokerConnection.Consumer consumer) {
            consumers.add(consumer);
            while (!backlog.isEmpty()) {
                StoredMessage msg = backlog.remove(0);
                consumer.deliver(msg);
            }
        }

        void removeConsumer(BrokerConnection.Consumer consumer) {
            consumers.remove(consumer);
        }

        synchronized int backlogSize() {
            return backlog.size();
        }

        private BrokerConnection.Consumer nextConsumer() {
            // Simplest possible distribution: always the first registered
            // consumer still around. Good enough for tests, not round-robin.
            return consumers.isEmpty() ? null : consumers.get(0);
        }
    }

    private BrokerQueue queue(String name) {
        return queues.computeIfAbsent(name, n -> new BrokerQueue());
    }

    private void route(String exchange, String routingKey, BasicProperties properties, byte[] body) {
        StoredMessage msg = new StoredMessage(exchange, routingKey, properties, body);
        if (exchange == null || exchange.isEmpty()) {
            // Default exchange: route directly to the queue named by the routing key.
            queue(routingKey).publish(msg);
            return;
        }
        Map<String, List<String>> byRoutingKey = bindings.get(exchange);
        if (byRoutingKey == null) {
            return;
        }
        List<String> boundQueues = byRoutingKey.get(routingKey);
        if (boundQueues == null) {
            return;
        }
        for (String q : boundQueues) {
            queue(q).publish(msg);
        }
    }

    private void bind(String queue, String exchange, String routingKey) {
        bindings.computeIfAbsent(exchange, e -> new ConcurrentHashMap<String, List<String>>())
                .computeIfAbsent(routingKey, rk -> new CopyOnWriteArrayList<String>())
                .add(queue);
    }

    // ── Per-connection protocol driver ──

    private final class BrokerConnection implements Runnable, AMQPFrameHandler {

        private final Socket socket;
        private final Object writeLock = new Object();
        private OutputStream out;
        private final AtomicLong deliveryTagSeq = new AtomicLong();
        private final Map<Long, String> unacked = new ConcurrentHashMap<Long, String>();
        private boolean confirmsEnabled;
        private long confirmSeq;

        // Pending inbound-publish state while header/body frames arrive.
        private BasicMethods.Publish pendingPublish;
        private BasicProperties.Header pendingHeader;
        private java.io.ByteArrayOutputStream pendingBody;
        private long pendingBodyRemaining = -1;

        BrokerConnection(Socket socket) {
            this.socket = socket;
        }

        void forceClose() {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }

        @Override
        public void run() {
            try {
                InputStream in = socket.getInputStream();
                out = socket.getOutputStream();

                byte[] header = new byte[8];
                if (!readFully(in, header)) {
                    return;
                }
                // Not validating the exact protocol header bytes — a fake
                // broker doesn't need to be a protocol-negotiation
                // conformance suite.

                sendConnectionStart();

                AMQPFrameParser parser = new AMQPFrameParser(this);
                ByteBuffer buf = ByteBuffer.allocate(65536);
                byte[] readBuf = new byte[8192];
                while (true) {
                    int n = in.read(readBuf);
                    if (n < 0) {
                        break;
                    }
                    if (buf.remaining() < n) {
                        ByteBuffer bigger = ByteBuffer.allocate(Math.max(buf.capacity() * 2, buf.position() + n));
                        buf.flip();
                        bigger.put(buf);
                        buf = bigger;
                    }
                    buf.put(readBuf, 0, n);
                    buf.flip();
                    parser.receive(buf);
                    buf.compact();
                }
            } catch (IOException e) {
                // Connection dropped — expected for forceClose()-driven tests.
            } finally {
                connections.remove(this);
                cleanupConsumers();
                forceClose();
            }
        }

        private final Map<String, Consumer> consumersByTag = new HashMap<String, Consumer>();

        private void cleanupConsumers() {
            for (Consumer c : consumersByTag.values()) {
                c.queue.removeConsumer(c);
            }
            consumersByTag.clear();
        }

        private boolean readFully(InputStream in, byte[] dest) throws IOException {
            int total = 0;
            while (total < dest.length) {
                int n = in.read(dest, total, dest.length - total);
                if (n < 0) {
                    return false;
                }
                total += n;
            }
            return true;
        }

        private void send(int type, int channel, ByteBuffer payload) {
            ByteBuffer frame = AMQPFrame.encode(type, channel, payload);
            byte[] bytes = new byte[frame.remaining()];
            frame.get(bytes);
            synchronized (writeLock) {
                try {
                    out.write(bytes);
                    out.flush();
                } catch (IOException e) {
                    // Peer gone; nothing more to do.
                }
            }
        }

        private void sendConnectionStart() {
            FieldTable serverProps = new FieldTable().put("product", "gumdrop-fake-broker");
            send(AMQPFrame.TYPE_METHOD, 0,
                    ConnectionMethods.encodeStart(0, 9, serverProps, "PLAIN AMQPLAIN EXTERNAL", "en_US"));
        }

        @Override
        public void methodFrame(int channel, ByteBuffer payload) {
            int classId = payload.getShort() & 0xFFFF;
            int methodId = payload.getShort() & 0xFFFF;
            try {
                dispatch(channel, classId, methodId, payload);
            } catch (AMQPProtocolException e) {
                LOGGER.log(Level.WARNING, "Fake broker: malformed method", e);
                forceClose();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Fake broker: error handling method", e);
                forceClose();
            }
        }

        private void dispatch(int channel, int classId, int methodId, ByteBuffer payload)
                throws AMQPProtocolException {
            if (classId == AMQPMethod.CLASS_CONNECTION) {
                switch (methodId) {
                    case AMQPMethod.CONNECTION_START_OK: {
                        ConnectionMethods.StartOk startOk = ConnectionMethods.decodeStartOk(payload);
                        if (requiredUsername != null
                                && !credentialsMatch(startOk.mechanism, startOk.response)) {
                            send(AMQPFrame.TYPE_METHOD, 0,
                                    ConnectionMethods.encodeClose(530, "NOT_ALLOWED - invalid credentials"));
                            return;
                        }
                        send(AMQPFrame.TYPE_METHOD, 0, ConnectionMethods.encodeTune(0, 131072, 0));
                        return;
                    }
                    case AMQPMethod.CONNECTION_TUNE_OK:
                        ConnectionMethods.decodeTuneOk(payload);
                        return;
                    case AMQPMethod.CONNECTION_OPEN:
                        ConnectionMethods.decodeOpen(payload);
                        send(AMQPFrame.TYPE_METHOD, 0, ConnectionMethods.encodeOpenOk());
                        return;
                    case AMQPMethod.CONNECTION_CLOSE:
                        send(AMQPFrame.TYPE_METHOD, 0, ConnectionMethods.encodeCloseOk());
                        forceClose();
                        return;
                    case AMQPMethod.CONNECTION_CLOSE_OK:
                        return;
                    default:
                        throw new AMQPProtocolException("Unexpected connection method " + methodId);
                }
            }
            if (classId == AMQPMethod.CLASS_CHANNEL) {
                switch (methodId) {
                    case AMQPMethod.CHANNEL_OPEN:
                        ChannelMethods.decodeOpen(payload);
                        send(AMQPFrame.TYPE_METHOD, channel, ChannelMethods.encodeOpenOk());
                        return;
                    case AMQPMethod.CHANNEL_CLOSE:
                        send(AMQPFrame.TYPE_METHOD, channel, ChannelMethods.encodeCloseOk());
                        return;
                    case AMQPMethod.CHANNEL_CLOSE_OK:
                        return;
                    default:
                        throw new AMQPProtocolException("Unexpected channel method " + methodId);
                }
            }
            if (classId == AMQPMethod.CLASS_EXCHANGE && methodId == AMQPMethod.EXCHANGE_DECLARE) {
                ExchangeMethods.decodeDeclare(payload);
                send(AMQPFrame.TYPE_METHOD, channel, ExchangeMethods.encodeDeclareOk());
                return;
            }
            if (classId == AMQPMethod.CLASS_QUEUE && methodId == AMQPMethod.QUEUE_DECLARE) {
                QueueMethods.Declare d = QueueMethods.decodeDeclare(payload);
                BrokerQueue q = queue(d.queue);
                send(AMQPFrame.TYPE_METHOD, channel,
                        QueueMethods.encodeDeclareOk(d.queue, q.backlogSize(), 0));
                return;
            }
            if (classId == AMQPMethod.CLASS_QUEUE && methodId == AMQPMethod.QUEUE_BIND) {
                QueueMethods.Bind b = QueueMethods.decodeBind(payload);
                bind(b.queue, b.exchange, b.routingKey);
                send(AMQPFrame.TYPE_METHOD, channel, QueueMethods.encodeBindOk());
                return;
            }
            if (classId == AMQPMethod.CLASS_BASIC) {
                dispatchBasic(channel, methodId, payload);
                return;
            }
            if (classId == AMQPMethod.CLASS_CONFIRM && methodId == AMQPMethod.CONFIRM_SELECT) {
                ConfirmMethods.decodeSelect(payload);
                confirmsEnabled = true;
                confirmSeq = 0;
                send(AMQPFrame.TYPE_METHOD, channel, ConfirmMethods.encodeSelectOk());
                return;
            }
            if (classId == AMQPMethod.CLASS_TX) {
                switch (methodId) {
                    case AMQPMethod.TX_SELECT:
                        send(AMQPFrame.TYPE_METHOD, channel, TxMethods.encodeSelectOk());
                        return;
                    case AMQPMethod.TX_COMMIT:
                        send(AMQPFrame.TYPE_METHOD, channel, TxMethods.encodeCommitOk());
                        return;
                    case AMQPMethod.TX_ROLLBACK:
                        send(AMQPFrame.TYPE_METHOD, channel, TxMethods.encodeRollbackOk());
                        return;
                    default:
                        throw new AMQPProtocolException("Unexpected tx method " + methodId);
                }
            }
            throw new AMQPProtocolException("Unhandled class " + classId + " method " + methodId);
        }

        private void dispatchBasic(int channel, int methodId, ByteBuffer payload)
                throws AMQPProtocolException {
            switch (methodId) {
                case AMQPMethod.BASIC_QOS:
                    send(AMQPFrame.TYPE_METHOD, channel, basicQosOk());
                    return;
                case AMQPMethod.BASIC_CONSUME: {
                    BasicMethods.Consume c = BasicMethods.decodeConsume(payload);
                    String tag = c.consumerTag.isEmpty()
                            ? "server-tag-" + consumersByTag.size() : c.consumerTag;
                    Consumer consumer = new Consumer(channel, tag, queue(c.queue));
                    consumersByTag.put(tag, consumer);
                    consumer.queue.addConsumer(consumer);
                    send(AMQPFrame.TYPE_METHOD, channel, BasicMethods.encodeConsumeOk(tag));
                    return;
                }
                case AMQPMethod.BASIC_CANCEL: {
                    String tag = BasicMethods.decodeCancel(payload);
                    Consumer consumer = consumersByTag.remove(tag);
                    if (consumer != null) {
                        consumer.queue.removeConsumer(consumer);
                    }
                    send(AMQPFrame.TYPE_METHOD, channel, BasicMethods.encodeCancelOk(tag));
                    return;
                }
                case AMQPMethod.BASIC_PUBLISH:
                    pendingPublish = BasicMethods.decodePublish(payload);
                    return;
                case AMQPMethod.BASIC_ACK:
                case AMQPMethod.BASIC_NACK:
                case AMQPMethod.BASIC_REJECT:
                    // Delivery acknowledgment from the client; a fake broker
                    // doesn't need to act on it beyond bookkeeping.
                    return;
                default:
                    throw new AMQPProtocolException("Unexpected basic method " + methodId);
            }
        }

        private ByteBuffer basicQosOk() {
            ByteBuffer buf = ByteBuffer.allocate(4);
            buf.putShort((short) AMQPMethod.CLASS_BASIC);
            buf.putShort((short) AMQPMethod.BASIC_QOS_OK);
            buf.flip();
            return buf;
        }

        /** Issue #188 — also accepts AMQPLAIN, matching what the real client now offers. */
        private boolean credentialsMatch(String mechanism, byte[] response) {
            if ("AMQPLAIN".equals(mechanism)) {
                try {
                    FieldTable table = FieldTable.decode(ByteBuffer.wrap(response), response.length);
                    return requiredUsername.equals(table.get("LOGIN"))
                            && requiredPassword.equals(table.get("PASSWORD"));
                } catch (AMQPProtocolException e) {
                    return false;
                }
            }
            String s = new String(response, StandardCharsets.UTF_8);
            String[] parts = s.split("\0", -1);
            if (parts.length != 3) {
                return false;
            }
            return requiredUsername.equals(parts[1]) && requiredPassword.equals(parts[2]);
        }

        @Override
        public void headerFrame(int channel, ByteBuffer payload) {
            try {
                pendingHeader = BasicProperties.decode(payload);
            } catch (AMQPProtocolException e) {
                LOGGER.log(Level.WARNING, "Fake broker: malformed content-header", e);
                forceClose();
                return;
            }
            pendingBodyRemaining = pendingHeader.getBodySize();
            pendingBody = new java.io.ByteArrayOutputStream();
            if (pendingBodyRemaining == 0) {
                completePublish(channel);
            }
        }

        @Override
        public void bodyFrame(int channel, ByteBuffer payload) {
            byte[] chunk = new byte[payload.remaining()];
            payload.get(chunk);
            pendingBody.writeBytes(chunk);
            pendingBodyRemaining -= chunk.length;
            if (pendingBodyRemaining <= 0) {
                completePublish(channel);
            }
        }

        private void completePublish(int channel) {
            if (pendingPublish != null) {
                route(pendingPublish.exchange, pendingPublish.routingKey,
                        pendingHeader.getProperties(), pendingBody.toByteArray());
            }
            if (confirmsEnabled) {
                confirmSeq++;
                send(AMQPFrame.TYPE_METHOD, channel, BasicMethods.encodeAck(confirmSeq, false));
            }
            pendingPublish = null;
            pendingHeader = null;
            pendingBody = null;
            pendingBodyRemaining = -1;
        }

        @Override
        public void heartbeatFrame() {
            send(AMQPFrame.TYPE_HEARTBEAT, 0, ByteBuffer.allocate(0));
        }

        @Override
        public void frameError(String message) {
            LOGGER.log(Level.WARNING, "Fake broker: frame error: {0}", message);
            forceClose();
        }

        private final class Consumer {
            final int channel;
            final String tag;
            final BrokerQueue queue;

            Consumer(int channel, String tag, BrokerQueue queue) {
                this.channel = channel;
                this.tag = tag;
                this.queue = queue;
            }

            void deliver(StoredMessage msg) {
                long deliveryTag = deliveryTagSeq.incrementAndGet();
                unacked.put(deliveryTag, tag);
                send(AMQPFrame.TYPE_METHOD, channel,
                        BasicMethods.encodeDeliver(tag, deliveryTag, false, msg.exchange, msg.routingKey));
                BasicProperties props = (msg.properties != null) ? msg.properties : new BasicProperties();
                send(AMQPFrame.TYPE_HEADER, channel, props.encode(msg.body.length));
                if (msg.body.length > 0) {
                    send(AMQPFrame.TYPE_BODY, channel, ByteBuffer.wrap(msg.body));
                }
            }
        }
    }
}
