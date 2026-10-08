/*
 * MockDataConnector.java
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

package org.bluezoo.gumdrop.ftp.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * Hand-written mock of the FTP client's data-connection seam: opens no
 * socket and registers nothing. A passive connect is recorded and the test
 * completes it by calling the handler with a {@link MockDataEndpoint}; an
 * active-mode channel the server "connects" with is adopted straight onto a
 * fresh {@link MockDataEndpoint}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MockDataConnector
        implements FtpClientDataConnectionCoordinator.DataConnector {

    /** A data endpoint whose output the test reads and whose handler it drives. */
    static final class MockDataEndpoint implements Endpoint {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean open = true;
        boolean secure;
        int idleCloseRequests;

        byte[] sentBytes() {
            return sent.toByteArray();
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
        public void closeWhenOutboundIdle() {
            idleCloseRequests++;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 40001);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 20);
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
        }

        @Override
        public void resumeRead() {
        }

        @Override
        public void onWriteReady(Runnable callback) {
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            return null;
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
            return null;
        }
    }

    /** A recorded passive connect. */
    static final class Connect {
        final InetSocketAddress address;
        final ProtocolHandler handler;
        final TcpTransportFactory factory;

        Connect(InetSocketAddress address, ProtocolHandler handler,
                TcpTransportFactory factory) {
            this.address = address;
            this.handler = handler;
            this.factory = factory;
        }
    }

    final List<Connect> connects = new ArrayList<Connect>();
    final List<MockDataEndpoint> adopted = new ArrayList<MockDataEndpoint>();
    final List<ProtocolHandler> adoptedHandlers = new ArrayList<ProtocolHandler>();
    private IOException connectFailure;
    private IOException adoptFailure;

    void failConnect(IOException e) {
        connectFailure = e;
    }

    void failAdopt(IOException e) {
        adoptFailure = e;
    }

    Connect lastConnect() {
        return connects.get(connects.size() - 1);
    }

    ProtocolHandler lastAdoptedHandler() {
        return adoptedHandlers.get(adoptedHandlers.size() - 1);
    }

    MockDataEndpoint lastAdopted() {
        return adopted.get(adopted.size() - 1);
    }

    @Override
    public void connect(Gumdrop gumdrop, Endpoint control,
            InetSocketAddress address, ProtocolHandler handler,
            TcpTransportFactory factory) throws IOException {
        if (connectFailure != null) {
            throw connectFailure;
        }
        connects.add(new Connect(address, handler, factory));
    }

    @Override
    public void adopt(Endpoint control, SocketChannel channel,
            ProtocolHandler handler) throws IOException {
        if (adoptFailure != null) {
            throw adoptFailure;
        }
        MockDataEndpoint ep = new MockDataEndpoint();
        adopted.add(ep);
        adoptedHandlers.add(handler);
        handler.connected(ep);
    }
}
