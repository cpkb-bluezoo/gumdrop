/*
 * InteropClient.java
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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.quic.SessionTicketCache;

/**
 * The client role of gumdrop's quic-interop-runner endpoint. Downloads
 * the {@code REQUESTS} into {@code /downloads} and exits 0 on success, 1
 * on failure, or {@link InteropTestCase#EXIT_UNSUPPORTED} for a test case
 * this endpoint does not implement.
 *
 * <p>The test case decides the connection pattern: one connection for
 * everything by default, one connection per file for {@code
 * multiconnect}, and two connections for {@code resumption} and {@code
 * zerortt}, the second resuming the first's session (and, for 0-RTT,
 * sending its requests before the handshake completes). {@code http3}
 * goes through the HTTP/3 client; every other case speaks
 * {@code hq-interop}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class InteropClient {

    private static final Logger LOGGER = Logger.getLogger(InteropClient.class.getName());

    /** Per connection; the runner's own timeout kills a stuck client anyway. */
    private static final long CONNECTION_TIMEOUT_MILLIS = 120_000;

    private InteropClient() {
    }

    public static void main(String[] args) {
        InteropEnvironment env = InteropEnvironment.fromSystem();
        InteropTestCase testCase = InteropTestCase.fromWireName(env.testCase());
        if (testCase == null || !testCase.isClientSupported()) {
            System.err.println("gumdrop interop client: unsupported test case: " + env.testCase());
            System.exit(InteropTestCase.EXIT_UNSUPPORTED);
            return;
        }
        List<RequestUrl> urls = new ArrayList<RequestUrl>();
        for (int i = 0; i < env.requests().size(); i++) {
            try {
                urls.add(RequestUrl.parse(env.requests().get(i)));
            } catch (URISyntaxException e) {
                System.err.println("gumdrop interop client: bad request URL: " + e.getMessage());
                System.exit(1);
                return;
            }
        }
        LOGGER.info("gumdrop interop client: test case " + testCase.getWireName() + ", "
                + urls.size() + " request(s), downloads to " + env.downloads());
        InteropEnvironment.enableKeyLog();
        boolean ok;
        try {
            ok = run(env, testCase, urls);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "client failed", e);
            ok = false;
        }
        LOGGER.info(ok ? "all downloads complete" : "downloads failed");
        System.exit(ok ? 0 : 1);
    }

    /**
     * Waits until the network simulator accepts connections on the address
     * in {@code INTEROP_WAIT_FOR} ({@code host:port}), which it does once its
     * network is up. This replaces the shell's wait-for-it before the JVM
     * starts: the simulator rebinds the client's port a second after it
     * starts (the rebinding cases), and a JVM that only begins loading
     * after the wait has spent most of that second before it can send
     * anything, so the handshake straddled the rebind. Starting the JVM
     * first and connecting the moment the simulator is up gives the
     * handshake the time a native client has.
     */
    static void awaitSimulator() {
        String target = System.getenv("INTEROP_WAIT_FOR");
        if (target == null || target.isEmpty()) {
            return;
        }
        int colon = target.lastIndexOf(':');
        String host = target.substring(0, colon);
        int port = Integer.parseInt(target.substring(colon + 1));
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (true) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), 1000);
                return;
            } catch (IOException e) {
                if (System.nanoTime() > deadline) {
                    LOGGER.warning("simulator " + target + " not reachable: " + e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // nothing to do
                }
            }
        }
    }

    static boolean run(InteropEnvironment env, InteropTestCase testCase, List<RequestUrl> urls)
            throws Exception {
        Files.createDirectories(env.downloads());
        if (testCase == InteropTestCase.HTTP3) {
            awaitSimulator();
            return new H3Downloader().download(urls, env.downloads(), env.caFile(), CONNECTION_TIMEOUT_MILLIS);
        }
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        try {
            QuicTransportFactory factory = new QuicTransportFactory();
            factory.setApplicationProtocols(InteropServer.ALPN_HQ);
            factory.setCaFile(env.caFile());
            InteropEnvironment.enableQlog(factory);
            factory.setEarlyDataEnabled(testCase == InteropTestCase.ZERORTT);
            factory.setAckFrequencyEnabled(!"0".equals(System.getenv("ACK_FREQUENCY")));
            if (testCase == InteropTestCase.CHACHA20) {
                factory.setCipherSuites(InteropServer.CHACHA20_ONLY);
            }
            if (testCase == InteropTestCase.V2) {
                // Still opens in v1 (the oldest listed version), as the
                // runner requires, but lists v2 first among the versions
                // the server may switch to.
                factory.setVersions(InteropServer.V2_PREFERRED);
            }
            if (testCase == InteropTestCase.TRANSFER || testCase == InteropTestCase.MULTICONNECT) {
                // The runner runs its loss, corruption, blackhole and
                // amplification scenarios under these two names, and they
                // drop whole datagrams by number or at random. A hybrid
                // X25519MLKEM768 key share makes the ClientHello two
                // datagrams (so one of the two is lost far more often, and
                // the amplification case drops the second every time); the
                // classical share fits in one. The handshake, resumption
                // and 0-RTT cases keep the hybrid default.
                factory.setNamedGroups("x25519");
            }
            if (testCase == InteropTestCase.TRANSFER) {
                // The transfer case asks for initial windows small enough
                // that multi-megabyte files need MAX_DATA / MAX_STREAM_DATA
                // updates during the download.
                factory.setMaxData(1024 * 1024);
                factory.setMaxStreamDataBidiLocal(256 * 1024);
            }
            factory.start();
            HqClient client = new HqClient(loop, factory, env.downloads());
            awaitSimulator();

            if (testCase == InteropTestCase.MULTICONNECT) {
                boolean ok = true;
                for (int i = 0; i < urls.size(); i++) {
                    List<RequestUrl> one = new ArrayList<RequestUrl>();
                    one.add(urls.get(i));
                    if (!client.download(one, false, false, CONNECTION_TIMEOUT_MILLIS)) {
                        ok = false;
                    }
                }
                return ok;
            }
            if (testCase == InteropTestCase.RESUMPTION || testCase == InteropTestCase.ZERORTT) {
                List<RequestUrl> firstOnly = urls.subList(0, 1);
                List<RequestUrl> rest = urls.subList(1, urls.size());
                if (!client.download(firstOnly, true, false, CONNECTION_TIMEOUT_MILLIS)) {
                    return false;
                }
                RequestUrl first = urls.get(0);
                if (SessionTicketCache.get(first.host, first.port) == null) {
                    LOGGER.warning("no session ticket cached for " + first.host + ":" + first.port
                            + "; the second connection cannot resume");
                }
                return client.download(rest, false, testCase == InteropTestCase.ZERORTT,
                        CONNECTION_TIMEOUT_MILLIS);
            }
            return client.download(urls, false, false, testCase == InteropTestCase.KEYUPDATE,
                    CONNECTION_TIMEOUT_MILLIS);
        } finally {
            loop.shutdown();
        }
    }

}
