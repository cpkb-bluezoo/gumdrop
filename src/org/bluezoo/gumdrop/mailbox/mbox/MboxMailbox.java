/*
 * MboxMailbox.java
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

package org.bluezoo.gumdrop.mailbox.mbox;

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
import org.bluezoo.util.ByteArrays;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mbox-format mailbox implementation.
 * 
 * <p>This implementation handles the standard Unix mbox format as defined
 * in RFC 4155. An mbox file contains multiple messages separated by
 * "From " envelope lines.
 * 
 * <p><b>Mbox Format:</b>
 * <pre>
 * From sender@example.com Mon Jan  1 00:00:00 2025
 * [RFC 822 message headers]
 * 
 * [message body]
 * 
 * From another@example.com Tue Jan  2 00:00:00 2025
 * [next message...]
 * </pre>
 * 
 * <p><b>From_ Escaping:</b> Lines in the message body that begin with
 * "From " are escaped by prepending "&gt;" to become "&gt;From ". When
 * reading, this escaping is reversed.
 * 
 * <p>This implementation uses file locking to prevent concurrent access.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4155">RFC 4155 - The application/mbox Media Type</a>
 */
public class MboxMailbox implements Mailbox {

    private static final Logger LOGGER = Logger.getLogger(MboxMailbox.class.getName());

    /** The "From " line prefix that starts each message */
    private static final byte[] FROM_PREFIX = "From ".getBytes(StandardCharsets.US_ASCII);
    
    /** The escaped From prefix in message bodies */
    private static final byte[] ESCAPED_FROM_PREFIX = ">From ".getBytes(StandardCharsets.US_ASCII);
    
    /** LF line terminator (mbox files often use LF only) */
    private static final byte LF = '\n';
    
    /** CR character */
    private static final byte CR = '\r';

