/*
 * POP3ProtocolHandlerTest.java
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
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.pop3.handler.AuthenticateState;
import org.bluezoo.gumdrop.pop3.handler.AuthorizationHandler;
import org.bluezoo.gumdrop.pop3.handler.ClientConnected;
import org.bluezoo.gumdrop.pop3.handler.ConnectedState;
import org.bluezoo.gumdrop.pop3.handler.ListState;
import org.bluezoo.gumdrop.pop3.handler.MailboxStatusState;
import org.bluezoo.gumdrop.pop3.handler.MarkDeletedState;
import org.bluezoo.gumdrop.pop3.handler.ResetState;
import org.bluezoo.gumdrop.pop3.handler.RetrieveState;
import org.bluezoo.gumdrop.pop3.handler.TopState;
import org.bluezoo.gumdrop.pop3.handler.TransactionHandler;
import org.bluezoo.gumdrop.pop3.handler.UidlState;
import org.bluezoo.gumdrop.pop3.handler.UpdateState;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link POP3ProtocolHandler}.
 *
 * <p>Tests the POP3 server state machine by simulating client commands
 * through a stub Endpoint and verifying the responses sent back. Uses
 * stub implementations of Realm, Mailbox, MailboxFactory, and
 * MailboxStore for isolated unit testing without real I/O.
 */
public class POP3ProtocolHandlerTest {

    private POP3ProtocolHandler handler;
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

        handler = new POP3ProtocolHandler(listener);
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

    @Test
    public void testPlaintextGreeting() {
        connectPlaintext();
        String response = lastResponse();
        assertTrue("Should send +OK greeting",
                response.startsWith("+OK"));
        assertTrue("Should contain greeting text",
                response.contains("POP3 server ready"));
    }

    @Test
    public void testPlaintextGreetingWithAPOP() {
        listener.setEnableAPOP(true);
        handler = new POP3ProtocolHandler(listener);
        connectPlaintext();
        String response = lastResponse();
        assertTrue(response.startsWith("+OK"));
        assertTrue("APOP greeting should contain timestamp",
                response.contains("<") && response.contains(">"));
    }

