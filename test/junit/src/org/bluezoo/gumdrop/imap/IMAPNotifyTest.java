/*
 * IMAPNotifyTest.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * RFC 5465 NOTIFY on the in-process IMAP server.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPNotifyTest {

    private MemoryFileSystem mem;
    private Gumdrop gumdrop;

    @Before
    public void setUp() throws Exception {
        mem = MemoryFileSystem.create();
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    @Test(timeout = 20000)
    public void testEnableNotifyAndWatchOtherMailbox() throws Exception {
        Path mailRoot = mem.getPath("/maildir");
        Path userDir = mailRoot.resolve("editor");
        Files.createDirectories(userDir.resolve("INBOX/cur"));
        Files.createDirectories(userDir.resolve("INBOX/new"));
        Files.createDirectories(userDir.resolve("INBOX/tmp"));
        Files.createDirectories(userDir.resolve("Other/cur"));
        Files.createDirectories(userDir.resolve("Other/new"));
        Files.createDirectories(userDir.resolve("Other/tmp"));

        ImapListener listener = new ImapListener();
        listener.setRealm(new AcceptingRealm("editor", "editor"));
        listener.setMailboxFactory(new MaildirMailboxFactory(mailRoot));
        listener.setAllowPlaintextLogin(true);

        ImapProtocolHandler handler = new ImapProtocolHandler(listener);
        RecordingStubEndpoint endpoint = new RecordingStubEndpoint(143);
        endpoint.setSelectorLoop(gumdrop.nextWorkerLoop());
        handler.connected(endpoint);

        sendLine(handler, "a1 LOGIN editor editor");
        endpoint.awaitLineContaining("a1 OK");
        assertTrue(listener.getCapabilities(true, true).contains("NOTIFY"));

        endpoint.clearResponses();
        sendLine(handler, "a2 CREATE Other");
        endpoint.awaitLineContaining("a2 OK");

        endpoint.clearResponses();
        sendLine(handler, "a3 ENABLE NOTIFY");
        endpoint.awaitLineContaining("a3 OK");
        assertTrue(endpoint.findLineContaining("* ENABLED").contains("NOTIFY"));
        assertTrue(handler.isNotifyEnabled());

        endpoint.clearResponses();
        sendLine(handler, "a4 NOTIFY SET STATUS (mailboxes Other "
                + "(MessageNew MessageExpunge))");
        endpoint.awaitLineContaining("a4 OK");
        assertTrue(endpoint.findLineContaining("* STATUS") != null);

        endpoint.clearResponses();
        sendLine(handler, "a5 SELECT INBOX");
        endpoint.awaitLineContaining("a5 OK");
        sendLine(handler, "a6 NOTIFY NONE");
        endpoint.awaitLineContaining("a6 OK");
    }

    private static void sendLine(ProtocolHandler handler, String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
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
