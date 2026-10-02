/*
 * IMAPScriptedHandlerTest.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.imap.server.AppendCompleteState;
import org.bluezoo.gumdrop.imap.server.AppendDataHandler;
import org.bluezoo.gumdrop.imap.server.AppendState;
import org.bluezoo.gumdrop.imap.server.AuthenticateState;
import org.bluezoo.gumdrop.imap.server.AuthenticatedHandler;
import org.bluezoo.gumdrop.imap.server.AuthenticatedStatusState;
import org.bluezoo.gumdrop.imap.server.ClientConnected;
import org.bluezoo.gumdrop.imap.server.CloseState;
import org.bluezoo.gumdrop.imap.server.ConnectedState;
import org.bluezoo.gumdrop.imap.server.CopyState;
import org.bluezoo.gumdrop.imap.server.CreateState;
import org.bluezoo.gumdrop.imap.server.DeleteState;
import org.bluezoo.gumdrop.imap.server.ExpungeState;
import org.bluezoo.gumdrop.imap.server.FetchState;
import org.bluezoo.gumdrop.imap.server.ImapServerSessionProvider;
import org.bluezoo.gumdrop.imap.server.ListState;
import org.bluezoo.gumdrop.imap.server.MoveState;
import org.bluezoo.gumdrop.imap.server.NotAuthenticatedHandler;
import org.bluezoo.gumdrop.imap.server.QuotaState;
import org.bluezoo.gumdrop.imap.server.RenameState;
import org.bluezoo.gumdrop.imap.server.SearchState;
import org.bluezoo.gumdrop.imap.server.SelectState;
import org.bluezoo.gumdrop.imap.server.SelectedHandler;
import org.bluezoo.gumdrop.imap.server.SelectedStatusState;
import org.bluezoo.gumdrop.imap.server.StoreState;
import org.bluezoo.gumdrop.imap.server.SubscribeState;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageSet;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.mailbox.StoreAction;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;
import org.bluezoo.gumdrop.quota.QuotaManager;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Drives {@link ImapProtocolHandler} with a scripted application handler
 * so that every response method of the staged state interfaces (the
 * accept, reject, failure and shutdown variants) is exercised, not only
 * the default {@code proceed} path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPScriptedHandlerTest {

    private MemoryFileSystem mem;
    private Gumdrop gumdrop;
    private ImapListener listener;
    private ImapProtocolHandler handler;
    private RecordingStubEndpoint endpoint;
    private ScriptHandler script;
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
        script = new ScriptHandler();
        listener.setSessionProvider(new ScriptProvider(script));
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    private void connect(int connectVariant) throws Exception {
        script.connectVariant = connectVariant;
        handler = new ImapProtocolHandler(listener);
        endpoint = new RecordingStubEndpoint(143);
        endpoint.setSelectorLoop(gumdrop.nextWorkerLoop());
        handler.connected(endpoint);
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

    private void any(String command) throws Exception {
        String line = cmd(command);
        assertNotNull(line);
    }

    /** Runs a command with the given variant and expects a tagged OK. */
    private void okV(int variant, String command) throws Exception {
        script.variant = variant;
        ok(command);
    }

    private void noV(int variant, String command) throws Exception {
        script.variant = variant;
        no(command);
    }

    private void anyV(int variant, String command) throws Exception {
        script.variant = variant;
        any(command);
    }

    private void login() throws Exception {
        connect(0);
        script.variant = 0;
        ok("LOGIN editor editor");
    }

    private void append(String subject) throws Exception {
        String msg = "From: a@example.com\r\nTo: b@example.com\r\n"
                + "Subject: " + subject + "\r\n"
                + "Message-ID: <" + subject.replace(' ', '.') + "@t>\r\n"
                + "\r\nBody " + subject + "\r\n";
        int len = msg.getBytes(StandardCharsets.UTF_8).length;
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        script.variant = 0;
        send(tag + " APPEND INBOX {" + len + "+}\r\n" + msg + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains(" OK"));
    }

    private void populate() throws Exception {
        login();
        append("one");
        append("two");
        append("three");
    }

    @Test(timeout = 30000)
    public void testConnectVariants() throws Exception {
        connect(0);
        assertNotNull(endpoint.awaitLineContaining("OK"));
        connect(1);
        assertNotNull(endpoint.awaitLineContaining("PREAUTH"));
        ok("NOOP");
        connect(2);
        assertNotNull(endpoint.awaitLineContaining("BYE"));
        connect(3);
        assertNotNull(endpoint.awaitLineContaining("BYE custom"));
        connect(4);
        assertNotNull(endpoint.awaitLineContaining("BYE"));
    }

    @Test(timeout = 30000)
    public void testAuthenticateVariants() throws Exception {
        connect(0);
        noV(1, "LOGIN editor editor");
        noV(2, "LOGIN editor editor");
        okV(3, "LOGIN editor editor");
        connect(0);
        okV(4, "LOGIN editor editor");
        connect(0);
        script.variant = 5;
        send("t90 LOGIN editor editor\r\n");
        assertNotNull(endpoint.awaitLineContaining("BYE"));
        connect(0);
        script.variant = 6;
        send("t91 LOGIN editor editor\r\n");
        assertNotNull(endpoint.awaitLineContaining("BYE"));
    }

    @Test(timeout = 30000)
    public void testSelectVariants() throws Exception {
        populate();
        okV(1, "SELECT INBOX");
        okV(2, "EXAMINE INBOX");
        okV(3, "SELECT INBOX");
        noV(4, "SELECT INBOX");
        noV(5, "SELECT INBOX");
        noV(6, "SELECT INBOX");
        noV(7, "SELECT INBOX");
        okV(0, "SELECT INBOX");
        okV(1, "SELECT INBOX");
        noV(104, "SELECT INBOX");
        script.variant = 8;
        send("t92 SELECT INBOX\r\n");
        assertNotNull(endpoint.awaitLineContaining("BYE"));
    }

    @Test(timeout = 30000)
    public void testSelectedStateReselectVariants() throws Exception {
        populate();
        okV(0, "SELECT INBOX");
        okV(1, "SELECT INBOX");
        okV(2, "EXAMINE INBOX");
        noV(4, "SELECT INBOX");
        noV(5, "EXAMINE INBOX");
        noV(6, "SELECT INBOX");
        noV(7, "EXAMINE INBOX");
    }

    @Test(timeout = 30000)
    public void testCreateDeleteRenameSubscribeVariants() throws Exception {
        login();
        okV(1, "CREATE A");
        okV(2, "CREATE A2");
        noV(3, "CREATE A");
        noV(4, "CREATE A");
        okV(1, "DELETE A");
        noV(2, "DELETE A");
        noV(3, "DELETE A");
        okV(1, "RENAME A2 A3");
        noV(2, "RENAME A2 A3");
        noV(3, "RENAME A2 A3");
        noV(4, "RENAME A2 A3");
        okV(1, "SUBSCRIBE A3");
        noV(2, "SUBSCRIBE A3");
        noV(3, "SUBSCRIBE A3");
        okV(1, "UNSUBSCRIBE A3");
        noV(2, "UNSUBSCRIBE A3");
        noV(3, "UNSUBSCRIBE A3");
        okV(0, "CREATE B");
        okV(0, "DELETE B");
        anyV(0, "RENAME A3 A4");
        anyV(0, "SUBSCRIBE A4");
        anyV(0, "UNSUBSCRIBE A4");
    }

    @Test(timeout = 30000)
    public void testSelectedStateMailboxManagementVariants() throws Exception {
        populate();
        okV(0, "SELECT INBOX");
        okV(1, "CREATE A");
        noV(3, "CREATE A");
        noV(4, "CREATE A");
        okV(2, "CREATE A5");
        okV(1, "DELETE A");
        noV(2, "DELETE A");
        noV(3, "DELETE A");
        okV(1, "RENAME A5 A6");
        noV(2, "RENAME A5 A6");
        noV(3, "RENAME A5 A6");
        noV(4, "RENAME A5 A6");
        okV(1, "SUBSCRIBE A6");
        noV(2, "SUBSCRIBE A6");
        noV(3, "SUBSCRIBE A6");
        okV(1, "UNSUBSCRIBE A6");
        noV(2, "UNSUBSCRIBE A6");
        okV(1, "LIST \"\" \"*\"");
        okV(2, "LIST \"\" \"*\"");
        noV(3, "LIST \"\" \"*\"");
        okV(1, "LSUB \"\" \"*\"");
        okV(2, "LSUB \"\" \"*\"");
        noV(3, "LSUB \"\" \"*\"");
        okV(1, "STATUS INBOX (MESSAGES)");
        noV(2, "STATUS INBOX (MESSAGES)");
        noV(3, "STATUS INBOX (MESSAGES)");
        okV(0, "STATUS INBOX (MESSAGES)");
    }

    @Test(timeout = 30000)
    public void testListAndStatusVariants() throws Exception {
        login();
        okV(1, "LIST \"\" \"*\"");
        okV(2, "LIST \"\" \"*\"");
        noV(3, "LIST \"\" \"*\"");
        okV(1, "LSUB \"\" \"*\"");
        okV(2, "LSUB \"\" \"*\"");
        noV(3, "LSUB \"\" \"*\"");
        okV(1, "STATUS INBOX (MESSAGES UIDNEXT)");
        noV(2, "STATUS INBOX (MESSAGES)");
        noV(3, "STATUS INBOX (MESSAGES)");
        okV(0, "STATUS INBOX (MESSAGES)");
    }

    @Test(timeout = 30000)
    public void testSelectedFailureVariants() throws Exception {
        populate();
        okV(0, "SELECT INBOX");
        noV(1, "FETCH 1 FLAGS");
        noV(1, "UID FETCH 1 FLAGS");
        noV(1, "SEARCH ALL");
        noV(1, "UID SEARCH ALL");
        noV(1, "STORE 1 +FLAGS (\\Seen)");
        noV(1, "UID STORE 1 +FLAGS (\\Seen)");
        noV(1, "EXPUNGE");
        noV(1, "UID EXPUNGE 1");
        noV(1, "COPY 1 INBOX");
        noV(1, "UID COPY 1 INBOX");
        noV(1, "MOVE 1 INBOX");
        noV(1, "UID MOVE 1 INBOX");
        noV(1, "CLOSE");
        noV(1, "UNSELECT");
        noV(2, "COPY 1 Missing");
        noV(2, "MOVE 1 Missing");
        noV(3, "COPY 1 INBOX");
        noV(3, "MOVE 1 INBOX");
        noV(2, "EXPUNGE");
        noV(2, "UID EXPUNGE 1");
        noV(2, "STORE 1 +FLAGS (\\Seen)");
        noV(2, "UID STORE 1 +FLAGS (\\Seen)");
        noV(2, "CLOSE");
        noV(2, "UNSELECT");
    }

    @Test(timeout = 30000)
    public void testSelectedSuccessVariants() throws Exception {
        populate();
        okV(0, "SELECT INBOX");
        okV(0, "FETCH 1 FLAGS");
        okV(0, "UID FETCH 1 FLAGS");
        okV(0, "SEARCH ALL");
        okV(0, "UID SEARCH ALL");
        okV(0, "STORE 1 +FLAGS (\\Seen)");
        okV(0, "UID STORE 1 +FLAGS (\\Seen)");
        okV(0, "STORE 1 +FLAGS.SILENT (\\Flagged)");
        okV(21, "STORE 1 +FLAGS (\\Seen)");
        okV(21, "UID STORE 1 +FLAGS (\\Seen)");
        okV(22, "COPY 1 INBOX");
        okV(22, "MOVE 1 INBOX");
        okV(22, "UID MOVE 1 INBOX");
        okV(22, "EXPUNGE");
        okV(22, "UID EXPUNGE 1:*");
        okV(22, "STORE 1 +FLAGS (\\Seen)");
        okV(0, "COPY 1 INBOX");
        okV(0, "UID COPY 1 INBOX");
        okV(0, "MOVE 1 INBOX");
        okV(0, "EXPUNGE");
        okV(0, "UID EXPUNGE 1:*");
        okV(23, "CLOSE");
    }

    @Test(timeout = 30000)
    public void testUnselectAndCloseVariants() throws Exception {
        populate();
        okV(0, "SELECT INBOX");
        okV(21, "UNSELECT");
        okV(0, "SELECT INBOX");
        okV(0, "UNSELECT");
        okV(0, "SELECT INBOX");
        okV(0, "CLOSE");
    }

    @Test(timeout = 30000)
    public void testAcceptLiteralWithoutMailbox() throws Exception {
        login();
        String msg = "Subject: x\r\n\r\nbody\r\n";
        script.variant = 2;
        script.literalSize = msg.length();
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + msg.length() + "}\r\n");
        endpoint.awaitLineStartingWith("+");
        send(msg + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains(" OK"));
    }

    @Test(timeout = 30000)
    public void testAppendDataHandlerNotReusedForLaterAppend() throws Exception {
        login();
        String msg = "Subject: x\r\n\r\nbody\r\n";
        script.variant = 9;
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + msg.length() + "+}\r\n" + msg + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(line, line.contains("APPENDUID 5 6"));
        append("later");
        ok("SELECT INBOX");
        assertNotNull(endpoint.findLineContaining("1 EXISTS"));
    }

    @Test(timeout = 30000)
    public void testAppendVariants() throws Exception {
        login();
        tagCounter++;
        String tag = "t" + tagCounter;
        script.variant = 1;
        String msg = "Subject: x\r\n\r\nbody\r\n";
        send(tag + " APPEND INBOX {" + msg.length() + "+}\r\n" + msg + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertNotNull(line);
        for (int v = 3; v <= 10; v++) {
            script.variant = v;
            tagCounter++;
            tag = "t" + tagCounter;
            endpoint.clearResponses();
            send(tag + " APPEND INBOX {" + msg.length() + "+}\r\n" + msg
                    + "\r\n");
            line = endpoint.awaitLineStartingWith(tag + " ");
            assertNotNull(line);
        }
        script.variant = 0;
        populateSelectedAppend();
    }

    private void populateSelectedAppend() throws Exception {
        ok("SELECT INBOX");
        script.variant = 1;
        String msg = "Subject: y\r\n\r\nbody\r\n";
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + msg.length() + "+}\r\n" + msg + "\r\n");
        assertNotNull(endpoint.awaitLineStartingWith(tag + " "));
        for (int v = 3; v <= 10; v++) {
            script.variant = v;
            tagCounter++;
            tag = "t" + tagCounter;
            endpoint.clearResponses();
            send(tag + " APPEND INBOX {" + msg.length() + "+}\r\n" + msg
                    + "\r\n");
            assertNotNull(endpoint.awaitLineStartingWith(tag + " "));
        }
    }

    @Test(timeout = 30000)
    public void testQuotaVariants() throws Exception {
        login();
        for (int v = 1; v <= 4; v++) {
            anyV(v, "GETQUOTA \"\"");
            anyV(v, "GETQUOTAROOT INBOX");
            anyV(v, "SETQUOTA \"\" (STORAGE 1000)");
        }
        okV(0, "NOOP");
        ok("SELECT INBOX");
        for (int v = 1; v <= 4; v++) {
            anyV(v, "GETQUOTA \"\"");
            anyV(v, "GETQUOTAROOT INBOX");
            anyV(v, "SETQUOTA \"\" (STORAGE 1000)");
        }
    }

    @Test(timeout = 30000)
    public void testShutdownVariants() throws Exception {
        String[] commands = new String[] {
            "SELECT INBOX", "CREATE X", "DELETE X", "RENAME X Y",
            "SUBSCRIBE X", "LIST \"\" \"*\"", "STATUS INBOX (MESSAGES)"
        };
        for (int i = 0; i < commands.length; i++) {
            login();
            script.variant = 99;
            send("s" + i + " " + commands[i] + "\r\n");
            assertNotNull(endpoint.awaitLineContaining("BYE"));
        }
        String[] selected = new String[] {
            "FETCH 1 FLAGS", "SEARCH ALL", "STORE 1 +FLAGS (\\Seen)",
            "EXPUNGE", "COPY 1 INBOX", "MOVE 1 INBOX", "CLOSE", "UNSELECT",
            "STATUS INBOX (MESSAGES)"
        };
        for (int i = 0; i < selected.length; i++) {
            populate();
            ok("SELECT INBOX");
            script.variant = 99;
            send("u" + i + " " + selected[i] + "\r\n");
            assertNotNull(endpoint.awaitLineContaining("BYE"));
        }
        login();
        script.variant = 98;
        tagCounter++;
        String tag = "t" + tagCounter;
        String msg = "Subject: z\r\n\r\nbody\r\n";
        send(tag + " APPEND INBOX {" + msg.length() + "+}\r\n" + msg + "\r\n");
        assertNotNull(endpoint.awaitLineContaining("BYE"));
    }

    // ---------------------------------------------------------------

    private static final class ScriptProvider implements ImapServerSessionProvider {
        private final ScriptHandler handler;

        ScriptProvider(ScriptHandler handler) {
            this.handler = handler;
        }

        @Override
        public ClientConnected openSession(TcpListener listener) {
            return handler;
        }
    }

    private static final class ScriptHandler implements ClientConnected,
            NotAuthenticatedHandler, AuthenticatedHandler, SelectedHandler {

        int connectVariant;
        int variant;
        int literalSize;

        @Override
        public void connected(ConnectedState state, Endpoint ep) {
            switch (connectVariant) {
                case 1:
                    state.acceptPreauth("preauth", this);
                    break;
                case 2:
                    state.rejectConnection();
                    break;
                case 3:
                    state.rejectConnection("custom");
                    break;
                case 4:
                    state.serverShuttingDown();
                    break;
                default:
                    state.acceptConnection("hello", this);
                    break;
            }
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void authenticate(AuthenticateState state, Principal principal,
                MailboxFactory factory) {
            MailboxStore store = null;
            switch (variant) {
                case 1:
                    state.reject("denied", this);
                    break;
                case 2:
                    state.reject("denied2", this);
                    break;
                case 3:
                    store = factory.createStore();
                    try {
                        store.open(principal.getName());
                    } catch (IOException e) {
                        state.reject("io", this);
                        return;
                    }
                    state.accept(store, this);
                    break;
                case 4:
                    store = factory.createStore();
                    try {
                        store.open(principal.getName());
                    } catch (IOException e) {
                        state.reject("io", this);
                        return;
                    }
                    state.accept("welcome", store, this);
                    break;
                case 5:
                    state.rejectAndClose("go away");
                    break;
                case 6:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        // ----- select / examine -----

        private void doSelect(SelectState state, MailboxStore store,
                String name, boolean readOnly) {
            Mailbox mb = null;
            switch (variant) {
                case 1:
                    try {
                        mb = store.openMailbox(name, readOnly);
                        Set<Flag> flags = EnumSet.of(Flag.SEEN, Flag.FLAGGED);
                        Set<Flag> perm = EnumSet.of(Flag.SEEN);
                        state.selectOk(mb, readOnly, flags, perm, 2, 1, 7L,
                                9L, this);
                    } catch (IOException e) {
                        state.selectFailed("io", this);
                    }
                    break;
                case 2:
                    try {
                        mb = store.openMailbox(name, readOnly);
                        Set<Flag> flags = EnumSet.of(Flag.SEEN, Flag.FLAGGED);
                        state.selectOk(mb, readOnly, flags, this);
                    } catch (IOException e) {
                        state.selectFailed("io", this);
                    }
                    break;
                case 3:
                    try {
                        mb = store.openMailbox(name, readOnly);
                        Set<Flag> flags = EnumSet.of(Flag.SEEN);
                        state.selectOk(mb, true, flags, this);
                    } catch (IOException e) {
                        state.selectFailed("io", this);
                    }
                    break;
                case 4:
                    state.selectFailed("failed", this);
                    break;
                case 5:
                    state.mailboxNotFound("nf", this);
                    break;
                case 6:
                    state.accessDenied("denied", this);
                    break;
                case 7:
                    state.no("nope", this);
                    break;
                case 8:
                    state.serverShuttingDown();
                    break;
                case 104:
                    state.selectFailed("failed104", this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void select(SelectState state, MailboxStore store, String name) {
            doSelect(state, store, name, false);
        }

        @Override
        public void examine(SelectState state, MailboxStore store, String name) {
            doSelect(state, store, name, true);
        }

        // ----- create / delete / rename / subscribe -----

        @Override
        public void create(CreateState state, MailboxStore store, String name) {
            switch (variant) {
                case 1:
                    state.created(this);
                    break;
                case 2:
                    state.created("made", this);
                    break;
                case 3:
                    state.alreadyExists("exists", this);
                    break;
                case 4:
                    state.cannotCreate("cannot", this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void delete(DeleteState state, MailboxStore store, String name) {
            switch (variant) {
                case 1:
                    state.deleted(this);
                    break;
                case 2:
                    state.mailboxNotFound("nf", this);
                    break;
                case 3:
                    state.cannotDelete("cannot", this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void rename(RenameState state, MailboxStore store, String oldName,
                String newName) {
            switch (variant) {
                case 1:
                    state.renamed(this);
                    break;
                case 2:
                    state.mailboxNotFound("nf", this);
                    break;
                case 3:
                    state.targetExists("exists", this);
                    break;
                case 4:
                    state.cannotRename("cannot", this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        private void doSubscribe(SubscribeState state) {
            switch (variant) {
                case 1:
                    state.subscribed(this);
                    break;
                case 2:
                    state.mailboxNotFound("nf", this);
                    break;
                case 3:
                    state.subscribeFailed("failed", this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void subscribe(SubscribeState state, MailboxStore store,
                String name) {
            doSubscribe(state);
        }

        @Override
        public void unsubscribe(SubscribeState state, MailboxStore store,
                String name) {
            doSubscribe(state);
        }

        // ----- list / status -----

        private void doList(ListState state) {
            Set<MailboxAttribute> attrs = EnumSet.of(MailboxAttribute.HASNOCHILDREN);
            switch (variant) {
                case 1:
                    state.listEntry(attrs, "/", "INBOX");
                    state.listEntry(Collections.<MailboxAttribute>emptySet(),
                            "/", "Has Space");
                    state.listComplete(this);
                    break;
                case 2: {
                    ListState.ListWriter w = state.beginList();
                    w.mailbox(attrs, "/", "INBOX");
                    w.end(this);
                    break;
                }
                case 3:
                    state.listFailed("list failed", this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void list(ListState state, MailboxStore store, String reference,
                String pattern) {
            doList(state);
        }

        @Override
        public void lsub(ListState state, MailboxStore store, String reference,
                String pattern) {
            doList(state);
        }

        @Override
        public void status(AuthenticatedStatusState state, MailboxStore store,
                String name, Set<StatusItem> items) {
            switch (variant) {
                case 1:
                    state.proceed(this);
                    break;
                case 2:
                    state.deny("denied", this);
                    break;
                case 3:
                    state.mailboxNotFound(this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void status(SelectedStatusState state, MailboxStore store,
                String name, Set<StatusItem> items) {
            switch (variant) {
                case 1:
                    state.proceed(this);
                    break;
                case 2:
                    state.deny("denied", this);
                    break;
                case 3:
                    state.mailboxNotFound(this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        // ----- append -----

        @Override
        public void append(AppendState state, MailboxStore store, String name,
                Set<Flag> flags, OffsetDateTime internalDate) {
            Mailbox mb = null;
            switch (variant) {
                case 1:
                    try {
                        mb = store.openMailbox(name, false);
                    } catch (IOException e) {
                        state.appendFailed("io", this);
                        return;
                    }
                    state.readyForData(mb, new SinkData(this));
                    break;
                case 2:
                    state.acceptLiteral(literalSize, new SinkData(this));
                    break;
                case 3:
                    state.tryCreate(this);
                    break;
                case 4:
                    state.appendFailed("failed", this);
                    break;
                case 5:
                    state.mailboxNotFound("nf", this);
                    break;
                case 6:
                    state.cannotAppend("cannot", this);
                    break;
                case 7:
                    state.messageTooLarge(10L, this);
                    break;
                case 8:
                    try {
                        mb = store.openMailbox(name, false);
                    } catch (IOException e) {
                        state.appendFailed("io", this);
                        return;
                    }
                    state.readyForData(mb, new SinkData(this, 1));
                    break;
                case 9:
                    try {
                        mb = store.openMailbox(name, false);
                    } catch (IOException e) {
                        state.appendFailed("io", this);
                        return;
                    }
                    state.readyForData(mb, new SinkData(this, 2));
                    break;
                case 10:
                    try {
                        mb = store.openMailbox(name, false);
                    } catch (IOException e) {
                        state.appendFailed("io", this);
                        return;
                    }
                    state.readyForData(mb, new SinkData(this, 3));
                    break;
                case 98:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        // ----- quota -----

        private void doQuota(QuotaState state) {
            Map<String, long[]> res = new LinkedHashMap<String, long[]>();
            res.put("STORAGE", new long[] {5L, 100L});
            switch (variant) {
                case 1:
                    state.quotaNotSupported(this);
                    break;
                case 2:
                    state.sendQuota("", res, this);
                    break;
                case 3:
                    state.quotaFailed("quota failed", this);
                    break;
                case 4:
                    state.sendQuota("root", new HashMap<String, long[]>(), this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void getQuota(QuotaState state, QuotaManager qm, MailboxStore store,
                String root) {
            doQuota(state);
        }

        @Override
        public void getQuotaRoot(QuotaState state, QuotaManager qm,
                MailboxStore store, String name) {
            doQuota(state);
        }

        @Override
        public void setQuota(QuotaState state, QuotaManager qm,
                MailboxStore store, String root, Map<String, Long> limits) {
            doQuota(state);
        }

        // ----- selected -----

        @Override
        public void close(CloseState state, Mailbox mailbox) {
            switch (variant) {
                case 1:
                    state.closeFailed("close failed", this);
                    break;
                case 2:
                    state.closeFailed("close failed2", this);
                    break;
                case 23:
                case 21:
                    state.closed(this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void unselect(CloseState state, Mailbox mailbox) {
            close(state, mailbox);
        }

        private void doExpunge(ExpungeState state) {
            switch (variant) {
                case 1:
                case 2:
                    state.expungeFailed("expunge failed", this);
                    break;
                case 22:
                    state.messageExpunged(1);
                    state.expungeComplete(this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void expunge(ExpungeState state, Mailbox mailbox) {
            doExpunge(state);
        }

        @Override
        public void uidExpunge(ExpungeState state, Mailbox mailbox,
                MessageSet uidSet) {
            doExpunge(state);
        }

        private void doStore(StoreState state) {
            switch (variant) {
                case 1:
                case 2:
                    state.storeFailed("store failed", this);
                    break;
                case 21:
                    state.flagsUpdated(1, EnumSet.of(Flag.SEEN, Flag.FLAGGED));
                    state.storeComplete(this);
                    break;
                case 22:
                    state.flagsUpdated(1, EnumSet.noneOf(Flag.class));
                    state.storeComplete(this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void store(StoreState state, Mailbox mailbox, MessageSet messages,
                StoreAction action, Set<Flag> flags, boolean silent) {
            doStore(state);
        }

        @Override
        public void uidStore(StoreState state, Mailbox mailbox, MessageSet uidSet,
                StoreAction action, Set<Flag> flags, boolean silent) {
            doStore(state);
        }

        private void doCopy(CopyState state) {
            switch (variant) {
                case 1:
                    state.copyFailed("copy failed", this);
                    break;
                case 2:
                    state.mailboxNotFound("nf", this);
                    break;
                case 3:
                    state.copyFailed("copy failed3", this);
                    break;
                case 22:
                    state.copied(this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void copy(CopyState state, MailboxStore store, Mailbox mailbox,
                MessageSet messages, String target) {
            doCopy(state);
        }

        @Override
        public void uidCopy(CopyState state, MailboxStore store, Mailbox mailbox,
                MessageSet uidSet, String target) {
            doCopy(state);
        }

        private void doMove(MoveState state) {
            switch (variant) {
                case 1:
                    state.moveFailed("move failed", this);
                    break;
                case 2:
                    state.mailboxNotFound("nf", this);
                    break;
                case 3:
                    state.moveFailed("move failed3", this);
                    break;
                case 22:
                    state.messageExpunged(1);
                    state.moved(this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void move(MoveState state, MailboxStore store, Mailbox mailbox,
                MessageSet messages, String target) {
            doMove(state);
        }

        @Override
        public void uidMove(MoveState state, MailboxStore store, Mailbox mailbox,
                MessageSet uidSet, String target) {
            doMove(state);
        }

        private void doFetch(FetchState state) {
            switch (variant) {
                case 1:
                    state.deny("fetch denied", this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void fetch(FetchState state, Mailbox mailbox, MessageSet messages,
                Set<String> items) {
            doFetch(state);
        }

        @Override
        public void uidFetch(FetchState state, Mailbox mailbox, MessageSet uidSet,
                Set<String> items) {
            doFetch(state);
        }

        private void doSearch(SearchState state) {
            switch (variant) {
                case 1:
                    state.deny("search denied", this);
                    break;
                case 99:
                    state.serverShuttingDown();
                    break;
                default:
                    state.proceed(this);
                    break;
            }
        }

        @Override
        public void search(SearchState state, Mailbox mailbox,
                SearchCriteria criteria) {
            doSearch(state);
        }

        @Override
        public void uidSearch(SearchState state, Mailbox mailbox,
                SearchCriteria criteria) {
            doSearch(state);
        }
    }

    /** Append data sink that writes the message to the mailbox. */
    private static final class SinkData implements AppendDataHandler {
        private final ScriptHandler owner;
        private final int mode;
        private Runnable resume;

        SinkData(ScriptHandler owner) {
            this(owner, 0);
        }

        SinkData(ScriptHandler owner, int mode) {
            this.owner = owner;
            this.mode = mode;
        }

        @Override
        public void appendData(Mailbox mailbox, ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void appendComplete(AppendCompleteState state, Mailbox mailbox) {
            switch (mode) {
                case 1:
                    state.appended(owner);
                    break;
                case 2:
                    state.appendedWithUid(5L, 6L, owner);
                    break;
                case 3:
                    state.appendFailed("append failed", owner);
                    break;
                case 4:
                    state.serverShuttingDown();
                    break;
                default:
                    state.appended(owner);
                    break;
            }
        }

        @Override
        public boolean wantsPause() {
            return false;
        }

        @Override
        public void setResumeCallback(Runnable callback) {
            this.resume = callback;
        }
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
