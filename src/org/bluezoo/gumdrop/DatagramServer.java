/*
 * DatagramServer.java
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

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.text.MessageFormat;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A server that binds to a UDP port and handles datagrams.
 *
 * <p>Unlike TCP servers, a DatagramServer does not create separate connection
 * objects for each remote address. Instead, the server itself receives all
 * datagrams and handles them directly via the {@link #receive(ByteBuffer, InetSocketAddress)}
 * method.
 *
 * <p>Subclasses implement {@link #receive(ByteBuffer, InetSocketAddress)} to
 * handle incoming datagrams and can use {@link #send(ByteBuffer, InetSocketAddress)}
 * to send responses.
 *
 * <p>For DTLS-secured connections, encryption and decryption are handled
 * transparently. Per-remote-address DTLS session state is maintained internally.
 *
 * <p>Example usage:
 * <pre><code>
 * public class SimpleDNSServer extends DatagramServer {
 *     &#64;Override
 *     protected int getPort() {
 *         return 53;
 *     }
 *
 *     &#64;Override
 *     protected void receive(ByteBuffer data, InetSocketAddress source) {
 *         DNSMessage query = DNSMessage.parse(data);
 *         DNSMessage response = resolve(query);
 *         send(response.serialize(), source);
 *     }
 *
 *     &#64;Override
 *     protected String getDescription() {
 *         return "dns";
 *     }
 * }
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class DatagramServer extends DatagramConnector implements ChannelHandler {

    private static final Logger LOGGER = Logger.getLogger(DatagramServer.class.getName());

    /** Default buffer size for datagram I/O (max UDP payload) */
    private static final int DEFAULT_BUFFER_SIZE = 65535;

    private DatagramChannel channel;
    private SelectionKey key;
    private SelectorLoop selectorLoop;
    private Set<InetAddress> addresses = null;
    protected boolean needClientAuth = false;

    // Network I/O buffers (per-server, reusable)
    ByteBuffer netIn;
    
    // Queue of pending outbound datagrams (thread-safe for multiple senders)
    final Deque<PendingDatagram> pendingDatagrams = new ConcurrentLinkedDeque<>();

    // DTLS sessions indexed by remote address (only used when secure=true)
    private final ConcurrentMap<InetSocketAddress, DTLSSession> dtlsSessions = new ConcurrentHashMap<>();
    
    /**
     * A pending datagram waiting to be sent.
     */
    static final class PendingDatagram {
        final ByteBuffer data;
        final InetSocketAddress destination;
        
        PendingDatagram(ByteBuffer data, InetSocketAddress destination) {
            this.data = data;
            this.destination = destination;
        }
    }

    protected DatagramServer() {
        super();
    }

    // -- ChannelHandler implementation --

    @Override
    public final Type getChannelType() {
        return Type.DATAGRAM_SERVER;
    }

    @Override
    public SelectionKey getSelectionKey() {
        return key;
    }

    @Override
    public void setSelectionKey(SelectionKey key) {
        this.key = key;
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    @Override
    public void setSelectorLoop(SelectorLoop loop) {
        this.selectorLoop = loop;
    }

    // -- Configuration --

    /**
     * Configures the addresses this server should bind to.
     *
     * @param value space-separated list of addresses, or null for all interfaces
     */
    public void setAddresses(String value) {
        if (value == null) {
            addresses = null;
            return;
        }
        addresses = new LinkedHashSet<>();
        StringTokenizer st = new StringTokenizer(value);
        while (st.hasMoreTokens()) {
            String host = st.nextToken();
            try {
                addresses.add(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                String message = Gumdrop.L10N.getString("err.unknown_host");
                message = MessageFormat.format(message, host);
                LOGGER.log(Level.SEVERE, message, e);
            }
        }
    }

    /**
     * Sets whether DTLS client authentication is required.
     *
     * @param flag true to require client certificates
     */
    public void setNeedClientAuth(boolean flag) {
        needClientAuth = flag;
    }

    /**
     * Returns the port number this server should bind to.
     *
     * @return the port number
     */
    protected abstract int getPort();

    /**
     * Returns the IP addresses this server should bind to.
     *
     * @return the set of addresses
     * @throws IOException if network interfaces cannot be enumerated
     */
    protected Set<InetAddress> getAddresses() throws IOException {
        if (addresses == null) {
            addresses = new LinkedHashSet<>();
            for (Enumeration<NetworkInterface> e1 = NetworkInterface.getNetworkInterfaces(); e1.hasMoreElements(); ) {
                NetworkInterface ni = e1.nextElement();
                for (Enumeration<InetAddress> e2 = ni.getInetAddresses(); e2.hasMoreElements(); ) {
                    addresses.add(e2.nextElement());
                }
            }
        }
        return addresses;
    }

    @Override
    protected void configureDTLSEngine(javax.net.ssl.SSLEngine engine) {
        super.configureDTLSEngine(engine);
        engine.setUseClientMode(false);
        if (needClientAuth) {
            engine.setNeedClientAuth(true);
        } else {
            engine.setWantClientAuth(true);
        }
    }

    /**
     * Returns the protocol family for the datagram channel.
     *
     * <p>Override this method to specify IPv4 or IPv6 explicitly.
     * The default implementation returns null, which uses the
     * system default.
     *
     * @return the protocol family, or null for system default
     */
    protected ProtocolFamily getProtocolFamily() {
        return null;
    }

    /**
     * Called after the channel is opened but before it is registered
     * with the selector.
     *
     * <p>Override this method to perform additional channel configuration
     * such as joining multicast groups or setting socket options.
     *
     * @param channel the datagram channel
     * @throws IOException if configuration fails
     */
    protected void configureChannel(DatagramChannel channel) throws IOException {
        // Default: no additional configuration
    }

    // -- Lifecycle --

    /**
     * Opens and binds the datagram channel.
     *
     * @throws IOException if the channel cannot be opened or bound
     */
    public void open() throws IOException {
        super.start();

        // Allocate network I/O buffer
        netIn = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);

        int port = getPort();

        // Open channel with optional protocol family
        ProtocolFamily family = getProtocolFamily();
        if (family != null) {
            channel = DatagramChannel.open(family);
        } else {
            channel = DatagramChannel.open();
        }
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);

        // Bind to first address (or wildcard if none specified)
        Set<InetAddress> addrs = getAddresses();
        InetAddress bindAddr = addrs.isEmpty() ? null : addrs.iterator().next();
        InetSocketAddress socketAddress = new InetSocketAddress(bindAddr, port);
        channel.bind(socketAddress);

        // Allow subclass to configure channel (e.g., join multicast groups)
        configureChannel(channel);

        if (LOGGER.isLoggable(Level.FINE)) {
            String message = Gumdrop.L10N.getString("info.bound_server");
            message = MessageFormat.format(message, getDescription(), port,
                    bindAddr != null ? bindAddr : "0.0.0.0", 0L);
            LOGGER.fine(message);
        }

        // Register with a worker SelectorLoop
        Gumdrop gumdrop = Gumdrop.getInstance();
        SelectorLoop workerLoop = gumdrop.nextWorkerLoop();
        workerLoop.registerDatagram(channel, this);

        // Register with Gumdrop for lifecycle tracking
        gumdrop.addChannelHandler(this);
    }

    /**
     * Closes the datagram channel.
     */
    public void close() {
        super.stop();

        // Clear DTLS sessions
        for (DTLSSession session : dtlsSessions.values()) {
            session.close();
        }
        dtlsSessions.clear();

        // Close channel
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing datagram channel", e);
            }
        }

        // Cancel key
        if (key != null) {
            key.cancel();
        }

        // Deregister from Gumdrop for lifecycle tracking
        Gumdrop gumdrop = Gumdrop.getInstance();
        if (gumdrop != null) {
            gumdrop.removeChannelHandler(this);
        }
    }

    // -- Datagram I/O --

    /**
     * Called when a datagram is received.
     * For DTLS connections, the data is already decrypted.
     *
     * @param data the datagram payload
     * @param source the source address of the datagram
     */
    protected abstract void receive(ByteBuffer data, InetSocketAddress source);

    /**
     * Sends a datagram to the specified destination.
     * For DTLS connections, the data is automatically encrypted.
     *
     * @param data the datagram payload
     * @param dest the destination address
     */
    public void send(ByteBuffer data, InetSocketAddress dest) {
        if (secure) {
            DTLSSession session = dtlsSessions.get(dest);
            if (session != null) {
                ByteBuffer encrypted = session.wrap(data);
                if (encrypted != null) {
                    data = encrypted;
                }
            }
        }
        
        // Copy data to a new buffer and queue it
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data);
        copy.flip();
        pendingDatagrams.add(new PendingDatagram(copy, dest));
        
        // Request write
        if (selectorLoop != null) {
            selectorLoop.requestDatagramWrite(this);
        }
    }

    // -- Package-private methods called by SelectorLoop --

    /**
     * Called by SelectorLoop when a datagram is received on the channel.
     */
    final void netReceive(ByteBuffer data, InetSocketAddress source) {
        if (secure) {
            DTLSSession session = getOrCreateDTLSSession(source);
            ByteBuffer decrypted = session.unwrap(data);
            if (decrypted != null && decrypted.hasRemaining()) {
                receive(decrypted, source);
            }
            // Note: During handshake, unwrap may return null while
            // handshake messages are being exchanged
        } else {
            receive(data, source);
        }
    }

    /**
     * Gets or creates a DTLS session for the given remote address.
     */
    private DTLSSession getOrCreateDTLSSession(InetSocketAddress remoteAddress) {
        DTLSSession session = dtlsSessions.get(remoteAddress);
        if (session == null) {
            javax.net.ssl.SSLEngine engine = createDTLSEngine(remoteAddress);
            session = new DTLSSession(engine, this, remoteAddress);
            dtlsSessions.put(remoteAddress, session);
        }
        return session;
    }

    /**
     * Returns the datagram channel.
     */
    DatagramChannel getChannel() {
        return channel;
    }

    /**
     * Sends a raw datagram (used by DTLSSession for handshake messages).
     */
    void netSend(ByteBuffer data, InetSocketAddress dest) {
        // Copy data to a new buffer and queue it
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data);
        copy.flip();
        pendingDatagrams.add(new PendingDatagram(copy, dest));
        
        // Request write
        if (selectorLoop != null) {
            selectorLoop.requestDatagramWrite(this);
        }
    }
}


