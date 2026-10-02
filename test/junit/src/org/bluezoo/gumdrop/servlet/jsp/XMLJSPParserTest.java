/*
 * XMLJSPParserTest.java
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

import jakarta.servlet.descriptor.JspPropertyGroupDescriptor;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for XMLJSPParser and JspParserFactory.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLJSPParserTest {

    private static final String NS = "xmlns:jsp=\"http://java.sun.com/JSP/Page\"";

    private InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private JspPage parse(String xml) throws Exception {
        XMLJSPParser p = new XMLJSPParser();
        return p.parse(in(xml), "UTF-8", "/t.jspx");
    }

    private <T extends JspElement> List<T> all(JspPage page, Class<T> type) {
        List<T> out = new ArrayList<T>();
        for (JspElement e : page.getElements()) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    @Test
    public void testParserName() {
        assertEquals("XML JSP Parser (JSPX)", new XMLJSPParser().getParserName());
    }

    @Test
    public void testCanParse() throws IOException {
        XMLJSPParser p = new XMLJSPParser();
        assertTrue(p.canParse(in("<?xml version=\"1.0\"?>\n<jsp:root " + NS + "/>"), "UTF-8"));
        assertTrue(p.canParse(in("<html " + NS + "></html>"), null));
        assertFalse(p.canParse(in("<html><% int x; %></html>"), "UTF-8"));
        assertFalse(p.canParse(in("<html></html>"), "UTF-8"));
        assertFalse(p.canParse(in(""), "UTF-8"));
        assertFalse(p.canParse(in("   \n\n  "), "UTF-8"));
        InputStream noMark = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };
        assertFalse(p.canParse(noMark, "UTF-8"));
    }

    @Test
    public void testScriptingElements() throws Exception {
        String xml = "<jsp:root " + NS + " version=\"2.0\">"
                + "<jsp:declaration>int n = 0;</jsp:declaration>"
                + "<jsp:scriptlet>n++;</jsp:scriptlet>"
                + "<jsp:expression>n</jsp:expression>"
                + "<jsp:text>hello</jsp:text>"
                + "<jsp:text></jsp:text>"
                + "</jsp:root>";
        JspPage page = parse(xml);
        assertEquals(1, all(page, DeclarationElement.class).size());
        assertEquals(1, all(page, ScriptletElement.class).size());
        assertEquals(1, all(page, ExpressionElement.class).size());
        assertFalse(all(page, TextElement.class).isEmpty());
    }

    @Test
    public void testDirectivesAndTaglibs() throws Exception {
        String xml = "<jsp:root " + NS + " xmlns:c=\"urn:c\" version=\"2.0\">"
                + "<jsp:directive.page contentType=\"text/html\"/>"
                + "<jsp:directive.taglib prefix=\"a\" uri=\"urn:a\"/>"
                + "<jsp:directive.taglib prefix=\"b\" tagdir=\"/WEB-INF/tags\"/>"
                + "<jsp:directive.taglib prefix=\"z\"/>"
                + "<jsp:directive.taglib uri=\"urn:q\"/>"
                + "</jsp:root>";
        JspPage page = parse(xml);
        assertEquals(5, all(page, DirectiveElement.class).size());
        assertEquals("urn:a", page.getTaglibUri("a"));
        assertEquals("tagdir:/WEB-INF/tags", page.getTaglibUri("b"));
        assertNull(page.getTaglibUri("z"));
    }

    @Test
    public void testRootNamespaceDeclarationsRegisterTaglibs() throws Exception {
        String xml = "<jsp:root " + NS + " xmlns:c=\"urn:c\" xmlns:fn=\"urn:fn\" version=\"2.0\">"
                + "<jsp:text>x</jsp:text>"
                + "</jsp:root>";
        JspPage page = parse(xml);
        assertEquals("urn:c", page.getTaglibUri("c"));
        assertEquals("urn:fn", page.getTaglibUri("fn"));
        assertNull(page.getTaglibUri("jsp"));
    }

    @Test
    public void testActionsAndNesting() throws Exception {
        String xml = "<jsp:root " + NS + " version=\"2.0\">"
                + "<jsp:include page=\"/x.jsp\"><jsp:param name=\"a\" value=\"b\"/></jsp:include>"
                + "<jsp:forward page=\"/y.jsp\"/>"
                + "<jsp:useBean id=\"b\" class=\"java.lang.Object\"/>"
                + "<jsp:setProperty name=\"b\" property=\"p\" value=\"v\"/>"
                + "<jsp:getProperty name=\"b\" property=\"p\"/>"
                + "<jsp:plugin type=\"applet\"><jsp:params/><jsp:fallback/></jsp:plugin>"
                + "<jsp:element name=\"e\"><jsp:attribute name=\"a\"/><jsp:body/></jsp:element>"
                + "<jsp:output omit-xml-declaration=\"yes\"/>"
                + "<jsp:invoke fragment=\"f\"/><jsp:doBody/>"
                + "<jsp:custom foo=\"bar\"/>"
                + "</jsp:root>";
        JspPage page = parse(xml);
        List<StandardActionElement> actions = all(page, StandardActionElement.class);
        assertTrue(actions.size() >= 8);
        StandardActionElement first = actions.get(0);
        assertEquals("include", first.getActionName());
        assertEquals(1, first.getChildren().size());
        assertEquals(1, all(page, CustomTagElement.class).size());
    }

    @Test
    public void testPlainElementsAsText() throws Exception {
        String xml = "<html " + NS + "><body class=\"a&amp;b\"><p>hi &lt; there</p></body></html>";
        JspPage page = parse(xml);
        StringBuilder sb = new StringBuilder();
        for (TextElement t : all(page, TextElement.class)) {
            sb.append(t.getContent());
        }
        String text = sb.toString();
        assertTrue(text.indexOf("<body class=\"a&amp;b\">") >= 0);
        assertTrue(text.indexOf("<p>") >= 0);
    }

    @Test
    public void testPrefixedWithoutNamespace() throws Exception {
        XMLJSPParser p = new XMLJSPParser();
        try {
            JspPage page = p.parse(in("<root><jsp:scriptlet>x();</jsp:scriptlet></root>"),
                    "UTF-8", "/n.jspx");
            assertNotNull(page);
        } catch (JspParseException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testScriptingInvalid() throws Exception {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("getScriptingInvalid", "true");
        List<String> pats = new ArrayList<String>();
        pats.add("*.jspx");
        List<JspPropertyGroupDescriptor> groups = new ArrayList<JspPropertyGroupDescriptor>();
        groups.add(JspStubSupport.group(pats, props));
        JspPropertyGroupResolver.ResolvedJSPProperties rp =
                JspPropertyGroupResolver.resolve("/t.jspx", JspStubSupport.config(null, groups));
        XMLJSPParser p = new XMLJSPParser();
        String xml = "<jsp:root " + NS + "><jsp:scriptlet>x();</jsp:scriptlet></jsp:root>";
        try {
            p.parse(in(xml), "UTF-8", "/t.jspx", rp);
            fail("expected scripting failure");
        } catch (JspParseException e) {
            assertTrue(e.getMessage().indexOf("Scripting is disabled") >= 0);
        }
    }

    @Test
    public void testMalformedXml() throws Exception {
        XMLJSPParser p = new XMLJSPParser();
        try {
            p.parse(in("<jsp:root " + NS + "><unclosed>"), "UTF-8", "/bad.jspx");
            fail("expected parse failure");
        } catch (JspParseException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testFactoryStatics() {
        assertTrue(JspParserFactory.isXmlFormat("a.jspx"));
        assertTrue(JspParserFactory.isXmlFormat("A.JSP.XML"));
        assertTrue(JspParserFactory.isXmlFormat("a.jspx.bak"));
        assertFalse(JspParserFactory.isXmlFormat("a.jsp"));
        assertFalse(JspParserFactory.isXmlFormat(null));
        assertEquals("UTF-8", JspParserFactory.determineEncoding(null));
        assertEquals("UTF-8", JspParserFactory.determineEncoding("text/html"));
        assertEquals("ISO-8859-1", JspParserFactory.determineEncoding("text/html; charset=ISO-8859-1"));
    }

    @Test
    public void testFactoryParsersAndDetection() throws Exception {
        JspParserFactory f = new JspParserFactory();
        assertTrue(f.createParser(JspParserFactory.ParserType.XML) instanceof XMLJSPParser);
        assertTrue(f.createParser(JspParserFactory.ParserType.TRADITIONAL) instanceof TraditionalJSPParser);
        int before = f.getAvailableParsers().size();
        JspParser extra = new TraditionalJSPParser();
        f.registerParser(extra);
        f.registerParser(extra);
        f.registerParser(null);
        assertEquals(before + 1, f.getAvailableParsers().size());
        f.unregisterParser(extra);
        assertEquals(before, f.getAvailableParsers().size());

        String xml = "<jsp:root " + NS + "><jsp:text>x</jsp:text></jsp:root>";
        JspPage page = f.parseJSP(in(xml), "UTF-8", "/t.jspx", null);
        assertFalse(page.getElements().isEmpty());
        InputStream noMark = new InputStream() {
            private final InputStream d = in("<html>plain</html>");

            @Override
            public int read() throws IOException {
                return d.read();
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };
        JspPage plain = f.parseJSP(noMark, "UTF-8", "/p.jsp");
        assertNotNull(plain);
    }

    @Test
    public void testFactoryForcedXml() throws Exception {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("getIsXml", "true");
        List<String> pats = new ArrayList<String>();
        pats.add("*.jsp");
        List<JspPropertyGroupDescriptor> groups = new ArrayList<JspPropertyGroupDescriptor>();
        groups.add(JspStubSupport.group(pats, props));
        JspPropertyGroupResolver.ResolvedJSPProperties rp =
                JspPropertyGroupResolver.resolve("/t.jsp", JspStubSupport.config(null, groups));
        JspParserFactory f = new JspParserFactory();
        String xml = "<root><a>x</a></root>";
        JspPage page = f.parseJSP(in(xml), "UTF-8", "/t.jsp", rp);
        assertNotNull(page);
    }
}
