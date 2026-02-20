/*
 * POP3ServerIntegrationTest.java
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

package org.bluezoo.gumdrop.pop3;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxFactory;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;

import java.util.EnumSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Integration test for POP3Listener with real mailbox stores.
 *
 * <p>Tests POP3Listener instances with both mbox and Maildir mailbox backends
 * using real network connections. Each mailbox contains 2 test messages:
 * <ul>
 *   <li>Message 1: "Welcome to Gumdrop!"</li>
 *   <li>Message 2: "You have WON the LOTTERY!!"</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3ServerIntegrationTest {
    
    private static final int MBOX_PORT = 11110;
    private static final int MAILDIR_PORT = 11111;
    private static final String TEST_USER = "editor";
    private static final String TEST_PASS = "editor";
    
    /**
     * Global timeout for all tests - 10 seconds max per test.
     */
    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(10, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();
    
    private Gumdrop gumdrop;
    private POP3Listener mboxServer;
    private POP3Listener maildirServer;
    
    private Logger rootLogger;
    private Level originalLogLevel;
    
    @Before
    public void setUp() throws Exception {
        // Reduce logging noise
        rootLogger = Logger.getLogger("");
        originalLogLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.WARNING);
        
        // Get mailbox paths
        Path testMailboxRoot = Paths.get("test/integration/mailbox").toAbsolutePath();
        Path mboxRoot = testMailboxRoot.resolve("mbox");
        Path maildirRoot = testMailboxRoot.resolve("maildir");
        
        // Create test realm
        TestRealm realm = new TestRealm();
        
        // Create mbox server
        mboxServer = new POP3Listener();
        mboxServer.setPort(MBOX_PORT);
        mboxServer.setAddresses("127.0.0.1");
        mboxServer.setEnableAPOP(false);
        mboxServer.setRealm(realm);
        mboxServer.setMailboxFactory(new MboxMailboxFactory(mboxRoot));
        
        // Create Maildir server
        maildirServer = new POP3Listener();
        maildirServer.setPort(MAILDIR_PORT);
        maildirServer.setAddresses("127.0.0.1");
        maildirServer.setEnableAPOP(false);
        maildirServer.setRealm(realm);
        maildirServer.setMailboxFactory(new MaildirMailboxFactory(maildirRoot));
        
        // Start servers using singleton with lifecycle management
        System.setProperty("gumdrop.workers", "2");
        gumdrop = Gumdrop.getInstance();
        gumdrop.addListener(mboxServer);
        gumdrop.addListener(maildirServer);
        gumdrop.start();
        
        // Wait for servers to be ready
        waitForPort(MBOX_PORT, 5000);
        waitForPort(MAILDIR_PORT, 5000);
    }
    
    @After
    public void tearDown() throws Exception {
        try {
            if (gumdrop != null) {
                gumdrop.shutdown();
                gumdrop.join();
            }
            Thread.sleep(1500); // Allow ports to be released
        } finally {
            if (rootLogger != null && originalLogLevel != null) {
                rootLogger.setLevel(originalLogLevel);
            }
        }
    }
    
    private void waitForPort(int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                Thread.sleep(200);
                return;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Server failed to start on port " + port);
    }
    
    // ==================== MBOX Tests ====================
    
    @Test
    public void testMboxServerGreeting() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.POP3Response greeting = session.getLastResponse();
            
            assertTrue("Greeting should be +OK", greeting.ok);
            assertTrue("Greeting should contain server info", 
                greeting.message.length() > 0);
        }
    }
    
    @Test
    public void testMboxAuthentication() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            boolean authenticated = POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            assertTrue("Authentication should succeed", authenticated);
        }
    }
    
    @Test
    public void testMboxAuthenticationFailure() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            boolean authenticated = POP3ClientHelper.authenticate(session, TEST_USER, "wrongpassword");
            assertFalse("Authentication should fail with wrong password", authenticated);
        }
    }
    
    @Test
    public void testMboxSTAT() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response stat = session.sendCommand("STAT");
            
            assertTrue("STAT should succeed", stat.ok);
            assertTrue("STAT should return message count and size",
                stat.message.matches("\\d+ \\d+"));
            
            // Should have 2 messages
            String[] parts = stat.message.split(" ");
            assertEquals("Should have 2 messages", "2", parts[0]);
        }
    }
    
    @Test
    public void testMboxLIST() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response list = session.sendMultiLineCommand("LIST");
            
            assertTrue("LIST should succeed", list.ok);
            // First line is +OK, then 2 message lines
            assertTrue("LIST should return at least 3 lines", list.lines.size() >= 3);
        }
    }
    
    @Test
    public void testMboxUIDL() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response uidl = session.sendMultiLineCommand("UIDL");
            
            assertTrue("UIDL should succeed", uidl.ok);
            // First line is +OK, then 2 message lines
            assertTrue("UIDL should return at least 3 lines", uidl.lines.size() >= 3);
        }
    }
    
    @Test
    public void testMboxRETR() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response retr = session.sendMultiLineCommand("RETR 1");
            
            assertTrue("RETR should succeed", retr.ok);
            
            // Check for expected content
            String fullMessage = String.join("\n", retr.lines);
            assertTrue("Message should contain Welcome subject",
                fullMessage.contains("Welcome to Gumdrop"));
        }
    }
    
    @Test
    public void testMboxTOP() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            // Get headers + 0 body lines
            POP3ClientHelper.POP3Response top = session.sendMultiLineCommand("TOP 1 0");
            
            assertTrue("TOP should succeed", top.ok);
            
            // Should have headers
            String fullMessage = String.join("\n", top.lines);
            assertTrue("TOP should return headers",
                fullMessage.contains("Subject:"));
        }
    }
    
    @Test
    public void testMboxDELE() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response dele = session.sendCommand("DELE 1");
            assertTrue("DELE should succeed", dele.ok);
            
            // Reset to undelete
            POP3ClientHelper.POP3Response rset = session.sendCommand("RSET");
            assertTrue("RSET should succeed", rset.ok);
        }
    }
    
    @Test
    public void testMboxNOOP() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response noop = session.sendCommand("NOOP");
            assertTrue("NOOP should succeed", noop.ok);
        }
    }
    
    @Test
    public void testMboxCAPA() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.POP3Response capa = session.sendMultiLineCommand("CAPA");
            
            assertTrue("CAPA should succeed", capa.ok);
            
            String fullCapa = String.join("\n", capa.lines);
            assertTrue("CAPA should advertise UIDL", fullCapa.contains("UIDL"));
            assertTrue("CAPA should advertise TOP", fullCapa.contains("TOP"));
        }
    }
    
    // ==================== Maildir Tests ====================
    
    @Test
    public void testMaildirServerGreeting() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MAILDIR_PORT)) {
            POP3ClientHelper.POP3Response greeting = session.getLastResponse();
            
            assertTrue("Greeting should be +OK", greeting.ok);
        }
    }
    
    @Test
    public void testMaildirAuthentication() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MAILDIR_PORT)) {
            boolean authenticated = POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            assertTrue("Authentication should succeed", authenticated);
        }
    }
    
    @Test
    public void testMaildirSTAT() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MAILDIR_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response stat = session.sendCommand("STAT");
            
            assertTrue("STAT should succeed", stat.ok);
            assertTrue("STAT should return message count and size",
                stat.message.matches("\\d+ \\d+"));
            
            // Should have 2 messages
            String[] parts = stat.message.split(" ");
            assertEquals("Should have 2 messages", "2", parts[0]);
        }
    }
    
    @Test
    public void testMaildirLIST() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MAILDIR_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response list = session.sendMultiLineCommand("LIST");
            
            assertTrue("LIST should succeed", list.ok);
            assertTrue("LIST should return at least 3 lines", list.lines.size() >= 3);
        }
    }
    
    @Test
    public void testMaildirRETR() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MAILDIR_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response retr = session.sendMultiLineCommand("RETR 1");
            
            assertTrue("RETR should succeed", retr.ok);
            
            // Check for expected content
            String fullMessage = String.join("\n", retr.lines);
            assertTrue("Message should contain Welcome subject",
                fullMessage.contains("Welcome to Gumdrop"));
        }
    }
    
    @Test
    public void testMaildirSecondMessage() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MAILDIR_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response retr = session.sendMultiLineCommand("RETR 2");
            
            assertTrue("RETR 2 should succeed", retr.ok);
            
            // Check for expected content
            String fullMessage = String.join("\n", retr.lines);
            assertTrue("Message 2 should contain LOTTERY subject",
                fullMessage.contains("LOTTERY"));
        }
    }
    
    // ==================== Error Cases ====================
    
    @Test
    public void testCommandBeforeAuth() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            // Try to get stats without authenticating
            POP3ClientHelper.POP3Response stat = session.sendCommand("STAT");
            
            assertFalse("STAT should fail before authentication", stat.ok);
        }
    }
    
    @Test
    public void testInvalidMessageNumber() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.authenticate(session, TEST_USER, TEST_PASS);
            
            POP3ClientHelper.POP3Response retr = session.sendCommand("RETR 999");
            
            assertFalse("RETR invalid message should fail", retr.ok);
        }
    }
    
    @Test
    public void testUnknownCommand() throws Exception {
        try (POP3ClientHelper.POP3Session session = POP3ClientHelper.connect("127.0.0.1", MBOX_PORT)) {
            POP3ClientHelper.POP3Response response = session.sendCommand("INVALID");
            
            assertFalse("Unknown command should fail", response.ok);
        }
    }
    
    /**
     * Test realm that accepts editor/editor credentials.
     */
    private static class TestRealm implements Realm {
        
        private static final Set<SASLMechanism> SUPPORTED = 
            Collections.unmodifiableSet(EnumSet.of(SASLMechanism.PLAIN, SASLMechanism.LOGIN));
        
        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this; // Simple synchronous realm
        }
        
        @Override
        public Set<SASLMechanism> getSupportedSASLMechanisms() {
            return SUPPORTED;
        }
        
        @Override
        public boolean passwordMatch(String username, String password) {
            return TEST_USER.equals(username) && TEST_PASS.equals(password);
        }
        
        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }
        
        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            throw new UnsupportedOperationException("getPassword is deprecated - use passwordMatch instead");
        }
        
        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }
        
        @Override
        public boolean userExists(String username) {
            return TEST_USER.equals(username);
        }
    }
}

