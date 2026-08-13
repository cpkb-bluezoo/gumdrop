/*
 * MaildirMailbox.java
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

package org.bluezoo.gumdrop.mailbox.maildir;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.mailbox.AsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.AsyncMessageWriter;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MessageContext;
import org.bluezoo.gumdrop.mailbox.index.MailboxIndexKey;
import org.bluezoo.gumdrop.mailbox.index.MailboxIndexer;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.mailbox.ParsedMessageContext;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.mailbox.index.IndexedMessageContext;
import org.bluezoo.gumdrop.mailbox.index.MessageIndex;
import org.bluezoo.gumdrop.mailbox.index.MessageIndexBuilder;
import org.bluezoo.gumdrop.mailbox.index.MessageIndexEntry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.Channels;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maildir format mailbox implementation.
 * 
 * <p>A Maildir mailbox consists of three subdirectories:
 * <ul>
 *   <li>{@code tmp/} - temporary files during delivery</li>
 *   <li>{@code new/} - newly delivered messages not yet seen by client</li>
 *   <li>{@code cur/} - messages that have been accessed</li>
 * </ul>
 * 
 * <p>Each message is stored as a separate file. Message metadata (flags,
 * size, timestamp) is encoded in the filename, so flag changes only
 * require a rename operation, not modifying file contents.
 * 
 * <p>This implementation is safe for concurrent access - multiple clients
 * can read the same mailbox simultaneously.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://en.wikipedia.org/wiki/Maildir">Maildir on Wikipedia</a>
 */
public final class MaildirMailbox implements Mailbox {

