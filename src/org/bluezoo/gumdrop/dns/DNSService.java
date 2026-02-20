/*
 * DNSService.java
 * Copyright (C) 2025, 2026 Chris Burdess
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
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Service;

/**
 * A DNS application service that resolves queries locally or proxies to
 * upstream servers.
 *
 * <p>The default implementation proxies all queries to configured upstream
 * DNS servers. Subclasses can override {@link #resolve(DNSMessage)} to
 * provide custom name resolution.
 *
 * <p>The service manages one or more listeners. Currently supported:
 * <ul>
 *   <li>{@link DNSListener} &ndash; standard DNS over UDP</li>
 *   <li>{@link DoTListener} &ndash; DNS over TLS (stub)</li>
 *   <li>{@link DoQListener} &ndash; DNS over QUIC (stub)</li>
 * </ul>
 *
 * <p>Features:
 * <ul>
 * <li>Configurable upstream DNS servers</li>
 * <li>Optional system resolver fallback</li>
 * <li>In-memory response caching with TTL support</li>
 * <li>Negative caching for NXDOMAIN responses</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.dns.DNSService">
 *   <property name="upstream-servers">8.8.8.8 1.1.1.1</property>
 *   <property name="cache-enabled">true</property>
 *   <listener class="org.bluezoo.gumdrop.dns.DNSListener"
 *           port="5353"/>
 * </service>
 * }</pre>
 *
 * <p>Example subclass for custom resolution:
 * <pre>{@code
 * public class MyDNSService extends DNSService {
 *     @Override
 *     protected DNSMessage resolve(DNSMessage query) {
 *         DNSQuestion question = query.getQuestions().get(0);
 *         if ("internal.example.com".equals(question.getName())) {
 *             List answers = new ArrayList();
 *             answers.add(DNSResourceRecord.a("internal.example.com", 300,
 *                     InetAddress.getByName("10.0.0.1")));
 *             return query.createResponse(answers);
 *         }
 *         return null; // Fall through to upstream
 *     }
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Service
 * @see DNSListener
 */
