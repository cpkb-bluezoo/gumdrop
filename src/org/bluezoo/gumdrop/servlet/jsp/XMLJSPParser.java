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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    
    // SAX parser factory for XML parsing
    private final SAXParserFactory saxParserFactory;

    /**
     * Creates an XML JSP parser with no SAX factory (uses fallback parser).
     */
    public XMLJSPParser() {
        this(null);
    }

    /**
     * Creates an XML JSP parser with a working SAX parser factory.
     * 
     * @param saxParserFactory the SAX parser factory to use (null to use fallback parser)
     */
    public XMLJSPParser(SAXParserFactory saxParserFactory) {
        this.saxParserFactory = saxParserFactory;
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

        if (saxParserFactory == null) {
            // Fall back to the simple parser if no factory provided
            return parseWithSimpleParser(input, encoding, jspUri, jspProperties);
        }

        try {
            // Use the injected working SAX parser factory
            saxParserFactory.setNamespaceAware(true);
            saxParserFactory.setValidating(false);
            javax.xml.parsers.SAXParser parser = saxParserFactory.newSAXParser();
            XMLReader reader = parser.getXMLReader();

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

        // Mark the stream with a larger buffer to accommodate BufferedReader
        input.mark(8192);  // Increased from 1024 to 8192 bytes

        try {
            // Read bytes directly to avoid BufferedReader's large internal buffering
            byte[] buffer = new byte[2048];  // Read up to 2KB for detection
            int bytesRead = input.read(buffer);
            
            if (bytesRead <= 0) {
                return false;
            }
            
            // Convert to string for analysis
            String content = new String(buffer, 0, bytesRead, encoding != null ? encoding : "UTF-8");
            String[] lines = content.split("\r?\n");
            
            // Analyze first few lines to detect XML JSP format
            int linesAnalyzed = 0;
            for (String line : lines) {
                if (linesAnalyzed >= 10) {
                    break;  // Only check first 10 lines
                }
                
                line = line.trim();

                // Skip empty lines
                if (line.isEmpty()) {
                    linesAnalyzed++;
                    continue;
                }

                // Check for XML declaration
                if (line.startsWith(XML_DECLARATION_PREFIX)) {
                    linesAnalyzed++;
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

                linesAnalyzed++;
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

    /**
     * Simple XML parser fallback for basic JSP XML parsing when SAX is unavailable.
     * This handles the most common JSP XML elements without requiring full SAX support.
     */
    private JSPPage parseWithSimpleParser(InputStream input, String encoding, String jspUri,
                                         JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties)
            throws IOException, JSPParseException {
        
        // Read the entire input into a string
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, encoding != null ? encoding : "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        
        String xmlContent = content.toString();
        JSPPage jspPage = new JSPPage(jspUri, encoding);
        
        // Simple regex-based parsing for basic JSP XML elements
        parseSimpleXMLElements(xmlContent, jspPage, jspProperties);
        
        return jspPage;
    }

    /**
     * Parse JSP XML elements using simple string matching (fallback method).
     */
    private void parseSimpleXMLElements(String xmlContent, JSPPage jspPage, 
                                       JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties)
            throws JSPParseException {
        
        try {
            // Remove XML declaration and jsp:root wrapper for easier parsing
            String content = xmlContent;
            content = content.replaceAll("<\\?xml[^>]*>", "");
            content = content.replaceAll("<jsp:root[^>]*>", "");
            content = content.replaceAll("</jsp:root>", "");
            
            // Parse jsp:directive.page elements
            Pattern pageDirective = Pattern.compile(
                "<jsp:directive\\.page\\s+([^>]*)/?>");
            Matcher matcher = pageDirective.matcher(content);
            while (matcher.find()) {
                String attributes = matcher.group(1);
                DirectiveElement directive = new DirectiveElement("page", parseAttributes(attributes), -1, -1);
                jspPage.addElement(directive);
            }
            
            // Parse jsp:declaration elements
            Pattern declaration = Pattern.compile(
                "<jsp:declaration>(.*?)</jsp:declaration>", Pattern.DOTALL);
            matcher = declaration.matcher(content);
            while (matcher.find()) {
                String code = matcher.group(1).trim();
                if (!code.isEmpty()) {
                    DeclarationElement decl = new DeclarationElement(unescapeXML(code), -1, -1);
                    jspPage.addElement(decl);
                }
            }
            
            // Parse jsp:scriptlet elements  
            Pattern scriptlet = Pattern.compile(
                "<jsp:scriptlet>(.*?)</jsp:scriptlet>", Pattern.DOTALL);
            matcher = scriptlet.matcher(content);
            while (matcher.find()) {
                String code = matcher.group(1).trim();
                if (!code.isEmpty()) {
                    // Check if scripting is disabled
                    if (jspProperties != null && jspProperties.getScriptingInvalid() != null && 
                        jspProperties.getScriptingInvalid()) {
                        throw new JSPParseException("Scripting is disabled for this JSP page", 
                                                  jspPage.getUri(), -1, -1);
                    }
                    ScriptletElement scriptletElem = new ScriptletElement(unescapeXML(code), -1, -1);
                    jspPage.addElement(scriptletElem);
                }
            }
            
            // Parse jsp:expression elements
            Pattern expression = Pattern.compile(
                "<jsp:expression>(.*?)</jsp:expression>", Pattern.DOTALL);
            matcher = expression.matcher(content);
            while (matcher.find()) {
                String code = matcher.group(1).trim();
                if (!code.isEmpty()) {
                    // Check if scripting is disabled
                    if (jspProperties != null && jspProperties.getScriptingInvalid() != null && 
                        jspProperties.getScriptingInvalid()) {
                        throw new JSPParseException("Scripting is disabled for this JSP page", 
                                                  jspPage.getUri(), -1, -1);
                    }
                    ExpressionElement expr = new ExpressionElement(unescapeXML(code), -1, -1);
                    jspPage.addElement(expr);
                }
            }
            
            // Parse text content (everything else)
            String textContent = content;
            // Remove all JSP elements to get remaining text
            textContent = textContent.replaceAll("<jsp:directive\\.[^>]*/?>\n*", "");
            textContent = textContent.replaceAll("<jsp:declaration>.*?</jsp:declaration>\n*", "");
            textContent = textContent.replaceAll("<jsp:scriptlet>.*?</jsp:scriptlet>\n*", "");
            textContent = textContent.replaceAll("<jsp:expression>.*?</jsp:expression>\n*", "");
            textContent = textContent.trim();
            
            if (!textContent.isEmpty()) {
                TextElement text = new TextElement(unescapeXML(textContent), -1, -1);
                jspPage.addElement(text);
            }
            
        } catch (Exception e) {
            throw new JSPParseException("Simple XML parsing error: " + e.getMessage(), 
                                      jspPage.getUri(), -1, -1, e);
        }
    }
    
    /**
     * Parse XML attributes into a map.
     */
    private Map<String, String> parseAttributes(String attributesString) {
        Map<String, String> attributes = new HashMap<>();
        if (attributesString == null || attributesString.trim().isEmpty()) {
            return attributes;
        }
        
        // Simple attribute parsing: name="value" or name='value'
        Pattern attr = Pattern.compile(
            "(\\w+)\\s*=\\s*[\"']([^\"']*)[\"']");
        Matcher matcher = attr.matcher(attributesString);
        while (matcher.find()) {
            attributes.put(matcher.group(1), unescapeXML(matcher.group(2)));
        }
        return attributes;
    }
    
    /**
     * Simple XML unescaping.
     */
    private String unescapeXML(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("&lt;", "<")
                   .replace("&gt;", ">")  
                   .replace("&amp;", "&")
                   .replace("&quot;", "\"")
                   .replace("&apos;", "'");
    }
}