    private static final Logger LOGGER = Logger.getLogger(MaildirMailbox.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mailbox.L10N");

    private static final byte LF = '\n';
    private static final byte CR = '\r';

    /** Comparator for sorting message descriptors by UID. */
    private static final Comparator<MaildirMessageDescriptor> UID_COMPARATOR =
        new Comparator<MaildirMessageDescriptor>() {
            @Override
            public int compare(MaildirMessageDescriptor a, MaildirMessageDescriptor b) {
                return Long.compare(a.getUid(), b.getUid());
            }
        };

    private final Path maildirPath;
    private final Path curPath;
    private final Path newPath;
    private final Path tmpPath;
    private final String name;
    private final boolean readOnly;

    private final MaildirUidList uidList;
    private final MaildirKeywords keywords;

    /** Indexed messages, sorted by UID */
    private List<MaildirMessageDescriptor> messages;
    
    /** Maps UID to message descriptor */
    private Map<Long, MaildirMessageDescriptor> uidToMessage;
    
    /** Messages marked for deletion (by UID) */
    private Set<Long> deletedMessages;

    /** Pending append data - FileChannel for direct write to temp file */
    private FileChannel appendChannel;
    private Path appendTempPath;
    private OffsetDateTime appendDate;
    private Set<Flag> appendFlags;
    private Set<String> appendKeywords;

    /** Search index for fast message searching */
    private MessageIndex searchIndex;

    /** Builder for creating index entries */
    private final MessageIndexBuilder indexBuilder;

    /** CONDSTORE: highest modification sequence */
    private long highestModSeq;

    /** CONDSTORE: per-UID modification sequence (uid -> modseq) */
    private Map<Long, Long> uidModSeq;

    /** CONDSTORE: true when modseq data needs to be persisted */
    private boolean modSeqDirty;

    /** QRESYNC: expunged UIDs with their last modseq (uid -> modseq) */
    private Map<Long, Long> expungedUids;

    /**
     * Per-canonical-path in-JVM gate so that a second {@code MaildirMailbox}
     * on the same directory in this process blocks and queues behind the
     * first rather than racing straight into {@link #scanMessages()} /
     * {@link MaildirUidList#save()} concurrently - unlike mbox, maildir has
     * no OS file lock protecting a session, so two same-process opens for
     * one mailbox (e.g. a live client SELECT racing an eager background
     * index-warming job, see issue #163) can otherwise corrupt or lose
     * writes to {@code .uidlist}. Mirrors {@code MboxMailbox}'s
     * {@code JVM_GATES}.
     */
    private static final Map<Path, JvmMailboxGate> JVM_GATES = new HashMap<>();

    private static final class JvmMailboxGate {
        final Semaphore permit = new Semaphore(1);
        int refCount;
    }

    private Path gatePath;
    private JvmMailboxGate gate;

    private static synchronized JvmMailboxGate acquireGateRef(Path canonicalPath) {
        JvmMailboxGate g = JVM_GATES.get(canonicalPath);
        if (g == null) {
            g = new JvmMailboxGate();
            JVM_GATES.put(canonicalPath, g);
        }
        g.refCount++;
        return g;
    }

    private static synchronized void releaseGateRef(Path canonicalPath, JvmMailboxGate g) {
        g.refCount--;
        if (g.refCount == 0) {
            JVM_GATES.remove(canonicalPath, g);
        }
    }

    /**
     * Opens a Maildir mailbox.
     *
     * @param maildirPath the path to the Maildir directory
     * @param name the mailbox name
     * @param readOnly true for read-only access
     * @throws IOException if the mailbox cannot be opened
     */
    public MaildirMailbox(Path maildirPath, String name, boolean readOnly) throws IOException {
        this.maildirPath = maildirPath;
        this.curPath = maildirPath.resolve("cur");
        this.newPath = maildirPath.resolve("new");
        this.tmpPath = maildirPath.resolve("tmp");
        this.name = name;
        this.readOnly = readOnly;
        this.uidList = new MaildirUidList(maildirPath);
        this.keywords = new MaildirKeywords(maildirPath);
        this.messages = new ArrayList<>();
        this.uidToMessage = new HashMap<>();
        this.deletedMessages = new HashSet<>();
        this.indexBuilder = new MessageIndexBuilder();

        // Ensure directories exist
        if (!readOnly) {
            Files.createDirectories(curPath);
            Files.createDirectories(newPath);
            Files.createDirectories(tmpPath);
        }

        // Block/queue behind any other same-JVM session on this maildir
        // instead of racing into concurrent scans/uidlist writes below.
        gatePath = maildirPath.toRealPath();
        gate = acquireGateRef(gatePath);
        Gumdrop gumdrop = Gumdrop.getInstance();
        MailboxIndexer indexer = (gumdrop != null) ? gumdrop.getMailboxIndexer() : null;
        if (indexer != null && indexer.isCurrentThread()) {
            // Running on the single MailboxIndexer worker thread (a
            // background warming job): never block here. A concurrent
            // live opener already holding this gate may itself be
            // waiting on THIS thread via ensureFreshBlocking() to finish
            // its own index rebuild - blocking would deadlock. Warming an
            // already-in-use mailbox is redundant anyway, so just skip.
            if (!gate.permit.tryAcquire()) {
                releaseGateRef(gatePath, gate);
                gate = null;
                throw new IOException("Mailbox busy, skipping background warm: " + maildirPath);
            }
        } else {
            try {
                gate.permit.acquire();
            } catch (InterruptedException e) {
                releaseGateRef(gatePath, gate);
                gate = null;
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for mailbox: " + maildirPath, e);
            }
        }

        boolean initialized = false;
        try {
            // Load UID list and keywords
            uidList.load();
            keywords.load();

            // Scan and index messages
            scanMessages();

            // Load MODSEQ data (CONDSTORE/QRESYNC)
            this.uidModSeq = new HashMap<>();
            this.expungedUids = new HashMap<>();
            loadModSeqData();
            loadExpungedData();

            // Load or build search index
            loadOrBuildSearchIndex();
            initialized = true;
        } finally {
            if (!initialized) {
                gate.permit.release();
                releaseGateRef(gatePath, gate);
                gate = null;
            }
        }
    }

    /**
     * Scans the maildir for messages and builds the index.
     */
    private void scanMessages() throws IOException {
        messages.clear();
        uidToMessage.clear();

        // First, move any messages from new/ to cur/ (marking as seen by client)
        moveNewToCur();

        // Scan cur/ directory using lazy iteration
        List<MaildirMessageDescriptor> scanned = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(curPath)) {
            for (Path filePath : stream) {
                if (!Files.isRegularFile(filePath)) {
                    continue;
                }

                String filename = filePath.getFileName().toString();
            
            // Skip hidden files
            if (filename.startsWith(".")) {
                continue;
            }

            try {
                MaildirFilename parsed = new MaildirFilename(filename);
                String baseFilename = parsed.getBaseFilename();
                
                // Get or assign UID
                long uid = uidList.getUid(baseFilename);
                if (uid < 0) {
                    uid = uidList.assignUid(baseFilename);
                }

                // Body offset is left unresolved here (UNKNOWN_BODY_OFFSET)
                // rather than eagerly scanned: scanning every file in cur/
                // means an open/list/UIDL-only client (never fetching a
                // body) still pays a FileChannel.open + up to 8KB read per
                // message. ensureBodyOffset() resolves and caches it lazily
                // on first actual content access instead.
                MaildirMessageDescriptor descriptor = new MaildirMessageDescriptor(
                    0, // Message number assigned later
                    uid,
                    filePath,
                    parsed
                );
                scanned.add(descriptor);
            } catch (IllegalArgumentException e) {
                LOGGER.log(Level.WARNING, "Skipping invalid Maildir file: " + filename, e);
            }
        }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error scanning cur directory", e);
            return;
        }

        // Sort by UID and assign message numbers
        Collections.sort(scanned, UID_COMPARATOR);
        
        int msgNum = 1;
        for (MaildirMessageDescriptor desc : scanned) {
            MaildirMessageDescriptor numbered =
                    desc.withMessageNumber(msgNum++);
            messages.add(numbered);
            uidToMessage.put(numbered.getUid(), numbered);
        }

        // Save UID list if new UIDs were assigned
        if (uidList.isDirty() && !readOnly) {
            uidList.save();
        }
    }