    /** Date format for mbox From_ lines: "Mon Jan  1 00:00:00 2025" */
    private static final DateTimeFormatter MBOX_DATE_FORMAT = 
        DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US);

    private final Path mboxFile;
    private final String name;
    private final boolean readOnly;
    
    private RandomAccessFile raf;
    private FileChannel channel;
    private FileLock lock;
    
    /** Indexed message descriptors with file offsets */
    private List<MboxMessageDescriptor> messages;
    
    /** Messages marked for deletion */
    private Set<Integer> deletedMessages;
    
    /** Pending append data */
    private ByteArrayOutputStream appendBuffer;
    private OffsetDateTime appendDate;
    private Set<Flag> appendFlags;

    /** Search index for fast message searching */
    private MessageIndex searchIndex;

    /** Builder for creating index entries */
    private final MessageIndexBuilder indexBuilder;

    /**
     * Creates and opens an mbox mailbox.
     * 
     * @param mboxFile the mbox file path
     * @param name the mailbox name
     * @param readOnly true to open in read-only mode
     * @throws IOException if the mailbox cannot be opened
     */
    public MboxMailbox(Path mboxFile, String name, boolean readOnly) throws IOException {
        this.mboxFile = mboxFile;
        this.name = name;
        this.readOnly = readOnly;
        this.deletedMessages = new HashSet<>();
        this.messages = new ArrayList<>();
        this.indexBuilder = new MessageIndexBuilder();
        
        // Create file if it doesn't exist
        if (!Files.exists(mboxFile)) {
            if (readOnly) {
                throw new IOException("Mailbox file does not exist: " + mboxFile);
            }
            Files.createFile(mboxFile);
        }
        
        // Open the file
        String mode = readOnly ? "r" : "rw";
        raf = new RandomAccessFile(mboxFile.toFile(), mode);
        channel = raf.getChannel();
        
        // Acquire lock
        lock = readOnly ? channel.lock(0, Long.MAX_VALUE, true) : channel.lock();
        if (lock == null) {
            raf.close();
            throw new IOException("Could not acquire lock on mailbox: " + mboxFile);
        }
        
        // Index the messages
        indexMessages();
        
        // Load or build search index
        loadOrBuildSearchIndex();
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
    public void close(boolean expunge) throws IOException {
        try {
            if (expunge && !readOnly && !deletedMessages.isEmpty()) {
                expungeDeletedMessages();
            }
            
            // Save search index if modified
            if (searchIndex != null && searchIndex.isDirty() && !readOnly) {
                try {
                    searchIndex.save();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to save search index", e);
                }
            }
        } finally {
            deletedMessages.clear();
            messages.clear();
            searchIndex = null;
            
            if (lock != null) {
                lock.release();
                lock = null;
            }
            if (channel != null) {
                channel.close();
                channel = null;
            }
            if (raf != null) {
                raf.close();
                raf = null;
            }
        }
    }

    @Override
    public int getMessageCount() throws IOException {
        int count = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (!deletedMessages.contains(i + 1)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public long getMailboxSize() throws IOException {
        long size = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (!deletedMessages.contains(i + 1)) {
                size += messages.get(i).getSize();
            }
        }
        return size;
    }

    @Override
    public Iterator<MessageDescriptor> getMessageList() throws IOException {
        List<MessageDescriptor> visible = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!deletedMessages.contains(i + 1)) {
                visible.add(messages.get(i));
            }
        }
        return visible.iterator();
    }

    @Override
    public MessageDescriptor getMessage(int messageNumber) throws IOException {
        if (messageNumber < 1 || messageNumber > messages.size()) {
            return null;
        }
        if (deletedMessages.contains(messageNumber)) {
            return null;
        }
        return messages.get(messageNumber - 1);
    }

    @Override
    public ReadableByteChannel getMessageContent(int messageNumber) throws IOException {
        if (messageNumber < 1 || messageNumber > messages.size()) {
            throw new IOException("Invalid message number: " + messageNumber);
        }
        if (deletedMessages.contains(messageNumber)) {
            throw new IOException("Message is deleted: " + messageNumber);
        }
        
        MboxMessageDescriptor msg = messages.get(messageNumber - 1);
        byte[] content = readMessageContent(msg.getStartOffset(), msg.getEndOffset());
        
        // Unescape ">From " lines
        content = unescapeFromLines(content);
        
        ByteBuffer buffer = ByteBuffer.wrap(content);
        return new ByteBufferChannel(buffer);
    }

    @Override
    public ReadableByteChannel getMessageTop(int messageNumber, int bodyLines) throws IOException {
        if (messageNumber < 1 || messageNumber > messages.size()) {
            throw new IOException("Invalid message number: " + messageNumber);
        }
        if (deletedMessages.contains(messageNumber)) {
            throw new IOException("Message is deleted: " + messageNumber);
        }
        
        MboxMessageDescriptor msg = messages.get(messageNumber - 1);
        byte[] content = readMessageContent(msg.getStartOffset(), msg.getEndOffset());
        content = unescapeFromLines(content);
        
        // Find the end of headers (blank line)
        int headerEnd = findBlankLine(content);
        if (headerEnd < 0) {
            headerEnd = content.length;
        }
        
        // Include headers + blank line
        int resultEnd = headerEnd;
        
        // Add requested body lines
        if (bodyLines > 0 && headerEnd < content.length) {
            int pos = headerEnd;
            int linesFound = 0;
            while (pos < content.length && linesFound < bodyLines) {
                // Find next line ending
                int lineEnd = pos;
                while (lineEnd < content.length && content[lineEnd] != LF) {
                    lineEnd++;
                }
                if (lineEnd < content.length) {
                    lineEnd++; // Include LF
                }
                pos = lineEnd;
                linesFound++;
            }
            resultEnd = pos;
        }
        
        byte[] result = new byte[resultEnd];
        System.arraycopy(content, 0, result, 0, resultEnd);
        
        ByteBuffer buffer = ByteBuffer.wrap(result);
        return new ByteBufferChannel(buffer);
    }

    @Override
    public void deleteMessage(int messageNumber) throws IOException {
        if (readOnly) {
            throw new IOException("Mailbox is read-only");
        }
        if (messageNumber < 1 || messageNumber > messages.size()) {
            throw new IOException("Invalid message number: " + messageNumber);
        }
        deletedMessages.add(messageNumber);
    }

    @Override
    public boolean isDeleted(int messageNumber) throws IOException {
        return deletedMessages.contains(messageNumber);
    }

    @Override
    public void undeleteAll() throws IOException {
        deletedMessages.clear();
    }

    @Override
    public List<Integer> expunge() throws IOException {
        if (readOnly) {
            throw new IOException("Mailbox is read-only");
        }
        
        List<Integer> expunged = new ArrayList<>(deletedMessages);
        Collections.sort(expunged);
        
        if (!expunged.isEmpty()) {
            expungeDeletedMessages();
        }
        
        return expunged;
    }

    @Override
    public String getUniqueId(int messageNumber) throws IOException {
        if (messageNumber < 1 || messageNumber > messages.size()) {
            throw new IOException("Invalid message number: " + messageNumber);
        }
        
        MboxMessageDescriptor msg = messages.get(messageNumber - 1);
        
        // Check if we already have a computed hash-based ID
        String existingId = msg.getUniqueId();
        if (existingId != null && existingId.length() == 32) {
            // Looks like an MD5 hash, use it
            return existingId;
        }
        
        // Compute MD5 hash of message content for unique ID
        byte[] content = readMessageContent(msg.getStartOffset(), msg.getEndOffset());
        
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(content);
            return ByteArrays.toHexString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // Fallback to offset-based ID
            return existingId;
        }
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
        appendFlags = flags;
        appendDate = (internalDate != null) ? internalDate : OffsetDateTime.now(ZoneOffset.UTC);
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
            byte[] messageContent = appendBuffer.toByteArray();
            
            // Escape "From " lines in the body
            messageContent = escapeFromLines(messageContent);
            
            // Build the From_ envelope line
            // Format: "From sender@localhost Mon Jan  1 00:00:00 2025\n"
            String fromLine = "From MAILER-DAEMON@localhost " + 
                dateToUse.format(MBOX_DATE_FORMAT) + "\n";
            
            // Position at end of file
            channel.position(channel.size());
            
            // If file is not empty, ensure there's a blank line before new message
            if (channel.size() > 0) {
                channel.write(ByteBuffer.wrap(new byte[]{LF}));
            }
            
            // Write From_ line
            channel.write(ByteBuffer.wrap(fromLine.getBytes(StandardCharsets.US_ASCII)));
            
            // Write message content
            channel.write(ByteBuffer.wrap(messageContent));
            
            // Ensure message ends with newline
            if (messageContent.length == 0 || messageContent[messageContent.length - 1] != LF) {
                channel.write(ByteBuffer.wrap(new byte[]{LF}));
            }
            
            // Re-index to pick up new message
            indexMessages();
            
            // Add to search index
            long uid = messages.size();
            if (searchIndex != null) {
                MboxMessageDescriptor newMsg = messages.get(messages.size() - 1);
                addMessageToSearchIndex(newMsg, uid, flagsToUse, dateToUse);
            }
            
            // Return the new message's UID (using message number as UID for simplicity)
            return uid;
            
        } finally {
            appendBuffer = null;
            appendFlags = null;
            appendDate = null;
        }
    }

    // ========================================================================
    // Private Implementation
    // ========================================================================

    /**
     * Indexes all messages in the mbox file.
     */
    private void indexMessages() throws IOException {
        messages.clear();
        
        long fileSize = channel.size();
        if (fileSize == 0) {
            return;
        }
        
        // First pass: scan entire file to find all "From " boundaries
        // We collect just the offsets here, then add messages after
        List<Long> fromOffsets = new ArrayList<>();
        
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long filePos = 0;
        boolean atLineStart = true;
        int matchPos = 0;
        
        while (channel.read(buffer) > 0) {
            buffer.flip();
            
            while (buffer.hasRemaining()) {
                byte b = buffer.get();
                long currentPos = filePos++;
                
                if (atLineStart) {
                    if (matchPos < FROM_PREFIX.length && b == FROM_PREFIX[matchPos]) {
                        matchPos++;
                        if (matchPos == FROM_PREFIX.length) {
                            // Found "From " at line start - this is a message boundary
                            long messageStart = currentPos - FROM_PREFIX.length + 1;
                            fromOffsets.add(messageStart);
                            matchPos = 0;
                            atLineStart = false;
                        }
                    } else {
                        matchPos = 0;
                        atLineStart = false;
                    }
                }
                
                if (b == LF) {
                    atLineStart = true;
                    matchPos = 0;
                }
            }
            
            buffer.clear();
        }
        
        // Second pass: add messages based on found boundaries
        for (int i = 0; i < fromOffsets.size(); i++) {
            long start = fromOffsets.get(i);
            long end = (i + 1 < fromOffsets.size()) ? fromOffsets.get(i + 1) : fileSize;
            addMessage(start, end);
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Indexed " + messages.size() + " messages in " + mboxFile);
        }
    }

    /**
     * Adds a message to the index.
     */
    private void addMessage(long start, long end) throws IOException {
        // Validate range
        if (start >= end) {
            return; // Invalid range, skip this message
        }
        
        // Skip the From_ line to find where the actual RFC822 message starts
        channel.position(start);
        ByteBuffer lineBuffer = ByteBuffer.allocate(1024);
        int bytesRead = channel.read(lineBuffer);
        if (bytesRead <= 0) {
            return; // Nothing to read
        }
        lineBuffer.flip();
        
        long rfc822Start = start;
        while (lineBuffer.hasRemaining()) {
            byte b = lineBuffer.get();
            rfc822Start++;
            if (b == LF) {
                break;
            }
        }
        
        // Trim trailing whitespace from end
        long rfc822End = end;
        if (rfc822End > rfc822Start) {
            channel.position(rfc822End - 1);
            ByteBuffer endBuf = ByteBuffer.allocate(1);
            if (channel.read(endBuf) > 0) {
                endBuf.flip();
                if (endBuf.hasRemaining() && endBuf.get() == LF) {
                    rfc822End--;
                    if (rfc822End > rfc822Start) {
                        channel.position(rfc822End - 1);
                        endBuf.clear();
                        if (channel.read(endBuf) > 0) {
                            endBuf.flip();
                            if (endBuf.hasRemaining() && endBuf.get() == CR) {
                                rfc822End--;
                            }
                        }
                    }
                }
            }
        }
        
        // Final validation - ensure we have actual content
        if (rfc822End <= rfc822Start) {
            return; // Empty message, skip
        }
        
        int msgNum = messages.size() + 1;
        
        // Use file position as initial unique ID (content hash computed on demand)
        String uniqueId = String.valueOf(start);
        
        messages.add(new MboxMessageDescriptor(msgNum, rfc822Start, rfc822End, uniqueId));
    }

    /**
     * Reads message content from the file.
     */
    private byte[] readMessageContent(long start, long end) throws IOException {
        int length = (int) (end - start);
        if (length <= 0) {
            return new byte[0];
        }
        
        byte[] content = new byte[length];
        channel.position(start);
        ByteBuffer buffer = ByteBuffer.wrap(content);
        
        int totalRead = 0;
        while (totalRead < length) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            totalRead += read;
        }
        
        return content;
    }

    /**
     * Unescapes ">From " lines back to "From " lines in message body.
     */
    private byte[] unescapeFromLines(byte[] content) {
        // Find where headers end
        int headerEnd = findBlankLine(content);
        if (headerEnd < 0 || headerEnd >= content.length) {
            return content; // No body to unescape
        }
        
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        
        // Copy headers as-is
        out.write(content, 0, headerEnd);
        
        // Process body, unescaping ">From " at line starts
        boolean atLineStart = true;
        for (int i = headerEnd; i < content.length; i++) {
            if (atLineStart && i + ESCAPED_FROM_PREFIX.length <= content.length) {
                boolean match = true;
                for (int j = 0; j < ESCAPED_FROM_PREFIX.length; j++) {
                    if (content[i + j] != ESCAPED_FROM_PREFIX[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    // Skip the ">" and write "From "
                    i++; // Skip ">"
                    out.write(content, i, FROM_PREFIX.length);
                    i += FROM_PREFIX.length - 1; // -1 because loop will increment
                    atLineStart = false;
                    continue;
                }
            }
            
            out.write(content[i]);
            atLineStart = (content[i] == LF);
        }
        
        return out.toByteArray();
    }

    /**
     * Escapes "From " lines to ">From " in message body.
     */
    private byte[] escapeFromLines(byte[] content) {
        // Find where headers end
        int headerEnd = findBlankLine(content);
        if (headerEnd < 0 || headerEnd >= content.length) {
            return content; // No body to escape
        }
        
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 100);
        
        // Copy headers as-is
        out.write(content, 0, headerEnd);
        
        // Process body, escaping "From " at line starts
        boolean atLineStart = true;
        for (int i = headerEnd; i < content.length; i++) {
            if (atLineStart && i + FROM_PREFIX.length <= content.length) {
                boolean match = true;
                for (int j = 0; j < FROM_PREFIX.length; j++) {
                    if (content[i + j] != FROM_PREFIX[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    // Write ">From " instead of "From "
                    out.write(ESCAPED_FROM_PREFIX, 0, ESCAPED_FROM_PREFIX.length);
                    i += FROM_PREFIX.length - 1; // -1 because loop will increment
                    atLineStart = false;
                    continue;
                }
            }
            
            out.write(content[i]);
            atLineStart = (content[i] == LF);
        }
        
        return out.toByteArray();
    }

    /**
     * Finds the position after the blank line separating headers from body.
     * Returns -1 if not found.
     */
    private int findBlankLine(byte[] content) {
        for (int i = 0; i < content.length - 1; i++) {
            if (content[i] == LF) {
                // Check for \n\n (LF LF) or \r\n\r\n
                if (i + 1 < content.length && content[i + 1] == LF) {
                    return i + 2;
                }
                if (i + 1 < content.length && content[i + 1] == CR && 
                    i + 2 < content.length && content[i + 2] == LF) {
                    return i + 3;
                }
            }
        }
        return -1;
    }

    /**
     * Expunges deleted messages by rewriting the mbox file.
     */
    private void expungeDeletedMessages() throws IOException {
        if (deletedMessages.isEmpty()) {
            return;
        }
        
        // Create temporary file
        Path tempFile = Files.createTempFile(mboxFile.getParent(), "mbox", ".tmp");
        
        try (FileChannel tempChannel = FileChannel.open(tempFile, 
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            
            // Copy non-deleted messages
            for (int i = 0; i < messages.size(); i++) {
                int msgNum = i + 1;
                if (!deletedMessages.contains(msgNum)) {
                    MboxMessageDescriptor msg = messages.get(i);
                    
                    // Reconstruct the From_ line
                    String fromLine = "From MAILER-DAEMON@localhost " + 
                        OffsetDateTime.now(ZoneOffset.UTC).format(MBOX_DATE_FORMAT) + "\n";
                    tempChannel.write(ByteBuffer.wrap(fromLine.getBytes(StandardCharsets.US_ASCII)));
                    
                    // Copy message content
                    byte[] content = readMessageContent(msg.getStartOffset(), msg.getEndOffset());
                    tempChannel.write(ByteBuffer.wrap(content));
                    tempChannel.write(ByteBuffer.wrap(new byte[]{LF}));
                }
            }
        }
        
        // Release lock temporarily for file replacement
        lock.release();
        
        // Replace original file
        Files.move(tempFile, mboxFile, StandardCopyOption.REPLACE_EXISTING);
        
        // Re-acquire lock
        lock = channel.lock();
        
        // Clear deleted set and re-index
        deletedMessages.clear();
        indexMessages();
        
        // Rebuild search index after expunge
        rebuildSearchIndex();
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

        // Check if criteria requires body/text parsing
        // For now, use index for all searches - TEXT/BODY will return empty
        // matches since body is not indexed. A more sophisticated approach
        // would detect TEXT/BODY criteria and fall back to parsing.
        List<Integer> results = new ArrayList<>();
        
        for (int i = 0; i < messages.size(); i++) {
            int msgNum = i + 1;
            
            // Skip deleted messages
            if (deletedMessages.contains(msgNum)) {
                continue;
            }
            
            MboxMessageDescriptor msg = messages.get(i);
            long uid = getMessageUid(msgNum);
            
            // Try to use indexed context first
            MessageIndexEntry indexEntry = searchIndex.getEntryByUid(uid);
            MessageContext context;
            
            if (indexEntry != null) {
                context = new IndexedMessageContext(indexEntry);
            } else {
                // Fall back to parsing if not in index
                context = new ParsedMessageContext(
                    this,
                    msgNum,
                    uid,
                    msg.getSize(),
                    getFlags(msgNum),
                    null
                );
            }
            
            if (criteria.matches(context)) {
                results.add(msgNum);
            }
        }
        
        return results;
    }

    /**
     * Gets the path to the search index file.
     */
    private Path getSearchIndexPath() {
        String indexName = mboxFile.getFileName().toString() + ".gidx";
        return mboxFile.resolveSibling(indexName);
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
        
        // For mbox, UID validity is based on file identity
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
        
        for (int i = 0; i < messages.size(); i++) {
            MboxMessageDescriptor msg = messages.get(i);
            long uid = i + 1; // UID is message number for mbox
            
            if (searchIndex.getEntryByUid(uid) == null) {
                // Message not indexed, add it
                try {
                    addMessageToSearchIndex(msg, uid, EnumSet.noneOf(Flag.class), null);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to index message " + (i + 1), e);
                }
            }
        }
    }

    /**
     * Rebuilds the search index from scratch.
     */
    private void rebuildSearchIndex() {
        Path indexPath = getSearchIndexPath();
        
        // Create new index with UID validity based on file modification time
        long uidValidity = System.currentTimeMillis() / 1000;
        long uidNext = messages.size() + 1;
        
        searchIndex = new MessageIndex(indexPath, uidValidity, uidNext);
        
        // Index all messages
        for (int i = 0; i < messages.size(); i++) {
            MboxMessageDescriptor msg = messages.get(i);
            long uid = i + 1;
            
            try {
                addMessageToSearchIndex(msg, uid, EnumSet.noneOf(Flag.class), null);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to index message " + (i + 1), e);
            }
        }
        
        LOGGER.info("Built search index with " + searchIndex.getEntryCount() + 
            " entries for " + name);
    }

    /**
     * Adds a single message to the search index.
     */
    private void addMessageToSearchIndex(MboxMessageDescriptor msg, long uid,
            Set<Flag> flags, OffsetDateTime internalDate) throws IOException {
        if (searchIndex == null) {
            return;
        }
        
        // Location is the offset range in the mbox file
        String location = msg.getStartOffset() + ":" + msg.getEndOffset();
        
        // Get internal date millis
        long internalDateMillis = 0;
        if (internalDate != null) {
            internalDateMillis = internalDate.toInstant().toEpochMilli();
        }
        
        // Build index entry by parsing message headers
        try (ReadableByteChannel channel = getMessageContent(msg.getMessageNumber())) {
            MessageIndexEntry entry = indexBuilder.buildEntry(
                uid,
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

    /**
     * Gets the UID for a message number.
     * For mbox, UID is simply the message number.
     */
    private long getMessageUid(int messageNumber) {
        return messageNumber;
    }

    /**
     * Simple ReadableByteChannel backed by a ByteBuffer.
     */
    private static class ByteBufferChannel implements ReadableByteChannel {
        private final ByteBuffer buffer;
        private boolean open = true;

        ByteBufferChannel(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!open) {
                throw new IOException("Channel is closed");
            }
            if (!buffer.hasRemaining()) {
                return -1;
            }
            int count = Math.min(dst.remaining(), buffer.remaining());
            for (int i = 0; i < count; i++) {
                dst.put(buffer.get());
            }
            return count;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
        }
    }
}
