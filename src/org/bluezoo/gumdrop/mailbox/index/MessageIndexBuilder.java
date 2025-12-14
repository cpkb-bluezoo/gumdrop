/*
 * MessageIndexBuilder.java
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
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.MIMELocator;
import org.bluezoo.gumdrop.mime.MIMEParseException;
import org.bluezoo.gumdrop.mime.MIMEVersion;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.mime.rfc5322.MessageParser;
import org.bluezoo.gumdrop.mime.rfc5322.ObsoleteStructureType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds {@link MessageIndexEntry} objects by parsing messages using
 * the {@link MessageParser}.
 * 
 * <p>This builder extracts all searchable header fields from messages
 * and stores them in lowercase for case-insensitive searching.
 * 
 * <p>Usage:
 * <pre>{@code
 * MessageIndexBuilder builder = new MessageIndexBuilder();
 * MessageIndexEntry entry = builder.buildEntry(
 *     uid, messageNumber, size, flags, location, messageChannel);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIndexBuilder {

    /** Separator used when concatenating multiple header values. */
    private static final String VALUE_SEPARATOR = " ";

    /** Separator used for keywords. */
    private static final String KEYWORD_SEPARATOR = ",";

    /**
     * Builds an index entry for a message.
     *
     * @param uid the message UID
     * @param messageNumber the message sequence number
     * @param size the message size in bytes
     * @param internalDate the internal date (when received), or 0 if unknown
     * @param flags the message flags
     * @param location the message location (filename for Maildir, offset for mbox)
     * @param channel readable channel for message content
     * @return the built index entry
     * @throws IOException if reading or parsing fails
     */
    public MessageIndexEntry buildEntry(long uid, int messageNumber, long size,
            long internalDate, Set<Flag> flags, String location,
            ReadableByteChannel channel) throws IOException {

        // Create handler to collect header data
        IndexingHandler handler = new IndexingHandler();

        // Parse the message
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);

        ByteBuffer buffer = ByteBuffer.allocate(8192);
        try {
            while (channel.read(buffer) > 0) {
                buffer.flip();
                parser.receive(buffer);
                
                // Preserve unconsumed data (partial lines)
                if (parser.isUnderflow()) {
                    buffer.compact();
                } else {
                    buffer.clear();
                }
                
                // Stop after headers are parsed - we don't need body for indexing
                if (handler.isHeadersComplete()) {
                    break;
                }
            }
            // Only close parser if we read the entire message
            // Otherwise we may get errors about incomplete multipart boundaries
            if (!handler.isHeadersComplete()) {
                parser.close();
            }
        } catch (MIMEParseException e) {
            // If headers are complete, ignore parsing errors (e.g., incomplete body)
            if (!handler.isHeadersComplete()) {
                throw new IOException("Failed to parse message for indexing", e);
            }
        }

        // Use sent date for internal date if not provided
        long finalInternalDate = internalDate;
        if (finalInternalDate == 0 && handler.getSentDate() != null) {
            finalInternalDate = handler.getSentDate().toInstant().toEpochMilli();
        }

        // Get sent date
        long sentDate = 0;
        if (handler.getSentDate() != null) {
            sentDate = handler.getSentDate().toInstant().toEpochMilli();
        }

        // Build the entry with lowercase values
        return new MessageIndexEntry(
            uid,
            messageNumber,
            size,
            finalInternalDate,
            sentDate,
            flags,
            location,
            toLowerCase(handler.getFrom()),
            toLowerCase(handler.getTo()),
            toLowerCase(handler.getCc()),
            toLowerCase(handler.getBcc()),
            toLowerCase(handler.getSubject()),
            toLowerCase(handler.getMessageId()),
            toLowerCase(handler.getKeywords())
        );
    }

    /**
     * Converts a string to lowercase, handling null.
     */
    private static String toLowerCase(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * Message handler that collects header fields for indexing.
     */
    private static class IndexingHandler implements MessageHandler {

        private final List<String> fromAddresses = new ArrayList<>();
        private final List<String> toAddresses = new ArrayList<>();
        private final List<String> ccAddresses = new ArrayList<>();
        private final List<String> bccAddresses = new ArrayList<>();
        private String subject;
        private String messageId;
        private OffsetDateTime sentDate;
        private boolean headersComplete = false;

        // MIMEHandler methods

        @Override
        public void setLocator(MIMELocator locator) {
            // Not needed for indexing
        }

        @Override
        public void startEntity(String boundary) throws MIMEParseException {
            // Not needed for indexing
        }

        @Override
        public void contentType(ContentType contentType) throws MIMEParseException {
            // Not needed for indexing
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) throws MIMEParseException {
            // Not needed for indexing
        }

        @Override
        public void contentTransferEncoding(String encoding) throws MIMEParseException {
            // Not needed for indexing
        }

        @Override
        public void contentID(ContentID contentID) throws MIMEParseException {
            // Not needed for indexing
        }

        @Override
        public void contentDescription(String description) throws MIMEParseException {
            // Not needed for indexing
        }

        @Override
        public void mimeVersion(MIMEVersion version) throws MIMEParseException {
            // Not needed for indexing
        }

        @Override
        public void endHeaders() throws MIMEParseException {
            headersComplete = true;
        }

        @Override
        public void bodyContent(ByteBuffer data) throws MIMEParseException {
            // We don't index body content
        }

        @Override
        public void unexpectedContent(ByteBuffer data) throws MIMEParseException {
            // Ignore unexpected content
        }

        @Override
        public void endEntity(String boundary) throws MIMEParseException {
            // Not needed for indexing
        }

        // MessageHandler methods

        @Override
        public void header(String name, String value) throws MIMEParseException {
            String lowerName = name.toLowerCase(Locale.ROOT);
            if ("subject".equals(lowerName) && subject == null) {
                subject = value;
            }
            // Message-ID is handled via messageIDHeader()
        }

        @Override
        public void unexpectedHeader(String name, String value) throws MIMEParseException {
            // Ignore malformed headers for indexing
        }

        @Override
        public void dateHeader(String name, OffsetDateTime date) throws MIMEParseException {
            if ("Date".equalsIgnoreCase(name) && sentDate == null) {
                sentDate = date;
            }
        }

        @Override
        public void addressHeader(String name, List<EmailAddress> addresses) 
                throws MIMEParseException {
            String lowerName = name.toLowerCase(Locale.ROOT);
            List<String> target;
            
            switch (lowerName) {
                case "from":
                case "sender":
                    target = fromAddresses;
                    break;
                case "to":
                    target = toAddresses;
                    break;
                case "cc":
                    target = ccAddresses;
                    break;
                case "bcc":
                    target = bccAddresses;
                    break;
                default:
                    return;
            }

            for (EmailAddress addr : addresses) {
                // Use the structured address, not the formatted toString()
                String addrStr = addr.getAddress();
                if (addrStr != null && !addrStr.isEmpty()) {
                    target.add(addrStr);
                }
            }
        }

        @Override
        public void messageIDHeader(String name, List<ContentID> messageIDs) 
                throws MIMEParseException {
            if ("Message-ID".equalsIgnoreCase(name) && messageId == null && !messageIDs.isEmpty()) {
                // Use the structured ContentID - format as <local@domain>
                ContentID mid = messageIDs.get(0);
                messageId = "<" + mid.getLocalPart() + "@" + mid.getDomain() + ">";
            }
        }

        @Override
        public void obsoleteStructure(ObsoleteStructureType type) throws MIMEParseException {
            // Ignore for indexing purposes
        }

        // Accessor methods

        public boolean isHeadersComplete() {
            return headersComplete;
        }

        public String getFrom() {
            return joinValues(fromAddresses);
        }

        public String getTo() {
            return joinValues(toAddresses);
        }

        public String getCc() {
            return joinValues(ccAddresses);
        }

        public String getBcc() {
            return joinValues(bccAddresses);
        }

        public String getSubject() {
            return subject;
        }

        public String getMessageId() {
            return messageId;
        }

        public OffsetDateTime getSentDate() {
            return sentDate;
        }

        public String getKeywords() {
            // Keywords are stored as flags in the mailbox, not in the message
            // They will be set separately when building the entry
            return "";
        }

        private String joinValues(List<String> values) {
            if (values.isEmpty()) {
                return "";
            }
            if (values.size() == 1) {
                return values.get(0);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(VALUE_SEPARATOR);
                }
                sb.append(values.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * Extracts individual email addresses from a concatenated address string.
     * Used for building reverse indexes.
     *
     * @param addresses concatenated address string
     * @return list of individual addresses
     */
    public static List<String> extractAddresses(String addresses) {
        List<String> result = new ArrayList<>();
        if (addresses == null || addresses.isEmpty()) {
            return result;
        }

        // Simple parsing: split on common separators and extract email parts
        // This handles "name <email>" and plain "email" formats
        int start = 0;
        int len = addresses.length();
        boolean inAngle = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < len; i++) {
            char c = addresses.charAt(i);
            
            if (c == '<') {
                inAngle = true;
                current.setLength(0); // Start fresh for actual email
            } else if (c == '>') {
                if (current.length() > 0) {
                    String addr = current.toString().trim();
                    if (!addr.isEmpty()) {
                        result.add(addr);
                    }
                }
                current.setLength(0);
                inAngle = false;
            } else if (c == ',' || c == ';') {
                if (!inAngle && current.length() > 0) {
                    String addr = current.toString().trim();
                    if (!addr.isEmpty() && addr.contains("@")) {
                        result.add(addr);
                    }
                }
                current.setLength(0);
            } else if (inAngle || (!Character.isWhitespace(c) && c != '"')) {
                current.append(c);
            }
        }

        // Handle remaining content
        if (current.length() > 0) {
            String addr = current.toString().trim();
            if (!addr.isEmpty() && addr.contains("@")) {
                result.add(addr);
            }
        }

        return result;
    }

    /**
     * Extracts individual keywords from a comma-separated keyword string.
     * Used for building reverse indexes.
     *
     * @param keywords comma-separated keywords
     * @return list of individual keywords
     */
    public static List<String> extractKeywords(String keywords) {
        List<String> result = new ArrayList<>();
        if (keywords == null || keywords.isEmpty()) {
            return result;
        }

        int start = 0;
        int len = keywords.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || keywords.charAt(i) == ',') {
                if (i > start) {
                    String kw = keywords.substring(start, i).trim();
                    if (!kw.isEmpty()) {
                        result.add(kw);
                    }
                }
                start = i + 1;
            }
        }

        return result;
    }

}
