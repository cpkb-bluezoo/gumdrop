/*
 * TraditionalJSPParserBranchTest.java
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Branch-level unit tests for {@link TraditionalJSPParser}: element
 * recognition and ordering, position tracking, scripting-invalid handling,
 * attribute syntax errors, standard actions with nested parameters, page and
 * taglib directive processing and format detection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TraditionalJSPParserBranchTest {

    private TraditionalJSPParser parser;

    @Before
    public void setUp() {
        parser = new TraditionalJSPParser();
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private JspPage parse(String jsp) throws Exception {
        return parser.parse(stream(jsp), "UTF-8", "/t.jsp");
    }

    private JspParseException parseFailure(String jsp) throws Exception {
        try {
            parse(jsp);
        } catch (JspParseException e) {
            return e;
        }
        fail("expected JspParseException for " + jsp);
        return null;
    }

    private static JspPropertyGroupResolver.ResolvedJSPProperties scripting(Boolean invalid) {
        JspPropertyGroupResolver.ResolvedJSPProperties p =
                new JspPropertyGroupResolver.ResolvedJSPProperties();
        p.setScriptingInvalid(invalid);
        return p;
    }

    // ===== identification =====

    @Test
    public void testParserName() {
        assertEquals("Traditional JSP Parser", parser.getParserName());
    }

    @Test
    public void testCanParseRequiresMarkSupport() throws Exception {
        InputStream noMark = new InputStream() {
            @Override
            public int read() {
                return -1;
            }
        };
        assertFalse(parser.canParse(noMark, "UTF-8"));
    }

    @Test
    public void testCanParseDetectsScriptletsAndXmlFormat() throws Exception {
        assertTrue(parser.canParse(stream("<html>\n<% x %>\n"), null));
        assertFalse(parser.canParse(stream("<jsp:root>\n<% x %>\n"), "UTF-8"));
        assertFalse(parser.canParse(stream("<html>\n<jsp:root xmlns:jsp='x'>\n"), "UTF-8"));
        assertTrue(parser.canParse(stream("plain text\n"), "UTF-8"));
        assertTrue(parser.canParse(stream(""), "UTF-8"));
    }

    @Test
    public void testCanParseStopsLookingAfterTenLines() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append("plain ").append(i).append('\n');
        }
        sb.append("<jsp:root>\n");
        assertTrue(parser.canParse(stream(sb.toString()), "UTF-8"));
    }

    @Test
    public void testCanParseResetsStream() throws Exception {
        InputStream in = stream("<% x %>");
        assertTrue(parser.canParse(in, "UTF-8"));
        assertEquals('<', in.read());
    }

    @Test
    public void testParseUsesUtf8WhenEncodingIsNull() throws Exception {
        JspPage page = parser.parse(stream("café"), null, "/e.jsp");
        TextElement text = (TextElement) page.getElements().get(0);
        assertTrue(text.getContent(), text.getContent().startsWith("café"));
        JspPage withProps = parser.parse(stream("café"), null, "/e.jsp", null);
        assertEquals(1, withProps.getElements().size());
    }

    // ===== element recognition =====

    @Test
    public void testElementsInDocumentOrderWithPositions() throws Exception {
        JspPage page = parse("hello\n<%-- c --%><%@ page session=\"true\" %>"
                + "<%= a %><%! int b; %><% c(); %>tail");
        List<JspElement> els = page.getElements();
        assertEquals(7, els.size());
        assertTrue(els.get(0) instanceof TextElement);
        assertEquals(1, els.get(0).getLineNumber());
        CommentElement c = (CommentElement) els.get(1);
        assertEquals(" c ", c.getComment());
        assertEquals(2, c.getLineNumber());
        assertEquals(1, c.getColumnNumber());
        assertTrue(els.get(2) instanceof DirectiveElement);
        assertEquals("a", ((ExpressionElement) els.get(3)).getExpression());
        assertEquals("int b;", ((DeclarationElement) els.get(4)).getDeclaration().trim());
        assertEquals("c();", ((ScriptletElement) els.get(5)).getCode().trim());
        assertEquals("tail\n", ((TextElement) els.get(6)).getContent());
    }

    @Test
    public void testWhitespaceOnlyTextIsDropped() throws Exception {
        JspPage page = parse("  \n <% a %> \t\n");
        assertEquals(1, page.getElements().size());
        assertTrue(page.getElements().get(0) instanceof ScriptletElement);
    }

    @Test
    public void testStandardActionBeforeScriptletIsFoundFirst() throws Exception {
        JspPage page = parse("<jsp:include page=\"a.jsp\"/><% b(); %>");
        assertTrue(page.getElements().get(0) instanceof StandardActionElement);
        assertTrue(page.getElements().get(1) instanceof ScriptletElement);
        JspPage reverse = parse("<% b(); %><jsp:include page=\"a.jsp\"/>");
        assertTrue(reverse.getElements().get(0) instanceof ScriptletElement);
        assertTrue(reverse.getElements().get(1) instanceof StandardActionElement);
    }

    // ===== unterminated constructs =====

    @Test
    public void testUnterminatedConstructsAreRejected() throws Exception {
        assertTrue(parseFailure("<%-- never ends").getMessage().contains("comment"));
        JspParseException directive = parseFailure("a\n<%@ page x=\"y\"");
        assertTrue(directive.getMessage().contains("directive"));
        assertEquals(2, directive.getLineNumber());
        assertTrue(parseFailure("<%= x").getMessage().contains("expression"));
        assertTrue(parseFailure("<%! x").getMessage().contains("declaration"));
        assertTrue(parseFailure("<% x").getMessage().contains("scriptlet"));
    }

    // ===== scripting invalid =====

    @Test
    public void testScriptingInvalidRejectsExpressionsDeclarationsAndScriptlets() throws Exception {
        String[] pages = {"<%= a %>", "<%! a %>", "<% a %>"};
        for (int i = 0; i < pages.length; i++) {
            try {
                parser.parse(stream(pages[i]), "UTF-8", "/s.jsp", scripting(Boolean.TRUE));
                fail("expected JspParseException for " + pages[i]);
            } catch (JspParseException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("Scripting is disabled"));
            }
        }
    }

    @Test
    public void testScriptingAllowedWhenNotInvalid() throws Exception {
        String[] pages = {"<%= a %>", "<%! a %>", "<% a %>"};
        for (int i = 0; i < pages.length; i++) {
            JspPage explicit = parser.parse(stream(pages[i]), "UTF-8", "/s.jsp",
                    scripting(Boolean.FALSE));
            assertEquals(1, explicit.getElements().size());
            JspPage unset = parser.parse(stream(pages[i]), "UTF-8", "/s.jsp", scripting(null));
            assertEquals(1, unset.getElements().size());
        }
    }

    @Test
    public void testScriptingInvalidDoesNotAffectDirectivesComments() throws Exception {
        JspPage page = parser.parse(stream("<%-- c --%><%@ page session=\"false\" %>"),
                "UTF-8", "/s.jsp", scripting(Boolean.TRUE));
        assertEquals(2, page.getElements().size());
        assertFalse(page.isSession());
    }

    // ===== directive attribute syntax =====

    @Test
    public void testDirectiveWithoutAttributes() throws Exception {
        JspPage page = parse("<%@ page %>");
        DirectiveElement d = (DirectiveElement) page.getElements().get(0);
        assertEquals("page", d.getName());
        assertTrue(d.getAttributes().isEmpty());
    }

    @Test
    public void testDirectiveAttributeFormsAreAccepted() throws Exception {
        JspPage page = parse("<%@ include\tfile = 'a.jspf'\n  other-name=\"x\" under_score='y' %>");
        DirectiveElement d = (DirectiveElement) page.getElements().get(0);
        assertEquals("include", d.getName());
        Map<String, String> attrs = d.getAttributes();
        assertEquals("a.jspf", attrs.get("file"));
        assertEquals("x", attrs.get("other-name"));
        assertEquals("y", attrs.get("under_score"));
    }

    @Test
    public void testMalformedAttributesAreRejected() throws Exception {
        assertTrue(parseFailure("<%@ page =x %>").getMessage().contains("Invalid attribute syntax"));
        assertTrue(parseFailure("<%@ page a %>").getMessage().contains("Expected '='"));
        assertTrue(parseFailure("<%@ page a b=\"c\" %>").getMessage().contains("Expected '='"));
        assertTrue(parseFailure("<%@ page a= %>").getMessage().contains("Expected attribute value"));
        assertTrue(parseFailure("<%@ page a=b %>").getMessage().contains("must be quoted"));
        assertTrue(parseFailure("<%@ page a=\"b %>").getMessage().contains("Unterminated attribute"));
    }

    // ===== standard actions =====

    @Test
    public void testSelfClosingAndBodiedStandardActions() throws Exception {
        JspPage page = parse("<jsp:include page=\"a.jsp\"/>"
                + "<jsp:forward page=\"b.jsp\">\n<jsp:param name=\"n\" value=\"v\"/>"
                + "<jsp:param name=\"m\" value=\"w\"/> text </jsp:forward>"
                + "<jsp:useBean id=\"x\" class=\"y\" ></jsp:useBean>");
        List<JspElement> els = page.getElements();
        assertEquals(3, els.size());
        StandardActionElement include = (StandardActionElement) els.get(0);
        assertEquals("include", include.getActionName());
        assertEquals("a.jsp", include.getAttribute("page"));
        assertTrue(include.getChildren().isEmpty());
        StandardActionElement forward = (StandardActionElement) els.get(1);
        assertEquals(2, forward.getChildren().size());
        StandardActionElement second = (StandardActionElement) forward.getChildren().get(1);
        assertEquals("m", second.getAttribute("name"));
        StandardActionElement bean = (StandardActionElement) els.get(2);
        assertEquals("useBean", bean.getActionName());
        assertTrue(bean.getChildren().isEmpty());
    }

    @Test
    public void testMalformedStandardActionsAreRejected() throws Exception {
        assertTrue(parseFailure("<jsp:/>").getMessage().contains("Missing action name"));
        assertTrue(parseFailure("<jsp:").getMessage().contains("Missing action name"));
        assertTrue(parseFailure("<jsp:include page=\"a\"").getMessage()
                .contains("Unterminated standard action tag: jsp:include"));
        assertTrue(parseFailure("<jsp:abc").getMessage().contains("jsp:abc"));
        assertTrue(parseFailure("<jsp:forward page=\"a\">no end").getMessage()
                .contains("Missing closing tag for jsp:forward"));
    }

    // ===== page directive processing =====

    @Test
    public void testPageDirectiveAttributes() throws Exception {
        JspPage page = parse("<%@ page contentType=\"text/plain\" session=\"false\" "
                + "autoFlush=\"false\" isThreadSafe=\"false\" isErrorPage=\"true\" "
                + "errorPage=\"/err.jsp\" import=\"a.B, c.D,,\" %>");
        assertEquals("text/plain", page.getContentType());
        assertFalse(page.isSession());
        assertFalse(page.isAutoFlush());
        assertFalse(page.isThreadSafe());
        assertTrue(page.isErrorPage());
        assertEquals("/err.jsp", page.getErrorPage());
        int defaults = new JspPage("/d.jsp", "UTF-8").getImports().size();
        assertEquals(defaults + 2, page.getImports().size());
        assertTrue(page.getImports().contains("a.B"));
        assertTrue(page.getImports().contains("c.D"));
    }

    @Test
    public void testBufferValues() throws Exception {
        assertEquals(0, parse("<%@ page buffer=\"none\" %>").getBuffer());
        assertEquals(8 * 1024, parse("<%@ page buffer=\"8kb\" %>").getBuffer());
        assertEquals(4 * 1024, parse("<%@ page buffer=\"4\" %>").getBuffer());
        assertEquals("invalid buffer ignored", 8192,
                parse("<%@ page buffer=\"lots\" %>").getBuffer());
    }

    // ===== taglib and other directives =====

    @Test
    public void testTaglibDirectives() throws Exception {
        JspPage page = parse("<%@ taglib prefix=\"c\" uri=\"urn:core\" %>"
                + "<%@ taglib prefix=\"t\" tagdir=\"/WEB-INF/tags\" %>"
                + "<%@ taglib prefix=\"n\" %>"
                + "<%@ taglib uri=\"urn:orphan\" %>");
        Map<String, String> libs = page.getTagLibraries();
        assertEquals("urn:core", libs.get("c"));
        assertEquals("tagdir:/WEB-INF/tags", libs.get("t"));
        assertFalse(libs.containsKey("n"));
        assertEquals(2, libs.size());
    }

    @Test
    public void testIncludeDirectiveLeavesPageSettingsAlone() throws Exception {
        JspPage page = parse("<%@ include file=\"x.jspf\" %>");
        assertNotNull(page.getElements().get(0));
        int defaults = new JspPage("/d.jsp", "UTF-8").getImports().size();
        assertEquals(defaults, page.getImports().size());
        assertTrue(page.getTagLibraries().isEmpty());
    }

    @Test
    public void testIoFailureIsPropagated() throws Exception {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk");
            }
        };
        try {
            parser.parse(failing, "UTF-8", "/x.jsp");
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("disk", e.getMessage());
        }
    }
}
