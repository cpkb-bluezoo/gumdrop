/*
 * Pop3ClientEndToEndTest.java
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

package org.bluezoo.gumdrop.pop3.client;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.MailboxFixtures;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxFactory;
import org.bluezoo.gumdrop.pop3.Pop3Listener;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * End-to-end test driving {@link Pop3Client} against a real
 * {@link Pop3Listener} over loopback, backed by a private copy of the mbox
 * fixture. Exercises the client state machine, response lexer, dot
 * unstuffing and reply handlers together with the server command handlers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Pop3ClientEndToEndTest {

    private static final int PORT = 11210;
    private static final String HOST = "::1";
    private static final String USER = "editor";
    private static final String PASS = "editor";

    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(60, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();

    private Gumdrop gumdrop;
    private Path mboxRoot;

    @Before
    public void setUp() throws Exception {
        mboxRoot = MailboxFixtures.copy("mbox");
        Pop3Listener server = new Pop3Listener();
        server.setPort(PORT);
        server.addresses(java.net.InetAddress.getByName(HOST));
        server.setEnableAPOP(false);
        server.setRealm(new TestRealm());
        server.setMailboxFactory(new MboxMailboxFactory(mboxRoot));
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
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
            MailboxFixtures.delete(mboxRoot);
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
        fail("POP3 server did not start");
    }

    private Script run(Script script) throws Exception {
        Pop3Client client = new Pop3Client(HOST, PORT);
        client.connect(gumdrop, script);
        boolean finished = script.done.await(30, TimeUnit.SECONDS);
        client.close();
        assertTrue("session did not finish; log=" + script.log, finished);
        return script;
    }

    // ---- tests ----

    @Test
    public void testCapabilities() throws Exception {
        Script s = run(new Script(USER, PASS) {
            void step(String event, ClientTransactionState t) {
            }
            void authorization(ClientAuthorizationState auth) {
                auth.capa(this);
            }
        });
        assertTrue(s.log.toString(), s.log.contains("greeting"));
        assertTrue(s.log.toString(), s.log.contains("capa"));
    }

    @Test
    public void testUnknownUserRejected() throws Exception {
        Script s = run(new Script("nobody", "x") {
            void step(String event, ClientTransactionState t) {
            }
        });
        assertTrue(s.log.toString(), s.log.contains("rejected")
                || s.log.contains("authfailed"));
        assertFalse(s.log.contains("authenticated"));
    }

    @Test
    public void testBadPasswordRejected() throws Exception {
        Script s = run(new Script(USER, "wrong") {
            void step(String event, ClientTransactionState t) {
            }
        });
        assertTrue(s.log.toString(), s.log.contains("authfailed"));
        assertFalse(s.log.contains("authenticated"));
    }

    @Test
    public void testStatListUidlNoop() throws Exception {
        Script s = run(new Script(USER, PASS) {
            void step(String event, ClientTransactionState t) {
                if ("authenticated".equals(event)) {
                    t.stat(this);
                } else if ("stat".equals(event)) {
                    t.list(this);
                } else if ("list:done".equals(event)) {
                    t.list(1, this);
                } else if ("list1".equals(event)) {
                    t.list(99, this);
                } else if ("nosuch".equals(event) && !log.contains("uidl")) {
                    log.add("uidl");
                    t.uidl(this);
                } else if ("uidl:done".equals(event)) {
                    t.uidl(1, this);
                } else if ("uid1".equals(event)) {
                    t.noop(this);
                } else if ("noop".equals(event)) {
                    t.quit();
                }
            }
        });
        String log = s.log.toString();
        assertTrue(log, s.log.contains("stat:2"));
        assertTrue(log, s.log.contains("entry:1"));
        assertTrue(log, s.log.contains("entry:2"));
        assertTrue(log, s.log.contains("list1"));
        assertTrue(log, s.log.contains("nosuch"));
        assertTrue(log, s.log.contains("uidentry:1"));
        assertTrue(log, s.log.contains("uid1"));
        assertTrue(log, s.log.contains("noop"));
    }

    @Test
    public void testRetrDeleRset() throws Exception {
        Script s = run(new Script(USER, PASS) {
            void step(String event, ClientTransactionState t) {
                if ("authenticated".equals(event)) {
                    t.retr(1, this);
                } else if ("retr".equals(event)) {
                    t.dele(1, this);
                } else if ("dele".equals(event)) {
                    // deleting again reports no such message
                    t.dele(1, this);
                } else if ("nosuch".equals(event)) {
                    t.rset(this);
                } else if ("rset".equals(event)) {
                    t.retr(99, this);
                } else if ("nosuch2".equals(event)) {
                    t.quit();
                }
            }
        });
        String log = s.log.toString();
        assertTrue(log, s.contentBytes > 0);
        assertTrue(log, s.log.contains("retr"));
        assertTrue(log, s.log.contains("dele"));
        assertTrue(log, s.log.contains("nosuch"));
        assertTrue(log, s.log.contains("nosuch2"));
        assertTrue(log, s.log.contains("rset"));
    }

    // ---- scripted client ----

    private abstract static class Script implements RemoteGreeting,
            CapaReplyHandler, UserReplyHandler, PassReplyHandler,
            StatReplyHandler, ListReplyHandler, RetrReplyHandler,
            DeleReplyHandler, UidlReplyHandler, RsetReplyHandler,
            NoopReplyHandler {

        final List<String> log =
                Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch done = new CountDownLatch(1);
        private final String user;
        private final String pass;
        volatile long contentBytes;
        private int nosuch;

        Script(String user, String pass) {
            this.user = user;
            this.pass = pass;
        }

        abstract void step(String event, ClientTransactionState t);

        /** Overridden by tests that stay in the authorization state. */
        void authorization(ClientAuthorizationState auth) {
            auth.user(user, this);
        }

        private void ev(String event, ClientTransactionState t) {
            log.add(event);
            try {
                step(event, t);
            } catch (RuntimeException e) {
                log.add("exception:" + e);
                done.countDown();
            }
        }

        @Override
        public void handleGreeting(ClientAuthorizationState auth,
                String message, String apopTimestamp) {
            log.add("greeting");
            authorization(auth);
        }

        @Override
        public void handleServiceUnavailable(String message) {
            log.add("unavailable");
            done.countDown();
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

        @Override
        public void handleCapabilities(ClientAuthorizationState auth,
                boolean stls, List<String> saslMechanisms, boolean top,
                boolean uidl, boolean user, boolean pipelining,
                String implementation) {
            log.add("capa");
            auth.quit();
        }

        @Override
        public void handleError(ClientAuthorizationState auth, String message) {
            log.add("autherror");
            auth.quit();
        }

        @Override
        public void handleUserAccepted(ClientPasswordState p) {
            p.pass(pass, this);
        }

        @Override
        public void handleRejected(ClientAuthorizationState auth,
                String message) {
            log.add("rejected");
            auth.quit();
        }

        @Override
        public void handleAuthenticated(ClientTransactionState t) {
            ev("authenticated", t);
        }

        @Override
        public void handleAuthFailed(ClientAuthorizationState auth,
                String message) {
            log.add("authfailed");
            auth.quit();
        }

        @Override
        public void handleStat(ClientTransactionState t, int messageCount,
                long totalSize) {
            log.add("stat:" + messageCount);
            ev("stat", t);
        }

        @Override
        public void handleListing(ClientTransactionState t, int messageNumber,
                long size) {
            ev("list" + messageNumber, t);
        }

        @Override
        public void handleListEntry(int messageNumber, long size) {
            log.add("entry:" + messageNumber);
        }

        @Override
        public void handleListComplete(ClientTransactionState t) {
            ev("list:done", t);
        }

        @Override
        public void handleNoSuchMessage(ClientTransactionState t,
                String message) {
            nosuch++;
            ev(nosuch > 1 ? "nosuch2" : "nosuch", t);
        }

        @Override
        public void handleError(ClientTransactionState t, String message) {
            ev("error", t);
        }

        @Override
        public void handleMessageContent(ByteBuffer content) {
            contentBytes += content.remaining();
        }

        @Override
        public void handleMessageComplete(ClientTransactionState t) {
            ev("retr", t);
        }

        @Override
        public void handleMessageDeleted(ClientTransactionState t,
                String message) {
            ev("deleted-msg", t);
        }

        @Override
        public boolean wantsPause() {
            return false;
        }

        @Override
        public void setResumeCallback(Runnable callback) {
        }

        @Override
        public void handleDeleted(ClientTransactionState t) {
            ev("dele", t);
        }

        @Override
        public void handleAlreadyDeleted(ClientTransactionState t,
                String message) {
            ev("already", t);
        }

        @Override
        public void handleUid(ClientTransactionState t, int messageNumber,
                String uid) {
            ev("uid" + messageNumber, t);
        }

        @Override
        public void handleUidEntry(int messageNumber, String uid) {
            log.add("uidentry:" + messageNumber);
        }

        @Override
        public void handleUidComplete(ClientTransactionState t) {
            ev("uidl:done", t);
        }

        @Override
        public void handleResetOk(ClientTransactionState t) {
            ev("rset", t);
        }

        @Override
        public void handleOk(ClientTransactionState t) {
            ev("noop", t);
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
