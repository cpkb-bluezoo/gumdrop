/*
 * MessageDescriptor.java
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
 * Interface describing a message in a mailbox.
 * Contains metadata about a message without loading the entire content.
 * 
 * <p>This is the base interface for message metadata. Protocol-specific
 * extensions may provide additional metadata:
 * <ul>
 *   <li>POP3 uses the basic interface as-is</li>
 *   <li>IMAP extends this with flags, internal date, envelope, etc.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SimpleMessageDescriptor
 */
public interface MessageDescriptor {

    /**
     * Returns the message sequence number in the mailbox (1-based).
     * This number may change as messages are expunged.
     * 
     * @return the message sequence number
     */
    int getMessageNumber();

    /**
     * Returns the message size in octets (RFC 822 format).
     * 
     * @return the size in octets
     */
    long getSize();

    /**
     * Returns the unique identifier for this message.
     * This identifier must be persistent across sessions and must not
     * change even if message sequence numbers change.
     * 
     * <p>For POP3, this is used by the UIDL command.
     * For IMAP, this corresponds to the UID.
     * 
     * @return the unique identifier string
     */
    String getUniqueId();

}
