/*
 * POP3ProtocolHandlerPerformanceTest.java
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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.pop3.server.Pop3Server;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
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
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Pop3ProtocolHandler}.
 *
 * <p>Tests the POP3 server state machine by simulating client commands
 * through a stub Endpoint and verifying the responses sent back. Uses
 * stub implementations of Realm, Mailbox, MailboxFactory, and
 * MailboxStore for isolated unit testing without real I/O.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
/*
 * NOTE: wall-clock thresholds live here, not in the unit suite: unit tests must
 * be deterministic (CONTRIBUTING.md). Extracted from POP3ProtocolHandlerTest.
 */
public class POP3ProtocolHandlerPerformanceTest {

    private Pop3ProtocolHandler handler;
    private StubEndpoint endpoint;
    private TestPOP3Listener listener;
    private StubRealm realm;
    private StubMailboxFactory mailboxFactory;

    @Before
    public void setUp() {
        realm = new StubRealm();
        mailboxFactory = new StubMailboxFactory();

        listener = new TestPOP3Listener();
        listener.setRealm(realm);
        listener.setMailboxFactory(mailboxFactory);
        listener.setEnableAPOP(false);
        listener.setEnableUTF8(true);
        listener.setEnablePipelining(false);

        handler = new Pop3ProtocolHandler(listener);
        endpoint = new StubEndpoint();
    }

    private void connectPlaintext() {
        handler.connected(endpoint);
    }

    private void connectSecure() {
        endpoint.secure = true;
        handler.connected(endpoint);
        handler.securityEstablished(new StubSecurityInfo());
    }

