/*
 * IntegrationTlsClient.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.X509TrustManager;

/**
 * Sends and receives raw application bytes over gumdrop's in-tree TLS stack.
 * Used by integration tests that previously opened a JSSE {@code SSLSocket}.
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
        run(host, port, trustManager, timeoutMs, new Session() {
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
        run(host, port, trustManager, timeoutMs, new Session() {
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

    private static byte[] run(String host, int port, X509TrustManager trustManager, int timeoutMs, Session session)
            throws Exception {
        TCPTransportFactory factory = new TCPTransportFactory();
        factory.setSecure(true);
        factory.setTrustManager(trustManager != null ? trustManager : new EmptyX509TrustManager());
        factory.start();

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<Exception>();
        ByteArrayOutputStream inbound = new ByteArrayOutputStream();

        ClientEndpoint client = new ClientEndpoint(factory, Gumdrop.getInstance().nextWorkerLoop(), host, port);
        client.connect(new ProtocolHandler() {
            private Endpoint endpoint;

            @Override
            public void connected(Endpoint endpoint) {
                this.endpoint = endpoint;
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
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
                doneLatch.countDown();
            }

            @Override
            public void error(Exception cause) {
                error.set(cause);
                doneLatch.countDown();
            }
        });

        if (!doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            client.close();
            throw new java.io.IOException("TLS session timed out after " + timeoutMs + "ms");
        }
        if (error.get() != null) {
            throw error.get();
        }
        return inbound.toByteArray();
    }

}
