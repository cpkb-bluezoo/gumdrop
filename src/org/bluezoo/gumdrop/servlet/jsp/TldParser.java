/*
 * TldParser.java
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

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SAX-based parser for Tag Library Descriptor (TLD) files.
 * 
 * <p>This parser reads TLD XML files and creates {@link TagLibraryDescriptor} 
 * objects containing all tag definitions, attributes, and metadata. It supports
 * various TLD versions and handles the complete TLD specification including:
 * <ul>
 *   <li>Library metadata (version, URI, shortname)</li>
 *   <li>Tag definitions with attributes and variables</li>
 *   <li>EL function definitions</li>
 *   <li>Validator configuration</li>
 * </ul>
 * 
 * <p>Example TLD structure:
 * <pre>
 * &lt;taglib xmlns="http://java.sun.com/xml/ns/j2ee" version="2.0"&gt;
 *   &lt;tlib-version&gt;1.0&lt;/tlib-version&gt;
 *   &lt;short-name&gt;mylib&lt;/short-name&gt;
 *   &lt;uri&gt;http://example.com/tags&lt;/uri&gt;
 *   &lt;tag&gt;
 *     &lt;name&gt;hello&lt;/name&gt;
 *     &lt;tag-class&gt;com.example.HelloTag&lt;/tag-class&gt;
 *     &lt;body-content&gt;empty&lt;/body-content&gt;
 *     &lt;attribute&gt;
 *       &lt;name&gt;message&lt;/name&gt;
 *       &lt;required&gt;true&lt;/required&gt;
 *     &lt;/attribute&gt;
 *   &lt;/tag&gt;
 * &lt;/taglib&gt;
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TldParser {

    private static final Logger LOGGER = Logger.getLogger(TldParser.class.getName());

    /**
     * Parses a TLD file from an InputStream and returns a TagLibraryDescriptor.
     * 
     * @param input the InputStream containing the TLD XML
     * @param sourceLocation the source location for debugging purposes
     * @return the parsed TagLibraryDescriptor, or null if parsing fails
     * @throws IOException if an I/O error occurs
     */
    public static TagLibraryDescriptor parseTld(InputStream input, String sourceLocation) throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();

            TldHandler handler = new TldHandler(sourceLocation);
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);

            InputSource source = new InputSource(input);
            source.setSystemId(sourceLocation);
            reader.parse(source);

            TagLibraryDescriptor tld = handler.getTagLibraryDescriptor();
            if (tld != null) {
                tld.setSourceLocation(sourceLocation);
                LOGGER.fine("Successfully parsed TLD: " + sourceLocation);
            }

            return tld;

        } catch (ParserConfigurationException | SAXException e) {
            LOGGER.log(Level.WARNING, "Failed to parse TLD: " + sourceLocation, e);
            throw new IOException("TLD parsing error: " + e.getMessage(), e);
        }
    }

    /**
     * SAX content handler for parsing TLD XML files.
     */
    private static class TldHandler extends DefaultHandler {

        private final String sourceLocation;
        private final TagLibraryDescriptor tld = new TagLibraryDescriptor();
        private final Stack<String> elementStack = new Stack<>();
        private final StringBuilder textBuffer = new StringBuilder();

        // Current parsing context
        private TagLibraryDescriptor.TagDescriptor currentTag;
        private TagLibraryDescriptor.AttributeDescriptor currentAttribute;
        private TagLibraryDescriptor.VariableDescriptor currentVariable;
        private TagLibraryDescriptor.FunctionDescriptor currentFunction;

        public TldHandler(String sourceLocation) {
            this.sourceLocation = sourceLocation;
        }

        public TagLibraryDescriptor getTagLibraryDescriptor() {
            return tld;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            elementStack.push(qName);
            textBuffer.setLength(0);

            // Handle element-specific initialization
            switch (qName) {
                case "tag":
                    currentTag = new TagLibraryDescriptor.TagDescriptor();
                    break;
                case "attribute":
                    currentAttribute = new TagLibraryDescriptor.AttributeDescriptor();
                    break;
                case "variable":
                    currentVariable = new TagLibraryDescriptor.VariableDescriptor();
                    break;
                case "function":
                    currentFunction = new TagLibraryDescriptor.FunctionDescriptor();
                    break;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            String text = textBuffer.toString().trim();
            elementStack.pop();

            // Handle library-level elements
            if (isInContext("taglib")) {
                switch (qName) {
                    case "tlib-version":
                        tld.setTlibVersion(text);
                        break;
                    case "short-name":
                        tld.setShortName(text);
                        break;
                    case "uri":
                        tld.setUri(text);
                        break;
                    case "description":
                        if (currentTag == null && currentAttribute == null && currentVariable == null && currentFunction == null) {
                            tld.setDescription(text);
                        }
                        break;
                    case "display-name":
                        if (currentTag == null && currentAttribute == null && currentVariable == null && currentFunction == null) {
                            tld.setDisplayName(text);
                        }
                        break;
                    case "small-icon":
                        if (currentTag == null && currentAttribute == null && currentVariable == null && currentFunction == null) {
                            tld.setSmallIcon(text);
                        }
                        break;
                    case "large-icon":
                        if (currentTag == null && currentAttribute == null && currentVariable == null && currentFunction == null) {
                            tld.setLargeIcon(text);
                        }
                        break;
                    case "jsp-version":
                        tld.setJspVersion(text);
                        break;
                    case "validator-class":
                        tld.setValidatorClass(text);
                        break;
                    case "tag":
                        if (currentTag != null) {
                            tld.addTag(currentTag);
                            currentTag = null;
                        }
                        break;
                    case "function":
                        if (currentFunction != null) {
                            tld.addFunction(currentFunction);
                            currentFunction = null;
                        }
                        break;
                }
            }

            // Handle tag-level elements (but not when inside attribute or variable)
            if (currentTag != null && isInContext("tag") && 
                !isInContext("attribute") && !isInContext("variable")) {
                switch (qName) {
                    case "name":
                        currentTag.setName(text);
                        break;
                    case "tag-class":
                        currentTag.setTagClass(text);
                        break;
                    case "tei-class":
                        currentTag.setTeiClass(text);
                        break;
                    case "body-content":
                        currentTag.setBodyContent(text);
                        break;
                    case "description":
                        if (currentAttribute == null && currentVariable == null) {
                            currentTag.setDescription(text);
                        }
                        break;
                    case "display-name":
                        if (currentAttribute == null && currentVariable == null) {
                            currentTag.setDisplayName(text);
                        }
                        break;
                    case "small-icon":
                        if (currentAttribute == null && currentVariable == null) {
                            currentTag.setSmallIcon(text);
                        }
                        break;
                    case "large-icon":
                        if (currentAttribute == null && currentVariable == null) {
                            currentTag.setLargeIcon(text);
                        }
                        break;
                    case "dynamic-attributes":
                        currentTag.setDynamicAttributes("true".equalsIgnoreCase(text));
                        break;
                    case "attribute":
                        if (currentAttribute != null) {
                            currentTag.addAttribute(currentAttribute);
                            currentAttribute = null;
                        }
                        break;
                    case "variable":
                        if (currentVariable != null) {
                            currentTag.addVariable(currentVariable);
                            currentVariable = null;
                        }
                        break;
                }
            }

            // Handle attribute-level elements
            if (currentAttribute != null && isInContext("attribute")) {
                switch (qName) {
                    case "name":
                        currentAttribute.setName(text);
                        break;
                    case "required":
                        currentAttribute.setRequired("true".equalsIgnoreCase(text));
                        break;
                    case "rtexprvalue":
                        currentAttribute.setRtexprvalue("true".equalsIgnoreCase(text));
                        break;
                    case "type":
                        currentAttribute.setType(text);
                        break;
                    case "description":
                        currentAttribute.setDescription(text);
                        break;
                    case "fragment":
                        currentAttribute.setFragment("true".equalsIgnoreCase(text));
                        break;
                }
            }

            // Handle variable-level elements
            if (currentVariable != null && isInContext("variable")) {
                switch (qName) {
                    case "name-given":
                        currentVariable.setNameGiven(text);
                        break;
                    case "name-from-attribute":
                        currentVariable.setNameFromAttribute(text);
                        break;
                    case "variable-class":
                        currentVariable.setVariableClass(text);
                        break;
                    case "declare":
                        currentVariable.setDeclare("true".equalsIgnoreCase(text));
                        break;
                    case "scope":
                        currentVariable.setScope(text);
                        break;
                    case "description":
                        currentVariable.setDescription(text);
                        break;
                }
            }

            // Handle function-level elements
            if (currentFunction != null && isInContext("function")) {
                switch (qName) {
                    case "name":
                        currentFunction.setName(text);
                        break;
                    case "function-class":
                        currentFunction.setFunctionClass(text);
                        break;
                    case "function-signature":
                        currentFunction.setFunctionSignature(text);
                        break;
                    case "description":
                        currentFunction.setDescription(text);
                        break;
                }
            }

            textBuffer.setLength(0);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            textBuffer.append(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            // Ignore whitespace
        }

        /**
         * Checks if we're currently in the context of a specific element.
         */
        private boolean isInContext(String elementName) {
            return elementStack.contains(elementName);
        }

        @Override
        public void warning(org.xml.sax.SAXParseException e) throws SAXException {
            LOGGER.log(Level.WARNING, "TLD parsing warning in " + sourceLocation + " at line " + e.getLineNumber(), e);
        }

        @Override
        public void error(org.xml.sax.SAXParseException e) throws SAXException {
            LOGGER.log(Level.SEVERE, "TLD parsing error in " + sourceLocation + " at line " + e.getLineNumber(), e);
            throw e;
        }

        @Override
        public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
            LOGGER.log(Level.SEVERE, "TLD parsing fatal error in " + sourceLocation + " at line " + e.getLineNumber(), e);
            throw e;
        }
    }
}
