/*
 * MessageSorterTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MessageContext;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.mailbox.ParsedMessageContext;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class MessageSorterTest {

    @Test
    public void testSortBySubjectThenSequence() throws Exception {
        SortMailbox mbox = new SortMailbox(
                msg(1, "Banana", "a@x.com"),
                msg(2, "Apple", "b@x.com"),
                msg(3, "Cherry", "c@x.com"));
        List<Integer> nums = new ArrayList<>(
                Arrays.asList(3, 1, 2));
        MessageSorter.sort(mbox, nums,
                Collections.singletonList(
                        new SortCriterion(SortKey.SUBJECT, false)));
        assertEquals(Arrays.asList(2, 1, 3), nums);
    }

    @Test
    public void testSearchThenSortSize() throws Exception {
        SortMailbox mbox = new SortMailbox(
                msg(1, "A", "a@x.com", 100),
                msg(2, "B", "b@x.com", 300),
                msg(3, "C", "c@x.com", 200));
        List<Integer> matches = mbox.search(SearchCriteria.all());
        MessageSorter.sort(mbox, matches,
                Collections.singletonList(
                        new SortCriterion(SortKey.SIZE, false)));
        assertEquals(Arrays.asList(1, 3, 2), matches);
    }

    static String msg(int num, String subject, String from) {
        return msg(num, subject, from, 50);
    }

    static String msg(int num, String subject, String from,
            int pad) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pad; i++) {
            body.append('x');
        }
        return "From: " + from + "\r\n"
                + "Subject: " + subject + "\r\n"
                + "Date: Mon, 0" + num + " May 2025 10:00:00 +0000\r\n"
                + "Message-ID: <m" + num + "@test>\r\n"
                + "\r\n"
                + body + "\r\n";
    }

    static final class SortMailbox implements Mailbox {
        private final String[] bodies;

        SortMailbox(String... bodies) {
            this.bodies = bodies;
        }

        @Override
        public void close(boolean expunge) {
        }

        @Override
        public int getMessageCount() {
            return bodies.length;
        }

        @Override
        public long getMailboxSize() {
            return 0;
        }

        @Override
        public Iterator<MessageDescriptor> getMessageList() {
            List<MessageDescriptor> list = new ArrayList<>();
            for (int i = 0; i < bodies.length; i++) {
                final int n = i + 1;
                list.add(new MessageDescriptor() {
                    @Override
                    public int getMessageNumber() {
                        return n;
                    }

                    @Override
                    public long getSize() {
                        return bodies[n - 1].length();
                    }

                    @Override
                    public String getUniqueId() {
                        return String.valueOf(n);
                    }
                });
            }
            return list.iterator();
        }

        @Override
        public MessageDescriptor getMessage(int messageNumber) {
            if (messageNumber < 1 || messageNumber > bodies.length) {
                return null;
            }
            final int n = messageNumber;
            return new MessageDescriptor() {
                @Override
                public int getMessageNumber() {
                    return n;
                }

                @Override
                public long getSize() {
                    return bodies[n - 1].length();
                }

                @Override
                public String getUniqueId() {
                    return String.valueOf(n);
                }
            };
        }

        @Override
        public ReadableByteChannel getMessageContent(int messageNumber)
                throws IOException {
            int idx = messageNumber - 1;
            if (idx < 0 || idx >= bodies.length) {
                throw new IOException("no message");
            }
            byte[] data = bodies[idx].getBytes(StandardCharsets.US_ASCII);
            return Channels.newChannel(
                    new java.io.ByteArrayInputStream(data));
        }

        @Override
        public ReadableByteChannel getMessageTop(int messageNumber,
                int bodyLines) throws IOException {
            return getMessageContent(messageNumber);
        }

        @Override
        public void deleteMessage(int messageNumber) {
        }

        @Override
        public boolean isDeleted(int messageNumber) {
            return false;
        }

        @Override
        public void undeleteAll() {
        }

        @Override
        public String getUniqueId(int messageNumber) {
            return String.valueOf(messageNumber);
        }

        @Override
        public Set<Flag> getFlags(int messageNumber) {
            return EnumSet.noneOf(Flag.class);
        }

        @Override
        public List<Integer> search(SearchCriteria criteria)
                throws IOException {
            List<Integer> out = new ArrayList<>();
            for (int i = 1; i <= bodies.length; i++) {
                MessageContext ctx = getMessageContext(i);
                if (criteria.matches(ctx)) {
                    out.add(i);
                }
            }
            return out;
        }

        @Override
        public MessageContext getMessageContext(int messageNumber)
                throws IOException {
            MessageDescriptor md = getMessage(messageNumber);
            if (md == null) {
                return null;
            }
            return new ParsedMessageContext(this, messageNumber,
                    messageNumber, md.getSize(), getFlags(messageNumber),
                    OffsetDateTime.of(2025, 5, messageNumber, 12, 0, 0, 0,
                            ZoneOffset.UTC));
        }
    }
}
