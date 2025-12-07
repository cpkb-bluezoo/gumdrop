/*
 * ParsedMessageContext.java
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A {@link MessageContext} implementation that parses message content
 * using {@link MessageParser} to extract searchable data.
 * 
 * <p>This implementation lazily parses the message on first access to
 * any content that requires parsing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ParsedMessageContext implements MessageContext {

    private final Mailbox mailbox;
    private final int messageNumber;
    private final long uid;
    private final long size;
    private final Set<Flag> flags;
    private final OffsetDateTime internalDate;

    // Parsed data (lazily populated)
    private boolean parsed = false;
    private Map<String, List<String>> headers;
    private StringBuilder headersText;
    private StringBuilder bodyText;
    private OffsetDateTime sentDate;

    /**
     * Creates a new parsed message context.
     * 
     * @param mailbox the mailbox containing the message
     * @param messageNumber the message sequence number
     * @param uid the message UID
     * @param size the message size in octets
     * @param flags the message flags
     * @param internalDate the internal date, or null if not available
     */
    public ParsedMessageContext(Mailbox mailbox, int messageNumber, long uid, 
            long size, Set<Flag> flags, OffsetDateTime internalDate) {
        this.mailbox = mailbox;
        this.messageNumber = messageNumber;
        this.uid = uid;
        this.size = size;
        this.flags = flags != null ? flags : Collections.emptySet();
        this.internalDate = internalDate;
        this.headers = new HashMap<>();
    }

    @Override
    public int getMessageNumber() {
        return messageNumber;
    }

    @Override
    public long getUID() {
        return uid;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public Set<Flag> getFlags() {
        return flags;
    }

    @Override
    public OffsetDateTime getInternalDate() {
        return internalDate;
    }

    @Override
    public String getHeader(String name) throws IOException {
        ensureParsed();
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    @Override
    public List<String> getHeaders(String name) throws IOException {
        ensureParsed();
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values != null ? values : Collections.emptyList();
    }

    @Override
    public OffsetDateTime getSentDate() throws IOException {
        ensureParsed();
        return sentDate;
    }

    @Override
    public CharSequence getHeadersText() throws IOException {
        ensureParsed();
        return headersText;
    }

    @Override
    public CharSequence getBodyText() throws IOException {
        ensureParsed();
        return bodyText;
    }

    /**
     * Ensures the message has been parsed.
     */
    private synchronized void ensureParsed() throws IOException {
        if (parsed) {
            return;
        }

        headersText = new StringBuilder();
        bodyText = new StringBuilder();

        // Create a handler that collects headers and body text
        SearchMessageHandler handler = new SearchMessageHandler();

        // Create parser
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);

        // Read and parse the message
        try (ReadableByteChannel channel = mailbox.getMessageContent(messageNumber)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) > 0) {
                buffer.flip();
                parser.receive(buffer);
                buffer.clear();
            }
            parser.close();
        } catch (MIMEParseException e) {
            throw new IOException("Failed to parse message", e);
        }

        // Extract collected data
        headers = handler.getHeaders();
        headersText = handler.getHeadersText();
        bodyText = handler.getBodyText();
        sentDate = handler.getSentDate();

        parsed = true;
    }

    /**
     * Message handler that collects data needed for search operations.
     */
    private static class SearchMessageHandler implements MessageHandler {

        private final Map<String, List<String>> headers = new HashMap<>();
        private final StringBuilder headersText = new StringBuilder();
        private final StringBuilder bodyText = new StringBuilder();
        private OffsetDateTime sentDate;
        private ContentType currentContentType;
        private boolean inHeaders = true;

        // MIMEHandler methods

        @Override
        public void setLocator(MIMELocator locator) {
            // Not needed for search
        }

        @Override
        public void startEntity(String boundary) throws MIMEParseException {
            inHeaders = true;
        }

        @Override
        public void contentType(ContentType contentType) throws MIMEParseException {
            currentContentType = contentType;
            addHeader("Content-Type", contentType.toHeaderValue());
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) throws MIMEParseException {
            addHeader("Content-Disposition", contentDisposition.toHeaderValue());
        }

        @Override
        public void contentTransferEncoding(String encoding) throws MIMEParseException {
            addHeader("Content-Transfer-Encoding", encoding);
        }

        @Override
        public void contentID(ContentID contentID) throws MIMEParseException {
            addHeader("Content-ID", "<" + contentID.getLocalPart() + "@" + contentID.getDomain() + ">");
        }

        @Override
        public void contentDescription(String description) throws MIMEParseException {
            addHeader("Content-Description", description);
        }

        @Override
        public void mimeVersion(MIMEVersion version) throws MIMEParseException {
            addHeader("MIME-Version", version.toString());
        }

        @Override
        public void endHeaders() throws MIMEParseException {
            inHeaders = false;
            headersText.append("\r\n"); // Blank line after headers
        }

        @Override
        public void bodyContent(ByteBuffer content) throws MIMEParseException {
            // Only collect text content for search
            if (isTextContent()) {
                Charset charset = getCharset();
                byte[] bytes = new byte[content.remaining()];
                content.get(bytes);
                bodyText.append(new String(bytes, charset));
            }
        }

        @Override
        public void unexpectedContent(ByteBuffer content) throws MIMEParseException {
            // Ignore unexpected content
        }

        @Override
        public void endEntity(String boundary) throws MIMEParseException {
            currentContentType = null;
        }

        // MessageHandler methods

        @Override
        public void header(String name, String value) throws MIMEParseException {
            addHeader(name, value);
        }

        @Override
        public void unexpectedHeader(String name, String value) throws MIMEParseException {
            // Still store malformed headers
            addHeader(name, value);
        }

        @Override
        public void dateHeader(String name, OffsetDateTime date) throws MIMEParseException {
            // Store as regular header too
            addHeader(name, date != null ? date.toString() : "");

            // Capture Date header as sent date
            if ("Date".equalsIgnoreCase(name) && sentDate == null) {
                sentDate = date;
            }
        }

        @Override
        public void addressHeader(String name, List<EmailAddress> addresses) throws MIMEParseException {
            // Convert addresses to string and store
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < addresses.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(addresses.get(i).toString());
            }
            addHeader(name, sb.toString());
        }

        @Override
        public void messageIDHeader(String name, List<ContentID> messageIDs) throws MIMEParseException {
            // Convert message IDs to string and store
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < messageIDs.size(); i++) {
                if (i > 0) {
                    sb.append(" ");
                }
                ContentID mid = messageIDs.get(i);
                sb.append("<").append(mid.getLocalPart()).append("@").append(mid.getDomain()).append(">");
            }
            addHeader(name, sb.toString());
        }

        @Override
        public void obsoleteStructure(ObsoleteStructureType type) throws MIMEParseException {
            // Ignore for search purposes
        }

        // Helper methods

        private void addHeader(String name, String value) {
            String lowerName = name.toLowerCase(Locale.ROOT);
            List<String> values = headers.get(lowerName);
            if (values == null) {
                values = new ArrayList<>();
                headers.put(lowerName, values);
            }
            values.add(value);

            // Append to headers text
            headersText.append(name).append(": ").append(value != null ? value : "").append("\r\n");
        }

        private boolean isTextContent() {
            if (currentContentType == null) {
                return true; // Default to text/plain
            }
            String type = currentContentType.getPrimaryType();
            return type == null || type.equalsIgnoreCase("text");
        }

        private Charset getCharset() {
            if (currentContentType != null) {
                String charsetParam = currentContentType.getParameter("charset");
                if (charsetParam != null) {
                    try {
                        return Charset.forName(charsetParam);
                    } catch (Exception e) {
                        // Fall through to default
                    }
                }
            }
            return StandardCharsets.ISO_8859_1;
        }

        Map<String, List<String>> getHeaders() {
            return headers;
        }

        StringBuilder getHeadersText() {
            return headersText;
        }

        StringBuilder getBodyText() {
            return bodyText;
        }

        OffsetDateTime getSentDate() {
            return sentDate;
        }
    }
}