    @Test
    public void testSecureGreetingDeferredUntilTLS() {
        endpoint.secure = true;
        handler.connected(endpoint);
        assertTrue("No greeting before TLS established",
                endpoint.getResponses().isEmpty());
        handler.securityEstablished(new StubSecurityInfo());
        assertFalse("Greeting sent after TLS",
                endpoint.getResponses().isEmpty());
        assertTrue(lastResponse().startsWith("+OK"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAPA tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testCAPAIncludesBasicCapabilities() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("CAPA");
        String all = allResponses();
        assertTrue(all.contains("+OK"));
        assertTrue("Should include USER", all.contains("USER"));
        assertTrue("Should include UIDL", all.contains("UIDL"));
        assertTrue("Should include TOP", all.contains("TOP"));
        assertTrue("Should include IMPLEMENTATION",
                all.contains("IMPLEMENTATION gumdrop"));
        assertTrue("Should end with dot", lastResponse().equals("."));
    }

    @Test
    public void testCAPAIncludesUTF8WhenEnabled() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("CAPA");
        String all = allResponses();
        assertTrue("Should include UTF8", all.contains("UTF8"));
    }

    @Test
    public void testCAPAExcludesUTF8WhenDisabled() {
        listener.setEnableUTF8(false);
        handler = new POP3ProtocolHandler(listener);
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("CAPA");
        List<String> lines = endpoint.getResponses();
        boolean hasUtf8 = false;
        for (String line : lines) {
            if (line.equals("UTF8")) {
                hasUtf8 = true;
            }
        }
        assertFalse("Should not include UTF8", hasUtf8);
    }

    @Test
    public void testCAPAIncludesSASLWithRealm() {
        realm.supportedMechanisms.add(SASLMechanism.PLAIN);
        realm.supportedMechanisms.add(SASLMechanism.LOGIN);
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("CAPA");
        String all = allResponses();
        assertTrue("Should include SASL line", all.contains("SASL"));
    }

    @Test
    public void testCAPAIncludesSTLSWhenAvailable() {
        listener.starttlsAvailable = true;
        handler = new POP3ProtocolHandler(listener);
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("CAPA");
        String all = allResponses();
        assertTrue("Should include STLS", all.contains("STLS"));
    }

    @Test
    public void testCAPAExcludesSTLSWhenSecure() {
        listener.starttlsAvailable = true;
        handler = new POP3ProtocolHandler(listener);
        connectSecure();
        endpoint.sentData.clear();
        sendCommand("CAPA");
        List<String> lines = endpoint.getResponses();
        boolean hasStls = false;
        for (String line : lines) {
            if (line.equals("STLS")) {
                hasStls = true;
            }
        }
        assertFalse("Should not include STLS when already secure",
                hasStls);
    }

    // ═══════════════════════════════════════════════════════════════════
    // NOOP tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testNOOP() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("NOOP");
        assertTrue(lastResponse().startsWith("+OK"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // USER/PASS authentication tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testUSERAccepted() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("USER testuser");
        assertTrue(lastResponse().startsWith("+OK"));
        assertTrue(lastResponse().contains("User accepted"));
    }

    @Test
    public void testUSERRequiresArgument() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("USER");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testPASSWithoutUSER() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("PASS secret");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testPASSSuccess() {
        authenticateWithUserPass();
        String response = lastResponse();
        assertTrue("Should get +OK on auth success",
                response.startsWith("+OK"));
        assertTrue(response.contains("Mailbox opened"));
    }

    @Test
    public void testPASSFailure() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("USER testuser");
        sendCommand("PASS wrongpass");
        assertTrue(lastResponse().startsWith("-ERR"));
        assertTrue(lastResponse().contains("Authentication failed"));
    }

    @Test
    public void testPASSWithNoRealm() {
        listener.setRealm(null);
        handler = new POP3ProtocolHandler(listener);
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // APOP authentication tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testAPOPNotSupportedWhenDisabled() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("APOP user digest");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testAPOPRequiresArguments() {
        listener.setEnableAPOP(true);
        handler = new POP3ProtocolHandler(listener);
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("APOP onlyuser");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // STLS tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testSTLSWhenNotSupported() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("STLS");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testSTLSWhenAlreadySecure() {
        listener.starttlsAvailable = true;
        handler = new POP3ProtocolHandler(listener);
        connectSecure();
        endpoint.sentData.clear();
        sendCommand("STLS");
        assertTrue(lastResponse().startsWith("-ERR"));
        assertTrue(lastResponse().contains("Already using TLS"));
    }

    @Test
    public void testSTLSSuccess() {
        listener.starttlsAvailable = true;
        handler = new POP3ProtocolHandler(listener);
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("STLS");
        assertTrue(lastResponse().startsWith("+OK"));
        assertTrue(lastResponse().contains("Begin TLS"));
        assertTrue("startTLS should be called",
                endpoint.startTLSCalled);
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTF8 tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testUTF8Enabled() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("UTF8");
        assertTrue(lastResponse().startsWith("+OK"));
        assertTrue(lastResponse().contains("UTF8 mode enabled"));
    }

    @Test
    public void testUTF8Disabled() {
        listener.setEnableUTF8(false);
        handler = new POP3ProtocolHandler(listener);
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("UTF8");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTH PLAIN tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testAuthPlainWithInitialResponse() {
        connectPlaintext();
        endpoint.sentData.clear();
        String creds = Base64.getEncoder().encodeToString(
                "\0testuser\0testpass".getBytes(StandardCharsets.US_ASCII));
        sendCommand("AUTH PLAIN " + creds);
        assertTrue(lastResponse().startsWith("+OK"));
        assertTrue(lastResponse().contains("Mailbox opened"));
    }

    @Test
    public void testAuthPlainWithContinuation() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("AUTH PLAIN");
        assertTrue("Should get continuation",
                lastResponse().startsWith("+ "));
        String creds = Base64.getEncoder().encodeToString(
                "\0testuser\0testpass".getBytes(StandardCharsets.US_ASCII));
        sendCommand(creds);
        assertTrue(lastResponse().startsWith("+OK"));
    }

    @Test
    public void testAuthPlainFailure() {
        connectPlaintext();
        endpoint.sentData.clear();
        String creds = Base64.getEncoder().encodeToString(
                "\0testuser\0wrongpass".getBytes(
                        StandardCharsets.US_ASCII));
        sendCommand("AUTH PLAIN " + creds);
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTH LOGIN tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testAuthLoginSuccess() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("AUTH LOGIN");
        assertTrue(lastResponse().startsWith("+ "));
        String userB64 = Base64.getEncoder().encodeToString(
                "testuser".getBytes(StandardCharsets.US_ASCII));
        sendCommand(userB64);
        assertTrue(lastResponse().startsWith("+ "));
        String passB64 = Base64.getEncoder().encodeToString(
                "testpass".getBytes(StandardCharsets.US_ASCII));
        sendCommand(passB64);
        assertTrue(lastResponse().startsWith("+OK"));
    }

    @Test
    public void testAuthLoginWithInitialUsername() {
        connectPlaintext();
        endpoint.sentData.clear();
        String userB64 = Base64.getEncoder().encodeToString(
                "testuser".getBytes(StandardCharsets.US_ASCII));
        sendCommand("AUTH LOGIN " + userB64);
        assertTrue("Should prompt for password",
                lastResponse().startsWith("+ "));
        String passB64 = Base64.getEncoder().encodeToString(
                "testpass".getBytes(StandardCharsets.US_ASCII));
        sendCommand(passB64);
        assertTrue(lastResponse().startsWith("+OK"));
    }

    @Test
    public void testAuthLoginFailure() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("AUTH LOGIN");
        String userB64 = Base64.getEncoder().encodeToString(
                "testuser".getBytes(StandardCharsets.US_ASCII));
        sendCommand(userB64);
        String passB64 = Base64.getEncoder().encodeToString(
                "wrong".getBytes(StandardCharsets.US_ASCII));
        sendCommand(passB64);
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTH abort tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testAuthAbortWithStar() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("AUTH PLAIN");
        assertTrue(lastResponse().startsWith("+ "));
        sendCommand("*");
        assertTrue(lastResponse().startsWith("-ERR"));
        assertTrue(lastResponse().contains("aborted"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTH mechanism listing
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testAuthListMechanisms() {
        realm.supportedMechanisms.add(SASLMechanism.CRAM_MD5);
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("AUTH");
        String all = allResponses();
        assertTrue(all.contains("+OK"));
        assertTrue(all.contains("CRAM-MD5"));
        assertTrue(lastResponse().equals("."));
    }

    @Test
    public void testUnsupportedAuthMechanism() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("AUTH XYZZY");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Unknown command tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testUnknownCommandInAuthorizationState() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("BOGUS");
        assertTrue(lastResponse().startsWith("-ERR"));
        assertTrue(lastResponse().contains("Unknown command"));
    }

    @Test
    public void testUnknownCommandInTransactionState() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("BOGUS");
        assertTrue(lastResponse().startsWith("-ERR"));
        assertTrue(lastResponse().contains("Unknown command"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // STAT tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testSTAT() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("STAT");
        String response = lastResponse();
        assertTrue(response.startsWith("+OK"));
        assertTrue("Response should contain message count and size",
                response.contains("3 1500"));
    }

    @Test
    public void testSTATWithoutMailbox() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("STAT");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIST tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testLISTAll() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("LIST");
        List<String> responses = endpoint.getResponses();
        assertTrue(responses.get(0).startsWith("+OK"));
        assertTrue(responses.get(0).contains("3 messages"));
        assertEquals("1 500", responses.get(1));
        assertEquals("2 500", responses.get(2));
        assertEquals("3 500", responses.get(3));
        assertEquals(".", responses.get(4));
    }

    @Test
    public void testLISTSingleMessage() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("LIST 2");
        String response = lastResponse();
        assertTrue(response.startsWith("+OK"));
        assertTrue(response.contains("2 500"));
    }

    @Test
    public void testLISTInvalidMessageNumber() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("LIST 999");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testLISTNonNumericArgument() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("LIST abc");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testLISTWithoutMailbox() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("LIST");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // RETR tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testRETR() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("RETR 1");
        List<String> responses = endpoint.getResponses();
        assertTrue(responses.get(0).startsWith("+OK"));
        assertTrue(responses.get(0).contains("octets"));
        assertEquals(".", responses.get(responses.size() - 1));
    }

