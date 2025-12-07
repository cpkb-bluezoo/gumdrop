/*
 * DNSServer.java
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
import java.util.List;
import java.util.ResourceBundle;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.DatagramServer;

/**
 * A DNS server that can resolve queries locally or proxy to upstream servers.
 *
 * <p>The default implementation proxies all queries to configured upstream
 * DNS servers. Subclasses can override {@link #resolve(DNSMessage)} to
 * provide custom name resolution.
 *
 * <p>Features:
 * <ul>
 * <li>Configurable upstream DNS servers</li>
 * <li>Optional system resolver fallback</li>
 * <li>In-memory response caching with TTL support</li>
 * <li>Negative caching for NXDOMAIN responses</li>
 * <li>DTLS support for secure DNS</li>
 * </ul>
 *
 * <p>Example configuration in gumdroprc:
 * <pre>
 * dns = org.bluezoo.gumdrop.dns.DNSServer {
 *     port = 5353
 *     upstreamServers = "8.8.8.8 1.1.1.1"
 *     useSystemResolvers = true
 *     cacheEnabled = true
 * }
 * </pre>
 *
 * <p>Example subclass for custom resolution:
 * <pre>
 * public class MyDNSServer extends DNSServer {
 *     &#64;Override
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
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSServer extends DatagramServer {

    private static final Logger LOGGER = Logger.getLogger(DNSServer.class.getName());
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private static final int DEFAULT_PORT = 53;
    private static final int UPSTREAM_TIMEOUT_MS = 5000;
    private static final int MAX_DNS_MESSAGE_SIZE = 512;

    private int port = DEFAULT_PORT;
    private final List<InetSocketAddress> upstreamServers = new ArrayList<>();
    private boolean useSystemResolvers = true;
    private boolean cacheEnabled = true;
    private DNSCache cache;

    private final AtomicInteger queryIdGenerator = new AtomicInteger(0);

    /**
     * Creates a new DNS server.
     */
    public DNSServer() {
        super();
    }

    // -- Configuration --

    /**
     * Sets the port to listen on.
     *
     * @param port the port number (default 53)
     */
    public void setPort(int port) {
        this.port = port;
    }

    @Override
    protected int getPort() {
        return port;
    }

    @Override
    public String getDescription() {
        return "dns";
    }

    /**
     * Sets the upstream DNS servers to use for proxying.
     *
     * <p>Format: space-separated list of addresses with optional port.
     * Examples:
     * <ul>
     * <li>"8.8.8.8 1.1.1.1" - Google and Cloudflare DNS</li>
     * <li>"192.168.1.1:53" - Local router with explicit port</li>
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
                InetSocketAddress addr = parseAddress(server, DEFAULT_PORT);
                upstreamServers.add(addr);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Invalid upstream server: " + server, e);
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

    private static InetSocketAddress parseAddress(String address, int defaultPort) throws Exception {
        int port = defaultPort;
        String host = address;

        // Handle [IPv6]:port format
        if (address.startsWith("[")) {
            int bracketEnd = address.indexOf(']');
            if (bracketEnd > 0) {
                host = address.substring(1, bracketEnd);
                if (address.length() > bracketEnd + 2 && address.charAt(bracketEnd + 1) == ':') {
                    port = Integer.parseInt(address.substring(bracketEnd + 2));
                }
            }
        } else if (address.contains(":") && !address.contains("::")) {
            // IPv4:port format (but not IPv6 with ::)
            int colonIdx = address.lastIndexOf(':');
            host = address.substring(0, colonIdx);
            port = Integer.parseInt(address.substring(colonIdx + 1));
        }

        InetAddress inetAddr = InetAddress.getByName(host);
        return new InetSocketAddress(inetAddr, port);
    }

    // -- Lifecycle --

    @Override
    public void open() throws IOException {
        // Initialize cache
        if (cacheEnabled) {
            cache = new DNSCache();
        }

        // Load system resolvers if enabled and no upstream configured
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

        super.open();

        String message = L10N.getString("info.dns_server_started");
        message = MessageFormat.format(message, port);
        LOGGER.info(message);
    }

    private void loadSystemResolvers() {
        // Try /etc/resolv.conf on Unix-like systems
        Path resolvConf = Paths.get("/etc/resolv.conf");
        if (Files.exists(resolvConf)) {
            try (BufferedReader reader = new BufferedReader(new FileReader(resolvConf.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("nameserver ")) {
                        String server = line.substring(11).trim();
                        try {
                            InetSocketAddress addr = parseAddress(server, DEFAULT_PORT);
                            upstreamServers.add(addr);
                            if (LOGGER.isLoggable(Level.FINE)) {
                                LOGGER.fine("Added system resolver: " + addr);
                            }
                        } catch (Exception e) {
                            LOGGER.log(Level.FINE, "Skipping invalid resolver: " + server, e);
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Could not read /etc/resolv.conf", e);
            }
        }

        // Fallback to well-known public DNS if still empty
        if (upstreamServers.isEmpty()) {
            try {
                InetAddress google = InetAddress.getByName("8.8.8.8");
                upstreamServers.add(new InetSocketAddress(google, DEFAULT_PORT));
                InetAddress cloudflare = InetAddress.getByName("1.1.1.1");
                upstreamServers.add(new InetSocketAddress(cloudflare, DEFAULT_PORT));
                LOGGER.fine("Using fallback public DNS servers");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Could not add fallback DNS servers", e);
            }
        }
    }

    @Override
    public void close() {
        super.close();
        if (cache != null) {
            cache.clear();
        }
    }

    // -- Query handling --

    @Override
    protected void receive(ByteBuffer data, InetSocketAddress source) {
        try {
            DNSMessage query = DNSMessage.parse(data);

            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("debug.received_query");
                message = MessageFormat.format(message, query, source);
                LOGGER.fine(message);
            }

            // Only handle standard queries
            if (!query.isQuery() || query.getOpcode() != DNSMessage.OPCODE_QUERY) {
                DNSMessage error = query.createErrorResponse(DNSMessage.RCODE_NOTIMP);
                sendResponse(error, source);
                return;
            }

            if (query.getQuestions().isEmpty()) {
                DNSMessage error = query.createErrorResponse(DNSMessage.RCODE_FORMERR);
                sendResponse(error, source);
                return;
            }

            // Process the query
            DNSMessage response = processQuery(query);
            sendResponse(response, source);

        } catch (DNSFormatException e) {
            LOGGER.log(Level.FINE, "Malformed DNS query from " + source, e);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing DNS query from " + source, e);
        }
    }

    private DNSMessage processQuery(DNSMessage query) {
        DNSQuestion question = query.getQuestions().get(0);

        // 1. Check cache
        if (cacheEnabled && cache != null) {
            // Check negative cache first
            if (cache.isNegativelyCached(question.getName())) {
                return query.createErrorResponse(DNSMessage.RCODE_NXDOMAIN);
            }

            List<DNSResourceRecord> cached = cache.lookup(question);
            if (cached != null) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.finest("Cache hit for " + question);
                }
                return query.createResponse(cached);
            }
        }

        // 2. Try custom resolution
        DNSMessage customResponse = resolve(query);
        if (customResponse != null) {
            // Cache the custom response
            if (cacheEnabled && cache != null && !customResponse.getAnswers().isEmpty()) {
                cache.cache(question, customResponse.getAnswers());
            }
            return customResponse;
        }

        // 3. Proxy to upstream
        DNSMessage upstreamResponse = proxyToUpstream(query);
        if (upstreamResponse != null) {
            // Cache the upstream response
            if (cacheEnabled && cache != null) {
                if (upstreamResponse.getRcode() == DNSMessage.RCODE_NXDOMAIN) {
                    cache.cacheNegative(question.getName());
                } else if (!upstreamResponse.getAnswers().isEmpty()) {
                    cache.cache(question, upstreamResponse.getAnswers());
                }
            }
            return upstreamResponse;
        }

        // No response available
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

    /**
     * Proxies a query to upstream DNS servers.
     *
     * @param query the query to proxy
     * @return the response, or null if all upstreams failed
     */
    private DNSMessage proxyToUpstream(DNSMessage query) {
        if (upstreamServers.isEmpty()) {
            return null;
        }

        // Generate a new query ID for upstream
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

        // Try each upstream in order
        for (InetSocketAddress upstream : upstreamServers) {
            try {
                DatagramSocket socket = new DatagramSocket();
                try {
                    socket.setSoTimeout(UPSTREAM_TIMEOUT_MS);

                    // Send query
                    DatagramPacket sendPacket = new DatagramPacket(queryData, queryData.length, upstream);
                    socket.send(sendPacket);

                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.finest("Sent query to upstream " + upstream);
                    }

                    // Receive response
                    byte[] responseData = new byte[MAX_DNS_MESSAGE_SIZE];
                    DatagramPacket receivePacket = new DatagramPacket(responseData, responseData.length);
                    socket.receive(receivePacket);

                    ByteBuffer responseBuffer = ByteBuffer.wrap(responseData, 0, receivePacket.getLength());
                    DNSMessage response = DNSMessage.parse(responseBuffer);

                    // Restore original query ID
                    DNSMessage finalResponse = new DNSMessage(
                            query.getId(),
                            response.getFlags(),
                            response.getQuestions(),
                            response.getAnswers(),
                            response.getAuthorities(),
                            response.getAdditionals()
                    );

                    if (LOGGER.isLoggable(Level.FINE)) {
                        String message = L10N.getString("debug.upstream_response");
                        message = MessageFormat.format(message, upstream, finalResponse);
                        LOGGER.fine(message);
                    }

                    return finalResponse;
                } finally {
                    socket.close();
                }

            } catch (SocketTimeoutException e) {
                LOGGER.log(Level.FINE, "Timeout querying upstream " + upstream);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error querying upstream " + upstream, e);
            } catch (DNSFormatException e) {
                LOGGER.log(Level.FINE, "Invalid response from upstream " + upstream, e);
            }
        }

        return null;
    }

    private void sendResponse(DNSMessage response, InetSocketAddress destination) {
        ByteBuffer data = response.serialize();

        if (LOGGER.isLoggable(Level.FINE)) {
            String message = L10N.getString("debug.sending_response");
            message = MessageFormat.format(message, response, destination);
            LOGGER.fine(message);
        }

        send(data, destination);
    }

    /**
     * Returns the DNS cache.
     *
     * @return the cache, or null if caching is disabled
     */
    public DNSCache getCache() {
        return cache;
    }

}
