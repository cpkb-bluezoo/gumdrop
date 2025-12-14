/*
 * DatagramClient.java
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
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A client that sends and receives datagrams to/from a specific remote address.
 *
 * <p>DatagramClient uses a "connected" DatagramChannel, which means all
 * datagrams are sent to and received from a single remote address. This
 * provides some efficiency benefits and allows the use of simpler read/write
 * operations.
 *
 * <p>Subclasses implement {@link #receive(ByteBuffer)} to handle incoming
 * datagrams and can use {@link #send(ByteBuffer)} to send data.
 *
 * <p>For DTLS-secured connections, encryption and decryption are handled
 * transparently.
 *
 * <p>Example usage:
 * <pre><code>
 * public class SimpleDNSClient extends DatagramClient {
 *
 *     public SimpleDNSClient(String server) throws UnknownHostException {
 *         super(server, 53);
 *     }
 *
 *     &#64;Override
 *     protected void receive(ByteBuffer data) {
 *         DNSMessage response = DNSMessage.parse(data);
 *         // Process response...
 *     }
 *
 *     public void query(DNSMessage query) {
 *         send(query.serialize());
 *     }
 *
 *     &#64;Override
 *     protected String getDescription() {
 *         return "dns-client";
 *     }
 * }
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class DatagramClient extends DatagramConnector implements ChannelHandler {

    private static final Logger LOGGER = Logger.getLogger(DatagramClient.class.getName());

    /** Default buffer size for datagram I/O (max UDP payload) */
    private static final int DEFAULT_BUFFER_SIZE = 65535;

    protected InetAddress host;
    protected int port;

    private DatagramChannel channel;
    private SelectionKey key;
    private SelectorLoop selectorLoop;

    // DTLS session (single session for the connected remote)
    private DTLSSession dtlsSession;

    // Network I/O buffer for receiving
    ByteBuffer netIn;
    
    // Queue of pending outbound datagrams (thread-safe for multiple senders)
    final Deque<ByteBuffer> pendingDatagrams = new ConcurrentLinkedDeque<>();

    /**
     * Creates a client that will communicate with the specified host and port.
     *
     * @param host the target host as a String
     * @param port the target port number
     * @throws UnknownHostException if the host cannot be resolved
     */
    public DatagramClient(String host, int port) throws UnknownHostException {
        this(InetAddress.getByName(host), port);
    }

    /**
     * Creates a client that will communicate with the specified host and port.
     *
     * @param host the target host as an InetAddress
     * @param port the target port number
     */
    public DatagramClient(InetAddress host, int port) {
        super();
        this.host = host;
        this.port = port;
    }

    // -- ChannelHandler implementation --

    @Override
    public final Type getChannelType() {
        return Type.DATAGRAM_CLIENT;
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
     * Returns the target host address.
     *
     * @return the host address
     */
    public InetAddress getHost() {
        return host;
    }

    /**
     * Returns the target port number.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    @Override
    protected void configureDTLSEngine(javax.net.ssl.SSLEngine engine) {
        super.configureDTLSEngine(engine);
        engine.setUseClientMode(true);  // Client mode for outbound connections
    }

    // -- Lifecycle --

    /**
     * Opens the datagram channel and connects to the remote address.
     * The channel is registered with a SelectorLoop for event-driven I/O.
     *
     * @throws IOException if the channel cannot be opened
     */
    public void open() throws IOException {
        super.start();

        // Allocate network I/O buffer
        netIn = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);

        channel = DatagramChannel.open();
        channel.configureBlocking(false);

        // Connect the channel to the remote address
        // This makes all send/receive operations go to/from this address
        InetSocketAddress remoteAddress = new InetSocketAddress(host, port);
        channel.connect(remoteAddress);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("DatagramClient connected to " + remoteAddress);
        }

        // Initialize DTLS session if secure
        if (secure) {
            javax.net.ssl.SSLEngine engine = createDTLSEngine(remoteAddress);
            dtlsSession = new DTLSSession(engine, this, remoteAddress);
            // Initiate DTLS handshake
            dtlsSession.beginHandshake();
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

        // Close DTLS session
        if (dtlsSession != null) {
            dtlsSession.close();
            dtlsSession = null;
        }

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
     * Called when a datagram is received from the remote.
     * For DTLS connections, the data is already decrypted.
     *
     * @param data the datagram payload
     */
    protected abstract void receive(ByteBuffer data);

    /**
     * Sends a datagram to the remote.
     * For DTLS connections, the data is automatically encrypted.
     *
     * @param data the datagram payload
     */
    public void send(ByteBuffer data) {
        if (secure && dtlsSession != null) {
            ByteBuffer encrypted = dtlsSession.wrap(data);
            if (encrypted != null) {
                data = encrypted;
            }
        }
        
        // Copy data to a new buffer and queue it
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data);
        copy.flip();
        pendingDatagrams.add(copy);
        
        // Request write
        if (selectorLoop != null) {
            selectorLoop.requestDatagramWrite(this);
        }
    }

    // -- Package-private methods called by SelectorLoop --

    /**
     * Called by SelectorLoop when a datagram is received on the channel.
     */
    final void netReceive(ByteBuffer data) {
        if (secure && dtlsSession != null) {
            ByteBuffer decrypted = dtlsSession.unwrap(data);
            if (decrypted != null && decrypted.hasRemaining()) {
                receive(decrypted);
            }
            // Note: During handshake, unwrap may return null while
            // handshake messages are being exchanged
        } else {
            receive(data);
        }
    }

    /**
     * Returns the datagram channel.
     */
    DatagramChannel getChannel() {
        return channel;
    }

    /**
     * Returns the remote address this client is connected to.
     */
    InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(host, port);
    }

    /**
     * Sends a raw datagram (used by DTLSSession for handshake messages).
     */
    void netSend(ByteBuffer data) {
        // Copy data to a new buffer and queue it
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data);
        copy.flip();
        pendingDatagrams.add(copy);
        
        // Request write
        if (selectorLoop != null) {
            selectorLoop.requestDatagramWrite(this);
        }
    }

}

