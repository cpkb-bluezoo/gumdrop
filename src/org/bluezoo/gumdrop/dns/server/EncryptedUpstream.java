/*
 * EncryptedUpstream.java
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

package org.bluezoo.gumdrop.dns.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryIdGenerator;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.client.DnsClientTransport;
import org.bluezoo.gumdrop.dns.client.DnsClientTransportHandler;

/**
 * A persistent DoT (RFC 7858) or DoQ (RFC 9250) connection to one upstream
 * server, shared by every query the relay sends there. Queries are pipelined
 * over the connection and matched to their responses by ID.
 *
 * <p>The connection is opened lazily by the first query and reopened by the
 * next query after it closes. An orderly close by the upstream (an idle
 * timeout, or a drain) is routine: queries outstanding on it fail, but the
 * next one reconnects at once. A connection error also fails the queries
 * outstanding, and further reconnects are then held off with an exponential
 * backoff, so that an unreachable upstream is skipped quickly rather than
 * being retried by every query.
 *
 * <p>All work runs on the one {@link SelectorLoop} that the session is bound
 * to, which is also the loop the transport is opened on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class EncryptedUpstream {

    private static final Logger LOGGER =
            Logger.getLogger(EncryptedUpstream.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    static final long INITIAL_BACKOFF_MS = 1000;
    static final long MAX_BACKOFF_MS = 30000;

    /** Creates the transport for a new connection; replaced by tests. */
    interface TransportSource {
        DnsClientTransport create(UpstreamRelayHandler.Transport kind);
    }

    /** Wall clock for reconnect backoff; replaced by tests. */
    interface Clock {
        long millis();
    }

    /** Receives the outcome of one query. */
    interface Result {
        /** @param response the matched response, or null on any failure */
        void onResult(DnsMessage response);
    }

    private final UpstreamRelayHandler.Transport kind;
    private final InetSocketAddress server;
    private final int port;
    private final SelectorLoop loop;
    private final TransportSource source;
    private final Clock clock;
    private final long timeoutMs;

    // Loop thread only.
    private Connection current;
    private int consecutiveFailures;
    private long retryNotBefore;
    private int idExhaustions;

    EncryptedUpstream(UpstreamRelayHandler.Transport kind,
                      InetSocketAddress server, int port, SelectorLoop loop,
                      TransportSource source, Clock clock, long timeoutMs) {
        this.kind = kind;
        this.server = server;
        this.port = port;
        this.loop = loop;
        this.source = source;
        this.clock = clock;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Sends a query and reports the matching response, or null if the
     * connection failed, the query timed out, or the answer did not match.
     * The connection gives the query an ID of its own, different from that
     * of every other query in flight on it, so the {@code data} may carry
     * any ID; it is replaced on the wire. May be called from any thread.
     */
    void query(final byte[] data, final List<DnsQuestion> questions, final Result result) {
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                queryOnLoop(data, questions, result);
            }
        });
    }

    /** How many times a query had to wait because every ID was in use; for tests and metrics. */
    int idExhaustions() {
        return idExhaustions;
    }

    /** How many queries are in flight; for tests and metrics. */
    int inFlight() {
        return current != null ? current.pending.size() : 0;
    }

    /** Closes the connection, failing anything outstanding. May be called from any thread. */
    void close() {
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (current != null) {
                    current.teardown(false);
                }
            }
        });
    }

    private void queryOnLoop(byte[] data,
                             List<DnsQuestion> questions, Result result) {
        if (current == null) {
            if (clock.millis() < retryNotBefore) {
                result.onResult(null);
                return;
            }
            Connection connection = new Connection();
            current = connection;
            if (!connection.open() || connection.dead) {
                result.onResult(null);
                return;
            }
        }
        current.add(new Pending(data, questions, result));
    }

    private void recordFailure() {
        consecutiveFailures++;
        long delay = INITIAL_BACKOFF_MS;
        for (int i = 1; i < consecutiveFailures && delay < MAX_BACKOFF_MS; i++) {
            delay *= 2;
        }
        retryNotBefore = clock.millis() + Math.min(delay, MAX_BACKOFF_MS);
    }

    private static final class Pending {
        // assigned by the connection when the query is sent
        int id;
        byte[] data;
        final List<DnsQuestion> questions;
        final Result result;
        TimerHandle timer;

        Pending(byte[] data, List<DnsQuestion> questions, Result result) {
            this.data = data;
            this.questions = questions;
            this.result = result;
        }
    }

    /** One connection's lifetime; a closed connection is never reused. */
    private final class Connection implements DnsClientTransportHandler {

        private DnsClientTransport transport;
        private boolean ready;
        private boolean dead;
        private final Map<Integer, Pending> pending = new HashMap<Integer, Pending>();
        private final List<Pending> waiting = new ArrayList<Pending>();
        // queries held back because all 65535 IDs are in use
        private final ArrayDeque<Pending> awaitingId = new ArrayDeque<Pending>();

        boolean open() {
            try {
                transport = source.create(kind);
                transport.open(server.getAddress(), port, loop, this);
                return true;
            } catch (IOException e) {
                LOGGER.log(Level.FINE, MessageFormat.format(
                        L10N.getString("fine.encrypted_upstream_open_failed"),
                        kind, server), e);
                teardown(true);
                return false;
            }
        }

        void add(final Pending p) {
            p.timer = transport.scheduleTimer(timeoutMs, new Runnable() {
                @Override
                public void run() {
                    boolean held = awaitingId.remove(p);
                    boolean flying = pending.get(Integer.valueOf(p.id)) == p;
                    if (flying) {
                        pending.remove(Integer.valueOf(p.id));
                        waiting.remove(p);
                    }
                    if (held || flying) {
                        p.result.onResult(null);
                        sendHeldBack();
                    }
                }
            });
            if (!assignId(p)) {
                idExhaustions++;
                awaitingId.add(p);
                return;
            }
            dispatch(p);
        }

        // Gives the query an ID no other query in flight on this connection
        // has, and writes it into the query. False if all are in use.
        private boolean assignId(Pending p) {
            int id = DnsQueryIdGenerator.tryAllocate(pending.keySet());
            if (id < 0) {
                return false;
            }
            byte[] wire = p.data.clone();
            wire[0] = (byte) (id >> 8);
            wire[1] = (byte) id;
            p.id = id;
            p.data = wire;
            pending.put(Integer.valueOf(id), p);
            return true;
        }

        private void dispatch(Pending p) {
            if (ready) {
                transport.send(ByteBuffer.wrap(p.data));
            } else {
                waiting.add(p);
            }
        }

        // IDs have been freed: let the queries held back for want of one go.
        private void sendHeldBack() {
            while (!awaitingId.isEmpty() && !dead) {
                Pending next = awaitingId.peek();
                if (!assignId(next)) {
                    return;
                }
                awaitingId.poll();
                dispatch(next);
            }
        }

        @Override
        public void onConnected() {
            if (dead || ready) {
                return;
            }
            ready = true;
            List<Pending> toSend = new ArrayList<Pending>(waiting);
            waiting.clear();
            for (int i = 0; i < toSend.size(); i++) {
                transport.send(ByteBuffer.wrap(toSend.get(i).data));
            }
        }

        @Override
        public void onReceive(ByteBuffer data) {
            if (dead) {
                return;
            }
            DnsMessage response;
            try {
                response = DnsMessage.parse(data);
            } catch (DnsFormatException e) {
                LOGGER.log(Level.FINE, MessageFormat.format(
                        L10N.getString("fine.encrypted_upstream_malformed"),
                        server), e);
                return;
            }
            Pending p = pending.remove(Integer.valueOf(response.getId()));
            if (p == null) {
                return; // late, duplicate or unsolicited
            }
            if (p.timer != null) {
                p.timer.cancel();
            }
            // RFC 1035 section 4.1.1 / RFC 5452 section 9.1: a response
            // must have QR set and echo the question.
            if (!response.isResponse()
                    || !response.getQuestions().equals(p.questions)) {
                p.result.onResult(null);
                sendHeldBack();
                return;
            }
            consecutiveFailures = 0;
            p.result.onResult(response);
            sendHeldBack();
        }

        @Override
        public void onError(Exception cause) {
            LOGGER.log(Level.FINE, MessageFormat.format(
                    L10N.getString("fine.encrypted_upstream_failed"),
                    kind, server), cause);
            teardown(true);
        }

        @Override
        public void onClosed() {
            teardown(false);
        }

        /** @param failure true to hold off reconnecting for a while */
        void teardown(boolean failure) {
            if (dead) {
                return;
            }
            dead = true;
            if (current == this) {
                current = null;
            }
            if (failure) {
                recordFailure();
            }
            if (transport != null) {
                transport.close();
            }
            List<Pending> failed = new ArrayList<Pending>(pending.values());
            failed.addAll(awaitingId);
            pending.clear();
            awaitingId.clear();
            waiting.clear();
            for (int i = 0; i < failed.size(); i++) {
                Pending p = failed.get(i);
                if (p.timer != null) {
                    p.timer.cancel();
                }
                p.result.onResult(null);
            }
        }
    }
}