    @Test
    public void testRETRDeletedMessage() {
        authenticateWithUserPass();
        sendCommand("DELE 1");
        endpoint.sentData.clear();
        sendCommand("RETR 1");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testRETRInvalidNumber() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("RETR");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testRETRNoSuchMessage() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("RETR 999");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testRETRWithoutMailbox() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("RETR 1");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // DELE tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testDELE() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("DELE 1");
        assertTrue(lastResponse().startsWith("+OK"));
        assertTrue(lastResponse().contains("deletion"));
    }

    @Test
    public void testDELEAlreadyDeleted() {
        authenticateWithUserPass();
        sendCommand("DELE 1");
        endpoint.sentData.clear();
        sendCommand("DELE 1");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testDELENoSuchMessage() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("DELE 999");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testDELEInvalidNumber() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("DELE");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testDELEWithoutMailbox() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("DELE 1");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // RSET tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testRSET() {
        authenticateWithUserPass();
        sendCommand("DELE 1");
        endpoint.sentData.clear();
        sendCommand("RSET");
        assertTrue(lastResponse().startsWith("+OK"));
        assertTrue(lastResponse().contains("reset"));
    }

    @Test
    public void testRSETRestoresDeletedMessages() {
        authenticateWithUserPass();
        sendCommand("DELE 1");
        sendCommand("RSET");
        endpoint.sentData.clear();
        sendCommand("RETR 1");
        assertTrue("RETR after RSET should succeed",
                endpoint.getResponses().get(0).startsWith("+OK"));
    }