    private void sendCommand(String command) {
        byte[] data = (command + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private String lastResponse() {
        List<String> responses = endpoint.getResponses();
        assertFalse("No responses sent", responses.isEmpty());
        return responses.get(responses.size() - 1);
    }

    private String allResponses() {
        StringBuilder sb = new StringBuilder();
        for (String r : endpoint.getResponses()) {
            sb.append(r).append("\n");
        }
        return sb.toString();
    }

    private List<String> responsesSince(int fromIndex) {
        List<String> all = endpoint.getResponses();
        return all.subList(fromIndex, all.size());
    }

    private void authenticateWithUserPass() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
    }

    private void authenticateWithHandler(
            RecordingTransactionHandler txHandler) {
        RecordingClientHandler clientHandler =
                new RecordingClientHandler();
        clientHandler.action = RecordingClientHandler.Action.ACCEPT;
        clientHandler.customGreeting = "Welcome";
        clientHandler.authAction =
                RecordingClientHandler.AuthAction.ACCEPT;
        clientHandler.txHandler = txHandler;
        listener.clientHandler = clientHandler;

        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Greeting tests
    // ═══════════════════════════════════════════════════════════════════







    // ═══════════════════════════════════════════════════════════════════
    // Streaming lexer tests (issue #85) — sliced-boundary and golden
    // transcript coverage, proving the Pop3ServerLexer conversion from
    // buffered-line parsing preserves identical semantic dispatch.
    // ═══════════════════════════════════════════════════════════════════

    // Mirrors the real transport contract (TcpEndpoint.processInbound()):
    // a single persistent buffer, compacted between receive() calls so
    // unconsumed bytes from a partial token are preserved and physically
    // moved forward, not a fresh isolated buffer per chunk.
    private void sendCommandSliced(String command, int chunkSize) {
        byte[] wire = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer netIn = ByteBuffer.allocate(1024);
        int offset = 0;
        while (offset < wire.length) {
            int len = Math.min(chunkSize, wire.length - offset);
            netIn.put(wire, offset, len);
            offset += len;
            netIn.flip();
            handler.receive(netIn);
            netIn.compact();
        }
    }













    // ═══════════════════════════════════════════════════════════════════
    // CAPA tests
    // ═══════════════════════════════════════════════════════════════════





























    // ═══════════════════════════════════════════════════════════════════
    // NOOP tests
    // ═══════════════════════════════════════════════════════════════════



    // ═══════════════════════════════════════════════════════════════════
    // USER/PASS authentication tests
    // ═══════════════════════════════════════════════════════════════════













    // ═══════════════════════════════════════════════════════════════════
    // APOP authentication tests
    // ═══════════════════════════════════════════════════════════════════





    // ═══════════════════════════════════════════════════════════════════
    // STLS tests
    // ═══════════════════════════════════════════════════════════════════







    // ═══════════════════════════════════════════════════════════════════
    // UTF8 tests
    // ═══════════════════════════════════════════════════════════════════





    // ═══════════════════════════════════════════════════════════════════
    // AUTH PLAIN tests
    // ═══════════════════════════════════════════════════════════════════







    // ═══════════════════════════════════════════════════════════════════
    // AUTH LOGIN tests
    // ═══════════════════════════════════════════════════════════════════







    // ═══════════════════════════════════════════════════════════════════
    // AUTH abort tests
    // ═══════════════════════════════════════════════════════════════════



    // ═══════════════════════════════════════════════════════════════════
    // AUTH mechanism listing
    // ═══════════════════════════════════════════════════════════════════



    // ═══════════════════════════════════════════════════════════════════
    // Issue #309: CRAM-MD5/DIGEST-MD5 challenge construction must not
    // block the SelectorLoop thread on a reverse-DNS lookup of the
    // endpoint's local address.
    // ═══════════════════════════════════════════════════════════════════

    // A raw byte-address InetAddress (not looked up from a hostname
    // string) has no cached name, so InetSocketAddress#getHostName() on
    // it must perform a real reverse lookup -- exactly the case
    // getHostString() is required to avoid. A distinct address per call
    // is essential: the JVM negative-caches a failed reverse lookup, so
    // repeating the *same* uncached address would only pay the lookup
    // cost once and mask the bug for every call after the first --
    // "series" keeps each test method's addresses disjoint from every
    // other test's too, so an earlier test populating the cache can't
    // mask a later one.
    private static InetSocketAddress addressWithNoCachedHostname(int series, int index) throws Exception {
        return new InetSocketAddress(
                java.net.InetAddress.getByAddress(
                        new byte[] { (byte) 10, (byte) series, (byte) (index >> 8), (byte) index }),
                110);
    }

    @Test(timeout = 15000)
    public void testAuthCramMd5ChallengeDoesNotBlockOnReverseDns() throws Exception {
        realm.supportedMechanisms.add(SaslMechanism.CRAM_MD5);
        connectPlaintext();
        endpoint.sentData.clear();

        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            endpoint.localAddress = addressWithNoCachedHostname(1, i);
            sendCommand("AUTH CRAM-MD5");
            assertTrue(lastResponse().startsWith("+ "));
            sendCommand("*");
            assertTrue(lastResponse().startsWith("-ERR"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue("200 AUTH CRAM-MD5 challenge/abort cycles against distinct local "
                + "addresses with no cached hostname took " + elapsedMs
                + "ms -- expected getHostString() (never resolves) rather than "
                + "getHostName() (attempts reverse DNS)", elapsedMs < 1000);
    }

    @Test(timeout = 15000)
    public void testAuthDigestMd5ChallengeDoesNotBlockOnReverseDns() throws Exception {
        realm.supportedMechanisms.add(SaslMechanism.DIGEST_MD5);
        connectPlaintext();
        endpoint.sentData.clear();

        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            endpoint.localAddress = addressWithNoCachedHostname(2, i);
            sendCommand("AUTH DIGEST-MD5");
            assertTrue(lastResponse().startsWith("+ "));
            sendCommand("*");
            assertTrue(lastResponse().startsWith("-ERR"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue("200 AUTH DIGEST-MD5 challenge/abort cycles against distinct local "
                + "addresses with no cached hostname took " + elapsedMs
                + "ms -- expected getHostString() (never resolves) rather than "
                + "getHostName() (attempts reverse DNS)", elapsedMs < 1000);
    }



    // ═══════════════════════════════════════════════════════════════════
    // Unknown command tests
    // ═══════════════════════════════════════════════════════════════════





    // ═══════════════════════════════════════════════════════════════════
    // STAT tests
    // ═══════════════════════════════════════════════════════════════════





    // ═══════════════════════════════════════════════════════════════════
    // LIST tests
    // ═══════════════════════════════════════════════════════════════════











    // ═══════════════════════════════════════════════════════════════════
    // RETR tests
    // ═══════════════════════════════════════════════════════════════════











    // ═══════════════════════════════════════════════════════════════════
    // DELE tests
    // ═══════════════════════════════════════════════════════════════════











    // ═══════════════════════════════════════════════════════════════════
    // RSET tests
    // ═══════════════════════════════════════════════════════════════════







    // ═══════════════════════════════════════════════════════════════════
    // TOP tests
    // ═══════════════════════════════════════════════════════════════════













    // ═══════════════════════════════════════════════════════════════════
    // UIDL tests
    // ═══════════════════════════════════════════════════════════════════















    // ═══════════════════════════════════════════════════════════════════
    // QUIT tests
    // ═══════════════════════════════════════════════════════════════════







    // ═══════════════════════════════════════════════════════════════════
    // Dot-stuffing on RETR tests
    // ═══════════════════════════════════════════════════════════════════



    // ═══════════════════════════════════════════════════════════════════
    // Disconnection tests
    // ═══════════════════════════════════════════════════════════════════





    // ═══════════════════════════════════════════════════════════════════
    // Error handling tests
    // ═══════════════════════════════════════════════════════════════════



    // ═══════════════════════════════════════════════════════════════════
    // ConnectedState handler tests (via Pop3Server)
    // ═══════════════════════════════════════════════════════════════════









    // ═══════════════════════════════════════════════════════════════════
    // TransactionHandler delegation tests
    // ═══════════════════════════════════════════════════════════════════



















    // ═══════════════════════════════════════════════════════════════════
    // Full session flow tests
    // ═══════════════════════════════════════════════════════════════════







    // ═══════════════════════════════════════════════════════════════════
    // AuthorizationHandler delegation tests
    // ═══════════════════════════════════════════════════════════════════





    // ═══════════════════════════════════════════════════════════════════
    // AUTH GSSAPI not available
    // ═══════════════════════════════════════════════════════════════════



    // ═══════════════════════════════════════════════════════════════════
    // Negative message number tests
    // ═══════════════════════════════════════════════════════════════════





    // ═══════════════════════════════════════════════════════════════════
    // Stub implementations
    // ═══════════════════════════════════════════════════════════════════

    static class TestPOP3Listener extends Pop3Listener {
        boolean starttlsAvailable = false;
        ClientConnected clientHandler;

        @Override
        protected boolean isSTARTTLSAvailable() {
            return starttlsAvailable;
        }

        @Override
        public org.bluezoo.gumdrop.pop3.server.Pop3Server getServer() {
            if (clientHandler == null) {
                return null;
            }
            return new TestPOP3Service(clientHandler);
        }
    }

    static class TestPOP3Service extends Pop3Server {
        private final ClientConnected handler;

        TestPOP3Service(ClientConnected handler) {
            this.handler = handler;
        }

        @Override
        public ClientConnected openSession(TcpListener endpoint) {
            return handler;
        }
    }

    static class StubEndpoint implements Endpoint {
        final List<byte[]> sentData = new ArrayList<byte[]>();
        boolean open = true;
        boolean startTLSCalled;
        boolean secure;
        SocketAddress localAddress = new InetSocketAddress("127.0.0.1", 110);

        @Override
        public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sentData.add(bytes);
        }

        List<String> getResponses() {
            List<String> result = new ArrayList<String>();
            for (byte[] data : sentData) {
                String s = new String(data, StandardCharsets.US_ASCII);
                String[] lines = s.split("\r\n", -1);
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        result.add(line);
                    }
                }
            }
            return result;
        }

        @Override public boolean isOpen() { return open; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { open = false; }
        @Override public SocketAddress getLocalAddress() {
            return localAddress;
        }
        @Override public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public boolean isSecure() { return secure; }
        @Override public SecurityInfo getSecurityInfo() {
            return new StubSecurityInfo();
        }
        @Override public void startTLS() { startTLSCalled = true; }
        @Override public SelectorLoop getSelectorLoop() {
            return null;
        }
        @Override public void execute(Runnable task) {
            task.run();
        }
        @Override public TimerHandle scheduleTimer(long delayMs,
                                                   Runnable cb) {
            return new TimerHandle() {
                @Override public void cancel() {}
                @Override public boolean isCancelled() {
                    return false;
                }
            };
        }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) {}
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() {
            return null;
        }
        @Override public void pauseRead() {}
        @Override public void resumeRead() {}
        @Override public void onWriteReady(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }
    }

    static class StubSecurityInfo implements SecurityInfo {
        @Override public String getProtocol() { return "TLSv1.3"; }
        @Override public String getCipherSuite() {
            return "TLS_AES_256_GCM_SHA384";
        }
        @Override public int getKeySize() { return 256; }
        @Override public Certificate[] getPeerCertificates() {
            return null;
        }
        @Override public Certificate[] getLocalCertificates() {
            return null;
        }
        @Override public String getApplicationProtocol() {
            return null;
        }
        @Override public long getHandshakeDurationMs() { return 5; }
        @Override public boolean isSessionResumed() { return false; }
    }

    static class StubRealm implements Realm {
        Set<SaslMechanism> supportedMechanisms =
                new HashSet<SaslMechanism>();

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return Collections.unmodifiableSet(supportedMechanisms);
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return "testuser".equals(username)
                    && "testpass".equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realm) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            if ("testuser".equals(username)) {
                return "testpass";
            }
            return null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }
    }

    static class StubMailboxFactory implements MailboxFactory {
        String messageContent = "Subject: Test\r\n\r\nTest body\r\n";
        int stubMessageCount = 3;
        StubMailboxStore lastStore;
        StubMailbox lastMailbox;

        @Override
        public MailboxStore createStore() {
            lastStore = new StubMailboxStore(this);
            return lastStore;
        }
    }

    static class StubMailboxStore implements MailboxStore {
        final StubMailboxFactory factory;
        boolean closed;

        StubMailboxStore(StubMailboxFactory factory) {
            this.factory = factory;
        }

        @Override
        public void open(String username) {}

        @Override
        public void close() { closed = true; }

        @Override
        public char getHierarchyDelimiter() { return '/'; }

        @Override
        public List<String> listMailboxes(String ref, String pattern) {
            return Collections.singletonList("INBOX");
        }

        @Override
        public List<String> listSubscribed(String ref, String pattern) {
            return Collections.singletonList("INBOX");
        }

        @Override
        public void subscribe(String name) {}

        @Override
        public void unsubscribe(String name) {}

        @Override
        public Mailbox openMailbox(String name, boolean readOnly) {
            StubMailbox mbox = new StubMailbox(factory.messageContent,
                    factory.stubMessageCount);
            factory.lastMailbox = mbox;
            return mbox;
        }

        @Override
        public void createMailbox(String name) {}

        @Override
        public void deleteMailbox(String name) {}

        @Override
        public void renameMailbox(String oldName, String newName) {}

        @Override
        public Set<org.bluezoo.gumdrop.mailbox.MailboxAttribute>
                getMailboxAttributes(String name) {
            return Collections.emptySet();
        }
    }

    static class StubMailbox implements Mailbox {
        private final List<StubMessage> messages;
        private final Set<Integer> deleted = new HashSet<Integer>();
        int getMessageListCallCount;
        boolean closed;
        boolean closedWithExpunge;
        private final String messageContent;

        StubMailbox(String content) {
            this(content, 3);
        }

        StubMailbox(String content, int messageCount) {
            this.messageContent = content;
            messages = new ArrayList<StubMessage>();
            for (int i = 1; i <= messageCount; i++) {
                messages.add(new StubMessage(i, 500, "msg-uid-" + i));
            }
        }

        @Override
        public void close(boolean expunge) {
            closed = true;
            closedWithExpunge = expunge;
        }

        @Override
        public int getMessageCount() {
            int count = 0;
            for (StubMessage msg : messages) {
                if (!deleted.contains(msg.number)) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public long getMailboxSize() {
            long size = 0;
            for (StubMessage msg : messages) {
                if (!deleted.contains(msg.number)) {
                    size += msg.size;
                }
            }
            return size;
        }

        @Override
        public Iterator<MessageDescriptor> getMessageList() {
            getMessageListCallCount++;
            List<MessageDescriptor> result =
                    new ArrayList<MessageDescriptor>();
            for (StubMessage msg : messages) {
                if (!deleted.contains(msg.number)) {
                    result.add(msg);
                }
            }
            return result.iterator();
        }

        @Override
        public MessageDescriptor getMessage(int messageNumber) {
            for (StubMessage msg : messages) {
                if (msg.number == messageNumber) {
                    return msg;
                }
            }
            return null;
        }

        @Override
        public ReadableByteChannel getMessageContent(int msgNum) {
            return Channels.newChannel(new ByteArrayInputStream(
                    messageContent.getBytes(StandardCharsets.US_ASCII)));
        }

        @Override
        public ReadableByteChannel getMessageTop(int msgNum,
                                                  int bodyLines) {
            return Channels.newChannel(new ByteArrayInputStream(
                    messageContent.getBytes(StandardCharsets.US_ASCII)));
        }

        @Override
        public void deleteMessage(int messageNumber) {
            deleted.add(messageNumber);
        }

        @Override
        public boolean isDeleted(int messageNumber) {
            return deleted.contains(messageNumber);
        }

        @Override
        public void undeleteAll() {
            deleted.clear();
        }

        @Override
        public String getUniqueId(int messageNumber) {
            for (StubMessage msg : messages) {
                if (msg.number == messageNumber) {
                    return msg.uid;
                }
            }
            return null;
        }
    }

    static class StubMessage implements MessageDescriptor {
        final int number;
        final long size;
        final String uid;

        StubMessage(int number, long size, String uid) {
            this.number = number;
            this.size = size;
            this.uid = uid;
        }

        @Override
        public int getMessageNumber() { return number; }

        @Override
        public long getSize() { return size; }

        @Override
        public String getUniqueId() { return uid; }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Recording handler implementations
    // ═══════════════════════════════════════════════════════════════════

    static class RecordingClientHandler
            implements ClientConnected, AuthorizationHandler {

        enum Action {
            ACCEPT, REJECT, REJECT_MESSAGE, SHUTTING_DOWN
        }

        enum AuthAction {
            ACCEPT, REJECT, REJECT_CLOSE
        }

        Action action = Action.ACCEPT;
        AuthAction authAction = AuthAction.ACCEPT;
        String customGreeting = "Welcome";
        String rejectMessage;
        TransactionHandler txHandler;

        boolean connectCalled;
        boolean disconnectCalled;
        boolean authenticateCalled;

        @Override
        public void connected(ConnectedState state,
                              Endpoint endpoint) {
            connectCalled = true;
            switch (action) {
                case ACCEPT:
                    state.acceptConnection(customGreeting, this);
                    break;
                case REJECT:
                    state.rejectConnection();
                    break;
                case REJECT_MESSAGE:
                    state.rejectConnection(rejectMessage);
                    break;
                case SHUTTING_DOWN:
                    state.serverShuttingDown();
                    break;
            }
        }

        @Override
        public void disconnected() {
            disconnectCalled = true;
        }

        @Override
        public void authenticate(AuthenticateState state,
                                 Principal principal,
                                 MailboxFactory factory) {
            authenticateCalled = true;
            switch (authAction) {
                case ACCEPT:
                    try {
                        MailboxStore store = factory.createStore();
                        store.open(principal.getName());
                        Mailbox mbox =
                                store.openMailbox("INBOX", false);
                        state.accept(mbox, txHandler);
                    } catch (IOException e) {
                        state.reject("Mailbox error", this);
                    }
                    break;
                case REJECT:
                    state.reject("Auth denied", this);
                    break;
                case REJECT_CLOSE:
                    state.rejectAndClose("Go away");
                    break;
            }
        }
    }

    static class RecordingTransactionHandler
            implements TransactionHandler {

        boolean statCalled;
        boolean listCalled;
        int listMessageNumber;
        boolean retrCalled;
        int retrMessageNumber;
        boolean deleCalled;
        int deleMessageNumber;
        boolean rsetCalled;
        boolean topCalled;
        int topMessageNumber;
        int topLines;
        boolean uidlCalled;
        int uidlMessageNumber;
        boolean quitCalled;

        @Override
        public void mailboxStatus(MailboxStatusState state,
                                  Mailbox mailbox) {
            statCalled = true;
            try {
                state.sendStatus(mailbox.getMessageCount(),
                        mailbox.getMailboxSize(), this);
            } catch (IOException e) {
                state.error("Error", this);
            }
        }

        @Override
        public void list(ListState state, Mailbox mailbox,
                         int messageNumber) {
            listCalled = true;
            listMessageNumber = messageNumber;
        }

        @Override
        public void retrieveMessage(RetrieveState state,
                                    Mailbox mailbox,
                                    int messageNumber) {
            retrCalled = true;
            retrMessageNumber = messageNumber;
        }

        @Override
        public void markDeleted(MarkDeletedState state,
                                Mailbox mailbox,
                                int messageNumber) {
            deleCalled = true;
            deleMessageNumber = messageNumber;
        }

        @Override
        public void reset(ResetState state, Mailbox mailbox) {
            rsetCalled = true;
        }

        @Override
        public void top(TopState state, Mailbox mailbox,
                        int messageNumber, int lines) {
            topCalled = true;
            topMessageNumber = messageNumber;
            topLines = lines;
        }

        @Override
        public void uidl(UidlState state, Mailbox mailbox,
                         int messageNumber) {
            uidlCalled = true;
            uidlMessageNumber = messageNumber;
        }

        @Override
        public void quit(UpdateState state, Mailbox mailbox) {
            quitCalled = true;
            state.commitAndClose("Goodbye");
        }
    }

}
