/*
 * IntegrationTlsClient.java
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

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.X509TrustManager;

/**
 * Sends and receives raw application bytes over gumdrop's in-tree TLS stack.
 * Used by integration tests that previously opened a JSSE {@code SSLSocket}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class IntegrationTlsClient {

    private IntegrationTlsClient() {
    }

    /**
     * Handshakes, sends {@code outbound}, and returns all application data
     * received before the connection ends.
     */
    public static byte[] exchange(String host, int port, byte[] outbound, X509TrustManager trustManager,
            int timeoutMs) throws Exception {
        return run(host, port, trustManager, timeoutMs, new Session() {
            @Override
            public void onReady(Endpoint endpoint) throws Exception {
                endpoint.send(ByteBuffer.wrap(outbound));
            }

            @Override
            public boolean closeAfterReady() {
                return false;
            }

            @Override
            public boolean isComplete(ByteArrayOutputStream inbound) {
                return false;
            }
        });
    }

    /**
     * Like {@link #exchange} but closes once {@code complete} returns true on
     * the accumulated inbound bytes (for HTTP responses with Content-Length).
     */
    public static byte[] exchangeWhenComplete(String host, int port, byte[] outbound,
            X509TrustManager trustManager, int timeoutMs, ResponseComplete complete) throws Exception {
        return run(host, port, trustManager, timeoutMs, new Session() {
            @Override
            public void onReady(Endpoint endpoint) throws Exception {
                endpoint.send(ByteBuffer.wrap(outbound));
            }

            @Override
            public boolean closeAfterReady() {
                return false;
            }

            @Override
            public boolean isComplete(ByteArrayOutputStream inbound) {
                return complete.isComplete(inbound.toByteArray());
            }
        });
    }

    /**
     * Handshakes, sends {@code outbound}, closes immediately, and waits for the
     * server to finish processing (used by buffer-layer tests).
     */
    public static void sendAndClose(String host, int port, byte[] outbound, X509TrustManager trustManager,
            int timeoutMs) throws Exception {
        sendAndClose(null, host, port, outbound, trustManager, timeoutMs);
    }

    /**
     * Like {@link #sendAndClose(String, int, byte[], X509TrustManager, int)} but
     * uses an existing {@link Gumdrop} runtime (e.g. the test server's instance)
     * instead of booting a separate one.
     */
    public static void sendAndClose(Gumdrop runtime, String host, int port, byte[] outbound,
            X509TrustManager trustManager, int timeoutMs) throws Exception {
        run(runtime, host, port, trustManager, timeoutMs, new Session() {
            @Override
            public void onReady(Endpoint endpoint) throws Exception {
                endpoint.send(ByteBuffer.wrap(outbound));
            }

            @Override
            public boolean closeAfterReady() {
                return true;
            }

            @Override
            public boolean isComplete(ByteArrayOutputStream inbound) {
                return false;
            }
        });
    }

    /**
     * Handshakes and runs {@code session} on the connected endpoint once TLS is up.
     */
    public static void withConnectedEndpoint(String host, int port, X509TrustManager trustManager,
            int timeoutMs, ConnectedSession session) throws Exception {
        withConnectedEndpoint(null, host, port, trustManager, timeoutMs, session);
    }

    /**
     * Like {@link #withConnectedEndpoint(String, int, X509TrustManager, int, ConnectedSession)}
     * but uses an existing {@link Gumdrop} runtime.
     */
    public static void withConnectedEndpoint(Gumdrop runtime, String host, int port,
            X509TrustManager trustManager, int timeoutMs, ConnectedSession session) throws Exception {
        run(runtime, host, port, trustManager, timeoutMs, new Session() {
            @Override
            public void onReady(Endpoint endpoint) throws Exception {
                session.run(endpoint);
            }

            @Override
            public boolean closeAfterReady() {
                return false;
            }

            @Override
            public boolean isComplete(ByteArrayOutputStream inbound) {
                return false;
            }
        });
    }

    public interface ResponseComplete {
        boolean isComplete(byte[] inbound);
    }

    public interface ConnectedSession {
        void run(Endpoint endpoint) throws Exception;
    }

    private interface Session {
        void onReady(Endpoint endpoint) throws Exception;

        boolean closeAfterReady();

        boolean isComplete(ByteArrayOutputStream inbound);
    }

    private static byte[] run(Gumdrop runtime, String host, int port, X509TrustManager trustManager,
            int timeoutMs, Session session) throws Exception {
        TcpTransportFactory factory = new TcpTransportFactory();
        factory.setSecure(true);
        factory.setApplicationProtocols("http/1.1");
        factory.setTrustManager(trustManager != null ? trustManager : new EmptyX509TrustManager());
        factory.start();

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<Exception>();
        java.util.concurrent.atomic.AtomicBoolean tlsEstablished = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<Endpoint> activeEndpoint = new AtomicReference<Endpoint>();
        ByteArrayOutputStream inbound = new ByteArrayOutputStream();

        boolean ownsRuntime = (runtime == null);
        Gumdrop gumdrop = ownsRuntime
                ? Gumdrop.boot(GumdropConfig.create().drainTimeoutMs(0))
                : runtime;
        ClientEndpoint client = new ClientEndpoint(factory, gumdrop.nextWorkerLoop(),
                InetAddress.getByName(host), port);
        try {
        client.connect(gumdrop, new ProtocolHandler() {
            private Endpoint endpoint;

            @Override
            public void connected(Endpoint endpoint) {
                this.endpoint = endpoint;
                activeEndpoint.set(endpoint);
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
                tlsEstablished.set(true);
                try {
                    session.onReady(endpoint);
                    if (session.closeAfterReady()) {
                        endpoint.close();
                    }
                } catch (Exception e) {
                    error.set(e);
                    doneLatch.countDown();
                }
            }

            @Override
            public void receive(ByteBuffer data) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                inbound.write(chunk, 0, chunk.length);
                if (session.isComplete(inbound) && endpoint != null) {
                    endpoint.close();
                }
            }

            @Override
            public void disconnected() {
                if (!tlsEstablished.get() && error.get() == null) {
                    error.set(new java.io.IOException(
                            "Connection closed before TLS handshake completed"));
                }
                doneLatch.countDown();
            }

            @Override
            public void error(Exception cause) {
                error.set(cause);
                doneLatch.countDown();
            }
        });

        if (!doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Endpoint open = activeEndpoint.get();
            if (open != null) {
                open.close();
            }
            client.close();
            throw new java.io.IOException("TLS session timed out after " + timeoutMs + "ms");
        }
        if (error.get() != null) {
            throw error.get();
        }
        return inbound.toByteArray();
        } finally {
            client.close();
            if (ownsRuntime) {
                gumdrop.shutdown();
            }
        }
    }

    private static byte[] run(String host, int port, X509TrustManager trustManager, int timeoutMs,
            Session session) throws Exception {
        return run(null, host, port, trustManager, timeoutMs, session);
    }

}
