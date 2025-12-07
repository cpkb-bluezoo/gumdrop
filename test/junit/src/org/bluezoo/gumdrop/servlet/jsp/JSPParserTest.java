/*
 * JSPParserTest.java
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

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for JSP parsing.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPParserTest {

    private JSPParserFactory parserFactory;

    @Before
    public void setUp() throws Exception {
        javax.xml.parsers.SAXParserFactory saxFactory = 
            javax.xml.parsers.SAXParserFactory.newInstance();
        saxFactory.setNamespaceAware(true);
        saxFactory.setValidating(false);
        parserFactory = new JSPParserFactory(saxFactory);
    }

    // ===== Basic Parsing Tests =====

    @Test
    public void testParseEmptyJSP() throws Exception {
        String jsp = "";
        JSPPage page = parse(jsp);
        
        assertNotNull("Page should not be null", page);
    }

    @Test
    public void testParseStaticHTML() throws Exception {
        String jsp = "<html><body><h1>Hello</h1></body></html>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        List<JSPElement> elements = page.getElements();
        assertFalse("Should have elements", elements.isEmpty());
    }

    // ===== Directive Tests =====

    @Test
    public void testParsePageDirective() throws Exception {
        String jsp = "<%@ page contentType=\"text/html; charset=UTF-8\" %>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        assertEquals("text/html; charset=UTF-8", page.getContentType());
    }

    @Test
    public void testParseImportDirective() throws Exception {
        String jsp = "<%@ page import=\"java.util.List, java.util.Map\" %>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        List<String> imports = page.getImports();
        assertTrue("Should import java.util.List", imports.contains("java.util.List"));
        assertTrue("Should import java.util.Map", imports.contains("java.util.Map"));
    }

    @Test
    public void testParseSessionDirective() throws Exception {
        String jsp = "<%@ page session=\"false\" %>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        assertFalse("Session should be disabled", page.isSession());
    }

    @Test
    public void testParseErrorPageDirective() throws Exception {
        String jsp = "<%@ page errorPage=\"/error.jsp\" %>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        assertEquals("/error.jsp", page.getErrorPage());
    }

    @Test
    public void testParseIsErrorPageDirective() throws Exception {
        String jsp = "<%@ page isErrorPage=\"true\" %>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        assertTrue("Should be error page", page.isErrorPage());
    }

    @Test
    public void testParseBufferDirective() throws Exception {
        String jsp = "<%@ page buffer=\"16kb\" %>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        assertEquals("Buffer should be 16KB", 16384, page.getBuffer());
    }

    @Test
    public void testParseAutoFlushDirective() throws Exception {
        String jsp = "<%@ page autoFlush=\"false\" %>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        assertFalse("AutoFlush should be disabled", page.isAutoFlush());
    }

    @Test
    public void testParseTaglibDirective() throws Exception {
        String jsp = "<%@ taglib uri=\"http://java.sun.com/jsp/jstl/core\" prefix=\"c\" %>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        assertEquals("http://java.sun.com/jsp/jstl/core", page.getTaglibUri("c"));
    }

    // ===== Scriptlet Tests =====

    @Test
    public void testParseScriptlet() throws Exception {
        String jsp = "<% String message = \"Hello\"; %>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        
        ScriptletElement scriptlet = findElement(page, ScriptletElement.class);
        assertNotNull("Should have scriptlet", scriptlet);
        assertTrue("Scriptlet should contain code", 
            scriptlet.getCode().contains("String message"));
    }

    @Test
    public void testParseMultiLineScriptlet() throws Exception {
        String jsp = 
            "<%\n" +
            "    int count = 0;\n" +
            "    for (int i = 0; i < 10; i++) {\n" +
            "        count += i;\n" +
            "    }\n" +
            "%>";
        JSPPage page = parse(jsp);
        
        ScriptletElement scriptlet = findElement(page, ScriptletElement.class);
        assertNotNull(scriptlet);
        assertTrue(scriptlet.getCode().contains("int count"));
        assertTrue(scriptlet.getCode().contains("for"));
    }

    // ===== Expression Tests =====

    @Test
    public void testParseExpression() throws Exception {
        String jsp = "<%= message %>";
        JSPPage page = parse(jsp);
        
        ExpressionElement expr = findElement(page, ExpressionElement.class);
        assertNotNull("Should have expression", expr);
        assertTrue("Expression should contain variable",
            expr.getExpression().contains("message"));
    }

    @Test
    public void testParseExpressionWithMethodCall() throws Exception {
        String jsp = "<%= user.getName() %>";
        JSPPage page = parse(jsp);
        
        ExpressionElement expr = findElement(page, ExpressionElement.class);
        assertNotNull(expr);
        assertTrue(expr.getExpression().contains("user.getName()"));
    }

    // ===== Declaration Tests =====

    @Test
    public void testParseDeclaration() throws Exception {
        String jsp = "<%! private int counter = 0; %>";
        JSPPage page = parse(jsp);
        
        DeclarationElement decl = findElement(page, DeclarationElement.class);
        assertNotNull("Should have declaration", decl);
        assertTrue("Declaration should contain field",
            decl.getDeclaration().contains("private int counter"));
    }

    @Test
    public void testParseMethodDeclaration() throws Exception {
        String jsp = 
            "<%!\n" +
            "    private String formatDate(java.util.Date date) {\n" +
            "        return date.toString();\n" +
            "    }\n" +
            "%>";
        JSPPage page = parse(jsp);
        
        DeclarationElement decl = findElement(page, DeclarationElement.class);
        assertNotNull(decl);
        assertTrue(decl.getDeclaration().contains("formatDate"));
    }

    // ===== Comment Tests =====

    @Test
    public void testParseJSPComment() throws Exception {
        String jsp = "<%-- This is a JSP comment --%>";
        JSPPage page = parse(jsp);
        
        CommentElement comment = findElement(page, CommentElement.class);
        assertNotNull("Should have comment", comment);
        assertTrue("Comment should contain text",
            comment.getComment().contains("This is a JSP comment"));
    }

    // ===== Standard Action Tests =====

    @Test
    public void testParseIncludeAction() throws Exception {
        String jsp = "<jsp:include page=\"header.jsp\" />";
        JSPPage page = parse(jsp);
        
        StandardActionElement action = findElement(page, StandardActionElement.class);
        assertNotNull("Should have include action", action);
        assertEquals("include", action.getActionName());
        assertEquals("header.jsp", action.getAttributes().get("page"));
    }

    @Test
    public void testParseForwardAction() throws Exception {
        String jsp = "<jsp:forward page=\"other.jsp\" />";
        JSPPage page = parse(jsp);
        
        StandardActionElement action = findElement(page, StandardActionElement.class);
        assertNotNull(action);
        assertEquals("forward", action.getActionName());
    }

    @Test
    public void testParseUseBeanAction() throws Exception {
        String jsp = "<jsp:useBean id=\"user\" class=\"com.example.User\" scope=\"request\" />";
        JSPPage page = parse(jsp);
        
        StandardActionElement action = findElement(page, StandardActionElement.class);
        assertNotNull(action);
        assertEquals("useBean", action.getActionName());
        assertEquals("user", action.getAttributes().get("id"));
        assertEquals("com.example.User", action.getAttributes().get("class"));
        assertEquals("request", action.getAttributes().get("scope"));
    }

    // ===== Mixed Content Tests =====

    @Test
    public void testParseMixedContent() throws Exception {
        String jsp = 
            "<html>\n" +
            "<body>\n" +
            "<% String name = \"World\"; %>\n" +
            "<h1>Hello, <%= name %>!</h1>\n" +
            "</body>\n" +
            "</html>";
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        
        // Should have scriptlet
        ScriptletElement scriptlet = findElement(page, ScriptletElement.class);
        assertNotNull("Should have scriptlet", scriptlet);
        
        // Should have expression
        ExpressionElement expr = findElement(page, ExpressionElement.class);
        assertNotNull("Should have expression", expr);
        
        // Should have text content
        TextElement text = findElement(page, TextElement.class);
        assertNotNull("Should have text", text);
    }

    @Test
    public void testParseComplexJSP() throws Exception {
        String jsp = 
            "<%@ page contentType=\"text/html; charset=UTF-8\" %>\n" +
            "<%@ page import=\"java.util.List\" %>\n" +
            "<%@ taglib uri=\"http://java.sun.com/jsp/jstl/core\" prefix=\"c\" %>\n" +
            "<%!\n" +
            "    private int visitCount = 0;\n" +
            "%>\n" +
            "<html>\n" +
            "<head><title>Test Page</title></head>\n" +
            "<body>\n" +
            "<%-- Increment visit count --%>\n" +
            "<% visitCount++; %>\n" +
            "<p>Visit count: <%= visitCount %></p>\n" +
            "</body>\n" +
            "</html>";
        
        JSPPage page = parse(jsp);
        
        assertNotNull(page);
        assertEquals("text/html; charset=UTF-8", page.getContentType());
        assertTrue(page.getImports().contains("java.util.List"));
        assertEquals("http://java.sun.com/jsp/jstl/core", page.getTaglibUri("c"));
        
        // Should have declaration, comment, scriptlet, and expression
        assertNotNull(findElement(page, DeclarationElement.class));
        assertNotNull(findElement(page, CommentElement.class));
        assertNotNull(findElement(page, ScriptletElement.class));
        assertNotNull(findElement(page, ExpressionElement.class));
    }

    // ===== Helper Methods =====

    private JSPPage parse(String jsp) throws Exception {
        InputStream input = new ByteArrayInputStream(jsp.getBytes(StandardCharsets.UTF_8));
        return parserFactory.parseJSP(input, "UTF-8", "/test.jsp", null);
    }

    @SuppressWarnings("unchecked")
    private <T extends JSPElement> T findElement(JSPPage page, Class<T> type) {
        for (JSPElement element : page.getElements()) {
            if (type.isInstance(element)) {
                return (T) element;
            }
        }
        return null;
    }
}
