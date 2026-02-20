/*
 * LocalDeliveryIntegrationTest.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxFactory;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.client.SMTPClientProtocolHandler;
import org.bluezoo.gumdrop.smtp.client.handler.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Integration test for LocalDeliveryHandler.
 *
 * <p>This test starts an SMTP server with the LocalDeliveryHandler configured
 * and uses the Gumdrop SMTPClient to send messages, verifying end-to-end delivery
 * to local mailboxes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LocalDeliveryIntegrationTest extends AbstractServerIntegrationTest {

    private static final int TEST_PORT = 12698;
    private static final String LOCAL_DOMAIN = "testlocal.example";
    private static final String TEST_USER = "testuser";
    private static final String TEST_USER2 = "testuser2";

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    private Path mailboxDir;
    private MboxMailboxFactory mailboxFactory;

    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/local-delivery-test.xml");
    }

    @Before
    public void setUpMailbox() throws Exception {
        // Ensure mailbox directory exists
        mailboxDir = Paths.get("test/integration/mailbox/local-delivery").toAbsolutePath();
        Files.createDirectories(mailboxDir);

        // Create user directories
        createUserDirectory(TEST_USER);
        createUserDirectory(TEST_USER2);

        // Create mailbox factory for verification
        mailboxFactory = new MboxMailboxFactory(mailboxDir.toFile());
    }

    private void createUserDirectory(String user) throws Exception {
        Path userDir = mailboxDir.resolve(user);
        Files.createDirectories(userDir);

        // Delete any existing mailbox file from previous test runs
        Path inboxPath = userDir.resolve("INBOX.mbox");
        Files.deleteIfExists(inboxPath);
        Path indexPath = userDir.resolve("INBOX.mbox.gidx");
        Files.deleteIfExists(indexPath);
    }

    // ============== End-to-End Delivery Tests ==============

    @Test
    public void testLocalDeliveryViaSmtp() throws Exception {
        TestMailHandler handler = new TestMailHandler();
        handler.addRecipient(TEST_USER + "@" + LOCAL_DOMAIN);
        handler.setSubject("Test Subject via SMTP");
        handler.setBody("This message was delivered via SMTP.");

        TCPTransportFactory factory = new TCPTransportFactory();
        factory.start();
        ClientEndpoint client = new ClientEndpoint(factory, "127.0.0.1", TEST_PORT);
        client.connect(new SMTPClientProtocolHandler(handler));

        // Wait for the transaction to complete
        assertTrue("Transaction should complete within timeout",
                handler.awaitCompletion(10, TimeUnit.SECONDS));
        assertTrue("Message should be accepted", handler.wasAccepted());
        assertNull("No error should occur", handler.getError());

        // Wait a bit for mailbox to be written and closed
        Thread.sleep(200);

        // Verify message was delivered to mailbox
        MailboxStore store = mailboxFactory.createStore();
        try {
            store.open(TEST_USER);
            Mailbox mailbox = store.openMailbox("INBOX", true);
            try {
                assertEquals("Mailbox should have 1 message", 1, mailbox.getMessageCount());

                // Read the message content
                String content = readMessageContent(mailbox, 1);
                assertTrue("Message should contain subject",
                        content.contains("Subject: Test Subject via SMTP"));
                assertTrue("Message should contain body",
                        content.contains("This message was delivered via SMTP."));

            } finally {
                mailbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    @Test
    public void testRejectNonLocalRecipient() throws Exception {
        TestMailHandler handler = new TestMailHandler();
        handler.addRecipient("user@nonlocal.example");
        handler.setSubject("Test");
        handler.setBody("Body");
        handler.setExpectRecipientRejection(true);

        TCPTransportFactory factory = new TCPTransportFactory();
        factory.start();
        ClientEndpoint client = new ClientEndpoint(factory, "127.0.0.1", TEST_PORT);
        client.connect(new SMTPClientProtocolHandler(handler));

        assertTrue("Transaction should complete within timeout",
                handler.awaitCompletion(10, TimeUnit.SECONDS));
        assertTrue("Recipient should be rejected", handler.wasRecipientRejected());
    }

    @Test
    public void testMultipleRecipientsLocalDelivery() throws Exception {
        TestMailHandler handler = new TestMailHandler();
        handler.addRecipient(TEST_USER + "@" + LOCAL_DOMAIN);
        handler.addRecipient(TEST_USER2 + "@" + LOCAL_DOMAIN);
        handler.setSubject("Multi-recipient Test");
        handler.setBody("This message was sent to multiple recipients.");

        TCPTransportFactory factory = new TCPTransportFactory();
        factory.start();
        ClientEndpoint client = new ClientEndpoint(factory, "127.0.0.1", TEST_PORT);
        client.connect(new SMTPClientProtocolHandler(handler));

        assertTrue("Transaction should complete within timeout",
                handler.awaitCompletion(10, TimeUnit.SECONDS));
        assertTrue("Message should be accepted", handler.wasAccepted());

        // Wait for delivery
        Thread.sleep(200);

        // Verify both mailboxes have the message
        verifyMailboxHasMessage(TEST_USER, "Multi-recipient Test");
        verifyMailboxHasMessage(TEST_USER2, "Multi-recipient Test");
    }

    @Test
    public void testMultipleTransactionsOnSameConnection() throws Exception {
        // This test sends two messages on the same connection
        MultiTransactionHandler handler = new MultiTransactionHandler(2);
        handler.setRecipient(TEST_USER + "@" + LOCAL_DOMAIN);
        handler.setSubjects(new String[]{"First Message", "Second Message"});
        handler.setBodies(new String[]{"First body.", "Second body."});

        TCPTransportFactory factory = new TCPTransportFactory();
        factory.start();
        ClientEndpoint client = new ClientEndpoint(factory, "127.0.0.1", TEST_PORT);
        client.connect(new SMTPClientProtocolHandler(handler));

        assertTrue("All transactions should complete within timeout",
                handler.awaitCompletion(10, TimeUnit.SECONDS));
        assertEquals("Both messages should be accepted", 2, handler.getAcceptedCount());

        // Wait for delivery
        Thread.sleep(200);

        // Verify mailbox has 2 messages
        MailboxStore store = mailboxFactory.createStore();
        try {
            store.open(TEST_USER);
            Mailbox mailbox = store.openMailbox("INBOX", true);
            try {
                assertEquals("Mailbox should have 2 messages", 2, mailbox.getMessageCount());
            } finally {
                mailbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    @Test
    public void testRsetClearsTransaction() throws Exception {
        RsetTestHandler handler = new RsetTestHandler();
        handler.setRecipient(TEST_USER + "@" + LOCAL_DOMAIN);

        TCPTransportFactory factory = new TCPTransportFactory();
        factory.start();
        ClientEndpoint client = new ClientEndpoint(factory, "127.0.0.1", TEST_PORT);
        client.connect(new SMTPClientProtocolHandler(handler));

        assertTrue("Test should complete within timeout",
                handler.awaitCompletion(10, TimeUnit.SECONDS));
        assertTrue("RSET should succeed", handler.wasRsetSuccessful());
    }

    // ============== Helper Methods ==============

    private void verifyMailboxHasMessage(String username, String expectedSubject) throws Exception {
        MailboxStore store = mailboxFactory.createStore();
        try {
            store.open(username);
            Mailbox mailbox = store.openMailbox("INBOX", true);
            try {
                assertTrue("Mailbox for " + username + " should have messages",
                        mailbox.getMessageCount() > 0);

                String content = readMessageContent(mailbox, 1);
                assertTrue("Message should contain expected subject",
                        content.contains("Subject: " + expectedSubject));
            } finally {
                mailbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    private String readMessageContent(Mailbox mailbox, int messageNumber) throws Exception {
        ReadableByteChannel channel = mailbox.getMessageContent(messageNumber);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        java.nio.channels.Channels.newInputStream(channel),
                        StandardCharsets.US_ASCII
                )
        );

        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    // ============== Test Handler Classes ==============

    /**
     * A test handler that sends a single email and tracks the result.
     */
    private static class TestMailHandler implements ServerGreeting, ServerEhloReplyHandler,
            ServerMailFromReplyHandler, ServerRcptToReplyHandler, ServerDataReplyHandler,
            ServerMessageReplyHandler {

        private final CountDownLatch completionLatch = new CountDownLatch(1);
        private final AtomicBoolean accepted = new AtomicBoolean(false);
        private final AtomicBoolean recipientRejected = new AtomicBoolean(false);
        private final AtomicReference<String> error = new AtomicReference<>();
        private final java.util.List<String> recipients = new java.util.ArrayList<>();
        private String subject = "Test";
        private String body = "Test body";
        private int recipientIndex = 0;
        private boolean expectRecipientRejection = false;

        void addRecipient(String recipient) {
            recipients.add(recipient);
        }

        void setSubject(String subject) {
            this.subject = subject;
        }

        void setBody(String body) {
            this.body = body;
        }

        void setExpectRecipientRejection(boolean expect) {
            this.expectRecipientRejection = expect;
        }

        boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return completionLatch.await(timeout, unit);
        }

        boolean wasAccepted() {
            return accepted.get();
        }

        boolean wasRecipientRejected() {
            return recipientRejected.get();
        }

        String getError() {
            return error.get();
        }

        // ServerGreeting
        @Override
        public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
            hello.ehlo("test.client.local", this);
        }

        @Override
        public void handleServiceUnavailable(String message) {
            error.set("Service unavailable: " + message);
            // Connection will close, onDisconnected() will countDown
        }

        // ServerEhloReplyHandler
        @Override
        public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                               List<String> authMethods, boolean pipelining) {
            session.mailFrom(new EmailAddress(null, "sender", "external.com", true), this);
        }

        @Override
        public void handleEhloNotSupported(ClientHelloState hello) {
            // Fall back to HELO
            hello.helo("test.client.local", new ServerHeloReplyHandler() {
                @Override
                public void handleHelo(ClientSession session) {
                    session.mailFrom(new EmailAddress(null, "sender", "external.com", true),
                            TestMailHandler.this);
                }

                @Override
                public void handlePermanentFailure(String message) {
                    error.set("HELO failed: " + message);
                    // Connection should close, onDisconnected() will countDown
                }

                @Override
                public void handleServiceClosing(String message) {
                    error.set("Service closing: " + message);
                    // Connection will close, onDisconnected() will countDown
                }
            });
        }

        // ServerEhloReplyHandler and ServerMailFromReplyHandler share this signature
        @Override
        public void handlePermanentFailure(String message) {
            error.set("Permanent failure: " + message);
            // Connection should close, onDisconnected() will countDown
        }

        // ServerMailFromReplyHandler
        @Override
        public void handleMailFromOk(ClientEnvelope envelope) {
            recipientIndex = 0;
            sendNextRecipient(envelope);
        }

        private void sendNextRecipient(ClientEnvelopeState envelope) {
            if (recipientIndex < recipients.size()) {
                String recipient = recipients.get(recipientIndex++);
                String[] parts = recipient.split("@");
                envelope.rcptTo(new EmailAddress(null, parts[0], parts[1], true), this);
            }
        }

        // ServerMailFromReplyHandler
        @Override
        public void handleTemporaryFailure(ClientSession session) {
            error.set("Temporary failure");
            session.quit();
            // Let onDisconnected() handle the countDown
        }

        // ServerRcptToReplyHandler
        @Override
        public void handleRcptToOk(ClientEnvelopeReady envelope) {
            if (recipientIndex < recipients.size()) {
                sendNextRecipient(envelope);
            } else {
                envelope.data(this);
            }
        }

        @Override
        public void handleTemporaryFailure(ClientEnvelopeState state) {
            if (expectRecipientRejection) {
                recipientRejected.set(true);
                state.quit();
                // Let onDisconnected() handle the countDown
            } else if (recipientIndex < recipients.size()) {
                sendNextRecipient(state);
            } else if (state.hasAcceptedRecipients()) {
                ((ClientEnvelopeReady) state).data(this);
            } else {
                error.set("No recipients accepted");
                state.quit();
                // Let onDisconnected() handle the countDown
            }
        }

        @Override
        public void handleRecipientRejected(ClientEnvelopeState state) {
            if (expectRecipientRejection) {
                recipientRejected.set(true);
                state.quit();
                // Let onDisconnected() handle the countDown
            } else if (recipientIndex < recipients.size()) {
                sendNextRecipient(state);
            } else if (state.hasAcceptedRecipients()) {
                ((ClientEnvelopeReady) state).data(this);
            } else {
                error.set("All recipients rejected");
                state.quit();
                // Let onDisconnected() handle the countDown
            }
        }

        // ServerDataReplyHandler
        @Override
        public void handleReadyForData(ClientMessageData data) {
            String message = "Subject: " + subject + "\r\n" +
                    "From: sender@external.com\r\n" +
                    "To: " + String.join(", ", recipients) + "\r\n" +
                    "\r\n" +
                    body + "\r\n";
            data.writeContent(ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
            data.endMessage(this);
        }

        @Override
        public void handleTemporaryFailure(ClientEnvelopeReady envelope) {
            error.set("DATA temporary failure");
            envelope.quit();
            // Let onDisconnected() handle the countDown
        }

        // ServerMessageReplyHandler
        @Override
        public void handleMessageAccepted(String queueId, ClientSession session) {
            accepted.set(true);
            session.quit();
            // Let onDisconnected() handle the countDown
        }

        @Override
        public void handlePermanentFailure(String message, ClientSession session) {
            error.set("Message rejected: " + message);
            session.quit();
            // Let onDisconnected() handle the countDown
        }

        // ServerReplyHandler (base)
        @Override
        public void handleServiceClosing(String message) {
            error.set("Service closing: " + message);
            // Connection will close, onDisconnected() will countDown
        }

        // ClientHandler
        @Override
        public void onConnected(Endpoint endpoint) {
            // Connection established - greeting will follow
        }

        @Override
        public void onDisconnected() {
            completionLatch.countDown();
        }

        @Override
        public void onError(Exception e) {
            error.set("I/O error: " + e.getMessage());
            completionLatch.countDown();
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
            // TLS upgrade completed
        }
    }

    /**
     * A handler that sends multiple messages on the same connection.
     */
    private static class MultiTransactionHandler implements ServerGreeting, ServerEhloReplyHandler,
            ServerMailFromReplyHandler, ServerRcptToReplyHandler, ServerDataReplyHandler,
            ServerMessageReplyHandler {

        private final CountDownLatch completionLatch;
        private final int transactionCount;
        private final AtomicInteger acceptedCount = new AtomicInteger(0);
        private final AtomicReference<String> error = new AtomicReference<>();
        private String recipient;
        private String[] subjects;
        private String[] bodies;
        private int currentTransaction = 0;

        MultiTransactionHandler(int transactionCount) {
            this.transactionCount = transactionCount;
            this.completionLatch = new CountDownLatch(1);
        }

        void setRecipient(String recipient) {
            this.recipient = recipient;
        }

        void setSubjects(String[] subjects) {
            this.subjects = subjects;
        }

        void setBodies(String[] bodies) {
            this.bodies = bodies;
        }

        boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return completionLatch.await(timeout, unit);
        }

        int getAcceptedCount() {
            return acceptedCount.get();
        }

        // ServerGreeting
        @Override
        public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
            hello.ehlo("test.client.local", this);
        }

        @Override
        public void handleServiceUnavailable(String message) {
            error.set("Service unavailable: " + message);
            // Connection will close, onDisconnected() will countDown
        }

        // ServerEhloReplyHandler
        @Override
        public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                               List<String> authMethods, boolean pipelining) {
            startTransaction(session);
        }

        @Override
        public void handleEhloNotSupported(ClientHelloState hello) {
            error.set("EHLO not supported");
            hello.quit();
            // Let onDisconnected() handle the countDown
        }

        // Shared by ServerEhloReplyHandler and ServerMailFromReplyHandler
        @Override
        public void handlePermanentFailure(String message) {
            error.set("Permanent failure: " + message);
            completionLatch.countDown();
        }

        private void startTransaction(ClientSession session) {
            if (currentTransaction < transactionCount) {
                session.mailFrom(
                        new EmailAddress(null, "sender" + currentTransaction, "external.com", true),
                        this);
            } else {
                session.quit();
                // Let onDisconnected() handle the countDown
            }
        }

        // ServerMailFromReplyHandler
        @Override
        public void handleMailFromOk(ClientEnvelope envelope) {
            String[] parts = recipient.split("@");
            envelope.rcptTo(new EmailAddress(null, parts[0], parts[1], true), this);
        }

        @Override
        public void handleTemporaryFailure(ClientSession session) {
            error.set("Temporary failure");
            session.quit();
            // Let onDisconnected() handle the countDown
        }

        // ServerRcptToReplyHandler
        @Override
        public void handleRcptToOk(ClientEnvelopeReady envelope) {
            envelope.data(this);
        }

        @Override
        public void handleTemporaryFailure(ClientEnvelopeState state) {
            error.set("RCPT TO temporary failure");
            state.quit();
            // Let onDisconnected() handle the countDown
        }

        @Override
        public void handleRecipientRejected(ClientEnvelopeState state) {
            error.set("Recipient rejected");
            state.quit();
            // Let onDisconnected() handle the countDown
        }

        // ServerDataReplyHandler
        @Override
        public void handleReadyForData(ClientMessageData data) {
            String message = "Subject: " + subjects[currentTransaction] + "\r\n" +
                    "\r\n" +
                    bodies[currentTransaction] + "\r\n";
            data.writeContent(ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
            data.endMessage(this);
        }

        @Override
        public void handleTemporaryFailure(ClientEnvelopeReady envelope) {
            error.set("DATA temporary failure");
            envelope.quit();
            // Let onDisconnected() handle the countDown
        }

        // ServerMessageReplyHandler
        @Override
        public void handleMessageAccepted(String queueId, ClientSession session) {
            acceptedCount.incrementAndGet();
            currentTransaction++;
            startTransaction(session);
        }

        @Override
        public void handlePermanentFailure(String message, ClientSession session) {
            error.set("Message rejected: " + message);
            session.quit();
            // Let onDisconnected() handle the countDown
        }

        // ServerReplyHandler (base)
        @Override
        public void handleServiceClosing(String message) {
            error.set("Service closing: " + message);
            // Connection will close, onDisconnected() will countDown
        }

        // ClientHandler
        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void onDisconnected() {
            completionLatch.countDown();
        }

        @Override
        public void onError(Exception e) {
            error.set("I/O error: " + e.getMessage());
            completionLatch.countDown();
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }
    }

    /**
     * A handler that tests RSET command clears transaction state.
     */
    private static class RsetTestHandler implements ServerGreeting, ServerEhloReplyHandler,
            ServerMailFromReplyHandler, ServerRcptToReplyHandler, ServerRsetReplyHandler {

        private final CountDownLatch completionLatch = new CountDownLatch(1);
        private final AtomicBoolean rsetSuccessful = new AtomicBoolean(false);
        private final AtomicReference<String> error = new AtomicReference<>();
        private String recipient;

        void setRecipient(String recipient) {
            this.recipient = recipient;
        }

        boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return completionLatch.await(timeout, unit);
        }

        boolean wasRsetSuccessful() {
            return rsetSuccessful.get();
        }

        // ServerGreeting
        @Override
        public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
            hello.ehlo("test.client.local", this);
        }

        @Override
        public void handleServiceUnavailable(String message) {
            error.set("Service unavailable: " + message);
            // Connection will close, onDisconnected() will countDown
        }

        // ServerEhloReplyHandler
        @Override
        public void handleEhlo(ClientSession session, boolean starttls, long maxSize,
                               List<String> authMethods, boolean pipelining) {
            session.mailFrom(new EmailAddress(null, "sender", "external.com", true), this);
        }

        @Override
        public void handleEhloNotSupported(ClientHelloState hello) {
            error.set("EHLO not supported");
            hello.quit();
            // Let onDisconnected() handle the countDown
        }

        // Shared by ServerEhloReplyHandler and ServerMailFromReplyHandler
        @Override
        public void handlePermanentFailure(String message) {
            error.set("Permanent failure: " + message);
            // Connection should close, onDisconnected() will countDown
        }

        // ServerMailFromReplyHandler
        @Override
        public void handleMailFromOk(ClientEnvelope envelope) {
            String[] parts = recipient.split("@");
            envelope.rcptTo(new EmailAddress(null, parts[0], parts[1], true), this);
        }

        @Override
        public void handleTemporaryFailure(ClientSession session) {
            error.set("Temporary failure");
            session.quit();
            // Let onDisconnected() handle the countDown
        }

        // ServerRcptToReplyHandler
        @Override
        public void handleRcptToOk(ClientEnvelopeReady envelope) {
            // Now reset the transaction
            envelope.rset(this);
        }

        @Override
        public void handleTemporaryFailure(ClientEnvelopeState state) {
            error.set("RCPT TO temporary failure");
            state.quit();
            // Let onDisconnected() handle the countDown
        }

        @Override
        public void handleRecipientRejected(ClientEnvelopeState state) {
            error.set("Recipient rejected");
            state.quit();
            // Let onDisconnected() handle the countDown
        }

        // ServerRsetReplyHandler
        @Override
        public void handleResetOk(ClientSession session) {
            rsetSuccessful.set(true);
            session.quit();
            // Let onDisconnected() handle the countDown
        }

        // ServerReplyHandler (base)
        @Override
        public void handleServiceClosing(String message) {
            error.set("Service closing: " + message);
            // Connection will close, onDisconnected() will countDown
        }

        // ClientHandler
        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void onDisconnected() {
            completionLatch.countDown();
        }

        @Override
        public void onError(Exception e) {
            error.set("I/O error: " + e.getMessage());
            completionLatch.countDown();
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }
    }
}