    @Test
    public void testRSETWithoutMailbox() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("RSET");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOP tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testTOP() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("TOP 1 5");
        List<String> responses = endpoint.getResponses();
        assertTrue(responses.get(0).startsWith("+OK"));
        assertEquals(".", responses.get(responses.size() - 1));
    }

    @Test
    public void testTOPRequiresArguments() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("TOP 1");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testTOPInvalidArguments() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("TOP abc def");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testTOPDeletedMessage() {
        authenticateWithUserPass();
        sendCommand("DELE 1");
        endpoint.sentData.clear();
        sendCommand("TOP 1 5");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testTOPNoSuchMessage() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("TOP 999 5");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testTOPWithoutMailbox() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("TOP 1 5");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // UIDL tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testUIDLAll() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("UIDL");
        List<String> responses = endpoint.getResponses();
        assertTrue(responses.get(0).startsWith("+OK"));
        assertEquals("1 msg-uid-1", responses.get(1));
        assertEquals("2 msg-uid-2", responses.get(2));
        assertEquals("3 msg-uid-3", responses.get(3));
        assertEquals(".", responses.get(4));
    }

    @Test
    public void testUIDLSingleMessage() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("UIDL 2");
        String response = lastResponse();
        assertTrue(response.startsWith("+OK"));
        assertTrue(response.contains("2 msg-uid-2"));
    }

    @Test
    public void testUIDLNoSuchMessage() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("UIDL 999");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testUIDLWithoutMailbox() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("UIDL");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUIT tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testQUITInAuthorizationState() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("QUIT");
        assertTrue(lastResponse().startsWith("+OK"));
        assertTrue(lastResponse().contains("Goodbye"));
        assertFalse("Endpoint should be closed", endpoint.open);
    }

    @Test
    public void testQUITInTransactionState() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("QUIT");
        assertTrue(lastResponse().startsWith("+OK"));
        assertFalse("Endpoint should be closed", endpoint.open);
    }

    @Test
    public void testQUITCommitsDeletedMessages() {
        authenticateWithUserPass();
        sendCommand("DELE 1");
        sendCommand("QUIT");
        StubMailbox mbox = mailboxFactory.lastMailbox;
        assertTrue("Mailbox should be closed with expunge",
                mbox.closedWithExpunge);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Dot-stuffing on RETR tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testRETRDotStuffing() {
        mailboxFactory.messageContent =
                "Subject: test\r\n\r\n.This starts with dot\r\n";
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("RETR 1");
        String all = allResponses();
        assertTrue("Dot-stuffed line should have double dot",
                all.contains("..This starts with dot"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Disconnection tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testDisconnectedClosesMailbox() {
        authenticateWithUserPass();
        handler.disconnected();
        StubMailbox mbox = mailboxFactory.lastMailbox;
        assertTrue("Mailbox should be closed on disconnect",
                mbox.closed);
        assertFalse("Disconnect should not expunge",
                mbox.closedWithExpunge);
    }

    @Test
    public void testDisconnectedClosesStore() {
        authenticateWithUserPass();
        handler.disconnected();
        StubMailboxStore store = mailboxFactory.lastStore;
        assertTrue("Store should be closed on disconnect",
                store.closed);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Error handling tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testTransportError() {
        connectPlaintext();
        handler.error(new IOException("test error"));
        assertFalse("Endpoint should be closed on error",
                endpoint.open);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ConnectedState handler tests (via POP3Service)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testRejectConnectionViaHandler() {
        RecordingClientHandler clientHandler =
                new RecordingClientHandler();
        clientHandler.action = RecordingClientHandler.Action.REJECT;
        listener.clientHandler = clientHandler;
        connectPlaintext();
        assertTrue("Should send -ERR",
                lastResponse().startsWith("-ERR"));
        assertFalse("Endpoint should be closed", endpoint.open);
    }

    @Test
    public void testRejectConnectionWithMessageViaHandler() {
        RecordingClientHandler clientHandler =
                new RecordingClientHandler();
        clientHandler.action =
                RecordingClientHandler.Action.REJECT_MESSAGE;
        clientHandler.rejectMessage = "Too many connections";
        listener.clientHandler = clientHandler;
        connectPlaintext();
        assertTrue(lastResponse().startsWith("-ERR"));
        assertTrue(lastResponse().contains("Too many connections"));
    }

    @Test
    public void testAcceptConnectionViaHandler() {
        RecordingClientHandler clientHandler =
                new RecordingClientHandler();
        clientHandler.action = RecordingClientHandler.Action.ACCEPT;
        clientHandler.customGreeting = "Welcome";
        listener.clientHandler = clientHandler;
        connectPlaintext();
        assertTrue(lastResponse().startsWith("+OK"));
        assertTrue(lastResponse().contains("Welcome"));
    }

    @Test
    public void testServerShuttingDownViaHandler() {
        RecordingClientHandler clientHandler =
                new RecordingClientHandler();
        clientHandler.action =
                RecordingClientHandler.Action.SHUTTING_DOWN;
        listener.clientHandler = clientHandler;
        connectPlaintext();
        assertTrue(lastResponse().startsWith("-ERR"));
        assertFalse("Endpoint should be closed", endpoint.open);
    }

    // ═══════════════════════════════════════════════════════════════════
    // TransactionHandler delegation tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testTransactionHandlerDelegatesSTAT() {
        RecordingTransactionHandler txHandler =
                new RecordingTransactionHandler();
        authenticateWithHandler(txHandler);
        endpoint.sentData.clear();
        sendCommand("STAT");
        assertTrue("Handler should receive STAT call",
                txHandler.statCalled);
    }

    @Test
    public void testTransactionHandlerDelegatesLIST() {
        RecordingTransactionHandler txHandler =
                new RecordingTransactionHandler();
        authenticateWithHandler(txHandler);
        endpoint.sentData.clear();
        sendCommand("LIST");
        assertTrue("Handler should receive LIST call",
                txHandler.listCalled);
        assertEquals(0, txHandler.listMessageNumber);
    }

    @Test
    public void testTransactionHandlerDelegatesLISTSingle() {
        RecordingTransactionHandler txHandler =
                new RecordingTransactionHandler();
        authenticateWithHandler(txHandler);
        endpoint.sentData.clear();
        sendCommand("LIST 2");
        assertTrue("Handler should receive LIST call",
                txHandler.listCalled);
        assertEquals(2, txHandler.listMessageNumber);
    }

    @Test
    public void testTransactionHandlerDelegatesRETR() {
        RecordingTransactionHandler txHandler =
                new RecordingTransactionHandler();
        authenticateWithHandler(txHandler);
        endpoint.sentData.clear();
        sendCommand("RETR 1");
        assertTrue("Handler should receive RETR call",
                txHandler.retrCalled);
        assertEquals(1, txHandler.retrMessageNumber);
    }

    @Test
    public void testTransactionHandlerDelegatesDELE() {
        RecordingTransactionHandler txHandler =
                new RecordingTransactionHandler();
        authenticateWithHandler(txHandler);
        endpoint.sentData.clear();
        sendCommand("DELE 2");
        assertTrue("Handler should receive DELE call",
                txHandler.deleCalled);
        assertEquals(2, txHandler.deleMessageNumber);
    }

    @Test
    public void testTransactionHandlerDelegatesRSET() {
        RecordingTransactionHandler txHandler =
                new RecordingTransactionHandler();
        authenticateWithHandler(txHandler);
        endpoint.sentData.clear();
        sendCommand("RSET");
        assertTrue("Handler should receive RSET call",
                txHandler.rsetCalled);
    }

    @Test
    public void testTransactionHandlerDelegatesTOP() {
        RecordingTransactionHandler txHandler =
                new RecordingTransactionHandler();
        authenticateWithHandler(txHandler);
        endpoint.sentData.clear();
        sendCommand("TOP 1 10");
        assertTrue("Handler should receive TOP call",
                txHandler.topCalled);
        assertEquals(1, txHandler.topMessageNumber);
        assertEquals(10, txHandler.topLines);
    }

    @Test
    public void testTransactionHandlerDelegatesUIDL() {
        RecordingTransactionHandler txHandler =
                new RecordingTransactionHandler();
        authenticateWithHandler(txHandler);
        endpoint.sentData.clear();
        sendCommand("UIDL");
        assertTrue("Handler should receive UIDL call",
                txHandler.uidlCalled);
        assertEquals(0, txHandler.uidlMessageNumber);
    }

    @Test
    public void testTransactionHandlerDelegatesQUIT() {
        RecordingTransactionHandler txHandler =
                new RecordingTransactionHandler();
        authenticateWithHandler(txHandler);
        endpoint.sentData.clear();
        sendCommand("QUIT");
        assertTrue("Handler should receive QUIT call",
                txHandler.quitCalled);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Full session flow tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testFullRetrieveAndDeleteSession() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("STAT");
        assertTrue(endpoint.getResponses().get(0).contains("3 1500"));

        endpoint.sentData.clear();
        sendCommand("LIST");
        assertTrue(endpoint.getResponses().size() >= 5);

        endpoint.sentData.clear();
        sendCommand("RETR 1");
        assertTrue(endpoint.getResponses().get(0).startsWith("+OK"));

        endpoint.sentData.clear();
        sendCommand("DELE 1");
        assertTrue(lastResponse().startsWith("+OK"));

        endpoint.sentData.clear();
        sendCommand("QUIT");
        assertTrue(lastResponse().startsWith("+OK"));
        assertFalse(endpoint.open);
    }

    @Test
    public void testMultipleCommandsInBuffer() {
        connectPlaintext();
        endpoint.sentData.clear();
        String commands = "NOOP\r\nNOOP\r\nNOOP\r\n";
        handler.receive(ByteBuffer.wrap(
                commands.getBytes(StandardCharsets.US_ASCII)));
        List<String> responses = endpoint.getResponses();
        int okCount = 0;
        for (String r : responses) {
            if (r.startsWith("+OK")) {
                okCount++;
            }
        }
        assertEquals("Three NOOPs should produce three +OK responses",
                3, okCount);
    }

    @Test
    public void testCaseInsensitiveCommands() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("noop");
        assertTrue(lastResponse().startsWith("+OK"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // AuthorizationHandler delegation tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testAuthorizationHandlerRejectsAuth() {
        RecordingClientHandler clientHandler =
                new RecordingClientHandler();
        clientHandler.action = RecordingClientHandler.Action.ACCEPT;
        clientHandler.customGreeting = "Welcome";
        clientHandler.authAction =
                RecordingClientHandler.AuthAction.REJECT;
        listener.clientHandler = clientHandler;

        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
        assertTrue("Auth rejection should send -ERR",
                lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testAuthorizationHandlerAcceptsAuth() {
        RecordingClientHandler clientHandler =
                new RecordingClientHandler();
        clientHandler.action = RecordingClientHandler.Action.ACCEPT;
        clientHandler.customGreeting = "Welcome";
        clientHandler.authAction =
                RecordingClientHandler.AuthAction.ACCEPT;
        listener.clientHandler = clientHandler;

        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("USER testuser");
        sendCommand("PASS testpass");
        assertTrue("Auth acceptance should send +OK",
                lastResponse().startsWith("+OK"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTH GSSAPI not available
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testAuthGSSAPINotAvailable() {
        connectPlaintext();
        endpoint.sentData.clear();
        sendCommand("AUTH GSSAPI");
        assertTrue(lastResponse().startsWith("-ERR"));
        assertTrue(lastResponse().contains("not available"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Negative message number tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testNegativeMessageNumber() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("RETR -1");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    @Test
    public void testZeroMessageNumber() {
        authenticateWithUserPass();
        endpoint.sentData.clear();
        sendCommand("RETR 0");
        assertTrue(lastResponse().startsWith("-ERR"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stub implementations
    // ═══════════════════════════════════════════════════════════════════

    static class TestPOP3Listener extends POP3Listener {
        boolean starttlsAvailable = false;
        ClientConnected clientHandler;

        @Override
        protected boolean isSTARTTLSAvailable() {
            return starttlsAvailable;
        }

        @Override
        public POP3Service getService() {
            if (clientHandler == null) {
                return null;
            }
            return new TestPOP3Service(clientHandler);
        }
    }

    static class TestPOP3Service extends POP3Service {
        private final ClientConnected handler;

        TestPOP3Service(ClientConnected handler) {
            this.handler = handler;
        }

        @Override
        protected ClientConnected createHandler(
                TCPListener endpoint) {
            return handler;
        }
    }

    static class StubEndpoint implements Endpoint {
        final List<byte[]> sentData = new ArrayList<byte[]>();
        boolean open = true;
        boolean startTLSCalled;
        boolean secure;

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
            return new InetSocketAddress("127.0.0.1", 110);
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
        Set<SASLMechanism> supportedMechanisms =
                new HashSet<SASLMechanism>();

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SASLMechanism> getSupportedSASLMechanisms() {
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
            StubMailbox mbox = new StubMailbox(factory.messageContent);
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
        boolean closed;
        boolean closedWithExpunge;
        private final String messageContent;

        StubMailbox(String content) {
            this.messageContent = content;
            messages = new ArrayList<StubMessage>();
            messages.add(new StubMessage(1, 500, "msg-uid-1"));
            messages.add(new StubMessage(2, 500, "msg-uid-2"));
            messages.add(new StubMessage(3, 500, "msg-uid-3"));
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
