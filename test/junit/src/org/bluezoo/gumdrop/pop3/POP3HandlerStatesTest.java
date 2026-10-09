/*
 * POP3HandlerStatesTest.java
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


package org.bluezoo.gumdrop.pop3;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.mailbox.AsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.BufferedAsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.pop3.server.AuthenticateState;
import org.bluezoo.gumdrop.pop3.server.AuthorizationHandler;
import org.bluezoo.gumdrop.pop3.server.ClientConnected;
import org.bluezoo.gumdrop.pop3.server.ConnectedState;
import org.bluezoo.gumdrop.pop3.server.ListState;
import org.bluezoo.gumdrop.pop3.server.MailboxStatusState;
import org.bluezoo.gumdrop.pop3.server.MarkDeletedState;
import org.bluezoo.gumdrop.pop3.server.ResetState;
import org.bluezoo.gumdrop.pop3.server.RetrieveState;
import org.bluezoo.gumdrop.pop3.server.TopState;
import org.bluezoo.gumdrop.pop3.server.TransactionHandler;
import org.bluezoo.gumdrop.pop3.server.UidlState;
import org.bluezoo.gumdrop.pop3.server.UpdateState;

import static org.junit.Assert.*;

/**
 * Drives every reply method of the handler-facing state objects that
 * {@link Pop3ProtocolHandler} implements (status, list, retrieve, delete,
 * reset, top, uidl, update, connection and authorisation states), using a
 * scripted {@link TransactionHandler}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3HandlerStatesTest {

    private Pop3ProtocolHandler handler;
    private POP3ProtocolHandlerTest.StubEndpoint endpoint;
    private POP3ProtocolHandlerTest.TestPOP3Listener listener;
    private POP3ProtocolHandlerTest.StubMailboxFactory factory;
    private Scripted tx;
    private Greeter greeter;

    @Before
    public void setUp() {
        factory = new POP3ProtocolHandlerTest.StubMailboxFactory();
        listener = new POP3ProtocolHandlerTest.TestPOP3Listener();
        listener.realm(new POP3ProtocolHandlerTest.StubRealm());
        listener.mailboxFactory(factory);
        listener.enableAPOP(false);
        tx = new Scripted();
        greeter = new Greeter();
        greeter.tx = tx;
        listener.clientHandler = greeter;
        endpoint = new POP3ProtocolHandlerTest.StubEndpoint();
        handler = new Pop3ProtocolHandler(listener);
    }

    private void send(String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private void login() {
        handler.connected(endpoint);
        send("USER testuser");
        send("PASS testpass");
        endpoint.sentData.clear();
    }

    private String last() {
        List<String> all = endpoint.getResponses();
        assertFalse(all.isEmpty());
        return all.get(all.size() - 1);
    }

    private String all() {
        StringBuilder sb = new StringBuilder();
        for (String line : endpoint.getResponses()) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void run(String command, String mode) {
        tx.mode = mode;
        endpoint.sentData.clear();
        send(command);
    }

    // ── Connection and authorisation states ──

    @Test
    public void testApopGreetingFromHandler() {
        greeter.action = "apop";
        handler.connected(endpoint);
        assertTrue(last(), last().startsWith("+OK Hello <ts@host>"));
    }

    @Test
    public void testRejectedConnectionVariantsCloseEndpoint() {
        greeter.action = "reject";
        handler.connected(endpoint);
        assertTrue(last().startsWith("-ERR"));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testAuthorizationHandlerProceedAndReject() {
        greeter.authAction = "proceed";
        handler.connected(endpoint);
        send("USER testuser");
        send("PASS testpass");
        assertTrue(last(), last().startsWith("+OK"));
        assertTrue(greeter.authenticateCalled);
    }

    @Test
    public void testAuthorizationHandlerRejectThenClose() {
        greeter.authAction = "reject";
        handler.connected(endpoint);
        send("USER testuser");
        send("PASS testpass");
        assertTrue(last().contains("denied"));
        assertTrue(endpoint.isOpen());
        greeter.authAction = "close";
        send("USER testuser");
        send("PASS testpass");
        assertTrue(last().contains("bye"));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testDisconnectNotifiesHandler() {
        login();
        handler.disconnected();
        assertTrue(greeter.disconnected);
    }

    // ── STAT and LIST ──

    @Test
    public void testStatReplies() {
        login();
        run("STAT", "ok");
        assertEquals("+OK 3 1500", last());
        run("STAT", "error");
        assertEquals("-ERR scripted failure", last());
    }

    @Test
    public void testListReplies() {
        login();
        run("LIST", "writer");
        assertTrue(all(), all().contains("+OK 2 messages"));
        assertTrue(all().contains("1 100"));
        assertTrue(all().contains("2 200"));
        assertEquals(".", last());
        run("LIST 2", "single");
        assertEquals("+OK 2 123", last());
        run("LIST 2", "nosuch");
        assertTrue(last().startsWith("-ERR"));
        run("LIST 2", "deleted");
        assertTrue(last().startsWith("-ERR"));
        run("LIST 2", "error");
        assertEquals("-ERR scripted failure", last());
    }

    // ── RETR ──

    @Test
    public void testRetrAsyncContentIsDotStuffed() {
        login();
        tx.content = "Subject: x\r\n\r\n.dot line\r\nlast no newline";
        run("RETR 1", "async");
        assertTrue(all(), all().contains("+OK 42 octets"));
        assertTrue(all().contains("..dot line"));
        assertTrue(all().contains("last no newline"));
        assertEquals(".", last());
    }

    @Test
    public void testRetrChannelAndProceed() {
        login();
        tx.content = "A\r\nB\r\n";
        run("RETR 1", "channel");
        assertEquals(".", last());
        assertTrue(all().contains("\nA\n"));
        run("RETR 1", "proceed");
        assertEquals(".", last());
        assertTrue(all(), all().contains("octets"));
    }

    @Test
    public void testRetrLargeContentSpansManyChunks() {
        login();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            big.append("line ").append(i).append("\r\n");
        }
        tx.content = big.toString();
        run("RETR 1", "async");
        assertEquals(".", last());
        assertTrue(all().contains("line 1499"));
    }

    @Test
    public void testRetrAsyncReadFailureStopsOutput() {
        login();
        tx.content = "x";
        run("RETR 1", "asyncFail");
        assertFalse(all().contains("\n.\n"));
    }

    @Test
    public void testRetrAsyncReadReturnsZeroEnds() {
        login();
        tx.content = "abc";
        run("RETR 1", "asyncEmpty");
        assertEquals(".", last());
    }

    @Test
    public void testRetrErrorReplies() {
        login();
        run("RETR 1", "nosuch");
        assertTrue(last().startsWith("-ERR"));
        run("RETR 1", "deleted");
        assertTrue(last().startsWith("-ERR"));
        run("RETR 1", "error");
        assertEquals("-ERR scripted failure", last());
    }

    // ── DELE and RSET ──

    @Test
    public void testDeleReplies() {
        login();
        run("DELE 1", "ok");
        assertTrue(last().startsWith("+OK"));
        run("DELE 1", "msg");
        assertEquals("+OK Custom", last());
        run("DELE 1", "nosuch");
        assertTrue(last().startsWith("-ERR"));
        run("DELE 1", "already");
        assertTrue(last().startsWith("-ERR"));
        run("DELE 1", "error");
        assertEquals("-ERR scripted failure", last());
    }

    @Test
    public void testRsetReplies() {
        login();
        run("RSET", "ok");
        assertTrue(last(), last().startsWith("+OK"));
        run("RSET", "error");
        assertEquals("-ERR scripted failure", last());
    }

    // ── TOP ──

    @Test
    public void testTopReplies() {
        login();
        tx.content = "H: v\r\n\r\nbody\r\n";
        run("TOP 1 1", "channel");
        assertEquals(".", last());
        run("TOP 1 1", "proceed");
        assertEquals(".", last());
        run("TOP 1 1", "async");
        assertEquals(".", last());
        assertTrue(all().contains("body"));
        run("TOP 1 1", "nosuch");
        assertTrue(last().startsWith("-ERR"));
        run("TOP 1 1", "deleted");
        assertTrue(last().startsWith("-ERR"));
        run("TOP 1 1", "error");
        assertEquals("-ERR scripted failure", last());
    }

    // ── UIDL ──

    @Test
    public void testUidlReplies() {
        login();
        run("UIDL", "writer");
        assertTrue(all().contains("1 uid-1"));
        assertEquals(".", last());
        run("UIDL 1", "single");
        assertEquals("+OK 1 uid-one", last());
        run("UIDL 1", "nosuch");
        assertTrue(last().startsWith("-ERR"));
        run("UIDL 1", "deleted");
        assertTrue(last().startsWith("-ERR"));
        run("UIDL 1", "error");
        assertEquals("-ERR scripted failure", last());
    }

    // ── QUIT ──

    @Test
    public void testQuitCommitAndCloseVariants() {
        login();
        run("QUIT", "commit");
        assertTrue(last().startsWith("+OK"));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testQuitCommitWithMessage() {
        login();
        run("QUIT", "message");
        assertEquals("+OK Farewell", last());
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testQuitPartialCommit() {
        login();
        run("QUIT", "partial");
        assertEquals("-ERR some not removed", last());
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testQuitUpdateFailed() {
        login();
        run("QUIT", "failed");
        assertEquals("-ERR update failed", last());
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testQuitProceedClosesMailbox() {
        login();
        POP3ProtocolHandlerTest.StubMailbox mailbox = factory.lastMailbox;
        run("QUIT", "proceed");
        assertTrue(last().startsWith("+OK"));
        assertTrue(mailbox.closedWithExpunge);
    }

    // ── Fixtures ──

    static class Greeter implements ClientConnected, AuthorizationHandler {
        String action = "accept";
        String authAction = "accept";
        TransactionHandler tx;
        boolean authenticateCalled;
        boolean disconnected;

        @Override
        public void connected(ConnectedState state, Endpoint endpoint) {
            if ("apop".equals(action)) {
                state.acceptConnectionWithApop("Hello", "<ts@host>", this);
            } else if ("reject".equals(action)) {
                state.rejectConnection();
            } else {
                state.acceptConnection("Hello", this);
            }
        }

        @Override
        public void disconnected() {
            disconnected = true;
        }

        @Override
        public void authenticate(AuthenticateState state, Principal principal,
                MailboxFactory factory) {
            authenticateCalled = true;
            if ("proceed".equals(authAction)) {
                state.proceed(tx);
            } else if ("reject".equals(authAction)) {
                state.reject("denied", this);
            } else if ("close".equals(authAction)) {
                state.rejectAndClose("bye");
            } else {
                try {
                    Mailbox mailbox = factory.createStore().openMailbox("INBOX", false);
                    state.accept(mailbox, tx);
                } catch (IOException e) {
                    state.reject("io", this);
                }
            }
        }
    }

    static class FailingAsync implements AsyncMessageContent {
        private final int mode;
        private final long size;

        FailingAsync(int mode, long size) {
            this.mode = mode;
            this.size = size;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long bodyOffset() {
            return 0;
        }

        @Override
        public void read(ByteBuffer dst, long position,
                CompletionHandler<Integer, ByteBuffer> completion) {
            if (mode == 0) {
                completion.failed(new IOException("read failed"), dst);
            } else {
                completion.completed(Integer.valueOf(0), dst);
            }
        }

        @Override
        public void close() {
        }
    }

    /**
     * Transaction handler whose reply to each command is picked by a mode.
     */
    static class Scripted implements TransactionHandler {
        String mode = "ok";
        String content = "";

        private ReadableByteChannel channel() {
            byte[] bytes = content.getBytes(StandardCharsets.US_ASCII);
            return Channels.newChannel(new ByteArrayInputStream(bytes));
        }

        private AsyncMessageContent async() {
            byte[] bytes = content.getBytes(StandardCharsets.US_ASCII);
            return new BufferedAsyncMessageContent(bytes);
        }

        @Override
        public void mailboxStatus(MailboxStatusState state, Mailbox mailbox) {
            if ("error".equals(mode)) {
                state.error("scripted failure", this);
            } else {
                state.sendStatus(3, 1500, this);
            }
        }

        @Override
        public void list(ListState state, Mailbox mailbox, int messageNumber) {
            if ("writer".equals(mode)) {
                ListState.ListWriter writer = state.beginListing(2);
                writer.message(1, 100);
                writer.message(2, 200);
                writer.end(this);
            } else if ("single".equals(mode)) {
                state.sendListing(2, 123, this);
            } else if ("nosuch".equals(mode)) {
                state.noSuchMessage(this);
            } else if ("deleted".equals(mode)) {
                state.messageDeleted(this);
            } else {
                state.error("scripted failure", this);
            }
        }

        @Override
        public void retrieveMessage(RetrieveState state, Mailbox mailbox,
                int messageNumber) {
            if ("async".equals(mode)) {
                state.sendMessage(42, async(), this);
            } else if ("asyncFail".equals(mode)) {
                state.sendMessage(1, new FailingAsync(0, 1), this);
            } else if ("asyncEmpty".equals(mode)) {
                state.sendMessage(3, new FailingAsync(1, 3), this);
            } else if ("channel".equals(mode)) {
                ReadableByteChannel ch = channel();
                state.sendMessage(6, ch, this);
            } else if ("proceed".equals(mode)) {
                state.proceed(500, this);
            } else if ("nosuch".equals(mode)) {
                state.noSuchMessage(this);
            } else if ("deleted".equals(mode)) {
                state.messageDeleted(this);
            } else {
                state.error("scripted failure", this);
            }
        }

        @Override
        public void markDeleted(MarkDeletedState state, Mailbox mailbox,
                int messageNumber) {
            if ("ok".equals(mode)) {
                state.markedDeleted(this);
            } else if ("msg".equals(mode)) {
                state.markedDeleted("Custom", this);
            } else if ("nosuch".equals(mode)) {
                state.noSuchMessage(this);
            } else if ("already".equals(mode)) {
                state.alreadyDeleted(this);
            } else {
                state.error("scripted failure", this);
            }
        }

        @Override
        public void reset(ResetState state, Mailbox mailbox) {
            if ("ok".equals(mode)) {
                state.resetComplete(3, 1500, this);
            } else {
                state.error("scripted failure", this);
            }
        }

        @Override
        public void top(TopState state, Mailbox mailbox, int messageNumber,
                int lines) {
            if ("channel".equals(mode)) {
                ReadableByteChannel ch = channel();
                state.sendTop(ch, this);
            } else if ("proceed".equals(mode)) {
                state.proceed(lines, this);
            } else if ("async".equals(mode)) {
                AsyncMessageContent body = async();
                long end = body.size();
                state.sendTop(body, end, this);
            } else if ("nosuch".equals(mode)) {
                state.noSuchMessage(this);
            } else if ("deleted".equals(mode)) {
                state.messageDeleted(this);
            } else {
                state.error("scripted failure", this);
            }
        }

        @Override
        public void uidl(UidlState state, Mailbox mailbox, int messageNumber) {
            if ("writer".equals(mode)) {
                UidlState.UidlWriter writer = state.beginListing();
                writer.message(1, "uid-1");
                writer.message(2, "uid-2");
                writer.end(this);
            } else if ("single".equals(mode)) {
                state.sendUid(1, "uid-one", this);
            } else if ("nosuch".equals(mode)) {
                state.noSuchMessage(this);
            } else if ("deleted".equals(mode)) {
                state.messageDeleted(this);
            } else {
                state.error("scripted failure", this);
            }
        }

        @Override
        public void quit(UpdateState state, Mailbox mailbox) {
            if ("commit".equals(mode)) {
                state.commitAndClose();
            } else if ("message".equals(mode)) {
                state.commitAndClose("Farewell");
            } else if ("partial".equals(mode)) {
                state.partialCommit("some not removed");
            } else if ("failed".equals(mode)) {
                state.updateFailed("update failed");
            } else {
                state.proceed(this);
            }
        }
    }
}
