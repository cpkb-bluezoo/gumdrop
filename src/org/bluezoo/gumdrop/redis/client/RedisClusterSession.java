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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private Runnable settledHook;

    // A transaction, and a WATCH, live on one connection. MULTI is answered
    // at once and only sent when the first command of the transaction shows
    // which node it belongs to (unless a WATCH already says).
    private boolean inTransaction;
    private boolean multiPending;
    private ClusterNode transactionNode;
    private int transactionSlot = ClusterCommandKeys.NO_KEY;
    private ClusterNode watchNode;

    RedisClusterSession(SlotMap map, NodeConnector connector) {
        this.map = map;
        this.connector = connector;
    }

    /**
     * Opens a connection to every master now, rather than on first use, so
     * the first command does not wait for one and unreachable nodes show up
     * at once.
     *
     * @param onSettled run once, when the first of those connections is
     *                  either up or has failed; may be null
     */
    void connectAll(Runnable onSettled) {
        settledHook = onSettled;
        for (ClusterNode node : new ArrayList<ClusterNode>(map.nodes())) {
            connection(node).open();
        }
    }

    private void settled() {
        Runnable hook = settledHook;
        settledHook = null;
        if (hook != null) {
            hook.run();
        }
    }

    /** The slot map in use; for tests. */
    SlotMap slotMap() {
        return map;
    }

    /** How many node connections are up; for tests. */
    int connectionCount() {
        int count = 0;
        for (NodeConnection c : connections.values()) {
            if (c.ready) {
                count++;
            }
        }
        return count;
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
        String name = command.getCommand().toUpperCase(Locale.ROOT);
        if (name.equals("MULTI")) {
            multi(command);
            return;
        } else if (name.equals("EXEC")) {
            endTransaction(command, true);
            return;
        } else if (name.equals("DISCARD")) {
            endTransaction(command, false);
            return;
        } else if (name.equals("WATCH")) {
            watch(command);
            return;
        } else if (name.equals("UNWATCH")) {
            unwatch(command);
            return;
        } else if (inTransaction) {
            routeInTransaction(command);
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

    // ── transactions and watches ──

    private void multi(SentCommand command) {
        if (inTransaction) {
            fail(command, "ERR MULTI calls can not be nested");
            return;
        }
        inTransaction = true;
        transactionNode = watchNode;
        transactionSlot = ClusterCommandKeys.NO_KEY;
        multiPending = true;
        answerOk(command.getCallback());
        if (transactionNode != null) {
            sendMulti();
        }
    }

    private void sendMulti() {
        multiPending = false;
        connection(transactionNode).submit(new SentCommand(null, "MULTI", null, null).pinned());
    }

    private void routeInTransaction(SentCommand command) {
        int slot = ClusterCommandKeys.slotOf(command.getCommand(), command.toBinaryArgs());
        if (slot == ClusterCommandKeys.CROSS_SLOT) {
            fail(command, CROSS_SLOT);
            return;
        }
        if (transactionNode == null) {
            ClusterNode node = (slot == ClusterCommandKeys.NO_KEY) ? anyNode() : map.nodeFor(slot);
            if (node == null) {
                fail(command, slot == ClusterCommandKeys.NO_KEY ? NO_NODES : SLOT_NOT_SERVED);
                return;
            }
            transactionNode = node;
            transactionSlot = slot;
            sendMulti();
        } else if (slot != ClusterCommandKeys.NO_KEY) {
            // Redis requires every key of a transaction to be in one slot
            if (!transactionNode.equals(map.nodeFor(slot))) {
                fail(command, CROSS_SLOT);
                return;
            } else if (transactionSlot == ClusterCommandKeys.NO_KEY) {
                transactionSlot = slot;
            } else if (slot != transactionSlot) {
                fail(command, CROSS_SLOT);
                return;
            }
        }
        // a redirect would move one command out of its transaction
        connection(transactionNode).submit(command.pinned());
    }

    private void endTransaction(SentCommand command, boolean exec) {
        if (!inTransaction) {
            fail(command, "ERR " + (exec ? "EXEC" : "DISCARD") + " without MULTI");
            return;
        }
        ClusterNode node = transactionNode;
        inTransaction = false;
        multiPending = false;
        transactionNode = null;
        transactionSlot = ClusterCommandKeys.NO_KEY;
        watchNode = null;
        if (node == null) {
            // nothing was queued, so no node ever saw the MULTI
            if (exec && command.getCallback() instanceof ArrayResultHandler) {
                ((ArrayResultHandler) command.getCallback()).handleResult(
                        Collections.<org.bluezoo.gumdrop.redis.codec.RespValue>emptyList(), this);
            } else if (!exec) {
                answerOk(command.getCallback());
            }
            return;
        }
        connection(node).submit(command.pinned());
    }

    private void watch(SentCommand command) {
        if (inTransaction) {
            fail(command, "ERR WATCH inside MULTI is not allowed");
            return;
        }
        int slot = ClusterCommandKeys.slotOf(command.getCommand(), command.toBinaryArgs());
        if (slot == ClusterCommandKeys.CROSS_SLOT) {
            fail(command, CROSS_SLOT);
            return;
        } else if (slot == ClusterCommandKeys.NO_KEY) {
            fail(command, "ERR wrong number of arguments for 'watch' command");
            return;
        }
        ClusterNode node = map.nodeFor(slot);
        if (node == null) {
            fail(command, SLOT_NOT_SERVED);
            return;
        } else if (watchNode != null && !watchNode.equals(node)) {
            fail(command, CROSS_SLOT);
            return;
        }
        watchNode = node;
        connection(node).submit(command.pinned());
    }

    private void unwatch(SentCommand command) {
        if (watchNode == null) {
            answerOk(command.getCallback());
            return;
        }
        ClusterNode node = watchNode;
        watchNode = null;
        connection(node).submit(command.pinned());
    }

    /** Answers a command locally with {@code OK}, as the server would. */
    private void answerOk(Object callback) {
        if (callback instanceof StringResultHandler) {
            ((StringResultHandler) callback).handleResult("OK", this);
        } else if (callback instanceof BulkResultHandler) {
            ((BulkResultHandler) callback).handleResult("OK".getBytes(UTF_8), this);
        }
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
        inTransaction = false;
        transactionNode = null;
        watchNode = null;
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
            open();
        }

        /** Starts connecting unless a connection is up or on its way. */
        void open() {
            if (!ready && !connecting) {
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
            settled();
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
            settled();
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
