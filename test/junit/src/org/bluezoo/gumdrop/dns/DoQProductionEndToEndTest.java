/*
 * DoQProductionEndToEndTest.java
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

package org.bluezoo.gumdrop.dns;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.dns.client.DNSClientTransportHandler;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.dns.client.DoQClientTransport;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.quic.SessionTicketCache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Stage 10 Part 6 (docs/QUIC-AGENT15-MIGRATION-PLAN.md): drives a real
 * {@link DoQClientTransport} against a real DoQ server (a raw {@link
 * QuicTransportFactory} server engine feeding {@link DoQStreamHandler},
 * mirroring how {@link DoQListener} wires it up) and proves the RFC 9250
 * section 4.5 opcode-eligibility gating added to {@code
 * DoQClientTransport.send} actually works.
 *
 * <p>A first connection captures a session ticket. A second connection
 * issues a QUERY and a STATUS request back to back, immediately after
 * {@code open()} -- dispatched onto the connection's own {@code
 * SelectorLoop} thread, matching how a real, always-loop-driven caller
 * like {@code DNSResolver} would use this API, so {@code open()}'s
 * internal {@code EarlyDataHandler} fires synchronously within that same
 * task. Only the QUERY (opcode-eligible) should have gone out as 0-RTT
 * data; the STATUS request (not eligible) must have been deferred until
 * the connection was fully established -- verified via the same {@code
 * sentZeroRttStream} bookkeeping check {@code HTTP3ProductionEndToEndTest}
 * uses for the equivalent HTTP/3 method-safety proof, keyed off the two
 * requests' predictable stream IDs (0, then 4 -- client-initiated bidi
 * streams on a fresh connection, in send order).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQProductionEndToEndTest {

    private static final String QUESTION_NAME = "doq-test.gumdrop.local";

    private static Path certsDirectory;
    private static Path certFile;
    private static Path keyFile;

    @BeforeClass
    public static void generatePemFiles() throws Exception {
        certsDirectory = Files.createTempDirectory("doq-production-e2e-test");
        Path keystorePath = certsDirectory.resolve("server.p12");
        certFile = certsDirectory.resolve("cert.pem");
        keyFile = certsDirectory.resolve("key.pem");

        // CN/SAN must match the literal loopback address, not a hostname:
        // DoQ connects directly by IP (no SNI hostname available), so
        // DoQClientTransport.open() offers the address string itself as
        // the TLS server name (see its own comment on why), and Agent15
        // performs real hostname verification against whatever was
        // offered -- a hostname-only CN would fail that check here.
        run(new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=127.0.0.1",
                "-ext", "SAN=ip:127.0.0.1",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit"), "keytool");

        run(new ProcessBuilder(
                "openssl", "pkcs12", "-in", keystorePath.toString(),
                "-nodes", "-nocerts", "-out", keyFile.toString(),
                "-passin", "pass:changeit"), "openssl (key export)");

        run(new ProcessBuilder(
                "openssl", "pkcs12", "-in", keystorePath.toString(),
                "-nokeys", "-out", certFile.toString(),
                "-passin", "pass:changeit"), "openssl (cert export)");
    }

    private static void run(ProcessBuilder pb, String toolName) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            fail(toolName + " failed to produce test PEM files");
        }
    }

    @AfterClass
    public static void deletePemFiles() throws IOException {
        if (certsDirectory == null) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(certsDirectory)) {
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(certsDirectory);
    }

    @Test
    public void testQueryRidesZeroRttStatusDefersUntilEstablished() throws Exception {
        SessionTicketCache.clear();
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        final DoQClientTransport firstTransport = new DoQClientTransport();
        final DoQClientTransport secondTransport = new DoQClientTransport();
        // DoQClientTransport has no setVerifyPeer(false) escape hatch (a
        // real DoQ client always validates the server's certificate --
        // RFC 9250 section 4.1's requirement to authenticate the server
        // isn't optional the way it can be relaxed for local test
        // fixtures elsewhere in this codebase); trust the test cert
        // directly instead, the same way a real deployment would pin a
        // private CA via setCaFile.
        firstTransport.setCaFile(certFile);
        secondTransport.setCaFile(certFile);
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols("doq");
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.setEarlyDataEnabled(true);
            serverFactory.start();

            final DNSService dnsService = new DNSService() {
                @Override
                protected DNSMessage resolve(DNSMessage query) {
                    List<DNSResourceRecord> answers = new ArrayList<DNSResourceRecord>();
                    try {
                        answers.add(DNSResourceRecord.a(QUESTION_NAME, 60,
                                InetAddress.getByName("127.0.0.1")));
                    } catch (java.net.UnknownHostException e) {
                        throw new AssertionError(e);
                    }
                    return query.createResponse(answers);
                }
            };

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return new DoQStreamHandler(dnsService);
                        }
                    }, loop);
            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            // First connection: an ordinary query, just to make the server
            // issue a session ticket (RFC 8446 section 4.6.1 -- issued
            // automatically to any client offering psk_dhe_ke). No ticket
            // is cached yet at this point, so open()'s EarlyDataHandler
            // never fires -- "connected" only flips true later,
            // asynchronously, once the real handshake completes.
            final CountDownLatch warmupLatch = new CountDownLatch(1);
            final AtomicReference<Exception> warmupFailure = new AtomicReference<Exception>();
            final CountDownLatch firstConnected = new CountDownLatch(1);
            DoQClientTransport.connectedObserver = new Runnable() {
                @Override
                public void run() {
                    firstConnected.countDown();
                    // Send on the same SelectorLoop thread that just
                    // marked the transport connected -- issuing from the
                    // JUnit thread raced openStream/flush and could miss
                    // the 5s warm-up deadline on loaded CI hosts.
                    firstTransport.send(buildQuery(DNSMessage.OPCODE_QUERY));
                }
            };
            try {
                firstTransport.open(InetAddress.getLoopbackAddress(), port, loop,
                        new DNSClientTransportHandler() {
                            @Override
                            public void onReceive(ByteBuffer data) {
                                warmupLatch.countDown();
                            }

                            @Override
                            public void onError(Exception cause) {
                                warmupFailure.set(cause);
                                warmupLatch.countDown();
                            }
                        });
                assertTrue("First transport should become connected within 5s",
                        firstConnected.await(5, TimeUnit.SECONDS));
            } finally {
                DoQClientTransport.connectedObserver = null;
            }

            assertTrue("Warm-up query should complete within 5s", warmupLatch.await(5, TimeUnit.SECONDS));
            if (warmupFailure.get() != null) {
                throw warmupFailure.get();
            }

            SessionTicketCache.Entry entry = awaitSessionTicket(
                    InetAddress.getLoopbackAddress().getHostAddress(), port);
            assertNotNull("A session ticket should have been cached after the warm-up query", entry);

            // Second connection: 0-RTT-enabled (DoQClientTransport.open()
            // always enables it), issuing a QUERY (stream 0, eligible)
            // and a STATUS request (stream 4, not eligible) back to back
            // immediately after open() -- dispatched onto the loop thread
            // itself so open()'s internal EarlyDataHandler, which fires
            // synchronously from within connectTo(), runs before this
            // task returns (matching how a real, always-loop-driven
            // caller like DNSResolver would use this API).
            //
            // sentZeroRttStream is captured synchronously, immediately
            // after both sends -- not after waiting for responses. RFC
            // 9002 loss detection can requeue-and-resend an accepted
            // 0-RTT packet if a later packet's ACK arrives first (a real,
            // separate, pre-existing gap in this codebase's loss-detection
            // heuristics, not specific to 0-RTT or to this stage's work);
            // that's a legitimate, harmless retransmission from the
            // application's perspective (both requests still complete
            // correctly either way, proven by the latch below), but it
            // would make a *post-response* bookkeeping check flaky for
            // reasons unrelated to what this test is actually proving:
            // that DoQClientTransport.send's opcode-eligibility gate
            // decides correctly which stream is allowed to go out as
            // 0-RTT in the first place.
            final CountDownLatch bothLatch = new CountDownLatch(2);
            final AtomicReference<Exception> secondFailure = new AtomicReference<Exception>();
            final AtomicReference<IOException> openFailure = new AtomicReference<IOException>();
            final AtomicReference<QuicConnection> connectionRef = new AtomicReference<QuicConnection>();
            final AtomicReference<Map<Long, Map<Long, ?>>> sentZeroRttStreamRef =
                    new AtomicReference<Map<Long, Map<Long, ?>>>();

            loop.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        secondTransport.open(InetAddress.getLoopbackAddress(), port, loop,
                                new DNSClientTransportHandler() {
                                    @Override
                                    public void onReceive(ByteBuffer data) {
                                        bothLatch.countDown();
                                    }

                                    @Override
                                    public void onError(Exception cause) {
                                        secondFailure.set(cause);
                                        bothLatch.countDown();
                                    }
                                });
                    } catch (IOException e) {
                        openFailure.set(e);
                        return;
                    }
                    secondTransport.send(buildQuery(DNSMessage.OPCODE_QUERY));
                    secondTransport.send(buildQuery(DNSMessage.OPCODE_STATUS));
                    try {
                        QuicConnection connection =
                                getPrivateField(secondTransport, "quicConnection", QuicConnection.class);
                        connectionRef.set(connection);
                        @SuppressWarnings("unchecked")
                        Map<Long, Map<Long, ?>> sentZeroRttStream =
                                getPrivateField(connection, "sentZeroRttStream", Map.class);
                        // Snapshot now -- requeueLostPacket can mutate the
                        // live map later.
                        sentZeroRttStreamRef.set(
                                new java.util.HashMap<Long, Map<Long, ?>>(sentZeroRttStream));
                    } catch (Exception e) {
                        secondFailure.set(e);
                    }
                }
            });

            if (openFailure.get() != null) {
                throw openFailure.get();
            }
            assertTrue("Both requests should complete within 5s", bothLatch.await(5, TimeUnit.SECONDS));
            if (secondFailure.get() != null) {
                throw secondFailure.get();
            }

            QuicConnection connection = connectionRef.get();
            assertNotNull(connection);
            Object zeroRttState = getPrivateField(connection, "zeroRttState", Object.class);
            assertEquals("0-RTT should have been accepted on the second connection", "ACCEPTED",
                    zeroRttState.toString());

            Map<Long, Map<Long, ?>> sentZeroRttStream = sentZeroRttStreamRef.get();
            assertNotNull(sentZeroRttStream);
            boolean queryStreamWasZeroRtt = false;
            boolean statusStreamWasZeroRtt = false;
            for (Map<Long, ?> perPacketStreams : sentZeroRttStream.values()) {
                if (perPacketStreams.containsKey(Long.valueOf(0L))) {
                    queryStreamWasZeroRtt = true;
                }
                if (perPacketStreams.containsKey(Long.valueOf(4L))) {
                    statusStreamWasZeroRtt = true;
                }
            }
            assertTrue("QUERY (opcode-eligible, stream 0) should have been sent as 0-RTT data",
                    queryStreamWasZeroRtt);
            assertFalse("STATUS (not opcode-eligible, stream 4) must not have been sent as 0-RTT data",
                    statusStreamWasZeroRtt);
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            firstTransport.close();
            secondTransport.close();
            if (serverEngine != null) {
                serverEngine.close();
            }
            SessionTicketCache.clear();
        }
    }

    /**
     * RFC 9250 section 4.2.1: Message ID MUST be 0 on the wire in both
     * directions for DoQ, so {@code DNSResolver}'s ID-keyed {@code
     * pendingQueries} map (RFC 1035 section 7.3 correlation, designed for
     * transports where the ID survives on the wire) cannot rely on the
     * parsed response ID to tell two concurrent DoQ queries apart -- both
     * responses parse to ID 0 regardless of which query they answer.
     * Drives two concurrent {@code queryA} calls for different names
     * through a real {@code DNSResolver} configured with a real {@code
     * DoQClientTransport} against a real DoQ server (same server harness
     * as the 0-RTT test above) that answers each name with a distinct
     * address, and asserts each callback receives the answer for its own
     * name -- proving correlation survives the round trip through a
     * transport that legitimately zeroes the ID both ways, not just that
     * some response eventually arrives.
     */
    @Test
    public void testConcurrentQueriesCorrelateThroughDNSResolver() throws Exception {
        final String nameA = "doq-concurrent-a.gumdrop.local";
        final String nameB = "doq-concurrent-b.gumdrop.local";
        final String ipA = "10.11.12.1";
        final String ipB = "10.11.12.2";

        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        final DoQClientTransport transport = new DoQClientTransport();
        transport.setCaFile(certFile);
        final DNSResolver resolver = new DNSResolver();
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols("doq");
            serverFactory.setCertFile(certFile);
            serverFactory.setKeyFile(keyFile);
            serverFactory.start();

            final DNSService dnsService = new DNSService() {
                @Override
                protected DNSMessage resolve(DNSMessage query) {
                    DNSQuestion question = query.getQuestions().get(0);
                    String ip = nameA.equals(question.getName()) ? ipA : ipB;
                    List<DNSResourceRecord> answers = new ArrayList<DNSResourceRecord>();
                    try {
                        answers.add(DNSResourceRecord.a(question.getName(), 60,
                                InetAddress.getByName(ip)));
                    } catch (java.net.UnknownHostException e) {
                        throw new AssertionError(e);
                    }
                    return query.createResponse(answers);
                }
            };

            serverEngine = serverFactory.createServerEngine(
                    InetAddress.getLoopbackAddress(), 0,
                    new StreamAcceptHandler() {
                        @Override
                        public ProtocolHandler acceptStream(Endpoint stream) {
                            return new DoQStreamHandler(dnsService);
                        }
                    }, loop);
            int port = ((InetSocketAddress) serverEngine.getLocalAddress()).getPort();

            resolver.setTransport(transport);
            resolver.setSelectorLoop(loop);
            // Short timeout: pre-fix, both responses are silently dropped
            // (neither correlates to a pending query keyed by its real,
            // non-zero ID) and this test's failure should come from a
            // clear "query timed out" error well before any risk of the
            // test's own await() racing that timeout.
            resolver.setTimeoutMs(2000);
            resolver.addServer(InetAddress.getLoopbackAddress(), port);
            final CountDownLatch transportConnected = new CountDownLatch(1);
            DoQClientTransport.connectedObserver = new Runnable() {
                @Override
                public void run() {
                    transportConnected.countDown();
                }
            };
            try {
                resolver.open();
                assertTrue("Transport should become connected within 5s",
                        transportConnected.await(5, TimeUnit.SECONDS));
            } finally {
                DoQClientTransport.connectedObserver = null;
            }

            final CountDownLatch latch = new CountDownLatch(2);
            final AtomicReference<InetAddress> resultA = new AtomicReference<InetAddress>();
            final AtomicReference<InetAddress> resultB = new AtomicReference<InetAddress>();
            final AtomicReference<AssertionError> failure = new AtomicReference<AssertionError>();

            loop.invokeLater(new Runnable() {
                @Override
                public void run() {
                    resolver.queryA(nameA, new DNSQueryCallback() {
                        @Override
                        public void onResponse(DNSMessage response) {
                            for (DNSResourceRecord rr : response.getAnswers()) {
                                if (rr.getType() == DNSType.A) {
                                    resultA.set(rr.getAddress());
                                }
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onError(String error) {
                            failure.compareAndSet(null,
                                    new AssertionError("Query for " + nameA + " failed: " + error));
                            latch.countDown();
                        }
                    });
                    resolver.queryA(nameB, new DNSQueryCallback() {
                        @Override
                        public void onResponse(DNSMessage response) {
                            for (DNSResourceRecord rr : response.getAnswers()) {
                                if (rr.getType() == DNSType.A) {
                                    resultB.set(rr.getAddress());
                                }
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onError(String error) {
                            failure.compareAndSet(null,
                                    new AssertionError("Query for " + nameB + " failed: " + error));
                            latch.countDown();
                        }
                    });
                }
            });

            assertTrue("Both concurrent queries should complete within 4s",
                    latch.await(4, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw failure.get();
            }
            assertEquals("Query for " + nameA + " should get " + nameA + "'s own answer",
                    InetAddress.getByName(ipA), resultA.get());
            assertEquals("Query for " + nameB + " should get " + nameB + "'s own answer",
                    InetAddress.getByName(ipB), resultB.get());
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            resolver.close();
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }

    private static SessionTicketCache.Entry awaitSessionTicket(String host, int port) throws Exception {
        SessionTicketCache.Entry existing = SessionTicketCache.get(host, port);
        if (existing != null) {
            return existing;
        }
        final CountDownLatch cached = new CountDownLatch(1);
        SessionTicketCache.putObserver = new Runnable() {
            @Override
            public void run() {
                cached.countDown();
            }
        };
        try {
            assertTrue("A session ticket should have been cached after the warm-up query",
                    cached.await(5, TimeUnit.SECONDS));
            SessionTicketCache.Entry entry = SessionTicketCache.get(host, port);
            assertNotNull(entry);
            return entry;
        } finally {
            SessionTicketCache.putObserver = null;
        }
    }

    private static ByteBuffer buildQuery(int opcode) {
        DNSQuestion question = new DNSQuestion(QUESTION_NAME, DNSType.A);
        int flags = DNSMessage.FLAG_RD | (opcode << 11);
        DNSMessage query = new DNSMessage(1234, flags,
                Collections.singletonList(question),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());
        return query.serialize();
    }

    private static <T> T getPrivateField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
