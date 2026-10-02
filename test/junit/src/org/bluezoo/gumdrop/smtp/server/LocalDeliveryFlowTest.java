/*
 * LocalDeliveryFlowTest.java
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

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.mailbox.AsyncMessageWriter;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.smtp.SmtpListener;
import org.bluezoo.gumdrop.smtp.SmtpProtocolHandler;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import static org.junit.Assert.*;

/**
 * Runs {@link LocalDeliveryHandler} behind a real
 * {@link SmtpProtocolHandler} with proxy-backed mailbox stores, covering
 * buffered delivery, asynchronous append delivery and the failure paths.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LocalDeliveryFlowTest {

    private RecordingStubEndpoint endpoint;
    private SmtpProtocolHandler handler;
    private FakeFactory factory;

    @Before
    public void setUp() {
        factory = new FakeFactory();
        start(factory);
    }

    private void start(FakeFactory f) {
        SmtpListener listener = new SmtpListener();
        LocalDeliveryHandler local = new LocalDeliveryHandler(f, "Example.COM", "mx.example.com");
        handler = new SmtpProtocolHandler(listener, local);
        endpoint = new RecordingStubEndpoint(25);
        handler.connected(endpoint);
        endpoint.clearResponses();
    }

    private void send(String command) {
        raw(command + "\r\n");
    }

    private void raw(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private String last() {
        List<String> responses = endpoint.getResponses();
        assertFalse("No responses", responses.isEmpty());
        return responses.get(responses.size() - 1);
    }

    private void expect(String command, String code) {
        endpoint.clearResponses();
        send(command);
        String response = last();
        assertTrue(command + " -> " + response, response.startsWith(code));
    }

    private void toRcpt() {
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<s@remote.example.net>", "250");
        expect("RCPT TO:<alice@example.com>", "250");
    }

    @Test
    public void testConstructorValidation() {
        try {
            new LocalDeliveryHandler(null, "example.com");
            fail("null factory");
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
        try {
            new LocalDeliveryHandler(factory, "");
            fail("empty domain");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
        assertNotNull(new LocalDeliveryHandler(factory, "example.com", null));
    }

    @Test
    public void testGreetingUsesHostname() {
        handler = new SmtpProtocolHandler(new SmtpListener(),
                new LocalDeliveryHandler(factory, "example.com", "mx.example.com"));
        endpoint = new RecordingStubEndpoint(25);
        handler.connected(endpoint);
        assertTrue(last(), last().contains("mx.example.com ESMTP"));
    }

    @Test
    public void testNonLocalRecipientRejected() {
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<s@remote.example.net>", "250");
        expect("RCPT TO:<bob@other.example>", "5");
    }

    @Test
    public void testDataWithoutRecipientsRefused() {
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<s@remote.example.net>", "250");
        expect("RCPT TO:<bob@other.example>", "5");
        expect("DATA", "503");
    }

    @Test
    public void testBufferedDeliveryToMailbox() {
        toRcpt();
        expect("RCPT TO:<carol@EXAMPLE.com>", "250");
        expect("DATA", "354");
        raw("Subject: hi\r\n\r\nhello body\r\n.\r\n");
        assertTrue(last(), last().startsWith("250"));
        assertEquals(2, factory.deliveries.size());
        assertTrue(factory.deliveries.get(0).contains("hello body"));
        assertTrue(factory.users.contains("alice"));
        assertTrue(factory.users.contains("carol"));
    }

    @Test
    public void testBufferedDeliveryFailureIsTemporary() {
        factory.failAppend = true;
        toRcpt();
        expect("DATA", "354");
        raw("Subject: hi\r\n\r\nbody\r\n.\r\n");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testAsyncDelivery() {
        factory.async = true;
        toRcpt();
        expect("DATA", "354");
        raw("Subject: hi\r\n\r\nasync body\r\n.\r\n");
        assertTrue(last(), last().startsWith("250"));
        assertEquals(1, factory.writers.size());
        FakeWriter writer = factory.writers.get(0);
        assertTrue(writer.finished);
        assertTrue(writer.written.toString().contains("async body"));
    }

    @Test
    public void testAsyncDeliveryFinishFailure() {
        factory.async = true;
        factory.failFinish = true;
        toRcpt();
        expect("DATA", "354");
        raw("body\r\n.\r\n");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testAsyncOpenFailureFallsBackToBuffer() {
        factory.failOpenMailbox = true;
        toRcpt();
        expect("DATA", "354");
        raw("body\r\n.\r\n");
        String response = last();
        assertTrue(response, response.startsWith("4") || response.startsWith("250"));
    }

    @Test
    public void testAsyncSecondTargetUnavailableFallsBack() {
        factory.async = true;
        factory.asyncLimit = 1;
        toRcpt();
        expect("RCPT TO:<dave@example.com>", "250");
        expect("DATA", "354");
        raw("body\r\n.\r\n");
        assertTrue(last(), last().startsWith("250"));
        assertEquals(1, factory.writers.size());
        assertTrue(factory.writers.get(0).aborted);
        assertEquals(2, factory.deliveries.size());
    }

    @Test
    public void testAsyncAbortOnDisconnectAndPause() {
        factory.async = true;
        toRcpt();
        expect("DATA", "354");
        raw("Subject: partial\r\n");
        assertEquals(1, factory.writers.size());
        FakeWriter writer = factory.writers.get(0);
        writer.pause = true;
        raw("more\r\n");
        assertTrue(writer.written.toString().contains("more"));
        handler.disconnected();
    }

    @Test
    public void testAsyncWriterNotFinishedBeforeTerminator() {
        factory.async = true;
        toRcpt();
        expect("DATA", "354");
        raw("partial\r\n");
        FakeWriter writer = factory.writers.get(0);
        assertFalse(writer.finished);
    }

    @Test
    public void testResetAndQuit() {
        toRcpt();
        expect("RSET", "250");
        expect("RCPT TO:<alice@example.com>", "503");
        expect("QUIT", "221");
    }

    @Test
    public void testDisconnectMidMessage() {
        toRcpt();
        expect("DATA", "354");
        raw("partial");
        handler.disconnected();
    }

    @Test
    public void testBdatDelivery() {
        toRcpt();
        endpoint.clearResponses();
        send("BDAT 11 LAST");
        raw("hello world");
        assertTrue(last(), last().startsWith("250"));
        assertEquals(1, factory.deliveries.size());
        assertEquals("hello world", factory.deliveries.get(0));
    }

    // -- fakes --

    /** Records what the handler does to the mailbox layer. */
    private static final class FakeFactory implements MailboxFactory {
        final List<String> deliveries = new ArrayList<String>();
        final List<String> users = new ArrayList<String>();
        final List<FakeWriter> writers = new ArrayList<FakeWriter>();
        boolean async;
        boolean failAppend;
        boolean failFinish;
        boolean failOpenMailbox;
        int asyncLimit = Integer.MAX_VALUE;

        @Override
        public MailboxStore createStore() {
            final FakeFactory self = this;
            final String[] user = new String[1];
            final Mailbox mailbox = (Mailbox) Proxy.newProxyInstance(
                    Mailbox.class.getClassLoader(), new Class<?>[] {Mailbox.class},
                    new MailboxHandler(this, user));
            InvocationHandler storeHandler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args)
                        throws Throwable {
                    String name = method.getName();
                    if ("open".equals(name)) {
                        user[0] = (String) args[0];
                        self.users.add(user[0]);
                        return null;
                    }
                    if ("openMailbox".equals(name)) {
                        if (self.failOpenMailbox) {
                            throw new IOException("no mailbox");
                        }
                        return mailbox;
                    }
                    return defaultFor(method.getReturnType());
                }
            };
            return (MailboxStore) Proxy.newProxyInstance(
                    MailboxStore.class.getClassLoader(), new Class<?>[] {MailboxStore.class},
                    storeHandler);
        }
    }

    /** Invocation handler for the mailbox proxy. */
    private static final class MailboxHandler implements InvocationHandler {
        private final FakeFactory factory;
        private final String[] user;
        private StringBuilder current;

        MailboxHandler(FakeFactory factory, String[] user) {
            this.factory = factory;
            this.user = user;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("openAsyncAppend".equals(name)) {
                if (factory.async && factory.writers.size() < factory.asyncLimit) {
                    FakeWriter writer = new FakeWriter(factory.failFinish);
                    factory.writers.add(writer);
                    return writer;
                }
                return null;
            }
            if ("startAppendMessage".equals(name)) {
                if (factory.failAppend) {
                    throw new IOException("append failed");
                }
                current = new StringBuilder();
                return null;
            }
            if ("appendMessageContent".equals(name)) {
                ByteBuffer data = (ByteBuffer) args[0];
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                current.append(new String(bytes, StandardCharsets.US_ASCII));
                return null;
            }
            if ("endAppendMessage".equals(name)) {
                factory.deliveries.add(current.toString());
                return Long.valueOf(1L);
            }
            return defaultFor(method.getReturnType());
        }
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        return null;
    }

    /** Asynchronous writer capturing everything written to it. */
    private static final class FakeWriter implements AsyncMessageWriter {
        final StringBuilder written = new StringBuilder();
        final boolean failFinish;
        boolean finished;
        boolean aborted;
        boolean pause;

        FakeWriter(boolean failFinish) {
            this.failFinish = failFinish;
        }

        @Override
        public void write(ByteBuffer src, CompletionHandler<Integer, ByteBuffer> h) {
            byte[] bytes = new byte[src.remaining()];
            src.get(bytes);
            written.append(new String(bytes, StandardCharsets.US_ASCII));
            h.completed(Integer.valueOf(bytes.length), src);
        }

        @Override
        public boolean wantsPause() {
            return pause;
        }

        @Override
        public void finish(CompletionHandler<Long, Void> h) {
            finished = true;
            if (failFinish) {
                h.failed(new IOException("finish failed"), null);
            } else {
                h.completed(Long.valueOf(7L), null);
            }
        }

        @Override
        public void abort() {
            aborted = true;
        }

        @Override
        public void close() {
        }
    }
}
