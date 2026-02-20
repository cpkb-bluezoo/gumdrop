/*
 * DNSResolver.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.dns;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.UDPEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.UDPTransportFactory;

/**
 * Asynchronous DNS resolver using non-blocking I/O.
 *
 * <p>DNSResolver provides non-blocking DNS lookups with callback-based
 * result delivery. It is designed for use in event-driven applications.
 *
 * <p>Queries are sent to configured upstream DNS servers and responses
 * are delivered via the {@link DNSQueryCallback} interface. Timeout
 * handling is integrated with the Gumdrop scheduler.
 *
 * <p>SelectorLoop affinity: when used inside a Gumdrop service (e.g. from an
 * HTTP or SMTP handler), call {@link #setSelectorLoop(SelectorLoop)} with
 * the endpoint's SelectorLoop so that DNS callbacks run on the same thread
 * and avoid cross-thread coordination.
 *
 * <p>Example usage:
 * <pre><code>
 * DNSResolver resolver = new DNSResolver();
 * resolver.addServer("8.8.8.8");
 * resolver.open();
 *
 * resolver.queryTXT("_dmarc.example.com", new DNSQueryCallback() {
 *     &#64;Override
 *     public void onResponse(DNSMessage response) {
 *         for (DNSResourceRecord rr : response.getAnswers()) {
 *             if (rr.getType() == DNSType.TXT) {
 *                 String txt = rr.getTxtData();
 *                 // Process TXT record...
 *             }
 *         }
 *     }
 *
 *     &#64;Override
 *     public void onError(String error) {
 *         // Handle timeout or error
 *     }
 * });
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSQueryCallback
 */
public class DNSResolver {

    private static final Logger LOGGER = Logger.getLogger(DNSResolver.class.getName());
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private static final int DEFAULT_PORT = 53;
    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final List<InetSocketAddress> servers;
    private final Map<Integer, PendingQuery> pendingQueries;
    private final AtomicInteger queryIdGenerator;
    private final List<ResolverClient> clients;

    private long timeoutMs;
    private boolean opened;
    private SelectorLoop selectorLoop;

    /**
     * Creates a new DNS resolver with no servers configured.
     * Call {@link #addServer(String)} or {@link #addServer(InetAddress, int)}
     * to add DNS servers before opening.
     */
    public DNSResolver() {
        this.servers = new ArrayList<>();
        this.pendingQueries = new ConcurrentHashMap<>();
        this.queryIdGenerator = new AtomicInteger(0);
        this.clients = new ArrayList<>();
        this.timeoutMs = DEFAULT_TIMEOUT_MS;
        this.opened = false;
    }

    // -- Configuration --

    /**
     * Adds a DNS server by hostname or IP address.
     * Uses the default DNS port (53).
     *
     * <p>This method should be called during configuration, before
     * {@link #open()}. For IP address strings (e.g., "8.8.8.8"),
     * no network lookup is performed. For hostnames, a blocking DNS
     * lookup occurs.
     *
     * @param server the server hostname or IP address
     * @throws UnknownHostException if the hostname cannot be resolved
     */
    public void addServer(String server) throws UnknownHostException {
        addServer(InetAddress.getByName(server), DEFAULT_PORT);
    }

    /**
     * Adds a DNS server by hostname or IP address with a specific port.
     *
     * <p>This method should be called during configuration, before
     * {@link #open()}. For IP address strings, no network lookup is
     * performed. For hostnames, a blocking DNS lookup occurs.
     *
     * @param server the server hostname or IP address
     * @param port the port number
     * @throws UnknownHostException if the hostname cannot be resolved
     */
    public void addServer(String server, int port) throws UnknownHostException {
        addServer(InetAddress.getByName(server), port);
    }

    /**
     * Adds a DNS server by InetAddress.
     *
     * <p>This is the preferred method when you already have the address,
     * as it avoids any potential blocking DNS lookup.
     *
     * @param address the server address
     * @param port the port number
     */
    public void addServer(InetAddress address, int port) {
        servers.add(new InetSocketAddress(address, port));
    }

