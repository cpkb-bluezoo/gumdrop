/*
 * TraditionalJSPParser.java
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Parser for traditional JSP syntax using {@code <% %>} scriptlets and directives.
 * 
 * <p>This parser handles JSP pages that use the classic JSP syntax with
 * scriptlets, expressions, declarations, and directives. For example:
 * <pre>
 * &lt;%@ page language="java" contentType="text/html; charset=UTF-8" %&gt;
 * &lt;%@ page import="java.util.*" %&gt;
 * &lt;html&gt;
 * &lt;body&gt;
 *   &lt;%-- JSP Comment --%&gt;
 *   &lt;% String message = "Hello, World!"; %&gt;
 *   &lt;h1&gt;&lt;%= message %&gt;&lt;/h1&gt;
 *   &lt;%!
 *     private int counter = 0;
 *     public String getNextId() {
 *       return "id_" + (++counter);
 *     }
 *   %&gt;
 * &lt;/body&gt;
 * &lt;/html&gt;
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TraditionalJSPParser implements JSPParser {
    
    // JSP tag markers
    private static final String SCRIPTLET_START = "<%";
    private static final String SCRIPTLET_END = "%>";
    private static final String EXPRESSION_START = "<%=";
    private static final String DECLARATION_START = "<%!";
    private static final String DIRECTIVE_START = "<%@";
    private static final String COMMENT_START = "<%--";
    private static final String COMMENT_END = "--%>";
    private static final String STANDARD_ACTION_START = "<jsp:";
    
    @Override
    public JSPPage parse(InputStream input, String encoding, String jspUri) 
            throws IOException, JSPParseException {
        
        JSPPage page = new JSPPage(jspUri, encoding);
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, encoding != null ? encoding : "UTF-8"))) {
            
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append('\n');
            }
            
            parseContent(page, content.toString(), jspUri, null);
        }
        
        return page;
    }
    
    @Override
    public JSPPage parse(InputStream input, String encoding, String jspUri, 
                        JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties)
            throws IOException, JSPParseException {
        
        JSPPage page = new JSPPage(jspUri, encoding);
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, encoding != null ? encoding : "UTF-8"))) {
            
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append('\n');
            }
            
            parseContent(page, content.toString(), jspUri, jspProperties);
        }
        
        return page;
    }
    
    @Override
    public boolean canParse(InputStream input, String encoding) throws IOException {
        if (!input.markSupported()) {
            return false;
        }
        
        input.mark(1024);
        
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, encoding != null ? encoding : "UTF-8"));
            
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null && linesRead < 10) {
                // Look for JSP scriptlet markers
                if (line.contains("<%") && !line.contains("jsp:root")) {
                    return true;
                }
                // Reject explicit XML format (jsp:root)
                if (line.contains("jsp:root")) {
                    return false;
                }
                linesRead++;
            }
            
            // TraditionalJSPParser is the fallback for any non-XML content
            // including empty JSP files, static HTML, and JSP with no <% markers
            return true;
            
        } finally {
            input.reset();
        }
    }
    
    @Override
    public String getParserName() {
        return "Traditional JSP Parser";
    }
    
    /**
     * Parses the JSP content and populates the JSP page.
     */
    private void parseContent(JSPPage page, String content, String jspUri, 
                             JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties) throws JSPParseException {
        
        int pos = 0;
        int line = 1;
        int column = 1;
        
        while (pos < content.length()) {
            
            // Find next JSP element
            int jspStart = findNextJSPElement(content, pos);
            
            if (jspStart == -1) {
                // No more JSP elements - rest is text
                if (pos < content.length()) {
                    String text = content.substring(pos);
                    if (!text.trim().isEmpty()) {
                        page.addElement(new TextElement(text, line, column));
                    }
                }
                break;
            }
            
            // Add text content before JSP element
            if (jspStart > pos) {
                String text = content.substring(pos, jspStart);
                if (!text.trim().isEmpty()) {
                    page.addElement(new TextElement(text, line, column));
                }
                
                // Update line and column based on text content
                int[] lineCol = updatePosition(text, line, column);
                line = lineCol[0];
                column = lineCol[1];
            }
            
            // Parse JSP element
            JSPElementInfo elementInfo = parseJSPElement(content, jspStart, jspUri, line, column, jspProperties);
            
            if (elementInfo.element != null) {
                page.addElement(elementInfo.element);
                
                // Process directives to update page settings
                if (elementInfo.element instanceof DirectiveElement) {
                    processDirective(page, (DirectiveElement) elementInfo.element);
                }
            }
            
            // Move to next position
            pos = elementInfo.endPos;
            int[] lineCol = updatePosition(content.substring(jspStart, pos), line, column);
            line = lineCol[0];
            column = lineCol[1];
        }
    }
    
    /**
     * Finds the next JSP element starting from the given position.
     * Searches for both traditional JSP markers (<%) and standard actions (<jsp:).
     */
    private int findNextJSPElement(String content, int startPos) {
        int scriptletPos = content.indexOf("<%", startPos);
        int actionPos = content.indexOf(STANDARD_ACTION_START, startPos);
        
        if (scriptletPos == -1 && actionPos == -1) {
            return -1;
        } else if (scriptletPos == -1) {
            return actionPos;
        } else if (actionPos == -1) {
            return scriptletPos;
        } else {
            return Math.min(scriptletPos, actionPos);
        }
    }
    
    /**
     * Parses a JSP element starting at the given position.
     */
    private JSPElementInfo parseJSPElement(String content, int startPos, String jspUri, 
                                          int line, int column, 
                                          JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties) throws JSPParseException {
        
        // Determine JSP element type
        if (content.startsWith(COMMENT_START, startPos)) {
            return parseComment(content, startPos, line, column);
        } else if (content.startsWith(DIRECTIVE_START, startPos)) {
            return parseDirective(content, startPos, jspUri, line, column);
        } else if (content.startsWith(EXPRESSION_START, startPos)) {
            return parseExpression(content, startPos, line, column, jspProperties);
        } else if (content.startsWith(DECLARATION_START, startPos)) {
            return parseDeclaration(content, startPos, line, column, jspProperties);
        } else if (content.startsWith(SCRIPTLET_START, startPos)) {
            return parseScriptlet(content, startPos, line, column, jspProperties);
        } else if (content.startsWith(STANDARD_ACTION_START, startPos)) {
            return parseStandardAction(content, startPos, jspUri, line, column);
        }
        
        throw new JSPParseException("Unknown JSP element at position " + startPos, 
                                   jspUri, line, column);
    }
    
    /**
     * Parses a JSP comment: <%-- comment --%>
     */
    private JSPElementInfo parseComment(String content, int startPos, int line, int column) 
            throws JSPParseException {
        
        int endPos = content.indexOf(COMMENT_END, startPos + COMMENT_START.length());
        if (endPos == -1) {
            throw new JSPParseException("Unterminated JSP comment", null, line, column);
        }
        
        String comment = content.substring(startPos + COMMENT_START.length(), endPos);
        CommentElement element = new CommentElement(comment, line, column);
        
        return new JSPElementInfo(element, endPos + COMMENT_END.length());
    }
    
    /**
     * Parses a JSP directive: <%@ directive attributes %>
     */
    private JSPElementInfo parseDirective(String content, int startPos, String jspUri, 
                                         int line, int column) throws JSPParseException {
        
        int endPos = content.indexOf(SCRIPTLET_END, startPos + DIRECTIVE_START.length());
        if (endPos == -1) {
            throw new JSPParseException("Unterminated JSP directive", jspUri, line, column);
        }
        
        String directiveContent = content.substring(startPos + DIRECTIVE_START.length(), endPos).trim();
        
        // Parse directive name and attributes
        String directiveName;
        String attributeString = "";
        int firstSpace = -1;
        for (int i = 0; i < directiveContent.length(); i++) {
            char c = directiveContent.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                firstSpace = i;
                break;
            }
        }
        if (firstSpace > 0) {
            directiveName = directiveContent.substring(0, firstSpace);
            attributeString = directiveContent.substring(firstSpace + 1).trim();
        } else {
            directiveName = directiveContent;
        }
        
        Map<String, String> attributes = parseAttributes(attributeString, jspUri, line, column);
        DirectiveElement element = new DirectiveElement(directiveName, attributes, line, column);
        
        return new JSPElementInfo(element, endPos + SCRIPTLET_END.length());
    }
    
    /**
     * Parses a JSP expression: <%= expression %>
     */
    private JSPElementInfo parseExpression(String content, int startPos, int line, int column,
                                          JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties) 
            throws JSPParseException {
        
        // Check if scripting is disabled for this JSP
        if (jspProperties != null && jspProperties.getScriptingInvalid() != null && 
            jspProperties.getScriptingInvalid()) {
            throw new JSPParseException("Scripting is disabled for this JSP page", null, line, column);
        }
        
        int endPos = content.indexOf(SCRIPTLET_END, startPos + EXPRESSION_START.length());
        if (endPos == -1) {
            throw new JSPParseException("Unterminated JSP expression", null, line, column);
        }
        
        String expression = content.substring(startPos + EXPRESSION_START.length(), endPos);
        ExpressionElement element = new ExpressionElement(expression, line, column);
        
        return new JSPElementInfo(element, endPos + SCRIPTLET_END.length());
    }
    
    /**
     * Parses a JSP declaration: <%! declaration %>
     */
    private JSPElementInfo parseDeclaration(String content, int startPos, int line, int column,
                                           JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties) 
            throws JSPParseException {
        
        // Check if scripting is disabled for this JSP
        if (jspProperties != null && jspProperties.getScriptingInvalid() != null && 
            jspProperties.getScriptingInvalid()) {
            throw new JSPParseException("Scripting is disabled for this JSP page", null, line, column);
        }
        
        int endPos = content.indexOf(SCRIPTLET_END, startPos + DECLARATION_START.length());
        if (endPos == -1) {
            throw new JSPParseException("Unterminated JSP declaration", null, line, column);
        }
        
        String declaration = content.substring(startPos + DECLARATION_START.length(), endPos);
        DeclarationElement element = new DeclarationElement(declaration, line, column);
        
        return new JSPElementInfo(element, endPos + SCRIPTLET_END.length());
    }
    
    /**
     * Parses a JSP scriptlet: <% code %>
     */
    private JSPElementInfo parseScriptlet(String content, int startPos, int line, int column,
                                         JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties) 
            throws JSPParseException {
        
        // Check if scripting is disabled for this JSP
        if (jspProperties != null && jspProperties.getScriptingInvalid() != null && 
            jspProperties.getScriptingInvalid()) {
            throw new JSPParseException("Scripting is disabled for this JSP page", null, line, column);
        }
        
        int endPos = content.indexOf(SCRIPTLET_END, startPos + SCRIPTLET_START.length());
        if (endPos == -1) {
            throw new JSPParseException("Unterminated JSP scriptlet", null, line, column);
        }
        
        String code = content.substring(startPos + SCRIPTLET_START.length(), endPos);
        ScriptletElement element = new ScriptletElement(code, line, column);
        
        return new JSPElementInfo(element, endPos + SCRIPTLET_END.length());
    }
    
    /**
     * Parses a JSP standard action: <jsp:actionName attr="value" ... />
     * or <jsp:actionName attr="value" ...>body</jsp:actionName>
     */
    private JSPElementInfo parseStandardAction(String content, int startPos, String jspUri,
                                              int line, int column) throws JSPParseException {
        
        // Extract the action name (after "<jsp:")
        int actionNameStart = startPos + STANDARD_ACTION_START.length();
        int actionNameEnd = actionNameStart;
        
        while (actionNameEnd < content.length()) {
            char c = content.charAt(actionNameEnd);
            if (Character.isWhitespace(c) || c == '/' || c == '>') {
                break;
            }
            actionNameEnd++;
        }
        
        if (actionNameEnd == actionNameStart) {
            throw new JSPParseException("Missing action name in standard action", jspUri, line, column);
        }
        
        String actionName = content.substring(actionNameStart, actionNameEnd);
        
        // Find the end of the tag (either "/>" for self-closing or ">")
        int pos = actionNameEnd;
        
        // Skip whitespace
        while (pos < content.length() && Character.isWhitespace(content.charAt(pos))) {
            pos++;
        }
        
        // Find end of opening tag, collecting attributes
        int tagEnd = -1;
        boolean selfClosing = false;
        
        // Look for > or />
        int closePos = pos;
        while (closePos < content.length()) {
            if (content.charAt(closePos) == '>') {
                if (closePos > 0 && content.charAt(closePos - 1) == '/') {
                    selfClosing = true;
                    tagEnd = closePos + 1;
                } else {
                    tagEnd = closePos + 1;
                }
                break;
            }
            closePos++;
        }
        
        if (tagEnd == -1) {
            throw new JSPParseException("Unterminated standard action tag: jsp:" + actionName, 
                                       jspUri, line, column);
        }
        
        // Extract attributes from the tag
        String attrPart = content.substring(actionNameEnd, selfClosing ? closePos - 1 : closePos).trim();
        Map<String, String> attributes = parseAttributes(attrPart, jspUri, line, column);
        
        int endPos = tagEnd;
        
        // If not self-closing, find the closing tag </jsp:actionName>
        if (!selfClosing) {
            String closeTag = "</jsp:" + actionName + ">";
            int closeTagPos = content.indexOf(closeTag, tagEnd);
            if (closeTagPos == -1) {
                throw new JSPParseException("Missing closing tag for jsp:" + actionName, 
                                           jspUri, line, column);
            }
            endPos = closeTagPos + closeTag.length();
            // Note: Body content between tags is ignored for now
            // Could be parsed recursively for nested elements
        }
        
        StandardActionElement element = new StandardActionElement(actionName, attributes, line, column);
        
        return new JSPElementInfo(element, endPos);
    }
    
    /**
     * Parses directive attributes from a string.
     */
    private Map<String, String> parseAttributes(String attributeString, String jspUri, 
                                              int line, int column) throws JSPParseException {
        
        Map<String, String> attributes = new HashMap<>();
        
        if (attributeString.trim().isEmpty()) {
            return attributes;
        }
        
        // Simple attribute parsing (this could be made more robust)
        int pos = 0;
        while (pos < attributeString.length()) {
            
            // Skip whitespace
            while (pos < attributeString.length() && Character.isWhitespace(attributeString.charAt(pos))) {
                pos++;
            }
            
            if (pos >= attributeString.length()) {
                break;
            }
            
            // Find attribute name
            int nameStart = pos;
            while (pos < attributeString.length() && 
                   (Character.isLetterOrDigit(attributeString.charAt(pos)) || 
                    attributeString.charAt(pos) == '_' || attributeString.charAt(pos) == '-')) {
                pos++;
            }
            
            if (pos == nameStart) {
                throw new JSPParseException("Invalid attribute syntax", jspUri, line, column);
            }
            
            String name = attributeString.substring(nameStart, pos);
            
            // Skip whitespace and find '='
            while (pos < attributeString.length() && Character.isWhitespace(attributeString.charAt(pos))) {
                pos++;
            }
            
            if (pos >= attributeString.length() || attributeString.charAt(pos) != '=') {
                throw new JSPParseException("Expected '=' after attribute name", jspUri, line, column);
            }
            pos++; // Skip '='
            
            // Skip whitespace and find value
            while (pos < attributeString.length() && Character.isWhitespace(attributeString.charAt(pos))) {
                pos++;
            }
            
            if (pos >= attributeString.length()) {
                throw new JSPParseException("Expected attribute value", jspUri, line, column);
            }
            
            // Parse quoted value
            char quote = attributeString.charAt(pos);
            if (quote != '"' && quote != '\'') {
                throw new JSPParseException("Attribute value must be quoted", jspUri, line, column);
            }
            pos++; // Skip opening quote
            
            int valueStart = pos;
            while (pos < attributeString.length() && attributeString.charAt(pos) != quote) {
                pos++;
            }
            
            if (pos >= attributeString.length()) {
                throw new JSPParseException("Unterminated attribute value", jspUri, line, column);
            }
            
            String value = attributeString.substring(valueStart, pos);
            pos++; // Skip closing quote
            
            attributes.put(name, value);
        }
        
        return attributes;
    }
    
    /**
     * Updates line and column position based on text content.
     */
    private int[] updatePosition(String text, int currentLine, int currentColumn) {
        int line = currentLine;
        int column = currentColumn;
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        
        return new int[]{line, column};
    }
    
    /**
     * Processes directives to update page settings and taglib imports.
     */
    private void processDirective(JSPPage page, DirectiveElement directive) {
        if (directive.isPageDirective()) {
            processPageDirectiveAttributes(page, directive);
        } else if (directive.isTaglibDirective()) {
            processTaglibDirective(page, directive);
        }
    }

    /**
     * Processes page directive attributes to update page settings.
     */
    private void processPageDirectiveAttributes(JSPPage page, DirectiveElement directive) {
        
        Map<String, String> attributes = directive.getAttributes();
        
        // Process common page directive attributes
        if (attributes.containsKey("contentType")) {
            page.setContentType(attributes.get("contentType"));
        }
        
        if (attributes.containsKey("import")) {
            String imports = attributes.get("import");
            for (String importStatement : imports.split(",")) {
                page.addImport(importStatement.trim());
            }
        }
        
        if (attributes.containsKey("session")) {
            page.setSession(Boolean.parseBoolean(attributes.get("session")));
        }
        
        if (attributes.containsKey("buffer")) {
            String bufferValue = attributes.get("buffer");
            if (!"none".equals(bufferValue)) {
                try {
                    // Remove 'kb' suffix if present
                    if (bufferValue.endsWith("kb")) {
                        bufferValue = bufferValue.substring(0, bufferValue.length() - 2);
                    }
                    int buffer = Integer.parseInt(bufferValue) * 1024; // Convert KB to bytes
                    page.setBuffer(buffer);
                } catch (NumberFormatException e) {
                    // Ignore invalid buffer values
                }
            } else {
                page.setBuffer(0); // No buffering
            }
        }
        
        if (attributes.containsKey("autoFlush")) {
            page.setAutoFlush(Boolean.parseBoolean(attributes.get("autoFlush")));
        }
        
        if (attributes.containsKey("isThreadSafe")) {
            page.setThreadSafe(Boolean.parseBoolean(attributes.get("isThreadSafe")));
        }
        
        if (attributes.containsKey("isErrorPage")) {
            page.setErrorPage(Boolean.parseBoolean(attributes.get("isErrorPage")));
        }
        
        if (attributes.containsKey("errorPage")) {
            page.setErrorPage(attributes.get("errorPage"));
        }
    }
    
    /**
     * Processes taglib directive to register tag library imports.
     */
    private void processTaglibDirective(JSPPage page, DirectiveElement directive) {
        Map<String, String> attributes = directive.getAttributes();
        
        String prefix = attributes.get("prefix");
        String uri = attributes.get("uri");
        String tagdir = attributes.get("tagdir");
        
        if (prefix != null) {
            if (uri != null) {
                // Standard taglib with URI
                page.addTaglibDirective(prefix, uri);
            } else if (tagdir != null) {
                // Tag files directory (JSP 2.0 feature)
                // Convert tagdir to a synthetic URI for internal tracking
                String syntheticUri = "tagdir:" + tagdir;
                page.addTaglibDirective(prefix, syntheticUri);
            }
        }
    }
    
    /**
     * Helper class to hold parsed JSP element information.
     */
    private static class JSPElementInfo {
        final JSPElement element;
        final int endPos;
        
        JSPElementInfo(JSPElement element, int endPos) {
            this.element = element;
            this.endPos = endPos;
        }
    }
}
