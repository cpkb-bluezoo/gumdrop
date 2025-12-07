/*
 * MessageIndexEntry.java
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

package org.bluezoo.gumdrop.mailbox.index;

import org.bluezoo.gumdrop.mailbox.Flag;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

/**
 * A single entry in the message index containing all searchable metadata
 * for one message.
 * 
 * <p>The entry uses a property descriptor format where fixed-size descriptors
 * (offset + length) appear first, followed by variable-length data. This allows
 * fast field access without parsing the entire entry.
 * 
 * <p>All string values are stored in lowercase for case-insensitive searching.
 * The original values can always be retrieved from the message itself.
 * 
 * <h2>Binary Format</h2>
 * <pre>
 * FIXED HEADER (48 bytes):
 *   uid: 8 bytes
 *   messageNumber: 4 bytes
 *   size: 8 bytes
 *   internalDate: 8 bytes (millis since epoch)
 *   sentDate: 8 bytes (millis since epoch)
 *   flags: 1 byte (bit flags)
 *   reserved: 3 bytes
 *   descriptorCount: 4 bytes
 *   variableDataSize: 4 bytes
 * 
 * PROPERTY DESCRIPTORS (N x 8 bytes):
 *   Each: offset(4) + length(4) relative to variable data start
 *   Order: location, from, to, cc, bcc, subject, messageId, keywords
 * 
 * VARIABLE DATA:
 *   String data in order matching descriptors
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIndexEntry {

    /** Number of property descriptors in each entry. */
    public static final int DESCRIPTOR_COUNT = 8;

    /** Size of the fixed header in bytes. */
    public static final int FIXED_HEADER_SIZE = 48;

    /** Size of each property descriptor in bytes. */
    public static final int DESCRIPTOR_SIZE = 8;

    // Descriptor indices
    public static final int DESC_LOCATION = 0;
    public static final int DESC_FROM = 1;
    public static final int DESC_TO = 2;
    public static final int DESC_CC = 3;
    public static final int DESC_BCC = 4;
    public static final int DESC_SUBJECT = 5;
    public static final int DESC_MESSAGE_ID = 6;
    public static final int DESC_KEYWORDS = 7;

    // Flag bit positions
    private static final int FLAG_BIT_SEEN = 0;
    private static final int FLAG_BIT_ANSWERED = 1;
    private static final int FLAG_BIT_FLAGGED = 2;
    private static final int FLAG_BIT_DELETED = 3;
    private static final int FLAG_BIT_DRAFT = 4;
    private static final int FLAG_BIT_RECENT = 5;

    // Fixed fields
    private long uid;
    private int messageNumber;
    private long size;
    private long internalDate;
    private long sentDate;
    private byte flagsByte;

    // Property descriptors: [offset, length] pairs
    private final int[] descriptors;

    // Variable data storage
    private byte[] variableData;

    /**
     * Creates a new empty index entry.
     */
    public MessageIndexEntry() {
        this.descriptors = new int[DESCRIPTOR_COUNT * 2];
        this.variableData = new byte[0];
    }

    /**
     * Creates a new index entry with the specified values.
     *
     * @param uid the message UID
     * @param messageNumber the message sequence number
     * @param size the message size in bytes
     * @param internalDate the internal date as millis since epoch
     * @param sentDate the sent date as millis since epoch
     * @param flags the message flags
     * @param location the message location (filename or offset)
     * @param from the From header value (lowercase)
     * @param to the To header value (lowercase)
     * @param cc the Cc header value (lowercase)
     * @param bcc the Bcc header value (lowercase)
     * @param subject the Subject header value (lowercase)
     * @param messageId the Message-ID header value (lowercase)
     * @param keywords comma-separated keywords (lowercase)
     */
    public MessageIndexEntry(long uid, int messageNumber, long size,
            long internalDate, long sentDate, Set<Flag> flags,
            String location, String from, String to, String cc, String bcc,
            String subject, String messageId, String keywords) {
        this.uid = uid;
        this.messageNumber = messageNumber;
        this.size = size;
        this.internalDate = internalDate;
        this.sentDate = sentDate;
        this.flagsByte = flagsToBytes(flags);
        this.descriptors = new int[DESCRIPTOR_COUNT * 2];

        // Build variable data and descriptors
        buildVariableData(location, from, to, cc, bcc, subject, messageId, keywords);
    }

    /**
     * Builds the variable data section and populates descriptors.
     */
    private void buildVariableData(String location, String from, String to,
            String cc, String bcc, String subject, String messageId, String keywords) {
        // Convert strings to bytes
        byte[][] values = new byte[DESCRIPTOR_COUNT][];
        values[DESC_LOCATION] = toBytes(location);
        values[DESC_FROM] = toBytes(from);
        values[DESC_TO] = toBytes(to);
        values[DESC_CC] = toBytes(cc);
        values[DESC_BCC] = toBytes(bcc);
        values[DESC_SUBJECT] = toBytes(subject);
        values[DESC_MESSAGE_ID] = toBytes(messageId);
        values[DESC_KEYWORDS] = toBytes(keywords);

        // Calculate total size
        int totalSize = 0;
        for (int i = 0; i < DESCRIPTOR_COUNT; i++) {
            totalSize += values[i].length;
        }

        // Allocate and populate variable data
        variableData = new byte[totalSize];
        int offset = 0;
        for (int i = 0; i < DESCRIPTOR_COUNT; i++) {
            byte[] value = values[i];
            descriptors[i * 2] = offset;
            descriptors[i * 2 + 1] = value.length;
            System.arraycopy(value, 0, variableData, offset, value.length);
            offset += value.length;
        }
    }

    /**
     * Converts a string to UTF-8 bytes, handling null.
     */
    private static byte[] toBytes(String s) {
        if (s == null || s.isEmpty()) {
            return new byte[0];
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Converts a flag set to a byte.
     */
    private static byte flagsToBytes(Set<Flag> flags) {
        if (flags == null) {
            return 0;
        }
        byte b = 0;
        if (flags.contains(Flag.SEEN)) {
            b |= (1 << FLAG_BIT_SEEN);
        }
        if (flags.contains(Flag.ANSWERED)) {
            b |= (1 << FLAG_BIT_ANSWERED);
        }
        if (flags.contains(Flag.FLAGGED)) {
            b |= (1 << FLAG_BIT_FLAGGED);
        }
        if (flags.contains(Flag.DELETED)) {
            b |= (1 << FLAG_BIT_DELETED);
        }
        if (flags.contains(Flag.DRAFT)) {
            b |= (1 << FLAG_BIT_DRAFT);
        }
        if (flags.contains(Flag.RECENT)) {
            b |= (1 << FLAG_BIT_RECENT);
        }
        return b;
    }

    /**
     * Converts a flag byte back to a flag set.
     */
    private static Set<Flag> bytesToFlags(byte b) {
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        if ((b & (1 << FLAG_BIT_SEEN)) != 0) {
            flags.add(Flag.SEEN);
        }
        if ((b & (1 << FLAG_BIT_ANSWERED)) != 0) {
            flags.add(Flag.ANSWERED);
        }
        if ((b & (1 << FLAG_BIT_FLAGGED)) != 0) {
            flags.add(Flag.FLAGGED);
        }
        if ((b & (1 << FLAG_BIT_DELETED)) != 0) {
            flags.add(Flag.DELETED);
        }
        if ((b & (1 << FLAG_BIT_DRAFT)) != 0) {
            flags.add(Flag.DRAFT);
        }
        if ((b & (1 << FLAG_BIT_RECENT)) != 0) {
            flags.add(Flag.RECENT);
        }
        return flags;
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public long getUid() {
        return uid;
    }

    public int getMessageNumber() {
        return messageNumber;
    }

    public void setMessageNumber(int messageNumber) {
        this.messageNumber = messageNumber;
    }

    public long getSize() {
        return size;
    }

    public long getInternalDate() {
        return internalDate;
    }

    public long getSentDate() {
        return sentDate;
    }

    public Set<Flag> getFlags() {
        return bytesToFlags(flagsByte);
    }

    public void setFlags(Set<Flag> flags) {
        this.flagsByte = flagsToBytes(flags);
    }

    public boolean hasFlag(Flag flag) {
        int bit;
        switch (flag) {
            case SEEN:
                bit = FLAG_BIT_SEEN;
                break;
            case ANSWERED:
                bit = FLAG_BIT_ANSWERED;
                break;
            case FLAGGED:
                bit = FLAG_BIT_FLAGGED;
                break;
            case DELETED:
                bit = FLAG_BIT_DELETED;
                break;
            case DRAFT:
                bit = FLAG_BIT_DRAFT;
                break;
            case RECENT:
                bit = FLAG_BIT_RECENT;
                break;
            default:
                return false;
        }
        return (flagsByte & (1 << bit)) != 0;
    }

    /**
     * Gets a string property by descriptor index.
     */
    private String getProperty(int descriptorIndex) {
        int offset = descriptors[descriptorIndex * 2];
        int length = descriptors[descriptorIndex * 2 + 1];
        if (length == 0) {
            return "";
        }
        return new String(variableData, offset, length, StandardCharsets.UTF_8);
    }

    public String getLocation() {
        return getProperty(DESC_LOCATION);
    }

    public String getFrom() {
        return getProperty(DESC_FROM);
    }

    public String getTo() {
        return getProperty(DESC_TO);
    }

    public String getCc() {
        return getProperty(DESC_CC);
    }

    public String getBcc() {
        return getProperty(DESC_BCC);
    }

    public String getSubject() {
        return getProperty(DESC_SUBJECT);
    }

    public String getMessageId() {
        return getProperty(DESC_MESSAGE_ID);
    }

    public String getKeywords() {
        return getProperty(DESC_KEYWORDS);
    }

    // ========================================================================
    // Serialization
    // ========================================================================

    /**
     * Writes this entry to the output stream.
     *
     * @param out the output stream
     * @throws IOException if writing fails
     */
    public void writeTo(DataOutputStream out) throws IOException {
        // Fixed header
        out.writeLong(uid);
        out.writeInt(messageNumber);
        out.writeLong(size);
        out.writeLong(internalDate);
        out.writeLong(sentDate);
        out.writeByte(flagsByte);
        out.writeByte(0); // reserved
        out.writeByte(0); // reserved
        out.writeByte(0); // reserved
        out.writeInt(DESCRIPTOR_COUNT);
        out.writeInt(variableData.length);

        // Descriptors
        for (int i = 0; i < DESCRIPTOR_COUNT; i++) {
            out.writeInt(descriptors[i * 2]);     // offset
            out.writeInt(descriptors[i * 2 + 1]); // length
        }

        // Variable data
        out.write(variableData);
    }

    /**
     * Reads an entry from the input stream.
     *
     * @param in the input stream
     * @return the read entry
     * @throws IOException if reading fails
     */
    public static MessageIndexEntry readFrom(DataInputStream in) throws IOException {
        MessageIndexEntry entry = new MessageIndexEntry();

        // Fixed header
        entry.uid = in.readLong();
        entry.messageNumber = in.readInt();
        entry.size = in.readLong();
        entry.internalDate = in.readLong();
        entry.sentDate = in.readLong();
        entry.flagsByte = in.readByte();
        in.readByte(); // reserved
        in.readByte(); // reserved
        in.readByte(); // reserved
        int descriptorCount = in.readInt();
        int variableDataSize = in.readInt();

        // Validate
        if (descriptorCount != DESCRIPTOR_COUNT) {
            throw new IOException("Invalid descriptor count: " + descriptorCount);
        }
        if (variableDataSize < 0 || variableDataSize > 10 * 1024 * 1024) {
            throw new IOException("Invalid variable data size: " + variableDataSize);
        }

        // Descriptors
        for (int i = 0; i < DESCRIPTOR_COUNT; i++) {
            int offset = in.readInt();
            int length = in.readInt();
            
            // Validate descriptor bounds
            if (offset < 0 || length < 0 || offset + length > variableDataSize) {
                throw new IOException("Invalid descriptor at index " + i + 
                    ": offset=" + offset + ", length=" + length + 
                    ", dataSize=" + variableDataSize);
            }
            
            entry.descriptors[i * 2] = offset;
            entry.descriptors[i * 2 + 1] = length;
        }

        // Variable data
        entry.variableData = new byte[variableDataSize];
        in.readFully(entry.variableData);

        return entry;
    }

    /**
     * Returns the total serialized size of this entry.
     *
     * @return size in bytes
     */
    public int getSerializedSize() {
        return FIXED_HEADER_SIZE + (DESCRIPTOR_COUNT * DESCRIPTOR_SIZE) + variableData.length;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("MessageIndexEntry{uid=").append(uid);
        sb.append(", msgNum=").append(messageNumber);
        sb.append(", size=").append(size);
        sb.append(", flags=").append(getFlags());
        sb.append(", from=").append(getFrom());
        sb.append(", subject=").append(getSubject());
        sb.append("}");
        return sb.toString();
    }

}

