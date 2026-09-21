/*
 * Pop3MessageListingCacheTest.java
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for {@link Pop3MessageListingCache}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Pop3MessageListingCacheTest {

    @Test
    public void testSnapshotBuildsFromMailbox() throws IOException {
        Pop3MessageListingCache cache = new Pop3MessageListingCache();
        List<Pop3MessageListingCache.Entry> entries =
                cache.snapshot(new ListingMailbox());
        assertEquals(2, entries.size());
        assertEquals(1, entries.get(0).getMessageNumber());
        assertEquals(100L, entries.get(0).getSize());
        assertEquals("uid-1", entries.get(0).getUniqueId());
        assertEquals(2, entries.get(1).getMessageNumber());
    }

    @Test
    public void testSnapshotReusesBuiltList() throws IOException {
        Pop3MessageListingCache cache = new Pop3MessageListingCache();
        ListingMailbox mailbox = new ListingMailbox();
        List<Pop3MessageListingCache.Entry> first = cache.snapshot(mailbox);
        mailbox.deleted.add(Integer.valueOf(2));
        List<Pop3MessageListingCache.Entry> second = cache.snapshot(mailbox);
        assertSame(first, second);
        assertEquals(2, second.size());
    }

    @Test
    public void testRemoveMessageUpdatesCache() throws IOException {
        Pop3MessageListingCache cache = new Pop3MessageListingCache();
        cache.snapshot(new ListingMailbox());
        cache.removeMessage(1);
        assertEquals(1, cache.snapshot(new ListingMailbox()).size());
    }

    @Test
    public void testInvalidateForcesRebuild() throws IOException {
        Pop3MessageListingCache cache = new Pop3MessageListingCache();
        ListingMailbox mailbox = new ListingMailbox();
        cache.snapshot(mailbox);
        mailbox.deleted.add(Integer.valueOf(2));
        cache.invalidate();
        assertEquals(1, cache.snapshot(mailbox).size());
    }

    @Test
    public void testForStateReturnsNullWhenNotHosted() {
        assertNull(Pop3MessageListingCache.forState("not-a-host"));
    }

    @Test
    public void testForStateReturnsCacheFromHost() {
        Pop3MessageListingCache cache = new Pop3MessageListingCache();
        MessageListingCacheHost host = new MessageListingCacheHost() {
            @Override
            public Pop3MessageListingCache getMessageListingCache() {
                return cache;
            }
        };
        assertSame(cache, Pop3MessageListingCache.forState(host));
    }

    private static final class ListingMailbox implements Mailbox {
        private final java.util.List<Integer> deleted = new java.util.ArrayList<Integer>();

        @Override
        public void close(boolean expunge) {
        }

        @Override
        public int getMessageCount() {
            return 2;
        }

        @Override
        public long getMailboxSize() {
            return 200;
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
        }

        @Override
        public boolean isDeleted(int messageNumber) {
            return deleted.contains(Integer.valueOf(messageNumber));
        }

        @Override
        public void undeleteAll() {
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
}
