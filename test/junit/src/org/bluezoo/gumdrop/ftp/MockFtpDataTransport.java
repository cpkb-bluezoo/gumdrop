/*
 * MockFtpDataTransport.java
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

package org.bluezoo.gumdrop.ftp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SocketChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.AcceptSelectorLoop;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.util.AsyncFile;

/**
 * Hand-written mock of the FTP data-transfer seam: opens no socket and
 * registers nothing. A passive listener is a recorded port whose acceptor the
 * test calls to deliver a data connection; an active connect is a recorded
 * request the test completes or fails; each data endpoint is a
 * {@link MockDataEndpoint} the test feeds and drains; files are opened on
 * the path's own (in-memory) file system, optionally with failing reads or
 * writes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MockFtpDataTransport implements FtpDataTransport {

    /** A data endpoint whose handler the test drives and whose output it reads. */
    static final class MockDataEndpoint implements Endpoint {
        final ProtocolHandler handler;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean open = true;
        boolean secure;
        int pauseCount;
        int resumeCount;
        Runnable writeReady;
        final List<Runnable> deferred = new ArrayList<Runnable>();
        boolean deferExecute;
        private final SelectorLoop loop;

        MockDataEndpoint(ProtocolHandler handler, SelectorLoop loop) {
            this.handler = handler;
            this.loop = loop;
        }

        /** Everything the transfer wrote to the data connection. */
        byte[] sentBytes() {
            return sent.toByteArray();
        }

        /** Runs the pending write-ready callback, if any; true if one ran. */
        boolean fireWriteReady() {
            Runnable r = writeReady;
            writeReady = null;
            if (r == null) {
                return false;
            }
            r.run();
            return true;
        }

        /** Runs tasks queued while {@code deferExecute} was set. */
        void runDeferred() {
            while (!deferred.isEmpty()) {
                Runnable r = deferred.remove(0);
                r.run();
            }
        }

        @Override
        public void send(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            sent.write(b, 0, b.length);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean isClosing() {
            return !open;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 20);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 40001);
        }

        @Override
        public boolean isSecure() {
            return secure;
        }

        @Override
        public SecurityInfo getSecurityInfo() {
            return null;
        }

        @Override
        public void startTLS() {
        }

        @Override
        public void pauseRead() {
            pauseCount++;
        }

        @Override
        public void resumeRead() {
            resumeCount++;
        }

        @Override
        public void onWriteReady(Runnable callback) {
            writeReady = callback;
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return loop;
        }

        @Override
        public void execute(Runnable task) {
            if (deferExecute) {
                deferred.add(task);
            } else {
                task.run();
            }
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            return new TimerHandle() {
                @Override
                public void cancel() {
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            };
        }

        @Override
        public Trace getTrace() {
            return null;
        }

        @Override
        public void setTrace(Trace trace) {
        }

        @Override
        public TelemetryConfig getTelemetryConfig() {
            return org.bluezoo.gumdrop.testsupport.StubTelemetry.CONFIG;
        }
    }

    /** A recorded passive listener. */
    static final class MockListener implements PassiveListener {
        final int port;
        final int requestedPort;
        final AcceptSelectorLoop.RawAcceptHandler acceptor;
        boolean closed;

        MockListener(int port, int requestedPort,
                AcceptSelectorLoop.RawAcceptHandler acceptor) {
            this.port = port;
            this.requestedPort = requestedPort;
            this.acceptor = acceptor;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public void close() {
            closed = true;
        }

        /** Delivers a data connection as the accept loop would. */
        void accept(SocketChannel channel) throws IOException {
            acceptor.accepted(channel);
        }
    }

    /** A recorded active connect. */
    static final class MockConnect implements ActiveConnection {
        final InetAddress address;
        final int port;
        final ActiveConnectCallback callback;
        boolean closed;

        MockConnect(InetAddress address, int port, ActiveConnectCallback callback) {
            this.address = address;
            this.port = port;
            this.callback = callback;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final SelectorLoop loop;
    final List<MockListener> listeners = new ArrayList<MockListener>();
    final List<MockConnect> connects = new ArrayList<MockConnect>();
    final List<MockDataEndpoint> endpoints = new ArrayList<MockDataEndpoint>();
    final List<Boolean> secureRequests = new ArrayList<Boolean>();
    final List<Path> openedPaths = new ArrayList<Path>();
    private IOException listenFailure;
    private IOException connectFailure;
    private IOException openFailure;
    private boolean secureEndpoints;
    private boolean failReads;
    private boolean failWrites;

    MockFtpDataTransport(SelectorLoop loop) {
        this.loop = loop;
    }

    void failListen(IOException e) {
        listenFailure = e;
    }

    void failConnect(IOException e) {
        connectFailure = e;
    }

    void failOpen(IOException e) {
        openFailure = e;
    }

    /** Makes endpoints report secure, as an established PROT P connection. */
    void setSecureEndpoints(boolean secure) {
        secureEndpoints = secure;
    }

    /** Makes every channel opened from now on fail its reads. */
    void failReads(boolean fail) {
        failReads = fail;
    }

    /** Makes every channel opened from now on fail its writes. */
    void failWrites(boolean fail) {
        failWrites = fail;
    }

    MockListener lastListener() {
        return listeners.get(listeners.size() - 1);
    }

    MockConnect lastConnect() {
        return connects.get(connects.size() - 1);
    }

    MockDataEndpoint lastEndpoint() {
        return endpoints.get(endpoints.size() - 1);
    }

    @Override
    public PassiveListener listenPassive(FtpListener server, int port,
            AcceptSelectorLoop.RawAcceptHandler acceptor) throws IOException {
        if (listenFailure != null) {
            throw listenFailure;
        }
        MockListener l = new MockListener(
                port != 0 ? port : 40000 + listeners.size(), port, acceptor);
        listeners.add(l);
        return l;
    }

    @Override
    public ActiveConnection connectActive(Gumdrop gumdrop, InetAddress address,
            int port, SelectorLoop connectLoop, ActiveConnectCallback callback)
            throws IOException {
        if (connectFailure != null) {
            throw connectFailure;
        }
        MockConnect c = new MockConnect(address, port, callback);
        connects.add(c);
        return c;
    }

    @Override
    public Endpoint createDataEndpoint(SocketChannel channel,
            ProtocolHandler handler, TcpTransportFactory secureFactory)
            throws IOException {
        secureRequests.add(Boolean.valueOf(secureFactory != null));
        MockDataEndpoint e = new MockDataEndpoint(handler, loop);
        e.secure = secureEndpoints;
        endpoints.add(e);
        return e;
    }

    @Override
    public void registerDataEndpoint(SelectorLoop registerLoop,
            SocketChannel channel, Endpoint endpoint) {
        // nothing to register: the test drives the handler directly
    }

    @Override
    public AsyncFile openFile(StorageExecutor storage, Path path,
            OpenOption... options) throws IOException {
        if (openFailure != null) {
            throw openFailure;
        }
        openedPaths.add(path);
        AsyncFile file = AsyncFile.open(null, path, options);
        if (failReads || failWrites) {
            return new FailingFile(file, failReads, failWrites);
        }
        return file;
    }

    /** An {@link AsyncFile} whose reads and/or writes fail. */
    private static final class FailingFile implements AsyncFile {
        private final AsyncFile delegate;
        private final boolean failReads;
        private final boolean failWrites;

        FailingFile(AsyncFile delegate, boolean failReads, boolean failWrites) {
            this.delegate = delegate;
            this.failReads = failReads;
            this.failWrites = failWrites;
        }

        @Override
        public <A> void read(ByteBuffer dst, long position, A attachment,
                CompletionHandler<Integer, ? super A> handler) {
            if (failReads) {
                handler.failed(new IOException("mock read failure"), attachment);
                return;
            }
            delegate.read(dst, position, attachment, handler);
        }

        @Override
        public <A> void write(ByteBuffer src, long position, A attachment,
                CompletionHandler<Integer, ? super A> handler) {
            if (failWrites) {
                handler.failed(new IOException("mock write failure"), attachment);
                return;
            }
            delegate.write(src, position, attachment, handler);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
