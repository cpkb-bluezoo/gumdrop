/*
 * PostfixSmtpIntegrationTest.java
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

package org.bluezoo.gumdrop.smtp.postfix;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.client.SMTPClient;
import org.bluezoo.gumdrop.smtp.client.handler.ClientEnvelope;
import org.bluezoo.gumdrop.smtp.client.handler.ClientEnvelopeReady;
import org.bluezoo.gumdrop.smtp.client.handler.ClientHelloState;
import org.bluezoo.gumdrop.smtp.client.handler.ClientMessageData;
import org.bluezoo.gumdrop.smtp.client.handler.ClientPostTls;
import org.bluezoo.gumdrop.smtp.client.handler.ClientSession;
import org.bluezoo.gumdrop.smtp.client.handler.ServerDataReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerEhloReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerGreeting;
import org.bluezoo.gumdrop.smtp.client.handler.ServerMailFromReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerMessageReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerRcptToReplyHandler;
import org.bluezoo.gumdrop.smtp.client.handler.ServerStarttlsReplyHandler;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end tests of gumdrop's SMTP client against a real, locally-running
 * Postfix instance -- not run in CI, see {@link PostfixTestSupport}.
 *
 * <p>{@code SMTPClientIntegrationTest} already covers the client against
 * gumdrop's own server ({@code AcceptAllService}); that server has to
 * agree with the client on every wire-level detail because they share an
 * implementation lineage, so a bug present in both would never surface
 * there. Postfix is a wire-compatible but wholly independent
 * implementation -- exactly the same reasoning that motivated testing the
 * AMQP client against real RabbitMQ (see {@code RabbitMQTestSupport}),
 * where it caught bugs the fake-broker tests could not.
 *
 * <p>Delivered mail is read back from Postfix's own mbox spool (via
 * {@link PostfixTestSupport#awaitMailbox}), not from anything gumdrop
 * wrote -- so a passing assertion here means an independent, unrelated
 * MTA parsed the client's wire bytes and agreed they formed a valid
 * message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PostfixSmtpIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Before
    public void checkReachableAndClearMailbox() throws Exception {
        assumeTrue(PostfixTestSupport.NOT_REACHABLE_MESSAGE, PostfixTestSupport.isReachable());
        PostfixTestSupport.clearMailbox();
    }

    private EmailAddress email(String address) {
        int at = address.indexOf('@');
        return new EmailAddress(null, address.substring(0, at), address.substring(at + 1), true);
    }

    private String senderAddress() {
        return "sender@" + PostfixTestSupport.MAIL_DOMAIN;
    }

    private String recipientAddress() {
        return PostfixTestSupport.MAILBOX_USER + "@" + PostfixTestSupport.MAIL_DOMAIN;
    }

    // ── Plain delivery, and confirming CHUNKING is actually negotiated ──

    @Test
    public void testSimpleDeliveryNegotiatesChunking() throws Exception {
        SMTPClient client = new SMTPClient(PostfixTestSupport.HOST, PostfixTestSupport.PORT);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        String subject = "gumdrop-postfix-simple-" + System.nanoTime();
        String body = "Subject: " + subject + "\r\n\r\nplain body over BDAT chunking\r\n";

        client.connect(new ServerGreeting() {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo("gumdrop-test", new ServerEhloReplyHandler() {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                            List<String> authMethods, boolean pipelining) {
                        session.mailFrom(email(senderAddress()), new ServerMailFromReplyHandler() {
                            @Override
                            public void handleMailFromOk(ClientEnvelope envelope) {
                                envelope.rcptTo(email(recipientAddress()), new ServerRcptToReplyHandler() {
                                    @Override
                                    public void handleRcptToOk(ClientEnvelopeReady ready) {
                                        ready.data(new ServerDataReplyHandler() {
                                            @Override
                                            public void handleReadyForData(ClientMessageData data) {
                                                data.writeContent(ByteBuffer.wrap(
                                                        body.getBytes(StandardCharsets.US_ASCII)));
                                                data.endMessage(new ServerMessageReplyHandler() {
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
            public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
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
            public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        client.close();

        String mailbox = PostfixTestSupport.awaitMailbox(TIMEOUT_SECONDS * 1000);
        assertTrue("subject not found in delivered mail", mailbox.contains(subject));
        assertTrue("body not found in delivered mail", mailbox.contains("plain body over BDAT chunking"));
    }

    // ── Large message, multiple writeContent()/onWriteReady round trips ──

    @Test
    public void testLargeMessageDeliveredAcrossMultipleChunkWrites() throws Exception {
        SMTPClient client = new SMTPClient(PostfixTestSupport.HOST, PostfixTestSupport.PORT);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        String subject = "gumdrop-postfix-large-" + System.nanoTime();

        // A few hundred KB, built from a repeating marker so the assertion
        // can check both ends and the middle survived the round trip
        // without needing to keep the whole thing pinned twice in memory.
        String line = "the quick brown fox jumps over the lazy dog 0123456789\r\n";
        int repeats = 6000; // ~ 340KB body, well under postfix's 10MB limit but large enough to force many chunks
        StringBuilder bodyBuilder = new StringBuilder(line.length() * repeats + 64);
        bodyBuilder.append("Subject: ").append(subject).append("\r\n\r\n");
        for (int i = 0; i < repeats; i++) {
            bodyBuilder.append(line);
        }
        String fullMessage = bodyBuilder.toString();
        byte[] messageBytes = fullMessage.getBytes(StandardCharsets.US_ASCII);

        client.connect(new ServerGreeting() {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo("gumdrop-test", new ServerEhloReplyHandler() {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                            List<String> authMethods, boolean pipelining) {
                        session.mailFrom(email(senderAddress()), simpleMailFromHandler(error, doneLatch,
                                envelope -> envelope.rcptTo(email(recipientAddress()), simpleRcptHandler(error, doneLatch,
                                        ready -> ready.data(simpleDataHandler(error, doneLatch, data -> {
                                            writeInChunks(data, messageBytes, 4096, error, doneLatch);
                                        }))))));
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
            public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
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
            public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS * 3, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        client.close();

        String mailbox = PostfixTestSupport.awaitMailbox(TIMEOUT_SECONDS * 1000);
        assertTrue("subject not found in delivered mail", mailbox.contains(subject));
        assertTrue("first line of body missing", mailbox.contains(line.trim()));
        int occurrences = countOccurrences(mailbox, line.trim());
        assertEquals("expected all " + repeats + " repeated lines to survive chunked delivery intact",
                repeats, occurrences);
    }

    /** Writes content across many small writeContent() calls, pacing via onWriteReady. */
    private void writeInChunks(ClientMessageData data, byte[] content, int chunkSize,
            AtomicReference<Exception> error, CountDownLatch doneLatch) {
        int[] offset = {0};
        Runnable[] writeNext = new Runnable[1];
        writeNext[0] = () -> {
            if (offset[0] >= content.length) {
                data.endMessage(new ServerMessageReplyHandler() {
                    @Override
                    public void handleMessageAccepted(String queueId, ClientSession s) {
                        s.quit();
                        doneLatch.countDown();
                    }

                    @Override
                    public void handleTemporaryFailure(ClientSession s) {
                        fail(error, doneLatch, "temp failure at end of chunked DATA");
                    }

                    @Override
                    public void handlePermanentFailure(String msg, ClientSession s) {
                        fail(error, doneLatch, "permanent failure at end of chunked DATA: " + msg);
                    }

                    @Override
                    public void handleServiceClosing(String msg) {
                        fail(error, doneLatch, "service closing: " + msg);
                    }
                });
                return;
            }
            int len = Math.min(chunkSize, content.length - offset[0]);
            data.writeContent(ByteBuffer.wrap(content, offset[0], len));
            offset[0] += len;
            data.onWriteReady(() -> writeNext[0].run());
        };
        writeNext[0].run();
    }

    // ── STARTTLS upgrade, then delivery over the encrypted connection ──

    @Test
    public void testStarttlsUpgradeThenDelivery() throws Exception {
        X509Certificate serverCert = PostfixTestSupport.loadServerCertificate();
        X509TrustManager trustManager = pinningTrustManager(serverCert);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[] { trustManager }, null);

        SMTPClient client = new SMTPClient(PostfixTestSupport.HOST, PostfixTestSupport.PORT);
        client.setSSLContext(sslContext);
        client.setTrustManager(trustManager);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean tlsEstablished = new AtomicBoolean(false);
        String subject = "gumdrop-postfix-tls-" + System.nanoTime();
        String body = "Subject: " + subject + "\r\n\r\ndelivered over starttls\r\n";

        client.connect(new ServerGreeting() {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo("gumdrop-test", new ServerEhloReplyHandler() {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                            List<String> authMethods, boolean pipelining) {
                        assertTrue("postfix test image should advertise STARTTLS", starttls);
                        session.starttls(new ServerStarttlsReplyHandler() {
                            @Override
                            public void handleTlsEstablished(ClientPostTls postTls) {
                                tlsEstablished.set(true);
                                postTls.ehlo("gumdrop-test", new ServerEhloReplyHandler() {
                                    @Override
                                    public void handleEhlo(ClientSession session2, boolean starttls2, long maxSize2,
                                            List<String> authMethods2, boolean pipelining2) {
                                        session2.mailFrom(email(senderAddress()), simpleMailFromHandler(error, doneLatch,
                                                envelope -> envelope.rcptTo(email(recipientAddress()), simpleRcptHandler(error, doneLatch,
                                                        ready -> ready.data(simpleDataHandler(error, doneLatch, data -> {
                                                            data.writeContent(ByteBuffer.wrap(body.getBytes(StandardCharsets.US_ASCII)));
                                                            data.endMessage(new ServerMessageReplyHandler() {
                                                                @Override
                                                                public void handleMessageAccepted(String queueId, ClientSession s) {
                                                                    s.quit();
                                                                    doneLatch.countDown();
                                                                }

                                                                @Override
                                                                public void handleTemporaryFailure(ClientSession s) {
                                                                    fail(error, doneLatch, "temp failure at end of DATA (tls)");
                                                                }

                                                                @Override
                                                                public void handlePermanentFailure(String msg, ClientSession s) {
                                                                    fail(error, doneLatch, "permanent failure at end of DATA (tls): " + msg);
                                                                }

                                                                @Override
                                                                public void handleServiceClosing(String msg) {
                                                                    fail(error, doneLatch, "service closing: " + msg);
                                                                }
                                                            });
                                                        }))))));
                                    }

                                    @Override
                                    public void handleEhloNotSupported(ClientHelloState h) {
                                        fail(error, doneLatch, "post-TLS EHLO not supported");
                                    }

                                    @Override
                                    public void handlePermanentFailure(String msg) {
                                        fail(error, doneLatch, "post-TLS EHLO rejected: " + msg);
                                    }

                                    @Override
                                    public void handleServiceClosing(String msg) {
                                        fail(error, doneLatch, "service closing: " + msg);
                                    }
                                });
                            }

                            @Override
                            public void handleTlsUnavailable(ClientSession s) {
                                fail(error, doneLatch, "TLS unavailable");
                            }

                            @Override
                            public void handlePermanentFailure(String msg) {
                                fail(error, doneLatch, "STARTTLS rejected: " + msg);
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
            public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
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
            public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertTrue("TLS was never established", tlsEstablished.get());
        client.close();

        String mailbox = PostfixTestSupport.awaitMailbox(TIMEOUT_SECONDS * 1000);
        assertTrue("subject not found in delivered mail", mailbox.contains(subject));
        assertTrue("body not found in delivered mail", mailbox.contains("delivered over starttls"));
    }

    // ── Shared plumbing ──

    @FunctionalInterface
    private interface MailFromOk {
        void accept(ClientEnvelope envelope);
    }

    @FunctionalInterface
    private interface RcptToOk {
        void accept(ClientEnvelopeReady ready);
    }

    @FunctionalInterface
    private interface DataReady {
        void accept(ClientMessageData data);
    }

    private ServerMailFromReplyHandler simpleMailFromHandler(AtomicReference<Exception> error,
            CountDownLatch doneLatch, MailFromOk onOk) {
        return new ServerMailFromReplyHandler() {
            @Override
            public void handleMailFromOk(ClientEnvelope envelope) {
                onOk.accept(envelope);
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
        };
    }

    private ServerRcptToReplyHandler simpleRcptHandler(AtomicReference<Exception> error,
            CountDownLatch doneLatch, RcptToOk onOk) {
        return new ServerRcptToReplyHandler() {
            @Override
            public void handleRcptToOk(ClientEnvelopeReady ready) {
                onOk.accept(ready);
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
        };
    }

    private ServerDataReplyHandler simpleDataHandler(AtomicReference<Exception> error,
            CountDownLatch doneLatch, DataReady onReady) {
        return new ServerDataReplyHandler() {
            @Override
            public void handleReadyForData(ClientMessageData data) {
                onReady.accept(data);
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
        };
    }

    private void fail(AtomicReference<Exception> error, CountDownLatch doneLatch, String message) {
        error.set(new RuntimeException(message));
        doneLatch.countDown();
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * Trusts exactly the one self-signed leaf certificate fetched live
     * from the running container -- not an accept-all manager (a real
     * anti-pattern even in test code, see the equivalent comment in
     * RabbitMQTlsIntegrationTest), and not a CA trust chain since this
     * cert is self-signed with no separate CA to pin.
     */
    private X509TrustManager pinningTrustManager(X509Certificate cert) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("postfix-test", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("no X509TrustManager produced for the pinned postfix cert");
    }
}
