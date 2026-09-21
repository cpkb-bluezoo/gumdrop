/*
 * DefaultPop3HandlerMailboxTest.java
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

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.junit.Test;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Transaction-state coverage for {@link DefaultPOP3Handler} with stub mailboxes.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultPop3HandlerMailboxTest {

    private final DefaultPOP3Handler handler = new DefaultPOP3Handler("+OK");

    @Test
    public void testListSingleNoSuchMessage() {
        RecordingListState state = new RecordingListState();
        handler.list(state, new TwoMessageMailbox(), 99);
        assertTrue(state.noSuchMessage);
    }

    @Test
    public void testListSingleDeletedMessage() {
        TwoMessageMailbox mailbox = new TwoMessageMailbox();
        mailbox.deleted.add(Integer.valueOf(1));
        RecordingListState state = new RecordingListState();
        handler.list(state, mailbox, 1);
        assertTrue(state.messageDeleted);
    }

    @Test
    public void testListAllSkipsDeletedWithoutCache() {
        TwoMessageMailbox mailbox = new TwoMessageMailbox();
        mailbox.deleted.add(Integer.valueOf(1));
        RecordingListState state = new RecordingListState();
        handler.list(state, mailbox, 0);
        assertEquals(1, state.listed.size());
        assertEquals(2, state.listed.get(0).messageNumber);
    }

    @Test
    public void testListAllUsesMessageListingCache() {
        TwoMessageMailbox mailbox = new TwoMessageMailbox();
        Pop3MessageListingCache cache = new Pop3MessageListingCache();
        CachingListState state = new CachingListState(cache);
        handler.list(state, mailbox, 0);
        assertEquals(2, state.listed.size());
        mailbox.deleted.add(Integer.valueOf(1));
        state.listed.clear();
        handler.list(state, mailbox, 0);
        assertEquals("cached listing is reused without rebuilding from mailbox",
                2, state.listed.size());
    }

    @Test
    public void testMarkDeletedSuccess() throws IOException {
        TwoMessageMailbox mailbox = new TwoMessageMailbox();
        RecordingMarkDeletedState state = new RecordingMarkDeletedState();
        handler.markDeleted(state, mailbox, 1);
        assertTrue(state.marked);
        assertTrue(mailbox.deleted.contains(Integer.valueOf(1)));
    }

    @Test
    public void testResetUndeletesAndReportsSize() throws IOException {
        TwoMessageMailbox mailbox = new TwoMessageMailbox();
        mailbox.deleted.add(Integer.valueOf(1));
        RecordingResetState state = new RecordingResetState();
        handler.reset(state, mailbox);
        assertEquals(2, state.count);
        assertEquals(150L, state.size);
        assertTrue(mailbox.deleted.isEmpty());
    }

    private static final class TwoMessageMailbox implements Mailbox {
        private final List<Integer> deleted = new ArrayList<Integer>();

        @Override
        public void close(boolean expunge) {
        }

        @Override
        public int getMessageCount() {
            return 2;
        }

        @Override
        public long getMailboxSize() {
            return 150;
        }

        @Override
        public Iterator<MessageDescriptor> getMessageList() {
            List<MessageDescriptor> messages = Arrays.asList(
                    (MessageDescriptor) new StubDescriptor(1, 100L),
                    (MessageDescriptor) new StubDescriptor(2, 50L));
            return messages.iterator();
        }

        @Override
        public MessageDescriptor getMessage(int messageNumber) {
            if (messageNumber == 1) {
                return new StubDescriptor(1, 100L);
            }
            if (messageNumber == 2) {
                return new StubDescriptor(2, 50L);
            }
            return null;
        }

        @Override
        public ReadableByteChannel getMessageContent(int messageNumber) {
            return null;
        }

        @Override
        public ReadableByteChannel getMessageTop(int messageNumber, int bodyLines) {
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
        public void undeleteAll() {
            deleted.clear();
        }

        @Override
        public String getUniqueId(int messageNumber) {
            return "uid-" + messageNumber;
        }
    }

    private static final class StubDescriptor implements MessageDescriptor {
        private final int number;
        private final long size;

        StubDescriptor(int number, long size) {
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

    private static final class ListedMessage {
        final int messageNumber;
        final long size;

        ListedMessage(int messageNumber, long size) {
            this.messageNumber = messageNumber;
            this.size = size;
        }
    }

    private static class RecordingListState implements ListState {
        boolean noSuchMessage;
        boolean messageDeleted;
        final List<ListedMessage> listed = new ArrayList<ListedMessage>();

        @Override
        public void sendListing(int messageNumber, long size, TransactionHandler handler) {
            listed.add(new ListedMessage(messageNumber, size));
        }

        @Override
        public ListWriter beginListing(int messageCount) {
            return new ListWriter() {
                @Override
                public void message(int messageNumber, long size) {
                    listed.add(new ListedMessage(messageNumber, size));
                }

                @Override
                public void end(TransactionHandler handler) {
                }
            };
        }

        @Override
        public void noSuchMessage(TransactionHandler handler) {
            noSuchMessage = true;
        }

        @Override
        public void messageDeleted(TransactionHandler handler) {
            messageDeleted = true;
        }

        @Override
        public void error(String message, TransactionHandler handler) {
        }
    }

    private static final class CachingListState extends RecordingListState
            implements MessageListingCacheHost {
        private final Pop3MessageListingCache cache;

        CachingListState(Pop3MessageListingCache cache) {
            this.cache = cache;
        }

        @Override
        public Pop3MessageListingCache getMessageListingCache() {
            return cache;
        }
    }

    private static final class RecordingMarkDeletedState implements MarkDeletedState {
        boolean marked;
        boolean noSuch;
        boolean already;

        @Override
        public void markedDeleted(TransactionHandler handler) {
            marked = true;
        }

        @Override
        public void markedDeleted(String message, TransactionHandler handler) {
            marked = true;
        }

        @Override
        public void noSuchMessage(TransactionHandler handler) {
            noSuch = true;
        }

        @Override
        public void alreadyDeleted(TransactionHandler handler) {
            already = true;
        }

        @Override
        public void error(String message, TransactionHandler handler) {
        }
    }

    private static final class RecordingResetState implements ResetState {
        int count;
        long size;

        @Override
        public void resetComplete(int messageCount, long mailboxSize, TransactionHandler handler) {
            count = messageCount;
            size = mailboxSize;
        }

        @Override
        public void error(String message, TransactionHandler handler) {
        }
    }
}
