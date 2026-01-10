/*
 * Parser.java
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
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;

/**
 * Gonzalez streaming XML parser.
 *
 * <p>This class provides the public interface to the Gonzalez parser. As a
 * SAX2 XMLReader, it allows it to be used with existing SAX2 frameworks and
 * applications. 
 * Internally, it uses a streaming pipeline (tokenizer and syntax parser) to
 * perform the parsing.
 *
 * <p>The same Parser instance to be reused for multiple documents by calling
 * {@link #reset()} between parses.
 *
 * <p>The parser supports:
 * <ul>
 *   <li>Standard SAX2 handlers: ContentHandler, DTDHandler, ErrorHandler</li>
 *   <li>SAX2 extension handlers: LexicalHandler</li>
 *   <li>Low-level byte buffer API for streaming</li>
 *   <li>Automatic charset detection (BOM and XML declaration)</li>
 *   <li>Line-end normalization</li>
 *   <li>Legacy blocking parsing from InputStream</li>
 *   <li>Parser reuse via {@link #reset()}</li>
 *   <li>Standard SAX2 Locator2 interface for reporting</li>
 * </ul>
 *
 * <p><b>Streaming usage:</b>
 * <pre>
 * Parser parser = new Parser();
 * parser.setContentHandler(myContentHandler);
 * parser.setSystemId("http://example.com/doc.xml");
 * 
 * // Feed data in chunks
 * ByteBuffer buffer = ByteBuffer.wrap(data);
 * parser.receive(buffer); // repeat while data is available
 * 
 * // Signal end of document
 * parser.close();
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class Parser implements XMLReader {

    /**
     * The tokenizer that converts characters to tokens.
     * Initialized in post-XMLDecl state (PROLOG_BEFORE_DOCTYPE).
     */
    private final Tokenizer tokenizer;
    
    /**
     * The external entity decoder that converts bytes to characters.
     * The decoder handles XMLDecl parsing before passing content to the tokenizer.
     */
    private final ExternalEntityDecoder decoder;
    
    /**
     * The parser that converts tokens to SAX events.
     */
    private final ContentParser xmlParser;

    /**
     * Set to true to enable debugging output.
     */
    static final boolean debug = false;

    /**
     * Creates a new Parser instance.
     * The tokenizer is initialized in PROLOG_BEFORE_DOCTYPE state (post-XMLDecl).
     * The decoder handles XMLDecl parsing before feeding content to the tokenizer.
     */
    public Parser() {
        xmlParser = new ContentParser();
        TokenConsumer consumer = debug ? new DebugTokenConsumer(xmlParser, "(document)") : xmlParser;
        tokenizer = new Tokenizer(null, consumer, TokenizerState.PROLOG_BEFORE_DOCTYPE, false);
        decoder = new ExternalEntityDecoder(tokenizer, null, null, false); // Document entity
    }

    // ========================================================================
    // XMLReader Interface Implementation
    // ========================================================================

    /**
     * Parses an XML document from an InputSource.
     *
     * <p>This is the standard SAX2 parsing method. It reads data from the
     * InputSource's byte stream in chunks and feeds them to the streaming
     * parser. This method blocks until the entire document has been parsed.
     *
     * <p>For non-blocking parsing, use the {@link #receive(ByteBuffer)} and
     * {@link #close()} methods instead.
     *
     * <p>If the InputSource specifies a public ID, system ID, or encoding,
     * these will be used by the parser. The encoding can be overridden by
     * a BOM or XML declaration in the document itself.
     *
     * @param input the InputSource containing the document to parse
     * @throws IOException if an I/O error occurs reading from the stream
     * @throws SAXException if a parsing error occurs
     * @throws IllegalArgumentException if input is null
     */
    @Override
    public void parse(InputSource input) throws IOException, SAXException {
        if (input == null) {
            throw new IllegalArgumentException("InputSource cannot be null");
        }

        // Set identifiers from InputSource if available
        if (input.getPublicId() != null) {
            setPublicId(input.getPublicId());
        }
        if (input.getSystemId() != null) {
            setSystemId(input.getSystemId());
        }
        
        // Set initial encoding from InputSource if specified
        if (input.getEncoding() != null) {
            try {
                java.nio.charset.Charset charset = java.nio.charset.Charset.forName(input.getEncoding());
                decoder.setInitialCharset(charset);
            } catch (java.nio.charset.IllegalCharsetNameException | java.nio.charset.UnsupportedCharsetException e) {
                // Invalid encoding name - ignore and use default (UTF-8)
                // The XML declaration will override if needed
            }
        }

        // Get the input stream
        InputStream inputStream = input.getByteStream();
        if (inputStream == null) {
            // Try character stream - would need conversion (not implemented yet)
            throw new SAXException("InputSource must have a byte stream");
        }

        // Bridge pattern: read from InputStream and feed to decoder
        // Uses standard NIO buffer management: read, flip, receive, compact
        ByteBuffer byteBuffer = ByteBuffer.allocate(4096);
        byte[] array = byteBuffer.array();
        int bytesRead;
        
        // Buffer is in write mode: position indicates end of any unprocessed data
        while (true) {
            // Read into the buffer's backing array starting at current position
            bytesRead = inputStream.read(array, byteBuffer.position(), byteBuffer.remaining());
            
            if (bytesRead > 0) {
                // Advance position to account for bytes read
                byteBuffer.position(byteBuffer.position() + bytesRead);
            }
            
            // If we have data in buffer, process it
            if (byteBuffer.position() > 0) {
                byteBuffer.flip();  // Switch to read mode
                receive(byteBuffer);
                byteBuffer.compact();  // Compact unprocessed bytes for next cycle
            }
            
            // Exit loop on EOF when no remaining data
            if (bytesRead == -1 && byteBuffer.position() == 0) {
                break;
            }
        }
        
        // Signal end of document
        close();
    }

    /**
     * Parses an XML document identified by a system ID (URI).
     *
     * <p>This method resolves the system ID to an InputSource using the
     * configured {@link EntityResolver}, or a default resolver if none
     * is set. The resolved InputSource is then parsed using
     * {@link #parse(InputSource)}.
     *
     * <p>The system ID is typically a URL (http:, https:, file:) or a
     * relative path that will be resolved against the current working
     * directory.
     *
     * @param systemId the system identifier (URI) of the document to parse
     * @throws IOException if an I/O error occurs
     * @throws SAXException if a parsing error occurs or the URI cannot be resolved
     * @throws IllegalArgumentException if systemId is null
     */
    @Override
    public void parse(String systemId) throws IOException, SAXException {
        if (systemId == null) {
            throw new IllegalArgumentException("System ID cannot be null");
        }
        
        // Try to use EntityResolver to resolve the systemId
        EntityResolver resolver = xmlParser.getEntityResolver();
        if (resolver != null) {
            InputSource source = resolver.resolveEntity(null, systemId);
            if (source != null) {
                // EntityResolver provided an InputSource, use it
                parse(source);
                return;
            }
        }
        
        // No user-specified EntityResolver or it returned null
        // Use default entity resolver
        try {
            DefaultEntityResolver defaultResolver = new DefaultEntityResolver();
            InputSource source = defaultResolver.resolveEntity(null, systemId);
            if (source != null) {
                parse(source);
                return;
            }
        } catch (SAXException e) {
            // Default resolver failed, throw the exception
            throw e;
        }
        
        // This should not happen (default resolver should always return something)
        throw new SAXException("Could not resolve system ID: " + systemId);
    }

    /**
     * Returns the current content handler.
     *
     * @return the current ContentHandler, or null if none has been set
     * @see #setContentHandler(ContentHandler)
     */
    @Override
    public ContentHandler getContentHandler() {
        return xmlParser.getContentHandler();
    }

    /**
     * Sets the content handler to receive document events.
     *
     * <p>The content handler receives callbacks for document structure events
     * such as {@code startDocument}, {@code startElement}, {@code characters},
     * {@code endElement}, and {@code endDocument}.
     *
     * @param handler the content handler, or null to remove the current handler
     * @see ContentHandler
     */
    @Override
    public void setContentHandler(ContentHandler handler) {
        xmlParser.setContentHandler(handler);
    }

    /**
     * Returns the current DTD handler.
     *
     * @return the current DTDHandler, or null if none has been set
     * @see #setDTDHandler(DTDHandler)
     */
    @Override
    public DTDHandler getDTDHandler() {
        return xmlParser.getDTDHandler();
    }

    /**
     * Sets the DTD handler to receive DTD events.
     *
     * <p>The DTD handler receives callbacks for notation declarations and
     * unparsed entity declarations from the DTD.
     *
     * @param handler the DTD handler, or null to remove the current handler
     * @see DTDHandler
     */
    @Override
    public void setDTDHandler(DTDHandler handler) {
        xmlParser.setDTDHandler(handler);
    }

    /**
     * Returns the current error handler.
     *
     * @return the current ErrorHandler, or null if none has been set
     * @see #setErrorHandler(ErrorHandler)
     */
    @Override
    public ErrorHandler getErrorHandler() {
        return xmlParser.getErrorHandler();
    }

    /**
     * Sets the error handler to receive parsing errors.
     *
     * <p>The error handler receives callbacks for warnings, recoverable errors,
     * and fatal errors. If no error handler is set, fatal errors will still
     * throw SAXException.
     *
     * @param handler the error handler, or null to remove the current handler
     * @see ErrorHandler
     */
    @Override
    public void setErrorHandler(ErrorHandler handler) {
        xmlParser.setErrorHandler(handler);
    }

    /**
     * Returns the current entity resolver.
     *
     * @return the current EntityResolver, or null if none has been set
     * @see #setEntityResolver(EntityResolver)
     */
    @Override
    public EntityResolver getEntityResolver() {
        return xmlParser.getEntityResolver();
    }

    /**
     * Sets the entity resolver for resolving external entities.
     *
     * <p>The entity resolver is called to resolve external entities referenced
     * in the document, including the external DTD subset and external parsed
     * entities. The resolver can provide alternative input sources or redirect
     * resolution to different locations.
     *
     * <p>Gonzalez also supports {@link org.xml.sax.ext.EntityResolver2} for
     * enhanced resolution capabilities.
     *
     * @param resolver the entity resolver, or null to use the default resolver
     * @see EntityResolver
     * @see org.xml.sax.ext.EntityResolver2
     */
    @Override
    public void setEntityResolver(EntityResolver resolver) {
        xmlParser.setEntityResolver(resolver);
    }

    /**
     * Returns the value of a parser feature.
     *
     * <p>Gonzalez supports the following SAX2 features:
     *
     * <table class="striped">
     * <caption>Supported SAX2 features</caption>
     * <thead>
     * <tr><th>Feature URI</th><th>Default</th><th>Description</th></tr>
     * </thead>
     * <tbody>
     * <tr><td>{@code http://xml.org/sax/features/namespaces}</td>
     *     <td>true</td><td>Perform namespace processing</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/namespace-prefixes}</td>
     *     <td>false</td><td>Report xmlns attributes</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/validation}</td>
     *     <td>false</td><td>Validate against DTD</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/external-general-entities}</td>
     *     <td>true</td><td>Include external general entities</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/external-parameter-entities}</td>
     *     <td>true</td><td>Include external parameter entities</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/string-interning}</td>
     *     <td>true</td><td>Intern element/attribute names</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/is-standalone}</td>
     *     <td>false</td><td>(Read-only) Document standalone status</td></tr>
     * <tr><td>{@code http://xml.org/sax/features/xml-1.1}</td>
     *     <td>true</td><td>(Read-only) XML 1.1 support</td></tr>
     * </tbody>
     * </table>
     *
     * @param name the feature URI
     * @return the current value of the feature (true or false)
     * @throws SAXNotRecognizedException if the feature is not recognized
     * @throws SAXNotSupportedException if the feature cannot be read
     * @throws NullPointerException if name is null
     */
    @Override
    public boolean getFeature(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
        if (name == null) {
            throw new NullPointerException("Feature name cannot be null");
        }
        
        switch (name) {
            // Delegate is-standalone to tokenizer (it owns XML declaration info)
            case "http://xml.org/sax/features/is-standalone":
                return tokenizer.standalone;
                
            // Mutable features (delegate to ContentParser)
            case "http://xml.org/sax/features/namespaces":
                return xmlParser.getNamespacesEnabled();
                
            case "http://xml.org/sax/features/namespace-prefixes":
                return xmlParser.getNamespacePrefixesEnabled();
                
            case "http://xml.org/sax/features/validation":
                return xmlParser.getValidationEnabled();
                
            case "http://xml.org/sax/features/external-general-entities":
                return xmlParser.getExternalGeneralEntitiesEnabled();
                
            case "http://xml.org/sax/features/external-parameter-entities":
                return xmlParser.getExternalParameterEntitiesEnabled();
                
            case "http://xml.org/sax/features/resolve-dtd-uris":
                return xmlParser.getResolveDTDURIsEnabled();
                
            case "http://xml.org/sax/features/string-interning":
                return xmlParser.getStringInterning();
                
            // Read-only features (report capabilities)
            case "http://xml.org/sax/features/lexical-handler":
                return true; // LexicalHandler interface is supported
                
            case "http://xml.org/sax/features/parameter-entities":
                return true; // Parameter entity events are reported
                
            case "http://xml.org/sax/features/use-attributes2":
                return true; // SAXAttributes implements Attributes2
                
            case "http://xml.org/sax/features/use-locator2":
                return true; // Tokenizer implements Locator2
                
            case "http://xml.org/sax/features/use-entity-resolver2":
                return true; // EntityResolver2 is supported
                
            case "http://xml.org/sax/features/xmlns-uris":
                return false; // xmlns attributes have no special namespace URI
                
            case "http://xml.org/sax/features/xml-1.1":
                return true; // XML 1.1 is supported
                
            // Unsupported features
            case "http://xml.org/sax/features/unicode-normalization-checking":
                return false; // Not supported
                
            default:
                throw new SAXNotRecognizedException("Feature not recognized: " + name);
        }
    }

    /**
     * Sets the value of a parser feature.
     *
     * <p>See {@link #getFeature(String)} for the list of supported features.
     * Some features are read-only and cannot be changed.
     *
     * <p>Features should be set before parsing begins. Setting features
     * during parsing may have undefined behavior.
     *
     * @param name the feature URI
     * @param value the new value for the feature
     * @throws SAXNotRecognizedException if the feature is not recognized
     * @throws SAXNotSupportedException if the feature cannot be set to the
     *         specified value (e.g., read-only features)
     * @throws NullPointerException if name is null
     */
    @Override
    public void setFeature(String name, boolean value) throws SAXNotRecognizedException, SAXNotSupportedException {
        if (name == null) {
            throw new NullPointerException("Feature name cannot be null");
        }
        
        switch (name) {
            // Mutable features (delegate to ContentParser)
            case "http://xml.org/sax/features/namespaces":
                xmlParser.setNamespacesEnabled(value);
                break;
                
            case "http://xml.org/sax/features/namespace-prefixes":
                xmlParser.setNamespacePrefixesEnabled(value);
                break;
                
            case "http://xml.org/sax/features/validation":
                xmlParser.setValidationEnabled(value);
                break;
                
            case "http://xml.org/sax/features/external-general-entities":
                xmlParser.setExternalGeneralEntitiesEnabled(value);
                break;
                
            case "http://xml.org/sax/features/external-parameter-entities":
                xmlParser.setExternalParameterEntitiesEnabled(value);
                break;
                
            case "http://xml.org/sax/features/resolve-dtd-uris":
                xmlParser.setResolveDTDURIsEnabled(value);
                break;
                
            case "http://xml.org/sax/features/string-interning":
                xmlParser.setStringInterning(value);
                break;
                
            // Read-only features (throw exception if trying to change)
            case "http://xml.org/sax/features/is-standalone":
            case "http://xml.org/sax/features/lexical-handler":
            case "http://xml.org/sax/features/parameter-entities":
            case "http://xml.org/sax/features/use-attributes2":
            case "http://xml.org/sax/features/use-locator2":
            case "http://xml.org/sax/features/use-entity-resolver2":
            case "http://xml.org/sax/features/xmlns-uris":
            case "http://xml.org/sax/features/xml-1.1":
                throw new SAXNotSupportedException(
                    "Feature is read-only: " + name + " (current value: " + getFeature(name) + ")");
                
            // Unsupported features
            case "http://xml.org/sax/features/unicode-normalization-checking":
                if (value) {
                    throw new SAXNotSupportedException("Feature not supported: " + name);
                }
                // Allow setting to false (no-op, already false)
                break;
                
            default:
                throw new SAXNotRecognizedException("Feature not recognized: " + name);
        }
    }

    /**
     * Returns the value of a parser property.
     *
     * <p>Gonzalez supports the following properties:
     *
     * <table class="striped">
     * <caption>Supported properties</caption>
     * <thead>
     * <tr><th>Property URI</th><th>Type</th><th>Description</th></tr>
     * </thead>
     * <tbody>
     * <tr><td>{@code http://xml.org/sax/properties/lexical-handler}</td>
     *     <td>{@link org.xml.sax.ext.LexicalHandler}</td>
     *     <td>Handler for lexical events (comments, CDATA, entity boundaries)</td></tr>
     * <tr><td>{@code http://www.nongnu.org/gonzalez/properties/dtd-parser}</td>
     *     <td>{@link DTDParser}</td>
     *     <td>(Read-only) The DTD parser instance, if DOCTYPE was encountered</td></tr>
     * </tbody>
     * </table>
     *
     * @param name the property URI
     * @return the current value of the property
     * @throws SAXNotRecognizedException if the property is not recognized
     * @throws SAXNotSupportedException if the property cannot be read
     */
    @Override
    public Object getProperty(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
        // SAX2 extension properties
        if ("http://xml.org/sax/properties/lexical-handler".equals(name)) {
            return xmlParser.getLexicalHandler();
        }
        // Gonzalez-specific properties
        if ("http://www.nongnu.org/gonzalez/properties/dtd-parser".equals(name)) {
            return xmlParser.getDTDParser();
        }
        throw new SAXNotRecognizedException("Property not recognized: " + name);
    }

    /**
     * Sets the value of a parser property.
     *
     * <p>See {@link #getProperty(String)} for the list of supported properties.
     *
     * @param name the property URI
     * @param value the new value for the property
     * @throws SAXNotRecognizedException if the property is not recognized
     * @throws SAXNotSupportedException if the property cannot be set to the
     *         specified value
     */
    @Override
    public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException {
        // SAX2 extension properties
        if ("http://xml.org/sax/properties/lexical-handler".equals(name)) {
            if (value instanceof LexicalHandler) {
                xmlParser.setLexicalHandler((LexicalHandler) value);
            } else {
                throw new SAXNotSupportedException("Value must be a LexicalHandler");
            }
        } else {
            throw new SAXNotRecognizedException("Property not recognized: " + name);
        }
    }

    // ========================================================================
    // Parser Reuse
    // ========================================================================

    /**
     * Resets the parser state to allow reuse for parsing another document.
     *
     * <p>This method clears all parsing state from the tokenizer and XML parser,
     * allowing the same Parser instance to be reused for multiple documents.
     * Handler references (ContentHandler, DTDHandler, etc.) are preserved.
     *
     * <p><b>Usage:</b>
     * <pre>
     * Parser parser = new Parser();
     * parser.setContentHandler(handler);
     *
     * // Parse first document
     * parser.parse(new InputSource(stream1));
     *
     * // Reset for reuse
     * parser.reset();
     *
     * // Parse second document with same handlers
     * parser.parse(new InputSource(stream2));
     * </pre>
     *
     * <p><b>Note:</b> You should call this method after a parse completes
     * and before starting a new parse. It is not necessary to call reset()
     * before the first parse.
     *
     * @throws SAXException if reset fails
     */
    public void reset() throws SAXException {
        decoder.reset();
        tokenizer.reset();
        xmlParser.reset();
    }

    // ========================================================================
    // Direct Tokenizer API (Advanced Usage)
    // ========================================================================

    /**
     * Sets the public identifier for the document.
     *
     * <p>The public identifier is used for error reporting and may be used
     * by entity resolvers. This delegates to the external entity decoder
     * which provides location information.
     *
     * <p>If parsing via {@link #parse(InputSource)}, the public ID from the
     * InputSource takes precedence over this setting.
     *
     * @param publicId the public identifier, or null if not available
     */
    public void setPublicId(String publicId) {
        tokenizer.publicId = publicId;
    }

    /**
     * Sets the system identifier for the document.
     *
     * <p>The system identifier (typically a URL) is used for resolving
     * relative URIs and for error reporting. This delegates to the external
     * entity decoder which provides location information.
     *
     * <p>If parsing via {@link #parse(InputSource)}, the system ID from the
     * InputSource takes precedence over this setting.
     *
     * @param systemId the system identifier, or null if not available
     */
    public void setSystemId(String systemId) {
        tokenizer.systemId = systemId;
    }

    /**
     * Receives raw byte data for parsing.
     *
     * <p>This is an advanced API that allows streaming data to the parser
     * in chunks without using an InputStream. The data buffer should be
     * prepared for reading (position set to start of data, limit set to
     * end of data). This delegates directly to the underlying tokenizer.
     *
     * <p>Multiple invocations of this method may occur to supply the
     * complete document content. The parser will handle buffer underflow
     * conditions automatically, requesting more data as needed.
     *
     * <p><b>Usage:</b>
     * <pre>
     * Parser parser = new Parser();
     * parser.setContentHandler(handler);
     * parser.setSystemId("http://example.com/doc.xml");
     *
     * ByteBuffer data1 = ByteBuffer.wrap(bytes1);
     * parser.receive(data1);
     *
     * ByteBuffer data2 = ByteBuffer.wrap(bytes2);
     * parser.receive(data2);
     *
     * parser.close(); // Signal end of document
     * </pre>
     *
     * <p><b>Important:</b> You must call {@link #close()} after the last
     * receive to signal end of document and allow the parser to finish
     * processing.
     *
     * @param data a byte buffer ready for reading (position at start, limit at end)
     * @throws SAXException if a parsing error occurs
     * @throws IllegalStateException if called after {@link #close()}
     */
    public void receive(ByteBuffer data) throws SAXException {
        decoder.receive(data);
    }

    /**
     * Signals that all data has been received and completes parsing.
     *
     * <p>This method must be called after the last {@link #receive} call
     * to signal the end of the document. The parser will process any
     * remaining buffered data and generate final SAX events (such as
     * endDocument). This delegates to the decoder which will flush to
     * the tokenizer.
     *
     * <p>After this method is called, you can call {@link #reset()} to
     * prepare the parser for reuse with another document.
     *
     * <p>If there is incomplete data (e.g., an unclosed element), this
     * method will throw a SAXException.
     *
     * @throws SAXException if there is incomplete or invalid data
     * @throws IllegalStateException if called without prior {@link #receive}
     */
    public void close() throws SAXException {
        decoder.close();
        // Validate that parsing is complete (no unclosed constructs)
        xmlParser.close();
    }

    /**
     * Gets the public identifier from the decoder.
     *
     * @return the public identifier, or null if not set
     */
    public String getPublicId() {
        return tokenizer.publicId;
    }

    /**
     * Gets the system identifier from the decoder.
     *
     * @return the system identifier, or null if not set
     */
    public String getSystemId() {
        return tokenizer.systemId;
    }

    /**
     * Command-line entry point for testing the parser.
     *
     * <p>Parses XML files specified as command-line arguments using a
     * default (no-op) content handler. Useful for testing well-formedness
     * of XML documents.
     *
     * <p>Usage: {@code java org.bluezoo.gonzalez.Parser file1.xml [file2.xml ...]}
     *
     * @param args paths to XML files to parse
     * @throws Exception if parsing fails
     */
    public static void main(String[] args) throws Exception {
        Parser parser = new Parser();
        parser.setContentHandler(new org.xml.sax.helpers.DefaultHandler());
        for (String arg : args) {
            java.io.File file = new java.io.File(arg);
            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                InputSource src = new InputSource(in);
                src.setSystemId(file.toURI().toString());
                parser.parse(src);
            }
        }
    }

}

