/*
 * GonzalezSAXParser.java
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.parsers.SAXParser;
import org.xml.sax.HandlerBase;
import org.xml.sax.InputSource;
import org.xml.sax.Parser;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * JAXP SAXParser implementation that wraps the Gonzalez {@link org.bluezoo.gonzalez.Parser}.
 *
 * <p>This class provides JAXP compatibility, allowing Gonzalez to be used via the
 * standard {@link javax.xml.parsers.SAXParserFactory} mechanism. For direct usage
 * or non-blocking streaming, use {@link org.bluezoo.gonzalez.Parser} directly.
 *
 * <p>Example usage via JAXP:
 * <pre>{@code
 * SAXParserFactory factory = SAXParserFactory.newInstance(
 *     "org.bluezoo.gonzalez.GonzalezSAXParserFactory", null);
 * SAXParser parser = factory.newSAXParser();
 * parser.parse(inputStream, myHandler);
 * }</pre>
 *
 * @author Chris Burdess
 * @see GonzalezSAXParserFactory
 * @see org.bluezoo.gonzalez.Parser
 */
@SuppressWarnings("deprecation")
public class GonzalezSAXParser extends SAXParser {

    private final org.bluezoo.gonzalez.Parser parser;
    private final boolean namespaceAware;
    private final boolean validating;

    /**
     * Creates a new GonzalezSAXParser with the specified configuration.
     *
     * @param namespaceAware whether the parser should be namespace-aware
     * @param validating whether the parser should validate against DTD
     * @throws SAXException if configuration fails
     */
    GonzalezSAXParser(boolean namespaceAware, boolean validating) throws SAXException {
        this.parser = new org.bluezoo.gonzalez.Parser();
        this.namespaceAware = namespaceAware;
        this.validating = validating;
        
        // Configure parser features
        try {
            parser.setFeature("http://xml.org/sax/features/namespaces", namespaceAware);
            parser.setFeature("http://xml.org/sax/features/validation", validating);
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new SAXException("Failed to configure parser", e);
        }
    }

    /**
     * Returns the underlying Gonzalez XMLReader.
     *
     * @return the XMLReader instance
     */
    @Override
    public XMLReader getXMLReader() {
        return parser;
    }

    /**
     * Returns the SAX1 Parser interface.
     *
     * @return the SAX1 Parser
     * @throws SAXException always, as SAX1 is not supported
     * @deprecated SAX1 is deprecated; use {@link #getXMLReader()} instead
     */
    @Override
    @Deprecated
    public Parser getParser() throws SAXException {
        throw new SAXException("SAX1 Parser interface not supported; use getXMLReader()");
    }

    /**
     * Returns whether this parser is namespace-aware.
     *
     * @return true if namespace-aware, false otherwise
     */
    @Override
    public boolean isNamespaceAware() {
        return namespaceAware;
    }

    /**
     * Returns whether this parser validates documents.
     *
     * @return true if validating, false otherwise
     */
    @Override
    public boolean isValidating() {
        return validating;
    }

    /**
     * Sets a property on the underlying XMLReader.
     *
     * @param name the property name
     * @param value the property value
     * @throws SAXNotRecognizedException if the property is not recognized
     * @throws SAXNotSupportedException if the property cannot be set
     */
    @Override
    public void setProperty(String name, Object value) 
            throws SAXNotRecognizedException, SAXNotSupportedException {
        parser.setProperty(name, value);
    }

    /**
     * Gets a property from the underlying XMLReader.
     *
     * @param name the property name
     * @return the property value
     * @throws SAXNotRecognizedException if the property is not recognized
     * @throws SAXNotSupportedException if the property cannot be read
     */
    @Override
    public Object getProperty(String name) 
            throws SAXNotRecognizedException, SAXNotSupportedException {
        return parser.getProperty(name);
    }

    // ========================================================================
    // Parse methods with DefaultHandler
    // ========================================================================

    @Override
    public void parse(InputStream is, DefaultHandler dh) throws SAXException, IOException {
        parse(new InputSource(is), dh);
    }

    @Override
    public void parse(InputStream is, DefaultHandler dh, String systemId) 
            throws SAXException, IOException {
        InputSource source = new InputSource(is);
        source.setSystemId(systemId);
        parse(source, dh);
    }

    @Override
    public void parse(String uri, DefaultHandler dh) throws SAXException, IOException {
        InputSource source = new InputSource(uri);
        parse(source, dh);
    }

    @Override
    public void parse(File f, DefaultHandler dh) throws SAXException, IOException {
        try (InputStream is = new FileInputStream(f)) {
            InputSource source = new InputSource(is);
            source.setSystemId(f.toURI().toString());
            parse(source, dh);
        }
    }

    @Override
    public void parse(InputSource is, DefaultHandler dh) throws SAXException, IOException {
        if (dh != null) {
            parser.setContentHandler(dh);
            parser.setDTDHandler(dh);
            parser.setErrorHandler(dh);
            parser.setEntityResolver(dh);
        }
        try {
            parser.parse(is);
        } finally {
            parser.reset();
        }
    }

    // ========================================================================
    // Deprecated SAX1 parse methods with HandlerBase
    // ========================================================================

    @Override
    @Deprecated
    public void parse(InputStream is, HandlerBase hb) throws SAXException, IOException {
        parse(new InputSource(is), hb);
    }

    @Override
    @Deprecated
    public void parse(InputStream is, HandlerBase hb, String systemId) 
            throws SAXException, IOException {
        InputSource source = new InputSource(is);
        source.setSystemId(systemId);
        parse(source, hb);
    }

    @Override
    @Deprecated
    public void parse(String uri, HandlerBase hb) throws SAXException, IOException {
        InputSource source = new InputSource(uri);
        parse(source, hb);
    }

    @Override
    @Deprecated
    public void parse(File f, HandlerBase hb) throws SAXException, IOException {
        try (InputStream is = new FileInputStream(f)) {
            InputSource source = new InputSource(is);
            source.setSystemId(f.toURI().toString());
            parse(source, hb);
        }
    }

    @Override
    @Deprecated
    public void parse(InputSource is, HandlerBase hb) throws SAXException, IOException {
        throw new SAXException("SAX1 HandlerBase not supported; use DefaultHandler");
    }

}

