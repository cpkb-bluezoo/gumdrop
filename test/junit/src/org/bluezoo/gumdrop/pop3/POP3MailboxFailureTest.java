/*
 * POP3MailboxFailureTest.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Checks the error replies {@link Pop3ProtocolHandler} sends when the
 * mailbox backend throws {@link IOException} from each transaction command,
 * and the CAPA output for the optional pipelining and APOP capabilities.
 * The backend is a mock that delegates to the shared stub mailbox and fails
 * on demand.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3MailboxFailureTest {

    private Pop3ProtocolHandler handler;
    private POP3AuthFlowsTest.TimerEndpoint endpoint;
    private POP3ProtocolHandlerTest.TestPOP3Listener listener;
    private FailingMailbox failing;
    private POP3ProtocolHandlerTest.StubRealm realm;

    @Before
    public void setUp() throws IOException {
        POP3ProtocolHandlerTest.StubMailboxFactory factory = new MockFactory();
        listener = new POP3ProtocolHandlerTest.TestPOP3Listener();
        realm = new POP3ProtocolHandlerTest.StubRealm();
        listener.setRealm(realm);
        listener.setMailboxFactory(factory);
        listener.setEnableAPOP(false);
        endpoint = new POP3AuthFlowsTest.TimerEndpoint();
    }

    private void login() {
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
        send("USER testuser");
        send("PASS testpass");
        endpoint.sentData.clear();
    }

    private void send(String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
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

    @Test
    public void testStatFailureReportsCannotAccess() {
        login();
        failing.failCount = true;
        send("STAT");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testListAllFailureReportsCannotAccess() {
        login();
        failing.failList = true;
        send("LIST");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testListSingleFailureReportsCannotAccess() {
        login();
        failing.failGetMessage = true;
        send("LIST 1");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testUidlAllFailureReportsCannotAccess() {
        login();
        failing.failList = true;
        send("UIDL");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testUidlSingleFailureReportsCannotAccess() {
        login();
        failing.failGetMessage = true;
        send("UIDL 1");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testRetrFailureReportsCannotRetrieve() {
        login();
        failing.failIsDeleted = true;
        send("RETR 1");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testTopFailureReportsError() {
        login();
        failing.failIsDeleted = true;
        send("TOP 1 1");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testDeleFailureReportsCannotDelete() {
        login();
        failing.failDelete = true;
        send("DELE 1");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testRsetFailureReportsCannotReset() {
        login();
        failing.failUndelete = true;
        send("RSET");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testQuitCloseFailureReportsPartialUpdate() {
        login();
        failing.failClose = true;
        send("QUIT");
        assertTrue(last(), last().startsWith("-ERR"));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testCapaListsPipeliningAndApopWhenEnabled() {
        listener.setEnableAPOP(true);
        listener.setEnablePipelining(true);
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
        endpoint.sentData.clear();
        send("CAPA");
        String capa = all();
        assertTrue(capa, capa.contains("PIPELINING"));
        assertTrue(capa, capa.contains("APOP"));
    }

    @Test
    public void testCapaOmitsPipeliningByDefault() {
        listener.setEnablePipelining(false);
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
        endpoint.sentData.clear();
        send("CAPA");
        assertFalse(all(), all().contains("PIPELINING"));
        assertEquals(".", last());
    }


    @Test
    public void testUidlRejectsBadAndMissingMessageNumbers() {
        login();
        send("UIDL abc");
        assertTrue(last(), last().startsWith("-ERR"));
        send("UIDL 99");
        assertTrue(last(), last().startsWith("-ERR"));
        send("DELE 2");
        send("UIDL 2");
        assertTrue(last(), last().startsWith("-ERR"));
        send("UIDL 1");
        assertEquals("+OK 1 msg-uid-1", last());
    }

    @Test
    public void testListRejectsBadAndMissingMessageNumbers() {
        login();
        send("LIST abc");
        assertTrue(last(), last().startsWith("-ERR"));
        send("LIST 99");
        assertTrue(last(), last().startsWith("-ERR"));
        send("DELE 2");
        send("LIST 2");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testTopRejectsNegativeLinesAndMissingMessage() {
        login();
        send("TOP 1 -1");
        assertTrue(last(), last().startsWith("-ERR"));
        send("TOP 1");
        assertTrue(last(), last().startsWith("-ERR"));
        send("TOP 99 1");
        assertTrue(last(), last().startsWith("-ERR"));
        send("RETR 99");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void testAuthExternalOnSecureConnectionWithoutCertificate() {
        endpoint.secure = true;
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
        handler.securityEstablished(new POP3ProtocolHandlerTest.StubSecurityInfo());
        endpoint.sentData.clear();
        send("AUTH EXTERNAL =");
        assertTrue(last(), last().startsWith("-ERR"));
        send("AUTH EXTERNAL !!!notbase64");
        assertTrue(last(), last().startsWith("-ERR"));
        send("AUTH EXTERNAL dXNlcg==");
        assertTrue(last(), last().startsWith("-ERR"));
        send("AUTH");
        assertEquals(".", last());
        assertTrue(all(), all().contains("Supported authentication"));
    }

    @Test
    public void testDigestMd5RejectsInitialResponseAndLoginBadInitial() {
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
        endpoint.sentData.clear();
        send("AUTH DIGEST-MD5 abcd");
        assertTrue(last(), last().startsWith("-ERR"));
        send("AUTH LOGIN !!!");
        assertTrue(last(), last().startsWith("-ERR"));
        send("AUTH LOGIN dGVzdHVzZXI=");
        assertTrue(last(), last().startsWith("+"));
        send("AUTH NOSUCHMECH");
        assertTrue(last(), last().startsWith("-ERR"));
    }


    @Test
    public void testLeadingSpaceAndBareLinefeedAreHandledByLexer() {
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
        endpoint.sentData.clear();
        handler.receive(ByteBuffer.wrap(" NOOP\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(1, endpoint.getResponses().size());
        assertTrue(last(), last().startsWith("-ERR"));
        handler.receive(ByteBuffer.wrap("NOOP\nNOOP\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(last(), last().startsWith("-ERR"));
        assertTrue(endpoint.isOpen());
    }


    @Test
    public void testCapaSaslListDependsOnTransportSecurity() {
        realm.supportedMechanisms.add(SaslMechanism.PLAIN);
        realm.supportedMechanisms.add(SaslMechanism.EXTERNAL);
        realm.supportedMechanisms.add(SaslMechanism.CRAM_MD5);
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
        endpoint.sentData.clear();
        send("CAPA");
        String plain = saslLine();
        assertTrue(plain, plain.contains("CRAM-MD5"));
        assertFalse(plain, plain.contains("PLAIN"));
        assertFalse(plain, plain.contains("EXTERNAL"));

        POP3AuthFlowsTest.TimerEndpoint secure = new POP3AuthFlowsTest.TimerEndpoint();
        secure.secure = true;
        endpoint = secure;
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
        handler.securityEstablished(new POP3ProtocolHandlerTest.StubSecurityInfo());
        endpoint.sentData.clear();
        send("CAPA");
        String tls = saslLine();
        assertTrue(tls, tls.contains("PLAIN"));
        assertTrue(tls, tls.contains("EXTERNAL"));
    }

    private String saslLine() {
        for (String line : endpoint.getResponses()) {
            if (line.startsWith("SASL")) {
                return line;
            }
        }
        return "";
    }

    @Test
    public void testAuthWithTrailingSpaceAsksForTheInitialResponse() {
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
        endpoint.sentData.clear();
        String[] mechanisms = {"PLAIN", "LOGIN", "OAUTHBEARER", "SCRAM-SHA-256", "CRAM-MD5"};
        for (int i = 0; i < mechanisms.length; i++) {
            endpoint.sentData.clear();
            handler.receive(ByteBuffer.wrap(("AUTH " + mechanisms[i] + " \r\n").getBytes(StandardCharsets.US_ASCII)));
            assertTrue(mechanisms[i] + " -> " + last(), last().startsWith("+"));
            send("*");
            assertTrue(last(), last().startsWith("-ERR"));
        }
    }

    // ── Mocks ──

    private final class MockFactory extends POP3ProtocolHandlerTest.StubMailboxFactory {
        @Override
        public MailboxStore createStore() {
            return new POP3ProtocolHandlerTest.StubMailboxStore(this) {
                @Override
                public Mailbox openMailbox(String name, boolean readOnly) throws IOException {
                    Mailbox inner = super.openMailbox(name, readOnly);
                    failing = new FailingMailbox(inner);
                    return failing;
                }
            };
        }
    }

    /** Mailbox that delegates to a stub and throws on demand. */
    private static final class FailingMailbox implements Mailbox {
        private final Mailbox inner;
        boolean failCount;
        boolean failList;
        boolean failGetMessage;
        boolean failIsDeleted;
        boolean failDelete;
        boolean failUndelete;
        boolean failClose;

        FailingMailbox(Mailbox inner) {
            this.inner = inner;
        }

        @Override
        public void close(boolean expunge) throws IOException {
            if (failClose) {
                throw new IOException("close failed");
            }
            inner.close(expunge);
        }

        @Override
        public int getMessageCount() throws IOException {
            if (failCount) {
                throw new IOException("count failed");
            }
            return inner.getMessageCount();
        }

        @Override
        public long getMailboxSize() throws IOException {
            return inner.getMailboxSize();
        }

        @Override
        public Iterator<MessageDescriptor> getMessageList() throws IOException {
            if (failList) {
                throw new IOException("list failed");
            }
            return inner.getMessageList();
        }

        @Override
        public MessageDescriptor getMessage(int messageNumber) throws IOException {
            if (failGetMessage) {
                throw new IOException("get failed");
            }
            return inner.getMessage(messageNumber);
        }

        @Override
        public ReadableByteChannel getMessageContent(int messageNumber) throws IOException {
            return inner.getMessageContent(messageNumber);
        }

        @Override
        public ReadableByteChannel getMessageTop(int messageNumber, int bodyLines) throws IOException {
            return inner.getMessageTop(messageNumber, bodyLines);
        }

        @Override
        public void deleteMessage(int messageNumber) throws IOException {
            if (failDelete) {
                throw new IOException("delete failed");
            }
            inner.deleteMessage(messageNumber);
        }

        @Override
        public boolean isDeleted(int messageNumber) throws IOException {
            if (failIsDeleted) {
                throw new IOException("deleted check failed");
            }
            return inner.isDeleted(messageNumber);
        }

        @Override
        public void undeleteAll() throws IOException {
            if (failUndelete) {
                throw new IOException("undelete failed");
            }
            inner.undeleteAll();
        }

        @Override
        public String getUniqueId(int messageNumber) throws IOException {
            return inner.getUniqueId(messageNumber);
        }
    }
}
