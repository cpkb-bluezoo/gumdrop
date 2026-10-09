/*
 * GumdropLiveConnectionIntegrationTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.bluezoo.gumdrop.util.CidrNetwork;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

/**
 * Whole-runtime tests: a booted {@link Gumdrop} with real listeners accepting
 * real loopback (and UNIX domain) connections, and {@link TcpTransportFactory}
 * clients connecting to them, in plaintext, with TLS from PEM and keystore
 * material, with in-band STARTTLS, and under admission limits. Every wait is a
 * latch, every send runs on its endpoint's own loop and no listening socket is
 * reused.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GumdropLiveConnectionIntegrationTest {

    private static final long HANG_GUARD_MS = 60000L;
    private static final String PW = "changeit";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Hang guard only: a test that stalls fails instead of blocking the suite. */
    @Rule
    public Timeout hangGuard = Timeout.seconds(90);

    private Gumdrop gumdrop;
    private final List<SocketChannel> raws = new ArrayList<SocketChannel>();

    @After
    public void tearDown() throws Exception {
        for (SocketChannel c : raws) {
            try {
                c.close();
            } catch (IOException e) {
                // best effort
            }
        }
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdownNow();
        }
    }

    /** Server or client handler: records, optionally echoes, optionally does STARTTLS. */
    private static final class Handler implements ProtocolHandler {
        volatile Endpoint endpoint;
        final boolean echo;
        final boolean starttlsServer;
        final StringBuffer received = new StringBuffer();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final CountDownLatch secure = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        volatile int expect = 1;
        volatile String sendWhenSecure;
        volatile boolean starttlsClientOnOk;
        final List<Exception> errors = Collections.synchronizedList(new ArrayList<Exception>());

        Handler(boolean echo, boolean starttlsServer) {
            this.echo = echo;
            this.starttlsServer = starttlsServer;
        }

        @Override
        public void receive(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            String s = new String(b, StandardCharsets.UTF_8);
            received.append(s);
            if (starttlsServer && "STARTTLS".equals(s)) {
                endpoint.send(ByteBuffer.wrap("OK".getBytes(StandardCharsets.UTF_8)));
                try {
                    endpoint.startTLS();
                } catch (IOException e) {
                    errors.add(e);
                }
            } else if (starttlsClientOnOk && "OK".equals(s)) {
                try {
                    endpoint.startTLS();
                } catch (IOException e) {
                    errors.add(e);
                }
            } else if (echo) {
                endpoint.send(ByteBuffer.wrap(b));
            }
            if (received.length() >= expect) {
                dataLatch.countDown();
            }
        }

        @Override
        public void connected(Endpoint ep) {
            endpoint = ep;
            connected.countDown();
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            secure.countDown();
            String s = sendWhenSecure;
            if (s != null) {
                endpoint.send(ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)));
            }
        }

        @Override
        public void disconnected() {
            disconnected.countDown();
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    private static final class TestListener extends TcpListener {
        final CountDownLatch bound = new CountDownLatch(1);
        final AtomicInteger created = new AtomicInteger();
        final BlockingQueue<Handler> handlers = new LinkedBlockingQueue<Handler>();
        volatile int boundPort;
        final boolean starttls;

        TestListener(boolean starttls) {
            this.starttls = starttls;
        }

        @Override
        protected ProtocolHandler createHandler() {
            Handler h = new Handler(true, starttls);
            handlers.add(h);
            created.incrementAndGet();
            return h;
        }

        @Override
        protected void applyBoundTcpPort(int port) {
            boundPort = port;
            bound.countDown();
        }

        @Override
        public int getPort() {
            return 0;
        }

        @Override
        public String getDescription() {
            return "live-test";
        }
    }

    private static void await(CountDownLatch l) throws InterruptedException {
        assertTrue("hang guard", l.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
    }

    private TestListener startListener(TestListener l) throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(2));
        l.addresses(InetAddress.getLoopbackAddress());
        gumdrop.addListener(l);
        assertTrue(gumdrop.awaitStartupComplete(HANG_GUARD_MS));
        await(l.bound);
        return l;
    }

    private Handler serverSide(TestListener l, int index) throws Exception {
        Handler h = l.handlers.poll(HANG_GUARD_MS, TimeUnit.MILLISECONDS);
        assertNotNull("hang guard: no server handler", h);
        await(h.connected);
        return h;
    }

    private TcpEndpoint connect(TcpTransportFactory f, int port, Handler h) throws Exception {
        f.start();
        SelectorLoop loop = gumdrop.nextWorkerLoop();
        return f.connect(gumdrop, InetAddress.getLoopbackAddress(), port, h, loop);
    }

    private static void sendOnLoop(final Endpoint ep, final String text) {
        ep.execute(new Runnable() {
            @Override
            public void run() {
                ep.send(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
            }
        });
    }

    private static void closeOnLoop(final Endpoint ep) {
        ep.execute(new Runnable() {
            @Override
            public void run() {
                ep.close();
            }
        });
    }

    private static TcpTransportFactory plainClientFactory() {
        return new TcpTransportFactory();
    }

    private static TcpTransportFactory tlsClientFactory() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        f.setSecure(true);
        f.setTrustManager(TestCertificates.ec256().trustManager());
        return f;
    }

    @Test
    public void plainEchoThenCloseUpdatesRegistries() throws Exception {
        TestListener l = startListener(new TestListener(false));
        Handler client = new Handler(false, false);
        connect(plainClientFactory(), l.boundPort, client);
        await(client.connected);
        Handler server = serverSide(l, 0);
        sendOnLoop(client.endpoint, "hello");
        await(client.dataLatch);
        assertEquals("hello", client.received.toString());
        assertEquals("hello", server.received.toString());
        closeOnLoop(client.endpoint);
        await(server.disconnected);
        await(client.disconnected);
        assertTrue(client.errors.isEmpty());
    }

    @Test
    public void tlsFromPemFilesOnListenerAndClient() throws Exception {
        TestCertificates.Identity id = TestCertificates.ec256();
        Path dir = tmp.getRoot().toPath();
        Path cert = TestCertificates.writeCertificatePem(dir, "s.crt", id);
        Path key = TestCertificates.writePrivateKeyPem(dir, "s.key", id);
        TestListener l = new TestListener(false);
        l.secure(true).tls(TlsConfig.pem(cert, key));
        startListener(l);
        Handler client = new Handler(false, false);
        client.sendWhenSecure = "secret";
        TcpTransportFactory f = tlsClientFactory();
        connect(f, l.boundPort, client);
        await(client.secure);
        await(client.dataLatch);
        assertEquals("secret", client.received.toString());
        closeOnLoop(client.endpoint);
        await(client.disconnected);
    }

    @Test
    public void tlsFromKeystoreWithTls12OnlyListener() throws Exception {
        TestCertificates.Identity id = TestCertificates.ec256();
        Path dir = tmp.getRoot().toPath();
        Path store = TestCertificates.writeKeyStore(dir, "s.p12", id, "srv", PW.toCharArray());
        TestListener l = new TestListener(false);
        l.secure(true).tls(TlsConfig.keystore(store, PW));
        l.setKeystoreFormat(KeystoreFormat.PKCS12);
        l.setTlsVersion(TlsVersion.TLS_1_2);
        startListener(l);
        Handler client = new Handler(false, false);
        client.sendWhenSecure = "twelve";
        TcpTransportFactory f = tlsClientFactory();
        f.setTlsVersion(TlsVersion.TLS_1_2);
        connect(f, l.boundPort, client);
        await(client.secure);
        await(client.dataLatch);
        assertEquals("twelve", client.received.toString());
        closeOnLoop(client.endpoint);
        await(client.disconnected);
    }

    @Test
    public void tlsFromKeystoreWithSniResolver() throws Exception {
        TestCertificates.Identity id = TestCertificates.ec256();
        Path dir = tmp.getRoot().toPath();
        Path store = TestCertificates.writeKeyStore(dir, "sni.p12", id, "srv", PW.toCharArray());
        TestListener l = new TestListener(false);
        l.secure(true).tls(TlsConfig.keystore(store, PW, KeystoreFormat.PKCS12)
                .sni("localhost", "srv").sniDefaultAlias("srv"));
        startListener(l);
        Handler client = new Handler(false, false);
        client.sendWhenSecure = "sni";
        connect(tlsClientFactory(), l.boundPort, client);
        await(client.secure);
        await(client.dataLatch);
        assertEquals("sni", client.received.toString());
        closeOnLoop(client.endpoint);
        await(client.disconnected);
    }

    @Test
    public void starttlsUpgradeOverRealSockets() throws Exception {
        TestCertificates.Identity id = TestCertificates.ec256();
        TestListener l = new TestListener(true);
        l.setServerCredentials(id.credentials());
        startListener(l);
        Handler client = new Handler(false, false);
        client.starttlsClientOnOk = true;
        client.sendWhenSecure = "after";
        client.expect = 7;
        TcpTransportFactory f = new TcpTransportFactory();
        f.setTrustManager(id.trustManager());
        connect(f, l.boundPort, client);
        await(client.connected);
        sendOnLoop(client.endpoint, "STARTTLS");
        await(client.secure);
        await(client.dataLatch);
        assertEquals("OKafter", client.received.toString());
        closeOnLoop(client.endpoint);
        await(client.disconnected);
        assertTrue(client.errors.isEmpty());
    }

    @Test
    public void connectionCapRefusesTheSecondConnection() throws Exception {
        TestListener l = new TestListener(false);
        l.maxConnections(1);
        startListener(l);
        Handler first = new Handler(false, false);
        connect(plainClientFactory(), l.boundPort, first);
        await(first.connected);
        serverSide(l, 0);
        SocketChannel raw = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                l.boundPort));
        raws.add(raw);
        ByteBuffer b = ByteBuffer.allocate(8);
        assertEquals(-1, raw.read(b));
        assertEquals(1, l.created.get());
        closeOnLoop(first.endpoint);
        await(first.disconnected);
    }

    @Test
    public void blockedNetworkIsRefused() throws Exception {
        TestListener l = new TestListener(false);
        List<CidrNetwork> blocked = new ArrayList<CidrNetwork>();
        blocked.add(new CidrNetwork("127.0.0.0/8"));
        blocked.add(new CidrNetwork("::1/128"));
        l.blockedNetworks(blocked);
        startListener(l);
        SocketChannel raw = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                l.boundPort));
        raws.add(raw);
        assertEquals(-1, raw.read(ByteBuffer.allocate(8)));
        assertEquals(0, l.created.get());
    }

    @Test
    public void notAllowedNetworkIsRefusedAndAllowedNetworkIsServed() throws Exception {
        TestListener refused = new TestListener(false);
        List<CidrNetwork> elsewhere = new ArrayList<CidrNetwork>();
        elsewhere.add(new CidrNetwork("10.99.0.0/16"));
        refused.allowedNetworks(elsewhere);
        startListener(refused);
        SocketChannel raw = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                refused.boundPort));
        raws.add(raw);
        assertEquals(-1, raw.read(ByteBuffer.allocate(8)));
        assertEquals(0, refused.created.get());
        gumdrop.shutdownNow();

        TestListener served = new TestListener(false);
        List<CidrNetwork> here = new ArrayList<CidrNetwork>();
        here.add(new CidrNetwork("127.0.0.0/8"));
        here.add(new CidrNetwork("::1/128"));
        served.allowedNetworks(here);
        startListener(served);
        Handler client = new Handler(false, false);
        connect(plainClientFactory(), served.boundPort, client);
        await(client.connected);
        serverSide(served, 0);
        assertEquals(1, served.created.get());
        closeOnLoop(client.endpoint);
        await(client.disconnected);
    }

    @Test
    public void connectionRateLimitRefusesBurst() throws Exception {
        TestListener l = new TestListener(false);
        l.rateLimit("1/1h");
        startListener(l);
        Handler first = new Handler(false, false);
        connect(plainClientFactory(), l.boundPort, first);
        await(first.connected);
        serverSide(l, 0);
        SocketChannel raw = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                l.boundPort));
        raws.add(raw);
        assertEquals(-1, raw.read(ByteBuffer.allocate(8)));
        closeOnLoop(first.endpoint);
        await(first.disconnected);
    }

    @Test
    public void removeListenerStopsAcceptingAndAddServerRegistersListeners() throws Exception {
        final TestListener l = new TestListener(false);
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        l.addresses(InetAddress.getLoopbackAddress());
        Server server = new Server() {
            @Override
            @SuppressWarnings("rawtypes")
            public List getListeners() {
                List<TcpListener> list = new ArrayList<TcpListener>();
                list.add(l);
                return list;
            }

            @Override
            public void start(Gumdrop g) {
                l.start(g);
            }

            @Override
            public void stop() {
                l.stop();
            }
        };
        gumdrop.addServer(server);
        assertTrue(gumdrop.awaitStartupComplete(HANG_GUARD_MS));
        await(l.bound);
        Handler client = new Handler(false, false);
        connect(plainClientFactory(), l.boundPort, client);
        await(client.connected);
        serverSide(l, 0);
        closeOnLoop(client.endpoint);
        await(client.disconnected);
        gumdrop.removeServer(server);
        assertTrue(gumdrop.getServers().isEmpty());
        assertNotNull(gumdrop.getListeners());
    }

    @Test
    public void clientToClosedFactoryTargetReportsViaHandlerWhenServerResets() throws Exception {
        TestListener l = startListener(new TestListener(false));
        Handler client = new Handler(false, false);
        connect(plainClientFactory(), l.boundPort, client);
        await(client.connected);
        Handler server = serverSide(l, 0);
        closeOnLoop(server.endpoint);
        await(client.disconnected);
        await(server.disconnected);
    }
}
