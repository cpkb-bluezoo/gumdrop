/*
 * IMAPMessageDescriptor.java
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

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Extended message descriptor for IMAP-specific metadata.
 * This interface extends the basic {@link MessageDescriptor} with additional
 * attributes required by the IMAP protocol.
 * 
 * <p>IMAP clients can request various message attributes via FETCH,
 * including flags, internal date, envelope, body structure, etc.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MessageDescriptor
 */
public interface IMAPMessageDescriptor extends MessageDescriptor {

    /**
     * Returns the IMAP UID for this message.
     * UIDs are unique within a mailbox and persistent across sessions.
     * The default implementation returns the unique ID parsed as a long.
     * 
     * @return the UID value
     */
    default long getUID() {
        try {
            return Long.parseLong(getUniqueId());
        } catch (NumberFormatException e) {
            return getMessageNumber();
        }
    }

    /**
     * Returns the message flags.
     * 
     * @return set of flag strings (e.g., "\\Seen", "\\Flagged")
     */
    Set<String> getFlags();

    /**
     * Returns the internal date of the message.
     * This is the date/time the message was received by the server,
     * not the Date header from the message itself.
     * 
     * @return the internal date
     */
    OffsetDateTime getInternalDate();

    /**
     * Returns whether the message has the \Seen flag.
     * 
     * @return true if seen
     */
    default boolean isSeen() {
        return getFlags().contains("\\Seen");
    }

    /**
     * Returns whether the message has the \Answered flag.
     * 
     * @return true if answered
     */
    default boolean isAnswered() {
        return getFlags().contains("\\Answered");
    }

    /**
     * Returns whether the message has the \Flagged flag.
     * 
     * @return true if flagged
     */
    default boolean isFlagged() {
        return getFlags().contains("\\Flagged");
    }

    /**
     * Returns whether the message has the \Deleted flag.
     * 
     * @return true if marked for deletion
     */
    default boolean isDeleted() {
        return getFlags().contains("\\Deleted");
    }

    /**
     * Returns whether the message has the \Draft flag.
     * 
     * @return true if draft
     */
    default boolean isDraft() {
        return getFlags().contains("\\Draft");
    }

    /**
     * Returns whether this is a recent message.
     * A message is recent if this is the first session to see it.
     * 
     * @return true if recent
     */
    default boolean isRecent() {
        return getFlags().contains("\\Recent");
    }

    /**
     * Returns the message envelope (RFC 2822 headers parsed into structure).
     * This is used by IMAP FETCH ENVELOPE.
     * 
     * @return the envelope, or null if not available
     */
    default Envelope getEnvelope() {
        return null;
    }

    /**
     * Returns the body structure (MIME structure of the message).
     * This is used by IMAP FETCH BODYSTRUCTURE.
     * 
     * @return the body structure, or null if not available
     */
    default BodyStructure getBodyStructure() {
        return null;
    }

    /**
     * Message envelope containing parsed RFC 2822 headers.
     */
    interface Envelope {
        /** Returns the Date header value */
        OffsetDateTime getDate();
        
        /** Returns the Subject header */
        String getSubject();
        
        /** Returns the From addresses */
        Address[] getFrom();
        
        /** Returns the Sender addresses */
        Address[] getSender();
        
        /** Returns the Reply-To addresses */
        Address[] getReplyTo();
        
        /** Returns the To addresses */
        Address[] getTo();
        
        /** Returns the Cc addresses */
        Address[] getCc();
        
        /** Returns the Bcc addresses */
        Address[] getBcc();
        
        /** Returns the In-Reply-To header */
        String getInReplyTo();
        
        /** Returns the Message-Id header */
        String getMessageId();
    }

    /**
     * Email address structure.
     */
    interface Address {
        /** Returns the display name, or null */
        String getName();
        
        /** Returns the routing information (usually null) */
        String getRoute();
        
        /** Returns the mailbox (local part) */
        String getMailbox();
        
        /** Returns the host (domain part) */
        String getHost();
    }

    /**
     * MIME body structure for a message or part.
     */
    interface BodyStructure {
        /** Returns the MIME type (e.g., "text") */
        String getType();
        
        /** Returns the MIME subtype (e.g., "plain") */
        String getSubtype();
        
        /** Returns the body parameters (e.g., charset) */
        Map<String, String> getParameters();
        
        /** Returns the Content-ID, or null */
        String getContentId();
        
        /** Returns the Content-Description, or null */
        String getDescription();
        
        /** Returns the Content-Transfer-Encoding */
        String getEncoding();
        
        /** Returns the size in octets */
        long getSize();
        
        /** Returns the number of text lines (for text types) */
        long getLines();
        
        /** Returns the envelope for message/rfc822 parts */
        Envelope getEnvelope();
        
        /** Returns nested body structure for message/rfc822 parts */
        BodyStructure getBody();
        
        /** Returns child parts for multipart types */
        BodyStructure[] getParts();
        
        /** Returns the MD5 sum, or null */
        String getMd5();
        
        /** Returns the Content-Disposition, or null */
        String getDisposition();
        
        /** Returns disposition parameters */
        Map<String, String> getDispositionParameters();
        
        /** Returns the content language, or null */
        String[] getLanguage();
        
        /** Returns the content location, or null */
        String getLocation();
    }

}

