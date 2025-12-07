/*
 * MboxMessageDescriptor.java
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

import org.bluezoo.gumdrop.mailbox.MessageDescriptor;

/**
 * Message descriptor for mbox format mailboxes.
 * 
 * <p>This descriptor stores the file offsets for the beginning and end
 * of the message content within the mbox file, allowing for efficient
 * random access without re-parsing the entire file.
 * 
 * <p>The offsets refer to the RFC 822 message content, not including
 * the mbox "From " envelope line.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MboxMessageDescriptor implements MessageDescriptor {

    private final int messageNumber;
    private final long startOffset;
    private final long endOffset;
    private final String uniqueId;

    /**
     * Creates a new mbox message descriptor.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @param startOffset the byte offset where the message content begins
     * @param endOffset the byte offset where the message content ends (exclusive)
     * @param uniqueId a unique identifier for the message
     */
    public MboxMessageDescriptor(int messageNumber, long startOffset, long endOffset, String uniqueId) {
        this.messageNumber = messageNumber;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.uniqueId = uniqueId;
    }

    @Override
    public int getMessageNumber() {
        return messageNumber;
    }

    @Override
    public long getSize() {
        return endOffset - startOffset;
    }

    @Override
    public String getUniqueId() {
        return uniqueId;
    }

    /**
     * Returns the byte offset where the message content begins.
     * This is the position after the mbox "From " envelope line.
     * 
     * @return the start offset
     */
    public long getStartOffset() {
        return startOffset;
    }

    /**
     * Returns the byte offset where the message content ends (exclusive).
     * 
     * @return the end offset
     */
    public long getEndOffset() {
        return endOffset;
    }

    @Override
    public String toString() {
        return "MboxMessageDescriptor[" + messageNumber + ", " + 
               startOffset + "-" + endOffset + ", " + uniqueId + "]";
    }
}

