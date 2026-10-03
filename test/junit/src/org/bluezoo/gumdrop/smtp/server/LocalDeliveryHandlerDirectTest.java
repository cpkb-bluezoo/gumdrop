/*
 * LocalDeliveryHandlerDirectTest
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

package org.bluezoo.gumdrop.smtp.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.server.LocalDeliveryFlowTest.MockFactory;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Calls the handler callbacks of {@link LocalDeliveryHandler} directly:
 * recipient domain policy, empty-recipient DATA, abort with open writers,
 * pause propagation, write and close failures, and buffered content before
 * and after the delivery fallback.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LocalDeliveryHandlerDirectTest {

    private MockFactory factory;
    private LocalDeliveryHandler handler;
    private RecordingSmtpStates states;

    @Before
    public void setUp() {
        factory = new MockFactory();
        handler = new LocalDeliveryHandler(factory, "Example.com", "mx.example.com");
        states = new RecordingSmtpStates();
        handler.connected(states, new RecordingStubEndpoint(25));
    }

    private static EmailAddress addr(String local, String domain) {
        return new EmailAddress(null, local, domain, true);
    }

    private void begin(String... locals) {
        handler.mailFrom(states, addr("s", "remote.example.net"), false, null);
        for (int i = 0; i < locals.length; i++) {
            handler.rcptTo(states, addr(locals[i], "example.com"), null);
        }
    }

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    private String lastCall() {
        return states.calls.get(states.calls.size() - 1);
    }

    @Test
    public void connectedGreetsWithHostname() {
        assertEquals("acceptConnection:mx.example.com ESMTP Service ready",
                states.calls.get(0));
    }

    @Test
    public void helloAuthenticatedAndTlsAreAccepted() {
        handler.hello(states, false, "c.example.org");
        handler.tlsEstablished(null);
        handler.authenticated(states, new Principal() {
            @Override
            public String getName() {
                return "alice";
            }
        });
        handler.quit();
        assertEquals("acceptHello", states.calls.get(1));
        assertEquals("accept", states.calls.get(2));
        assertNull(handler.getPipeline());
    }

    @Test
    public void recipientDomainIsMatchedCaseInsensitively() {
        handler.mailFrom(states, addr("s", "remote.example.net"), false, null);
        handler.rcptTo(states, addr("a", "EXAMPLE.COM"), null);
        assertEquals("acceptRecipient", lastCall());
        handler.rcptTo(states, addr("a", "other.org"), null);
        assertEquals("rejectRecipientRelayDenied", lastCall());
    }

    @Test
    public void startMessageWithoutRecipientsIsRejected() {
        handler.mailFrom(states, addr("s", "remote.example.net"), false, null);
        handler.startMessage(states);
        assertEquals("rejectMessage", lastCall());
        assertEquals("No recipients", states.lastMessage);
    }

    @Test
    public void resetClearsRecipients() {
        begin("alice");
        handler.reset(states);
        assertEquals("acceptReset", lastCall());
        handler.startMessage(states);
        assertEquals("rejectMessage", lastCall());
    }

    @Test
    public void disconnectClearsRecipients() {
        begin("alice");
        handler.disconnected();
        handler.startMessage(states);
        assertEquals("rejectMessage", lastCall());
    }

    @Test
    public void bufferedContentIsDeliveredToEveryRecipient() {
        begin("alice", "bob");
        handler.startMessage(states);
        handler.messageContent(bytes("part one "));
        handler.messageContent(bytes("part two"));
        handler.messageComplete(states);
        assertEquals("acceptMessageDelivery", lastCall());
        assertEquals(2, factory.deliveries.size());
        assertEquals("part one part two", factory.deliveries.get(0));
    }

    @Test
    public void bufferedDeliveryFailureReportsFirstError() {
        factory.failAppend = true;
        begin("alice", "bob");
        handler.startMessage(states);
        handler.messageContent(bytes("x"));
        handler.messageComplete(states);
        assertEquals("rejectMessageTemporary", lastCall());
        assertEquals("append failed", states.lastMessage);
    }

    @Test
    public void closeFailuresDoNotFailDelivery() {
        factory.failClose = true;
        begin("alice");
        handler.startMessage(states);
        handler.messageContent(bytes("x"));
        handler.messageComplete(states);
        assertEquals("acceptMessageDelivery", lastCall());
    }

    @Test
    public void asyncWriterReceivesStreamedContentAndPauseIsReported() {
        factory.async = true;
        begin("alice", "bob");
        handler.startMessage(states);
        assertEquals(2, factory.writers.size());
        assertFalse(handler.wantsPause());
        factory.writers.get(1).pause = true;
        assertTrue(handler.wantsPause());
        handler.setResumeCallback(null);
        handler.messageContent(bytes("streamed"));
        assertTrue(factory.writers.get(0).written.toString().contains("streamed"));
        assertTrue(factory.writers.get(1).written.toString().contains("streamed"));
        handler.messageComplete(states);
        assertEquals("acceptMessageDelivery", lastCall());
        assertTrue(factory.writers.get(0).finished);
    }

    @Test
    public void asyncWriteFailureIsLoggedNotFatal() {
        factory.async = true;
        factory.failWrite = true;
        begin("alice");
        handler.startMessage(states);
        handler.messageContent(bytes("data"));
        handler.messageComplete(states);
        assertEquals("acceptMessageDelivery", lastCall());
    }

    @Test
    public void asyncFinishFailureRejectsTemporarily() {
        factory.async = true;
        factory.failFinish = true;
        factory.failClose = true;
        begin("alice");
        handler.startMessage(states);
        handler.messageContent(bytes("data"));
        handler.messageComplete(states);
        assertEquals("rejectMessageTemporary", lastCall());
        assertTrue(states.lastMessage, states.lastMessage.contains("Delivery failed"));
    }

    @Test
    public void abortingAbortsEveryOpenWriter() {
        factory.async = true;
        factory.failClose = true;
        begin("alice", "bob");
        handler.startMessage(states);
        handler.messageAborted();
        assertTrue(factory.writers.get(0).aborted);
        assertTrue(factory.writers.get(1).aborted);
        assertFalse(handler.wantsPause());
    }

    @Test
    public void abortingWithoutWritersIsHarmless() {
        begin("alice");
        handler.startMessage(states);
        handler.messageAborted();
        assertTrue(factory.writers.isEmpty());
    }

    @Test
    public void secondWriterFailureAbortsTheFirstAndFallsBack() {
        factory.async = true;
        factory.asyncLimit = 1;
        factory.failClose = true;
        begin("alice", "bob");
        handler.startMessage(states);
        assertTrue(factory.writers.get(0).aborted);
        handler.messageContent(bytes("fallback"));
        handler.messageComplete(states);
        assertEquals(2, factory.deliveries.size());
    }

    @Test
    public void mailboxOpenFailureFallsBackToBufferedDelivery() {
        factory.failOpenMailbox = true;
        begin("alice");
        handler.startMessage(states);
        handler.messageContent(bytes("x"));
        handler.messageComplete(states);
        assertEquals("rejectMessageTemporary", lastCall());
        assertEquals("no mailbox", states.lastMessage);
    }

    @Test
    public void emptyMessageDeliversEmptyContent() {
        begin("alice");
        handler.startMessage(states);
        handler.messageComplete(states);
        assertEquals("acceptMessageDelivery", lastCall());
        assertEquals("", factory.deliveries.get(0));
    }

    @Test
    public void contentWithoutMailFromIsBuffered() {
        LocalDeliveryHandler fresh = new LocalDeliveryHandler(factory, "example.com");
        fresh.messageContent(bytes("orphan"));
        fresh.rcptTo(states, addr("a", "example.com"), null);
        fresh.startMessage(states);
        fresh.messageComplete(states);
        assertEquals("acceptMessageDelivery", lastCall());
        assertEquals("orphan", factory.deliveries.get(0));
    }

    @Test
    public void storeCreationFailureFallsBackThenRejectsTemporarily() {
        factory.createThrows = true;
        begin("alice");
        handler.startMessage(states);
        handler.messageContent(bytes("x"));
        handler.messageComplete(states);
        assertEquals("rejectMessageTemporary", lastCall());
        assertEquals("store unavailable", states.lastMessage);
    }

    @Test
    public void writerFailureDuringFinishRejectsTemporarily() {
        factory.async = true;
        factory.finishThrows = true;
        begin("alice");
        handler.startMessage(states);
        handler.messageContent(bytes("x"));
        handler.messageComplete(states);
        assertEquals("rejectMessageTemporary", lastCall());
        assertEquals("writer broke", states.lastMessage);
    }

    @Test
    public void interruptedFinalisationIsReportedAndPreservesInterrupt() {
        factory.async = true;
        begin("alice");
        handler.startMessage(states);
        handler.messageContent(bytes("x"));
        Thread.currentThread().interrupt();
        try {
            handler.messageComplete(states);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals("rejectMessageTemporary", lastCall());
        assertEquals("Interrupted during delivery", states.lastMessage);
    }
}
