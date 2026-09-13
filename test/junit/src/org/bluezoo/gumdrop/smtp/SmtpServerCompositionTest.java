/*
 * SmtpServerCompositionTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.smtp.client.SmtpClient;
import org.bluezoo.gumdrop.smtp.client.handler.ClientHelloState;
import org.bluezoo.gumdrop.smtp.client.handler.ClientSession;
import org.bluezoo.gumdrop.smtp.client.handler.EhloReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.RemoteGreeting;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;
import org.bluezoo.gumdrop.smtp.handler.ConnectedState;
import org.bluezoo.gumdrop.smtp.handler.HelloHandler;
import org.bluezoo.gumdrop.smtp.handler.HelloState;
import org.bluezoo.gumdrop.smtp.handler.MailFromHandler;
import org.bluezoo.gumdrop.smtp.handler.MailFromState;
import org.bluezoo.gumdrop.smtp.handler.ResetState;
import org.bluezoo.gumdrop.smtp.server.SmtpServer;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.DeliveryRequirements;
import org.bluezoo.gumdrop.smtp.SmtpPipeline;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Workstream C.3.1 — {@link SmtpServer#compose()} and fluent
 * {@link SmtpClient} session-provider composition.
 */
public class SmtpServerCompositionTest {

    private static final String TEST_HOST = "127.0.0.1";
    private static final String TEST_BANNER = "gumdrop-composition-test ESMTP";
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(25200);

    private Gumdrop gumdrop;
    private SmtpServer server;
    private int testPort;

