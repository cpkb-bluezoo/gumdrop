/*
 * IMAPNotifyDeliveryTest.java
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

package org.bluezoo.gumdrop.imap;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * RFC 5465 NOTIFY subscription validation and unsolicited delivery,
 * driven deterministically: the poll and IDLE timers captured by the
 * endpoint are fired by the test rather than by the clock.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPNotifyDeliveryTest {

    private MemoryFileSystem mem;
    private Gumdrop gumdrop;
    private ImapListener listener;
    private ImapProtocolHandler handler;
    private RecordingStubEndpoint endpoint;
    private int tagCounter;

    @Before
    public void setUp() throws Exception {
        mem = MemoryFileSystem.create();
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        Path mailRoot = mem.getPath("/maildir");
        Path userDir = mailRoot.resolve("editor");
        Files.createDirectories(userDir.resolve("cur"));
        Files.createDirectories(userDir.resolve("new"));
        Files.createDirectories(userDir.resolve("tmp"));
        listener = new ImapListener();
        listener.setRealm(new AcceptingRealm("editor", "editor"));
        listener.setMailboxFactory(new MaildirMailboxFactory(mailRoot));
        listener.setAllowPlaintextLogin(true);
        handler = new ImapProtocolHandler(listener);
        endpoint = new RecordingStubEndpoint(143);
        endpoint.setSelectorLoop(gumdrop.nextWorkerLoop());
        handler.connected(endpoint);
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    private String cmd(String command) throws Exception {
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " " + command + "\r\n");
        return endpoint.awaitLineStartingWith(tag + " ");
    }

    private void send(String data) {
        handler.receive(ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)));
    }

    private void ok(String command) throws Exception {
        String line = cmd(command);
        assertTrue(command + " -> " + line, line.contains(" OK"));
    }

    private void no(String command) throws Exception {
        String line = cmd(command);
        assertTrue(command + " -> " + line, line.contains(" NO"));
    }

    private void bad(String command) throws Exception {
        String line = cmd(command);
        assertTrue(command + " -> " + line, line.contains(" BAD"));
    }

    private void appendTo(String mailbox, String subject) throws Exception {
        String msg = "From: a@example.com\r\nSubject: " + subject
                + "\r\n\r\nBody\r\n";
        int len = msg.getBytes(StandardCharsets.UTF_8).length;
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND " + mailbox + " {" + len + "+}\r\n" + msg + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains(" OK"));
    }

    private void prepare() throws Exception {
        ok("LOGIN editor editor");
        ok("CREATE Other");
        ok("CREATE Other/Child");
        ok("SUBSCRIBE Other");
        ok("ENABLE NOTIFY");
    }

    @Test(timeout = 30000)
    public void testNotifyRequiresEnable() throws Exception {
        ok("LOGIN editor editor");
        no("NOTIFY SET (personal (MessageNew MessageExpunge))");
        ok("ENABLE NOTIFY");
        bad("NOTIFY");
        bad("NOTIFY SET");
        bad("NOTIFY SET (personal (MessageNew");
        ok("NOTIFY NONE");
    }

    @Test(timeout = 30000)
    public void testNotifyValidationErrors() throws Exception {
        prepare();
        bad("NOTIFY SET (selected (MessageNew MessageExpunge)) "
                + "(selected (MessageNew MessageExpunge))");
        bad("NOTIFY SET (selected (MailboxName))");
        bad("NOTIFY SET (personal (MessageNew))");
        bad("NOTIFY SET (personal (MessageExpunge))");
        bad("NOTIFY SET (personal (FlagChange))");
        no("NOTIFY SET (personal (AnnotationChange))");
        no("NOTIFY SET (personal (MailboxMetadataChange))");
        ok("NOTIFY SET (personal (NONE))");
        ok("NOTIFY SET (personal (MailboxName SubscriptionChange))");
        ok("NOTIFY SET (subscribed (MessageNew MessageExpunge FlagChange))");
        ok("NOTIFY SET (inboxes (MessageNew MessageExpunge))");
        ok("NOTIFY SET (subtree Other (MessageNew MessageExpunge))");
        ok("NOTIFY SET (mailboxes (Other Missing) (MessageNew MessageExpunge))");
        ok("NOTIFY NONE");
    }

    @Test(timeout = 30000)
    public void testPollDeliversStatusForOtherMailbox() throws Exception {
        prepare();
        ok("NOTIFY SET STATUS (personal (MessageNew MessageExpunge)) "
                + "(selected (MessageNew MessageExpunge FlagChange))");
        assertNotNull(endpoint.findLineContaining("* STATUS"));
        ok("SELECT INBOX");
        endpoint.fireTimers();
        appendTo("Other", "fresh");
        endpoint.clearResponses();
        endpoint.fireTimers();
        assertNotNull(endpoint.findLineContaining("STATUS Other"));
        endpoint.fireTimers();
        appendTo("INBOX", "mine");
        endpoint.fireTimers();
        ok("NOTIFY NONE");
        assertEquals(0, endpoint.fireTimers());
    }

    @Test(timeout = 30000)
    public void testIdleTicksDeliverNotifications() throws Exception {
        prepare();
        ok("ENABLE CONDSTORE");
        ok("SELECT INBOX");
        ok("NOTIFY SET (subtree Other (MessageNew MessageExpunge FlagChange)) "
                + "(selected (MessageNew MessageExpunge FlagChange))");
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " IDLE\r\n");
        endpoint.awaitLineStartingWith("+");
        endpoint.fireTimers();
        appendTo("Other", "one");
        endpoint.fireTimers();
        endpoint.fireTimers();
        appendTo("INBOX", "inbox one");
        endpoint.fireTimers();
        send("DONE\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains(" OK"));
    }

    @Test(timeout = 30000)
    public void testDisconnectCancelsPolling() throws Exception {
        prepare();
        ok("NOTIFY SET (personal (MessageNew MessageExpunge))");
        handler.disconnected();
        assertEquals(0, endpoint.fireTimers());
    }

    private static final class AcceptingRealm implements Realm {
        private final String user;
        private final String pass;
        private static final Set<SaslMechanism> SUPPORTED =
                Collections.unmodifiableSet(
                        EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN));

        AcceptingRealm(String user, String pass) {
            this.user = user;
            this.pass = pass;
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return SUPPORTED;
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return user.equals(username) && pass.equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            return user.equals(username) ? pass : null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }
    }
}
