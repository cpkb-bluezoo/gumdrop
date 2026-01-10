/*
 * XMLWriter.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Streaming XML writer with NIO-first design.
 * <p>
 * This class provides an efficient, streaming approach to XML serialization
 * that writes to a {@link WritableByteChannel}. The writer uses an internal
 * buffer and automatically sends chunks to the channel when the buffer fills
 * beyond a threshold.
 * <p>
 * The writer supports full namespace handling, pretty-print indentation, and
 * automatic empty element optimization (emitting {@code <foo/>} instead of
 * {@code <foo></foo>} when an element has no content).
 * <p>
 * This class does not perform extensive well-formedness checking: the user
 * is responsible for ensuring elements are properly nested. However, it does
 * maintain an element stack for closing tags and tracks namespace bindings.
 * Character data is properly escaped.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Write to a file
 * FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
 * XMLWriter writer = new XMLWriter(channel);
 * 
 * writer.writeStartElement("http://example.com/ns", "root");
 * writer.writeDefaultNamespace("http://example.com/ns");
 * writer.writeAttribute("id", "1");
 * writer.writeCharacters("Hello, World!");
 * writer.writeEndElement();
 * writer.close();
 * 
 * // Output: <root xmlns="http://example.com/ns" id="1">Hello, World!</root>
 * }</pre>
 *
 * <h2>Empty Element Optimization</h2>
 * <p>
 * When {@link #writeEndElement()} is called immediately after
 * {@link #writeStartElement(String)} with no intervening content, the writer
 * automatically emits a self-closing tag:
 * <pre>{@code
 * writer.writeStartElement("br");
 * writer.writeEndElement();
 * // Output: <br/>
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is NOT thread-safe. It is intended for use on a single thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLWriter {

    private static final int DEFAULT_CAPACITY = 4096;
    private static final float SEND_THRESHOLD = 0.75f;

    private final WritableByteChannel channel;
    private ByteBuffer buffer;
    private final int sendThreshold;
    private final IndentConfig indentConfig;

    // Element stack for tracking open elements
    private final Deque<ElementInfo> elementStack = new ArrayDeque<>();
    
    // Namespace context: maps prefix -> URI at current scope
    // We use a stack of maps, one per element depth
    private final Deque<Map<String, String>> namespaceStack = new ArrayDeque<>();
    
    // Pending start tag that hasn't been closed yet (for empty element optimization)
    private boolean pendingStartTag = false;
    
    // Whether we've written any content since the start tag
    private boolean hasContent = false;
    
    // Whether we've written nested elements (for indentation of closing tag)
    private boolean hasNestedElements = false;
    
    // Track if we're at the document start (for indentation)
    private boolean atDocumentStart = true;

    /**
     * Information about an open element.
     */
    private static class ElementInfo {
        final String prefix;
        final String localName;
        final String namespaceURI;
        
        ElementInfo(String prefix, String localName, String namespaceURI) {
            this.prefix = prefix;
            this.localName = localName;
            this.namespaceURI = namespaceURI;
        }
        
        /**
         * Returns the qualified name (prefix:localName or just localName).
         */
        String getQName() {
            if (prefix != null && !prefix.isEmpty()) {
                return prefix + ":" + localName;
            }
            return localName;
        }
    }

    /**
     * Creates a new XML writer with default capacity (4KB) and no indentation.
     *
     * @param out the output stream to write to
     */
    public XMLWriter(OutputStream out) {
        this(new OutputStreamChannel(out), DEFAULT_CAPACITY, null);
    }

    /**
     * Creates a new XML writer with default capacity and optional indentation.
     *
     * @param out the output stream to write to
     * @param indentConfig the indentation configuration, or null for no indentation
     */
    public XMLWriter(OutputStream out, IndentConfig indentConfig) {
        this(new OutputStreamChannel(out), DEFAULT_CAPACITY, indentConfig);
    }

    /**
     * Creates a new XML writer with default capacity (4KB) and no indentation.
     *
     * @param channel the channel to write to
     */
    public XMLWriter(WritableByteChannel channel) {
        this(channel, DEFAULT_CAPACITY, null);
    }

    /**
     * Creates a new XML writer with specified buffer capacity and no indentation.
     *
     * @param channel the channel to write to
     * @param bufferCapacity initial buffer capacity in bytes
     */
    public XMLWriter(WritableByteChannel channel, int bufferCapacity) {
        this(channel, bufferCapacity, null);
    }

    /**
     * Creates a new XML writer with specified buffer capacity and optional indentation.
     *
     * @param channel the channel to write to
     * @param bufferCapacity initial buffer capacity in bytes
     * @param indentConfig the indentation configuration, or null for no indentation
     */
    public XMLWriter(WritableByteChannel channel, int bufferCapacity, IndentConfig indentConfig) {
        this.channel = channel;
        this.buffer = ByteBuffer.allocate(bufferCapacity);
        this.sendThreshold = (int) (bufferCapacity * SEND_THRESHOLD);
        this.indentConfig = indentConfig;
        // Initialize root namespace scope
        namespaceStack.push(new HashMap<>());
    }

    // ========== Element Methods ==========

    /**
     * Writes a start element with no namespace.
     *
     * @param localName the local name of the element
     * @throws IOException if there is an error writing data
     */
    public void writeStartElement(String localName) throws IOException {
        writeStartElement(null, localName, null);
    }

    /**
     * Writes a start element with a namespace URI.
     * The prefix will be looked up from existing namespace bindings or left empty
     * (requiring a subsequent {@link #writeDefaultNamespace(String)} call).
     *
     * @param namespaceURI the namespace URI
     * @param localName the local name of the element
     * @throws IOException if there is an error writing data
     */
    public void writeStartElement(String namespaceURI, String localName) throws IOException {
        String prefix = getPrefix(namespaceURI);
        writeStartElement(prefix, localName, namespaceURI);
    }

    /**
     * Writes a start element with an explicit prefix and namespace URI.
     *
     * @param prefix the namespace prefix (may be null or empty for default namespace)
     * @param localName the local name of the element
     * @param namespaceURI the namespace URI (may be null)
     * @throws IOException if there is an error writing data
     */
    public void writeStartElement(String prefix, String localName, String namespaceURI) throws IOException {
        // Close any pending start tag first
        closePendingStartTag(false);
        
        // Write indentation if configured
        if (indentConfig != null && !atDocumentStart) {
            writeIndent();
        }
        atDocumentStart = false;
        
        // Write the start tag opening
        ensureCapacity(1);
        buffer.put((byte) '<');
        
        // Write the qualified name
        if (prefix != null && !prefix.isEmpty()) {
            writeRawString(prefix);
            ensureCapacity(1);
            buffer.put((byte) ':');
        }
        writeRawString(localName);
        
        // Push element onto stack
        elementStack.push(new ElementInfo(prefix, localName, namespaceURI));
        
        // Push new namespace scope
        namespaceStack.push(new HashMap<>());
        
        // Mark that we have a pending start tag
        pendingStartTag = true;
        hasContent = false;
        hasNestedElements = false;
        
        sendIfNeeded();
    }

    /**
     * Writes an end element for the most recently opened element.
     * If the element has no content, this will emit a self-closing tag.
     *
     * @throws IOException if there is an error writing data
     * @throws IllegalStateException if there is no open element
     */
    public void writeEndElement() throws IOException {
        if (elementStack.isEmpty()) {
            throw new IllegalStateException("No open element to close");
        }
        
        ElementInfo element = elementStack.pop();
        
        // Pop namespace scope
        namespaceStack.pop();
        
        if (pendingStartTag && !hasContent) {
            // Empty element - close with />
            ensureCapacity(2);
            buffer.put((byte) '/');
            buffer.put((byte) '>');
            pendingStartTag = false;
        } else {
            // Close pending start tag if needed
            closePendingStartTag(false);
            
            // Write indentation for closing tag only if we had nested elements
            // (not just text content)
            if (indentConfig != null && hasNestedElements) {
                writeIndent();
            }
            
            // Write closing tag
            ensureCapacity(2);
            buffer.put((byte) '<');
            buffer.put((byte) '/');
            writeRawString(element.getQName());
            ensureCapacity(1);
            buffer.put((byte) '>');
        }
        
        // Parent element now has content (nested elements specifically)
        hasContent = true;
        hasNestedElements = true;
        
        sendIfNeeded();
    }

    // ========== Attribute Methods ==========

    /**
     * Writes an attribute with no namespace.
     *
     * @param localName the attribute name
     * @param value the attribute value
     * @throws IOException if there is an error writing data
     * @throws IllegalStateException if not in a start element
     */
    public void writeAttribute(String localName, String value) throws IOException {
        writeAttribute(null, null, localName, value);
    }

    /**
     * Writes an attribute with a namespace URI.
     *
     * @param namespaceURI the namespace URI
     * @param localName the local name of the attribute
     * @param value the attribute value
     * @throws IOException if there is an error writing data
     * @throws IllegalStateException if not in a start element
     */
    public void writeAttribute(String namespaceURI, String localName, String value) throws IOException {
        String prefix = getPrefix(namespaceURI);
        writeAttribute(prefix, namespaceURI, localName, value);
    }

    /**
     * Writes an attribute with an explicit prefix and namespace URI.
     *
     * @param prefix the namespace prefix (may be null or empty)
     * @param namespaceURI the namespace URI (may be null)
     * @param localName the local name of the attribute
     * @param value the attribute value
     * @throws IOException if there is an error writing data
     * @throws IllegalStateException if not in a start element
     */
    public void writeAttribute(String prefix, String namespaceURI, String localName, String value) 
            throws IOException {
        if (!pendingStartTag) {
            throw new IllegalStateException("Attributes must be written immediately after writeStartElement");
        }
        
        ensureCapacity(1);
        buffer.put((byte) ' ');
        
        // Write qualified name
        if (prefix != null && !prefix.isEmpty()) {
            writeRawString(prefix);
            ensureCapacity(1);
            buffer.put((byte) ':');
        }
        writeRawString(localName);
        
        // Write ="value"
        ensureCapacity(2);
        buffer.put((byte) '=');
        buffer.put((byte) '"');
        writeEscapedAttributeValue(value);
        ensureCapacity(1);
        buffer.put((byte) '"');
        
        sendIfNeeded();
    }

    // ========== Namespace Methods ==========

    /**
     * Writes a namespace declaration.
     *
     * @param prefix the namespace prefix
     * @param namespaceURI the namespace URI
     * @throws IOException if there is an error writing data
     * @throws IllegalStateException if not in a start element
     */
    public void writeNamespace(String prefix, String namespaceURI) throws IOException {
        if (!pendingStartTag) {
            throw new IllegalStateException("Namespace declarations must be written immediately after writeStartElement");
        }
        
        // Record the binding in current scope
        if (!namespaceStack.isEmpty()) {
            namespaceStack.peek().put(prefix, namespaceURI);
        }
        
        ensureCapacity(7); // " xmlns:"
        buffer.put((byte) ' ');
        buffer.put((byte) 'x');
        buffer.put((byte) 'm');
        buffer.put((byte) 'l');
        buffer.put((byte) 'n');
        buffer.put((byte) 's');
        buffer.put((byte) ':');
        writeRawString(prefix);
        ensureCapacity(2);
        buffer.put((byte) '=');
        buffer.put((byte) '"');
        writeEscapedAttributeValue(namespaceURI);
        ensureCapacity(1);
        buffer.put((byte) '"');
        
        sendIfNeeded();
    }

    /**
     * Writes a default namespace declaration.
     *
     * @param namespaceURI the namespace URI
     * @throws IOException if there is an error writing data
     * @throws IllegalStateException if not in a start element
     */
    public void writeDefaultNamespace(String namespaceURI) throws IOException {
        if (!pendingStartTag) {
            throw new IllegalStateException("Namespace declarations must be written immediately after writeStartElement");
        }
        
        // Record the binding in current scope (empty string = default namespace)
        if (!namespaceStack.isEmpty()) {
            namespaceStack.peek().put("", namespaceURI);
        }
        
        ensureCapacity(7); // " xmlns="
        buffer.put((byte) ' ');
        buffer.put((byte) 'x');
        buffer.put((byte) 'm');
        buffer.put((byte) 'l');
        buffer.put((byte) 'n');
        buffer.put((byte) 's');
        buffer.put((byte) '=');
        buffer.put((byte) '"');
        writeEscapedAttributeValue(namespaceURI);
        ensureCapacity(1);
        buffer.put((byte) '"');
        
        sendIfNeeded();
    }

    /**
     * Gets the prefix bound to a namespace URI, or null if not bound.
     *
     * @param namespaceURI the namespace URI
     * @return the prefix, or null if not bound
     */
    public String getPrefix(String namespaceURI) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            return null;
        }
        // Search from innermost scope outward
        for (Map<String, String> scope : namespaceStack) {
            for (Map.Entry<String, String> entry : scope.entrySet()) {
                if (namespaceURI.equals(entry.getValue())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * Gets the namespace URI bound to a prefix, or null if not bound.
     *
     * @param prefix the namespace prefix
     * @return the namespace URI, or null if not bound
     */
    public String getNamespaceURI(String prefix) {
        if (prefix == null) {
            prefix = "";
        }
        // Search from innermost scope outward
        for (Map<String, String> scope : namespaceStack) {
            String uri = scope.get(prefix);
            if (uri != null) {
                return uri;
            }
        }
        return null;
    }

    // ========== Character Content Methods ==========

    /**
     * Writes character content, escaping special characters.
     *
     * @param text the text to write
     * @throws IOException if there is an error writing data
     */
    public void writeCharacters(String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        closePendingStartTag(true);
        writeEscapedCharacters(text);
        sendIfNeeded();
    }

    /**
     * Writes character content from a character array, escaping special characters.
     *
     * @param text the character array
     * @param start the start offset
     * @param len the number of characters to write
     * @throws IOException if there is an error writing data
     */
    public void writeCharacters(char[] text, int start, int len) throws IOException {
        writeCharacters(new String(text, start, len));
    }

    /**
     * Writes a CDATA section.
     * <p>
     * Note: If the data contains "]]&gt;", the CDATA section will be split.
     *
     * @param data the CDATA content
     * @throws IOException if there is an error writing data
     */
    public void writeCData(String data) throws IOException {
        closePendingStartTag(true);
        
        // Handle the case where data contains ]]>
        int start = 0;
        int end;
        while ((end = data.indexOf("]]>", start)) >= 0) {
            // Write up to and including ]]
            writeCDataSection(data.substring(start, end + 2));
            // Continue after ]]
            start = end + 2;
        }
        // Write remaining content
        if (start < data.length()) {
            writeCDataSection(data.substring(start));
        }
        
        sendIfNeeded();
    }

    private void writeCDataSection(String data) throws IOException {
        ensureCapacity(9); // <![CDATA[
        buffer.put((byte) '<');
        buffer.put((byte) '!');
        buffer.put((byte) '[');
        buffer.put((byte) 'C');
        buffer.put((byte) 'D');
        buffer.put((byte) 'A');
        buffer.put((byte) 'T');
        buffer.put((byte) 'A');
        buffer.put((byte) '[');
        writeRawString(data);
        ensureCapacity(3); // ]]>
        buffer.put((byte) ']');
        buffer.put((byte) ']');
        buffer.put((byte) '>');
    }

    // ========== Comment and PI Methods ==========

    /**
     * Writes an XML comment.
     * <p>
     * Note: The comment text must not contain "--" or end with "-".
     *
     * @param comment the comment text
     * @throws IOException if there is an error writing data
     */
    public void writeComment(String comment) throws IOException {
        closePendingStartTag(true);
        
        if (indentConfig != null && !atDocumentStart) {
            writeIndent();
        }
        atDocumentStart = false;
        
        ensureCapacity(4); // <!--
        buffer.put((byte) '<');
        buffer.put((byte) '!');
        buffer.put((byte) '-');
        buffer.put((byte) '-');
        writeRawString(comment);
        ensureCapacity(3); // -->
        buffer.put((byte) '-');
        buffer.put((byte) '-');
        buffer.put((byte) '>');
        
        sendIfNeeded();
    }

    /**
     * Writes a processing instruction with no data.
     *
     * @param target the PI target
     * @throws IOException if there is an error writing data
     */
    public void writeProcessingInstruction(String target) throws IOException {
        writeProcessingInstruction(target, null);
    }

    /**
     * Writes a processing instruction.
     *
     * @param target the PI target
     * @param data the PI data (may be null)
     * @throws IOException if there is an error writing data
     */
    public void writeProcessingInstruction(String target, String data) throws IOException {
        closePendingStartTag(true);
        
        if (indentConfig != null && !atDocumentStart) {
            writeIndent();
        }
        atDocumentStart = false;
        
        ensureCapacity(2); // <?
        buffer.put((byte) '<');
        buffer.put((byte) '?');
        writeRawString(target);
        if (data != null && !data.isEmpty()) {
            ensureCapacity(1);
            buffer.put((byte) ' ');
            writeRawString(data);
        }
        ensureCapacity(2); // ?>
        buffer.put((byte) '?');
        buffer.put((byte) '>');
        
        sendIfNeeded();
    }

    // ========== Entity Reference Method ==========

    /**
     * Writes an entity reference.
     *
     * @param name the entity name (without &amp; and ;)
     * @throws IOException if there is an error writing data
     */
    public void writeEntityRef(String name) throws IOException {
        closePendingStartTag(true);
        
        ensureCapacity(2 + name.length());
        buffer.put((byte) '&');
        writeRawString(name);
        buffer.put((byte) ';');
        
        sendIfNeeded();
    }

    // ========== Flush and Close ==========

    /**
     * Flushes any buffered data to the channel.
     *
     * @throws IOException if there is an error sending data
     */
    public void flush() throws IOException {
        closePendingStartTag(false);
        if (buffer.position() > 0) {
            send();
        }
    }

    /**
     * Flushes and closes the writer.
     * <p>
     * After calling this method, the writer should not be used again.
     * Note: This does NOT close the underlying channel - the caller is
     * responsible for closing the channel.
     *
     * @throws IOException if there is an error flushing data
     */
    public void close() throws IOException {
        flush();
    }

    // ========== Internal Helper Methods ==========

    /**
     * Closes a pending start tag by writing the closing '&gt;'.
     *
     * @param markContent if true, mark that the element has content
     */
    private void closePendingStartTag(boolean markContent) throws IOException {
        if (pendingStartTag) {
            ensureCapacity(1);
            buffer.put((byte) '>');
            pendingStartTag = false;
        }
        if (markContent) {
            hasContent = true;
        }
    }

    /**
     * Writes an indentation newline and spaces/tabs.
     */
    private void writeIndent() throws IOException {
        int depth = elementStack.size();
        int indentSize = indentConfig.getIndentCount() * depth;
        ensureCapacity(1 + indentSize);
        buffer.put((byte) '\n');
        byte indentByte = (byte) indentConfig.getIndentChar();
        for (int i = 0; i < indentSize; i++) {
            buffer.put(indentByte);
        }
    }

    /**
     * Writes a raw string as UTF-8 bytes without escaping.
     */
    private void writeRawString(String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        ensureCapacity(bytes.length);
        buffer.put(bytes);
    }

    /**
     * Writes character content with XML escaping (&lt;, &gt;, &amp;).
     */
    private void writeEscapedCharacters(String s) throws IOException {
        for (int i = 0; i < s.length(); ) {
            int codePoint = s.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            
            if (buffer.remaining() < 12) {
                growBuffer(buffer.capacity() * 2);
            }
            
            if (codePoint == '<') {
                buffer.put((byte) '&');
                buffer.put((byte) 'l');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '>') {
                buffer.put((byte) '&');
                buffer.put((byte) 'g');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '&') {
                buffer.put((byte) '&');
                buffer.put((byte) 'a');
                buffer.put((byte) 'm');
                buffer.put((byte) 'p');
                buffer.put((byte) ';');
            } else if (codePoint < 0x20 && codePoint != '\t' && codePoint != '\n' && codePoint != '\r') {
                // Control character - write as character reference
                writeCharacterReference(codePoint);
            } else if (codePoint < 0x80) {
                buffer.put((byte) codePoint);
            } else {
                writeUtf8CodePoint(codePoint);
            }
            
            i += charCount;
        }
    }

    /**
     * Writes an attribute value with XML escaping (&lt;, &gt;, &amp;, &quot;).
     */
    private void writeEscapedAttributeValue(String s) throws IOException {
        for (int i = 0; i < s.length(); ) {
            int codePoint = s.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            
            if (buffer.remaining() < 12) {
                growBuffer(buffer.capacity() * 2);
            }
            
            if (codePoint == '<') {
                buffer.put((byte) '&');
                buffer.put((byte) 'l');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '>') {
                buffer.put((byte) '&');
                buffer.put((byte) 'g');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '&') {
                buffer.put((byte) '&');
                buffer.put((byte) 'a');
                buffer.put((byte) 'm');
                buffer.put((byte) 'p');
                buffer.put((byte) ';');
            } else if (codePoint == '"') {
                buffer.put((byte) '&');
                buffer.put((byte) 'q');
                buffer.put((byte) 'u');
                buffer.put((byte) 'o');
                buffer.put((byte) 't');
                buffer.put((byte) ';');
            } else if (codePoint == '\t') {
                buffer.put((byte) '\t');
            } else if (codePoint == '\n') {
                // Normalize newlines in attributes
                buffer.put((byte) ' ');
            } else if (codePoint == '\r') {
                // Normalize newlines in attributes
                buffer.put((byte) ' ');
            } else if (codePoint < 0x20) {
                // Control character - write as character reference
                writeCharacterReference(codePoint);
            } else if (codePoint < 0x80) {
                buffer.put((byte) codePoint);
            } else {
                writeUtf8CodePoint(codePoint);
            }
            
            i += charCount;
        }
    }

    /**
     * Writes a character reference (&#xHH; format).
     */
    private void writeCharacterReference(int codePoint) throws IOException {
        ensureCapacity(8); // &#xHHHH;
        buffer.put((byte) '&');
        buffer.put((byte) '#');
        buffer.put((byte) 'x');
        String hex = Integer.toHexString(codePoint).toUpperCase();
        for (int i = 0; i < hex.length(); i++) {
            buffer.put((byte) hex.charAt(i));
        }
        buffer.put((byte) ';');
    }

    /**
     * Writes a code point as UTF-8 bytes.
     */
    private void writeUtf8CodePoint(int codePoint) {
        if (codePoint < 0x80) {
            buffer.put((byte) codePoint);
        } else if (codePoint < 0x800) {
            buffer.put((byte) (0xC0 | (codePoint >> 6)));
            buffer.put((byte) (0x80 | (codePoint & 0x3F)));
        } else if (codePoint < 0x10000) {
            buffer.put((byte) (0xE0 | (codePoint >> 12)));
            buffer.put((byte) (0x80 | ((codePoint >> 6) & 0x3F)));
            buffer.put((byte) (0x80 | (codePoint & 0x3F)));
        } else {
            buffer.put((byte) (0xF0 | (codePoint >> 18)));
            buffer.put((byte) (0x80 | ((codePoint >> 12) & 0x3F)));
            buffer.put((byte) (0x80 | ((codePoint >> 6) & 0x3F)));
            buffer.put((byte) (0x80 | (codePoint & 0x3F)));
        }
    }

    private void ensureCapacity(int needed) {
        if (buffer.remaining() < needed) {
            growBuffer(Math.max(buffer.capacity() * 2, buffer.position() + needed));
        }
    }

    private void growBuffer(int newCapacity) {
        ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
        buffer.flip();
        newBuffer.put(buffer);
        buffer = newBuffer;
    }

    private void sendIfNeeded() throws IOException {
        if (buffer.position() >= sendThreshold) {
            send();
        }
    }

    private void send() throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        buffer.clear();
    }

    /**
     * Adapter that wraps an OutputStream as a WritableByteChannel.
     */
    static class OutputStreamChannel implements WritableByteChannel {
        
        private final OutputStream out;
        private boolean open = true;

        OutputStreamChannel(OutputStream out) {
            this.out = out;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (!open) {
                throw new IOException("Channel is closed");
            }
            int written = src.remaining();
            if (src.hasArray()) {
                out.write(src.array(), src.arrayOffset() + src.position(), written);
                src.position(src.limit());
            } else {
                while (src.hasRemaining()) {
                    out.write(src.get());
                }
            }
            return written;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            if (open) {
                open = false;
                out.close();
            }
        }
    }
}

