/*
 * ImapClientEndToEndTest.java
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

package org.bluezoo.gumdrop.imap.client;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.MailboxFixtures;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.imap.ImapListener;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import static org.junit.Assert.*;

/**
 * End-to-end test driving {@link ImapClient} against a real
 * {@link ImapListener} over loopback, backed by a private copy of the mbox
 * fixture. Exercises the client state machine, response lexer and reply
 * handlers together with the server command handlers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapClientEndToEndTest {

    private static final int PORT = 11243;
    private static final String HOST = "::1";
    private static final String USER = "editor";
    private static final String PASS = "editor";

    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(60, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();

    private Gumdrop gumdrop;
    private Path maildirRoot;

    @Before
    public void setUp() throws Exception {
        maildirRoot = MailboxFixtures.copy("maildir");
        ImapListener server = new ImapListener();
        server.setPort(PORT);
        server.setAddresses(HOST);
        server.setRealm(new TestRealm());
        server.setMailboxFactory(new MaildirMailboxFactory(maildirRoot));
        server.setAllowPlaintextLogin(true);
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        gumdrop.addListener(server);
        waitForPort();
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (gumdrop != null) {
                gumdrop.shutdown();
                gumdrop.join();
            }
        } finally {
            MailboxFixtures.delete(maildirRoot);
        }
    }

    private void waitForPort() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(HOST, PORT), 200);
                socket.close();
                return;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        fail("IMAP server did not start");
    }

    /**
     * Runs a scripted session and waits for it to finish.
     */
    private Script run(Script script) throws Exception {
        ImapClient client = new ImapClient(HOST, PORT);
        client.connect(gumdrop, script);
        boolean finished = script.done.await(30, TimeUnit.SECONDS);
        client.close();
        assertTrue("session did not finish; log=" + script.log, finished);
        return script;
    }

    // ---- tests ----

    @Test
    public void testLoginFailure() throws Exception {
        Script s = run(new Script(USER, "wrong") {
            void step(String event, ClientAuthenticatedState a,
                    ClientSelectedState sel) {
            }
        });
        assertTrue(s.log.toString(), s.log.contains("greeting"));
        assertTrue(s.log.toString(), s.log.contains("authfailed"));
        assertFalse(s.log.contains("authenticated"));
    }

    @Test
    public void testLoginAndLogout() throws Exception {
        Script s = run(new Script(USER, PASS) {
            void step(String event, ClientAuthenticatedState a,
                    ClientSelectedState sel) {
                if ("authenticated".equals(event)) {
                    a.logout();
                }
            }
        });
        assertTrue(s.log.toString(), s.log.contains("authenticated"));
    }

    @Test
    public void testNamespaceNoopList() throws Exception {
        Script s = run(new Script(USER, PASS) {
            void step(String event, ClientAuthenticatedState a,
                    ClientSelectedState sel) {
                if ("authenticated".equals(event)) {
                    pending = "namespace";
                    a.namespace(this);
                } else if ("namespace".equals(event)) {
                    pending = "noop";
                    a.noop(this);
                } else if ("noop:ok".equals(event)) {
                    pending = "list";
                    a.list("", "*", this);
                } else if ("list:done".equals(event)) {
                    a.logout();
                }
            }
        });
        String log = s.log.toString();
        assertTrue(log, s.log.contains("namespace"));
        assertTrue(log, s.log.contains("noop:ok"));
        assertTrue(log, s.log.contains("list:INBOX"));
    }

    @Test
    public void testMailboxLifecycle() throws Exception {
        Script s = run(new Script(USER, PASS) {
            void step(String event, ClientAuthenticatedState a,
                    ClientSelectedState sel) {
                if ("authenticated".equals(event)) {
                    pending = "create";
                    a.create("ITBox", this);
                } else if ("create:ok".equals(event)) {
                    pending = "subscribe";
                    a.subscribe("ITBox", this);
                } else if ("subscribe:ok".equals(event)) {
                    pending = "lsub";
                    a.lsub("", "*", this);
                } else if ("lsub:done".equals(event)) {
                    pending = "unsubscribe";
                    a.unsubscribe("ITBox", this);
                } else if ("unsubscribe:ok".equals(event)) {
                    pending = "rename";
                    a.rename("ITBox", "ITBox2", this);
                } else if ("rename:ok".equals(event)) {
                    pending = "status";
                    a.status("ITBox2", new String[] {"MESSAGES", "UIDNEXT"}, this);
                } else if ("status".equals(event)) {
                    pending = "delete";
                    a.delete("ITBox2", this);
                } else if ("delete:ok".equals(event)) {
                    pending = "delete2";
                    a.delete("ITBox2", this);
                } else if ("delete2:ok".equals(event)
                        || "delete2:no".equals(event)) {
                    a.logout();
                }
            }
        });
        String log = s.log.toString();
        assertTrue(log, s.log.contains("create:ok"));
        assertTrue(log, s.log.contains("rename:ok"));
        assertTrue(log, s.log.contains("status:ITBox2:0"));
        assertTrue(log, s.log.contains("delete:ok"));
        assertTrue(log, s.log.contains("delete2:no"));
    }

    @Test
    public void testSelectFetchStoreSearchExpunge() throws Exception {
        Script s = run(new Script(USER, PASS) {
            void step(String event, ClientAuthenticatedState a,
                    ClientSelectedState sel) {
                if ("authenticated".equals(event)) {
                    pending = "select";
                    a.select("INBOX", this);
                } else if ("selected".equals(event)) {
                    pending = "fetch";
                    sel.fetch("1:*", "(FLAGS UID RFC822.SIZE)", this);
                } else if ("fetch".equals(event) && !log.contains("body")) {
                    log.add("body");
                    pending = "fetch";
                    sel.fetch("1", "BODY[]", this);
                } else if ("fetch".equals(event)) {
                    pending = "store";
                    sel.store("1", "+FLAGS", new String[] {"\\Deleted"}, this);
                } else if ("store".equals(event)) {
                    pending = "search";
                    sel.search("DELETED", this);
                } else if ("search".equals(event)) {
                    pending = "uidsearch";
                    sel.uidSearch("ALL", this);
                } else if ("uidsearch".equals(event)) {
                    pending = "expunge";
                    sel.expunge(this);
                } else if ("expunge".equals(event)) {
                    pending = "close";
                    sel.close(this);
                } else if ("closed".equals(event)) {
                    pending = "status";
                    a.status("INBOX", new String[] {"MESSAGES"}, this);
                } else if ("status".equals(event)) {
                    a.logout();
                }
            }
        });
        String log = s.log.toString();
        assertTrue(log, log.contains("exists=2"));
        assertTrue(log, log.contains("fetch:1:"));
        assertTrue(log, log.contains("fetch:2:"));
        assertTrue("literal body received; " + log, s.literalBytes > 0);
        assertTrue(log, log.contains("store:1:"));
        assertTrue(log, log.contains("search:1"));
        assertTrue(log, log.contains("uidsearch:2"));
        assertTrue(log, s.log.contains("expunge"));
        assertTrue(log, s.log.contains("status:INBOX:1"));
        assertTrue(log, s.log.contains("closed"));
    }

    @Test
    public void testExamineCopyAppend() throws Exception {
        final byte[] msg = ("From: a@example.org\r\nTo: b@example.org\r\n"
                + "Subject: appended\r\n\r\nbody\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        Script s = run(new Script(USER, PASS) {
            void step(String event, ClientAuthenticatedState a,
                    ClientSelectedState sel) {
                if ("authenticated".equals(event)) {
                    pending = "create";
                    a.create("Dest", this);
                } else if ("create:ok".equals(event)) {
                    pending = "append";
                    a.append("Dest", new String[] {"\\Seen"}, null,
                            msg.length, this);
                } else if ("append".equals(event)) {
                    pending = "examine";
                    a.examine("INBOX", this);
                } else if ("selected".equals(event)) {
                    pending = "copy";
                    sel.copy("1", "Dest", this);
                } else if ("copy".equals(event)) {
                    pending = "close";
                    sel.unselect(this);
                } else if ("closed".equals(event)) {
                    pending = "select2";
                    a.select("Dest", this);
                } else if ("selected2".equals(event)) {
                    a.logout();
                }
            }

            @Override
            public void handleReadyForData(ClientAppendState append) {
                append.writeContent(ByteBuffer.wrap(msg));
                append.endAppend();
            }
        });
        String log = s.log.toString();
        assertTrue(log, s.log.contains("append"));
        assertTrue(log, s.log.contains("copy"));
        assertFalse(log, s.log.contains("copy:error"));
        // INBOX has 2 messages; Dest holds the appended one plus the copy
        assertTrue(log, s.log.contains("exists=2"));
        assertEquals(log, 2, countOf(s.log, "exists=2"));
    }

    @Test
    public void testMove() throws Exception {
        Script s = run(new Script(USER, PASS) {
            void step(String event, ClientAuthenticatedState a,
                    ClientSelectedState sel) {
                if ("authenticated".equals(event)) {
                    pending = "create";
                    a.create("Moved", this);
                } else if ("create:ok".equals(event)) {
                    pending = "select";
                    a.select("INBOX", this);
                } else if ("selected".equals(event)) {
                    pending = "move";
                    sel.move("1", "Moved", this);
                } else if ("copy".equals(event) || "move:error".equals(event)) {
                    pending = "status";
                    a.status("INBOX", new String[] {"MESSAGES"}, this);
                } else if ("status".equals(event) && !log.contains("moved")) {
                    log.add("moved");
                    pending = "status";
                    a.status("Moved", new String[] {"MESSAGES"}, this);
                } else if ("status".equals(event)) {
                    a.logout();
                }
            }
        });
        String log = s.log.toString();
        assertFalse(log, s.log.contains("move:error"));
        assertTrue(log, s.log.contains("copy"));
        assertTrue(log, s.log.contains("status:INBOX:1"));
        assertTrue(log, s.log.contains("status:Moved:1"));
    }

    private static int countOf(List<String> log, String entry) {
        int count = 0;
        for (int i = 0; i < log.size(); i++) {
            if (entry.equals(log.get(i))) {
                count++;
            }
        }
        return count;
    }

    // ---- scripted client ----

    /**
     * A single object implementing every reply handler; each completion
     * callback records an event and calls {@link #step} so each test reads as
     * a flat state machine.
     */
    private abstract static class Script implements RemoteGreeting,
            LoginReplyHandler, MailboxReplyHandler, ListReplyHandler,
            SelectReplyHandler, StatusReplyHandler, SearchReplyHandler,
            StoreReplyHandler, FetchReplyHandler, ExpungeReplyHandler,
            CloseReplyHandler, CopyReplyHandler, NamespaceReplyHandler,
            AppendReplyHandler, NoopReplyHandler {

        final List<String> log =
                Collections.synchronizedList(new java.util.ArrayList<String>());
        final CountDownLatch done = new CountDownLatch(1);
        private final String user;
        private final String pass;
        volatile String pending = "";
        volatile long literalBytes;
        private int selects;
        private ClientSelectedState lastSelected;

        Script(String user, String pass) {
            this.user = user;
            this.pass = pass;
        }

        abstract void step(String event, ClientAuthenticatedState a,
                ClientSelectedState sel);

        private void ev(String event, ClientAuthenticatedState a,
                ClientSelectedState sel) {
            log.add(event);
            if (sel != null) {
                lastSelected = sel;
            }
            try {
                step(event, a, sel != null ? sel : lastSelected);
            } catch (RuntimeException e) {
                log.add("exception:" + e);
                done.countDown();
            }
        }

        @Override
        public void handleGreeting(ClientNotAuthenticatedState auth,
                String greeting, List<String> caps) {
            log.add("greeting");
            auth.login(user, pass, this);
        }

        @Override
        public void handlePreAuthenticated(ClientAuthenticatedState auth,
                String greeting) {
            ev("authenticated", auth, null);
        }

        @Override
        public void handleServiceUnavailable(String message) {
            log.add("unavailable");
            done.countDown();
        }

        @Override
        public void handleAuthenticated(ClientAuthenticatedState session,
                List<String> caps) {
            ev("authenticated", session, null);
        }

        @Override
        public void handleAuthFailed(ClientNotAuthenticatedState auth,
                String message) {
            log.add("authfailed");
            auth.logout();
        }

        @Override
        public void handleServiceClosing(String message) {
            log.add("closing");
            done.countDown();
        }

        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void onError(Exception cause) {
            log.add("error:" + cause);
            done.countDown();
        }

        @Override
        public void onDisconnected() {
            done.countDown();
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        // Mailbox / noop
        @Override
        public void handleOk(ClientAuthenticatedState session) {
            ev(pending + ":ok", session, null);
        }

        @Override
        public void handleNo(ClientAuthenticatedState session, String message) {
            ev(pending + ":no", session, null);
        }

        // List
        @Override
        public void handleListEntry(String attributes, String delimiter,
                String name) {
            log.add("list:" + name);
        }

        @Override
        public void handleListComplete(ClientAuthenticatedState session) {
            ev(pending + ":done", session, null);
        }

        // Shared error/failure callbacks
        @Override
        public void handleError(ClientAuthenticatedState session,
                String message) {
            ev(pending + ":error", session, null);
        }

        @Override
        public void handleError(ClientSelectedState selected, String message) {
            ev(pending + ":error", selected, selected);
        }

        @Override
        public void handleFailed(ClientAuthenticatedState session,
                String message) {
            ev(pending + ":failed", session, null);
        }

        // Select
        @Override
        public void handleSelected(ClientSelectedState selected,
                MailboxInfo info) {
            log.add("exists=" + info.getExists());
            selects++;
            ev(selects > 1 ? "selected2" : "selected", selected, selected);
        }

        // Status
        @Override
        public void handleStatus(ClientAuthenticatedState session,
                String mailbox, int messages, int recent, long uidNext,
                long uidValidity, int unseen) {
            log.add("status:" + mailbox + ":" + messages);
            ev("status", session, null);
        }

        // Search
        @Override
        public void handleSearchResults(ClientSelectedState selected,
                long[] results) {
            log.add(pending + ":" + results.length);
            ev(pending, selected, selected);
        }

        // Store
        @Override
        public void handleStoreResponse(int messageNumber, String[] flags) {
            log.add("store:" + messageNumber + ":" + flags.length);
        }

        @Override
        public void handleStoreComplete(ClientSelectedState selected) {
            ev("store", selected, selected);
        }

        // Fetch
        @Override
        public void handleFetchResponse(int messageNumber, FetchData data) {
            log.add("fetch:" + messageNumber + ":uid=" + data.getUid());
        }

        @Override
        public void handleFetchLiteralBegin(int messageNumber, String section,
                long size) {
            log.add("literal:" + size);
        }

        @Override
        public void handleFetchLiteralContent(ByteBuffer content) {
            literalBytes += content.remaining();
        }

        @Override
        public void handleFetchLiteralEnd(int messageNumber) {
        }

        @Override
        public void handleFetchComplete(ClientSelectedState selected) {
            ev("fetch", selected, selected);
        }

        @Override
        public boolean wantsPause() {
            return false;
        }

        @Override
        public void setResumeCallback(Runnable callback) {
        }

        // Expunge
        @Override
        public void handleExpunged(int messageNumber) {
            log.add("expunged:" + messageNumber);
        }

        @Override
        public void handleExpungeComplete(ClientSelectedState selected) {
            ev("expunge", selected, selected);
        }

        // Close
        @Override
        public void handleClosed(ClientAuthenticatedState session) {
            ev("closed", session, null);
        }

        // Copy
        @Override
        public void handleCopyComplete(ClientAuthenticatedState session,
                long uidValidity, String sourceUids, String destUids) {
            ev("copy", session, null);
        }

        // Namespace
        @Override
        public void handleNamespace(ClientAuthenticatedState session,
                String personal, String personalDelimiter) {
            ev("namespace", session, null);
        }

        // Append
        @Override
        public void handleReadyForData(ClientAppendState append) {
            append.endAppend();
        }

        @Override
        public void handleAppendComplete(ClientAuthenticatedState session,
                long uidValidity, long uid) {
            ev("append", session, null);
        }
    }

    private static class TestRealm implements Realm {

        private static final Set<SaslMechanism> SUPPORTED =
            Collections.unmodifiableSet(
                EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN));

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
            return USER.equals(username) && PASS.equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }

        @Override
        public boolean userExists(String username) {
            return USER.equals(username);
        }
    }
}
