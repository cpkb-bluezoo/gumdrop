/*
 * SmtpServerCompositionTest.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.smtp.client.SmtpClient;
import org.bluezoo.gumdrop.smtp.client.ClientHelloState;
import org.bluezoo.gumdrop.smtp.client.ClientSession;
import org.bluezoo.gumdrop.smtp.client.EhloReplyHandler;
import org.bluezoo.gumdrop.smtp.client.RemoteGreeting;
import org.bluezoo.gumdrop.smtp.server.ClientConnected;
import org.bluezoo.gumdrop.smtp.server.ConnectedState;
import org.bluezoo.gumdrop.smtp.server.HelloHandler;
import org.bluezoo.gumdrop.smtp.server.HelloState;
import org.bluezoo.gumdrop.smtp.server.MailFromHandler;
import org.bluezoo.gumdrop.smtp.server.MailFromState;
import org.bluezoo.gumdrop.smtp.server.ResetState;
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
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Workstream C.3.1 — {@link SmtpServer#compose()} and fluent
 * {@link SmtpClient} session-provider composition.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(2));
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
    public void testComposedServerAndDirectClientConnect() throws Exception {
        AtomicInteger serverSessionsOpened = new AtomicInteger();
        AtomicReference<String> greetingRef = new AtomicReference<String>();
        CountDownLatch ehloDone = new CountDownLatch(1);
        AtomicReference<Exception> clientError = new AtomicReference<Exception>();

        SmtpListener listener = new SmtpListener()
                .port(testPort)
                .addresses(InetAddress.ofLiteral(TEST_HOST));

        server = SmtpServer.compose()
                .listener(listener)
                .sessionPerConnection(new Supplier<ClientConnected>() {
                    @Override
                    public ClientConnected get() {
                        return new BannerServerHandler(serverSessionsOpened);
                    }
                })
                .server();

        gumdrop.addServer(server);

        SmtpClient client = new SmtpClient()
                .host(TEST_HOST)
                .port(testPort);

        client.connect(gumdrop, new RemoteGreeting() {
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
                .sessionPerConnection(new Supplier<ClientConnected>() {
                    @Override
                    public ClientConnected get() {
                        return new BannerServerHandler(serverSessionsOpened);
                    }
                })
                .server();

        gumdrop.addServer(server);

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
                .port(port);

        client.connect(gumdrop, new RemoteGreeting() {
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
                org.bluezoo.gumdrop.smtp.server.AuthenticateState state,
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