public class DNSService implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(DNSService.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private static final int DEFAULT_PORT = 53;
    private static final int UPSTREAM_TIMEOUT_MS = 5000;
    private static final int MAX_DNS_MESSAGE_SIZE = 512;

    private final List listeners = new ArrayList();

    // ── Configuration ──

    private final List upstreamServers = new ArrayList();
    private boolean useSystemResolvers = true;
    private boolean cacheEnabled = true;
    private DNSCache cache;

    private final AtomicInteger queryIdGenerator = new AtomicInteger(0);

    /**
     * Creates a new DNS service.
     */
    public DNSService() {
    }

    // ── Listener management ──

    /**
     * Adds a UDP DNS listener.
     *
     * @param endpoint the DNS server endpoint
     */
    public void addListener(DNSListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Adds a DNS-over-TLS listener (stub).
     *
     * @param endpoint the DoT server endpoint
     */
    public void addListener(DoTListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Adds a DNS-over-QUIC listener (stub).
     *
     * @param endpoint the DoQ server endpoint
     */
    public void addListener(DoQListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Sets the listeners from a configuration list. Each item must be
     * a {@link DNSListener}, {@link DoTListener}, or
     * {@link DoQListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof DNSListener) {
                addListener((DNSListener) item);
            } else if (item instanceof DoTListener) {
                addListener((DoTListener) item);
            } else if (item instanceof DoQListener) {
                addListener((DoQListener) item);
            }
        }
    }

    @Override
    public List getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    // ── Configuration ──

    /**
     * Sets the upstream DNS servers to use for proxying.
     *
     * <p>Format: space-separated list of addresses with optional port.
     * Examples:
     * <ul>
     * <li>"8.8.8.8 1.1.1.1" &ndash; Google and Cloudflare DNS</li>
     * <li>"192.168.1.1:53" &ndash; Local router with explicit port</li>
     * </ul>
     *
     * @param servers space-separated list of server addresses
     */
    public void setUpstreamServers(String servers) {
        upstreamServers.clear();
        if (servers == null || servers.trim().isEmpty()) {
            return;
        }

        StringTokenizer st = new StringTokenizer(servers);
        while (st.hasMoreTokens()) {
            String server = st.nextToken();
            try {
                InetSocketAddress addr =
                        parseAddress(server, DEFAULT_PORT);
                upstreamServers.add(addr);
            } catch (Exception e) {
                String msg = MessageFormat.format(
                        L10N.getString("err.invalid_upstream_server"),
                        server);
                LOGGER.log(Level.WARNING, msg, e);
            }
        }
    }

    /**
     * Sets whether to use system resolvers from /etc/resolv.conf.
     *
     * @param useSystemResolvers true to use system resolvers as fallback
     */
    public void setUseSystemResolvers(boolean useSystemResolvers) {
        this.useSystemResolvers = useSystemResolvers;
    }

    /**
     * Sets whether response caching is enabled.
     *
     * @param cacheEnabled true to enable caching
     */
    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    /**
     * Returns the DNS cache.
     *
     * @return the cache, or null if caching is disabled
     */
    public DNSCache getCache() {
        return cache;
    }

    // ── Lifecycle ──

    @Override
    public void start() {
        if (cacheEnabled) {
            cache = new DNSCache();
        }

        if (useSystemResolvers && upstreamServers.isEmpty()) {
            loadSystemResolvers();
        }

        if (upstreamServers.isEmpty()) {
            LOGGER.warning(L10N.getString("warn.no_upstream_servers"));
        } else if (LOGGER.isLoggable(Level.FINE)) {
            String message = L10N.getString("info.upstream_servers");
            message = MessageFormat.format(message, upstreamServers);
            LOGGER.fine(message);
        }

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            wireListener(listener);
            startListener(listener);
        }
    }

    @Override
    public void stop() {
        for (int i = 0; i < listeners.size(); i++) {
            stopListener(listeners.get(i));
        }
        if (cache != null) {
            cache.clear();
        }
    }

    // ── Query handling ──

    /**
     * Handles a DNS datagram received from a UDP listener.
     *
     * <p>This is called by {@link DNSListener} when a datagram
     * arrives. The method parses the DNS message, processes it, and
     * sends the response back to the source.
     *
     * @param origin  the endpoint that received the datagram
     * @param data    the raw datagram data
     * @param source  the sender's address
     */
    void handleDatagram(DNSListener origin,
                        ByteBuffer data,
                        InetSocketAddress source) {
        try {
            DNSMessage query = DNSMessage.parse(data);

            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("debug.received_query");
                message = MessageFormat.format(message, query, source);
                LOGGER.fine(message);
            }

            if (!query.isQuery()
                    || query.getOpcode() != DNSMessage.OPCODE_QUERY) {
                DNSMessage error = query.createErrorResponse(
                        DNSMessage.RCODE_NOTIMP);
                sendResponse(origin, error, source);
                return;
            }

            if (query.getQuestions().isEmpty()) {
                DNSMessage error = query.createErrorResponse(
                        DNSMessage.RCODE_FORMERR);
                sendResponse(origin, error, source);
                return;
            }

            DNSMessage response = processQuery(query);
            sendResponse(origin, response, source);

        } catch (DNSFormatException e) {
            String msg = MessageFormat.format(
                    L10N.getString("err.malformed_query"), source);
            LOGGER.log(Level.FINE, msg, e);
        } catch (Exception e) {
            String msg = MessageFormat.format(
                    L10N.getString("err.query"), source);
            LOGGER.log(Level.WARNING, msg, e);
        }
    }

    /**
     * Processes a DNS query through the resolution pipeline:
     * cache, custom resolve, upstream proxy.
     *
     * <p>This method is public so that other listeners (DoT, DoQ)
     * can delegate to it.
     *
     * @param query the parsed DNS query message
     * @return the DNS response message
     */
    public DNSMessage processQuery(DNSMessage query) {
        DNSQuestion question = query.getQuestions().get(0);

        // 1. Check cache
        if (cacheEnabled && cache != null) {
            if (cache.isNegativelyCached(question.getName())) {
                return query.createErrorResponse(
                        DNSMessage.RCODE_NXDOMAIN);
            }

            List cached = cache.lookup(question);
            if (cached != null) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    String msg = MessageFormat.format(
                            L10N.getString("debug.cache_hit"), question);
                    LOGGER.finest(msg);
                }
                return query.createResponse(cached);
            }
        }

        // 2. Try custom resolution
        DNSMessage customResponse = resolve(query);
        if (customResponse != null) {
            if (cacheEnabled && cache != null
                    && !customResponse.getAnswers().isEmpty()) {
                cache.cache(question, customResponse.getAnswers());
            }
            return customResponse;
        }

        // 3. Proxy to upstream
        DNSMessage upstreamResponse = proxyToUpstream(query);
        if (upstreamResponse != null) {
            if (cacheEnabled && cache != null) {
                if (upstreamResponse.getRcode()
                        == DNSMessage.RCODE_NXDOMAIN) {
                    cache.cacheNegative(question.getName());
                } else if (!upstreamResponse.getAnswers().isEmpty()) {
                    cache.cache(question,
                            upstreamResponse.getAnswers());
                }
            }
            return upstreamResponse;
        }

        return query.createErrorResponse(DNSMessage.RCODE_SERVFAIL);
    }

    /**
     * Override this method to provide custom name resolution.
     *
     * <p>Return a response message to handle the query locally,
     * or return null to proxy to upstream servers.
     *
     * @param query the DNS query
     * @return a response message, or null to proxy to upstream
     */
    protected DNSMessage resolve(DNSMessage query) {
        return null;
    }

    // ── Upstream proxy ──

    private DNSMessage proxyToUpstream(DNSMessage query) {
        if (upstreamServers.isEmpty()) {
            return null;
        }

        int upstreamId = queryIdGenerator.getAndIncrement() & 0xFFFF;
        DNSMessage upstreamQuery = new DNSMessage(
                upstreamId,
                query.getFlags(),
                query.getQuestions(),
                query.getAnswers(),
                query.getAuthorities(),
                query.getAdditionals()
        );

        ByteBuffer queryBytes = upstreamQuery.serialize();
        byte[] queryData = new byte[queryBytes.remaining()];
        queryBytes.get(queryData);

        for (int i = 0; i < upstreamServers.size(); i++) {
            InetSocketAddress upstream =
                    (InetSocketAddress) upstreamServers.get(i);
            try {
                DatagramSocket socket = new DatagramSocket();
                try {
                    socket.setSoTimeout(UPSTREAM_TIMEOUT_MS);

                    DatagramPacket sendPacket = new DatagramPacket(
                            queryData, queryData.length, upstream);
                    socket.send(sendPacket);

                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.finest("Sent query to upstream "
                                + upstream);
                    }

                    byte[] responseData =
                            new byte[MAX_DNS_MESSAGE_SIZE];
                    DatagramPacket receivePacket = new DatagramPacket(
                            responseData, responseData.length);
                    socket.receive(receivePacket);

                    ByteBuffer responseBuffer = ByteBuffer.wrap(
                            responseData, 0,
                            receivePacket.getLength());
                    DNSMessage response =
                            DNSMessage.parse(responseBuffer);

                    DNSMessage finalResponse = new DNSMessage(
                            query.getId(),
                            response.getFlags(),
                            response.getQuestions(),
                            response.getAnswers(),
                            response.getAuthorities(),
                            response.getAdditionals()
                    );

                    if (LOGGER.isLoggable(Level.FINE)) {
                        String message =
                                L10N.getString("debug.upstream_response");
                        message = MessageFormat.format(
                                message, upstream, finalResponse);
                        LOGGER.fine(message);
                    }

                    return finalResponse;
                } finally {
                    socket.close();
                }

            } catch (SocketTimeoutException e) {
                String msg = MessageFormat.format(
                        L10N.getString("err.timeout_upstream"), upstream);
                LOGGER.log(Level.FINE, msg);
            } catch (IOException e) {
                String msg = MessageFormat.format(
                        L10N.getString("err.upstream"), upstream);
                LOGGER.log(Level.FINE, msg, e);
            } catch (DNSFormatException e) {
                String msg = MessageFormat.format(
                        L10N.getString("err.upstream_malformed"),
                        upstream);
                LOGGER.log(Level.FINE, msg, e);
            }
        }

        return null;
    }

    // ── Internal helpers ──

    private void sendResponse(DNSListener origin,
                              DNSMessage response,
                              InetSocketAddress destination) {
        ByteBuffer data = response.serialize();

        if (LOGGER.isLoggable(Level.FINE)) {
            String message = L10N.getString("debug.sending_response");
            message = MessageFormat.format(
                    message, response, destination);
            LOGGER.fine(message);
        }

        origin.sendTo(data, destination);
    }

    private void wireListener(Object listener) {
        if (listener instanceof DNSListener) {
            ((DNSListener) listener).setService(this);
        } else if (listener instanceof DoTListener) {
            ((DoTListener) listener).setService(this);
        } else if (listener instanceof DoQListener) {
            ((DoQListener) listener).setService(this);
        }
    }

    private void startListener(Object listener) {
        if (listener instanceof DoQListener) {
            DoQListener doq = (DoQListener) listener;
            if (doq.getSelectorLoop() == null) {
                doq.setSelectorLoop(
                        Gumdrop.getInstance().nextWorkerLoop());
            }
        }
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).start();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE,
                        "Failed to start DNS listener: " + listener, e);
            }
        }
    }

    private void stopListener(Object listener) {
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Error stopping DNS listener: " + listener, e);
            }
        }
    }

    private static InetSocketAddress parseAddress(String address,
                                                  int defaultPort)
            throws Exception {
        int port = defaultPort;
        String host = address;

        if (address.startsWith("[")) {
            int bracketEnd = address.indexOf(']');
            if (bracketEnd > 0) {
                host = address.substring(1, bracketEnd);
                if (address.length() > bracketEnd + 2
                        && address.charAt(bracketEnd + 1) == ':') {
                    port = Integer.parseInt(
                            address.substring(bracketEnd + 2));
                }
            }
        } else if (address.indexOf(':') >= 0
                && address.indexOf("::") < 0) {
            int colonIdx = address.lastIndexOf(':');
            host = address.substring(0, colonIdx);
            port = Integer.parseInt(
                    address.substring(colonIdx + 1));
        }

        InetAddress inetAddr = InetAddress.getByName(host);
        return new InetSocketAddress(inetAddr, port);
    }

    private void loadSystemResolvers() {
        Path resolvConf = Paths.get("/etc/resolv.conf");
        if (Files.exists(resolvConf)) {
            try {
                BufferedReader reader = new BufferedReader(
                        new FileReader(resolvConf.toFile()));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("nameserver ")) {
                            String server = line.substring(11).trim();
                            try {
                                InetSocketAddress addr =
                                        parseAddress(server,
                                                DEFAULT_PORT);
                                upstreamServers.add(addr);
                                if (LOGGER.isLoggable(Level.FINE)) {
                                    String msg = MessageFormat.format(
                                            L10N.getString(
                                                    "debug.added_system_resolver"),
                                            addr);
                                    LOGGER.fine(msg);
                                }
                            } catch (Exception e) {
                                String msg = MessageFormat.format(
                                        L10N.getString(
                                                "debug.skip_invalid_resolver"),
                                        server);
                                LOGGER.log(Level.FINE, msg, e);
                            }
                        }
                    }
                } finally {
                    reader.close();
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE,
                        L10N.getString("err.read_resolv_conf"), e);
            }
        }

        if (upstreamServers.isEmpty()) {
            try {
                InetAddress google =
                        InetAddress.getByName("8.8.8.8");
                upstreamServers.add(
                        new InetSocketAddress(google, DEFAULT_PORT));
                InetAddress cloudflare =
                        InetAddress.getByName("1.1.1.1");
                upstreamServers.add(
                        new InetSocketAddress(cloudflare, DEFAULT_PORT));
                LOGGER.fine(L10N.getString("debug.using_fallback"));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("warn.no_fallback"), e);
            }
        }
    }

}
