/*
 * FakeAmqp1Broker.java
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

package org.bluezoo.gumdrop.amqp1.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Frame;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1FrameHandler;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1FrameParser;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1ProtocolException;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Begin;
import org.bluezoo.gumdrop.amqp1.codec.Close;
import org.bluezoo.gumdrop.amqp1.codec.DeliveryState;
import org.bluezoo.gumdrop.amqp1.codec.Detach;
import org.bluezoo.gumdrop.amqp1.codec.Disposition;
import org.bluezoo.gumdrop.amqp1.codec.End;
import org.bluezoo.gumdrop.amqp1.codec.Flow;
import org.bluezoo.gumdrop.amqp1.codec.MessageWriter;
import org.bluezoo.gumdrop.amqp1.codec.Open;
import org.bluezoo.gumdrop.amqp1.codec.Performative;
import org.bluezoo.gumdrop.amqp1.codec.PerformativeReader;
import org.bluezoo.gumdrop.amqp1.codec.SaslInit;
import org.bluezoo.gumdrop.amqp1.codec.SaslMechanisms;
import org.bluezoo.gumdrop.amqp1.codec.SaslOutcome;
import org.bluezoo.gumdrop.amqp1.codec.Transfer;

/**
 * A minimal in-process AMQP 1.0 broker on a real loopback socket, built
 * directly on {@code amqp1.codec} (which doubles as a check that the codec
 * carries no client assumptions). It authenticates with SASL {@code PLAIN}
 * or {@code ANONYMOUS}, opens connections and sessions, and keeps one
 * queue per address: a client sender's messages are queued whole and delivered to
 * any client receiver attached to the same address, with credit, streamed
 * multi-frame transfers and dispositions honoured. It is deliberately
 * simple: it exists to give the real client a real socket peer.
 *
 * <p>One global lock guards all broker state, and each connection is
 * served by its own thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FakeAmqp1Broker implements AutoCloseable {

    private static final int FRAME_PAYLOAD = 32768;

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private volatile boolean running = true;

    private final Object lock = new Object();
    private final List<BrokerConnection> connections = new CopyOnWriteArrayList<BrokerConnection>();
    private final Map<String, ArrayDeque<byte[]>> queues = new HashMap<String, ArrayDeque<byte[]>>();
    private final Set<String> missingAddresses = new HashSet<String>();

    private volatile String requiredUsername;
    private volatile String requiredPassword;
    private volatile long idleTimeOutMs;

    private final AtomicInteger connectionsAccepted = new AtomicInteger();
    private final AtomicInteger messagesReceived = new AtomicInteger();
    private final AtomicInteger dispositionsReceived = new AtomicInteger();
    private final AtomicInteger attachesReceived = new AtomicInteger();
    private final AtomicInteger heartbeatsReceived = new AtomicInteger();

    FakeAmqp1Broker() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "fake-amqp1-broker-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int getPort() {
        return serverSocket.getLocalPort();
    }

    /** Requires SASL PLAIN with these credentials; ANONYMOUS is then no longer accepted. */
    void requireCredentials(String username, String password) {
        this.requiredUsername = username;
        this.requiredPassword = password;
    }

    /** Attaching to this address is refused with amqp:not-found. */
    void addMissingAddress(String address) {
        synchronized (lock) {
            missingAddresses.add(address);
        }
    }

    /** Advertises this idle-time-out in each connection's open. */
    void setIdleTimeOut(long ms) {
        this.idleTimeOutMs = ms;
    }

    int connectionsAccepted() {
        return connectionsAccepted.get();
    }

    int messagesReceived() {
        return messagesReceived.get();
    }

    int dispositionsReceived() {
        return dispositionsReceived.get();
    }

    int attachesReceived() {
        return attachesReceived.get();
    }

    int heartbeatsReceived() {
        return heartbeatsReceived.get();
    }

    /**
     * Puts a message with the given body straight into an address's
     * queue, as if another client had sent it.
     */
    void enqueue(String address, byte[] body) {
        synchronized (lock) {
            queue(address).addLast(new Amqp1FrameBytes(body).data);
            dispatch(address);
        }
    }

    int queueDepth(String address) {
        synchronized (lock) {
            return queue(address).size();
        }
    }

    /** Drops every connection without a close: a network failure. */
    void dropConnections() {
        for (BrokerConnection c : connections) {
            c.abort();
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            // closing anyway
        }
        dropConnections();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                connectionsAccepted.incrementAndGet();
                BrokerConnection c = new BrokerConnection(socket);
                connections.add(c);
                Thread t = new Thread(c, "fake-amqp1-broker-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
                return;
            }
        }
    }

    private ArrayDeque<byte[]> queue(String address) {
        ArrayDeque<byte[]> q = queues.get(address);
        if (q == null) {
            q = new ArrayDeque<byte[]>();
            queues.put(address, q);
        }
        return q;
    }

    /** Delivers queued messages to receivers attached to the address. Caller holds the lock. */
    private void dispatch(String address) {
        for (BrokerConnection c : connections) {
            c.pump(address);
        }
    }

    // ── per-session and per-link state ──

    private static final class LinkState {
        final long handle;
        final String name;
        final boolean brokerIsReceiver;
        final String address;
        long credit;
        long deliveryCount;
        // inbound (client sending to us)
        final ByteArrayOutputStream inbound = new ByteArrayOutputStream();
        Long inboundDeliveryId;

        LinkState(long handle, String name, boolean brokerIsReceiver, String address) {
            this.handle = handle;
            this.name = name;
            this.brokerIsReceiver = brokerIsReceiver;
            this.address = address;
        }
    }

    private static final class SessionState {
        final int channel;
        long nextIncomingId;
        long nextOutgoingId;
        long nextDeliveryId;
        final Map<Long, LinkState> links = new HashMap<Long, LinkState>();
        /** Handles we detached ourselves when refusing an attach: the client's answer needs no reply. */
        final Set<Long> refusedHandles = new HashSet<Long>();

        SessionState(int channel) {
            this.channel = channel;
        }
    }

    // ── one client connection ──

    private final class BrokerConnection implements Runnable, Amqp1FrameHandler {

        private final Socket socket;
        private final OutputStream out;
        private final Amqp1FrameParser parser = new Amqp1FrameParser(this);
        private final PerformativeReader reader = new PerformativeReader(1048576);
        private final Map<Integer, SessionState> sessions = new HashMap<Integer, SessionState>();

        private int frameType;
        private int frameChannel;
        private Performative performative;
        private boolean rearm;
        private LinkState transferLink;
        private boolean saslDone;
        private boolean peerMayUseLargeFrames;

        BrokerConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        void abort() {
            try {
                socket.close();
            } catch (IOException e) {
                // gone
            }
        }

        @Override
        public void run() {
            try {
                InputStream in = socket.getInputStream();
                ByteBuffer buf = ByteBuffer.allocate(65536);
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    if (buf.remaining() < n) {
                        ByteBuffer bigger = ByteBuffer.allocate(buf.position() + n + 65536);
                        buf.flip();
                        bigger.put(buf);
                        buf = bigger;
                    }
                    buf.put(chunk, 0, n);
                    buf.flip();
                    synchronized (lock) {
                        parser.receive(buf);
                    }
                    buf.compact();
                }
            } catch (IOException e) {
                // the client went away
            } finally {
                connections.remove(this);
                abort();
            }
        }

        private void write(ByteBuffer data) {
            try {
                byte[] b = new byte[data.remaining()];
                data.get(b);
                synchronized (out) {
                    out.write(b);
                    out.flush();
                }
            } catch (IOException e) {
                abort();
            }
        }

        private void sendSasl(Performative p) {
            write(Amqp1Frame.encode(Amqp1Frame.TYPE_SASL, 0, p.encode()));
        }

        private void sendAmqp(int channel, Performative p) {
            write(Amqp1Frame.encode(Amqp1Frame.TYPE_AMQP, channel, p.encode()));
        }

        // ── Amqp1FrameHandler ──

        @Override
        public void protocolHeader(int protocolId, int major, int minor, int revision) {
            if (protocolId == Amqp1Frame.PROTOCOL_ID_SASL) {
                write(Amqp1Frame.protocolHeader(Amqp1Frame.PROTOCOL_ID_SASL));
                sendSasl(new SaslMechanisms(Arrays.asList("PLAIN", "ANONYMOUS")));
            } else {
                write(Amqp1Frame.protocolHeader(Amqp1Frame.PROTOCOL_ID_AMQP));
            }
        }

        @Override
        public void startFrame(int type, int channel, int bodyLength) {
            frameType = type;
            frameChannel = channel;
            performative = null;
            transferLink = null;
            reader.reset();
        }

        @Override
        public void frameBody(ByteBuffer chunk) {
            try {
                if (performative == null) {
                    performative = reader.receive(chunk);
                    if (performative == null) {
                        return;
                    }
                    handle(performative);
                }
                if (chunk.hasRemaining() && transferLink != null) {
                    byte[] b = new byte[chunk.remaining()];
                    chunk.get(b);
                    transferLink.inbound.write(b, 0, b.length);
                }
            } catch (Amqp1ProtocolException e) {
                abort();
            }
        }

        @Override
        public void endFrame() {
            if (rearm) {
                rearm = false;
                parser.expectProtocolHeader();
            }
            if (performative instanceof Transfer && transferLink != null) {
                Transfer t = (Transfer) performative;
                if (!t.isMore() && !t.isAborted()) {
                    completeInbound(sessions.get(Integer.valueOf(frameChannel)), transferLink);
                } else if (t.isAborted()) {
                    transferLink.inbound.reset();
                }
            }
        }

        @Override
        public void heartbeat(int channel) {
            heartbeatsReceived.incrementAndGet();
        }

        @Override
        public void frameError(String message) {
            abort();
        }

        // ── performatives ──

        private void handle(Performative p) {
            if (p instanceof SaslInit) {
                handleSaslInit((SaslInit) p);
            } else if (p instanceof Open) {
                Open open = new Open("fake-broker");
                open.setMaxFrameSize(1048576);
                if (idleTimeOutMs > 0) {
                    open.setIdleTimeOut(idleTimeOutMs);
                }
                parser.setMaxFrameSize(1048576);
                sendAmqp(0, open);
            } else if (p instanceof Begin) {
                handleBegin(frameChannel, (Begin) p);
            } else if (p instanceof Attach) {
                handleAttach(frameChannel, (Attach) p);
            } else if (p instanceof Flow) {
                handleFlow(frameChannel, (Flow) p);
            } else if (p instanceof Transfer) {
                handleTransfer(frameChannel, (Transfer) p);
            } else if (p instanceof Disposition) {
                if (((Disposition) p).isReceiver()) {
                    dispositionsReceived.incrementAndGet();
                }
            } else if (p instanceof Detach) {
                Detach d = (Detach) p;
                SessionState s = sessions.get(Integer.valueOf(frameChannel));
                if (s != null && s.refusedHandles.remove(Long.valueOf(d.getHandle()))) {
                    return; // the client answering our own detach
                }
                if (s != null) {
                    s.links.remove(Long.valueOf(d.getHandle()));
                }
                sendAmqp(frameChannel, new Detach(d.getHandle(), d.isClosed(), null));
            } else if (p instanceof End) {
                sessions.remove(Integer.valueOf(frameChannel));
                sendAmqp(frameChannel, new End());
            } else if (p instanceof Close) {
                sendAmqp(0, new Close());
                abort();
            }
        }

        private void handleSaslInit(SaslInit init) {
            boolean ok;
            if (requiredUsername != null) {
                ok = false;
                if ("PLAIN".equals(init.getMechanism()) && init.getInitialResponse() != null) {
                    String[] parts = new String(init.getInitialResponse(), StandardCharsets.UTF_8)
                            .split("\0", -1);
                    ok = parts.length == 3 && requiredUsername.equals(parts[1])
                            && requiredPassword.equals(parts[2]);
                }
            } else {
                ok = "PLAIN".equals(init.getMechanism()) || "ANONYMOUS".equals(init.getMechanism());
            }
            sendSasl(new SaslOutcome(ok ? SaslOutcome.OK : SaslOutcome.AUTH, null));
            if (ok) {
                saslDone = true;
                rearm = true;
            } else {
                abort();
            }
        }

        private void handleBegin(int channel, Begin begin) {
            SessionState s = new SessionState(channel);
            sessions.put(Integer.valueOf(channel), s);
            Begin reply = new Begin(0, 100000, 100000);
            reply.setRemoteChannel(Integer.valueOf(channel));
            s.nextIncomingId = begin.getNextOutgoingId();
            sendAmqp(channel, reply);
        }

        private void handleAttach(int channel, Attach a) {
            attachesReceived.incrementAndGet();
            SessionState s = sessions.get(Integer.valueOf(channel));
            if (s == null) {
                abort();
                return;
            }
            boolean clientIsReceiver = a.isReceiver();
            String address = clientIsReceiver
                    ? (a.getSource() == null ? null : a.getSource().getAddress())
                    : (a.getTarget() == null ? null : a.getTarget().getAddress());
            Attach reply = new Attach(a.getName(), a.getHandle(), !clientIsReceiver);
            if (address == null || missingAddresses.contains(address)) {
                // Refuse: reply with null termini, then detach with an error
                sendAmqp(channel, reply);
                s.refusedHandles.add(Long.valueOf(a.getHandle()));
                sendAmqp(channel, new Detach(a.getHandle(), true,
                        new Amqp1Error(Amqp1Error.NOT_FOUND, "no such node: " + address)));
                return;
            }
            reply.setSource(a.getSource());
            reply.setTarget(a.getTarget());
            reply.setSndSettleMode(a.getSndSettleMode());
            reply.setRcvSettleMode(a.getRcvSettleMode());
            LinkState link = new LinkState(a.getHandle(), a.getName(), !clientIsReceiver, address);
            s.links.put(Long.valueOf(a.getHandle()), link);
            if (clientIsReceiver) {
                reply.setInitialDeliveryCount(Long.valueOf(0));
                sendAmqp(channel, reply);
            } else {
                link.deliveryCount = a.getInitialDeliveryCount() == null ? 0L
                        : a.getInitialDeliveryCount().longValue();
                link.credit = 1000;
                sendAmqp(channel, reply);
                Flow f = new Flow(Long.valueOf(s.nextIncomingId), 100000, s.nextOutgoingId, 100000);
                f.setLink(a.getHandle(), link.deliveryCount, link.credit);
                sendAmqp(channel, f);
            }
        }

        private void handleFlow(int channel, Flow f) {
            SessionState s = sessions.get(Integer.valueOf(channel));
            if (s == null || f.getHandle() == null) {
                return;
            }
            LinkState link = s.links.get(f.getHandle());
            if (link == null || link.brokerIsReceiver) {
                return;
            }
            // credit = client's delivery-count + its link-credit - ours
            long dc = f.getDeliveryCount() == null ? link.deliveryCount : f.getDeliveryCount().longValue();
            link.credit = Flow.serialDiff(Flow.serialAdd(dc, f.getLinkCredit().longValue()),
                    link.deliveryCount);
            pump(link.address);
        }

        private void handleTransfer(int channel, Transfer t) {
            SessionState s = sessions.get(Integer.valueOf(channel));
            if (s == null) {
                return;
            }
            s.nextIncomingId = Flow.serialAdd(s.nextIncomingId, 1);
            LinkState link = s.links.get(Long.valueOf(t.getHandle()));
            if (link == null || !link.brokerIsReceiver) {
                return;
            }
            if (t.getDeliveryId() != null) {
                link.inboundDeliveryId = t.getDeliveryId();
                link.inbound.reset();
            }
            transferLink = link;
        }

        /**
         * A whole delivery from a client sender has arrived: queue its
         * octets as they came (so every section is preserved) and accept it.
         */
        private void completeInbound(SessionState s, LinkState link) {
            byte[] message = link.inbound.toByteArray();
            link.inbound.reset();
            link.deliveryCount = Flow.serialAdd(link.deliveryCount, 1);
            link.credit--;
            messagesReceived.incrementAndGet();

            long id = link.inboundDeliveryId == null ? 0L : link.inboundDeliveryId.longValue();
            Disposition d = new Disposition(true, id, null);
            d.setSettled(true);
            d.setState(DeliveryState.accepted());
            sendAmqp(s.channel, d);
            if (link.credit < 500) {
                link.credit += 500;
                Flow f = new Flow(Long.valueOf(s.nextIncomingId), 100000, s.nextOutgoingId, 100000);
                f.setLink(link.handle, link.deliveryCount, link.credit);
                sendAmqp(s.channel, f);
            }
            queue(link.address).addLast(message);
            dispatch(link.address);
        }

        /** Sends queued messages for {@code address} on this connection's receiver links. */
        void pump(String address) {
            for (SessionState s : sessions.values()) {
                for (LinkState link : s.links.values()) {
                    if (link.brokerIsReceiver || !link.address.equals(address)) {
                        continue;
                    }
                    ArrayDeque<byte[]> q = queue(address);
                    while (link.credit > 0 && !q.isEmpty()) {
                        sendMessage(s, link, q.pollFirst());
                    }
                }
            }
        }

        private void sendMessage(SessionState s, LinkState link, byte[] message) {
            long id = s.nextDeliveryId;
            s.nextDeliveryId = Flow.serialAdd(s.nextDeliveryId, 1);
            byte[] tag = ByteBuffer.allocate(4).putInt((int) id).array();
            int off = 0;
            boolean first = true;
            do {
                int n = Math.min(FRAME_PAYLOAD, message.length - off);
                boolean more = off + n < message.length;
                Transfer t = new Transfer(link.handle);
                if (first) {
                    t.setFirst(id, tag, false);
                    first = false;
                }
                t.setMore(more);
                write(Amqp1Frame.encode(Amqp1Frame.TYPE_AMQP, s.channel, t.encode(),
                        ByteBuffer.wrap(message, off, n)));
                s.nextOutgoingId = Flow.serialAdd(s.nextOutgoingId, 1);
                off += n;
            } while (off < message.length);
            link.credit--;
            link.deliveryCount = Flow.serialAdd(link.deliveryCount, 1);
        }
    }

    /** A message body wrapped as the octets of one {@code data} section. */
    private static final class Amqp1FrameBytes {
        final byte[] data;

        Amqp1FrameBytes(byte[] body) {
            ByteBuffer prefix = MessageWriter.dataSectionPrefix(body.length);
            byte[] all = new byte[prefix.remaining() + body.length];
            int p = prefix.remaining();
            prefix.get(all, 0, p);
            System.arraycopy(body, 0, all, p, body.length);
            this.data = all;
        }
    }
}
