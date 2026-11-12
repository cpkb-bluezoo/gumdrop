/*
 * RedisClusterClient.java
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

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.TlsConfig;

/**
 * Client for a Redis or Valkey Cluster.
 *
 * <p>Given one or more seed nodes, it loads the cluster's topology
 * ({@code CLUSTER SLOTS}) and hands the application a single
 * {@link RedisSession}. Commands sent on that session are routed to the node
 * that serves each key's hash slot, over one connection per node opened on
 * demand; {@code MOVED} and {@code ASK} redirections are followed. The
 * session offers the same API as a single-node one, with these differences
 * that follow from the cluster:
 * <ul>
 * <li>a command naming keys in more than one slot fails at once with
 *     {@code CROSSSLOT}; use {@code {hash tags}} to keep related keys
 *     together;</li>
 * <li>a {@code MULTI} transaction, and a {@code WATCH}, are kept on one
 *     node, and every key in a transaction must be in one slot;</li>
 * <li>a command that names no key ({@code KEYS}, {@code DBSIZE},
 *     {@code FLUSHALL}, and so on) runs on one node only;</li>
 * <li>there is no {@code SELECT}: a cluster has only database 0.</li>
 * </ul>
 *
 * <p>All the connections run on one {@link SelectorLoop}, and every callback
 * is invoked on that loop's thread.
 *
 * <h4>Basic Usage</h4>
 * <pre>{@code
 * RedisClusterClient client = new RedisClusterClient()
 *         .seed("redis-1.example.com", 6379)
 *         .seed("redis-2.example.com", 6379);
 * client.connect(gumdrop, new RedisClusterReady() {
 *     public void handleReady(RedisSession session) {
 *         session.set("key", "value", handler);
 *     }
 *     public void onError(Exception cause) { cause.printStackTrace(); }
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RedisClusterReady
 */
public class RedisClusterClient {

    private final List<ClusterNode> seeds = new ArrayList<ClusterNode>();
    private final TlsConfig tls = new TlsConfig();
    private SelectorLoop selectorLoop;
    private DnsResolver dnsResolver;
    private boolean secure;
    private String username;
    private String password;
    private int protocol = 2;
    private PushHandler pushHandler;
    private java.time.Duration commandTimeout = java.time.Duration.ZERO;
    private int maxPendingCommands = RedisClientProtocolHandler.DEFAULT_MAX_PENDING;

    private RedisClusterSession.Scheduler scheduler;
    private RedisClusterSession session;
    private RedisClientProtocolHandler seedConnection;
    private boolean closed;

    /** Creates a client; add at least one {@link #seed seed} before {@link #connect}. */
    public RedisClusterClient() {
    }

    /**
     * Adds a node to ask for the cluster's topology. Any one node of the
     * cluster will do, but giving several lets the client start while some
     * are down.
     *
     * @param host the node's host name or address
     * @param port the node's port
     * @return this client
     */
    public RedisClusterClient seed(String host, int port) {
        seeds.add(new ClusterNode(host, port, null));
        return this;
    }

    /**
     * Sets the loop that all of the connections run on. If not set, a worker
     * loop is chosen when connecting.
     *
     * @param selectorLoop the loop
     * @return this client
     */
    public RedisClusterClient selectorLoop(SelectorLoop selectorLoop) {
        this.selectorLoop = selectorLoop;
        return this;
    }

    /**
     * Sets the resolver used to look up the nodes' host names.
     *
     * @param dnsResolver the resolver
     * @return this client
     */
    public RedisClusterClient dnsResolver(DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
        return this;
    }