    @Before
    public void setUp() throws Exception {
        testPort = NEXT_PORT.getAndIncrement();
        System.setProperty("gumdrop.workers", "2");
        gumdrop = Gumdrop.getInstance();
        if (gumdrop.isStarted()) {
            gumdrop.shutdown();
            gumdrop.join();
            gumdrop = Gumdrop.getInstance();
        }
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null) {
            if (server != null) {
                gumdrop.removeServer(server);
                server = null;
            }
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    @Test
    public void testComposedServerAndClientSessionProviders() throws Exception {
        AtomicInteger serverSessionsOpened = new AtomicInteger();
        AtomicReference<String> greetingRef = new AtomicReference<String>();
        CountDownLatch ehloDone = new CountDownLatch(1);
        AtomicReference<Exception> clientError = new AtomicReference<Exception>();

        SmtpListener listener = new SmtpListener()
                .port(testPort)
                .addresses(InetAddress.ofLiteral(TEST_HOST));

        server = SmtpServer.compose()
                .listener(listener)
                .sessionPerConnection(() ->
                        new BannerServerHandler(serverSessionsOpened))
                .server();

        gumdrop.addServer(server);
        gumdrop.start();

        SmtpClient client = new SmtpClient()
                .host(TEST_HOST)
                .port(testPort)
                .sessionPerConnection(() -> new RemoteGreeting() {
                    @Override
                    public void handleGreeting(ClientHelloState hello,
                                               String message, boolean esmtp) {
                        greetingRef.set(message);
                        hello.ehlo("composition-client", new EhloReplyHandler() {
                            @Override
                            public void handleEhlo(ClientSession session,
                                                    boolean starttls,
                                                    long maxSize,
                                                    List<String> authMethods,
                                                    boolean pipelining) {
                                ehloDone.countDown();
                                session.quit();
                            }

                            @Override
                            public void handleEhloNotSupported(
                                    ClientHelloState retryHello) {
                                clientError.set(new IllegalStateException(
                                        "EHLO not supported"));
                                ehloDone.countDown();
                            }

                            @Override
                            public void handlePermanentFailure(String message) {
                                clientError.set(new IllegalStateException(
                                        message));
                                ehloDone.countDown();
                            }

                            @Override
                            public void handleReply(int code, String message,
                                                    ClientSession session) {
                            }

                            @Override
                            public void handleServiceClosing(String message) {
                            }
                        });
                    }

                    @Override
                    public void handleServiceUnavailable(String message) {
                        clientError.set(new IllegalStateException(message));
                        ehloDone.countDown();
                    }

                    @Override
                    public void onConnected(Endpoint endpoint) {
                    }

                    @Override
                    public void onDisconnected() {
                    }

                    @Override
                    public void onSecurityEstablished(SecurityInfo info) {
                    }

                    @Override
                    public void onError(Exception e) {
                        clientError.set(e);
                        ehloDone.countDown();
                    }
                });

        client.connect();

        assertTrue("EHLO not completed", ehloDone.await(5, TimeUnit.SECONDS));
        assertNull(clientError.get());
        assertEquals(TEST_BANNER, greetingRef.get());
        assertEquals(1, serverSessionsOpened.get());

        client.close();
    }

    @Test
    public void testServerSessionPerConnectionCreatesFreshPipeline() throws Exception {
        AtomicInteger serverSessionsOpened = new AtomicInteger();

        SmtpListener listener = new SmtpListener()
                .port(testPort)
                .addresses(InetAddress.ofLiteral(TEST_HOST));

        server = SmtpServer.compose()
                .listener(listener)
                .sessionPerConnection(() ->
                        new BannerServerHandler(serverSessionsOpened))
                .server();

        gumdrop.addServer(server);
        gumdrop.start();

        runEhloClient(testPort);
        assertEquals(1, serverSessionsOpened.get());

        runEhloClient(testPort);
        assertEquals(2, serverSessionsOpened.get());
    }

    private void runEhloClient(int port) throws Exception {
        CountDownLatch ehloDone = new CountDownLatch(1);
        AtomicReference<Exception> clientError = new AtomicReference<Exception>();

        SmtpClient client = new SmtpClient()
                .host(TEST_HOST)
                .port(port)
                .sessionPerConnection(() -> new RemoteGreeting() {
                    @Override
                    public void handleGreeting(ClientHelloState hello,
                                               String message, boolean esmtp) {
                        hello.ehlo("composition-client", new EhloReplyHandler() {
                            @Override
                            public void handleEhlo(ClientSession session,
                                                    boolean starttls,
                                                    long maxSize,
                                                    List<String> authMethods,
                                                    boolean pipelining) {
                                ehloDone.countDown();
                                session.quit();
                            }

                            @Override
                            public void handleEhloNotSupported(
                                    ClientHelloState retryHello) {
                                clientError.set(new IllegalStateException(
                                        "EHLO not supported"));
                                ehloDone.countDown();
                            }

                            @Override
                            public void handlePermanentFailure(String message) {
                                clientError.set(new IllegalStateException(
                                        message));
                                ehloDone.countDown();
                            }

                            @Override
                            public void handleReply(int code, String message,
                                                    ClientSession session) {
                            }

                            @Override
                            public void handleServiceClosing(String message) {
                            }
                        });
                    }

                    @Override
                    public void handleServiceUnavailable(String message) {
                        clientError.set(new IllegalStateException(message));
                        ehloDone.countDown();
                    }

                    @Override
                    public void onConnected(Endpoint endpoint) {
                    }

                    @Override
                    public void onDisconnected() {
                    }

                    @Override
                    public void onSecurityEstablished(SecurityInfo info) {
                    }

                    @Override
                    public void onError(Exception e) {
                        clientError.set(e);
                        ehloDone.countDown();
                    }
                });

        client.connect();
        assertTrue(ehloDone.await(5, TimeUnit.SECONDS));
        assertNull(clientError.get());
        client.close();
    }

    private static final class BannerServerHandler implements ClientConnected,
            HelloHandler, MailFromHandler {

        private final AtomicInteger sessionsOpened;

        BannerServerHandler(AtomicInteger sessionsOpened) {
            this.sessionsOpened = sessionsOpened;
        }

        @Override
        public void connected(ConnectedState state, Endpoint endpoint) {
            sessionsOpened.incrementAndGet();
            state.acceptConnection(TEST_BANNER, this);
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void hello(HelloState state, boolean extended, String hostname) {
            state.acceptHello(this);
        }

        @Override
        public void tlsEstablished(SecurityInfo securityInfo) {
        }

        @Override
        public void authenticated(
                org.bluezoo.gumdrop.smtp.handler.AuthenticateState state,
                java.security.Principal principal) {
        }

        @Override
        public void quit() {
        }

        @Override
        public SmtpPipeline getPipeline() {
            return null;
        }

        @Override
        public void mailFrom(MailFromState state, EmailAddress sender,
                             boolean smtputf8,
                             DeliveryRequirements deliveryRequirements) {
        }

        @Override
        public void reset(ResetState state) {
        }
    }

}
