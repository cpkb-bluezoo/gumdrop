/*
 * Mailbox.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface for accessing mailbox contents.
 * Implementations of this interface provide access to a single mailbox
 * (folder) from various backend storage systems.
 * 
 * <p>This interface is designed to be shared across multiple mail access
 * protocols including POP3 and IMAP.
 * 
 * <p>All implementations must be thread-safe as a single mailbox may
 * be accessed by multiple concurrent sessions.
 *
 * <p>This interface uses NIO {@link ReadableByteChannel} for message content
 * access, consistent with Gumdrop's NIO architecture.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Mailbox {

    // ========================================================================
    // Lifecycle Methods
    // ========================================================================

    /**
     * Closes the mailbox and releases any locks.
     * This should commit any pending deletions.
     * 
     * @param expunge if true, permanently remove messages marked for deletion;
     *                if false, unmark all deletion marks
     * @throws IOException if the mailbox cannot be closed properly
     */
    void close(boolean expunge) throws IOException;

    /**
     * Returns the name of this mailbox.
     * For POP3, this is typically "INBOX".
     * 
     * @return the mailbox name
     */
    default String getName() {
        return "INBOX";
    }

    /**
     * Returns whether this mailbox is open in read-only mode.
     * 
     * @return true if read-only
     */
    default boolean isReadOnly() {
        return false;
    }

    // ========================================================================
    // Message Enumeration
    // ========================================================================

    /**
     * Returns the number of messages in the mailbox.
     * This does not include messages marked for deletion.
     * 
     * @return the number of accessible messages
     * @throws IOException if the count cannot be determined
     */
    int getMessageCount() throws IOException;

    /**
     * Returns the total size of all messages in octets.
     * This does not include messages marked for deletion.
     * 
     * @return the total size in octets
     * @throws IOException if the size cannot be determined
     */
    long getMailboxSize() throws IOException;

    /**
     * Returns an iterator over all message descriptors in the mailbox.
     * Messages are returned in order by message number (1-based).
     * Messages marked for deletion are not included.
     * 
     * <p>Using an iterator rather than a list allows implementations to
     * lazily load descriptors, reducing memory usage for large mailboxes.
     * Use {@link #getMessageCount()} to get the total count.
     * 
     * @return iterator over message descriptors
     * @throws IOException if the messages cannot be enumerated
     */
    Iterator<MessageDescriptor> getMessageList() throws IOException;

    /**
     * Returns the message descriptor for the specified message number.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @return the message descriptor, or null if the message doesn't exist
     * @throws IOException if the descriptor cannot be retrieved
     */
    MessageDescriptor getMessage(int messageNumber) throws IOException;

    // ========================================================================
    // Message Content Access
    // ========================================================================

    /**
     * Opens a channel to read the entire message content.
     * The content includes headers and body, with proper RFC 822 formatting.
     * 
     * <p>The caller is responsible for closing the returned channel.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @return a readable channel for the message content
     * @throws IOException if the message cannot be opened
     */
    ReadableByteChannel getMessageContent(int messageNumber) throws IOException;

    /**
     * Opens a channel to read the message headers and optionally a portion of
     * the body, as required by the POP3 TOP command (RFC 1939 Section 7).
     * 
     * <p>The content returned includes:
     * <ol>
     *   <li>All message headers (RFC 822 format)</li>
     *   <li>A blank line (CRLF) separating headers from body</li>
     *   <li>The first {@code bodyLines} lines of the message body</li>
     * </ol>
     * 
     * <p>The caller is responsible for closing the returned channel.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @param bodyLines the number of body lines to retrieve after headers
     *                  (0 means headers only, with trailing blank line)
     * @return a readable channel for the headers and body lines
     * @throws IOException if the content cannot be retrieved
     */
    ReadableByteChannel getMessageTop(int messageNumber, int bodyLines) throws IOException;

    // ========================================================================
    // Message Flags (IMAP)
    // ========================================================================

    /**
     * Returns the flags for the specified message.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @return set of flags for the message
     * @throws IOException if flags cannot be retrieved
     */
    default Set<Flag> getFlags(int messageNumber) throws IOException {
        return Set.of();
    }

    /**
     * Sets flags on the specified message.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @param flags the flags to set
     * @param add true to add flags, false to remove them
     * @throws IOException if flags cannot be modified
     */
    default void setFlags(int messageNumber, Set<Flag> flags, boolean add) throws IOException {
        throw new UnsupportedOperationException("Flags not supported");
    }

    /**
     * Replaces all flags on the specified message.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @param flags the new set of flags
     * @throws IOException if flags cannot be modified
     */
    default void replaceFlags(int messageNumber, Set<Flag> flags) throws IOException {
        throw new UnsupportedOperationException("Flags not supported");
    }

    /**
     * Returns the set of permanent flags supported by this mailbox.
     * Permanent flags are preserved across sessions.
     * 
     * @return set of supported permanent flags
     */
    default Set<Flag> getPermanentFlags() {
        return Flag.permanentFlags();
    }

    // ========================================================================
    // Message Deletion
    // ========================================================================

    /**
     * Marks a message for deletion.
     * The message is not actually deleted until close() is called with expunge=true,
     * or expunge() is called explicitly.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @throws IOException if the message cannot be marked for deletion
     */
    void deleteMessage(int messageNumber) throws IOException;

    /**
     * Checks if a message is marked for deletion.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @return true if the message is marked for deletion
     * @throws IOException if the status cannot be determined
     */
    boolean isDeleted(int messageNumber) throws IOException;

    /**
     * Unmarks all messages marked for deletion.
     * This is used by the POP3 RSET command.
     * 
     * @throws IOException if the messages cannot be unmarked
     */
    void undeleteAll() throws IOException;

    /**
     * Permanently removes all messages marked for deletion.
     * This is the IMAP EXPUNGE command.
     * 
     * @return list of expunged message numbers (in ascending order)
     * @throws IOException if messages cannot be expunged
     */
    default List<Integer> expunge() throws IOException {
        throw new UnsupportedOperationException("Expunge not supported");
    }

    // ========================================================================
    // Unique Identifiers
    // ========================================================================

    /**
     * Returns the unique identifier for a message.
     * This must be unique and persistent across sessions.
     * Typically derived from Message-ID or a content hash.
     * 
     * @param messageNumber the message sequence number (1-based)
     * @return the unique identifier string
     * @throws IOException if the UID cannot be retrieved
     */
    String getUniqueId(int messageNumber) throws IOException;

    /**
     * Returns the UIDVALIDITY value for this mailbox.
     * This value must change if UIDs are reassigned.
     * 
     * @return the UIDVALIDITY value
     * @throws IOException if the value cannot be determined
     */
    default long getUidValidity() throws IOException {
        return 1L;
    }

    /**
     * Returns the next UID value that will be assigned.
     * 
     * @return the next UID value
     * @throws IOException if the value cannot be determined
     */
    default long getUidNext() throws IOException {
        return getMessageCount() + 1;
    }

    // ========================================================================
    // Message Append (IMAP)
    // ========================================================================

    /**
     * Begins appending a new message to this mailbox.
     * 
     * <p>This is the first of three methods used for streaming message append
     * in an event-driven architecture. After calling this method, call
     * {@link #appendMessageContent(ByteBuffer)} zero or more times as data
     * arrives, then call {@link #endAppendMessage()} to complete the operation.
     * 
     * <p>Only one append operation may be in progress at a time per mailbox
     * instance. Implementations should throw an exception if this method is
     * called while an append is already in progress.
     * 
     * @param flags initial flags for the message, or null for no flags
     * @param internalDate the internal date, or null for current time
     * @throws IOException if the append cannot be started
     */
    default void startAppendMessage(Set<Flag> flags, OffsetDateTime internalDate) 
            throws IOException {
        throw new UnsupportedOperationException("Append not supported");
    }

    /**
     * Appends content data to the message currently being appended.
     * 
     * <p>This method may be called multiple times as data arrives from the
     * client. The data should be in RFC 822 format. The buffer should be
     * ready for reading (flipped).
     * 
     * <p>This method must only be called after {@link #startAppendMessage}
     * and before {@link #endAppendMessage}.
     * 
     * @param data buffer containing message content data, ready for reading
     * @throws IOException if the content cannot be written
     * @throws IllegalStateException if no append is in progress
     */
    default void appendMessageContent(ByteBuffer data) throws IOException {
        throw new UnsupportedOperationException("Append not supported");
    }

    /**
     * Completes the message append operation.
     * 
     * <p>This method finalizes the append, assigns a UID to the new message,
     * and makes it visible in the mailbox. After this method returns, the
     * server can send the response to the client.
     * 
     * <p>If an error occurs, implementations should clean up any partial
     * data and throw an exception. The mailbox should remain in a consistent
     * state.
     * 
     * @return the UID of the newly appended message
     * @throws IOException if the append cannot be completed
     * @throws IllegalStateException if no append is in progress
     */
    default long endAppendMessage() throws IOException {
        throw new UnsupportedOperationException("Append not supported");
    }

    // ========================================================================
    // Message Copy/Move (IMAP)
    // ========================================================================

    /**
     * Copies messages to another mailbox.
     * 
     * @param messageNumbers the message sequence numbers to copy
     * @param destinationMailbox the destination mailbox name
     * @return mapping of source sequence numbers to destination UIDs
     * @throws IOException if the copy fails
     */
    default Map<Integer, Long> copyMessages(List<Integer> messageNumbers, 
            String destinationMailbox) throws IOException {
        throw new UnsupportedOperationException("Copy not supported");
    }

    /**
     * Moves messages to another mailbox.
     * This is equivalent to COPY followed by marking as deleted.
     * 
     * @param messageNumbers the message sequence numbers to move
     * @param destinationMailbox the destination mailbox name
     * @return mapping of source sequence numbers to destination UIDs
     * @throws IOException if the move fails
     */
    default Map<Integer, Long> moveMessages(List<Integer> messageNumbers, 
            String destinationMailbox) throws IOException {
        throw new UnsupportedOperationException("Move not supported");
    }

    // ========================================================================
    // Search (IMAP)
    // ========================================================================

    /**
     * Searches for messages matching the given criteria.
     * 
     * <p>The default implementation iterates through all messages, parses
     * each one using {@link org.bluezoo.gumdrop.mime.rfc5322.MessageParser},
     * and evaluates against the criteria. This is slow for large mailboxes.
     * Implementations may override to use indexed search for better performance.
     * 
     * @param criteria the search criteria
     * @return list of matching message sequence numbers
     * @throws IOException if the search fails
     * @see SearchCriteria
     * @see MessageContext
     * @see ParsedMessageContext
     */
    default List<Integer> search(SearchCriteria criteria) throws IOException {
        List<Integer> results = new ArrayList<>();
        
        Iterator<MessageDescriptor> messages = getMessageList();
        while (messages.hasNext()) {
            MessageDescriptor msg = messages.next();
            int msgNum = msg.getMessageNumber();
            
            // Skip deleted messages
            if (isDeleted(msgNum)) {
                continue;
            }
            
            // Create a context for evaluating this message
            // Use message number as UID if getUniqueId returns a string
            long uid;
            try {
                uid = Long.parseLong(getUniqueId(msgNum));
            } catch (NumberFormatException e) {
                uid = msgNum; // Fallback to message number
            }
            
            MessageContext context = new ParsedMessageContext(
                this,
                msgNum,
                uid,
                msg.getSize(),
                getFlags(msgNum),
                null // Internal date not tracked in base implementation
            );
            
            // Evaluate criteria
            if (criteria.matches(context)) {
                results.add(msgNum);
            }
        }
        
        return results;
    }

}

