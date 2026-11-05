/*
 * NntpMaildirTest.java
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

package org.bluezoo.gumdrop.nntp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Serves a real Maildir store over NNTP, to check that the stock handler
 * works with a real mailbox backend: group names, article numbers,
 * overview from the raw headers, and streaming of article content.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpMaildirTest {

    private Path root;
    private NntpProtocolHandler handler;
    private WaitingEndpoint endpoint;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("nntp-maildir");
        Path user = root.resolve("news");
        Files.createDirectories(user.resolve("cur"));
        Files.createDirectories(user.resolve("new"));
        Files.createDirectories(user.resolve("tmp"));
        MaildirMailboxFactory factory = new MaildirMailboxFactory(root);
        MailboxStore store = factory.createStore();
        store.open("news");
        store.createMailbox("comp/lang/java");
        Mailbox mailbox = store.openMailbox("comp/lang/java", false);
        append(mailbox, "Subject: Hello\r\nFrom: Ann <ann@example.com>\r\n"
                + "Date: Tue, 2 Jan 2024 10:00:00 +0000\r\nMessage-ID: <a1@example.com>\r\n"
                + "\r\nFirst body\r\n.starts with a dot\r\n");
        append(mailbox, "Subject: Re: Hello\r\n  folded\r\nFrom: Bob <bob@example.com>\r\n"
                + "Message-ID: <a2@example.com>\r\nReferences: <a1@example.com>\r\n"
                + "\r\nSecond body\r\n");
        mailbox.close(false);
        store.close();

        NntpListener listener = new NntpListener();
        listener.mailboxFactory(factory);
        handler = new NntpProtocolHandler(listener);
        endpoint = new WaitingEndpoint();
        handler.connected(endpoint);
    }

    private static void append(Mailbox mailbox, String message) throws IOException {
        mailbox.startAppendMessage(EnumSet.noneOf(Flag.class), null);
        mailbox.appendMessageContent(ByteBuffer.wrap(message.getBytes(StandardCharsets.ISO_8859_1)));
        mailbox.endAppendMessage();
    }

    @After
    public void tearDown() throws IOException {
        if (handler != null) {
            handler.disconnected();
        }
        delete(root.toFile());
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

    private String exchange(String command) {
        endpoint.sent.clear();
        handler.receive(ByteBuffer.wrap((command + "\r\n").getBytes(StandardCharsets.ISO_8859_1)));
        return endpoint.text();
    }

    /** As exchange, for a response whose content arrives from the mailbox's own thread. */
    private String exchangeAndWait(String command) throws InterruptedException {
        endpoint.done = new CountDownLatch(1);
        exchange(command);
        assertTrue("response did not end: " + endpoint.text(),
                endpoint.done.await(5, TimeUnit.SECONDS));
        return endpoint.text();
    }

    /** Releases a latch when a multi-line response is complete. */
    private static final class WaitingEndpoint extends StubEndpoint {
        volatile CountDownLatch done = new CountDownLatch(1);

        @Override
        public synchronized void send(ByteBuffer data) {
            super.send(data);
            if (text().endsWith("\r\n.\r\n")) {
                done.countDown();
            }
        }
    }

    @Test
    public void testGroupsAndArticles() throws InterruptedException {
        String list = exchange("LIST");
        assertTrue(list, list.contains("comp.lang.java 2 1 n\r\n"));
        assertEquals("211 2 1 2 comp.lang.java\r\n", exchange("GROUP comp.lang.java"));
        assertEquals("220 1 <a1@example.com> article follows\r\n"
                + "Subject: Hello\r\nFrom: Ann <ann@example.com>\r\n"
                + "Date: Tue, 2 Jan 2024 10:00:00 +0000\r\nMessage-ID: <a1@example.com>\r\n"
                + "\r\nFirst body\r\n..starts with a dot\r\n.\r\n", exchangeAndWait("ARTICLE 1"));
        assertEquals("222 2 <a2@example.com> body follows\r\nSecond body\r\n.\r\n", exchangeAndWait("BODY 2"));
        assertEquals("223 0 <a2@example.com>\r\n", exchange("STAT <a2@example.com>"));
    }

    @Test
    public void testOverviewComesFromTheRawHeaders() {
        exchange("GROUP comp.lang.java");
        String over = exchange("OVER 1-2");
        assertTrue(over, over.contains(
                "\r\n1\tHello\tAnn <ann@example.com>\tTue, 2 Jan 2024 10:00:00 +0000\t<a1@example.com>\t\t"));
        // the folded subject is unfolded, a missing Date is empty
        assertTrue(over, over.contains(
                "\r\n2\tRe: Hello folded\tBob <bob@example.com>\t\t<a2@example.com>\t<a1@example.com>\t"));
    }

}
