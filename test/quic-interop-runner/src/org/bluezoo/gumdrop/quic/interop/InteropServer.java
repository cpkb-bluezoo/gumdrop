/*
 * InteropServer.java
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

package org.bluezoo.gumdrop.quic.interop;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.h3.Http3Listener;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;

/**
 * The server role of gumdrop's quic-interop-runner endpoint. Serves
 * {@code /www} on UDP 443 with the runner's certificate: over
 * {@code hq-interop} (HTTP/0.9) for every test case but {@code http3},
 * which uses the real HTTP/3 stack through {@link Http3Listener}.
 *
 * <p>The test case only changes configuration: {@code retry} turns on
 * Retry address validation, {@code chacha20} restricts the cipher suite,
 * {@code zerortt} accepts early data. Cases this endpoint does not
 * implement exit with {@link InteropTestCase#EXIT_UNSUPPORTED}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class InteropServer {

    private static final Logger LOGGER = Logger.getLogger(InteropServer.class.getName());

    static final String ALPN_HQ = "hq-interop";
    static final String CHACHA20_ONLY = "TLS_CHACHA20_POLY1305_SHA256";
    /** Version preference for the v2 case: a server switches offering clients to v2; a client opens in v1 and lists v2 first. */
    static final String V2_PREFERRED = "2,1";

    private InteropServer() {
    }

    public static void main(String[] args) throws Exception {
        InteropEnvironment env = InteropEnvironment.fromSystem();
        InteropTestCase testCase = InteropTestCase.fromWireName(env.testCase());
        if (testCase == null || !testCase.isServerSupported()) {
            System.err.println("gumdrop interop server: unsupported test case: " + env.testCase());
            System.exit(InteropTestCase.EXIT_UNSUPPORTED);
            return;
        }
        LOGGER.info("gumdrop interop server: test case " + testCase.getWireName()
                + ", serving " + env.www() + " on port " + env.port());
        InteropEnvironment.enableKeyLog();
        if (testCase == InteropTestCase.HTTP3) {
            runHttp3(env);
        } else {
            runHq(env, testCase);
        }
    }

    /**
     * Configures the transport the way the test case asks for; shared by
     * the hq and HTTP/3 servers.
     */
    static void configure(QuicTransportFactory factory, InteropEnvironment env, InteropTestCase testCase) {
        factory.setCertFile(env.certFile());
        factory.setKeyFile(env.keyFile());
        InteropEnvironment.enableQlog(factory);
        // The handshake test fails if a Retry is sent, so only the retry
        // case validates addresses.
        factory.setRequireRetry(testCase == InteropTestCase.RETRY);
        factory.setEarlyDataEnabled(testCase == InteropTestCase.ZERORTT);
        if (testCase == InteropTestCase.CHACHA20) {
            factory.setCipherSuites(CHACHA20_ONLY);
        }
        if (testCase == InteropTestCase.V2) {
            // The runner expects the server to move a client that opens in
            // v1 and offers v2 onto v2; the default order keeps every
            // other case on v1, which is what those cases check for.
            factory.setVersions(V2_PREFERRED);
        }
        if (testCase == InteropTestCase.CONNECTIONMIGRATION) {
            // RFC 9000 section 9.6: offer this host's addresses on another
            // port; the engine listens there too and the client migrates.
            InetSocketAddress ipv4 = InteropEnvironment.preferredAddress(false);
            InetSocketAddress ipv6 = InteropEnvironment.preferredAddress(true);
            if (ipv4 == null && ipv6 == null) {
                LOGGER.warning("connectionmigration needs INTEROP_PREFERRED_IPV4 or INTEROP_PREFERRED_IPV6");
            } else {
                factory.setPreferredAddress(ipv4, ipv6);
                LOGGER.info("preferred address " + ipv4 + " / " + ipv6);
            }
        }
    }

    private static void runHq(InteropEnvironment env, InteropTestCase testCase) throws Exception {
        final SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicTransportFactory factory = new QuicTransportFactory();
        factory.setApplicationProtocols(ALPN_HQ);
        configure(factory, env, testCase);
        factory.start();

        final HqConnectionHandler handler = new HqConnectionHandler(env.www());
        final QuicEngine engine = bind(factory, env.port(), handler, loop);
        LOGGER.info("listening on " + engine.getLocalAddress());

        final CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                loop.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        engine.close();
                        stopped.countDown();
                    }
                });
                try {
                    stopped.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                loop.shutdown();
            }
        }));
        loop.join();
    }

    /**
     * Binds the dual-stack wildcard address so that both {@code server4}
     * and {@code server6} reach the same engine, falling back to IPv4 only
     * where the container has no IPv6.
     */
    private static QuicEngine bind(QuicTransportFactory factory, int port,
            QuicEngine.ConnectionAcceptedHandler handler, SelectorLoop loop) throws IOException {
        InetAddress any = new InetSocketAddress(0).getAddress();
        try {
            return factory.createServerEngine(any, port, handler, loop);
        } catch (IOException e) {
            if (!(any instanceof Inet6Address)) {
                throw e;
            }
            LOGGER.log(Level.WARNING, "dual-stack bind failed, trying IPv4 only", e);
            return factory.createServerEngine(InetAddress.getByName("0.0.0.0"), port, handler, loop);
        }
    }

    private static void runHttp3(InteropEnvironment env) throws Exception {
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        Http3Listener listener = new Http3Listener().port(env.port()).requireRetry(false);
        listener.setCertFile(env.certFile());
        listener.setKeyFile(env.keyFile());
        listener.setCompressResponses(false);
        listener.bindWildcard();
        HttpServer server = HttpServer.compose()
                .listener(listener)
                .streamHandler(new H3FileRequestHandler.StreamHandler(env.www()))
                .addSecurityHeaders(false)
                .server();
        gumdrop.addServer(server);
        gumdrop.join();
    }

    /**
     * Accepts every connection and answers each of its request streams
     * from {@code /www}, granting the peer a new stream for each one that
     * completes so that thousands of requests can flow over one
     * connection within the initial stream limit.
     */
    static final class HqConnectionHandler implements QuicEngine.ConnectionAcceptedHandler {

        private final Path www;

        HqConnectionHandler(Path www) {
            this.www = www;
        }

        @Override
        public void connectionAccepted(final QuicConnection connection) {
            LOGGER.fine("connection from " + connection.getRemoteAddress());
            connection.setStreamAcceptHandler(new StreamAcceptHandler() {
                @Override
                public ProtocolHandler acceptStream(Endpoint stream) {
                    return new HqServerStream(www, new Runnable() {
                        @Override
                        public void run() {
                            connection.releaseStreamCredit(true);
                        }
                    });
                }
            });
        }

    }

}
