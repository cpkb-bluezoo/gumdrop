/*
 * JspCodeGeneratorTest.java
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

import jakarta.servlet.ServletContext;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for JspCodeGenerator.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspCodeGeneratorTest {

    private static final String TLD = "<?xml version=\"1.0\"?>\n<taglib>\n"
            + "<short-name>t</short-name><uri>http://example.com/t</uri>\n"
            + "<tag><name>hi</name><tag-class>com.example.Hi</tag-class>"
            + "<body-content>empty</body-content>"
            + "<attribute><name>msg</name><rtexprvalue>true</rtexprvalue></attribute>"
            + "<attribute><name>plain</name></attribute></tag>\n"
            + "<tag><name>box</name><tag-class>com.example.Box</tag-class>"
            + "<body-content>JSP</body-content></tag>\n"
            + "<tag><name>noclass</name><body-content>empty</body-content></tag>\n"
            + "</taglib>";

    private String generate(JspPage page, TaglibRegistry reg) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JspCodeGenerator gen = new JspCodeGenerator(page, out, reg);
        gen.generateCode();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private Map<String, String> attrs(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private TaglibRegistry registry() {
        Map<String, String> res = new HashMap<String, String>();
        res.put("/WEB-INF/t.tld", TLD);
        ServletContext ctx = JspStubSupport.context(res, null);
        return new TaglibRegistry(ctx);
    }

    @Test
    public void testClassNameDerivation() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JspCodeGenerator g = new JspCodeGenerator(new JspPage("/dir/my-page.jsp", "UTF-8"), out, null);
        assertEquals("MyPage_jsp", g.getGeneratedClassName());
        g = new JspCodeGenerator(new JspPage("/9lives.jsp", "UTF-8"), out, null);
        assertEquals("JSP_9lives_jsp", g.getGeneratedClassName());
        g = new JspCodeGenerator(new JspPage("index", "UTF-8"), out, null);
        assertEquals("Index_jsp", g.getGeneratedClassName());
        g = new JspCodeGenerator(new JspPage("/.jsp", "UTF-8"), out, null);
        assertTrue(g.getGeneratedClassName().endsWith("_jsp"));
        g = new JspCodeGenerator(new JspPage(null, "UTF-8"), out, null);
        assertEquals("GeneratedJSP", g.getGeneratedClassName());
        g.setClassName("Custom");
        assertEquals("Custom", g.getGeneratedClassName());
    }

    @Test
    public void testBasicElements() throws IOException {
        JspPage page = new JspPage("/a.jsp", "UTF-8");
        page.addElement(new TextElement("Hello \"w\"\n\t\\é", 1, 1));
        page.addElement(new TextElement("", 1, 1));
        page.addElement(new ScriptletElement("int x = 1;\nint y = 2;", 2, 1));
        page.addElement(new ScriptletElement("   ", 2, 1));
        page.addElement(new ExpressionElement("x + y", 3, 1));
        page.addElement(new ExpressionElement("", 3, 1));
        page.addElement(new DeclarationElement("int field = 3;", 4, 1));
        page.addElement(new CommentElement("ignored", 5, 1));
        page.addElement(new DirectiveElement("include", attrs("file", "x"), 6, 1));
        String src = generate(page, null);
        assertTrue(src.indexOf("public class A_jsp extends HttpServlet") >= 0);
        assertTrue(src.indexOf("out.write(\"Hello \\\"w\\\"\\n\\t\\\\\\u00e9\");") >= 0);
        assertTrue(src.indexOf("int x = 1;") >= 0);
        assertTrue(src.indexOf("out.write(String.valueOf(x + y));") >= 0);
        assertTrue(src.indexOf("// JSP Declarations") >= 0);
        assertTrue(src.indexOf("int field = 3;") >= 0);
        assertTrue(src.indexOf("ignored") < 0);
        assertTrue(src.indexOf("HttpSession session = request.getSession();") >= 0);
        assertTrue(src.indexOf("response.setContentType(\"text/html; charset=UTF-8\");") >= 0);
        assertTrue(src.indexOf("response.setCharacterEncoding(\"UTF-8\");") >= 0);
    }

    @Test
    public void testPageDirectives() throws IOException {
        JspPage page = new JspPage("/b.jsp", "UTF-8");
        Map<String, String> a = new HashMap<String, String>();
        a.put("contentType", "text/plain");
        a.put("session", "false");
        a.put("buffer", "16kb");
        a.put("autoFlush", "false");
        a.put("errorPage", "/err.jsp");
        a.put("isErrorPage", "true");
        a.put("pageEncoding", "ISO-8859-1");
        a.put("language", "java");
        a.put("import", "java.util.List, java.util.Map,,");
        a.put("extends", "com.example.Base");
        a.put("implements", "java.io.Serializable");
        page.addElement(new DirectiveElement("page", a, 1, 1));
        page.addElement(new DirectiveElement("taglib", attrs("prefix", "x"), 1, 1));
        String src = generate(page, null);
        assertTrue(src.indexOf("response.setContentType(\"text/plain\");") >= 0);
        assertTrue(src.indexOf("HttpSession session") < 0);
        assertTrue(src.indexOf("16384") >= 0);
        assertTrue(src.indexOf("import java.util.List;") >= 0);
        assertTrue(src.indexOf("import java.util.Map;") >= 0);
        assertTrue(src.indexOf("extends com.example.Base implements java.io.Serializable") >= 0);
        assertTrue(src.indexOf("ISO-8859-1") >= 0);
    }

    @Test
    public void testBufferNoneAndBadBuffer() throws IOException {
        JspPage page = new JspPage("/c.jsp", "UTF-8");
        page.addElement(new DirectiveElement("page", attrs("buffer", "none"), 1, 1));
        String src = generate(page, null);
        assertTrue(src.indexOf(", 0, false);") >= 0);
        JspPage bad = new JspPage("/d.jsp", "UTF-8");
        bad.addElement(new DirectiveElement("page", attrs("buffer", "zzz"), 1, 1));
        src = generate(bad, null);
        assertTrue(src.indexOf("8192") >= 0);
    }

    @Test
    public void testStandardActions() throws IOException {
        JspPage page = new JspPage("/e.jsp", "UTF-8");
        page.addElement(new StandardActionElement("include", attrs("page", "/inc.jsp"), 1, 1));
        StandardActionElement inc = new StandardActionElement("include", attrs("page", "/inc2.jsp"), 2, 1);
        inc.addChild(new StandardActionElement("param", attrs("name", "a", "value", "b\"c"), 2, 2));
        inc.addChild(new StandardActionElement("param", attrs("name", "n"), 2, 3));
        inc.addChild(new StandardActionElement("param", attrs("value", "v"), 2, 4));
        inc.addChild(new TextElement("t", 2, 5));
        page.addElement(inc);
        StandardActionElement fwd = new StandardActionElement("forward", attrs("page", "/f.jsp"), 3, 1);
        page.addElement(fwd);
        StandardActionElement fwd2 = new StandardActionElement("forward", attrs("page", "/g.jsp"), 3, 1);
        fwd2.addChild(new StandardActionElement("param", attrs("name", "a", "value", "1"), 3, 2));
        fwd2.addChild(new StandardActionElement("param", attrs("name", "b", "value", "2"), 3, 3));
        page.addElement(fwd2);
        page.addElement(new StandardActionElement("include", attrs(), 4, 1));
        page.addElement(new StandardActionElement("forward", attrs(), 4, 1));
        page.addElement(new StandardActionElement("other", attrs(), 4, 1));
        String src = generate(page, null);
        assertTrue(src.indexOf("getRequestDispatcher(\"/inc.jsp\").include") >= 0);
        assertTrue(src.indexOf("StringBuilder _qs") >= 0);
        assertTrue(src.indexOf("getRequestDispatcher(\"/f.jsp\").forward") >= 0);
        assertTrue(src.indexOf("return;") >= 0);
        assertTrue(src.indexOf("Unimplemented action: other") >= 0);
        assertTrue(src.indexOf("_qs.append('&');") >= 0);
    }

    @Test
    public void testBeanActions() throws IOException {
        JspPage page = new JspPage("/f.jsp", "UTF-8");
        page.addElement(new StandardActionElement("useBean",
                attrs("id", "b", "class", "com.example.B", "scope", "session"), 1, 1));
        page.addElement(new StandardActionElement("useBean",
                attrs("id", "c", "class", "com.example.C", "type", "com.example.I", "scope", "request"), 1, 1));
        page.addElement(new StandardActionElement("useBean",
                attrs("id", "d", "class", "com.example.D", "scope", "application"), 1, 1));
        page.addElement(new StandardActionElement("useBean",
                attrs("id", "e", "class", "com.example.E", "scope", "bogus"), 1, 1));
        page.addElement(new StandardActionElement("useBean", attrs("id", "f"), 1, 1));
        page.addElement(new StandardActionElement("setProperty",
                attrs("name", "b", "property", "x", "value", "1"), 2, 1));
        page.addElement(new StandardActionElement("setProperty",
                attrs("name", "b", "property", "x", "value", "${y}"), 2, 1));
        page.addElement(new StandardActionElement("setProperty",
                attrs("name", "b", "property", "x"), 2, 1));
        page.addElement(new StandardActionElement("setProperty",
                attrs("name", "b", "property", "x", "param", "q"), 2, 1));
        page.addElement(new StandardActionElement("setProperty", attrs("name", "b"), 2, 1));
        page.addElement(new StandardActionElement("getProperty",
                attrs("name", "b", "property", "x"), 3, 1));
        page.addElement(new StandardActionElement("getProperty", attrs("name", "b"), 3, 1));
        String src = generate(page, null);
        assertTrue(src.indexOf("pageContext.getAttribute(\"b\", 3)") >= 0);
        assertTrue(src.indexOf("com.example.I c =") >= 0);
        assertTrue(src.indexOf("pageContext.getAttribute(\"d\", 4)") >= 0);
        assertTrue(src.indexOf("pageContext.getAttribute(\"e\", 1)") >= 0);
        assertTrue(src.indexOf("b.setX(\"1\");") >= 0);
        assertTrue(src.indexOf("evaluate(\"${y}\")") >= 0);
        assertTrue(src.indexOf("request.getParameter(\"x\")") >= 0);
        assertTrue(src.indexOf("request.getParameter(\"q\")") >= 0);
        assertTrue(src.indexOf("b.getX()") >= 0);
    }

    @Test
    public void testCustomTagsWithoutRegistry() throws IOException {
        JspPage page = new JspPage("/g.jsp", "UTF-8");
        page.addTagLibrary("t", "http://example.com/t");
        page.addElement(new CustomTagElement("t", "hi", attrs(), 1, 1));
        page.addElement(new CustomTagElement("zz", "hi", attrs(), 2, 1));
        page.addElement(new CustomTagElement(null, null, attrs(), 3, 1));
        String src = generate(page, null);
        assertTrue(src.indexOf("no taglib registry available") >= 0);
        assertTrue(src.indexOf("Unknown taglib prefix 'zz'") >= 0);
        assertTrue(src.indexOf("Invalid custom tag") >= 0 || src.indexOf("Unknown taglib prefix ''") >= 0);
    }

    @Test
    public void testCustomTagsWithRegistry() throws IOException {
        JspPage page = new JspPage("/h.jsp", "UTF-8");
        page.addTagLibrary("t", "http://example.com/t");
        page.addTagLibrary("u", "http://unknown.example/u");
        page.addElement(new CustomTagElement("t", "hi",
                attrs("msg", "${a}", "plain", "p\"q", "bogus", "z"), 1, 1));
        page.addElement(new CustomTagElement("t", "hi", attrs("msg", "x${a}y"), 2, 1));
        page.addElement(new CustomTagElement("t", "hi", attrs("msg", "lit"), 3, 1));
        CustomTagElement box = new CustomTagElement("t", "box", attrs(), 4, 1);
        box.addChild(new TextElement("body", 4, 2));
        page.addElement(box);
        page.addElement(new CustomTagElement("t", "box", attrs(), 5, 1));
        page.addElement(new CustomTagElement("t", "missing", attrs(), 6, 1));
        page.addElement(new CustomTagElement("t", "noclass", attrs(), 7, 1));
        page.addElement(new CustomTagElement("u", "x", attrs(), 8, 1));
        String src = generate(page, registry());
        assertTrue(src.indexOf("Hi tag_t_hi_1 = new Hi();") >= 0);
        assertTrue(src.indexOf("tag_t_hi_1.setMsg(String.valueOf(") >= 0);
        assertTrue(src.indexOf("evaluateTemplate(\"x${a}y\")") >= 0);
        assertTrue(src.indexOf("tag_t_hi_3.setMsg(\"lit\");") >= 0);
        assertTrue(src.indexOf("Unknown attribute 'bogus'") >= 0);
        assertTrue(src.indexOf("doEndTag() == jakarta.servlet.jsp.tagext.Tag.SKIP_PAGE") >= 0);
        assertTrue(src.indexOf("not found in taglib") >= 0);
        assertTrue(src.indexOf("No tag class specified") >= 0);
        assertTrue(src.indexOf("Unable to resolve taglib URI") >= 0);
        assertTrue(src.indexOf("import com.example.Hi;") >= 0);
    }

    @Test
    public void testJspPropertiesConstructor() throws IOException {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("getBuffer", "4kb");
        props.put("getDefaultContentType", "text/xml");
        java.util.List<String> pats = new java.util.ArrayList<String>();
        pats.add("*.jsp");
        java.util.List<jakarta.servlet.descriptor.JspPropertyGroupDescriptor> groups =
                new java.util.ArrayList<jakarta.servlet.descriptor.JspPropertyGroupDescriptor>();
        groups.add(JspStubSupport.group(pats, props));
        JspPropertyGroupResolver.ResolvedJSPProperties rp =
                JspPropertyGroupResolver.resolve("/p.jsp", JspStubSupport.config(null, groups));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JspPage page = new JspPage("/p.jsp", "UTF-8");
        new JspCodeGenerator(page, out, null, rp).generateCode();
        String src = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(src.length() > 0);
        assertNotNull(src);

        props.put("getBuffer", "none");
        rp = JspPropertyGroupResolver.resolve("/p.jsp", JspStubSupport.config(null, groups));
        new JspCodeGenerator(page, new ByteArrayOutputStream(), null, rp);
        props.put("getBuffer", "bad");
        rp = JspPropertyGroupResolver.resolve("/p.jsp", JspStubSupport.config(null, groups));
        new JspCodeGenerator(page, new ByteArrayOutputStream(), null, rp);
        new JspCodeGenerator(page, new ByteArrayOutputStream(), null, null);
    }

    @Test
    public void testParsedPageRoundTrip() throws Exception {
        String jsp = "<%@ page import=\"java.util.*\" %><%! int n = 0; %>"
                + "<html><% for (int i = 0; i < 2; i++) { %><b><%= i %></b><% } %>"
                + "<%-- c --%><jsp:include page=\"/x.jsp\"/></html>";
        JspParserFactory f = new JspParserFactory();
        JspPage page = f.parseJSP(new ByteArrayInputStream(jsp.getBytes(StandardCharsets.UTF_8)),
                "UTF-8", "/rt.jsp", null);
        String src = generate(page, null);
        assertTrue(src.indexOf("class Rt_jsp") >= 0);
        assertTrue(src.indexOf("int n = 0;") >= 0);
    }
}
