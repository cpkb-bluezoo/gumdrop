/*
 * RedisClient.java
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

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.client.ClientDial;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * High-level Redis client facade.
 *
 * <p>This class provides a simple, concrete API for connecting to Redis servers
 * using the RESP (Redis Serialization Protocol) wire format. It internally
 * creates a {@link TcpTransportFactory}, {@link ClientEndpoint}, and
 * {@link RedisClientProtocolHandler}, wiring them together and forwarding
 * lifecycle events to the caller's {@link RedisConnectionReady} handler.
 *
 * <h4>Basic Usage</h4>
 * <pre>{@code
 * RedisClient client = new RedisClient()
 *         .host("localhost")
 *         .port(6379);
 * client.connect(new RedisConnectionReady() {
 *     public void handleReady(RedisSession session) {
 *         session.set("key", "value", handler);
 *     }
 *     public void onConnected(Endpoint endpoint) { }
 *     public void onSecurityEstablished(SecurityInfo info) { }
 *     public void onError(Exception cause) { cause.printStackTrace(); }
 *     public void onDisconnected() { }
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RedisConnectionReady
 * @see RedisClientProtocolHandler
 */
public class RedisClient {

    private final ClientDial dial = ClientDial.withDefaultPort(6379);
    private final TlsConfig tls = new TlsConfig();
    private boolean secure;
    private int protocol = 2;
    private java.time.Duration commandTimeout = java.time.Duration.ZERO;
    private int maxPendingCommands = RedisClientProtocolHandler.DEFAULT_MAX_PENDING;
    private java.time.Duration healthCheckInterval = java.time.Duration.ZERO;
    private int maxLine = RedisClientProtocolHandler.DEFAULT_MAX_LINE;
    private int maxField = RedisClientProtocolHandler.DEFAULT_MAX_FIELD;
    private long maxSkip = RedisClientProtocolHandler.DEFAULT_MAX_SKIP;

    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private RedisClientProtocolHandler endpointHandler;
    private boolean connected;

    /** Creates a client for fluent dial configuration before {@link #connect}. */
    public RedisClient() {
    }

    public RedisClient(String host, int port) {
        this(null, host, port);
    }