    /**
     * Sets the query timeout in milliseconds.
     *
     * @param timeoutMs the timeout in milliseconds
     */
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Returns the SelectorLoop used for resolver I/O, or null if not set.
     *
     * @return the SelectorLoop, or null
     */
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    /**
     * Sets the SelectorLoop for resolver I/O. When set, all DNS client
     * connections and timers use this loop, so callbacks are invoked on
     * the same thread. Use this when the resolver is used from a Gumdrop
     * service (e.g. HTTP or SMTP handler) to avoid cross-thread coordination.
     *
     * <p>Must be called before {@link #open()}.
     *
     * @param loop the SelectorLoop, or null to use a Gumdrop worker loop
     */
    public void setSelectorLoop(SelectorLoop loop) {
        this.selectorLoop = loop;
    }

    /**
     * Adds the system's default DNS resolvers.
     * This reads from /etc/resolv.conf on Unix-like systems.
     */
    public void useSystemResolvers() {
        // Try /etc/resolv.conf on Unix-like systems
        Path resolvConf = Paths.get("/etc/resolv.conf");
        if (Files.exists(resolvConf)) {
            try {
                BufferedReader reader = Files.newBufferedReader(resolvConf);
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("nameserver ")) {
                            String addr = line.substring(11).trim();
                            try {
                                addServer(addr);
                            } catch (UnknownHostException e) {
                                String msg = MessageFormat.format(L10N.getString("err.invalid_nameserver"), addr);
                                LOGGER.log(Level.FINE, msg, e);
                            }
                        }
                    }
                } finally {
                    reader.close();
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, L10N.getString("err.read_resolv_conf"), e);
            }
        }
        // Fallback to common public DNS servers if none found
        if (servers.isEmpty()) {
            try {
                addServer("8.8.8.8");      // Google
                addServer("1.1.1.1");      // Cloudflare
            } catch (UnknownHostException e) {
                // Should not happen for IP addresses
            }
        }
    }

    // -- Lifecycle --

    /**
     * Opens the resolver by creating connections to all configured servers.
     *
     * @throws IOException if a connection cannot be opened
     */
    public void open() throws IOException {
        if (opened) {
            return;
        }
        if (servers.isEmpty()) {
            throw new IOException(L10N.getString("err.no_dns_servers"));
        }
        for (InetSocketAddress server : servers) {
            ResolverClient client = new ResolverClient(server.getAddress(), server.getPort(), selectorLoop);
            clients.add(client);
        }
        opened = true;
    }

    /**
     * Closes the resolver and all connections.
     */
    public void close() {
        if (!opened) {
            return;
        }
        // Cancel all pending queries and their timers
        for (PendingQuery pending : pendingQueries.values()) {
            if (pending.timeoutHandle != null) {
                pending.timeoutHandle.cancel();
            }
            pending.callback.onError(L10N.getString("err.resolver_closed"));
        }
        pendingQueries.clear();
        // Close all clients
        for (ResolverClient client : clients) {
            client.close();
        }
        clients.clear();
        opened = false;
    }

    // -- Query Methods --

    /**
     * Queries for TXT records.
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryTXT(String name, DNSQueryCallback callback) {
        query(name, DNSType.TXT, callback);
    }

    /**
     * Queries for A records (IPv4 addresses).
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryA(String name, DNSQueryCallback callback) {
        query(name, DNSType.A, callback);
    }

    /**
     * Queries for AAAA records (IPv6 addresses).
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryAAAA(String name, DNSQueryCallback callback) {
        query(name, DNSType.AAAA, callback);
    }

    /**
     * Queries for MX records (mail exchangers).
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryMX(String name, DNSQueryCallback callback) {
        query(name, DNSType.MX, callback);
    }

    /**
     * Queries for PTR records (reverse DNS).
     *
     * @param name the domain name to query (e.g., "1.0.0.127.in-addr.arpa")
     * @param callback the callback to receive results
     */
    public void queryPTR(String name, DNSQueryCallback callback) {
        query(name, DNSType.PTR, callback);
    }

    /**
     * Performs a DNS query with the specified type.
     *
     * @param name the domain name to query
     * @param type the record type to query
     * @param callback the callback to receive results
     */
    public void query(String name, DNSType type, DNSQueryCallback callback) {
        if (!opened) {
            callback.onError(L10N.getString("err.resolver_not_opened"));
            return;
        }
        if (clients.isEmpty()) {
            callback.onError(L10N.getString("err.no_dns_servers"));
            return;
        }
        // Generate query ID
        int queryId = queryIdGenerator.getAndIncrement() & 0xFFFF;
        // Create query message
        List<DNSQuestion> questions = new ArrayList<>();
        questions.add(new DNSQuestion(name, type, DNSClass.IN));
        int flags = DNSMessage.FLAG_RD; // Recursion desired
        DNSMessage query = new DNSMessage(
                queryId,
                flags,
                questions,
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList()
        );
        // Create pending query
        long expiry = System.currentTimeMillis() + timeoutMs;
        final PendingQuery pending = new PendingQuery(queryId, name, type, callback, expiry);
        pendingQueries.put(queryId, pending);
        // Schedule timeout timer
        ResolverClient client = clients.get(0);
        pending.timeoutHandle = client.scheduleTimer(timeoutMs, new Runnable() {
            @Override
            public void run() {
                handleTimeout(pending.queryId);
            }
        });
        // Send query to first server
        ByteBuffer serialized = query.serialize();
        client.send(serialized);
        if (LOGGER.isLoggable(Level.FINE)) {
            String msg = MessageFormat.format(L10N.getString("debug.sent_query"), name, type, queryId);
            LOGGER.fine(msg);
        }
    }

    // -- Internal Methods --

    /**
     * Handles a received DNS response.
     */
    private void handleResponse(DNSMessage response) {
        int queryId = response.getId();
        PendingQuery pending = pendingQueries.remove(queryId);
        if (pending == null) {
            // Stale or duplicate response (possibly already timed out)
            if (LOGGER.isLoggable(Level.FINE)) {
                String msg = MessageFormat.format(L10N.getString("debug.received_response_unknown"), queryId);
                LOGGER.fine(msg);
            }
            return;
        }
        // Cancel the timeout timer since we got a response
        if (pending.timeoutHandle != null) {
            pending.timeoutHandle.cancel();
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            String msg = MessageFormat.format(L10N.getString("debug.received_response"), pending.name, response.getAnswers().size());
            LOGGER.fine(msg);
        }
        pending.callback.onResponse(response);
    }

    /**
     * Handles a query timeout.
     */
    private void handleTimeout(int queryId) {
        PendingQuery pending = pendingQueries.remove(queryId);
        if (pending == null) {
            // Already received a response
            return;
        }
        String msg = MessageFormat.format(L10N.getString("err.query_timeout"), pending.name);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(msg);
        }
        pending.callback.onError(msg);
    }

    // -- Inner Classes --

    /**
     * Tracks a pending DNS query.
     */
    private static class PendingQuery {
        final int queryId;
        final String name;
        final DNSType type;
        final DNSQueryCallback callback;
        final long expiry;
        TimerHandle timeoutHandle;

        PendingQuery(int queryId, String name, DNSType type, DNSQueryCallback callback,
                     long expiry) {
            this.queryId = queryId;
            this.name = name;
            this.type = type;
            this.callback = callback;
            this.expiry = expiry;
        }
    }

    /**
     * Client for DNS queries using {@link UDPEndpoint}.
     */
    private class ResolverClient {

        private final UDPEndpoint endpoint;

        ResolverClient(InetAddress host, int port, SelectorLoop loop) throws IOException {
            UDPTransportFactory factory = new UDPTransportFactory();
            factory.start();
            this.endpoint = factory.connect(host, port, new ResolverProtocolHandler(), loop);
        }

        void send(ByteBuffer data) {
            endpoint.send(data);
        }

        TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            return endpoint.scheduleTimer(delayMs, callback);
        }

        void close() {
            endpoint.close();
        }
    }

    /**
     * Endpoint handler for DNS resolver client.
     */
    private class ResolverProtocolHandler implements ProtocolHandler {

        @Override
        public void connected(Endpoint ep) {
            // Connected to DNS server
        }

        @Override
        public void receive(ByteBuffer data) {
            try {
                DNSMessage response = DNSMessage.parse(data);
                handleResponse(response);
            } catch (DNSFormatException e) {
                LOGGER.log(Level.WARNING, L10N.getString("err.malformed_response"), e);
            }
        }

        @Override
        public void disconnected() {
            // Client disconnected
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            // No-op for plain UDP
        }

        @Override
        public void error(Exception cause) {
            LOGGER.log(Level.WARNING, "DNS resolver endpoint error", cause);
        }
    }

}

