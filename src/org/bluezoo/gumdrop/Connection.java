/*
 * Connection.java
 * Copyright (C) 2005, 2013 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Abstract base class for protocol handlers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Connection {

    private static final Logger LOGGER = Logger.getLogger(Connection.class.getName());

    // getKeySize relies on us parsing the cipher suite string, since it is
    // not available via any API function. Either the key size will be a
    // number in the string or we can infer from one of the ciphers below
    private static final Map<String,Integer> KNOWN_KEY_SIZES = new HashMap<>();
    static {
        KNOWN_KEY_SIZES.put("3DES", Integer.valueOf(168));
        KNOWN_KEY_SIZES.put("CHACHA20", Integer.valueOf(256));
        KNOWN_KEY_SIZES.put("IDEA", Integer.valueOf(128));
    }

    protected Connector connector;
    protected SocketChannel channel;
    protected SelectionKey key;
    ExecutorService threadPool;
    private AtomicBoolean hasOpWriteInterest = new AtomicBoolean(false);
    final BlockingQueue<ByteBuffer> outboundQueue = new LinkedBlockingQueue<>();
    protected int bufferSize;

    protected boolean secure; // TLS applied
    protected X509Certificate[] certificates;
    protected String cipherSuite;
    protected int keySize = -1;
    protected final SSLEngine engine;
    private SSLState sslState; // will be non-null if secure and SSLEngine present

    /**
     * Constructor for an unencrypted connection.
     */
    protected Connection() {
        this(null, false);
    }

    /**
     * Connector for an encrypted connection. Secure must be true and the
     * SSLEngine not null for encryption to occur.
     */
    protected Connection(SSLEngine engine, boolean secure) {
        this.engine = engine;
        this.secure = secure;
    }

    /**
     * Indicates whether this connection is secure.
     */
    public boolean isSecure() {
        return secure;
    }

    boolean hasOpWriteInterest() {
        return hasOpWriteInterest.get();
    }

    void setHasOpWriteInterest(boolean value) {
        hasOpWriteInterest.set(value);
    }

    protected final void init() throws IOException {
        Socket socket = channel.socket();
        socket.setTcpNoDelay(true);
        if (engine == null) {
            bufferSize = Math.max(4096, socket.getReceiveBufferSize());
        } else {
            SSLSession session = engine.getSession();
            sslState = this.new SSLState(session);
        }
    }

    // Will be invoked in server main selector loop thread
    final void receive(ByteBuffer data) {
        if (sslState != null) {
            Server.getInstance().sslMainExecutor.execute(sslState.new SSLReceive(data));
        } else {
            threadPool.submit(this.new ReadRequest(data));
        }
    }

    private final class ReadRequest implements Runnable {

        private final ByteBuffer data;

        ReadRequest(ByteBuffer data) {
            this.data = data;
        }

        // Will be invoked on application (connector) thread pool
        public void run() {
            try {
                int len = data.remaining();
                if (LOGGER.isLoggable(Level.FINEST)) {
                    Object sa = channel.socket().getRemoteSocketAddress();
                    String message = Server.L10N.getString("info.received_plaintext");
                    message = MessageFormat.format(message, len, sa);
                    LOGGER.finest(message);
                }
                received(data);
                data.compact();
            } catch (RuntimeException e) {
                // Ensure this is logged
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    /**
     * Invoked when SSL handshaking has completed.
     *
     * @param protocol the application protocol negotiated
     */
    protected void handshakeComplete(String protocol) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("SSL handshake complete. Negotiated protocol: " + protocol);
        }
    }

    /**
     * If this is a secure connection, return the cipher suite used to
     * encrypt it.
     */
    public String getCipherSuite() {
        return (sslState == null) ? null : sslState.session.getCipherSuite();
    }

    /**
     * If this is a secure connection, return the peer certificates
     * discovered in the TLS handshake.
     */
    public Certificate[] getPeerCertificates() {
        try {
            return (sslState == null) ? null : sslState.session.getPeerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            return null;
        }
    }

    /**
     * If this is a secure connection, return the key size of the cipher
     * suite used to encrypt it.
     */
    public int getKeySize() {
        String cipherSuite = getCipherSuite();
        if (cipherSuite == null) {
            return -1;
        }
        // We have to parse the cipher suite string to determine this
        String[] comps = cipherSuite.split("_");
        for (String comp : comps) {
            try {
                return Integer.parseInt(comp);
            } catch (NumberFormatException e) {
            }
            Integer keySize = KNOWN_KEY_SIZES.get(comp);
            if (keySize != null) {
                return keySize.intValue();
            }
        }
        return -1; // Cannot determine
    }

    /**
     * Invoked when application data is received from the client.
     * The connection takes care of decrypting the network data if this is
     * an encrypted connection.
     * @param buf the application data received from the client
     */
    protected abstract void received(ByteBuffer buf);

    /**
     * Invoked when the connection is opened.
     */
    protected void connected() throws IOException {}

    /**
     * Invoked when the connection failed during connect.
     */
    protected void finishConnectFailed(IOException connectException) throws IOException {}

    /**
     * Invoked when a network read failure occurred.
     */
    protected void receiveFailed(IOException ioException) throws IOException {}

    /**
     * Invoked when the peer closed the connection.
     */
    protected abstract void disconnected() throws IOException;

    /**
     * Sends the specified data to the underlying socket.
     * The buffer should be ready for reading, i.e. its position should be set
     * to zero and its limit set to the index after the last byte to be
     * written. Note that this takes application data - the connection
     * transparently handles encrypting it if this is an encrypted
     * connection.
     * @param buf the application data to send to the client
     */
    public void send(ByteBuffer buf) {
        if (!channel.isOpen()) {
            if (LOGGER.isLoggable(Level.FINE)) {
                String message = Server.L10N.getString("err.channel_closed");
                message = MessageFormat.format(message, channel);
                LOGGER.fine(message);
            }
            return;
        }
        if (sslState != null) {
            try {
                // We cannot write directly to appOut, it can only be
                // accessed in the SSL main thread
                Server.getInstance().sslMainExecutor.execute(sslState.new SSLSend(buf));
            } catch (Exception e) {
                // Handle exceptions from the executor, e.g., RejectedExecutionException
                throw (RuntimeException) new RuntimeException().initCause(e);
            }
        } else {
            try {
                outboundQueue.put(buf); // NB does not use appOut
                Server.getInstance().addWriteRequest(this);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Closes the underlying socket connection.
     */
    public void close() {
        if (sslState != null) {
            Server.getInstance().sslMainExecutor.execute(sslState.sslClose);
        } else {
            doClose();
        }
    }

    private void doClose() {
        try {
            channel.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
        }
        Selector selector = key.selector();
        key.cancel();
        selector.wakeup();
    }

    /**
     * Returns the address of the endpoint this connection is bound to.
     */
    public SocketAddress getLocalSocketAddress() {
        return channel.socket().getLocalSocketAddress();
    }

    /**
     * Returns the address of the endpoint this connection is connected to.
     */
    public SocketAddress getRemoteSocketAddress() {
        return channel.socket().getRemoteSocketAddress();
    }

    private class SSLState {

        final SSLSession session;

        ByteBuffer rawIn;
        ByteBuffer appIn;
        ByteBuffer rawOut;
        ByteBuffer appOut;

        boolean handshakeStarted;

        // Thread control with NEED_TASK
        AtomicInteger delegatedTasks;
        Runnable needTaskRunner;
        Runnable sslResume;
        Runnable sslClose;

        SSLState(SSLSession session) {
            this.session = session;
            int rsize = Math.max(32768, session.getPacketBufferSize());
            rawIn = ByteBuffer.allocate(rsize);
            rawOut = ByteBuffer.allocate(rsize);
            bufferSize = Math.max(32768, session.getApplicationBufferSize());
            appIn = ByteBuffer.allocate(bufferSize);
            appOut = ByteBuffer.allocate(bufferSize);
            appOut.flip(); // empty buffer for handshake
            sslResume = this.new SSLResume();
            sslClose = this.new SSLClose();
            delegatedTasks = new AtomicInteger(0);
        }

        /**
         * Will be invoked on main SSL executor thread.
         */
        void processSSLEvents() throws IOException {
            if (delegatedTasks.get() > 0) { // it will be 0 when the last task has been completed
                return;
            }
            if (!handshakeStarted) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    Object sa = channel.socket().getRemoteSocketAddress();
                    String message = Server.L10N.getString("info.ssl_begin_handshake");
                    message = MessageFormat.format(message, sa);
                    LOGGER.finest(message);
                }
                engine.beginHandshake();
                handshakeStarted = true;
            }
            SSLEngineResult.HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
            switch (handshakeStatus) {
                case FINISHED:
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        Object sa = channel.socket().getRemoteSocketAddress();
                        String message = Server.L10N.getString("info.ssl_handshake_finished");
                        message = MessageFormat.format(message, sa);
                        LOGGER.finest(message);
                    }
                    handshakeComplete(engine.getApplicationProtocol());
                    break;
                case NOT_HANDSHAKING:
                    handleApplicationData(handshakeStatus);
                    break;
                default: // handshaking
                    handleHandshake(handshakeStatus);
            }
        }

        /**
         * We are handshaking.
         */
        private void handleHandshake(SSLEngineResult.HandshakeStatus handshakeStatus) throws IOException {
            boolean loop = false;
            SSLEngineResult result;
            do {
                loop = false;
                switch (handshakeStatus) {
                    case NEED_WRAP:
                        rawOut.clear();
                        //System.err.println("appOut contains:\n"+Server.hexdump(appOut));
                        result = engine.wrap(appOut, rawOut);
                        if (rawOut.position() > 0) {
                            ByteBuffer readRawOut = rawOut.duplicate();
                            readRawOut.flip();
                        }
                        switch (result.getStatus()) {
                            case OK:
                                if (rawOut.position() > 0) {
                                    rawOut.flip();
                                    sendRawOut();
                                }
                                break;
                            case BUFFER_UNDERFLOW:
                                // This case should ideally not happen during handshake wraps if appOut is empty,
                                // as the engine should be able to produce handshake messages.
                                // If it does, it might mean the engine needs more internal state or a task.
                                // For now, treat it as needing to wait.
                                break;
                            case BUFFER_OVERFLOW: // extend rawOut and wrap again
                                rawOut = ByteBuffer.allocate(rawOut.capacity() + 4096);
                                loop = true; // retry
                                break;
                            case CLOSED:
                                handleClosed();
                                return;
                        }
                        break;
                    case NEED_UNWRAP:
                        result = engine.unwrap(rawIn, appIn);
                        switch (result.getStatus()) {
                            case OK:
                                break;
                            case BUFFER_UNDERFLOW:
                                // Need more raw data from the network to complete the current TLS record.
                                //System.err.println("handleHandshake termination due to BUFFER_UNDERFLOW");
                                return;
                            case BUFFER_OVERFLOW: // extend appIn and unwrap again
                                ByteBuffer b = ByteBuffer.allocate(appIn.capacity() + 4096);
                                appIn.flip();
                                b.put(appIn);
                                appIn = b;
                                loop = true; // retry
                                break;
                            case CLOSED:
                                handleClosed();
                                return;
                        }
                        break;
                    case NEED_TASK:
                        // First count the tasks to do
                        Collection<Runnable> tasks = new ArrayList<>();
                        for (Runnable task = engine.getDelegatedTask(); task != null; task = engine.getDelegatedTask()) {
                            tasks.add(task);
                        }
                        delegatedTasks.set(tasks.size());
                        // Now submit them to SSL task thread
                        for (Runnable task : tasks) {
                            Server.getInstance().sslTaskExecutor.execute(this.new DelegatedTask(task));
                        }
                        return; // processSSLEvents will be invoked by sslResume
                }
                handshakeStatus = engine.getHandshakeStatus();
                // If handshake is NOT_HANDSHAKING or FINISHED, we are done with handshake
                // Otherwise, we need to loop
                switch (handshakeStatus) {
                    case FINISHED:
                    case NOT_HANDSHAKING:
                        break;
                    default:
                        loop = true;
                }
            } while (loop);
        }

        void sendRawOut() {
            try {
                ByteBuffer data = ByteBuffer.allocate(rawOut.remaining());
                data.put(rawOut);
                data.flip();
                outboundQueue.put(data);
                Server.getInstance().addWriteRequest(Connection.this);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Process application data to be wrapped and client data to be
         * unwrapped.
         */
        private void handleApplicationData(SSLEngineResult.HandshakeStatus handshakeStatus) throws IOException {
            SSLEngineResult result;
            // wrap pending outbound data in appOut
            boolean loop = appOut.hasRemaining();
            while (loop) {
                // wrap
                loop = false;
                rawOut.clear();
                result = engine.wrap(appOut, rawOut);
                switch (result.getStatus()) {
                    case OK:
                        if (rawOut.position() > 0) { // output was produced
                            rawOut.flip();
                            sendRawOut();
                            rawOut.clear();
                        }
                        loop = appOut.hasRemaining();
                        break;
                    case BUFFER_UNDERFLOW:
                        // XXX what should happen here?
                        break;
                    case BUFFER_OVERFLOW: // extend rawOut and wrap again
                        ByteBuffer b = ByteBuffer.allocate(rawOut.capacity() + 4096);
                        rawOut.flip();
                        b.put(rawOut);
                        rawOut = b;
                        loop = appOut.hasRemaining();
                        break;
                    case CLOSED:
                        handleClosed();
                        return;
                }
            }
            // unwrap pending inbound data in rawIn
            loop = rawIn.hasRemaining();
            while (loop) {
                // unwrap
                loop = false;
                result = engine.unwrap(rawIn, appIn);
                switch (result.getStatus()) {
                    case OK:
                        if (appIn.position() > 0) {
                            // Prepare appIn for reading
                            appIn.flip();
                            // Create a copy of the application data to pass as appIn is shared
                            ByteBuffer data = ByteBuffer.allocate(appIn.remaining());
                            data.put(appIn);
                            // Prepare the copy for reading
                            data.flip();
                            // Submit the copy to received(ByteBuffer)
                            threadPool.submit(Connection.this.new ReadRequest(data));
                            appIn.compact(); // Clear appIn for the next unwrap operation
                        }
                        if (rawIn.hasRemaining()) {
                            loop = true; // Stay in loop to unwrap more records
                        }
                        break;
                    case BUFFER_UNDERFLOW:
                        return;
                    case BUFFER_OVERFLOW: // extend appIn and unwrap again
                        appIn = ByteBuffer.allocate(appIn.capacity() + 4096);
                        loop = true;
                        break;
                    case CLOSED:
                        handleClosed();
                        return;
                }
            }
        }

        void handleClosed() throws IOException {
            if (LOGGER.isLoggable(Level.WARNING)) {
                String message = Server.L10N.getString("warn.sslengine_closed_in_read");
                LOGGER.warning(message);
            }
            channel.close();
            disconnected();
        }

        /**
         * Executed on task executor.
         */
        private class DelegatedTask implements Runnable {

            private final Runnable task;

            DelegatedTask(Runnable task) {
                this.task = task;
            }

            public void run() {
                task.run();
                if (delegatedTasks.decrementAndGet() == 0) {
                    Server.getInstance().sslMainExecutor.execute(sslResume);
                }
            }
        }

        /**
         * Executed on main executor.
         */
        private class SSLResume implements Runnable {

            public void run() {
                try {
                    processSSLEvents();
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            }

        }

        /**
         * Executed on main executor. New network data received.
         * Data must be flipped and ready for reading.
         */
        private class SSLReceive implements Runnable {

            private final ByteBuffer data;

            SSLReceive(ByteBuffer data) {
                this.data = data;
            }

            public void run() {
                try {
                    System.err.println("Receiving bytes from client into rawIn:\n"+Server.hexdump(data));
                    if (rawIn.position() > 0) {
                        rawIn.compact(); // prepare for write
                    }
                    if (rawIn.remaining() < data.remaining()) {
                        ByteBuffer tmp = ByteBuffer.allocate(rawIn.capacity() + Math.max(4096, data.remaining()));
                        rawIn.flip();
                        tmp.put(rawIn);
                        rawIn = tmp;
                    }
                    rawIn.put(data);
                    rawIn.flip(); // prepare for read
                    processSSLEvents();
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        }

        /**
         * Executed on main executor. Application data to send.
         * Data must be flipped and ready for reading.
         */
        private class SSLSend implements Runnable {

            private final ByteBuffer data;

            SSLSend(ByteBuffer data) {
                this.data = data;
            }

            public void run() {
                try {
                    if (appOut.position() > 0) {
                        appOut.compact(); // prepare for write
                    }
                    if (appOut.remaining() < data.remaining()) {
                        ByteBuffer tmp = ByteBuffer.allocate(appOut.capacity() + Math.max(4096, data.remaining()));
                        appOut.flip(); // prepare for read
                        tmp.put(appOut);
                        appOut = tmp;
                    }
                    appOut.put(data);
                    appOut.flip(); // prepare for read
                    processSSLEvents();
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        }

        /**
         * Executed on main executor. Closes socket.
         */
        private class SSLClose implements Runnable {
            public void run() {
                try {
                    processSSLEvents();
                    doClose();
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        }

    }

    // -- Debugging --

    void debugResult(String name, SSLEngineResult result) {
        System.err.println(name + ": SSLEngineResult: status=" + result.getStatus() +
                ", handshakeStatus=" + result.getHandshakeStatus() +
                ", bytesConsumed=" + result.bytesConsumed() +
                ", bytesProduced=" + result.bytesProduced());
    }

    protected static String toString(ByteBuffer buf) {
        return buf.getClass().getName()+"[pos="+buf.position()+",limit="+buf.limit()+",capacity="+buf.capacity()+"]";
    }

    protected static String toASCIIString(ByteBuffer data) {
        int pos = data.position();
        StringBuilder s = new StringBuilder();
        while (data.hasRemaining()) {
            int c = data.get() & 0xff;
            if (c == '\r') {
                s.append("<CR>");
            } else if (c == '\n') {
                s.append("<LF>");
            } else if (c == '\t') {
                s.append("<HT>");
            } else if (c >= 32 && c < 127) {
                s.append((char) c);
            } else {
                s.append('.');
            }
        }
        data.position(pos);
        return s.toString();
    }

}