    /**
     * Sets whether to use TLS to every node.
     *
     * @param secure true for TLS
     * @return this client
     */
    public RedisClusterClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * Sets the TLS settings (trust, client identity and so on) for every
     * node. The settings are copied. Whether TLS is used at all is decided by
     * {@link #secure(boolean)}.
     *
     * @param source the TLS configuration
     * @return this client
     */
    public RedisClusterClient tls(TlsConfig source) {
        tls.copyFrom(source);
        return this;
    }

    /**
     * Authenticates every node connection with a password ({@code AUTH}).
     *
     * @param password the password
     * @return this client
     */
    /**
     * Sets the RESP version to use on every node connection. With 3,
     * {@code HELLO 3} (carrying the credentials, if any) is sent on each
     * connection as soon as it is up, before any command runs on it. That
     * covers the connections to every node, the dedicated Pub/Sub
     * connections, and those opened after a redirect or a topology refresh.
     * A node that rejects it fails the connection. The default is 2, which
     * sends no HELLO.
     *
     * @param protocol 2 or 3
     * @return this client
     * @throws IllegalArgumentException for any other value
     */
    public RedisClusterClient protocol(int protocol) {
        if (protocol != 2 && protocol != 3) {
            throw new IllegalArgumentException("RESP version must be 2 or 3: " + protocol);
        }
        this.protocol = protocol;
        return this;
    }

    /**
     * Sets the handler for RESP3 pushes that are not Pub/Sub deliveries,
     * such as client-side caching invalidations, from every node connection.
     * Needs {@link #protocol(int) protocol 3}. It is called on the loop the
     * connection runs on. The session's
     * {@link RedisSession#setPushHandler(PushHandler)} changes it later.
     *
     * @param handler the handler
     * @return this client
     */
    public RedisClusterClient pushHandler(PushHandler handler) {
        this.pushHandler = handler;
        return this;
    }

    /**
     * Subscribes to keyspace or keyevent notifications across the cluster.
     * Redis publishes them only on the node that owns the key, so this
     * subscribes the pattern with {@code PSUBSCRIBE} on a dedicated
     * connection to every primary, adds the primaries found by a topology
     * refresh, and drops those that leave. Events reach the handler as
     * pattern messages. The subscription lasts until the client is closed.
     *
     * <p>The cluster's nodes must have notifications switched on
     * ({@code notify-keyspace-events}, for example {@code KEA}); the client
     * does not change server configuration.
     *
     * <p>Call on the client's loop, for example from
     * {@link RedisClusterReady#handleReady}. If the client is not connected
     * the handler's {@code handleError} is called.
     *
     * @param pattern the pattern, for example {@code __keyspace@0__:user:*}
     * @param handler receives the events
     */
    public void subscribeKeyspace(String pattern, MessageHandler handler) {
        RedisClusterSession current = session;
        if (current == null || closed) {
            handler.handleError(RedisClientProtocolHandler.L10N.getString("err.not_connected"));
            return;
        }
        current.subscribeKeyspace(pattern, handler);
    }

    /**
     * Sets how long a command may wait for its reply, for every command
     * that names no timeout of its own, on every node connection and while
     * it waits for a connection to come up. When it expires the command's
     * handler gets an error beginning {@code TIMEOUT}, once, on the loop the
     * connection runs on, and a reply that arrives later is discarded.
     * Commands that wait on the server on purpose (blocking pops, blocking
     * stream reads) are allowed their own wait on top of it. The default is
     * no timeout.
     *
     * @param timeout the timeout; zero for none
     * @return this client
     * @throws IllegalArgumentException if negative
     */
    public RedisClusterClient commandTimeout(java.time.Duration timeout) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("negative timeout: " + timeout);
        }
        this.commandTimeout = timeout;
        return this;
    }

    /**
     * Sets the most commands that may wait at once on one node connection,
     * and for a connection to come up. A command sent beyond that fails at
     * once, with an error beginning {@code OVERLOAD}, instead of queueing
     * without limit. The default is 65536.
     *
     * @param max the cap; 0 for none
     * @return this client
     * @throws IllegalArgumentException if negative
     */
    public RedisClusterClient maxPendingCommands(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("negative cap: " + max);
        }
        this.maxPendingCommands = max;
        return this;
    }

    public RedisClusterClient auth(String password) {
        return auth(null, password);
    }

    /**
     * Authenticates every node connection as a user ({@code AUTH}).
     *
     * @param username the user, or null for the default user
     * @param password the password
     * @return this client
     */
    public RedisClusterClient auth(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    /**
     * Loads the cluster's topology and calls back when it is ready.
     *
     * @param gumdrop the runtime to connect under
     * @param handler told when the cluster is ready, or that it cannot be reached
     * @throws IllegalStateException if no seed was given
     */
    public void connect(Gumdrop gumdrop, RedisClusterReady handler) {
        final SelectorLoop loop = selectorLoop != null ? selectorLoop : gumdrop.nextWorkerLoop();
        final Gumdrop runtime = gumdrop;
        if (scheduler == null) {
            // timers fire on a timer thread; the work goes back to the loop
            scheduler = new RedisClusterSession.Scheduler() {
                @Override
                public void schedule(long delayMs, final Runnable task) {
                    runtime.scheduleTimer(null, delayMs, new Runnable() {
                        @Override
                        public void run() {
                            loop.invokeLater(task);
                        }
                    });
                }
            };
        }
        connect(new TcpNodeConnector(gumdrop, loop, dnsResolver, secure, tls, username, password, protocol), handler);
    }

    /** Sets how retries are timed; a test seam (a real client times them on its loop). */
    void scheduler(RedisClusterSession.Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Connects through {@code connector}; a test seam. */
    void connect(NodeConnector connector, final RedisClusterReady handler) {
        if (seeds.isEmpty()) {
            throw new IllegalStateException("No seed node was given");
        }
        closed = false;
        final NodeConnector nodes = connector;
        ClusterDiscovery.discover(new ArrayList<ClusterNode>(seeds), connector,
                new ClusterDiscovery.Callback() {
                    @Override
                    public void discovered(SlotMap map, RedisClientProtocolHandler seed) {
                        if (closed) {
                            seed.close();
                            return;
                        }
                        seedConnection = seed;
                        session = new RedisClusterSession(map, nodes);
                        session.setScheduler(scheduler);
                        session.setRefresher(refresher(nodes));
                        session.setPushHandler(pushHandler);
                        session.setCommandTimeoutMillis(commandTimeout.toMillis());
                        session.setMaxPending(maxPendingCommands);
                        // Connect to every master, and keep the seed connection
                        // until one of those has settled: Gumdrop stops a
                        // runtime with no connection at all.
                        session.connectAll(new Runnable() {
                            @Override
                            public void run() {
                                closeSeed();
                            }
                        });
                        handler.handleReady(session);
                    }

                    @Override
                    public void failed(Exception cause) {
                        if (!closed) {
                            handler.onError(cause);
                        }
                    }
                });
    }

    /** True once the topology is loaded and until {@link #close()}. */
    public boolean isOpen() {
        return session != null && !closed;
    }

    /** Closes every node connection. */
    public void close() {
        closed = true;
        if (session != null) {
            session.close();
        }
        closeSeed();
    }

    // Reloads the topology when the session suspects it is stale: asks the
    // masters it knows first, as they are the likeliest to be up, then the
    // seeds. The connection that answers is not needed afterwards.
    private TopologyRefresher refresher(final NodeConnector nodes) {
        return new TopologyRefresher() {
            @Override
            public void refresh(final Callback callback) {
                List<ClusterNode> targets = new ArrayList<ClusterNode>();
                if (session != null) {
                    targets.addAll(session.slotMap().nodes());
                }
                for (ClusterNode seed : seeds) {
                    if (!targets.contains(seed)) {
                        targets.add(seed);
                    }
                }
                ClusterDiscovery.discover(targets, nodes, new ClusterDiscovery.Callback() {
                    @Override
                    public void discovered(SlotMap map, RedisClientProtocolHandler answering) {
                        answering.close();
                        callback.refreshed(map);
                    }

                    @Override
                    public void failed(Exception cause) {
                        callback.failed(cause);
                    }
                });
            }
        };
    }

    private void closeSeed() {
        RedisClientProtocolHandler seed = seedConnection;
        seedConnection = null;
        if (seed != null) {
            seed.close();
        }
    }
}
