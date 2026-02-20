/*
 * SMTPClientIntegrationTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.smtp.client;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.TestCertificateManager;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.client.handler.*;
import org.bluezoo.gumdrop.smtp.client.SMTPClientProtocolHandler;

import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.Assert.*;

/**
 * Integration tests for Gumdrop's SMTP client implementation.
 *
 * <p>Tests SMTP client functionality with real network connections:
 * <ul>
 *   <li>Basic SMTP session (EHLO, MAIL FROM, RCPT TO, DATA, QUIT)</li>
 *   <li>STARTTLS upgrade</li>
 *   <li>SMTPS (implicit TLS)</li>
 *   <li>RSET command</li>
 *   <li>Multiple messages per session</li>
 *   <li>Message content verification</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPClientIntegrationTest extends AbstractServerIntegrationTest {

    private static final int SMTP_PORT = 18025;
    private static final int SMTPS_PORT = 18465;
    private static final String TEST_HOST = "127.0.0.1";
    
    /** Timeout for async operations. */
    private static final int ASYNC_TIMEOUT_SECONDS = 5;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(ASYNC_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();

    private static TestCertificateManager certManager;

    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/smtp-client-test.xml");
    }

    @Override
    protected Level getTestLogLevel() {
        return Level.WARNING;
    }

    @BeforeClass
    public static void setupCertificates() throws Exception {
        File certsDir = new File("test/integration/certs");
        if (!certsDir.exists()) {
            certsDir.mkdirs();
        }

        File caKeystore = new File(certsDir, "ca-keystore.p12");
        if (caKeystore.exists()) {
            caKeystore.delete();
        }

        certManager = new TestCertificateManager(certsDir);
        certManager.generateCA("Test CA", 365);
        certManager.generateServerCertificate("localhost", 365);

        File keystoreFile = new File(certsDir, "test-keystore.p12");
        certManager.saveServerKeystore(keystoreFile, "testpass");
    }

    private AcceptAllService getService() {
        return (AcceptAllService) registry.getComponent("acceptAllService");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Helper that wraps ClientEndpoint + SMTPClientProtocolHandler for v2 API.
     */
    private static class SMTPClientHelper {
        private final ClientEndpoint client;
        private final TCPTransportFactory factory;
        private final int port;
        private boolean secure;
        private javax.net.ssl.SSLContext sslContext;

        SMTPClientHelper(int port) throws Exception {
            this.port = port;
            this.factory = new TCPTransportFactory();
            this.factory.start();
            Gumdrop gumdrop = Gumdrop.getInstance();
            SelectorLoop selectorLoop = gumdrop.nextWorkerLoop();
            this.client = new ClientEndpoint(factory, selectorLoop, TEST_HOST, port);
        }

        void setSecure(boolean secure) {
            this.secure = secure;
        }

        void setSSLContext(javax.net.ssl.SSLContext sslContext) {
            this.sslContext = sslContext;
        }

        void connect(ServerGreeting handler) throws Exception {
            if (secure && sslContext != null) {
                factory.setSecure(true);
                factory.setSSLContext(sslContext);
            } else if (sslContext != null) {
                factory.setSSLContext(sslContext);
            }
            client.connect(new SMTPClientProtocolHandler(handler));
        }
    }

    private SMTPClientHelper createClient(int port) throws Exception {
        return new SMTPClientHelper(port);
    }

    /**
     * Creates an EmailAddress from a string like "user@domain".
     */
    private EmailAddress email(String address) {
        int at = address.indexOf('@');
        if (at < 0) {
            throw new IllegalArgumentException("Invalid email: " + address);
        }
        return new EmailAddress(null, address.substring(0, at), address.substring(at + 1), true);
    }

    /**
     * Creates a simple test message.
     */
    private String createTestMessage(String from, String to, String subject, String body) {
        return String.format(
            "From: %s\r\n" +
            "To: %s\r\n" +
            "Subject: %s\r\n" +
            "Date: Mon, 1 Jan 2025 12:00:00 +0000\r\n" +
            "Message-ID: <test@example.com>\r\n" +
            "\r\n" +
            "%s\r\n",
            from, to, subject, body
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Basic SMTP Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testSimpleMessage() throws Exception {
        AcceptAllService service = getService();
        service.clearMessages();

        SMTPClientHelper client = createClient(SMTP_PORT);

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> queueId = new AtomicReference<>();
        AtomicReference<String> lastStep = new AtomicReference<>("START");

        final String messageContent = createTestMessage(
            "sender@example.com", "recipient@example.com", 
            "Test Subject", "Test body content."
        );

        client.connect(new TestHandler(completeLatch, error) {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                lastStep.set("GREETING");
                assertTrue("Should support ESMTP", esmtp);
                hello.ehlo("test.client.com", new TestEhloHandler(completeLatch, error) {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                                          List<String> authMethods, boolean pipelining) {
                        lastStep.set("EHLO");
                        session.mailFrom(email("sender@example.com"), 
                            new TestMailFromHandler(completeLatch, error) {
                                @Override
                                public void handleMailFromOk(ClientEnvelope envelope) {
                                    lastStep.set("MAIL_FROM");
                                    envelope.rcptTo(email("recipient@example.com"),
                                        new TestRcptToHandler(completeLatch, error) {
                                            @Override
                                            public void handleRcptToOk(ClientEnvelopeReady envelope) {
                                                lastStep.set("RCPT_TO");
                                                envelope.data(new TestDataHandler(completeLatch, error) {
                                                    @Override
                                                    public void handleReadyForData(ClientMessageData data) {
                                                        lastStep.set("DATA");
                                                        ByteBuffer buf = ByteBuffer.wrap(
                                                            messageContent.getBytes(StandardCharsets.UTF_8));
                                                        data.writeContent(buf);
                                                        data.endMessage(new TestMessageHandler(completeLatch, error) {
                                                            @Override
                                                            public void handleMessageAccepted(String id, ClientSession session) {
                                                                lastStep.set("MESSAGE_ACCEPTED");
                                                                queueId.set(id);
                                                                session.quit();
                                                                completeLatch.countDown();
                                                            }
                                                        });
                                                    }
                                                });
                                            }
                                        });
                                }
                            });
                    }
                });
            }
        });

        assertTrue("Should complete within timeout", 
                   completeLatch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        
        if (error.get() != null) {
            throw error.get();
        }

        // Wait for server-side message processing and verify
        pause(200);
        List messages = service.getReceivedMessages();
        assertEquals("Should have received 1 message (lastStep: " + lastStep.get() + ")", 1, messages.size());
        
        // Queue ID is optional - some servers don't provide it
        // Just log if we got one
        if (queueId.get() != null) {
            log("Message queued with ID: " + queueId.get());
        }

        AcceptAllService.ReceivedMessage received = (AcceptAllService.ReceivedMessage) messages.get(0);
        assertEquals("Sender should match", "sender@example.com", received.getSender().getAddress());
        assertEquals("Should have 1 recipient", 1, received.getRecipients().size());
        assertEquals("Recipient should match", "recipient@example.com",
                     ((EmailAddress) received.getRecipients().get(0)).getAddress());
        assertTrue("Content should contain body", 
                   received.getContentAsString().contains("Test body content."));
    }

    @Test
    public void testMultipleRecipients() throws Exception {
        AcceptAllService service = getService();
        service.clearMessages();

        SMTPClientHelper client = createClient(SMTP_PORT);

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        client.connect(new TestHandler(completeLatch, error) {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo("test.client.com", new TestEhloHandler(completeLatch, error) {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                                          List<String> authMethods, boolean pipelining) {
                        session.mailFrom(email("sender@example.com"), 
                            new TestMailFromHandler(completeLatch, error) {
                                @Override
                                public void handleMailFromOk(ClientEnvelope envelope) {
                                    envelope.rcptTo(email("first@example.com"),
                                        new TestRcptToHandler(completeLatch, error) {
                                            @Override
                                            public void handleRcptToOk(ClientEnvelopeReady ready) {
                                                ready.rcptTo(email("second@example.com"),
                                                    new TestRcptToHandler(completeLatch, error) {
                                                        @Override
                                                        public void handleRcptToOk(ClientEnvelopeReady ready) {
                                                            ready.rcptTo(email("third@example.com"),
                                                                new TestRcptToHandler(completeLatch, error) {
                                                                    @Override
                                                                    public void handleRcptToOk(ClientEnvelopeReady ready) {
                                                                        ready.data(new TestDataHandler(completeLatch, error) {
                                                                            @Override
                                                                            public void handleReadyForData(ClientMessageData data) {
                                                                                data.writeContent(ByteBuffer.wrap(
                                                                                    "Subject: Test\r\n\r\nBody\r\n".getBytes()));
                                                                                data.endMessage(new TestMessageHandler(completeLatch, error) {
                                                                                    @Override
                                                                                    public void handleMessageAccepted(String id, ClientSession session) {
                                                                                        session.quit();
                                                                                        completeLatch.countDown();
                                                                                    }
                                                                                });
                                                                            }
                                                                        });
                                                                    }
                                                                });
                                                        }
                                                    });
                                            }
                                        });
                                }
                            });
                    }
                });
            }
        });

        assertTrue("Should complete within timeout",
                   completeLatch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        
        if (error.get() != null) {
            throw error.get();
        }

        pause(200);
        List messages = service.getReceivedMessages();
        assertEquals("Should have 1 message", 1, messages.size());
        assertEquals("Should have 3 recipients", 3,
                ((AcceptAllService.ReceivedMessage) messages.get(0)).getRecipients().size());
    }

    @Test
    public void testRsetCommand() throws Exception {
        AcceptAllService service = getService();
        service.clearMessages();

        SMTPClientHelper client = createClient(SMTP_PORT);

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean rsetSucceeded = new AtomicBoolean(false);

        client.connect(new TestHandler(completeLatch, error) {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo("test.client.com", new TestEhloHandler(completeLatch, error) {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                                          List<String> authMethods, boolean pipelining) {
                        // Start a transaction
                        session.mailFrom(email("sender@example.com"), 
                            new TestMailFromHandler(completeLatch, error) {
                                @Override
                                public void handleMailFromOk(ClientEnvelope envelope) {
                                    envelope.rcptTo(email("recipient@example.com"),
                                        new TestRcptToHandler(completeLatch, error) {
                                            @Override
                                            public void handleRcptToOk(ClientEnvelopeReady ready) {
                                                // RSET instead of DATA
                                                ready.rset(new ServerRsetReplyHandler() {
                                                    @Override
                                                    public void handleResetOk(ClientSession session) {
                                                        rsetSucceeded.set(true);
                                                        // New transaction
                                                        session.mailFrom(email("new-sender@example.com"),
                                                            new TestMailFromHandler(completeLatch, error) {
                                                                @Override
                                                                public void handleMailFromOk(ClientEnvelope envelope) {
                                                                    envelope.rcptTo(email("new-recipient@example.com"),
                                                                        new TestRcptToHandler(completeLatch, error) {
                                                                            @Override
                                                                            public void handleRcptToOk(ClientEnvelopeReady ready) {
                                                                                ready.data(new TestDataHandler(completeLatch, error) {
                                                                                    @Override
                                                                                    public void handleReadyForData(ClientMessageData data) {
                                                                                        data.writeContent(ByteBuffer.wrap("Test\r\n".getBytes()));
                                                                                        data.endMessage(new TestMessageHandler(completeLatch, error) {
                                                                                            @Override
                                                                                            public void handleMessageAccepted(String id, ClientSession s) {
                                                                                                s.quit();
                                                                                                completeLatch.countDown();
                                                                                            }
                                                                                        });
                                                                                    }
                                                                                });
                                                                            }
                                                                        });
                                                                }
                                                            });
                                                    }
                                                    @Override
                                                    public void handleServiceClosing(String message) {
                                                        error.set(new SMTPException("Service closing: " + message));
                                                        completeLatch.countDown();
                                                    }
                                                });
                                            }
                                        });
                                }
                            });
                    }
                });
            }
        });

        assertTrue("Should complete within timeout",
                   completeLatch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        
        if (error.get() != null) {
            throw error.get();
        }

        assertTrue("RSET should succeed", rsetSucceeded.get());

        pause(200);
        List messages = service.getReceivedMessages();
        assertEquals("Should have 1 message (first was reset)", 1, messages.size());
        assertEquals("Sender should be from second transaction", 
                     "new-sender@example.com",
                     ((AcceptAllService.ReceivedMessage) messages.get(0)).getSender().getAddress());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMTPS (Implicit TLS) Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testSmtpsConnection() throws Exception {
        AcceptAllService service = getService();
        service.clearMessages();

        SMTPClientHelper client = createClient(SMTPS_PORT);
        client.setSecure(true);
        client.setSSLContext(certManager.createClientSSLContext());

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean tlsConfirmed = new AtomicBoolean(false);

        client.connect(new TestHandler(completeLatch, error) {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo("test.client.com", new TestEhloHandler(completeLatch, error) {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                                          List<String> authMethods, boolean pipelining) {
                        session.mailFrom(email("secure@example.com"), 
                            new TestMailFromHandler(completeLatch, error) {
                                @Override
                                public void handleMailFromOk(ClientEnvelope envelope) {
                                    envelope.rcptTo(email("recipient@example.com"),
                                        new TestRcptToHandler(completeLatch, error) {
                                            @Override
                                            public void handleRcptToOk(ClientEnvelopeReady ready) {
                                                ready.data(new TestDataHandler(completeLatch, error) {
                                                    @Override
                                                    public void handleReadyForData(ClientMessageData data) {
                                                        data.writeContent(ByteBuffer.wrap(
                                                            "Subject: Secure\r\n\r\nSecure body\r\n".getBytes()));
                                                        data.endMessage(new TestMessageHandler(completeLatch, error) {
                                                            @Override
                                                            public void handleMessageAccepted(String id, ClientSession session) {
                                                                session.quit();
                                                                completeLatch.countDown();
                                                            }
                                                        });
                                                    }
                                                });
                                            }
                                        });
                                }
                            });
                    }
                });
            }

            @Override
            public void onConnected(Endpoint endpoint) {
                tlsConfirmed.set(endpoint.isSecure());
            }
        });

        assertTrue("Should complete within timeout",
                   completeLatch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        
        if (error.get() != null) {
            throw error.get();
        }

        assertTrue("Connection should be TLS", tlsConfirmed.get());

        pause(200);
        List messages = service.getReceivedMessages();
        assertEquals("Should have 1 message", 1, messages.size());
        assertTrue("Server should see TLS active",
                ((AcceptAllService.ReceivedMessage) messages.get(0)).isTlsActive());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STARTTLS Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testStarttlsUpgrade() throws Exception {
        AcceptAllService service = getService();
        service.clearMessages();

        SMTPClientHelper client = createClient(SMTP_PORT);
        client.setSSLContext(certManager.createClientSSLContext());

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean starttlsSucceeded = new AtomicBoolean(false);

        client.connect(new TestHandler(completeLatch, error) {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo("test.client.com", new TestEhloHandler(completeLatch, error) {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                                          List<String> authMethods, boolean pipelining) {
                        if (!starttls) {
                            error.set(new SMTPException("Server does not offer STARTTLS"));
                            completeLatch.countDown();
                            return;
                        }
                        // Upgrade to TLS
                        session.starttls(new ServerStarttlsReplyHandler() {
                            @Override
                            public void handleTlsEstablished(ClientPostTls postTls) {
                                starttlsSucceeded.set(true);
                                // Must re-issue EHLO after STARTTLS
                                postTls.ehlo("test.client.com", new TestEhloHandler(completeLatch, error) {
                                    @Override
                                    public void handleEhlo(ClientSession session, boolean st, long sz,
                                                          List<String> auth, boolean pipe) {
                                        session.mailFrom(email("sender@example.com"), 
                                            new TestMailFromHandler(completeLatch, error) {
                                                @Override
                                                public void handleMailFromOk(ClientEnvelope envelope) {
                                                    envelope.rcptTo(email("recipient@example.com"),
                                                        new TestRcptToHandler(completeLatch, error) {
                                                            @Override
                                                            public void handleRcptToOk(ClientEnvelopeReady ready) {
                                                                ready.data(new TestDataHandler(completeLatch, error) {
                                                                    @Override
                                                                    public void handleReadyForData(ClientMessageData data) {
                                                                        data.writeContent(ByteBuffer.wrap(
                                                                            "Subject: TLS\r\n\r\nBody\r\n".getBytes()));
                                                                        data.endMessage(new TestMessageHandler(completeLatch, error) {
                                                                            @Override
                                                                            public void handleMessageAccepted(String id, ClientSession s) {
                                                                                s.quit();
                                                                                completeLatch.countDown();
                                                                            }
                                                                        });
                                                                    }
                                                                });
                                                            }
                                                        });
                                                }
                                            });
                                    }
                                });
                            }
                            @Override
                            public void handleTlsUnavailable(ClientSession session) {
                                error.set(new SMTPException("STARTTLS unavailable"));
                                completeLatch.countDown();
                            }
                            @Override
                            public void handlePermanentFailure(String message) {
                                error.set(new SMTPException("STARTTLS perm: " + message));
                                completeLatch.countDown();
                            }
                            @Override
                            public void handleServiceClosing(String message) {
                                error.set(new SMTPException("Service closing: " + message));
                                completeLatch.countDown();
                            }
                        });
                    }
                });
            }
        });

        assertTrue("Should complete within timeout",
                   completeLatch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        
        if (error.get() != null) {
            throw error.get();
        }

        assertTrue("STARTTLS should succeed", starttlsSucceeded.get());

        pause(200);
        List messages = service.getReceivedMessages();
        assertEquals("Should have 1 message", 1, messages.size());
        assertTrue("Server should see TLS active after STARTTLS",
                ((AcceptAllService.ReceivedMessage) messages.get(0)).isTlsActive());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multiple Messages Test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testMultipleMessagesPerSession() throws Exception {
        AcceptAllService service = getService();
        service.clearMessages();

        SMTPClientHelper client = createClient(SMTP_PORT);

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<Integer> messagesSent = new AtomicReference<>(0);

        client.connect(new TestHandler(completeLatch, error) {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo("test.client.com", new TestEhloHandler(completeLatch, error) {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                                          List<String> authMethods, boolean pipelining) {
                        sendMessage(session, 1);
                    }

                    private void sendMessage(ClientSession session, int messageNum) {
                        if (messageNum > 3) {
                            session.quit();
                            completeLatch.countDown();
                            return;
                        }

                        session.mailFrom(email("sender" + messageNum + "@example.com"), 
                            new TestMailFromHandler(completeLatch, error) {
                                @Override
                                public void handleMailFromOk(ClientEnvelope envelope) {
                                    envelope.rcptTo(email("recipient" + messageNum + "@example.com"),
                                        new TestRcptToHandler(completeLatch, error) {
                                            @Override
                                            public void handleRcptToOk(ClientEnvelopeReady ready) {
                                                ready.data(new TestDataHandler(completeLatch, error) {
                                                    @Override
                                                    public void handleReadyForData(ClientMessageData data) {
                                                        data.writeContent(ByteBuffer.wrap(
                                                            ("Subject: Message " + messageNum + "\r\n\r\nBody\r\n").getBytes()));
                                                        data.endMessage(new TestMessageHandler(completeLatch, error) {
                                                            @Override
                                                            public void handleMessageAccepted(String id, ClientSession nextSession) {
                                                                messagesSent.set(messageNum);
                                                                sendMessage(nextSession, messageNum + 1);
                                                            }
                                                        });
                                                    }
                                                });
                                            }
                                        });
                                }
                            });
                    }
                });
            }
        });

        assertTrue("Should complete within timeout",
                   completeLatch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        
        if (error.get() != null) {
            throw error.get();
        }

        assertEquals("Should have sent 3 messages", Integer.valueOf(3), messagesSent.get());

        pause(200);
        List messages = service.getReceivedMessages();
        assertEquals("Should have 3 messages", 3, messages.size());
        
        for (int i = 0; i < 3; i++) {
            assertEquals("Sender " + (i+1) + " should match", 
                        "sender" + (i+1) + "@example.com", 
                        ((AcceptAllService.ReceivedMessage) messages.get(i)).getSender().getAddress());
        }
    }

    @Test
    public void testNullSender() throws Exception {
        AcceptAllService service = getService();
        service.clearMessages();

        SMTPClientHelper client = createClient(SMTP_PORT);

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        client.connect(new TestHandler(completeLatch, error) {
            @Override
            public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
                hello.ehlo("test.client.com", new TestEhloHandler(completeLatch, error) {
                    @Override
                    public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                                          List<String> authMethods, boolean pipelining) {
                        // Null sender for bounce message
                        session.mailFrom(null, new TestMailFromHandler(completeLatch, error) {
                            @Override
                            public void handleMailFromOk(ClientEnvelope envelope) {
                                envelope.rcptTo(email("recipient@example.com"),
                                    new TestRcptToHandler(completeLatch, error) {
                                        @Override
                                        public void handleRcptToOk(ClientEnvelopeReady ready) {
                                            ready.data(new TestDataHandler(completeLatch, error) {
                                                @Override
                                                public void handleReadyForData(ClientMessageData data) {
                                                    data.writeContent(ByteBuffer.wrap(
                                                        "Subject: Bounce\r\n\r\nBounce message\r\n".getBytes()));
                                                    data.endMessage(new TestMessageHandler(completeLatch, error) {
                                                        @Override
                                                        public void handleMessageAccepted(String id, ClientSession s) {
                                                            s.quit();
                                                            completeLatch.countDown();
                                                        }
                                                    });
                                                }
                                            });
                                        }
                                    });
                            }
                        });
                    }
                });
            }
        });

        assertTrue("Should complete within timeout",
                   completeLatch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        
        if (error.get() != null) {
            throw error.get();
        }

        pause(200);
        List messages = service.getReceivedMessages();
        assertEquals("Should have 1 message", 1, messages.size());
        assertNull("Sender should be null for bounce",
                ((AcceptAllService.ReceivedMessage) messages.get(0)).getSender());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test Handler Base Classes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Base handler for tests with default error handling.
     */
    private abstract static class TestHandler implements ServerGreeting {
        protected final CountDownLatch latch;
        protected final AtomicReference<Exception> error;

        TestHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleServiceUnavailable(String message) {
            error.set(new SMTPException("Service unavailable: " + message));
            latch.countDown();
        }

        @Override
        public void onConnected(Endpoint endpoint) {}

        @Override
        public void onDisconnected() {}

        @Override
        public void onError(Exception e) {
            error.set(e);
            latch.countDown();
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {}
    }

    private abstract static class TestEhloHandler implements ServerEhloReplyHandler {
        protected final CountDownLatch latch;
        protected final AtomicReference<Exception> error;

        TestEhloHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleEhloNotSupported(ClientHelloState hello) {
            error.set(new SMTPException("EHLO not supported"));
            latch.countDown();
        }

        @Override
        public void handlePermanentFailure(String message) {
            error.set(new SMTPException("EHLO rejected: " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new SMTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestMailFromHandler implements ServerMailFromReplyHandler {
        protected final CountDownLatch latch;
        protected final AtomicReference<Exception> error;

        TestMailFromHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleTemporaryFailure(ClientSession session) {
            error.set(new SMTPException("MAIL FROM temporary failure"));
            latch.countDown();
        }

        @Override
        public void handlePermanentFailure(String message) {
            error.set(new SMTPException("MAIL FROM permanent failure: " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new SMTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestRcptToHandler implements ServerRcptToReplyHandler {
        protected final CountDownLatch latch;
        protected final AtomicReference<Exception> error;

        TestRcptToHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleTemporaryFailure(ClientEnvelopeState state) {
            error.set(new SMTPException("RCPT TO temporary failure"));
            latch.countDown();
        }

        @Override
        public void handleRecipientRejected(ClientEnvelopeState state) {
            error.set(new SMTPException("Recipient rejected"));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new SMTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestDataHandler implements ServerDataReplyHandler {
        protected final CountDownLatch latch;
        protected final AtomicReference<Exception> error;

        TestDataHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleTemporaryFailure(ClientEnvelopeReady envelope) {
            error.set(new SMTPException("DATA temporary failure"));
            latch.countDown();
        }

        @Override
        public void handlePermanentFailure(String message) {
            error.set(new SMTPException("DATA permanent failure: " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new SMTPException("Service closing: " + message));
            latch.countDown();
        }
    }

    private abstract static class TestMessageHandler implements ServerMessageReplyHandler {
        protected final CountDownLatch latch;
        protected final AtomicReference<Exception> error;

        TestMessageHandler(CountDownLatch latch, AtomicReference<Exception> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void handleTemporaryFailure(ClientSession session) {
            error.set(new SMTPException("Message temporary failure"));
            latch.countDown();
        }

        @Override
        public void handlePermanentFailure(String message, ClientSession session) {
            error.set(new SMTPException("Message permanent failure: " + message));
            latch.countDown();
        }

        @Override
        public void handleServiceClosing(String message) {
            error.set(new SMTPException("Service closing: " + message));
            latch.countDown();
        }
    }
}
