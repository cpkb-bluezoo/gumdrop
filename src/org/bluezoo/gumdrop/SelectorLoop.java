/*
 * SelectorLoop.java
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
public class SelectorLoop extends Thread {

    public static final String VERSION = "0.3";

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");
    static final Logger LOGGER = Logger.getLogger(SelectorLoop.class.getName());

    static SelectorLoop instance;

    /**
     * Returns the singleton selector loop instance.
     */
    public static SelectorLoop getInstance() {
        return instance;
    }

    final Executor sslMainExecutor; // for main SSL operations
    final ThreadPoolExecutor sslTaskThreadPool; // for SSLEngine delayed task operations

    private Collection<Server> servers;
    private Selector selector;
    private final Set<Selectable> connectionsWithPendingWrites;
    private ByteBuffer readBuffer; // reusable per selector

    private volatile boolean active;
    private volatile boolean reload;

    /**
     * Creates a new SelectorLoop instance with the provided servers.
     * This constructor allows for programmatic configuration of the server.
     *
     * @param servers the collection of servers to serve
     */
    public SelectorLoop(Collection<Server> servers) {
        super("Server");
        instance = this; // Set singleton instance
        this.servers = servers;
        ThreadFactory factory = Executors.defaultThreadFactory();
        sslMainExecutor = Executors.newSingleThreadExecutor(new GumdropThreadFactory(factory, "ssl-main"));
        sslTaskThreadPool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new GumdropThreadFactory(factory, "ssl-task"));
        connectionsWithPendingWrites = ConcurrentHashMap.<Selectable>newKeySet();
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

                // Create server socket for each server
                for (Iterator i = servers.iterator(); i.hasNext(); ) {
                    registerServer((Server) i.next());
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace(System.err);
                System.err.flush();
                System.exit(2);
            }
            t4 = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.INFO)) {
                String message = L10N.getString("info.started_gumdrop");
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
        LOGGER.info(L10N.getString("info.gumdrop_end_loop"));
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
     * Register a server with this selector loop.
     * This will bind its port.
     *
     * @param server the server
     */
    public void registerServer(Server server) throws IOException {
        long t1, t2;
        Set<InetAddress> addresses = server.getAddresses();
        int port = server.getPort();

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
                String message = L10N.getString("info.bound_server");
                message = MessageFormat.format(message, server.getDescription(), port, address, (t2 - t1));
                LOGGER.fine(message);
            }

            // Register selector for accept
            SelectionKey key = ssc.register(selector, SelectionKey.OP_ACCEPT);
            key.attach(server);

            server.addServerChannel(ssc);
        }
        server.start();
    }

    private void unregisterServer(Server server) throws IOException {
        server.stop();
        server.closeServerChannels();
    }

    private void processKey(SelectionKey key) throws IOException {
        int readyOps = key.readyOps();
        if ((readyOps & SelectionKey.OP_ACCEPT) != 0) {
            // accept - batch process if multiple connections pending
            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
            Server server = (Server) key.attachment();
            
            // Process all pending connections to avoid selector thrashing
            SocketChannel sc;
            while ((sc = ssc.accept()) != null) {
                try {
                    // Check if connector accepts this connection
                    InetSocketAddress remoteAddress = (InetSocketAddress) sc.getRemoteAddress();
                    if (!server.acceptConnection(remoteAddress)) {
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
                    Connection connection = server.newConnection(sc, skey);
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
            // client channel connect (supports both Connection and SMTPClientConnection)
            Selectable selectable = (Selectable) key.attachment();
            SocketChannel sc = (SocketChannel) key.channel();
            try {
                if (sc.finishConnect()) {
                    // For Connection objects, update SelectionKey registration
                    if (selectable instanceof Connection) {
                        Connection connection = (Connection) selectable;
                        SelectionKey skey = sc.register(selector, SelectionKey.OP_READ);
                        skey.attach(connection);
                        connection.key = skey;
                    }
                    // For client connections like SMTP, they manage their own registration
                    
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        String message = L10N.getString("info.connected");
                        message = MessageFormat.format(message, sc.toString());
                        LOGGER.finest(message);
                    }
                    selectable.connected();
                }
            } catch (IOException ce) {
                selectable.finishConnectFailed(ce);
            }
        }
        if ((readyOps & SelectionKey.OP_WRITE) != 0) {
            // write (supports both Connection and client connections like SMTP)
            Selectable selectable = (Selectable) key.attachment();
            SocketChannel sc = (SocketChannel) key.channel();
            BlockingQueue<ByteBuffer> queue = selectable.getOutboundQueue();
            ByteBuffer buffer = null;
            try {
                while (queue.peek() != null) {
                    buffer = queue.peek(); // do not remove until we write all data in the buffer
                    int len = sc.write(buffer);
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        Object sa = sc.socket().getRemoteSocketAddress();
                        String message = SelectorLoop.L10N.getString("info.sent");
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
                selectable.setHasOpWriteInterest(false);
                connectionsWithPendingWrites.remove(selectable);
                if (selectable.shouldCloseAfterSend()) {
                    selectable.close();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                String message = L10N.getString("err.write");
                message = MessageFormat.format(message, sc);
                LOGGER.log(Level.WARNING, message, e);
                selectable.close();
                key.cancel();
                connectionsWithPendingWrites.remove(selectable);
            } catch (IOException e) {
                String message = L10N.getString("err.write");
                message = MessageFormat.format(message, sc);
                LOGGER.log(Level.WARNING, message, e);
                selectable.close();
                key.cancel();
                connectionsWithPendingWrites.remove(selectable);
            }
        }
        if ((readyOps & SelectionKey.OP_READ) != 0) {
            // read (supports both Connection and client connections like SMTP)
            Selectable selectable = (Selectable) key.attachment();
            SocketChannel sc = (SocketChannel) key.channel();
            readBuffer.clear();
            try {
                int len = sc.read(readBuffer);
                if (len == -1) {
                    //selectable.eof();
                    //throw new EOFException();
                } else if (len > 0) {
                    readBuffer.flip();
                    ByteBuffer data = ByteBuffer.allocate(len);
                    data.put(readBuffer);
                    data.flip();
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        Object sa = sc.socket().getRemoteSocketAddress();
                        String message = SelectorLoop.L10N.getString("info.received");
                        message = MessageFormat.format(message, len, sa);
                        LOGGER.finest(message);
                    }
                    selectable.receive(data);
                }
            } catch (IOException e) {
                selectable.receiveFailed(e);
                Object sa = sc.socket().getRemoteSocketAddress();
                String message = L10N.getString("err.read");
                message = MessageFormat.format(message, sa);
                LOGGER.log(Level.WARNING, message, e);
                selectable.close();
                key.cancel();
                
                // Remove from pending writes
                connectionsWithPendingWrites.remove(selectable);
            }
        }
    }

    /**
     * Called by a Selectable to submit a write request.
     * May be called either on the sslMainExecutor thread or on an
     * application connector pool thread, or by client connections.
     */
    public void addWriteRequest(Selectable selectable) {
        connectionsWithPendingWrites.add(selectable);
        selector.wakeup();
    }

    /**
     * Registers a client connection for CONNECT events in the main selector.
     * This allows client-side connections (like SMTPClientConnection) to participate
     * in the server's event loop efficiently.
     * 
     * @param channel the client socket channel
     * @param selectable the client connection object
     * @throws IOException if registration fails
     */
    public void registerClientConnection(SocketChannel channel, Selectable selectable) throws IOException {
        SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
        key.attach(selectable);
        selector.wakeup(); // Wake up selector to process new registration
    }
    
    /**
     * Registers a client connection for READ events in the main selector.
     * Called after successful connection establishment.
     * 
     * @param channel the client socket channel  
     * @param selectable the client connection object
     * @throws IOException if registration fails
     */
    public void registerClientForRead(SocketChannel channel, Selectable selectable) throws IOException {
        SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
        key.attach(selectable);
        selectable.setSelectionKey(key); // Ensure selectable knows its key
        selector.wakeup(); // Wake up selector to process new registration
    }

    private void processIncomingWriteRequests() {
        for (Selectable selectable : connectionsWithPendingWrites) {
            SelectionKey key = selectable.getSelectionKey();
            if (key == null || !key.isValid() || !key.channel().isOpen()) {
                connectionsWithPendingWrites.remove(selectable);
                selectable.getOutboundQueue().clear();
                continue;
            }
            if (selectable.getOutboundQueue().peek() != null && !selectable.hasOpWriteInterest()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                selectable.setHasOpWriteInterest(true);
                selector.wakeup();
            }
        }
    }

    synchronized void close() {
        if (servers == null) {
            return;
        }
        String message = L10N.getString("info.closing_servers");
        LOGGER.info(message);
        try {
            // Clean up
            for (Server server : servers) {
                unregisterServer(server);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        } finally {
            servers = null;
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
        Collection<Server> servers;
        try {
            long t1 = System.currentTimeMillis();
            servers = new ConfigurationParser().parse(gumdroprc);
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

        // Create and start selector loop with parsed connectors
        instance = new SelectorLoop(servers);
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