    /**
     * Moves messages from new/ to cur/.
     * Per Maildir spec, messages move from new to cur when seen by client.
     */
    private void moveNewToCur() throws IOException {
        if (readOnly) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(newPath)) {
            for (Path filePath : stream) {
                if (!Files.isRegularFile(filePath) || filePath.getFileName().toString().startsWith(".")) {
                    continue;
                }

                String filename = filePath.getFileName().toString();
            
            // Parse and ensure has :2, info section
            try {
                MaildirFilename parsed;
                if (filename.contains(":2,")) {
                    parsed = new MaildirFilename(filename);
                } else {
                    // New message without flags section - add it
                    // Find the base part before any existing info
                    String base = filename;
                    int colonIdx = filename.indexOf(':');
                    if (colonIdx > 0) {
                        base = filename.substring(0, colonIdx);
                    }
                    
                    // Create with empty flags
                    int dotIdx = base.indexOf('.');
                    if (dotIdx > 0) {
                        long timestamp = Long.parseLong(base.substring(0, dotIdx));
                        String unique = base.substring(dotIdx + 1);
                        
                        // Check for size
                        long size = -1;
                        int sizeIdx = unique.indexOf(",S=");
                        if (sizeIdx > 0) {
                            size = Long.parseLong(unique.substring(sizeIdx + 3));
                            unique = unique.substring(0, sizeIdx);
                        }
                        
                        parsed = new MaildirFilename(timestamp, unique, size, 
                            EnumSet.noneOf(Flag.class), null);
                    } else {
                        continue; // Invalid filename
                    }
                }

                // Move to cur/
                String newFilename = parsed.toString();
                Path destPath = curPath.resolve(newFilename);
                Files.move(filePath, destPath, StandardCopyOption.ATOMIC_MOVE);

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error processing new message: " + filename, e);
            }
        }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error listing new directory", e);
        }
    }

    @Override
    public void close(boolean expunge) throws IOException {
        try {
            if (expunge) {
                doExpunge();
            } else {
                // Clear deletion marks
                deletedMessages.clear();
            }

            // Save metadata
            if (!readOnly) {
                if (uidList.isDirty()) {
                    uidList.save();
                }
                if (keywords.isDirty()) {
                    keywords.save();
                }
                if (modSeqDirty) {
                    saveModSeqData();
                    modSeqDirty = false;
                }

                // Save search index if modified
                if (searchIndex != null && searchIndex.isDirty()) {
                    try {
                        searchIndex.save();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to save search index", e);
                    }
                }
            }

            searchIndex = null;
        } finally {
            if (gate != null) {
                gate.permit.release();
                releaseGateRef(gatePath, gate);
                gate = null;
            }
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public int getMessageCount() throws IOException {
        int count = 0;
        for (MaildirMessageDescriptor msg : messages) {
            if (!deletedMessages.contains(msg.getUid())) {
                count++;
            }
        }
        return count;
    }

    @Override
    public long getMailboxSize() throws IOException {
        long size = 0;
        for (MaildirMessageDescriptor msg : messages) {
            if (!deletedMessages.contains(msg.getUid())) {
                size += msg.getSize();
            }
        }
        return size;
    }

    @Override
    public Iterator<MessageDescriptor> getMessageList() throws IOException {
        List<MessageDescriptor> result = new ArrayList<>();
        for (MaildirMessageDescriptor msg : messages) {
            if (!deletedMessages.contains(msg.getUid())) {
                result.add(msg);
            }
        }
        return result.iterator();
    }

    @Override
    public MessageDescriptor getMessage(int messageNumber) throws IOException {
        if (messageNumber < 1 || messageNumber > messages.size()) {
            return null;
        }
        MaildirMessageDescriptor msg = messages.get(messageNumber - 1);
        if (deletedMessages.contains(msg.getUid())) {
            return null;
        }
        return msg;
    }

    @Override
    public Path getMessagePath(int messageNumber) throws IOException {
        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            throw new IOException("Message not found: " + messageNumber);
        }
        return msg.getFilePath();
    }

    @Override
    public ReadableByteChannel getMessageContent(int messageNumber) throws IOException {
        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            throw new IOException("Message not found: " + messageNumber);
        }
        
        return FileChannel.open(msg.getFilePath(), StandardOpenOption.READ);
    }

    @Override
    public long getMessageTopEndOffset(int messageNumber, int bodyLines)
            throws IOException {
        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            throw new IOException("Message not found: " + messageNumber);
        }
        try (FileChannel fc = FileChannel.open(msg.getFilePath(), StandardOpenOption.READ)) {
            return scanTopEnd(fc, bodyLines);
        }
    }

    @Override
    public ReadableByteChannel getMessageTop(int messageNumber, int bodyLines) throws IOException {
        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            throw new IOException("Message not found: " + messageNumber);
        }

        try (FileChannel fc = FileChannel.open(msg.getFilePath(), StandardOpenOption.READ)) {
            long endPos = scanTopEnd(fc, bodyLines);

            ByteBuffer result = ByteBuffer.allocate((int) endPos);
            fc.position(0);
            while (result.hasRemaining()) {
                if (fc.read(result) == -1) {
                    break;
                }
            }
            result.flip();
            return Channels.newChannel(
                    new ByteArrayInputStream(result.array(), 0, result.limit()));
        }
    }

    /**
     * Scans a message file to find the byte offset after the headers plus
     * the requested number of body lines. Returns the file size if the
     * message has fewer body lines than requested.
     */
    private static long scanTopEnd(FileChannel fc, int bodyLines) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8192);
        boolean inHeaders = true;
        boolean lastWasNewline = false;
        int lineCount = 0;
        long pos = 0;

        while (true) {
            buf.clear();
            int n = fc.read(buf);
            if (n == -1) {
                break;
            }
            buf.flip();
            for (int i = 0; i < n; i++) {
                byte b = buf.get(i);
                if (b == LF) {
                    if (inHeaders) {
                        if (lastWasNewline) {
                            inHeaders = false;
                        }
                        lastWasNewline = true;
                    } else {
                        lineCount++;
                        if (lineCount >= bodyLines) {
                            return pos + i + 1;
                        }
                    }
                } else if (b != CR) {
                    lastWasNewline = false;
                }
            }
            pos += n;
        }
        return pos;
    }

    @Override
    public Set<Flag> getFlags(int messageNumber) throws IOException {
        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            return EnumSet.noneOf(Flag.class);
        }
        return msg.getFlags();
    }

    @Override
    public void setFlags(int messageNumber, Set<Flag> flags, boolean add) throws IOException {
        if (readOnly) {
            throw new IOException("Mailbox is read-only");
        }

        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            return;
        }

        Set<Flag> currentFlags = msg.getFlags();
        Set<Flag> newFlags = EnumSet.copyOf(currentFlags);
        
        if (add) {
            newFlags.addAll(flags);
        } else {
            newFlags.removeAll(flags);
        }

        if (!newFlags.equals(currentFlags)) {
            renameWithFlags(msg, newFlags, msg.getKeywordIndices());
            incrementModSeq(msg.getUid());

            // Update search index
            if (searchIndex != null) {
                searchIndex.updateFlags(msg.getUid(), newFlags);
            }
        }
    }

    @Override
    public void replaceFlags(int messageNumber, Set<Flag> flags) throws IOException {
        if (readOnly) {
            throw new IOException("Mailbox is read-only");
        }

        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            return;
        }

        renameWithFlags(msg, flags, msg.getKeywordIndices());
        incrementModSeq(msg.getUid());

        // Update search index
        if (searchIndex != null) {
            searchIndex.updateFlags(msg.getUid(), flags);
        }
    }

    /**
     * Renames a message file to update its flags.
     */
    private void renameWithFlags(MaildirMessageDescriptor msg, Set<Flag> newFlags, 
            Set<Integer> keywordIndices) throws IOException {
        MaildirFilename newFilename = msg.getParsedFilename().withFlags(newFlags, keywordIndices);
        String newName = newFilename.toString();
        
        Path oldPath = msg.getFilePath();
        Path newPath = oldPath.getParent().resolve(newName);
        
        if (!oldPath.equals(newPath)) {
            Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
            
            // Update the message descriptor (preserve cached body offset)
            int idx = msg.getMessageNumber() - 1;
            MaildirMessageDescriptor updated =
                    msg.withPath(newPath, newFilename);
            messages.set(idx, updated);
            uidToMessage.put(updated.getUid(), updated);
        }
    }

    @Override
    public void deleteMessage(int messageNumber) throws IOException {
        // Mark in-memory only — no Files.move on the SelectorLoop (POP3 DELE).
        // The file is removed at close(true) / expunge. IMAP STORE \Deleted
        // still renames via setFlags when a client sets the flag explicitly.
        if (messageNumber < 1 || messageNumber > messages.size()) {
            return;
        }
        MaildirMessageDescriptor msg = messages.get(messageNumber - 1);
        deletedMessages.add(msg.getUid());
    }

    @Override
    public boolean isDeleted(int messageNumber) throws IOException {
        if (messageNumber < 1 || messageNumber > messages.size()) {
            return false;
        }
        return deletedMessages.contains(messages.get(messageNumber - 1).getUid());
    }

    @Override
    public void undeleteAll() throws IOException {
        // Deletion marks are in-memory until expunge; clearing is enough.
        deletedMessages.clear();
    }

    @Override
    public List<Integer> expunge() throws IOException {
        if (readOnly) {
            throw new IOException("Mailbox is read-only");
        }
        return doExpunge();
    }

    /**
     * Performs the actual expunge operation.
     */
    private List<Integer> doExpunge() throws IOException {
        List<Integer> expunged = new ArrayList<>();
        List<Long> removedUids = new ArrayList<>();
        List<MaildirMessageDescriptor> toKeep = new ArrayList<>();

        for (MaildirMessageDescriptor msg : messages) {
            if (deletedMessages.contains(msg.getUid())) {
                Files.deleteIfExists(msg.getFilePath());
                uidList.removeUid(msg.getBaseFilename());
                expunged.add(msg.getMessageNumber());
                removedUids.add(msg.getUid());
                uidToMessage.remove(msg.getUid());

                // Record for QRESYNC VANISHED
                long ms = uidModSeq.containsKey(msg.getUid())
                        ? uidModSeq.get(msg.getUid()).longValue()
                        : highestModSeq;
                expungedUids.put(msg.getUid(), ms);
                uidModSeq.remove(msg.getUid());
                modSeqDirty = true;
            } else {
                toKeep.add(msg);
            }
        }

        messages = toKeep;
        deletedMessages.clear();

        // Renumber remaining messages (preserve cached body offsets)
        for (int i = 0; i < messages.size(); i++) {
            MaildirMessageDescriptor old = messages.get(i);
            if (old.getMessageNumber() != i + 1) {
                MaildirMessageDescriptor renumbered =
                        old.withMessageNumber(i + 1);
                messages.set(i, renumbered);
                uidToMessage.put(renumbered.getUid(), renumbered);
            }
        }

        // Update search index - remove expunged entries and compact
        if (searchIndex != null && !removedUids.isEmpty()) {
            for (Long uid : removedUids) {
                searchIndex.removeEntry(uid);
            }
            searchIndex.compact();
        }

        // Persist expunged UIDs
        if (!removedUids.isEmpty()) {
            saveExpungedData();
        }

        // Sort expunged list in ascending order
        Collections.sort(expunged);

        return expunged;
    }

    @Override
    public String getUniqueId(int messageNumber) throws IOException {
        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            throw new IOException("Message not found: " + messageNumber);
        }
        return String.valueOf(msg.getUid());
    }

    @Override
    public long getUidValidity() throws IOException {
        return uidList.getUidValidity();
    }

    @Override
    public long getUidNext() throws IOException {
        return uidList.getUidNext();
    }

    @Override
    public void startAppendMessage(Set<Flag> flags, OffsetDateTime internalDate) throws IOException {
        if (readOnly) {
            throw new IOException("Mailbox is read-only");
        }
        if (appendChannel != null) {
            throw new IllegalStateException("Append already in progress");
        }

        appendTempPath = Files.createTempFile(tmpPath, "mail", ".tmp");
        appendChannel = FileChannel.open(appendTempPath,
            StandardOpenOption.WRITE);
        appendFlags = flags != null ? EnumSet.copyOf(flags) : EnumSet.noneOf(Flag.class);
        appendDate = internalDate;
        appendKeywords = new HashSet<>();
    }

    @Override
    public void appendMessageContent(ByteBuffer data) throws IOException {
        if (appendChannel == null) {
            throw new IllegalStateException("No append in progress");
        }

        while (data.hasRemaining()) {
            appendChannel.write(data);
        }
    }

    @Override
    public long endAppendMessage() throws IOException {
        if (appendChannel == null) {
            throw new IllegalStateException("No append in progress");
        }

        Set<Flag> flagsToUse = appendFlags;
        OffsetDateTime dateToUse = appendDate;

        try {
            appendChannel.close();
            appendChannel = null;

            long size = Files.size(appendTempPath);

            // Convert keywords to indices
            Set<Integer> keywordIndices = keywords.keywordsToIndices(appendKeywords);

            // Generate unique filename
            MaildirFilename filename = MaildirFilename.generate(size, flagsToUse, keywordIndices);

            // Move from tmp to cur/ (atomic)
            Path curFile = curPath.resolve(filename.toString());
            Files.move(appendTempPath, curFile, StandardCopyOption.ATOMIC_MOVE);
            appendTempPath = null;

            // Assign UID
            long uid = uidList.assignUid(filename.getBaseFilename());

            // Add to message list (body offset from the just-written file)
            int msgNum = messages.size() + 1;
            long bodyOffset = detectBodyOffset(curFile);
            MaildirMessageDescriptor descriptor = new MaildirMessageDescriptor(
                msgNum, uid, curFile, filename, bodyOffset);
            messages.add(descriptor);
            uidToMessage.put(uid, descriptor);

            // Assign MODSEQ
            incrementModSeq(uid);

            // Save UID list
            uidList.save();

            // Add to search index
            if (searchIndex != null) {
                addMessageToSearchIndex(descriptor, flagsToUse, dateToUse);
            }

            return uid;

        } finally {
            if (appendChannel != null) {
                try {
                    appendChannel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error closing append channel", e);
                }
                appendChannel = null;
            }
            if (appendTempPath != null) {
                try {
                    Files.deleteIfExists(appendTempPath);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error cleaning up temp file", e);
                }
                appendTempPath = null;
            }
            appendFlags = null;
            appendDate = null;
            appendKeywords = null;
        }
    }

    @Override
    public Set<Flag> getPermanentFlags() {
        return Flag.permanentFlags();
    }

    /**
     * Returns the path to this Maildir.
     *
     * @return the Maildir path
     */
    public Path getMaildirPath() {
        return maildirPath;
    }

    /**
     * Returns the keywords manager.
     *
     * @return the keywords manager
     */
    public MaildirKeywords getKeywords() {
        return keywords;
    }

    // ========================================================================
    // CONDSTORE / QRESYNC
    // ========================================================================

    @Override
    public long getHighestModSeq() throws IOException {
        return highestModSeq;
    }

    @Override
    public long getModSeq(int messageNumber) throws IOException {
        MaildirMessageDescriptor msg =
                (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            return 0;
        }
        Long ms = uidModSeq.get(msg.getUid());
        return ms != null ? ms.longValue() : 0;
    }

    @Override
    public List<Long> getChangedSince(long modSeq) throws IOException {
        List<Long> result = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : uidModSeq.entrySet()) {
            if (entry.getValue().longValue() > modSeq) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    @Override
    public List<Long> getExpungedSince(long modSeq) throws IOException {
        List<Long> result = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : expungedUids.entrySet()) {
            if (entry.getValue().longValue() > modSeq) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private void incrementModSeq(long uid) {
        highestModSeq++;
        uidModSeq.put(uid, highestModSeq);
        modSeqDirty = true;
    }

    /**
     * Loads MODSEQ data from the .modseq sidecar file.
     * Format: first line is "HIGHEST modseq", subsequent lines
     * are "uid modseq" pairs.
     */
    private void loadModSeqData() {
        Path modSeqPath = maildirPath.resolve(".modseq");
        if (!Files.exists(modSeqPath)) {
            highestModSeq = 0;
            return;
        }
        try {
            List<String> lines = Files.readAllLines(modSeqPath);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int space = line.indexOf(' ');
                if (space < 0) {
                    continue;
                }
                String key = line.substring(0, space);
                long value = Long.parseLong(line.substring(space + 1));
                if ("HIGHEST".equals(key)) {
                    highestModSeq = value;
                } else {
                    long uid = Long.parseLong(key);
                    uidModSeq.put(uid, value);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Failed to load .modseq file, starting fresh", e);
            highestModSeq = 0;
            uidModSeq.clear();
        }
    }

    /**
     * Saves MODSEQ data to the .modseq sidecar file.
     */
    private void saveModSeqData() {
        Path modSeqPath = maildirPath.resolve(".modseq");
        try {
            List<String> lines = new ArrayList<>();
            lines.add("HIGHEST " + highestModSeq);
            for (Map.Entry<Long, Long> entry : uidModSeq.entrySet()) {
                lines.add(entry.getKey() + " " + entry.getValue());
            }
            Files.write(modSeqPath, lines);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to save .modseq file", e);
        }
    }

    /**
     * Loads expunged UID data from the .expunged sidecar file.
     * Format: "uid modseq" per line.
     */
    private void loadExpungedData() {
        Path expungedPath = maildirPath.resolve(".expunged");
        if (!Files.exists(expungedPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(expungedPath);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int space = line.indexOf(' ');
                if (space < 0) {
                    continue;
                }
                long uid = Long.parseLong(line.substring(0, space));
                long ms = Long.parseLong(line.substring(space + 1));
                expungedUids.put(uid, ms);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Failed to load .expunged file", e);
            expungedUids.clear();
        }
    }

    /**
     * Saves expunged UID data to the .expunged sidecar file.
     */
    private void saveExpungedData() {
        Path expungedPath = maildirPath.resolve(".expunged");
        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<Long, Long> entry
                    : expungedUids.entrySet()) {
                lines.add(entry.getKey() + " " + entry.getValue());
            }
            Files.write(expungedPath, lines);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to save .expunged file", e);
        }
    }

    // ========================================================================
    // Async Message I/O
    // ========================================================================

    @Override
    public AsyncMessageContent openAsyncContent(int messageNumber)
            throws IOException {
        MaildirMessageDescriptor msg =
                (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            throw new IOException("Message not found: " + messageNumber);
        }
        // Resolve body offset on this StorageExecutor/open path if an
        // older descriptor never had it scanned — never by waiting on async-file I/O.
        msg = ensureBodyOffset(msg);
        AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                msg.getFilePath(), StandardOpenOption.READ);
        return new MaildirAsyncMessageContent(channel, msg.getSize(),
                msg.getBodyOffset());
    }

    /**
     * Ensures the descriptor has a resolved body offset, computing it with
     * a blocking {@link FileChannel} if needed and replacing the in-memory
     * descriptor so subsequent opens reuse the cache.
     */
    private MaildirMessageDescriptor ensureBodyOffset(
            MaildirMessageDescriptor msg) {
        if (msg.hasResolvedBodyOffset()) {
            return msg;
        }
        long bodyOffset = detectBodyOffset(msg.getFilePath());
        MaildirMessageDescriptor updated = msg.withBodyOffset(bodyOffset);
        int idx = msg.getMessageNumber() - 1;
        if (idx >= 0 && idx < messages.size()
                && messages.get(idx).getUid() == msg.getUid()) {
            messages.set(idx, updated);
            uidToMessage.put(updated.getUid(), updated);
        }
        return updated;
    }

    /**
     * Scans a message file for the blank-line header/body boundary
     * (CRLFCRLF or LFLF). Uses a blocking {@link FileChannel} — call only
     * from mailbox scan, append, or StorageExecutor open paths, never from
     * the SelectorLoop via blocking JDK async-file APIs.
     *
     * @param filePath the message file
     * @return body start offset, or {@code -1} if not found within the
     *         scan window
     */
    static long detectBodyOffset(Path filePath) {
        try (FileChannel fc = FileChannel.open(filePath,
                StandardOpenOption.READ)) {
            long size = fc.size();
            int scanLen = (int) Math.min(size, 8192L);
            if (scanLen <= 0) {
                return -1;
            }
            ByteBuffer buf = ByteBuffer.allocate(scanLen);
            while (buf.hasRemaining()) {
                if (fc.read(buf) == -1) {
                    break;
                }
            }
            buf.flip();
            boolean lastWasLF = false;
            for (int i = 0; i < buf.limit(); i++) {
                byte b = buf.get(i);
                if (b == LF) {
                    if (lastWasLF) {
                        return (long) i + 1;
                    }
                    lastWasLF = true;
                } else if (b != CR) {
                    lastWasLF = false;
                }
            }
            return -1;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Error detecting body offset for " + filePath, e);
            return -1;
        }
    }

    @Override
    public AsyncMessageWriter openAsyncAppend(Set<Flag> flags,
            OffsetDateTime internalDate) throws IOException {
        if (readOnly) {
            throw new IOException("Mailbox is read-only");
        }
        Path tempFile = Files.createTempFile(tmpPath, "mail", ".tmp");
        AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                tempFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE);
        Set<Flag> flagsCopy = flags != null
                ? EnumSet.copyOf(flags)
                : EnumSet.noneOf(Flag.class);
        return new MaildirAsyncMessageWriter(channel, tempFile,
                flagsCopy, internalDate);
    }

    /**
     * Async positional reader backed by an AsynchronousFileChannel.
     *
     * <p>The body offset is supplied from the message descriptor (scanned
     * at mailbox open/append time). {@link #bodyOffset()} is a pure
     * memory read — it never blocks on disk or waits on async-file completions.
     */
    private static final class MaildirAsyncMessageContent
            implements AsyncMessageContent {

        private final AsynchronousFileChannel channel;
        private final long contentSize;
        /** Resolved body offset ({@code >= 0} or {@code -1}). */
        private final long bodyOffset;

        MaildirAsyncMessageContent(AsynchronousFileChannel channel,
                long contentSize, long bodyOffset) {
            this.channel = channel;
            this.contentSize = contentSize;
            // Normalize unknown sentinel to API -1 (should already be resolved)
            this.bodyOffset = bodyOffset == MaildirMessageDescriptor.UNKNOWN_BODY_OFFSET
                    ? -1
                    : bodyOffset;
        }

        @Override
        public long size() {
            return contentSize;
        }

        @Override
        public long bodyOffset() {
            return bodyOffset;
        }

        @Override
        public void read(ByteBuffer dst, long position,
                CompletionHandler<Integer, ByteBuffer> handler) {
            channel.read(dst, position, dst, handler);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    /**
     * Async writer that streams to a temp file then finalizes into cur/.
     */
    private final class MaildirAsyncMessageWriter
            implements AsyncMessageWriter {

        private final AsynchronousFileChannel channel;
        private final Path tempFile;
        private final Set<Flag> flags;
        private final OffsetDateTime internalDate;
        private long writePosition;
        private boolean finished;

        MaildirAsyncMessageWriter(AsynchronousFileChannel channel,
                Path tempFile, Set<Flag> flags,
                OffsetDateTime internalDate) {
            this.channel = channel;
            this.tempFile = tempFile;
            this.flags = flags;
            this.internalDate = internalDate;
        }

        @Override
        public void write(ByteBuffer src,
                CompletionHandler<Integer, ByteBuffer> handler) {
            long pos = writePosition;
            channel.write(src, pos, src,
                    new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer result, ByteBuffer attachment) {
                    writePosition += result;
                    handler.completed(result, attachment);
                }

                @Override
                public void failed(Throwable exc, ByteBuffer attachment) {
                    handler.failed(exc, attachment);
                }
            });
        }

        @Override
        public boolean wantsPause() {
            return false;
        }

        @Override
        public void finish(CompletionHandler<Long, Void> handler) {
            if (finished) {
                handler.failed(
                        new IllegalStateException("Already finished"), null);
                return;
            }
            finished = true;
            try {
                channel.close();

                long size = Files.size(tempFile);
                Set<Integer> keywordIndices = Collections.emptySet();
                MaildirFilename filename =
                        MaildirFilename.generate(size, flags, keywordIndices);

                Path curFile = curPath.resolve(filename.toString());
                Files.move(tempFile, curFile, StandardCopyOption.ATOMIC_MOVE);

                long uid = uidList.assignUid(filename.getBaseFilename());

                int msgNum = messages.size() + 1;
                long bodyOffset = detectBodyOffset(curFile);
                MaildirMessageDescriptor descriptor =
                        new MaildirMessageDescriptor(
                                msgNum, uid, curFile, filename, bodyOffset);
                messages.add(descriptor);
                uidToMessage.put(uid, descriptor);
                uidList.save();

                if (searchIndex != null) {
                    addMessageToSearchIndex(descriptor, flags, internalDate);
                }

                handler.completed(uid, null);
            } catch (IOException e) {
                handler.failed(e, null);
            }
        }

        @Override
        public void abort() {
            finished = true;
            try {
                channel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing async append channel", e);
            }
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error cleaning up temp file", e);
            }
        }

        @Override
        public void close() throws IOException {
            if (!finished) {
                abort();
            }
        }
    }

    // ========================================================================
    // Search Index Methods
    // ========================================================================

    /**
     * Searches for messages matching the given criteria using the search index.
     * Falls back to parsing messages for TEXT/BODY searches.
     */
    @Override
    public List<Integer> search(SearchCriteria criteria) throws IOException {
        // If no search index, fall back to default implementation
        if (searchIndex == null) {
            return Mailbox.super.search(criteria);
        }

        List<Integer> results = new ArrayList<>();
        
        for (MaildirMessageDescriptor msg : messages) {
            // Skip deleted messages
            if (deletedMessages.contains(msg.getUid())) {
                continue;
            }
            
            // Try to use indexed context first
            MessageIndexEntry indexEntry = searchIndex.getEntryByUid(msg.getUid());
            MessageContext context;
            
            if (indexEntry != null) {
                IndexedMessageContext indexed =
                        new IndexedMessageContext(indexEntry);
                long uid = msg.getUid();
                Long ms = uidModSeq.get(uid);
                long modSeqVal = ms != null ? ms : 0;
                context = new MessageContext() {
                    @Override
                    public int getMessageNumber() {
                        return indexed.getMessageNumber();
                    }
                    @Override
                    public long getUID() {
                        return indexed.getUID();
                    }
                    @Override
                    public long getSize() {
                        return indexed.getSize();
                    }
                    @Override
                    public Set<Flag> getFlags() {
                        return indexed.getFlags();
                    }
                    @Override
                    public Set<String> getKeywords() {
                        return indexed.getKeywords();
                    }
                    @Override
                    public OffsetDateTime getInternalDate() {
                        return indexed.getInternalDate();
                    }
                    @Override
                    public String getHeader(String name)
                            throws IOException {
                        return indexed.getHeader(name);
                    }
                    @Override
                    public List<String> getHeaders(String name)
                            throws IOException {
                        return indexed.getHeaders(name);
                    }
                    @Override
                    public OffsetDateTime getSentDate()
                            throws IOException {
                        return indexed.getSentDate();
                    }
                    @Override
                    public CharSequence getHeadersText()
                            throws IOException {
                        return indexed.getHeadersText();
                    }
                    @Override
                    public CharSequence getBodyText()
                            throws IOException {
                        return indexed.getBodyText();
                    }
                    @Override
                    public long getModSeq() {
                        return modSeqVal;
                    }
                };
            } else {
                // Fall back to parsing if not in index
                context = new ParsedMessageContext(
                    this,
                    msg.getMessageNumber(),
                    msg.getUid(),
                    msg.getSize(),
                    getFlags(msg.getMessageNumber()),
                    null
                );
            }
            
            if (criteria.matches(context)) {
                results.add(msg.getMessageNumber());
            }
        }
        
        return results;
    }

    /**
     * Gets the path to the search index file.
     */
    private Path getSearchIndexPath() {
        return maildirPath.resolve(".gidx");
    }

    /**
     * Loads the search index from disk, or builds it if not present/corrupt.
     *
     * <p>The cheap incremental path (an existing, valid index that just
     * needs {@link #indexNewMessages()} to catch up a handful of new
     * messages) stays inline. A full {@link #rebuildSearchIndex()} -
     * parsing every message from scratch - is routed through the shared
     * {@link MailboxIndexer} instead of running inline, so it is
     * prioritized against other mailboxes' indexing work rather than
     * unconditionally blocking whichever client happened to trigger this
     * open (issue #163). This call still blocks until the rebuild
     * completes either way: a SELECT/APPEND/SEARCH must never proceed
     * against a stale or partial index.
     */
    private void loadOrBuildSearchIndex() {
        Path indexPath = getSearchIndexPath();

        // Try to load existing index
        if (Files.exists(indexPath)) {
            try {
                searchIndex = MessageIndex.load(indexPath);

                // Validate index is consistent with mailbox
                if (validateSearchIndex()) {
                    // Index any new messages that aren't in the index
                    indexNewMessages();
                    LOGGER.fine("Loaded search index for " + name);
                    return;
                } else {
                    LOGGER.info(L10N.getString("info.search_index_inconsistent"));
                }
            } catch (MessageIndex.CorruptIndexException e) {
                LOGGER.log(Level.WARNING, L10N.getString("warn.corrupt_search_index"), e);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("warn.search_index_load_failed"), e);
            }
        }

        // Build new index, via the shared background indexer when available.
        // If we're already running ON the indexer's own worker thread (a
        // background warming job opened this mailbox and its index also
        // turns out to need a rebuild), do it inline instead of submitting
        // a second job that thread would have to wait on itself to run.
        Gumdrop gumdrop = Gumdrop.getInstance();
        MailboxIndexer indexer = (gumdrop != null) ? gumdrop.getMailboxIndexer() : null;
        if (indexer == null || indexer.isCurrentThread()) {
            rebuildSearchIndex();
            return;
        }
        try {
            long lastModified;
            try {
                lastModified = Files.getLastModifiedTime(maildirPath).toMillis();
            } catch (IOException e) {
                lastModified = 0L;
            }
            indexer.ensureFreshBlocking(new MailboxIndexKey(indexPath),
                    "INBOX".equalsIgnoreCase(name), lastModified,
                    new MailboxIndexer.IndexWork() {
                        @Override
                        public void run() throws Exception {
                            rebuildSearchIndex();
                        }
                    });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rebuildSearchIndex();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Background index rebuild failed, rebuilding inline", e);
            rebuildSearchIndex();
        }
    }

    /**
     * Validates that the search index is consistent with the mailbox.
     */
    private boolean validateSearchIndex() {
        if (searchIndex == null) {
            return false;
        }
        
        // Check UID validity matches
        if (searchIndex.getUidValidity() != uidList.getUidValidity()) {
            return false;
        }
        
        // Check that entry count is reasonable
        int indexedCount = searchIndex.getEntryCount();
        int messageCount = messages.size();
        
        // Index should not have more entries than messages
        if (indexedCount > messageCount) {
            return false;
        }
        
        return true;
    }

    /**
     * Indexes any new messages not yet in the search index.
     */
    private void indexNewMessages() {
        if (searchIndex == null) {
            return;
        }
        
        for (MaildirMessageDescriptor msg : messages) {
            if (searchIndex.getEntryByUid(msg.getUid()) == null) {
                // Message not indexed, add it
                try {
                    addMessageToSearchIndex(msg, msg.getFlags(), null);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to index message " + msg.getMessageNumber(), e);
                }
            }
        }
    }

    /**
     * Rebuilds the search index from scratch.
     */
    private void rebuildSearchIndex() {
        Path indexPath = getSearchIndexPath();
        
        // Create new index with UID validity from the UID list
        long uidValidity = uidList.getUidValidity();
        long uidNext = uidList.getUidNext();
        
        searchIndex = new MessageIndex(indexPath, uidValidity, uidNext);
        
        // Index all messages
        for (MaildirMessageDescriptor msg : messages) {
            try {
                addMessageToSearchIndex(msg, msg.getFlags(), null);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("warn.index_message_failed"), msg.getMessageNumber()), e);
            }
        }

        LOGGER.info(MessageFormat.format(
                L10N.getString("info.search_index_built"), searchIndex.getEntryCount(), name));
    }

    /**
     * Adds a single message to the search index.
     */
    private void addMessageToSearchIndex(MaildirMessageDescriptor msg, 
            Set<Flag> flags, OffsetDateTime internalDate) throws IOException {
        if (searchIndex == null) {
            return;
        }
        
        // Location is the filename in cur/
        String location = msg.getFilePath().getFileName().toString();
        
        // Get internal date from filename timestamp, or use provided date
        long internalDateMillis = 0;
        if (internalDate != null) {
            internalDateMillis = internalDate.toInstant().toEpochMilli();
        } else {
            // Use timestamp from Maildir filename
            internalDateMillis = msg.getParsedFilename().getTimestamp() * 1000;
        }
        
        // Build index entry by parsing message headers
        try (ReadableByteChannel channel = getMessageContent(msg.getMessageNumber())) {
            MessageIndexEntry entry = indexBuilder.buildEntry(
                msg.getUid(),
                msg.getMessageNumber(),
                msg.getSize(),
                internalDateMillis,
                flags != null ? flags : EnumSet.noneOf(Flag.class),
                location,
                channel
            );
            searchIndex.addEntry(entry);
        }
    }

}

