/*
 * SocksClientDantedIntegrationTest.java
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

package org.bluezoo.gumdrop.socks.danted;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.client.SmtpClientProtocolHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ClientEnvelope;
import org.bluezoo.gumdrop.smtp.client.handler.ClientEnvelopeReady;
import org.bluezoo.gumdrop.smtp.client.handler.ClientHelloState;
import org.bluezoo.gumdrop.smtp.client.handler.ClientMessageData;
import org.bluezoo.gumdrop.smtp.client.handler.ClientSession;
import org.bluezoo.gumdrop.smtp.client.handler.DataReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.EhloReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.RemoteGreeting;
import org.bluezoo.gumdrop.smtp.client.handler.MailFromReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.MessageReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.RcptToReplyHandler;
import org.bluezoo.gumdrop.socks.client.SocksClientConfig;
import org.bluezoo.gumdrop.socks.client.SocksClientHandler;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end tests of gumdrop's SOCKS client ({@code
 * org.bluezoo.gumdrop.socks.client}) against a real Dante ({@code
 * danted}) SOCKS5 proxy -- not run in CI, see {@link DantedTestSupport}.
 *
 * <p>Same rationale as the Postfix/vsftpd tests: an independent
 * implementation of the protocol on the other end of the wire catches
 * bugs a same-lineage fake server can't. Here the protocol under test is
 * SOCKS5 itself (RFC 1928 CONNECT, RFC 1929 username/password), proven
 * by tunnelling a real SMTP transaction through the proxy to the same
 * Postfix container the {@code smtp.postfix} tests use -- see {@link
 * DantedTestSupport}'s class Javadoc for why that's solid evidence the
 * tunnel did real work rather than the client accidentally connecting
 * directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksClientDantedIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Before
    public void checkReachableAndClearMailbox() throws Exception {
        assumeTrue(DantedTestSupport.NOT_REACHABLE_MESSAGE, DantedTestSupport.isReachable());
        DantedTestSupport.clearMailbox();
    }

    private EmailAddress email(String address) {
        int at = address.indexOf('@');
        return new EmailAddress(null, address.substring(0, at), address.substring(at + 1), true);
    }

    private String senderAddress() {
        return "sender@" + DantedTestSupport.MAIL_DOMAIN;
    }

    private String recipientAddress() {
        return DantedTestSupport.MAILBOX_USER + "@" + DantedTestSupport.MAIL_DOMAIN;
    }

    // ── No-auth CONNECT tunnel, full SMTP transaction through it ──

    @Test
    public void testNoAuthTunnelDeliversMailToDestination() throws Exception {
        String subject = "gumdrop-socks-noauth-" + System.nanoTime();
        runTunneledDelivery(DantedTestSupport.PROXY_PORT, new SocksClientConfig(), subject);

        String mailbox = DantedTestSupport.awaitMailbox(TIMEOUT_SECONDS * 1000);
        assertTrue("subject not found in mail delivered through the SOCKS tunnel",
                mailbox.contains(subject));
    }

    // ── RFC 1929 username/password CONNECT tunnel ──

    @Test
    public void testUsernamePasswordAuthTunnelDeliversMailToDestination() throws Exception {
        String subject = "gumdrop-socks-auth-" + System.nanoTime();
        SocksClientConfig config = new SocksClientConfig(
                DantedTestSupport.AUTH_USERNAME, DantedTestSupport.AUTH_PASSWORD);
        runTunneledDelivery(DantedTestSupport.PROXY_AUTH_PORT, config, subject);

        String mailbox = DantedTestSupport.awaitMailbox(TIMEOUT_SECONDS * 1000);
        assertTrue("subject not found in mail delivered through the authenticated SOCKS tunnel",
                mailbox.contains(subject));
    }

    // ── Negative: wrong password against the auth-required proxy ──

    @Test
    public void testWrongPasswordRejectedByAuthProxy() throws Exception {
        SocksClientConfig config = new SocksClientConfig(DantedTestSupport.AUTH_USERNAME, "wrong-password");
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        connectThroughTunnel(DantedTestSupport.PROXY_AUTH_PORT, config, new RemoteGreeting() {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                error.set(new RuntimeException("should never reach the SMTP greeting with a rejected SOCKS auth"));
                doneLatch.countDown();
            }

            @Override
            public void handleServiceUnavailable(String message) {
                error.set(new RuntimeException("unexpected service unavailable"));
                doneLatch.countDown();
            }

            @Override
            public void onConnected(Endpoint endpoint) {
            }

            @Override
            public void onError(Exception cause) {
                // Expected: RFC 1929 auth failure surfaces as a connection error.
                doneLatch.countDown();
            }

            @Override
            public void onDisconnected() {
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
    }

    // ── Shared plumbing ──

    private void runTunneledDelivery(int proxyPort, SocksClientConfig config, String subject) throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        String body = "Subject: " + subject + "\r\n\r\ndelivered through a real SOCKS5 tunnel\r\n";

        connectThroughTunnel(proxyPort, config, new RemoteGreeting() {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo("gumdrop-test", new EhloReplyHandler() {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                            List<String> authMethods, boolean pipelining) {
                        session.mailFrom(email(senderAddress()), new MailFromReplyHandler() {
                            @Override
                            public void handleMailFromOk(ClientEnvelope envelope) {
                                envelope.rcptTo(email(recipientAddress()), new RcptToReplyHandler() {
                                    @Override
                                    public void handleRcptToOk(ClientEnvelopeReady ready) {
                                        ready.data(new DataReplyHandler() {
                                            @Override
                                            public void handleReadyForData(ClientMessageData data) {
                                                data.writeContent(ByteBuffer.wrap(body.getBytes(StandardCharsets.US_ASCII)));
                                                data.endMessage(new MessageReplyHandler() {
                                                    @Override
                                                    public void handleMessageAccepted(String queueId, ClientSession s) {
                                                        s.quit();
                                                        doneLatch.countDown();
                                                    }

                                                    @Override
                                                    public void handleTemporaryFailure(ClientSession s) {
                                                        fail(error, doneLatch, "temp failure at end of DATA");
                                                    }

                                                    @Override
                                                    public void handlePermanentFailure(String msg, ClientSession s) {
                                                        fail(error, doneLatch, "permanent failure at end of DATA: " + msg);
                                                    }

                                                    @Override
                                                    public void handleServiceClosing(String msg) {
                                                        fail(error, doneLatch, "service closing: " + msg);
                                                    }
                                                });
                                            }

                                            @Override
                                            public void handleTemporaryFailure(ClientEnvelopeReady r) {
                                                fail(error, doneLatch, "temp failure on DATA");
                                            }

                                            @Override
                                            public void handlePermanentFailure(String msg) {
                                                fail(error, doneLatch, "permanent failure on DATA: " + msg);
                                            }

                                            @Override
                                            public void handleServiceClosing(String msg) {
                                                fail(error, doneLatch, "service closing: " + msg);
                                            }
                                        });
                                    }

                                    @Override
                                    public void handleTemporaryFailure(org.bluezoo.gumdrop.smtp.client.handler.ClientEnvelopeState s) {
                                        fail(error, doneLatch, "temp failure on RCPT TO");
                                    }

                                    @Override
                                    public void handleRecipientRejected(org.bluezoo.gumdrop.smtp.client.handler.ClientEnvelopeState s) {
                                        fail(error, doneLatch, "recipient rejected");
                                    }

                                    @Override
                                    public void handleServiceClosing(String msg) {
                                        fail(error, doneLatch, "service closing: " + msg);
                                    }
                                });
                            }

                            @Override
                            public void handleTemporaryFailure(ClientSession s) {
                                fail(error, doneLatch, "temp failure on MAIL FROM");
                            }

                            @Override
                            public void handlePermanentFailure(String msg) {
                                fail(error, doneLatch, "permanent failure on MAIL FROM: " + msg);
                            }

                            @Override
                            public void handleServiceClosing(String msg) {
                                fail(error, doneLatch, "service closing: " + msg);
                            }
                        });
                    }

                    @Override
                    public void handleEhloNotSupported(ClientHelloState h) {
                        fail(error, doneLatch, "EHLO not supported");
                    }

                    @Override
                    public void handlePermanentFailure(String msg) {
                        fail(error, doneLatch, "EHLO rejected: " + msg);
                    }

                    @Override
                    public void handleServiceClosing(String msg) {
                        fail(error, doneLatch, "service closing: " + msg);
                    }
                });
            }

            @Override
            public void handleServiceUnavailable(String message) {
                fail(error, doneLatch, "service unavailable: " + message);
            }

            @Override
            public void onConnected(Endpoint endpoint) {
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                doneLatch.countDown();
            }

            @Override
            public void onDisconnected() {
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
    }

    /**
     * Connects a {@link ClientEndpoint} to the SOCKS proxy (not the
     * final destination) and wraps {@code greeting}'s SMTP handler in a
     * {@link SocksClientHandler} tunnelling to {@link
     * DantedTestSupport#DEST_HOST}:{@link DantedTestSupport#DEST_PORT}
     * -- exactly the composable pattern shown in {@code
     * SocksClientHandler}'s own class Javadoc.
     */
    private void connectThroughTunnel(int proxyPort, SocksClientConfig config, RemoteGreeting greeting)
            throws Exception {
        TcpTransportFactory factory = new TcpTransportFactory();
        factory.start();
        Gumdrop gumdrop = Gumdrop.boot();
        SelectorLoop selectorLoop = gumdrop.nextWorkerLoop();
        ClientEndpoint client = new ClientEndpoint(
                factory, selectorLoop, DantedTestSupport.PROXY_HOST, proxyPort);
        client.connect(gumdrop, new SocksClientHandler(
                DantedTestSupport.DEST_HOST, DantedTestSupport.DEST_PORT, config,
                new SmtpClientProtocolHandler(greeting)));
    }

    private void fail(AtomicReference<Exception> error, CountDownLatch doneLatch, String message) {
        error.set(new RuntimeException(message));
        doneLatch.countDown();
    }
}
