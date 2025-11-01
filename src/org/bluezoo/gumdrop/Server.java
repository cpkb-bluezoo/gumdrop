/*
 * Server.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Iterator;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The gumdrop server.
 * This class is responsible for serving a collection of connectors,
 * via a <code>select</code> loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Server extends Thread {

    public static final String VERSION = "0.3";

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");
    static final Logger LOGGER = Logger.getLogger(Server.class.getName());

    static Server instance;

    /**
     * Returns the singleton server instance.
     */
    public static Server getInstance() {
        return instance;
    }

    final Executor sslMainExecutor; // for main SSL operations
    final ThreadPoolExecutor sslTaskThreadPool; // for SSLEngine delayed task operations

    private Collection<Connector> connectors;
    private Selector selector;
    private final Set<Connection> connectionsWithPendingWrites;
    private ByteBuffer readBuffer; // reusable per selector

    private volatile boolean active;
    private volatile boolean reload;

    /**
     * Creates a new Server instance with the provided connectors.
     * This constructor allows for programmatic configuration of the server.
     *
     * @param connectors the collection of connectors to serve
     */
    public Server(Collection<Connector> connectors) {
        super("Server");
        instance = this; // Set singleton instance
        this.connectors = connectors;
        ThreadFactory factory = Executors.defaultThreadFactory();
        sslMainExecutor = Executors.newSingleThreadExecutor(new GumdropThreadFactory(factory, "ssl-main"));
        sslTaskThreadPool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new GumdropThreadFactory(factory, "ssl-task"));
        connectionsWithPendingWrites = ConcurrentHashMap.newKeySet();
        readBuffer = ByteBuffer.allocate(8192);
        Runtime.getRuntime().addShutdownHook(this.new Shutdown());
    }


    /**
     * The main service loop.
     */
    public void run() {
        long t3, t4;
        active = true;
        do {
            reload = false;
            selector = null;
            t3 = System.currentTimeMillis();
            try {
                // Open selector
                selector = Selector.open();

                // Create server socket for each connector
                for (Iterator i = connectors.iterator(); i.hasNext(); ) {
                    registerConnector((Connector) i.next());
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace(System.err);
                System.err.flush();
                System.exit(2);
            }
            t4 = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.INFO)) {
                String message = L10N.getString("info.started_server");
                message = MessageFormat.format(message, (t4 - t3));
                LOGGER.info(message);
            }

            try {
                // Multiplex loop
                while (active) {
                    try {
                        processIncomingWriteRequests();
                        selector.select();
                        processIncomingWriteRequests();
                        Set<SelectionKey> sk = selector.selectedKeys();
                        if (sk.size() > 0) {
                            for (Iterator<SelectionKey> i = sk.iterator(); i.hasNext(); ) {
                                SelectionKey key = i.next();
                                i.remove();
                                if (!key.isValid()) {
                                    continue;
                                }
                                processKey(key);
                            }
                        }
                    } catch (CancelledKeyException e) {
                        // NOOP jamvm
                    } catch (IOException e) {
                        if ("Bad file descriptor".equals(e.getMessage())) {
                            // NOOP
                        } else {
                            throw e;
                        }
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) {
                            // NOOP jamvm
                        } else if (e instanceof RuntimeException) {
                            throw (RuntimeException) e;
                        } else {
                            RuntimeException e2 = new RuntimeException();
                            e2.initCause(e);
                            throw e2;
                        }
                    }
                }
            } catch (Throwable e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace(System.err);
                System.err.flush();
            } finally {
                close();
                try {
                    selector.close();
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        } while (reload);
        LOGGER.info(L10N.getString("info.server_end_loop"));
        System.out.flush();
        System.err.flush();
    }

    /**
     * Closes and restarts the server. This will reload its configuration.
     */
    public void reload() {
        reload = true;
        active = false;
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Register a connector with this server.
     * This will bind its port.
     *
     * @param connector the connector
     */
    public void registerConnector(Connector connector) throws IOException {
        long t1, t2;
        Set<InetAddress> addresses = connector.getAddresses();
        int port = connector.getPort();

        for (InetAddress address : addresses) {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ServerSocket ss = ssc.socket();

            // Bind server socket to port
            InetSocketAddress socketAddress = new InetSocketAddress(address, port);
            t1 = System.currentTimeMillis();
            ss.bind(socketAddress);
            t2 = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("info.bound_connector");
                message = MessageFormat.format(message, connector.getDescription(), port, address, (t2 - t1));
                LOGGER.fine(message);
            }

            // Register selector for accept
            SelectionKey key = ssc.register(selector, SelectionKey.OP_ACCEPT);
            key.attach(connector);

            connector.addServerChannel(ssc);
        }
        connector.start();
    }

    private void unregisterConnector(Connector connector) throws IOException {
        connector.stop();
        connector.closeServerChannels();
    }

    private void processKey(SelectionKey key) throws IOException {
        int readyOps = key.readyOps();
        if ((readyOps & SelectionKey.OP_ACCEPT) != 0) {
            // accept - batch process if multiple connections pending
            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
            Connector connector = (Connector) key.attachment();
            
            // Process all pending connections to avoid selector thrashing
            SocketChannel sc;
            while ((sc = ssc.accept()) != null) {
                try {
                    // Check if connector accepts this connection
                    InetSocketAddress remoteAddress = (InetSocketAddress) sc.getRemoteAddress();
                    if (!connector.acceptConnection(remoteAddress)) {
                        // Connection rejected - close immediately and continue
                        if (LOGGER.isLoggable(Level.FINE)) {
                            String message = L10N.getString("info.connection_rejected");
                            if (message == null) {
                                message = "Connection rejected from {0}";
                            }
                            message = MessageFormat.format(message, remoteAddress.toString());
                            LOGGER.fine(message);
                        }
                        sc.close();
                        continue; // Process next connection
                    }
                    
                    // Connection accepted - proceed with normal flow
                    sc.configureBlocking(false);
                    SelectionKey skey = sc.register(selector, SelectionKey.OP_READ);
                    Connection connection = connector.newConnection(sc, skey);
                    skey.attach(connection);
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        String message = L10N.getString("info.accepted");
                        message = MessageFormat.format(message, remoteAddress.toString());
                        LOGGER.finest(message);
                    }
                } catch (IOException e) {
                    // Error with this connection - close and continue with others
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.log(Level.WARNING, "Error processing accepted connection", e);
                    }
                    try {
                        sc.close();
                    } catch (IOException closeEx) {
                        // Ignore close errors
                    }
                }
            }
        }
        if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
            // client channel connect
            Connection connection = (Connection) key.attachment();
            SocketChannel sc = (SocketChannel) key.channel();
            try {
                if (sc.finishConnect()) {
                    SelectionKey skey = sc.register(selector, SelectionKey.OP_READ);
                    skey.attach(connection);
                    connection.key = skey;
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        String message = L10N.getString("info.connected");
                        message = MessageFormat.format(message, sc.toString());
                        LOGGER.finest(message);
                    }
                    connection.connected();
                }
            } catch (IOException ce) {
                connection.finishConnectFailed(ce);
            }
        }
        if ((readyOps & SelectionKey.OP_WRITE) != 0) {
            // write
            Connection connection = (Connection) key.attachment();
            SocketChannel sc = (SocketChannel) key.channel();
            BlockingQueue<ByteBuffer> queue = connection.outboundQueue;
            ByteBuffer buffer = null;
            try {
                while (queue.peek() != null) {
                    buffer = queue.peek(); // do not remove until we write all data in the buffer
                    int len = sc.write(buffer);
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        Object sa = sc.socket().getRemoteSocketAddress();
                        String message = Server.L10N.getString("info.sent");
                        message = MessageFormat.format(message, len, sa);
                        LOGGER.finest(message);
                    }
                    if (buffer.hasRemaining()) {
                        // cannot write all data. Keep OP_WRITE interest
                        return;
                    } else {
                        // buffer completely written
                        queue.take();
                    }
                }
                // If we got here all buffers in the queue were written.
                // We can remove OP_WRITE interest for this connection.
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                connection.setHasOpWriteInterest(false);
                connectionsWithPendingWrites.remove(connection);
                if (connection.closeAfterSend) {
                    connection.close();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                String message = L10N.getString("err.write");
                message = MessageFormat.format(message, sc);
                LOGGER.log(Level.WARNING, message, e);
                connection.close();
                key.cancel();
                connectionsWithPendingWrites.remove(connection);
            } catch (IOException e) {
                String message = L10N.getString("err.write");
                message = MessageFormat.format(message, sc);
                LOGGER.log(Level.WARNING, message, e);
                connection.close();
                key.cancel();
                connectionsWithPendingWrites.remove(connection);
            }
        }
        if ((readyOps & SelectionKey.OP_READ) != 0) {
            // read
            Connection connection = (Connection) key.attachment();
            SocketChannel sc = (SocketChannel) key.channel();
            readBuffer.clear();
            try {
                int len = sc.read(readBuffer);
                if (len == -1) {
                    //connection.eof();
                    //throw new EOFException();
                } else if (len > 0) {
                    readBuffer.flip();
                    ByteBuffer data = ByteBuffer.allocate(len);
                    data.put(readBuffer);
                    data.flip();
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        Object sa = sc.socket().getRemoteSocketAddress();
                        String message = Server.L10N.getString("info.received");
                        message = MessageFormat.format(message, len, sa);
                        LOGGER.finest(message);
                    }
                    connection.receive(data);
                }
            } catch (IOException e) {
                connection.receiveFailed(e);
                Object sa = sc.socket().getRemoteSocketAddress();
                String message = L10N.getString("err.read");
                message = MessageFormat.format(message, sa);
                LOGGER.log(Level.WARNING, message, e);
                connection.close();
                key.cancel();
                connectionsWithPendingWrites.remove(connection);
            }
        }
    }

    /**
     * Called by a Connection to submit a write request.
     * May be called either on the sslMainExecutor thread or on an
     * application connector pool thread.
     */
    void addWriteRequest(Connection connection) {
        connectionsWithPendingWrites.add(connection);
        selector.wakeup();
    }

    private void processIncomingWriteRequests() {
        for (Connection connection : connectionsWithPendingWrites) {
            SelectionKey key = connection.key;
            if (key == null || !key.isValid() || !connection.channel.isOpen()) {
                connectionsWithPendingWrites.remove(connection);
                connection.outboundQueue.clear();
                continue;
            }
            if (connection.outboundQueue.peek() != null && !connection.hasOpWriteInterest()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                connection.setHasOpWriteInterest(true);
                selector.wakeup();
            }
        }
    }

    synchronized void close() {
        if (connectors == null) {
            return;
        }
        String message = L10N.getString("info.closing_connectors");
        LOGGER.info(message);
        try {
            // Clean up
            for (Connector connector : connectors) {
                unregisterConnector(connector);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        } finally {
            connectors = null;
        }
    }

    public static void main(String[] args) {
        // Determine configuration file location
        File gumdroprc;
        if (args.length > 0) {
            gumdroprc = new File(args[0]);
        } else {
            gumdroprc = new File(System.getProperty("user.home") + File.separator + ".gumdroprc");
        }
        if (!gumdroprc.exists()) {
            gumdroprc = new File("/etc/gumdroprc");
        }
        if (!gumdroprc.exists()) {
            System.out.println(L10N.getString("err.syntax"));
            System.exit(1);
        }

        // Parse configuration file to get connectors
        Collection<Connector> connectors;
        try {
            long t1 = System.currentTimeMillis();
            connectors = new ConfigurationParser().parse(gumdroprc);
            long t2 = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("info.read_configuration");
                message = MessageFormat.format(message, (t2 - t1));
                LOGGER.fine(message);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to parse configuration file: " + gumdroprc, e);
            System.exit(2);
            return; // unreachable, but helps with compilation
        }

        // Create and start server with parsed connectors
        instance = new Server(connectors);
        System.out.println(L10N.getString("banner"));
        instance.run();
    }

    private static final class GumdropThreadFactory implements ThreadFactory {

        final ThreadFactory factory;
        final String name;
        long threadNum = 1L;

        GumdropThreadFactory(ThreadFactory factory, String name) {
            this.factory = factory;
            this.name = name;
        }

        public Thread newThread(Runnable r) {
            Thread t = factory.newThread(r);
            t.setName(name + "-" + (threadNum++));
            return t;
        }
    }

    private final class Shutdown extends Thread {

        public void run() {
            active = false;
            if (selector != null) {
                selector.wakeup();
            }
        }

    }

    static String hexdump(ByteBuffer buffer) {
        StringBuilder sink = new StringBuilder();
        int pos = buffer.position();
        int off = 0;
        int line = 0;
        while (buffer.hasRemaining()) {
            int c = buffer.get() & 0xff;
            if (line == 0) {
                sink.append(String.format("%04x - ", off));
            }
            sink.append(String.format("%02x ", c));
            off++;
            line++;
            if (line > 16) {
                sink.append('\n');
                line = 0;
            }
        }
        sink.append("\n(").append(off).append(") bytes");
        buffer.position(pos);
        return sink.toString();
    }

}