    public RedisClient(SelectorLoop selectorLoop, String host, int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    public RedisClient(InetAddress host, int port) {
        this(null, host, port);
    }

    public RedisClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    public RedisClient(String socketPath) {
        this(null, socketPath);
    }

    public RedisClient(SelectorLoop selectorLoop, String socketPath) {
        dial.selectorLoop(selectorLoop).socketPath(socketPath);
    }

    public RedisClient host(String host) {
        dial.host(host);
        return this;
    }

    public RedisClient host(InetAddress hostAddress) {
        dial.host(hostAddress);
        return this;
    }

    public RedisClient port(int port) {
        dial.port(port);
        return this;
    }

    public RedisClient socketPath(String socketPath) {
        dial.socketPath(socketPath);
        return this;
    }

    public RedisClient selectorLoop(SelectorLoop selectorLoop) {
        dial.selectorLoop(selectorLoop);
        return this;
    }

    public RedisClient dnsResolver(DnsResolver dnsResolver) {
        dial.dnsResolver(dnsResolver);
        return this;
    }

    public RedisClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * Sets the longest header or simple-string line the client will read: a
     * length, a status, an error message. A longer one is not RESP the
     * client is prepared to accept, and the connection is closed. This bounds
     * what a server can make the client hold of a line. The default is
     * 64 KiB.
     *
     * @param bytes the limit, at least 64
     * @return this client
     * @throws IllegalArgumentException if less than 64
     */
    public RedisClient maxLine(int bytes) {
        this.maxLine = RedisClusterClient.checkLine(bytes);
        return this;
    }

    /**
     * Sets the longest identifier (a key, a hash field name, a channel, a
     * pattern, a cursor) or scalar string the client will deliver whole to a
     * handler. A longer one fails the reply it is in with a
     * {@code TOOLARGE} error, or drops the Pub/Sub message, and the
     * connection is not affected. Payloads (values, list elements, message
     * bodies) are not subject to it, as they are delivered as chunks. This
     * bounds what a server, or whoever can write a key, can make the client
     * hold for each connection. The default is 64 KiB.
     *
     * @param bytes the limit, at least 1
     * @return this client
     * @throws IllegalArgumentException if less than 1
     */
    public RedisClient maxField(int bytes) {
        this.maxField = RedisClusterClient.checkField(bytes);
        return this;
    }

    /**
     * Sets the most bytes of one reply the client will read only to discard
     * them: what a handler skipped, the reply to a command that timed out,
     * and RESP3 attributes. Discarding costs no memory but it does cost
     * time, so past this the connection is closed, and the commands waiting
     * on it fail. This is the one case in which a reply that nobody wants
     * closes the connection. The default is 256 MiB.
     *
     * @param bytes the limit, at least 1
     * @return this client
     * @throws IllegalArgumentException if less than 1
     */
    public RedisClient maxSkip(long bytes) {
        this.maxSkip = RedisClusterClient.checkSkip(bytes);
        return this;
    }

    /**
     * Sets the RESP version to use. With 3, {@code HELLO 3} is sent as soon
     * as the connection is up, before the handler is told it is ready, so
     * the server can send pushes such as client-side caching invalidations;
     * if the server rejects it the handler's {@code onError} is called. The
     * default is 2, which sends no HELLO.
     *
     * @param protocol 2 or 3
     * @return this client
     * @throws IllegalArgumentException for any other value
     */
    public RedisClient protocol(int protocol) {
        if (protocol != 2 && protocol != 3) {
            throw new IllegalArgumentException("RESP version must be 2 or 3: " + protocol);
        }
        this.protocol = protocol;
        return this;
    }

    /**
     * Sets how long a command may wait for its reply, for every command
     * that names no timeout of its own. When it expires the command's
     * handler gets an error beginning {@code TIMEOUT}, once, on this
     * client's loop, and a reply that arrives later is discarded. Commands
     * that wait on the server on purpose (blocking pops, blocking stream
     * reads) are allowed their own wait on top of it. The default is no
     * timeout.
     *
     * @param timeout the timeout; zero for none
     * @return this client
     * @throws IllegalArgumentException if negative
     */
    public RedisClient commandTimeout(java.time.Duration timeout) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("negative timeout: " + timeout);
        }
        this.commandTimeout = timeout;
        return this;
    }

    /**
     * Sets the most commands that may await a reply on the connection at
     * once. A command sent beyond that fails at once, with an error
     * beginning {@code OVERLOAD}, instead of queueing without limit. The
     * default is 65536; a command that timed out keeps its place until its
     * reply arrives.
     *
     * @param max the cap; 0 for none
     * @return this client
     * @throws IllegalArgumentException if negative
     */
    public RedisClient maxPendingCommands(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("negative cap: " + max);
        }
        this.maxPendingCommands = max;
        return this;
    }

    /**
     * Sets the health check interval. When commands are waiting (or the
     * connection is subscribed) and nothing has been heard from the server
     * for this long, the client sends {@code PING}; if nothing is heard for
     * another interval the connection is closed, and the handlers of the
     * commands waiting get an error. This finds a server that has gone
     * silent without the connection being reset. A connection whose oldest
     * waiting command blocks on the server on purpose (a blocking pop) is
     * not checked. The default is no check.
     *
     * @param interval the interval; zero for no check
     * @return this client
     * @throws IllegalArgumentException if negative
     */
    public RedisClient healthCheckInterval(java.time.Duration interval) {
        if (interval.isNegative()) {
            throw new IllegalArgumentException("negative interval: " + interval);
        }
        this.healthCheckInterval = interval;
        return this;
    }

    /**
     * Sets this client's TLS settings (certificates, trust, ECH and so on). The
     * settings are copied, so later changes to {@code source} are not seen.
     * Whether TLS is used at all is decided by {@link #secure(boolean)}.
     *
     * @param source the TLS configuration
     * @return this client
     */
    public RedisClient tls(TlsConfig source) {
        tls.copyFrom(source);
        return this;
    }

    // The connection's handler, with this client's options applied.
    RedisClientProtocolHandler newHandler(RedisConnectionReady handler) {
        RedisClientProtocolHandler h = new RedisClientProtocolHandler(
                protocol == 3 ? new HelloOnReady(handler, protocol) : handler);
        h.setCommandTimeoutMillis(commandTimeout.toMillis());
        h.setMaxPending(maxPendingCommands);
        h.setHealthCheckMillis(healthCheckInterval.toMillis());
        h.setMaxLine(maxLine);
        h.setMaxField(maxField);
        h.setMaxSkip(maxSkip);
        return h;
    }

    public ClientDial getDial() {
        return dial;
    }

    public TlsConfig getTls() {
        return tls;
    }

    /**
     * Connects to the remote Redis server.
     */
    public void connect(Gumdrop gumdrop, RedisConnectionReady handler) {
        transportFactory = new TcpTransportFactory();
        endpointHandler = newHandler(handler);

        ClientConnect.discoverEch(gumdrop, secure, dial, tls, new ClientConnect.EchDiscoveryCallback() {
            @Override
            public void discovered(byte[] echConfigList) {
                try {
                    ClientConnect.prepareTls(secure, tls, transportFactory, echConfigList);
                    clientEndpoint = ClientConnect.openAndConnect(
                            gumdrop, dial, transportFactory, endpointHandler);
                    connected = true;
                } catch (IOException e) {
                    handler.onError(e);
                }
            }
        });
    }

    public boolean isOpen() {
        return connected && endpointHandler != null;
    }

    public void close() {
        if (endpointHandler != null) {
            endpointHandler.close();
        }
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
    }
}
