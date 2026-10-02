/*
 * DefaultPop3HandlerStatesTest.java
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

package org.bluezoo.gumdrop.pop3.server;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.mime.HeaderLineTooLongException;
import org.bluezoo.gumdrop.mime.HeaderValueTooLongException;

import static org.junit.Assert.*;

/**
 * Transaction-state coverage for {@link DefaultPOP3Handler}: the success,
 * missing-message, deleted-message and I/O failure branches of every
 * command hook.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultPop3HandlerStatesTest {

    private final DefaultPOP3Handler handler = new DefaultPOP3Handler("+OK");

    // ── Authorisation / update ──

    @Test
    public void authenticateProceeds() {
        Rec rec = new Rec();
        handler.authenticate(rec, new Principal() {
            @Override
            public String getName() {
                return "u";
            }
        }, null);
        assertTrue(rec.events.contains("auth-proceed"));
    }

    @Test
    public void quitProceeds() {
        Rec rec = new Rec();
        handler.quit(rec, new Box());
        assertTrue(rec.events.contains("update-proceed"));
    }

    @Test
    public void disconnectedIsNoOp() {
        handler.disconnected();
    }

    // ── STAT ──

    @Test
    public void statusReportsCountAndSize() {
        Rec rec = new Rec();
        handler.mailboxStatus(rec, new Box());
        assertTrue(rec.events.contains("status:2:150"));
    }

    @Test
    public void statusIoFailure() {
        Rec rec = new Rec();
        Box box = new Box();
        box.fail = true;
        handler.mailboxStatus(rec, box);
        assertTrue(rec.events.contains("error:Unable to get mailbox status"));
    }

    // ── LIST ──

    @Test
    public void listSingleMessage() {
        Rec rec = new Rec();
        handler.list(rec, new Box(), 2);
        assertTrue(rec.events.contains("list:2:50"));
    }

    @Test
    public void listIoFailure() {
        Rec rec = new Rec();
        Box box = new Box();
        box.fail = true;
        handler.list(rec, box, 1);
        handler.list(rec, box, 0);
        assertEquals(2, count(rec, "error:Unable to list messages"));
    }

    @Test
    public void listAllWithoutCache() {
        Rec rec = new Rec();
        handler.list(rec, new Box(), 0);
        assertTrue(rec.events.contains("begin-list:2"));
        assertTrue(rec.events.contains("list-msg:1:100"));
        assertTrue(rec.events.contains("list-msg:2:50"));
        assertTrue(rec.events.contains("list-end"));
    }

    @Test
    public void listAllWithCacheSnapshot() {
        CachedRec rec = new CachedRec();
        handler.list(rec, new Box(), 0);
        assertTrue(rec.events.contains("list-msg:1:100"));
    }

    // ── RETR ──

    @Test
    public void retrieveBranches() {
        Rec rec = new Rec();
        Box box = new Box();
        handler.retrieveMessage(rec, box, 99);
        box.deleted.add(Integer.valueOf(1));
        handler.retrieveMessage(rec, box, 1);
        handler.retrieveMessage(rec, box, 2);
        assertTrue(rec.events.contains("nosuch"));
        assertTrue(rec.events.contains("deleted"));
        assertTrue(rec.events.contains("retr-proceed:50"));
        box.fail = true;
        handler.retrieveMessage(rec, box, 2);
        assertTrue(rec.events.contains("error:Unable to retrieve message"));
    }

    // ── DELE ──

    @Test
    public void markDeletedBranches() {
        Rec rec = new Rec();
        Box box = new Box();
        handler.markDeleted(rec, box, 99);
        handler.markDeleted(rec, box, 1);
        handler.markDeleted(rec, box, 1);
        assertTrue(rec.events.contains("nosuch"));
        assertTrue(rec.events.contains("marked"));
        assertTrue(rec.events.contains("already"));
        box.fail = true;
        handler.markDeleted(rec, box, 2);
        assertTrue(rec.events.contains("error:Unable to delete message"));
    }

    // ── RSET ──

    @Test
    public void resetIoFailure() {
        Rec rec = new Rec();
        Box box = new Box();
        box.fail = true;
        handler.reset(rec, box);
        assertTrue(rec.events.contains("error:Unable to reset mailbox"));
    }

    // ── TOP ──

    @Test
    public void topBranches() {
        Rec rec = new Rec();
        Box box = new Box();
        handler.top(rec, box, 99, 5);
        box.deleted.add(Integer.valueOf(1));
        handler.top(rec, box, 1, 5);
        handler.top(rec, box, 2, 5);
        assertTrue(rec.events.contains("nosuch"));
        assertTrue(rec.events.contains("deleted"));
        assertTrue(rec.events.contains("top-proceed:5"));
    }

    @Test
    public void topIoFailureVariants() {
        Rec rec = new Rec();
        Box box = new Box();
        box.fail = true;
        handler.top(rec, box, 1, 1);
        box.failure = new IOException("x",
                new HeaderLineTooLongException("long", null));
        handler.top(rec, box, 1, 1);
        box.failure = new IOException("y",
                new HeaderValueTooLongException("long", null));
        handler.top(rec, box, 1, 1);
        assertEquals(3, count(rec, "error:", true));
        assertTrue(rec.events.contains("error:Unable to get message headers"));
    }

    // ── UIDL ──

    @Test
    public void uidlSingleBranches() {
        Rec rec = new Rec();
        Box box = new Box();
        handler.uidl(rec, box, 99);
        box.deleted.add(Integer.valueOf(1));
        handler.uidl(rec, box, 1);
        handler.uidl(rec, box, 2);
        assertTrue(rec.events.contains("nosuch"));
        assertTrue(rec.events.contains("deleted"));
        assertTrue(rec.events.contains("uid:2:uid-2"));
    }

    @Test
    public void uidlAllWithAndWithoutCache() {
        Rec rec = new Rec();
        Box box = new Box();
        box.deleted.add(Integer.valueOf(1));
        handler.uidl(rec, box, 0);
        assertTrue(rec.events.contains("uid-msg:2:uid-2"));
        assertFalse(rec.events.contains("uid-msg:1:uid-1"));
        assertTrue(rec.events.contains("uid-end"));

        CachedRec cached = new CachedRec();
        handler.uidl(cached, new Box(), 0);
        assertTrue(cached.events.contains("uid-msg:1:uid-1"));
    }

    @Test
    public void uidlIoFailure() {
        Rec rec = new Rec();
        Box box = new Box();
        box.fail = true;
        handler.uidl(rec, box, 1);
        handler.uidl(rec, box, 0);
        assertEquals(2, count(rec, "error:Unable to get unique identifiers"));
    }

    // ── Helpers ──

    private static int count(Rec rec, String event) {
        return count(rec, event, false);
    }

    private static int count(Rec rec, String prefix, boolean startsWith) {
        int n = 0;
        for (int i = 0; i < rec.events.size(); i++) {
            String e = rec.events.get(i);
            if (startsWith ? e.startsWith(prefix) : e.equals(prefix)) {
                n++;
            }
        }
        return n;
    }

    private static final class Desc implements MessageDescriptor {
        private final int number;
        private final long size;

        Desc(int number, long size) {
            this.number = number;
            this.size = size;
        }

        @Override
        public int getMessageNumber() {
            return number;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public String getUniqueId() {
            return "uid-" + number;
        }
    }

    private static final class Box implements Mailbox {
        final List<Integer> deleted = new ArrayList<Integer>();
        boolean fail;
        IOException failure;

        private void check() throws IOException {
            if (failure != null) {
                throw failure;
            }
            if (fail) {
                throw new IOException("simulated");
            }
        }

        @Override
        public void close(boolean expunge) {
        }

        @Override
        public int getMessageCount() throws IOException {
            check();
            return 2;
        }

        @Override
        public long getMailboxSize() throws IOException {
            check();
            return 150;
        }

        @Override
        public Iterator<MessageDescriptor> getMessageList() throws IOException {
            check();
            List<MessageDescriptor> messages = Arrays.asList(
                    (MessageDescriptor) new Desc(1, 100L),
                    (MessageDescriptor) new Desc(2, 50L));
            return messages.iterator();
        }

        @Override
        public MessageDescriptor getMessage(int messageNumber)
                throws IOException {
            check();
            if (messageNumber == 1) {
                return new Desc(1, 100L);
            }
            if (messageNumber == 2) {
                return new Desc(2, 50L);
            }
            return null;
        }

        @Override
        public ReadableByteChannel getMessageContent(int messageNumber) {
            return null;
        }

        @Override
        public ReadableByteChannel getMessageTop(int messageNumber,
                                                 int bodyLines) {
            return null;
        }

        @Override
        public void deleteMessage(int messageNumber) {
            deleted.add(Integer.valueOf(messageNumber));
        }

        @Override
        public boolean isDeleted(int messageNumber) {
            return deleted.contains(Integer.valueOf(messageNumber));
        }

        @Override
        public void undeleteAll() throws IOException {
            check();
            deleted.clear();
        }

        @Override
        public String getUniqueId(int messageNumber) throws IOException {
            check();
            return "uid-" + messageNumber;
        }
    }

    /** Records every callback as a string event. */
    @SuppressWarnings("deprecation")
    private static class Rec implements AuthenticateState, UpdateState,
            MailboxStatusState, ListState, RetrieveState, MarkDeletedState,
            ResetState, TopState, UidlState {

        final List<String> events = new ArrayList<String>();

        @Override
        public void proceed(TransactionHandler h) {
            events.add("auth-proceed");
            events.add("update-proceed");
        }

        @Override
        public void accept(Mailbox mailbox, TransactionHandler h) {
        }

        @Override
        public void reject(String message, AuthorizationHandler h) {
        }

        @Override
        public void rejectAndClose(String message) {
        }

        @Override
        public void serverShuttingDown() {
        }

        @Override
        public void commitAndClose() {
        }

        @Override
        public void commitAndClose(String message) {
        }

        @Override
        public void partialCommit(String message) {
        }

        @Override
        public void updateFailed(String message) {
        }

        @Override
        public void sendStatus(int count, long size, TransactionHandler h) {
            events.add("status:" + count + ":" + size);
        }

        @Override
        public void sendListing(int n, long size, TransactionHandler h) {
            events.add("list:" + n + ":" + size);
        }

        @Override
        public ListState.ListWriter beginListing(int messageCount) {
            events.add("begin-list:" + messageCount);
            return new ListState.ListWriter() {
                @Override
                public void message(int n, long size) {
                    events.add("list-msg:" + n + ":" + size);
                }

                @Override
                public void end(TransactionHandler h) {
                    events.add("list-end");
                }
            };
        }

        @Override
        public void proceed(long size, TransactionHandler h) {
            events.add("retr-proceed:" + size);
        }

        @Override
        public void sendMessage(long size, ReadableByteChannel content,
                                TransactionHandler h) {
        }

        @Override
        public void markedDeleted(TransactionHandler h) {
            events.add("marked");
        }

        @Override
        public void markedDeleted(String message, TransactionHandler h) {
            events.add("marked");
        }

        @Override
        public void alreadyDeleted(TransactionHandler h) {
            events.add("already");
        }

        @Override
        public void resetComplete(int count, long size, TransactionHandler h) {
            events.add("reset:" + count);
        }

        @Override
        public void proceed(int lines, TransactionHandler h) {
            events.add("top-proceed:" + lines);
        }

        @Override
        public void sendTop(ReadableByteChannel content,
                            TransactionHandler h) {
        }

        @Override
        public void sendUid(int n, String uid, TransactionHandler h) {
            events.add("uid:" + n + ":" + uid);
        }

        @Override
        public UidlState.UidlWriter beginListing() {
            return new UidlState.UidlWriter() {
                @Override
                public void message(int n, String uid) {
                    events.add("uid-msg:" + n + ":" + uid);
                }

                @Override
                public void end(TransactionHandler h) {
                    events.add("uid-end");
                }
            };
        }

        @Override
        public void noSuchMessage(TransactionHandler h) {
            events.add("nosuch");
        }

        @Override
        public void messageDeleted(TransactionHandler h) {
            events.add("deleted");
        }

        @Override
        public void error(String message, TransactionHandler h) {
            events.add("error:" + message);
        }
    }

    /** Variant that exposes a listing cache. */
    private static final class CachedRec extends Rec
            implements MessageListingCacheHost {
        private final Pop3MessageListingCache cache =
                new Pop3MessageListingCache();

        @Override
        public Pop3MessageListingCache getMessageListingCache() {
            return cache;
        }
    }
}
