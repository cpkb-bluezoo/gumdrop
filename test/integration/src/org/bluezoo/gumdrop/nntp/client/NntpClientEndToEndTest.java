/*
 * NntpClientEndToEndTest.java
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

package org.bluezoo.gumdrop.nntp.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.ListenerBindCheck;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.auth.SynchronousRealm;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;
import org.bluezoo.gumdrop.nntp.NntpListener;
import org.bluezoo.gumdrop.nntp.server.MailboxStoreNntpSessionProvider;
import org.bluezoo.gumdrop.nntp.server.NntpServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives {@link NntpClient} against a real {@link NntpListener} over
 * loopback, with a Maildir store as the newsgroups. Exercises both ends'
 * state machines, the streaming of lists and articles, the storage executor
 * path of the server and the client's connection set-up.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpClientEndToEndTest {

    private static final int PORT = 11220;
    private static final String HOST = "127.0.0.1";
    private static final String GROUP = "comp.lang.java";

    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(60, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();

    private Gumdrop gumdrop;
    private Path root;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("nntp-e2e");
        Path user = root.resolve("news");
        Files.createDirectories(user.resolve("cur"));
        Files.createDirectories(user.resolve("new"));
        Files.createDirectories(user.resolve("tmp"));
        MaildirMailboxFactory factory = new MaildirMailboxFactory(root);
        MailboxStore store = factory.createStore();
        store.open("news");
        store.createMailbox("comp/lang/java");
        Mailbox mailbox = store.openMailbox("comp/lang/java", false);
        append(mailbox, "Subject: Hello\r\nFrom: ann@example.com\r\nDate: Tue, 2 Jan 2024 10:00:00 +0000\r\n"
                + "Message-ID: <a1@example.com>\r\n\r\nFirst body\r\n.starts with a dot\r\n");
        append(mailbox, "Subject: Re: Hello\r\nFrom: bob@example.com\r\nMessage-ID: <a2@example.com>\r\n"
                + "References: <a1@example.com>\r\n\r\nSecond body\r\n");
        mailbox.close(false);
        store.close();
    }

    private void startServer(boolean authRequired) throws Exception {
        NntpListener listener = new NntpListener().port(PORT)
                .addresses(InetAddress.getByName(HOST));
        NntpServer.Composer composer = NntpServer.compose()
                .listener(listener)
                .sessionProvider(new MailboxStoreNntpSessionProvider()
                        .mailboxFactory(new MaildirMailboxFactory(root)))
                .anonymousUser("news")
                .postingAllowed(true)
                .realm(new TestRealm());
        if (authRequired) {
            composer.authenticationRequired(true);
        }
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        gumdrop.addServer(composer.server());
        ListenerBindCheck.assertBound(gumdrop);
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (gumdrop != null) {
                gumdrop.shutdown();
                gumdrop.join();
            }
        } finally {
            delete(root.toFile());
        }
    }

    private static void append(Mailbox mailbox, String message) throws IOException {
        mailbox.startAppendMessage(EnumSet.noneOf(Flag.class), null);
        mailbox.appendMessageContent(ByteBuffer.wrap(message.getBytes(StandardCharsets.ISO_8859_1)));
        mailbox.endAppendMessage();
    }

    private static void delete(java.io.File f) {
        java.io.File[] children = f.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                delete(children[i]);
            }
        }
        f.delete();
    }

    /** Runs a script of steps after connecting, and records what happens. */
    private abstract static class Script implements NntpClientHandler {
        final CountDownLatch done = new CountDownLatch(1);
        final List<String> log = new CopyOnWriteArrayList<String>();
        final StringBuilder article = new StringBuilder();
        volatile NntpGreeting greeting;

        abstract void run(NntpSession session);

        /** Sets the client up before it connects. */
        void configure(NntpClient client) {
        }

        @Override
        public void connected(NntpSession session, NntpGreeting g) {
            greeting = g;
            log.add("connected");
            run(session);
        }

        @Override
        public void error(Exception cause) {
            log.add("error " + cause.getMessage());
            done.countDown();
        }

        @Override
        public void disconnected() {
            log.add("disconnected");
            done.countDown();
        }

        /** Records the outcome of a command under a label. */
        <T> NntpCallback<T> record(final String label, final Runnable next) {
            return new NntpCallback<T>() {
                @Override
                public void completed(T result) {
                    log.add(label + " ok" + describe(result));
                    if (next != null) {
                        next.run();
                    }
                }

                @Override
                public void failed(NntpException error) {
                    log.add(label + " failed " + error.getCode());
                    if (next != null) {
                        next.run();
                    }
                }
            };
        }

        private static String describe(Object result) {
            if (result instanceof GroupResult) {
                GroupResult g = (GroupResult) result;
                return " " + g.getCount() + " " + g.getFirst() + " " + g.getLast() + " " + g.getName();
            }
            return "";
        }
    }

    private Script run(Script script) throws Exception {
        NntpClient client = new NntpClient(HOST, PORT);
        script.configure(client);
        client.connect(gumdrop, script);
        boolean finished = script.done.await(30, TimeUnit.SECONDS);
        client.close();
        assertTrue("session did not finish; log=" + script.log, finished);
        return script;
    }

    @Test
    public void testBrowseReadAndPost() throws Exception {
        startServer(false);
        Script s = run(new Script() {
            @Override
            void run(final NntpSession session) {
                session.group(GROUP, this.<GroupResult>record("group", new Runnable() {
                    @Override
                    public void run() {
                        listAndRead(session);
                    }
                }));
            }

            void listAndRead(final NntpSession session) {
                final Script self = this;
                session.listActive("comp.*", new NntpConsumer<NewsgroupEntry>() {
                    @Override
                    public void accept(NewsgroupEntry e) {
                        log.add("entry " + e.getName() + " " + e.getHigh() + " " + e.getLow());
                    }
                }, this.<Void>record("list", null));
                session.over(1, 2, new NntpConsumer<OverviewEntry>() {
                    @Override
                    public void accept(OverviewEntry e) {
                        log.add("over " + e.getNumber() + " " + e.getSubject() + " " + e.getMessageId());
                    }
                }, this.<Void>record("over", null));
                session.article(1, new NntpConsumer<ByteBuffer>() {
                    @Override
                    public void accept(ByteBuffer b) {
                        byte[] bytes = new byte[b.remaining()];
                        b.get(bytes);
                        article.append(new String(bytes, StandardCharsets.ISO_8859_1));
                    }
                }, this.<Void>record("article", null));
                session.post(("From: cat@example.com\r\nSubject: New\r\nNewsgroups: " + GROUP
                        + "\r\n\r\nPosted body\r\n.leading dot\r\n").getBytes(StandardCharsets.ISO_8859_1),
                        this.<Void>record("post", new Runnable() {
                            @Override
                            public void run() {
                                session.group(GROUP, self.<GroupResult>record("regroup", new Runnable() {
                                    @Override
                                    public void run() {
                                        session.quit();
                                    }
                                }));
                            }
                        }));
            }
        });
        String log = s.log.toString();
        assertTrue(log, s.greeting.isPostingAllowed());
        assertTrue(log, s.log.contains("group ok 2 1 2 " + GROUP));
        assertTrue(log, s.log.contains("entry " + GROUP + " 2 1"));
        assertTrue(log, s.log.contains("list ok"));
        assertTrue(log, s.log.contains("over 1 Hello <a1@example.com>"));
        assertTrue(log, s.log.contains("over 2 Re: Hello <a2@example.com>"));
        assertTrue(log, s.log.contains("over ok"));
        assertEquals("Subject: Hello\r\nFrom: ann@example.com\r\nDate: Tue, 2 Jan 2024 10:00:00 +0000\r\n"
                + "Message-ID: <a1@example.com>\r\n\r\nFirst body\r\n.starts with a dot\r\n",
                s.article.toString());
        assertTrue(log, s.log.contains("post ok"));
        assertTrue(log, s.log.contains("regroup ok 3 1 3 " + GROUP));
        assertTrue(log, s.log.contains("disconnected"));
    }

    @Test
    public void testLoginRequiredAndGiven() throws Exception {
        startServer(true);
        Script anonymous = run(new Script() {
            @Override
            void run(NntpSession session) {
                session.group(GROUP, this.<GroupResult>record("group", new Runnable() {
                    @Override
                    public void run() {
                        // handled below
                    }
                }));
                session.quit();
            }
        });
        assertTrue(anonymous.log.toString(), anonymous.log.contains("group failed 480"));

        Script loggedIn = run(new Script() {
            @Override
            void configure(NntpClient client) {
                client.credentials("alice", "secret");
            }

            @Override
            void run(NntpSession session) {
                session.group(GROUP, this.<GroupResult>record("group", null));
                session.quit();
            }
        });
        assertTrue(loggedIn.greeting.isAuthenticated());
        assertTrue(loggedIn.log.toString(), loggedIn.log.contains("group failed 411")
                || loggedIn.log.contains("group ok"));
    }

    @Test
    public void testWrongPasswordIsAnError() throws Exception {
        startServer(true);
        Script s = run(new Script() {
            @Override
            void configure(NntpClient client) {
                client.credentials("alice", "wrong");
            }

            @Override
            void run(NntpSession session) {
                session.quit();
            }
        });
        assertFalse(s.log.toString(), s.log.contains("connected"));
        assertNotNull(s.log.toString(), s.log.get(0));
        assertTrue(s.log.toString(), s.log.get(0).startsWith("error"));
    }

    private static final class TestRealm implements SynchronousRealm {

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return Collections.emptySet();
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return "alice".equals(username) && "secret".equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }

        @Override
        public boolean userExists(String username) {
            return "alice".equals(username);
        }
    }
}
