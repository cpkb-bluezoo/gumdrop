/*
 * ImapMockMailboxFactory.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gumdrop.mailbox.AsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.AsyncMessageWriter;
import org.bluezoo.gumdrop.mailbox.BufferedAsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.ImapMessageDescriptor;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageContext;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;

/**
 * Hand-written mock mailbox factory for IMAP handler tests. It wraps a real
 * (in-memory) Maildir factory and delegates everything to it, but exposes
 * hooks a test can set to make the mailbox report IMAP-rich message
 * descriptors (cached envelope and body structure), CONDSTORE/QRESYNC data,
 * alternative async content, a mailbox id, or to fail selected operations
 * with an {@link IOException}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ImapMockMailboxFactory implements MailboxFactory {

    /** How {@link Box#openAsyncAppend} behaves. */
    enum AppendMode { DELEGATE, NULL_WRITER, STREAM, WRITE_FAIL, FINISH_FAIL, FINISH_THROW }

    /** How {@link Box#openAsyncContent(int)} behaves. */
    enum AsyncMode { DELEGATE, NONE, NO_BODY_OFFSET, THROW, READ_ZERO, READ_FAIL }

    final MailboxFactory real;

    /** Operation names (method names) that throw an IOException. */
    final Set<String> failing = new HashSet<String>();

    /** Operation names that throw UnsupportedOperationException. */
    final Set<String> unsupported = new HashSet<String>();

    /** Message numbers whose message context is reported as absent. */
    final Set<Integer> nullContexts = new HashSet<Integer>();

    /** Descriptors by message number returned from getMessage. */
    final Map<Integer, MessageDescriptor> descriptors =
            new HashMap<Integer, MessageDescriptor>();

    AsyncMode asyncMode = AsyncMode.DELEGATE;
    AppendMode appendMode = AppendMode.DELEGATE;
    String mailboxId;
    String emailId;
    long highestModSeq;
    long modSeq;
    List<Long> changedSince = new ArrayList<Long>();
    List<Long> expungedSince = new ArrayList<Long>();
    Long uidValidity;
    /** When non-null, the unique ids the mailbox reports (and its count). */
    List<String> uidsOverride;
    boolean failStoreOpen;
    boolean failStoreClose;
    boolean failMailboxClose;
    int mailboxCloses;
    int storeCloses;
    Box lastBox;
    Store lastStore;

    ImapMockMailboxFactory(MailboxFactory real) {
        this.real = real;
    }

    @Override
    public MailboxStore createStore() {
        lastStore = new Store(real.createStore());
        return lastStore;
    }

    void check(String op) throws IOException {
        if (unsupported.contains(op)) {
            throw new UnsupportedOperationException("mock unsupported: " + op);
        }
        if (failing.contains(op)) {
            throw new IOException("mock failure: " + op);
        }
    }

    /** Mock store delegating to the real store. */
    final class Store implements MailboxStore {
        final MailboxStore delegate;

        Store(MailboxStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void open(String username) throws IOException {
            if (failStoreOpen) {
                throw new IOException("mock failure: store open");
            }
            delegate.open(username);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
            storeCloses++;
            if (failStoreClose) {
                throw new IOException("mock failure: store close");
            }
        }

        @Override
        public char getHierarchyDelimiter() {
            return delegate.getHierarchyDelimiter();
        }

        @Override
        public List<String> listMailboxes(String reference, String pattern)
                throws IOException {
            check("listMailboxes");
            return delegate.listMailboxes(reference, pattern);
        }

        @Override
        public List<String> listSubscribed(String reference, String pattern)
                throws IOException {
            check("listSubscribed");
            return delegate.listSubscribed(reference, pattern);
        }

        @Override
        public void subscribe(String mailboxName) throws IOException {
            check("subscribe");
            delegate.subscribe(mailboxName);
        }

        @Override
        public void unsubscribe(String mailboxName) throws IOException {
            check("unsubscribe");
            delegate.unsubscribe(mailboxName);
        }

        @Override
        public Mailbox openMailbox(String mailboxName, boolean readOnly)
                throws IOException {
            check("openMailbox");
            Mailbox inner = delegate.openMailbox(mailboxName, readOnly);
            lastBox = new Box(inner);
            return lastBox;
        }

        @Override
        public void createMailbox(String mailboxName) throws IOException {
            check("createMailbox");
            delegate.createMailbox(mailboxName);
        }

        @Override
        public void deleteMailbox(String mailboxName) throws IOException {
            check("deleteMailbox");
            delegate.deleteMailbox(mailboxName);
        }

        @Override
        public void renameMailbox(String oldName, String newName)
                throws IOException {
            check("renameMailbox");
            delegate.renameMailbox(oldName, newName);
        }

        @Override
        public Set<MailboxAttribute> getMailboxAttributes(String mailboxName)
                throws IOException {
            check("getMailboxAttributes");
            return delegate.getMailboxAttributes(mailboxName);
        }

        @Override
        public Map<Integer, Long> copyMessages(Mailbox source,
                List<Integer> messageNumbers, String destinationMailbox)
                throws IOException {
            check("copyMessages");
            return delegate.copyMessages(unwrap(source), messageNumbers,
                    destinationMailbox);
        }

        @Override
        public Map<Integer, Long> moveMessages(Mailbox source,
                List<Integer> messageNumbers, String destinationMailbox)
                throws IOException {
            check("moveMessages");
            return delegate.moveMessages(unwrap(source), messageNumbers,
                    destinationMailbox);
        }

        private Mailbox unwrap(Mailbox source) {
            if (source instanceof Box) {
                return ((Box) source).delegate;
            }
            return source;
        }
    }

    /** Mock mailbox delegating to the real mailbox. */
    final class Box implements Mailbox {
        final Mailbox delegate;

        Box(Mailbox delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close(boolean expunge) throws IOException {
            delegate.close(expunge);
            mailboxCloses++;
            if (failMailboxClose) {
                throw new IOException("mock failure: mailbox close");
            }
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public boolean isReadOnly() {
            return delegate.isReadOnly();
        }

        @Override
        public int getMessageCount() throws IOException {
            check("getMessageCount");
            if (uidsOverride != null) {
                return uidsOverride.size();
            }
            return delegate.getMessageCount();
        }

        @Override
        public long getMailboxSize() throws IOException {
            check("getMailboxSize");
            return delegate.getMailboxSize();
        }

        @Override
        public Iterator<MessageDescriptor> getMessageList()
                throws IOException {
            check("getMessageList");
            return delegate.getMessageList();
        }

        @Override
        public MessageDescriptor getMessage(int messageNumber)
                throws IOException {
            check("getMessage");
            MessageDescriptor desc = descriptors.get(Integer.valueOf(messageNumber));
            if (desc != null) {
                return desc;
            }
            return delegate.getMessage(messageNumber);
        }

        @Override
        public AsyncMessageContent openAsyncContent(int messageNumber)
                throws IOException {
            switch (asyncMode) {
                case NONE:
                    return null;
                case THROW:
                    throw new IOException("mock failure: openAsyncContent");
                case NO_BODY_OFFSET:
                    return scripted(messageNumber, false, 0);
                case READ_ZERO:
                    return scripted(messageNumber, true, 1);
                case READ_FAIL:
                    return scripted(messageNumber, true, 2);
                default:
                    return delegate.openAsyncContent(messageNumber);
            }
        }

        private ScriptedContent scripted(int messageNumber,
                boolean withOffset, int mode) throws IOException {
            byte[] data = loadAll(messageNumber);
            long offset = -1;
            if (withOffset) {
                offset = BufferedAsyncMessageContent.detectBodyOffset(data);
            }
            return new ScriptedContent(data, offset, mode);
        }

        private byte[] loadAll(int messageNumber) throws IOException {
            ReadableByteChannel ch = delegate.getMessageContent(messageNumber);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(4096);
            while (ch.read(buf) >= 0) {
                buf.flip();
                out.write(buf.array(), 0, buf.limit());
                buf.clear();
            }
            ch.close();
            return out.toByteArray();
        }

        @Override
        public AsyncMessageWriter openAsyncAppend(Set<Flag> flags,
                OffsetDateTime internalDate) throws IOException {
            check("openAsyncAppend");
            if (appendMode == AppendMode.NULL_WRITER) {
                return null;
            }
            if (appendMode == AppendMode.DELEGATE) {
                return delegate.openAsyncAppend(flags, internalDate);
            }
            return new ScriptedWriter(delegate, flags, internalDate,
                    appendMode);
        }

        @Override
        public Path getMessagePath(int messageNumber) throws IOException {
            return delegate.getMessagePath(messageNumber);
        }

        @Override
        public ReadableByteChannel getMessageContent(int messageNumber)
                throws IOException {
            check("getMessageContent");
            return delegate.getMessageContent(messageNumber);
        }

        @Override
        public long getMessageTopEndOffset(int messageNumber, int bodyLines)
                throws IOException {
            return delegate.getMessageTopEndOffset(messageNumber, bodyLines);
        }

        @Override
        public ReadableByteChannel getMessageTop(int messageNumber,
                int bodyLines) throws IOException {
            return delegate.getMessageTop(messageNumber, bodyLines);
        }

        @Override
        public Set<Flag> getFlags(int messageNumber) throws IOException {
            check("getFlags");
            return delegate.getFlags(messageNumber);
        }

        @Override
        public void setFlags(int messageNumber, Set<Flag> flags, boolean add)
                throws IOException {
            check("setFlags");
            delegate.setFlags(messageNumber, flags, add);
        }

        @Override
        public void replaceFlags(int messageNumber, Set<Flag> flags)
                throws IOException {
            check("replaceFlags");
            delegate.replaceFlags(messageNumber, flags);
        }

        @Override
        public Set<Flag> getPermanentFlags() {
            return delegate.getPermanentFlags();
        }

        @Override
        public void deleteMessage(int messageNumber) throws IOException {
            delegate.deleteMessage(messageNumber);
        }

        @Override
        public boolean isDeleted(int messageNumber) throws IOException {
            return delegate.isDeleted(messageNumber);
        }

        @Override
        public void undeleteAll() throws IOException {
            delegate.undeleteAll();
        }

        @Override
        public List<Integer> expunge() throws IOException {
            check("expunge");
            return delegate.expunge();
        }

        @Override
        public List<Integer> expungeMessages(List<Integer> messageNumbers)
                throws IOException {
            check("expungeMessages");
            return delegate.expungeMessages(messageNumbers);
        }

        @Override
        public String getMailboxId() throws IOException {
            if (mailboxId != null) {
                return mailboxId;
            }
            return delegate.getMailboxId();
        }

        @Override
        public String getUniqueId(int messageNumber) throws IOException {
            check("getUniqueId");
            if (uidsOverride != null) {
                return uidsOverride.get(messageNumber - 1);
            }
            return delegate.getUniqueId(messageNumber);
        }

        @Override
        public String getEmailId(int messageNumber) throws IOException {
            if (emailId != null) {
                return emailId;
            }
            return delegate.getEmailId(messageNumber);
        }

        @Override
        public long getUidValidity() throws IOException {
            if (uidValidity != null) {
                return uidValidity.longValue();
            }
            return delegate.getUidValidity();
        }

        @Override
        public long getUidNext() throws IOException {
            return delegate.getUidNext();
        }

        @Override
        public long getHighestModSeq() throws IOException {
            if (highestModSeq > 0) {
                return highestModSeq;
            }
            return delegate.getHighestModSeq();
        }

        @Override
        public long getModSeq(int messageNumber) throws IOException {
            if (modSeq > 0) {
                return modSeq;
            }
            return delegate.getModSeq(messageNumber);
        }

        @Override
        public List<Long> getChangedSince(long since) throws IOException {
            if (!changedSince.isEmpty()) {
                return changedSince;
            }
            return delegate.getChangedSince(since);
        }

        @Override
        public List<Long> getExpungedSince(long since) throws IOException {
            if (!expungedSince.isEmpty()) {
                return expungedSince;
            }
            return delegate.getExpungedSince(since);
        }

        @Override
        public void startAppendMessage(Set<Flag> flags,
                OffsetDateTime internalDate) throws IOException {
            check("startAppendMessage");
            delegate.startAppendMessage(flags, internalDate);
        }

        @Override
        public void appendMessageContent(ByteBuffer data)
                throws IOException {
            check("appendMessageContent");
            delegate.appendMessageContent(data);
        }

        @Override
        public long endAppendMessage() throws IOException {
            check("endAppendMessage");
            return delegate.endAppendMessage();
        }

        @Override
        public MessageContext getMessageContext(int messageNumber)
                throws IOException {
            if (nullContexts.contains(Integer.valueOf(messageNumber))) {
                return null;
            }
            return delegate.getMessageContext(messageNumber);
        }

        @Override
        public List<Integer> search(SearchCriteria criteria)
                throws IOException {
            check("search");
            return delegate.search(criteria);
        }
    }

    /**
     * Async content over a byte array whose body offset and read behaviour
     * are scripted. Mode 0 reads normally, 1 completes reads with zero
     * bytes, 2 fails reads.
     */
    static final class ScriptedContent implements AsyncMessageContent {
        private final byte[] data;
        private final long bodyOffset;
        private final int mode;
        boolean closed;

        ScriptedContent(byte[] data, long bodyOffset, int mode) {
            this.data = data;
            this.mode = mode;
            this.bodyOffset = bodyOffset;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public long bodyOffset() {
            return bodyOffset;
        }

        @Override
        public void read(ByteBuffer dst, long position,
                CompletionHandler<Integer, ByteBuffer> handler) {
            if (mode == 1) {
                handler.completed(Integer.valueOf(0), dst);
                return;
            }
            if (mode == 2) {
                handler.failed(new IOException("mock read failure"), dst);
                return;
            }
            int n = (int) Math.min(dst.remaining(), data.length - position);
            if (n <= 0) {
                handler.completed(Integer.valueOf(-1), dst);
                return;
            }
            dst.put(data, (int) position, n);
            handler.completed(Integer.valueOf(n), dst);
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }

    /**
     * In-memory async writer that buffers the message and, on finish,
     * appends it to the real mailbox. Writes or the finish can be scripted
     * to fail.
     */
    static final class ScriptedWriter implements AsyncMessageWriter {
        private final Mailbox target;
        private final Set<Flag> flags;
        private final OffsetDateTime date;
        private final AppendMode mode;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean aborted;

        ScriptedWriter(Mailbox target, Set<Flag> flags, OffsetDateTime date,
                AppendMode mode) {
            this.target = target;
            this.flags = flags;
            this.date = date;
            this.mode = mode;
        }

        @Override
        public void write(ByteBuffer src,
                CompletionHandler<Integer, ByteBuffer> handler) {
            if (mode == AppendMode.WRITE_FAIL) {
                handler.failed(new IOException("mock write failure"), src);
                return;
            }
            int n = src.remaining();
            byte[] tmp = new byte[n];
            src.get(tmp);
            buffer.write(tmp, 0, n);
            handler.completed(Integer.valueOf(n), src);
        }

        @Override
        public boolean wantsPause() {
            return false;
        }

        @Override
        public void finish(CompletionHandler<Long, Void> handler) {
            if (mode == AppendMode.FINISH_FAIL) {
                handler.failed(new IOException("mock finish failure"), null);
                return;
            }
            if (mode == AppendMode.FINISH_THROW) {
                throw new IllegalStateException("mock finish exception");
            }
            try {
                target.startAppendMessage(flags, date);
                target.appendMessageContent(
                        ByteBuffer.wrap(buffer.toByteArray()));
                long uid = target.endAppendMessage();
                handler.completed(Long.valueOf(uid), null);
            } catch (IOException e) {
                handler.failed(e, null);
            }
        }

        @Override
        public void abort() {
            aborted = true;
        }

        @Override
        public void close() throws IOException {
            aborted = true;
        }
    }
}
