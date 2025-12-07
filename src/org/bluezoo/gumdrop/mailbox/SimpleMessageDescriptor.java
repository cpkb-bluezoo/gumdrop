/*
 * SimpleMessageDescriptor.java
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

package org.bluezoo.gumdrop.mailbox;

/**
 * Simple implementation of {@link MessageDescriptor} for basic mail access.
 * Provides the core message metadata required by POP3 and similar protocols.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SimpleMessageDescriptor implements MessageDescriptor {

    private final int messageNumber;
    private final long size;
    private final String uniqueId;

    /**
     * Creates a new simple message descriptor.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @param size the message size in octets
     * @param uniqueId the unique identifier for this message
     */
    public SimpleMessageDescriptor(int messageNumber, long size, String uniqueId) {
        this.messageNumber = messageNumber;
        this.size = size;
        this.uniqueId = uniqueId;
    }

    @Override
    public int getMessageNumber() {
        return messageNumber;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public String getUniqueId() {
        return uniqueId;
    }

    @Override
    public String toString() {
        return "SimpleMessageDescriptor{" +
                "messageNumber=" + messageNumber +
                ", size=" + size +
                ", uniqueId='" + uniqueId + '\'' +
                '}';
    }

}

