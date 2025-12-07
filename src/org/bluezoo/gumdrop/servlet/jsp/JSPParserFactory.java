/*
 * JSPParserFactory.java
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

package org.bluezoo.gumdrop.servlet.jsp;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.SAXParserFactory;

/**
 * Factory for creating JSP parsers and automatically detecting JSP format.
 * 
 * <p>This factory provides convenient methods to parse JSP files without
 * needing to know whether they use traditional JSP syntax or XML format.
 * It automatically detects the format and chooses the appropriate parser.
 * 
 * <p>Usage example:
 * <pre>
 * try (InputStream input = new FileInputStream("example.jsp")) {
 *   JSPPage page = JSPParserFactory.parseJSP(input, "UTF-8", "/example.jsp");
 *   // Process the parsed JSP page...
 * }
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPParserFactory {
    
    private static final Logger LOGGER = Logger.getLogger(JSPParserFactory.class.getName());
    
    // Working SAX parser factory from deployment descriptor parsing
    private final SAXParserFactory saxParserFactory;
    
    // Registry of available parsers  
    private final List<JSPParser> availableParsers = new ArrayList<>();
    
    /**
     * Creates a JSP parser factory with a working SAX parser factory.
     * 
     * @param saxParserFactory the SAX parser factory that successfully parsed web.xml (may be null)
     */
    public JSPParserFactory(SAXParserFactory saxParserFactory) {
        this.saxParserFactory = saxParserFactory;
        
        // Register default parsers with injected SAX factory
        availableParsers.add(new XMLJSPParser(saxParserFactory));
        availableParsers.add(new TraditionalJSPParser());
    }
    
    /**
     * Parses a JSP file, automatically detecting the format and choosing
     * the appropriate parser.
     * 
     * @param input the input stream containing JSP source bytes
     * @param encoding the character encoding of the JSP source (e.g., "UTF-8")
     * @param jspUri the URI/path of the JSP file being parsed (for error reporting)
     * @return a parsed JSP page
     * @throws IOException if an I/O error occurs while reading
     * @throws JSPParseException if parsing fails
     * @throws UnsupportedOperationException if no suitable parser is found
     */
    public JSPPage parseJSP(InputStream input, String encoding, String jspUri) 
            throws IOException, JSPParseException {
        
        // Ensure the input stream supports mark/reset for format detection
        if (!input.markSupported()) {
            input = new BufferedInputStream(input);
        }
        
        JSPParser parser = detectParser(input, encoding);
        if (parser == null) {
            throw new UnsupportedOperationException(
                "No suitable JSP parser found for: " + jspUri);
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Using " + parser.getParserName() + " for: " + jspUri);
        }
        
        return parser.parse(input, encoding, jspUri);
    }
    
    /**
     * Parses a JSP file using the specified resolved JSP properties.
     * This method uses the JSP configuration to determine parsing behavior,
     * including whether to force XML parsing via the isXml property.
     * 
     * @param input the input stream containing JSP content
     * @param encoding the character encoding to use
     * @param jspUri the URI of the JSP file (for error reporting)
     * @param jspProperties the resolved JSP properties from configuration
     * @return the parsed JSP page
     * @throws IOException if an I/O error occurs
     * @throws JSPParseException if the JSP content is malformed
     */
    public JSPPage parseJSP(InputStream input, String encoding, String jspUri, 
                                  JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties)
            throws IOException, JSPParseException {
        
        // Ensure the input stream supports mark/reset for format detection
        if (!input.markSupported()) {
            input = new BufferedInputStream(input);
        }
        
        JSPParser parser;
        
        // Check if isXml property forces XML parsing
        if (jspProperties != null && jspProperties.getIsXml() != null && jspProperties.getIsXml()) {
            // Force XML parsing with SAX factory if available
            parser = createParser(ParserType.XML);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Forcing XML parser due to isXml=true for: " + jspUri);
            }
        } else {
            // Use automatic detection
            parser = detectParser(input, encoding);
            if (parser == null) {
                throw new UnsupportedOperationException(
                    "No suitable JSP parser found for: " + jspUri);
            }
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Using " + parser.getParserName() + " for: " + jspUri);
        }
        
        // Use standard JSPParser interface - SAX factory already injected into XML parser
        return parser.parse(input, encoding, jspUri, jspProperties);
    }

    
    /**
     * Creates a specific parser by type.
     * 
     * @param parserType the type of parser to create
     * @return a new parser instance
     * @throws IllegalArgumentException if the parser type is unknown
     */
    public JSPParser createParser(ParserType parserType) {
        switch (parserType) {
            case XML:
                return new XMLJSPParser(saxParserFactory);
            case TRADITIONAL:
                return new TraditionalJSPParser();
            default:
                throw new IllegalArgumentException("Unknown parser type: " + parserType);
        }
    }
    
    /**
     * Detects the JSP format and returns an appropriate parser.
     * 
     * @param input the input stream (must support mark/reset)
     * @param encoding the character encoding
     * @return a suitable parser, or null if none found
     * @throws IOException if format detection fails
     */
    private JSPParser detectParser(InputStream input, String encoding) throws IOException {
        
        for (JSPParser parser : availableParsers) {
            try {
                if (parser.canParse(input, encoding)) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine("Detected JSP format: " + parser.getParserName());
                    }
                    return parser;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, 
                    "Error during format detection with " + parser.getParserName(), e);
                // Continue with next parser
            }
        }
        
        return null;
    }
    
    /**
     * Registers a custom JSP parser.
     * 
     * @param parser the parser to register
     */
    public void registerParser(JSPParser parser) {
        if (parser != null && !availableParsers.contains(parser)) {
            availableParsers.add(0, parser); // Add at beginning for priority
        }
    }
    
    /**
     * Unregisters a JSP parser.
     * 
     * @param parser the parser to unregister
     */
    public void unregisterParser(JSPParser parser) {
        availableParsers.remove(parser);
    }
    
    /**
     * Gets a list of all registered parsers.
     * 
     * @return a list of available parsers
     */
    public List<JSPParser> getAvailableParsers() {
        return new ArrayList<>(availableParsers);
    }
    
    /**
     * Checks if a JSP file appears to be in XML format based on filename.
     * 
     * @param filename the JSP filename
     * @return true if the filename suggests XML format (e.g., .jspx)
     */
    public static boolean isXmlFormat(String filename) {
        if (filename == null) {
            return false;
        }
        
        String lowercaseName = filename.toLowerCase();
        return lowercaseName.endsWith(".jspx") || 
               lowercaseName.endsWith(".jsp.xml") ||
               lowercaseName.contains(".jspx.");
    }
    
    /**
     * Determines the default encoding to use for JSP files.
     * 
     * @param contentType the content type from page directive, or null
     * @return the encoding to use (defaults to UTF-8)
     */
    public static String determineEncoding(String contentType) {
        if (contentType == null) {
            return "UTF-8";
        }
        
        // Look for charset in content type
        String[] parts = contentType.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("charset=")) {
                return part.substring(8).trim();
            }
        }
        
        return "UTF-8";
    }
    
    /**
     * Enumeration of available parser types.
     */
    public enum ParserType {
        /** XML format parser (JSPX) */
        XML,
        /** Traditional JSP syntax parser */
        TRADITIONAL
    }
}
