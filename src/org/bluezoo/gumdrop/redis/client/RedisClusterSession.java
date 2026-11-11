/*
 * RedisClusterSession.java
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

package org.bluezoo.gumdrop.redis.client;

import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link RedisSession} over a whole Redis Cluster: each command is sent to
 * the node that serves its key's hash slot, over a connection to that node
 * opened on first use. {@code MOVED} and {@code ASK} replies are followed.
 *
 * <p>Single-threaded: used only from the loop thread its connections run on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RedisClusterSession extends AbstractRedisSession {

    /** Redirects followed for one command before its error is given to its handler. */
    static final int MAX_REDIRECTS = 5;

    // The wording Redis itself uses for these errors.
    private static final String CROSS_SLOT =
            "CROSSSLOT Keys in request don't hash to the same slot";
    private static final String SLOT_NOT_SERVED = "CLUSTERDOWN Hash slot not served";
    private static final String NO_NODES = "CLUSTERDOWN No cluster nodes are known";

    private final SlotMap map;
    private final NodeConnector connector;
    private final Map<ClusterNode, NodeConnection> connections =
            new HashMap<ClusterNode, NodeConnection>();
    private boolean closed;
    private int rotor;

    RedisClusterSession(SlotMap map, NodeConnector connector) {
        this.map = map;
        this.connector = connector;
    }

    // ── sending ──

    @Override
    void sendCommand(Object callback, String command) {
        route(new SentCommand(callback, command, null, null));
    }

    @Override
    void sendCommand(Object callback, String command, String[] args) {
        route(new SentCommand(callback, command, args, null));
    }

    @Override
    void sendCommand(Object callback, String command, byte[][] args) {
        route(new SentCommand(callback, command, null, args));
    }

    private void fail(SentCommand command, String error) {
        deliverError(command.getCallback(), error, this);
    }

    private void route(SentCommand command) {
        if (closed) {
            fail(command, RedisClientProtocolHandler.L10N.getString("err.not_connected"));
            return;
        }
        int slot = ClusterCommandKeys.slotOf(command.getCommand(), command.toBinaryArgs());
        ClusterNode node;
        if (slot == ClusterCommandKeys.CROSS_SLOT) {
            fail(command, CROSS_SLOT);
            return;
        } else if (slot == ClusterCommandKeys.NO_KEY) {
            node = anyNode();
            if (node == null) {
                fail(command, NO_NODES);
                return;
            }
        } else {
            node = map.nodeFor(slot);
            if (node == null) {
                fail(command, SLOT_NOT_SERVED);
                return;
            }
        }
        connection(node).submit(command);
    }

    /** A node to run a command that names no key: one already connected if possible. */
    private ClusterNode anyNode() {
        List<ClusterNode> nodes = new ArrayList<ClusterNode>(map.nodes());
        if (nodes.isEmpty()) {
            return null;
        }
        int start = rotor++;
        for (int i = 0; i < nodes.size(); i++) {
            ClusterNode candidate = nodes.get((start + i) % nodes.size());
            NodeConnection c = connections.get(candidate);
            if (c != null && c.ready) {
                return candidate;
            }
        }
        return nodes.get(start % nodes.size());
    }

    private NodeConnection connection(ClusterNode node) {
        NodeConnection c = connections.get(node);
        if (c == null) {
            c = new NodeConnection(node);
            connections.put(node, c);
        }
        return c;
    }

    // ── RedisSession: this session's own lifecycle ──

    @Override
    public void quit() {
        close();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Collection<NodeConnection> all = new ArrayList<NodeConnection>(connections.values());
        for (NodeConnection c : all) {
            c.shutdown();
        }
        connections.clear();
    }

    @Override
    public void reset(StringResultHandler handler) {
        deliverError(handler, "ERR RESET is not supported by a cluster session", this);
    }

    @Override
    public void subscribe(MessageHandler handler, String... channels) {
        throw new UnsupportedOperationException("Pub/Sub on a cluster session is not yet supported");
    }

    @Override
    public void psubscribe(MessageHandler handler, String... patterns) {
        throw new UnsupportedOperationException("Pub/Sub on a cluster session is not yet supported");
    }

    // ── one node ──

    /** The connection to one node: opened on demand, with commands queued until it is up. */
    private final class NodeConnection implements NodeListener {

        private final ClusterNode node;
        private final ArrayDeque<SentCommand> queue = new ArrayDeque<SentCommand>();
        private RedisClientProtocolHandler handler;
        private boolean ready;
        private boolean connecting;

        NodeConnection(ClusterNode node) {
            this.node = node;
        }

        void submit(SentCommand command) {
            if (ready) {
                write(command);
                return;
            }
            queue.add(command);
            if (!connecting) {
                connecting = true;
                connector.connect(node, this);
            }
        }

        private void write(SentCommand command) {
            if (command.isAsking()) {
                handler.sendCommand(null, "ASKING");
            }
            handler.send(command);
        }

        @Override
        public void ready(RedisClientProtocolHandler connection) {
            connecting = false;
            if (closed) {
                connection.close();
                return;
            }
            handler = connection;
            handler.setRedirectListener(new Redirects(node));
            handler.setResultSession(RedisClusterSession.this);
            ready = true;
            SentCommand next;
            while ((next = queue.poll()) != null) {
                write(next);
            }
        }

        @Override
        public void failed(Exception cause) {
            connecting = false;
            ready = false;
            String message = MessageFormat.format(
                    RedisClientProtocolHandler.L10N.getString("err.cluster_connect_failed"),
                    node.getAddress(), cause != null ? cause.getMessage() : "");
            SentCommand next;
            while ((next = queue.poll()) != null) {
                fail(next, message);
            }
        }

        @Override
        public void closed() {
            ready = false;
            connecting = false;
            handler = null;
        }

        void shutdown() {
            String message = RedisClientProtocolHandler.L10N.getString("err.not_connected");
            SentCommand next;
            while ((next = queue.poll()) != null) {
                fail(next, message);
            }
            if (handler != null) {
                handler.close();
            }
            ready = false;
        }
    }

    /** Follows {@code MOVED} and {@code ASK} replies from one node. */
    private final class Redirects implements RedirectListener {

        private final ClusterNode from;

        Redirects(ClusterNode from) {
            this.from = from;
        }

        @Override
        public boolean handleError(SentCommand command, String error) {
            if (closed || command.isPinned() || command.getRedirects() >= MAX_REDIRECTS) {
                return false;
            }
            ClusterRedirect redirect = ClusterRedirect.parse(error);
            if (redirect == null) {
                return false;
            }
            String host = redirect.getHost() != null ? redirect.getHost() : from.getHost();
            ClusterNode target = map.node(host, redirect.getPort());
            // MOVED is permanent; ASK is for this one command during a migration
            if (!redirect.isAsk()) {
                map.set(redirect.getSlot(), target);
            }
            connection(target).submit(command.redirected(redirect.isAsk()));
            return true;
        }
    }
}
