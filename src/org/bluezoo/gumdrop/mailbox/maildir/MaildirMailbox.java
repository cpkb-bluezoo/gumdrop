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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    /** Pending append data */
    private ByteArrayOutputStream appendBuffer;
    private OffsetDateTime appendDate;
    private Set<Flag> appendFlags;
    private Set<String> appendKeywords;

    /** Search index for fast message searching */
    private MessageIndex searchIndex;

    /** Builder for creating index entries */
    private final MessageIndexBuilder indexBuilder;

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

        // Scan cur/ directory
        File curDir = curPath.toFile();
        File[] files = curDir.listFiles();
        if (files == null) {
            return;
        }

        List<MaildirMessageDescriptor> scanned = new ArrayList<>();
        
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            
            String filename = file.getName();
            
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
                    file.toPath(),
                    parsed
                );
                scanned.add(descriptor);
            } catch (IllegalArgumentException e) {
                LOGGER.log(Level.WARNING, "Skipping invalid Maildir file: " + filename, e);
            }
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

        File newDir = newPath.toFile();
        File[] files = newDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (!file.isFile() || file.getName().startsWith(".")) {
                continue;
            }

            String filename = file.getName();
            
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
                Files.move(file.toPath(), destPath, StandardCopyOption.ATOMIC_MOVE);
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error processing new message: " + filename, e);
            }
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
    public ReadableByteChannel getMessageContent(int messageNumber) throws IOException {
        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            throw new IOException("Message not found: " + messageNumber);
        }
        
        FileInputStream fis = new FileInputStream(msg.getFilePath().toFile());
        return fis.getChannel();
    }

    @Override
    public ReadableByteChannel getMessageTop(int messageNumber, int bodyLines) throws IOException {
        MaildirMessageDescriptor msg = (MaildirMessageDescriptor) getMessage(messageNumber);
        if (msg == null) {
            throw new IOException("Message not found: " + messageNumber);
        }

        // Read message and extract headers + body lines
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        FileInputStream fis = new FileInputStream(msg.getFilePath().toFile());
        try {
            byte[] buffer = new byte[8192];
            boolean inHeaders = true;
            boolean prevCr = false;
            int lineCount = 0;
            int emptyLineCount = 0;

            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    byte b = buffer[i];
                    result.write(b);

                    if (b == LF) {
                        if (inHeaders) {
                            // Check if this is the blank line ending headers
                            if (prevCr || (i > 0 && buffer[i - 1] == LF)) {
                                inHeaders = false;
                            }
                        } else {
                            lineCount++;
                            if (lineCount >= bodyLines) {
                                fis.close();
                                return Channels.newChannel(
                                    new java.io.ByteArrayInputStream(result.toByteArray()));
                            }
                        }
                    }
                    prevCr = (b == CR);
                }
            }
        } finally {
            fis.close();
        }

        return Channels.newChannel(new java.io.ByteArrayInputStream(result.toByteArray()));
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
        List<Long> expungedUids = new ArrayList<>();

        for (int i = messages.size() - 1; i >= 0; i--) {
            MaildirMessageDescriptor msg = messages.get(i);
            if (deletedMessages.contains(msg.getUid())) {
                // Delete the file
                Files.deleteIfExists(msg.getFilePath());
                
                // Remove from UID list
                uidList.removeUid(msg.getBaseFilename());
                
                // Record expunged message number and UID
                expunged.add(msg.getMessageNumber());
                expungedUids.add(msg.getUid());
                
                // Remove from lists
                messages.remove(i);
                uidToMessage.remove(msg.getUid());
            }
        }

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
        if (searchIndex != null && !expungedUids.isEmpty()) {
            for (Long uid : expungedUids) {
                searchIndex.removeEntry(uid);
            }
            searchIndex.compact();
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
        if (appendBuffer != null) {
            throw new IllegalStateException("Append already in progress");
        }
        
        appendBuffer = new ByteArrayOutputStream();
        appendFlags = flags != null ? EnumSet.copyOf(flags) : EnumSet.noneOf(Flag.class);
        appendDate = internalDate;
        appendKeywords = new HashSet<>();
    }

    @Override
    public void appendMessageContent(ByteBuffer data) throws IOException {
        if (appendBuffer == null) {
            throw new IllegalStateException("No append in progress");
        }
        
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        appendBuffer.write(bytes);
    }

    @Override
    public long endAppendMessage() throws IOException {
        if (appendBuffer == null) {
            throw new IllegalStateException("No append in progress");
        }

        Set<Flag> flagsToUse = appendFlags;
        OffsetDateTime dateToUse = appendDate;
        
        try {
            byte[] content = appendBuffer.toByteArray();
            long size = content.length;

            // Convert keywords to indices
            Set<Integer> keywordIndices = keywords.keywordsToIndices(appendKeywords);

            // Generate unique filename
            MaildirFilename filename = MaildirFilename.generate(size, flagsToUse, keywordIndices);

            // Write to tmp/ first
            Path tmpFile = tmpPath.resolve(filename.toString());
            FileOutputStream fos = new FileOutputStream(tmpFile.toFile());
            try {
                fos.write(content);
            } finally {
                fos.close();
            }

            // Move to cur/ (atomic)
            Path curFile = curPath.resolve(filename.toString());
            Files.move(tmpFile, curFile, StandardCopyOption.ATOMIC_MOVE);

            // Assign UID
            long uid = uidList.assignUid(filename.getBaseFilename());

            // Add to message list
            int msgNum = messages.size() + 1;
            MaildirMessageDescriptor descriptor = new MaildirMessageDescriptor(
                msgNum, uid, curFile, filename);
            messages.add(descriptor);
            uidToMessage.put(uid, descriptor);

            // Save UID list
            uidList.save();

            // Add to search index
            if (searchIndex != null) {
                addMessageToSearchIndex(descriptor, flagsToUse, dateToUse);
            }

            return uid;

        } finally {
            appendBuffer = null;
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
                context = new IndexedMessageContext(indexEntry);
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

