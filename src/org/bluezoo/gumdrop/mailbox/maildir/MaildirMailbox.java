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

import org.bluezoo.gumdrop.mailbox.AsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.AsyncMessageWriter;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MessageContext;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.mailbox.ParsedMessageContext;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.mailbox.index.IndexedMessageContext;
import org.bluezoo.gumdrop.mailbox.index.MessageIndex;
import org.bluezoo.gumdrop.mailbox.index.MessageIndexBuilder;
import org.bluezoo.gumdrop.mailbox.index.MessageIndexEntry;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
public class MaildirMailbox implements Mailbox {

    private static final Logger LOGGER = Logger.getLogger(MaildirMailbox.class.getName());

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
            MaildirMessageDescriptor numbered = new MaildirMessageDescriptor(
                msgNum++,
                desc.getUid(),
                desc.getFilePath(),
                desc.getParsedFilename()
            );
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
                    new java.io.ByteArrayInputStream(result.array(), 0, result.limit()));
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
            
            // Update the message descriptor
            int idx = msg.getMessageNumber() - 1;
            MaildirMessageDescriptor updated = new MaildirMessageDescriptor(
                msg.getMessageNumber(),
                msg.getUid(),
                newPath,
                newFilename
            );
            messages.set(idx, updated);
            uidToMessage.put(updated.getUid(), updated);
        }
    }

    @Override
    public void deleteMessage(int messageNumber) throws IOException {
        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg != null) {
            deletedMessages.add(msg.getUid());
            
            // Also set the \Deleted flag in the filename
            Set<Flag> flags = msg.getFlags();
            if (!flags.contains(Flag.DELETED)) {
                flags = EnumSet.copyOf(flags);
                flags.add(Flag.DELETED);
                renameWithFlags(msg, flags, msg.getKeywordIndices());
            }
        }
    }

    @Override
    public boolean isDeleted(int messageNumber) throws IOException {
        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            return false;
        }
        return deletedMessages.contains(msg.getUid());
    }

    @Override
    public void undeleteAll() throws IOException {
        for (Long uid : deletedMessages) {
            MaildirMessageDescriptor msg = uidToMessage.get(uid);
            if (msg != null) {
                Set<Flag> flags = msg.getFlags();
                if (flags.contains(Flag.DELETED)) {
                    flags = EnumSet.copyOf(flags);
                    flags.remove(Flag.DELETED);
                    renameWithFlags(msg, flags, msg.getKeywordIndices());
                }
            }
        }
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

        // Renumber remaining messages
        for (int i = 0; i < messages.size(); i++) {
            MaildirMessageDescriptor old = messages.get(i);
            if (old.getMessageNumber() != i + 1) {
                MaildirMessageDescriptor renumbered = new MaildirMessageDescriptor(
                    i + 1,
                    old.getUid(),
                    old.getFilePath(),
                    old.getParsedFilename()
                );
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

            // Add to message list
            int msgNum = messages.size() + 1;
            MaildirMessageDescriptor descriptor = new MaildirMessageDescriptor(
                msgNum, uid, curFile, filename);
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
        AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                msg.getFilePath(), StandardOpenOption.READ);
        return new MaildirAsyncMessageContent(channel, msg.getSize());
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
     */
    private static final class MaildirAsyncMessageContent
            implements AsyncMessageContent {

        private final AsynchronousFileChannel channel;
        private final long contentSize;
        private long cachedBodyOffset = -2; // -2 = not yet scanned

        MaildirAsyncMessageContent(AsynchronousFileChannel channel,
                long contentSize) {
            this.channel = channel;
            this.contentSize = contentSize;
        }

        @Override
        public long size() {
            return contentSize;
        }

        @Override
        public long bodyOffset() {
            if (cachedBodyOffset != -2) {
                return cachedBodyOffset;
            }
            // Synchronously scan the first portion of the file for the
            // blank-line separator (CRLFCRLF or LFLF).
            int scanLen = (int) Math.min(contentSize, 8192L);
            ByteBuffer buf = ByteBuffer.allocate(scanLen);
            try {
                int totalRead = 0;
                while (totalRead < scanLen) {
                    java.util.concurrent.Future<Integer> f =
                            channel.read(buf, totalRead);
                    int n = f.get();
                    if (n == -1) {
                        break;
                    }
                    totalRead += n;
                }
                buf.flip();
                boolean lastWasLF = false;
                for (int i = 0; i < buf.limit(); i++) {
                    byte b = buf.get(i);
                    if (b == LF) {
                        if (lastWasLF) {
                            cachedBodyOffset = (long) i + 1;
                            return cachedBodyOffset;
                        }
                        lastWasLF = true;
                    } else if (b != CR) {
                        lastWasLF = false;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Interrupted scanning body offset", e);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error scanning body offset", e);
            }
            cachedBodyOffset = -1;
            return cachedBodyOffset;
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
                MaildirMessageDescriptor descriptor =
                        new MaildirMessageDescriptor(
                                msgNum, uid, curFile, filename);
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
                    LOGGER.info("Search index inconsistent with mailbox, rebuilding");
                }
            } catch (MessageIndex.CorruptIndexException e) {
                LOGGER.log(Level.WARNING, "Corrupt search index, rebuilding", e);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to load search index, rebuilding", e);
            }
        }
        
        // Build new index
        rebuildSearchIndex();
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
                LOGGER.log(Level.WARNING, "Failed to index message " + msg.getMessageNumber(), e);
            }
        }
        
        LOGGER.info("Built search index with " + searchIndex.getEntryCount() + 
            " entries for " + name);
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

