/*
 * XMLJSPParser.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet.jsp;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * SAX-based parser for JSP pages in XML format (JSPX).
 *
 * <p>This parser handles JSP pages that use XML syntax, where JSP elements
 * are represented as XML elements with specific namespaces. For example:
 * <pre>
 * &lt;jsp:root xmlns:jsp="http://java.sun.com/JSP/Page" version="2.0"&gt;
 *   &lt;jsp:directive.page contentType="text/html; charset=UTF-8"/&gt;
 *   &lt;html&gt;
 *     &lt;body&gt;
 *       &lt;jsp:scriptlet&gt;
 *         String message = "Hello, World!";
 *       &lt;/jsp:scriptlet&gt;
 *       &lt;jsp:expression&gt;message&lt;/jsp:expression&gt;
 *     &lt;/body&gt;
 *   &lt;/html&gt;
 * &lt;/jsp:root&gt;
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLJSPParser implements JSPParser {

    private static final String JSP_NAMESPACE = "http://java.sun.com/JSP/Page";
    private static final String XML_DECLARATION_PREFIX = "<?xml";
    private static final String JSP_ROOT_ELEMENT = "jsp:root";

    @Override
    public JSPPage parse(InputStream input, String encoding, String jspUri)
            throws IOException, JSPParseException {

        try {
            // Create SAX parser
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();

            // Create content handler
            JSPContentHandler handler = new JSPContentHandler(jspUri, encoding);
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);

            // Parse the input
            InputSource source = new InputSource(input);
            if (encoding != null) {
                source.setEncoding(encoding);
            }
            source.setSystemId(jspUri);

            reader.parse(source);

            return handler.getJSPPage();

        } catch (ParserConfigurationException e) {
            throw new JSPParseException("Failed to create XML parser", jspUri, -1, -1, e);
        } catch (SAXException e) {
            throw new JSPParseException("XML parsing error: " + e.getMessage(), jspUri, -1, -1, e);
        }
    }

    @Override
    public JSPPage parse(InputStream input, String encoding, String jspUri, 
                        JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties)
            throws IOException, JSPParseException {

        try {
            // Create SAX parser
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();

            // Create content handler with JSP properties
            JSPContentHandler handler = new JSPContentHandler(jspUri, encoding, jspProperties);
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);

            // Parse the input
            InputSource source = new InputSource(input);
            if (encoding != null) {
                source.setEncoding(encoding);
            }
            source.setSystemId(jspUri);

            reader.parse(source);

            return handler.getJSPPage();

        } catch (ParserConfigurationException e) {
            throw new JSPParseException("Failed to create XML parser", jspUri, -1, -1, e);
        } catch (SAXException e) {
            throw new JSPParseException("XML parsing error: " + e.getMessage(), jspUri, -1, -1, e);
        }
    }

    @Override
    public boolean canParse(InputStream input, String encoding) throws IOException {
        if (!input.markSupported()) {
            return false;
        }

        // Mark the stream to allow reset
        input.mark(1024);

        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, encoding != null ? encoding : "UTF-8"));

            // Read first few lines to detect XML JSP format
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null && linesRead < 10) {
                line = line.trim();

                // Skip empty lines
                if (line.isEmpty()) {
                    linesRead++;
                    continue;
                }

                // Check for XML declaration
                if (line.startsWith(XML_DECLARATION_PREFIX)) {
                    linesRead++;
                    continue;
                }

                // Check for JSP root element or JSP namespace
                if (line.contains(JSP_ROOT_ELEMENT) || line.contains(JSP_NAMESPACE)) {
                    return true;
                }

                // If we encounter JSP scriptlets, this is traditional JSP
                if (line.contains("<%")) {
                    return false;
                }

                linesRead++;
            }

            return false;

        } finally {
            // Reset the stream for actual parsing
            input.reset();
        }
    }

    @Override
    public String getParserName() {
        return "XML JSP Parser (JSPX)";
    }

    /**
     * SAX content handler for parsing XML JSP content.
     */
    private static class JSPContentHandler extends DefaultHandler {

        private final JSPPage jspPage;
        private final Stack<String> elementStack = new Stack<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private final JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties;

        private Locator locator;

        public JSPContentHandler(String jspUri, String encoding) {
            this.jspPage = new JSPPage(jspUri, encoding);
            this.jspProperties = null;
        }

        public JSPContentHandler(String jspUri, String encoding, 
                               JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties) {
            this.jspPage = new JSPPage(jspUri, encoding);
            this.jspProperties = jspProperties;
        }

        public JSPPage getJSPPage() {
            return jspPage;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {

            // Flush any accumulated text
            flushText();

            elementStack.push(qName);

            int line = locator != null ? locator.getLineNumber() : -1;
            int column = locator != null ? locator.getColumnNumber() : -1;

            // Handle JSP-specific elements
            if (JSP_NAMESPACE.equals(uri)) {
                handleJSPElement(localName, attributes, line, column);
            } else if (qName.startsWith("jsp:")) {
                // Handle jsp: prefixed elements (for backward compatibility)
                String jspElement = qName.substring(4);
                handleJSPElement(jspElement, attributes, line, column);
            } else {
                // Regular XML/HTML element - treat as text
                StringBuilder sb = new StringBuilder();
                sb.append('<').append(qName);

                for (int i = 0; i < attributes.getLength(); i++) {
                    sb.append(' ').append(attributes.getQName(i)).append("=\"")
                      .append(escapeXml(attributes.getValue(i))).append('"');
                }

                sb.append('>');
                textBuffer.append(sb.toString());
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {

            if (!elementStack.isEmpty()) {
                elementStack.pop();
            }

            // Handle JSP elements that might have body content
            if (JSP_NAMESPACE.equals(uri) || qName.startsWith("jsp:")) {
                String jspElement = JSP_NAMESPACE.equals(uri) ? localName : qName.substring(4);

                if ("scriptlet".equals(jspElement) || "expression".equals(jspElement) ||
                    "declaration".equals(jspElement)) {

                    String content = textBuffer.toString();
                    textBuffer.setLength(0);

                    int line = locator != null ? locator.getLineNumber() : -1;
                    int column = locator != null ? locator.getColumnNumber() : -1;

                    // Check if scripting is disabled
                    if (jspProperties != null && jspProperties.getScriptingInvalid() != null && 
                        jspProperties.getScriptingInvalid()) {
                        throw new SAXException(new JSPParseException(
                            "Scripting is disabled for this JSP page", 
                            jspPage.getUri(), line, column));
                    }

                    if ("scriptlet".equals(jspElement)) {
                        jspPage.addElement(new ScriptletElement(content, line, column));
                    } else if ("expression".equals(jspElement)) {
                        jspPage.addElement(new ExpressionElement(content, line, column));
                    } else if ("declaration".equals(jspElement)) {
                        jspPage.addElement(new DeclarationElement(content, line, column));
                    }
                }
            } else {
                // Regular XML/HTML end element
                textBuffer.append("</").append(qName).append('>');
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            textBuffer.append(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            textBuffer.append(ch, start, length);
        }

        private void flushText() {
            if (textBuffer.length() > 0) {
                int line = locator != null ? locator.getLineNumber() : -1;
                int column = locator != null ? locator.getColumnNumber() : -1;

                jspPage.addElement(new TextElement(textBuffer.toString(), line, column));
                textBuffer.setLength(0);
            }
        }

        private void handleJSPElement(String elementName, Attributes attributes, int line, int column) {

            Map<String, String> attrMap = new HashMap<>();
            for (int i = 0; i < attributes.getLength(); i++) {
                attrMap.put(attributes.getLocalName(i), attributes.getValue(i));
            }

            // Handle directive elements (e.g., jsp:directive.page, jsp:directive.taglib)
            if (elementName.startsWith("directive.")) {
                String directiveName = elementName.substring(10);
                DirectiveElement directive = new DirectiveElement(directiveName, attrMap, line, column);
                jspPage.addElement(directive);
                processDirective(directive);
                return;
            }

            // Handle other JSP elements
            switch (elementName) {
                case "root":
                    // JSP root element - process attributes for page settings
                    handleRootElement(attrMap);
                    break;

                case "include":
                case "forward":
                case "useBean":
                case "setProperty":
                case "getProperty":
                case "param":
                    jspPage.addElement(new StandardActionElement(elementName, attrMap, line, column));
                    break;

                // Elements with body content (scriptlet, expression, declaration)
                // are handled in endElement

                default:
                    // Custom tag or unknown JSP element
                    jspPage.addElement(new CustomTagElement("jsp", elementName, attrMap, line, column));
                    break;
            }
        }

        private void processDirective(DirectiveElement directive) {
            if (directive.isTaglibDirective()) {
                Map<String, String> attributes = directive.getAttributes();
                String prefix = attributes.get("prefix");
                String uri = attributes.get("uri");
                String tagdir = attributes.get("tagdir");
                
                if (prefix != null) {
                    if (uri != null) {
                        // Standard taglib with URI
                        jspPage.addTaglibDirective(prefix, uri);
                    } else if (tagdir != null) {
                        // Tag files directory (JSP 2.0 feature)
                        // Convert tagdir to a synthetic URI for internal tracking
                        String syntheticUri = "tagdir:" + tagdir;
                        jspPage.addTaglibDirective(prefix, syntheticUri);
                    }
                }
            }
            // Note: Page directives are handled by JSPPage internally during code generation
        }

        private void handleRootElement(Map<String, String> attributes) {
            String version = attributes.get("version");
            // TODO: Handle JSP version and other root attributes
        }

        private String escapeXml(String text) {
            if (text == null) {
                return "";
            }
            return text.replace("&", "&amp;")
                      .replace("<", "&lt;")
                      .replace(">", "&gt;")
                      .replace("\"", "&quot;")
                      .replace("'", "&apos;");
        }
    }
}