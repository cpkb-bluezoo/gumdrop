/*
 * XMLJSPParser.java
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

import org.bluezoo.gumdrop.util.XMLParseUtils;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
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
 * <p>This parser uses SAX exclusively for XML parsing. It requires a working
 * SAX parser factory to be provided.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLJSPParser implements JSPParser {

    private static final String JSP_NAMESPACE = "http://java.sun.com/JSP/Page";
    private static final String XML_DECLARATION_PREFIX = "<?xml";
    private static final String JSP_ROOT_ELEMENT = "jsp:root";

    /**
     * Creates an XML JSP parser.
     * Uses the Gonzalez streaming XML parser for non-blocking parsing.
     */
    public XMLJSPParser() {
        // No-op - Gonzalez is used directly
    }

    @Override
    public JSPPage parse(InputStream input, String encoding, String jspUri)
            throws IOException, JSPParseException {
        return parse(input, encoding, jspUri, null);
    }

    @Override
    public JSPPage parse(InputStream input, String encoding, String jspUri, 
                        JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties)
            throws IOException, JSPParseException {

        try {
            // Create content handler with JSP properties
            JSPContentHandler handler = new JSPContentHandler(jspUri, encoding, jspProperties);
            
            // Use Gonzalez streaming parser
            XMLParseUtils.parseStream(input, handler, handler, jspUri, null);

            return handler.getJSPPage();

        } catch (SAXException e) {
            // Check if this wraps a JSPParseException
            if (e.getCause() instanceof JSPParseException) {
                throw (JSPParseException) e.getCause();
            }
            throw new JSPParseException("XML parsing error: " + e.getMessage(), jspUri, -1, -1, e);
        }
    }

    @Override
    public boolean canParse(InputStream input, String encoding) throws IOException {
        if (!input.markSupported()) {
            return false;
        }

        // Mark the stream with a larger buffer to accommodate detection
        input.mark(8192);

        try {
            // Read bytes directly for detection
            byte[] buffer = new byte[2048];
            int bytesRead = input.read(buffer);
            
            if (bytesRead <= 0) {
                return false;
            }
            
            // Convert to string for analysis
            String enc = encoding != null ? encoding : "UTF-8";
            String content = new String(buffer, 0, bytesRead, enc);
            
            // Analyze content to detect XML JSP format
            int pos = 0;
            int linesAnalyzed = 0;
            
            while (pos < content.length() && linesAnalyzed < 10) {
                // Skip whitespace
                while (pos < content.length() && Character.isWhitespace(content.charAt(pos))) {
                    if (content.charAt(pos) == '\n') {
                        linesAnalyzed++;
                    }
                    pos++;
                }
                
                if (pos >= content.length()) {
                    break;
                }
                
                // Get line content
                int lineEnd = content.indexOf('\n', pos);
                if (lineEnd < 0) {
                    lineEnd = content.length();
                }
                String line = content.substring(pos, lineEnd).trim();
                
                // Check for XML declaration
                if (line.startsWith(XML_DECLARATION_PREFIX)) {
                    pos = lineEnd + 1;
                    linesAnalyzed++;
                    continue;
                }

                // Check for JSP root element or JSP namespace
                if (containsSubstring(line, JSP_ROOT_ELEMENT) || 
                    containsSubstring(line, JSP_NAMESPACE)) {
                    return true;
                }

                // If we encounter JSP scriptlets, this is traditional JSP
                if (containsSubstring(line, "<%")) {
                    return false;
                }

                pos = lineEnd + 1;
                linesAnalyzed++;
            }

            return false;

        } finally {
            // Reset the stream for actual parsing
            input.reset();
        }
    }

    /**
     * Checks if a string contains a substring (without using regex).
     */
    private boolean containsSubstring(String str, String substring) {
        return str.indexOf(substring) >= 0;
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
        private final Stack<String> elementStack = new Stack<String>();
        private final StringBuilder textBuffer = new StringBuilder();
        private final JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties;

        private Locator locator;

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
                } else if ("text".equals(jspElement)) {
                    // jsp:text element - content is literal output
                    String content = textBuffer.toString();
                    textBuffer.setLength(0);
                    
                    if (content.length() > 0) {
                        int line = locator != null ? locator.getLineNumber() : -1;
                        int column = locator != null ? locator.getColumnNumber() : -1;
                        jspPage.addElement(new TextElement(content, line, column));
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

        @Override
        public void endDocument() throws SAXException {
            // Flush any remaining text
            flushText();
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

            Map<String, String> attrMap = new HashMap<String, String>();
            for (int i = 0; i < attributes.getLength(); i++) {
                String name = attributes.getLocalName(i);
                if (name == null || name.isEmpty()) {
                    name = attributes.getQName(i);
                }
                attrMap.put(name, attributes.getValue(i));
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
            if ("root".equals(elementName)) {
                // JSP root element - process attributes for page settings
                handleRootElement(attrMap);
            } else if ("include".equals(elementName) || "forward".equals(elementName) ||
                       "useBean".equals(elementName) || "setProperty".equals(elementName) ||
                       "getProperty".equals(elementName) || "param".equals(elementName) ||
                       "plugin".equals(elementName) || "params".equals(elementName) ||
                       "fallback".equals(elementName) || "attribute".equals(elementName) ||
                       "body".equals(elementName) || "invoke".equals(elementName) ||
                       "doBody".equals(elementName) || "element".equals(elementName) ||
                       "output".equals(elementName)) {
                jspPage.addElement(new StandardActionElement(elementName, attrMap, line, column));
            } else if (!"scriptlet".equals(elementName) && !"expression".equals(elementName) &&
                       !"declaration".equals(elementName) && !"text".equals(elementName)) {
                // Custom tag or unknown JSP element
                jspPage.addElement(new CustomTagElement("jsp", elementName, attrMap, line, column));
            }
            // Note: scriptlet, expression, declaration, and text are handled in endElement
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
            // Process namespace declarations for taglibs
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                
                // Look for xmlns:prefix declarations (other than xmlns:jsp)
                if (name.startsWith("xmlns:") && !name.equals("xmlns:jsp")) {
                    String prefix = name.substring(6);
                    // Register as a taglib
                    jspPage.addTaglibDirective(prefix, value);
                }
            }
        }

        private String escapeXml(String text) {
            if (text == null) {
                return "";
            }
            StringBuilder result = new StringBuilder(text.length() + 16);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '&':
                        result.append("&amp;");
                        break;
                    case '<':
                        result.append("&lt;");
                        break;
                    case '>':
                        result.append("&gt;");
                        break;
                    case '"':
                        result.append("&quot;");
                        break;
                    case '\'':
                        result.append("&apos;");
                        break;
                    default:
                        result.append(c);
                        break;
                }
            }
            return result.toString();
        }
    }
}
