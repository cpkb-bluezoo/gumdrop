/*
 * GonzalezSAXParserFactory.java
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

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

/**
 * JAXP SAXParserFactory implementation for the Gonzalez XML parser.
 *
 * <p>This factory creates {@link GonzalezSAXParser} instances that wrap the
 * Gonzalez {@link Parser}. It can be used via the standard JAXP discovery
 * mechanism or instantiated directly.
 *
 * <h2>Usage via JAXP Discovery</h2>
 *
 * <p>Set the system property to use Gonzalez as the default SAX parser:
 * <pre>{@code
 * System.setProperty("javax.xml.parsers.SAXParserFactory",
 *     "org.bluezoo.gonzalez.GonzalezSAXParserFactory");
 * SAXParserFactory factory = SAXParserFactory.newInstance();
 * }</pre>
 *
 * <h2>Direct Instantiation</h2>
 *
 * <pre>{@code
 * SAXParserFactory factory = new GonzalezSAXParserFactory();
 * factory.setNamespaceAware(true);
 * SAXParser parser = factory.newSAXParser();
 * parser.parse(inputStream, myHandler);
 * }</pre>
 *
 * <h2>Explicit Factory Selection</h2>
 *
 * <pre>{@code
 * SAXParserFactory factory = SAXParserFactory.newInstance(
 *     "org.bluezoo.gonzalez.GonzalezSAXParserFactory", null);
 * }</pre>
 *
 * @author Chris Burdess
 * @see GonzalezSAXParser
 * @see Parser
 */
public class GonzalezSAXParserFactory extends SAXParserFactory {

    private boolean xincludeAware = false;

    /**
     * Creates a new GonzalezSAXParserFactory.
     *
     * <p>The factory is initially configured to be non-namespace-aware and
     * non-validating, consistent with JAXP defaults.
     */
    public GonzalezSAXParserFactory() {
        super();
    }

    /**
     * Creates a new SAXParser instance with the current configuration.
     *
     * @return a new GonzalezSAXParser
     * @throws ParserConfigurationException if the configuration is not supported
     * @throws SAXException if parser creation fails
     */
    @Override
    public SAXParser newSAXParser() throws ParserConfigurationException, SAXException {
        if (xincludeAware) {
            throw new ParserConfigurationException("XInclude is not supported");
        }
        return new GonzalezSAXParser(isNamespaceAware(), isValidating());
    }

    /**
     * Sets a feature on parsers created by this factory.
     *
     * <p>Supported features include:
     * <ul>
     *   <li>{@code http://xml.org/sax/features/namespaces}</li>
     *   <li>{@code http://xml.org/sax/features/namespace-prefixes}</li>
     *   <li>{@code http://xml.org/sax/features/validation}</li>
     *   <li>{@code http://xml.org/sax/features/external-general-entities}</li>
     *   <li>{@code http://xml.org/sax/features/external-parameter-entities}</li>
     * </ul>
     *
     * @param name the feature name
     * @param value the feature value
     * @throws ParserConfigurationException if the feature cannot be set
     * @throws SAXNotRecognizedException if the feature is not recognized
     * @throws SAXNotSupportedException if the feature value is not supported
     */
    @Override
    public void setFeature(String name, boolean value) 
            throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException {
        // Handle standard JAXP features via the parent class methods
        if ("http://xml.org/sax/features/namespaces".equals(name)) {
            setNamespaceAware(value);
        } else if ("http://xml.org/sax/features/validation".equals(name)) {
            setValidating(value);
        } else {
            // Validate feature by trying it on a temporary parser
            Parser testParser = new Parser();
            testParser.setFeature(name, value);
        }
    }

    /**
     * Gets the value of a feature.
     *
     * @param name the feature name
     * @return the feature value
     * @throws ParserConfigurationException if the feature cannot be read
     * @throws SAXNotRecognizedException if the feature is not recognized
     * @throws SAXNotSupportedException if the feature cannot be read
     */
    @Override
    public boolean getFeature(String name) 
            throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException {
        if ("http://xml.org/sax/features/namespaces".equals(name)) {
            return isNamespaceAware();
        } else if ("http://xml.org/sax/features/validation".equals(name)) {
            return isValidating();
        } else {
            // Query a temporary parser for other features
            Parser testParser = new Parser();
            return testParser.getFeature(name);
        }
    }

    /**
     * Sets whether parsers created by this factory are XInclude-aware.
     *
     * <p>Gonzalez does not support XInclude. Setting this to {@code true} will
     * cause {@link #newSAXParser()} to throw {@link ParserConfigurationException}.
     *
     * @param state true to enable XInclude (not supported)
     */
    @Override
    public void setXIncludeAware(boolean state) {
        this.xincludeAware = state;
    }

    /**
     * Returns whether parsers are XInclude-aware.
     *
     * @return false (XInclude is not supported)
     */
    @Override
    public boolean isXIncludeAware() {
        return xincludeAware;
    }

}

