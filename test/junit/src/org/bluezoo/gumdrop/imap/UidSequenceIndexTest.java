/*
 * UidSequenceIndexTest.java
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

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.mailbox.MessageSet;

import org.junit.Test;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Regression coverage for issue #315: UID FETCH/STORE/COPY/MOVE previously
 * scanned every message to resolve a narrow UID set.
 */
public class UidSequenceIndexTest {

    private static final int MAILBOX_SIZE = 10_000;

    @Test
    public void narrowUidRangeResolvesWithoutScanningEveryMessage()
            throws Exception {
        CountingUidMailbox mailbox = new CountingUidMailbox(MAILBOX_SIZE);
        UidSequenceIndex index = UidSequenceIndex.build(mailbox);
        assertEquals("index build walks the mailbox once",
                MAILBOX_SIZE, mailbox.getUniqueIdCalls());

        mailbox.resetUniqueIdCalls();
        List<Integer> matches = index.resolve(
                MessageSet.parse("9999:9999"), MAILBOX_SIZE);

        assertEquals(Collections.singletonList(Integer.valueOf(9999)), matches);
        assertEquals("resolving a narrow UID range must use the index, "
                + "not re-walk every message",
                0, mailbox.getUniqueIdCalls());
    }

    @Test
    public void uidWildcardRangeResolvesCorrectly() throws Exception {
        CountingUidMailbox mailbox = new CountingUidMailbox(100);
        UidSequenceIndex index = UidSequenceIndex.build(mailbox);

        List<Integer> matches = index.resolve(MessageSet.parse("98:*"), 100);
        assertEquals(3, matches.size());
        assertEquals(Integer.valueOf(98), matches.get(0));
        assertEquals(Integer.valueOf(99), matches.get(1));
        assertEquals(Integer.valueOf(100), matches.get(2));
    }

    /**
     * Stub mailbox whose sequence number equals UID and counts
     * {@link #getUniqueId} calls.
     */
    private static final class CountingUidMailbox implements Mailbox {
        private final int messageCount;
        private int uniqueIdCalls;

        CountingUidMailbox(int messageCount) {
            this.messageCount = messageCount;
        }

        int getUniqueIdCalls() {
            return uniqueIdCalls;
        }

        void resetUniqueIdCalls() {
            uniqueIdCalls = 0;
        }

        @Override
        public int getMessageCount() {
            return messageCount;
        }

        @Override
        public boolean isDeleted(int messageNumber) {
            return false;
        }

        @Override
        public String getUniqueId(int messageNumber) {
            uniqueIdCalls++;
            return String.valueOf(messageNumber);
        }

        @Override
        public long getUidNext() {
            return messageCount + 1L;
        }

        @Override public void close(boolean expunge) { }
        @Override public long getMailboxSize() { return 0; }
        @Override public Iterator<MessageDescriptor> getMessageList() {
            return Collections.emptyIterator();
        }
        @Override public MessageDescriptor getMessage(int messageNumber) {
            return null;
        }
        @Override public Path getMessagePath(int messageNumber) {
            return null;
        }
        @Override public ReadableByteChannel getMessageContent(int messageNumber) {
            return null;
        }
        @Override public long getMessageTopEndOffset(int messageNumber, int bodyLines) {
            return 0;
        }
        @Override public ReadableByteChannel getMessageTop(int messageNumber, int bodyLines) {
            return null;
        }
        @Override public Set<Flag> getFlags(int messageNumber) {
            return Collections.emptySet();
        }
        @Override public void setFlags(int messageNumber, Set<Flag> flags, boolean add) { }
        @Override public void replaceFlags(int messageNumber, Set<Flag> flags) { }
        @Override public void deleteMessage(int messageNumber) { }
        @Override public void undeleteAll() { }
        @Override public List<Integer> expunge() { return Collections.emptyList(); }
        @Override public long getUidValidity() { return 1; }
        @Override public void startAppendMessage(Set<Flag> flags, OffsetDateTime internalDate) { }
        @Override public void appendMessageContent(ByteBuffer data) { }
        @Override public long endAppendMessage() { return 0; }
        @Override public Map<Integer, Long> copyMessages(List<Integer> messageNumbers,
                String targetMailboxName) {
            return null;
        }
        @Override public Map<Integer, Long> moveMessages(List<Integer> messageNumbers,
                String targetMailboxName) {
            return null;
        }
        @Override public Set<Flag> getPermanentFlags() {
            return Collections.emptySet();
        }
        @Override public long getHighestModSeq() { return 0; }
        @Override public long getModSeq(int messageNumber) { return 0; }
        @Override public List<Long> getExpungedSince(long modSeq) {
            return Collections.emptyList();
        }
        @Override public List<Long> getChangedSince(long modSeq) {
            return Collections.emptyList();
        }
    }
}
