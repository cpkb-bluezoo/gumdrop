/*
 * FakeAmqp1Peer.java
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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Frame;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1FrameHandler;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1FrameParser;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1ProtocolException;
import org.bluezoo.gumdrop.amqp1.codec.Performative;
import org.bluezoo.gumdrop.amqp1.codec.PerformativeReader;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * An in-process stand-in for the network and the broker: an
 * {@link Endpoint} that records what the client sends (decoding it with
 * the codec) and builds the frames a server would send back. No sockets.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FakeAmqp1Peer implements Endpoint {

    /** One item the client sent: a protocol header, a frame, or a heartbeat. */
    static final class Out {
        final String kind; // "header", "sasl", "amqp" or "heartbeat"
        final int channel;
        final int protocolId;
        final Performative performative;
        /** The octets after the performative in the frame body (a transfer's message bytes). */
        final byte[] payload;

        Out(String kind, int channel, int protocolId, Performative performative, byte[] payload) {
            this.kind = kind;
            this.channel = channel;
            this.protocolId = protocolId;
            this.performative = performative;
            this.payload = payload;
        }

        @Override
        public String toString() {
            if (performative != null) {
                return kind + "/" + channel + " " + performative.getClass().getSimpleName();
            }
            return kind + (kind.equals("header") ? " " + protocolId : "/" + channel);
        }
    }

    /** A scheduled timer the test can fire by hand. */
    static final class Timer implements TimerHandle {
        final long delayMs;
        final Runnable task;
        boolean cancelled;
        boolean fired;

        Timer(long delayMs, Runnable task) {
            this.delayMs = delayMs;
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    final List<Timer> timers = new ArrayList<Timer>();
    boolean closed;

    private final java.io.ByteArrayOutputStream sentBytes = new java.io.ByteArrayOutputStream();
    private int consumed;
    private final List<Out> outputs = new ArrayList<Out>();
    private Amqp1FrameParser outParser;
    private PerformativeReader outReader;
    private int outType;
    private int outChannel;
    private Performative outPerformative;
    private final java.io.ByteArrayOutputStream outPayload = new java.io.ByteArrayOutputStream();

    // ── decoding what the client sent ──

    /** Everything the client has sent so far, decoded, oldest first. */
    List<Out> output() {
        byte[] all = sentBytes.toByteArray();
        if (outParser == null) {
            outReader = new PerformativeReader(1048576);
            outParser = new Amqp1FrameParser(new Amqp1FrameHandler() {
                @Override
                public void protocolHeader(int protocolId, int major, int minor, int revision) {
                    outputs.add(new Out("header", 0, protocolId, null, new byte[0]));
                }

                @Override
                public void startFrame(int type, int channel, int bodyLength) {
                    outType = type;
                    outChannel = channel;
                    outPerformative = null;
                    outPayload.reset();
                    outReader.reset();
                }

                @Override
                public void frameBody(ByteBuffer chunk) {
                    try {
                        if (outPerformative == null) {
                            outPerformative = outReader.receive(chunk);
                        }
                        if (outPerformative != null && chunk.hasRemaining()) {
                            byte[] b = new byte[chunk.remaining()];
                            chunk.get(b);
                            outPayload.write(b, 0, b.length);
                        }
                    } catch (Amqp1ProtocolException e) {
                        throw new AssertionError("client sent a malformed frame: " + e);
                    }
                }

                @Override
                public void endFrame() {
                    if (outPerformative == null) {
                        throw new AssertionError("client sent a frame with no complete performative");
                    }
                    outputs.add(new Out(outType == Amqp1Frame.TYPE_SASL ? "sasl" : "amqp",
                            outChannel, 0, outPerformative, outPayload.toByteArray()));
                }

                @Override
                public void heartbeat(int channel) {
                    outputs.add(new Out("heartbeat", channel, 0, null, new byte[0]));
                }

                @Override
                public void frameError(String message) {
                    throw new AssertionError("client sent a bad frame: " + message);
                }
            });
            // The client's frames are bounded by what it was told the peer accepts
            outParser.setMaxFrameSize(1048576);
        }
        // Split the stream into whole items ourselves: the client sends
        // its AMQP protocol header after the SASL frames, and a header is
        // recognisable because a frame size can never spell "AMQP"
        while (all.length - consumed >= 8) {
            boolean isHeader = all[consumed] == 'A' && all[consumed + 1] == 'M'
                    && all[consumed + 2] == 'Q' && all[consumed + 3] == 'P';
            int length = isHeader ? 8 : ByteBuffer.wrap(all, consumed, 4).getInt();
            if (all.length - consumed < length) {
                break;
            }
            if (isHeader) {
                outParser.expectProtocolHeader();
            }
            outParser.receive(ByteBuffer.wrap(all, consumed, length));
            consumed += length;
        }
        return outputs;
    }

    // ── what a server sends ──

    static ByteBuffer saslHeader() {
        return Amqp1Frame.protocolHeader(Amqp1Frame.PROTOCOL_ID_SASL);
    }

    static ByteBuffer amqpHeader() {
        return Amqp1Frame.protocolHeader(Amqp1Frame.PROTOCOL_ID_AMQP);
    }

    static ByteBuffer saslFrame(Performative p) {
        return Amqp1Frame.encode(Amqp1Frame.TYPE_SASL, 0, p.encode());
    }

    static ByteBuffer amqpFrame(int channel, Performative p) {
        return Amqp1Frame.encode(Amqp1Frame.TYPE_AMQP, channel, p.encode());
    }

    static byte[] concat(ByteBuffer... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < parts.length; i++) {
            ByteBuffer b = parts[i].duplicate();
            byte[] bytes = new byte[b.remaining()];
            b.get(bytes);
            out.write(bytes, 0, bytes.length);
        }
        return out.toByteArray();
    }

    // ── Endpoint ──

    @Override
    public void send(ByteBuffer data) {
        ByteBuffer d = data.duplicate();
        byte[] b = new byte[d.remaining()];
        d.get(b);
        sentBytes.write(b, 0, b.length);
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean isClosing() {
        return false;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", 40000);
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 5672);
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public SecurityInfo getSecurityInfo() {
        return null;
    }

    @Override
    public void startTLS() {
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return null;
    }

    @Override
    public void execute(Runnable task) {
        task.run();
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        Timer t = new Timer(delayMs, callback);
        timers.add(t);
        return t;
    }

    /** Runs every timer that is scheduled and not cancelled, once. */
    void fireTimers() {
        List<Timer> due = new ArrayList<Timer>();
        for (Timer t : timers) {
            if (!t.cancelled && !t.fired) {
                due.add(t);
            }
        }
        for (Timer t : due) {
            t.fired = true;
            t.task.run();
        }
    }

    @Override
    public Trace getTrace() {
        return null;
    }

    @Override
    public void setTrace(Trace trace) {
    }

    @Override
    public boolean isTelemetryEnabled() {
        return false;
    }

    @Override
    public TelemetryConfig getTelemetryConfig() {
        return null;
    }

    @Override
    public void pauseRead() {
    }

    @Override
    public void resumeRead() {
    }

    @Override
    public void onWriteReady(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }
}
