/*
 * MaildirMessageDescriptor.java
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
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * Message descriptor for a Maildir message file.
 * 
 * <p>Each message in a Maildir is stored as a separate file. The message
 * metadata (flags, size, timestamp) is encoded in the filename.
 *
 * <p>The header/body boundary offset ({@link #getBodyOffset()}) is
 * precomputed at mailbox scan or append time so async content readers
 * can return it without blocking I/O.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirMessageDescriptor implements MessageDescriptor {

    /**
     * Sentinel for {@link #bodyOffset} when the header/body boundary has
     * not yet been scanned. Distinct from {@code -1}, which means the
     * boundary was scanned but not found.
     */
    public static final long UNKNOWN_BODY_OFFSET = -2L;

    private final int messageNumber;
    private final long uid;
    private final Path filePath;
    private final MaildirFilename parsedFilename;
    private final long actualSize;

    /**
     * Byte offset where the message body begins (after the blank line
     * separating headers from body). {@link #UNKNOWN_BODY_OFFSET} if not
     * yet scanned; {@code -1} if scanned and not found; {@code >= 0} when
     * known.
     */
    private final long bodyOffset;

    /**
     * Creates a message descriptor with an unresolved body offset.
     *
     * @param messageNumber the sequence number (1-based)
     * @param uid the unique identifier
     * @param filePath the path to the message file
     * @param parsedFilename the parsed filename
     */
    public MaildirMessageDescriptor(int messageNumber, long uid, Path filePath, 
            MaildirFilename parsedFilename) {
        this(messageNumber, uid, filePath, parsedFilename, UNKNOWN_BODY_OFFSET);
    }

    /**
     * Creates a message descriptor with a known or unknown body offset.
     *
     * @param messageNumber the sequence number (1-based)
     * @param uid the unique identifier
     * @param filePath the path to the message file
     * @param parsedFilename the parsed filename
     * @param bodyOffset body start offset, {@link #UNKNOWN_BODY_OFFSET},
     *                   or {@code -1} if no boundary was found
     */
    public MaildirMessageDescriptor(int messageNumber, long uid, Path filePath,
            MaildirFilename parsedFilename, long bodyOffset) {
        this.messageNumber = messageNumber;
        this.uid = uid;
        this.filePath = filePath;
        this.parsedFilename = parsedFilename;
        this.bodyOffset = bodyOffset;
        
        // Use actual file size if filename doesn't have it
        long filenameSize = parsedFilename.getSize();
        if (filenameSize >= 0) {
            this.actualSize = filenameSize;
        } else {
            File file = filePath.toFile();
            this.actualSize = file.exists() ? file.length() : 0;
        }
    }

    @Override
    public int getMessageNumber() {
        return messageNumber;
    }

    @Override
    public long getSize() {
        return actualSize;
    }

    /**
     * Returns the unique identifier for this message.
     *
     * @return the UID
     */
    public long getUid() {
        return uid;
    }

    /**
     * Returns the path to the message file.
     *
     * @return the file path
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * Returns the current filename.
     *
     * @return the filename
     */
    public String getFilename() {
        return filePath.getFileName().toString();
    }

    /**
     * Returns the parsed filename.
     *
     * @return the parsed filename
     */
    public MaildirFilename getParsedFilename() {
        return parsedFilename;
    }

    /**
     * Returns the base filename (without flags).
     *
     * @return the base filename
     */
    public String getBaseFilename() {
        return parsedFilename.getBaseFilename();
    }

    /**
     * Returns the message flags.
     *
     * @return set of flags
     */
    public Set<Flag> getFlags() {
        return parsedFilename.getFlags();
    }

    /**
     * Returns the keyword indices.
     *
     * @return set of keyword indices
     */
    public Set<Integer> getKeywordIndices() {
        return parsedFilename.getKeywordIndices();
    }

    /**
     * Returns the internal date (delivery timestamp).
     *
     * @return the internal date
     */
    public OffsetDateTime getInternalDate() {
        long timestamp = parsedFilename.getTimestamp();
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC);
    }

    /**
     * Returns whether the message is in the 'new' directory.
     *
     * @return true if new (unseen by client)
     */
    public boolean isNew() {
        Path parent = filePath.getParent();
        if (parent != null) {
            return "new".equals(parent.getFileName().toString());
        }
        return false;
    }

    /**
     * Returns the cached byte offset where the message body begins.
     *
     * <p>Values:
     * <ul>
     *   <li>{@code >= 0} — body starts at this offset</li>
     *   <li>{@code -1} — scanned; no blank-line separator found</li>
     *   <li>{@link #UNKNOWN_BODY_OFFSET} — not yet scanned</li>
     * </ul>
     *
     * @return the body offset sentinel or resolved offset
     */
    public long getBodyOffset() {
        return bodyOffset;
    }

    /**
     * Returns whether {@link #getBodyOffset()} has been resolved by a scan
     * (found or not found), as opposed to still being unknown.
     *
     * @return true if the offset has been computed
     */
    public boolean hasResolvedBodyOffset() {
        return bodyOffset != UNKNOWN_BODY_OFFSET;
    }

    /**
     * Returns a copy of this descriptor with a resolved body offset.
     *
     * @param resolvedBodyOffset the scanned offset ({@code >= 0} or {@code -1})
     * @return a new descriptor with the same identity and the given offset
     */
    public MaildirMessageDescriptor withBodyOffset(long resolvedBodyOffset) {
        if (this.bodyOffset == resolvedBodyOffset) {
            return this;
        }
        return new MaildirMessageDescriptor(messageNumber, uid, filePath,
                parsedFilename, resolvedBodyOffset);
    }

    /**
     * Returns a copy of this descriptor with a new sequence number,
     * preserving the cached body offset.
     *
     * @param newMessageNumber the new 1-based sequence number
     * @return a new descriptor with the updated message number
     */
    public MaildirMessageDescriptor withMessageNumber(int newMessageNumber) {
        if (this.messageNumber == newMessageNumber) {
            return this;
        }
        return new MaildirMessageDescriptor(newMessageNumber, uid, filePath,
                parsedFilename, bodyOffset);
    }

    /**
     * Returns a copy of this descriptor with an updated path and filename
     * (e.g. after a flag rename), preserving the cached body offset.
     *
     * @param newFilePath the new file path
     * @param newParsedFilename the new parsed filename
     * @return a new descriptor with updated path metadata
     */
    public MaildirMessageDescriptor withPath(Path newFilePath,
            MaildirFilename newParsedFilename) {
        return new MaildirMessageDescriptor(messageNumber, uid, newFilePath,
                newParsedFilename, bodyOffset);
    }

    @Override
    public String getUniqueId() {
        return String.valueOf(uid);
    }

}
