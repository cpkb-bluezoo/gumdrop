/*
 * ParsedMessageContext.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

import org.bluezoo.gumdrop.mime.HeaderValueBuilder;
import org.bluezoo.gumdrop.mime.MimeLocator;
import org.bluezoo.gumdrop.mime.MimeParseException;
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

    @Override
    public long getModSeq() throws IOException {
        return mailbox.getModSeq(messageNumber);
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
                buffer.compact(); // keep a partial line for the next read
            }
            buffer.flip();
            parser.close(buffer); // what is left is the last line, which has no line ending
        } catch (MimeParseException e) {
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
        private String currentPrimaryType; // of the current entity, or null
        private String currentCharset; // of the current entity, or null
        private boolean inHeaders = true;

        private final HeaderValueBuilder structured = new HeaderValueBuilder();
        private String structuredName; // Content-Type or Content-Disposition, while it is read
        private String parameterName;
        private final StringBuilder parameterValue = new StringBuilder();
        private String charsetParameter;
        private String primaryType;

        private String headerName; // the header being read, or null
        private final StringBuilder headerText = new StringBuilder();
        private final StringBuilder list = new StringBuilder(); // the addresses or ids read so far
        private boolean inGroup;

        // MimeHandler methods

        @Override
        public boolean wantsLegacyEvents() {
            return false;
        }

        @Override
        public void setLocator(MimeLocator locator) {
            // Not needed for search
        }

        @Override
        public void startEntity(String boundary) throws MimeParseException {
            inHeaders = true;
        }

        @Override
        public void startContentType(String primary, String sub) throws MimeParseException {
            structuredName = "Content-Type";
            primaryType = primary;
            charsetParameter = null;
            structured.start(primary + "/" + sub);
        }

        @Override
        public void endContentType() throws MimeParseException {
            currentPrimaryType = primaryType;
            currentCharset = charsetParameter;
            addHeader(structuredName, structured.value());
        }

        @Override
        public void startContentDisposition(String kind) throws MimeParseException {
            structuredName = "Content-Disposition";
            structured.start(kind);
        }

        @Override
        public void endContentDisposition() throws MimeParseException {
            addHeader(structuredName, structured.value());
        }

        @Override
        public void startParameter(String name, String language) throws MimeParseException {
            parameterName = name;
            parameterValue.setLength(0);
            structured.startParameter(name);
        }

        @Override
        public void parameterContent(String text) throws MimeParseException {
            parameterValue.append(text);
            structured.parameterContent(text);
        }

        @Override
        public void endParameter() throws MimeParseException {
            structured.endParameter();
            if (charsetParameter == null && "Content-Type".equals(structuredName)
                    && "charset".equalsIgnoreCase(parameterName)) {
                charsetParameter = parameterValue.toString();
            }
        }

        @Override
        public void contentTransferEncoding(String encoding) throws MimeParseException {
            addHeader("Content-Transfer-Encoding", encoding);
        }

        @Override
        public void contentID(String localPart, String domain) throws MimeParseException {
            addHeader("Content-ID", "<" + localPart + "@" + domain + ">");
        }

        @Override
        public void contentDescription(String description) throws MimeParseException {
            addHeader("Content-Description", description);
        }

        @Override
        public void mimeVersion(int major, int minor) throws MimeParseException {
            addHeader("MIME-Version", major + "." + minor);
        }

        @Override
        public void endHeaders() throws MimeParseException {
            inHeaders = false;
            headersText.append("\r\n"); // Blank line after headers
        }

        @Override
        public void bodyContent(ByteBuffer content) throws MimeParseException {
            // Only collect text content for search
            if (isTextContent()) {
                Charset charset = getCharset();
                byte[] bytes = new byte[content.remaining()];
                content.get(bytes);
                bodyText.append(new String(bytes, charset));
            }
        }

        @Override
        public void unexpectedContent(ByteBuffer content) throws MimeParseException {
            // Ignore unexpected content
        }

        @Override
        public void endEntity(String boundary) throws MimeParseException {
            currentPrimaryType = null;
            currentCharset = null;
        }

        // MessageHandler methods

        @Override
        public void startHeader(String name) throws MimeParseException {
            headerName = name;
            headerText.setLength(0);
        }

        @Override
        public void headerContent(String text) throws MimeParseException {
            headerText.append(text);
        }

        @Override
        public void endHeader() throws MimeParseException {
            // an unstructured header, or the text of one that could not be read
            addHeader(headerName, headerText.toString());
            headerName = null;
        }

        @Override
        public void malformedHeader(String name, String reason) throws MimeParseException {
            // Still store malformed headers: what was read, then the text from there on
            headerName = name;
            headerText.setLength(0);
            if (list.length() > 0) {
                headerText.append(list).append(inGroup ? " " : ", ");
            }
            list.setLength(0);
            inGroup = false;
        }

        @Override
        public void dateHeader(String name, OffsetDateTime date) throws MimeParseException {
            // Store as regular header too
            addHeader(name, date != null ? date.toString() : "");

            // Capture Date header as sent date
            if ("Date".equalsIgnoreCase(name) && sentDate == null) {
                sentDate = date;
            }
        }

        @Override
        public void startAddressHeader(String name) throws MimeParseException {
            headerName = name;
            list.setLength(0);
            inGroup = false;
        }

        @Override
        public void mailbox(String displayName, String localPart, String domain) throws MimeParseException {
            separate();
            if (displayName != null && !displayName.isEmpty()) {
                list.append(displayName).append(' ');
            }
            list.append('<').append(localPart).append('@').append(domain).append('>');
        }

        @Override
        public void startGroup(String name) throws MimeParseException {
            separate();
            list.append(name).append(':');
            inGroup = true;
        }

        @Override
        public void endGroup() throws MimeParseException {
            list.append(';');
            inGroup = false;
        }

        @Override
        public void endAddressHeader() throws MimeParseException {
            addHeader(headerName, list.toString());
            headerName = null;
        }

        @Override
        public void startMessageIDHeader(String name) throws MimeParseException {
            headerName = name;
            list.setLength(0);
        }

        @Override
        public void messageID(String localPart, String domain) throws MimeParseException {
            if (list.length() > 0) {
                list.append(' ');
            }
            list.append('<').append(localPart).append('@').append(domain).append('>');
        }

        @Override
        public void endMessageIDHeader() throws MimeParseException {
            addHeader(headerName, list.toString());
            headerName = null;
        }

        @Override
        public void obsoleteStructure(ObsoleteStructureType type) throws MimeParseException {
            // Ignore for search purposes
        }

        /** Separates an address from the one before it in the list, if any. */
        private void separate() {
            if (list.length() > 0) {
                list.append(list.charAt(list.length() - 1) == ':' ? " " : ", ");
            }
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
            if (currentPrimaryType == null) {
                return true; // Default to text/plain
            }
            return currentPrimaryType.equalsIgnoreCase("text");
        }

        private Charset getCharset() {
            if (currentCharset != null) {
                try {
                    return Charset.forName(currentCharset);
                } catch (Exception e) {
                    // Fall through to default
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
