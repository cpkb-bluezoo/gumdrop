/*
 * DeadPropertyParser.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.webdav;

import org.bluezoo.gonzalez.Parser;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * SAX handler for parsing dead property sidecar XML files.
 *
 * <p>Uses the Gonzalez streaming push parser
 * ({@link org.bluezoo.gonzalez.Parser}) with
 * {@code receive(ByteBuffer)} for non-blocking parsing.
 *
 * <p>Sidecar format:
 * <pre>{@code
 * <properties xmlns="urn:gumdrop:webdav-props">
 *   <property ns="http://example.com/ns" name="author" xml="false">
 *     John Doe
 *   </property>
 * </properties>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DeadPropertyStore
 */
final class DeadPropertyParser extends DefaultHandler {

    private final Parser parser;
    private final Map<String, DeadProperty> properties;
    private final StringBuilder textContent;

    private boolean inProperty;
    private String currentNS;
    private String currentName;
    private boolean currentIsXML;

    DeadPropertyParser() {
        this.parser = new Parser();
        this.parser.setContentHandler(this);
        try {
            this.parser.setFeature(
                    "http://xml.org/sax/features/namespaces", true);
        } catch (Exception e) {
            // Namespaces enabled by default
        }
        this.properties = new HashMap<String, DeadProperty>();
        this.textContent = new StringBuilder();
    }

    /**
     * Feeds data to the push parser.
     *
     * @param data the data buffer
     * @throws IOException on parse error
     */
    void receive(ByteBuffer data) throws IOException {
        try {
            parser.receive(data);
        } catch (SAXException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Signals end of input.
     *
     * @throws IOException on parse error
     */
    void close() throws IOException {
        try {
            parser.close();
        } catch (SAXException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Returns the parsed properties.
     *
     * @return the properties map
     */
    Map<String, DeadProperty> getProperties() {
        return properties;
    }

    // -- SAX ContentHandler --

    @Override
    public void startElement(String uri, String localName,
                             String qName, Attributes atts)
            throws SAXException {
        if (DeadPropertyStore.PROPS_NAMESPACE.equals(uri)
                && DeadPropertyStore.PROPS_ELEM_PROPERTY
                        .equals(localName)) {
            inProperty = true;
            currentNS = atts.getValue(DeadPropertyStore.PROPS_ATTR_NS);
            currentName = atts.getValue(
                    DeadPropertyStore.PROPS_ATTR_NAME);
            String xmlAttr = atts.getValue(
                    DeadPropertyStore.PROPS_ATTR_XML);
            currentIsXML = "true".equals(xmlAttr);
            textContent.setLength(0);
        }
    }

    @Override
    public void characters(char[] ch, int start, int length)
            throws SAXException {
        if (inProperty) {
            textContent.append(ch, start, length);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName)
            throws SAXException {
        if (inProperty
                && DeadPropertyStore.PROPS_NAMESPACE.equals(uri)
                && DeadPropertyStore.PROPS_ELEM_PROPERTY
                        .equals(localName)) {
            String value = textContent.toString();
            DeadProperty prop = new DeadProperty(
                    currentNS, currentName, value, currentIsXML);
            properties.put(prop.getKey(), prop);
            inProperty = false;
            currentNS = null;
            currentName = null;
            currentIsXML = false;
            textContent.setLength(0);
        }
    }

}
