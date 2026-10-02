/*
 * JspElementsTest.java
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

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for JSP AST element classes, JspParseException and
 * JspSourceLocation.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspElementsTest {

    /** Visitor recording the last visited element kind. */
    private static class Recorder implements JspElementVisitor {
        String last;

        public void visitText(TextElement e) {
            last = "text";
        }

        public void visitScriptlet(ScriptletElement e) {
            last = "scriptlet";
        }

        public void visitExpression(ExpressionElement e) {
            last = "expression";
        }

        public void visitDeclaration(DeclarationElement e) {
            last = "declaration";
        }

        public void visitDirective(DirectiveElement e) {
            last = "directive";
        }

        public void visitComment(CommentElement e) {
            last = "comment";
        }

        public void visitCustomTag(CustomTagElement e) {
            last = "custom";
        }

        public void visitStandardAction(StandardActionElement e) {
            last = "action";
        }
    }

    @Test
    public void testTextElement() throws Exception {
        TextElement t = new TextElement("hello", 1, 2);
        assertEquals(JspElement.Type.TEXT, t.getType());
        assertEquals(1, t.getLineNumber());
        assertEquals(2, t.getColumnNumber());
        assertEquals("hello", t.getContent());
        assertFalse(t.isWhitespaceOnly());
        assertTrue(new TextElement("  \n", 1, 1).isWhitespaceOnly());
        assertEquals("", new TextElement(null, 1, 1).getContent());
        Recorder r = new Recorder();
        t.accept(r);
        assertEquals("text", r.last);
        assertTrue(t.toString().indexOf("hello") > 0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append('x');
        }
        assertTrue(new TextElement(sb.toString(), 1, 1).toString().indexOf("...") > 0);
        assertEquals(t, new TextElement("hello", 1, 2));
        assertEquals(t.hashCode(), new TextElement("hello", 1, 2).hashCode());
        assertFalse(t.equals(new TextElement("hello", 2, 2)));
        assertFalse(t.equals(null));
        assertFalse(t.equals("hello"));
        assertTrue(t.equals(t));
    }

    @Test
    public void testScriptletElement() throws Exception {
        ScriptletElement s = new ScriptletElement("int x = 1;", 3, 4);
        assertEquals(JspElement.Type.SCRIPTLET, s.getType());
        assertEquals("int x = 1;", s.getCode());
        assertEquals(3, s.getLineNumber());
        assertEquals(4, s.getColumnNumber());
        assertFalse(s.isEmpty());
        assertTrue(new ScriptletElement("  ", 1, 1).isEmpty());
        assertTrue(new ScriptletElement("// only comment", 1, 1).isEmpty());
        assertTrue(new ScriptletElement("/* c */", 1, 1).isEmpty());
        assertEquals("", new ScriptletElement(null, 1, 1).getCode());
        Recorder r = new Recorder();
        s.accept(r);
        assertEquals("scriptlet", r.last);
        assertTrue(s.toString().indexOf("int x") > 0);
        assertTrue(new ScriptletElement("a\nb", 1, 1).toString().indexOf('\n') < 0);
        assertEquals(s, new ScriptletElement("int x = 1;", 3, 4));
        assertEquals(s.hashCode(), new ScriptletElement("int x = 1;", 3, 4).hashCode());
        assertFalse(s.equals(new ScriptletElement("int y;", 3, 4)));
        assertFalse(s.equals(null));
        assertFalse(s.equals("x"));
        assertTrue(s.equals(s));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append('x');
        }
        assertTrue(new ScriptletElement(sb.toString(), 1, 1).toString().indexOf("...") > 0);
    }

    @Test
    public void testExpressionElement() throws Exception {
        ExpressionElement e = new ExpressionElement("  foo  ", 1, 2);
        assertEquals(JspElement.Type.EXPRESSION, e.getType());
        assertEquals("foo", e.getExpression());
        assertFalse(e.isEmpty());
        assertTrue(e.isSimpleVariable());
        assertFalse(new ExpressionElement("a + b", 1, 1).isSimpleVariable());
        assertFalse(new ExpressionElement("", 1, 1).isSimpleVariable());
        assertTrue(new ExpressionElement(null, 1, 1).isEmpty());
        Recorder r = new Recorder();
        e.accept(r);
        assertEquals("expression", r.last);
        assertEquals(1, e.getLineNumber());
        assertEquals(2, e.getColumnNumber());
        assertTrue(e.toString().indexOf("foo") > 0);
        assertEquals(e, new ExpressionElement("foo", 1, 2));
        assertEquals(e.hashCode(), new ExpressionElement("foo", 1, 2).hashCode());
        assertFalse(e.equals(new ExpressionElement("bar", 1, 2)));
        assertFalse(e.equals(null));
        assertFalse(e.equals("x"));
        assertTrue(e.equals(e));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append('x');
        }
        assertTrue(new ExpressionElement(sb.toString(), 1, 1).toString().indexOf("...") > 0);
    }

    @Test
    public void testDeclarationElement() throws Exception {
        DeclarationElement d = new DeclarationElement("int count = 0;", 5, 6);
        assertEquals(JspElement.Type.DECLARATION, d.getType());
        assertEquals("int count = 0;", d.getDeclaration());
        assertFalse(d.isEmpty());
        assertTrue(new DeclarationElement("// x", 1, 1).isEmpty());
        assertTrue(new DeclarationElement(null, 1, 1).isEmpty());
        assertTrue(d.containsFields());
        assertFalse(d.containsMethods());
        DeclarationElement m = new DeclarationElement("public void run() { }", 1, 1);
        assertTrue(m.containsMethods());
        Recorder r = new Recorder();
        d.accept(r);
        assertEquals("declaration", r.last);
        assertEquals(5, d.getLineNumber());
        assertEquals(6, d.getColumnNumber());
        assertTrue(d.toString().indexOf("count") > 0);
        assertEquals(d, new DeclarationElement("int count = 0;", 5, 6));
        assertEquals(d.hashCode(), new DeclarationElement("int count = 0;", 5, 6).hashCode());
        assertFalse(d.equals(m));
        assertFalse(d.equals(null));
        assertFalse(d.equals("x"));
        assertTrue(d.equals(d));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append('x');
        }
        assertTrue(new DeclarationElement(sb.toString(), 1, 1).toString().indexOf("...") > 0);
    }

    @Test
    public void testCommentElement() throws Exception {
        CommentElement c = new CommentElement("a\nb\nc", 7, 8);
        assertEquals(JspElement.Type.COMMENT, c.getType());
        assertEquals("a\nb\nc", c.getComment());
        assertEquals(3, c.getLineCount());
        assertEquals(1, new CommentElement("", 1, 1).getLineCount());
        assertEquals(1, new CommentElement(null, 1, 1).getLineCount());
        assertTrue(new CommentElement("  ", 1, 1).isEmpty());
        assertFalse(c.isEmpty());
        Recorder r = new Recorder();
        c.accept(r);
        assertEquals("comment", r.last);
        assertEquals(7, c.getLineNumber());
        assertEquals(8, c.getColumnNumber());
        assertNotNull(c.toString());
        assertEquals(c, new CommentElement("a\nb\nc", 7, 8));
        assertEquals(c.hashCode(), new CommentElement("a\nb\nc", 7, 8).hashCode());
        assertFalse(c.equals(new CommentElement("z", 7, 8)));
        assertFalse(c.equals(null));
        assertFalse(c.equals("x"));
        assertTrue(c.equals(c));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append('x');
        }
        assertTrue(new CommentElement(sb.toString(), 1, 1).toString().indexOf("...") > 0);
    }

    @Test
    public void testDirectiveElement() throws Exception {
        Map<String, String> attrs = new HashMap<String, String>();
        attrs.put("import", "java.util.*");
        DirectiveElement d = new DirectiveElement(" page ", attrs, 1, 2);
        assertEquals(JspElement.Type.DIRECTIVE, d.getType());
        assertEquals("page", d.getName());
        assertTrue(d.isPageDirective());
        assertFalse(d.isIncludeDirective());
        assertFalse(d.isTaglibDirective());
        assertTrue(new DirectiveElement("include", null, 1, 1).isIncludeDirective());
        assertTrue(new DirectiveElement("taglib", null, 1, 1).isTaglibDirective());
        assertEquals("", new DirectiveElement(null, null, 1, 1).getName());
        assertEquals("java.util.*", d.getAttribute("import"));
        assertTrue(d.hasAttribute("import"));
        assertFalse(d.hasAttribute("other"));
        assertEquals(1, d.getAttributes().size());
        Recorder r = new Recorder();
        d.accept(r);
        assertEquals("directive", r.last);
        assertEquals(1, d.getLineNumber());
        assertEquals(2, d.getColumnNumber());
        assertTrue(d.toString().indexOf("import") > 0);
        attrs.put("session", "false");
        assertTrue(d.toString().indexOf("session") < 0);
        DirectiveElement d2 = new DirectiveElement("page", new HashMap<String, String>(attrs), 1, 2);
        assertFalse(d.equals(d2));
        assertFalse(d.equals(null));
        assertFalse(d.equals("x"));
        assertTrue(d.equals(d));
        Map<String, String> same = new HashMap<String, String>();
        same.put("import", "java.util.*");
        DirectiveElement d3 = new DirectiveElement("page", same, 1, 2);
        assertEquals(d, d3);
        assertEquals(d.hashCode(), d3.hashCode());
        assertNotNull(d2.toString());
    }

    @Test
    public void testStandardActionElement() throws Exception {
        Map<String, String> attrs = new HashMap<String, String>();
        attrs.put("page", "/x.jsp");
        StandardActionElement a = new StandardActionElement("include", attrs, 1, 2);
        assertEquals(JspElement.Type.STANDARD_ACTION, a.getType());
        assertEquals("include", a.getActionName());
        assertEquals("jsp:include", a.getQualifiedName());
        assertTrue(a.isIncludeAction());
        assertFalse(a.isForwardAction());
        assertFalse(a.isUseBeanAction());
        assertTrue(new StandardActionElement("forward", null, 1, 1).isForwardAction());
        assertTrue(new StandardActionElement("useBean", null, 1, 1).isUseBeanAction());
        assertEquals("", new StandardActionElement(null, null, 1, 1).getActionName());
        assertEquals("/x.jsp", a.getAttribute("page"));
        assertEquals(1, a.getAttributes().size());
        assertTrue(a.getChildren().isEmpty());
        a.addChild(new TextElement("t", 1, 1));
        assertEquals(1, a.getChildren().size());
        Recorder r = new Recorder();
        a.accept(r);
        assertEquals("action", r.last);
        assertEquals(1, a.getLineNumber());
        assertEquals(2, a.getColumnNumber());
        assertTrue(a.toString().indexOf("jsp:include") > 0);
    }

    @Test
    public void testCustomTagElement() throws Exception {
        Map<String, String> attrs = new HashMap<String, String>();
        attrs.put("a", "b");
        CustomTagElement c = new CustomTagElement("x", "tag", attrs, 4, 5);
        assertEquals(JspElement.Type.CUSTOM_TAG, c.getType());
        assertEquals("x", c.getPrefix());
        assertEquals("tag", c.getTagName());
        assertEquals("x:tag", c.getQualifiedName());
        assertEquals("tag", new CustomTagElement(null, "tag", null, 1, 1).getQualifiedName());
        assertEquals("", new CustomTagElement("p", null, null, 1, 1).getTagName());
        assertEquals(1, c.getAttributes().size());
        assertTrue(c.getChildren().isEmpty());
        c.addChild(new TextElement("t", 1, 1));
        assertEquals(1, c.getChildren().size());
        Recorder r = new Recorder();
        c.accept(r);
        assertEquals("custom", r.last);
        assertEquals(4, c.getLineNumber());
        assertEquals(5, c.getColumnNumber());
        assertTrue(c.toString().indexOf("x:tag") > 0);
    }

    @Test
    public void testJspParseException() {
        JspParseException e = new JspParseException("bad");
        assertEquals("bad", e.getMessage());
        assertNull(e.getJSPUri());
        assertEquals(-1, e.getLineNumber());
        assertEquals(-1, e.getColumnNumber());
        Throwable cause = new RuntimeException();
        JspParseException c = new JspParseException("bad", cause);
        assertSame(cause, c.getCause());
        JspParseException f = new JspParseException("bad", "/a.jsp", 3, 4);
        assertEquals("/a.jsp:3:4: bad", f.getMessage());
        assertEquals("/a.jsp", f.getJSPUri());
        assertEquals(3, f.getLineNumber());
        assertEquals(4, f.getColumnNumber());
        assertEquals("/a.jsp:3: bad", new JspParseException("bad", "/a.jsp", 3, -1).getMessage());
        assertEquals("/a.jsp: bad", new JspParseException("bad", "/a.jsp", -1, -1).getMessage());
        JspParseException g = new JspParseException("bad", "/a.jsp", 1, 2, cause);
        assertSame(cause, g.getCause());
        assertEquals("/a.jsp:1:2: bad", g.getMessage());
    }

    @Test
    public void testJspSourceLocation() {
        JspSourceLocation a = new JspSourceLocation("/a.jsp", 3);
        assertEquals("/a.jsp", a.getJspFile());
        assertEquals(3, a.getJspLine());
        assertEquals(0, a.getJspColumn());
        assertNull(a.getElementType());
        assertEquals("/a.jsp:3", a.toString());
        JspSourceLocation b = new JspSourceLocation("/a.jsp", 3, 7);
        assertEquals("/a.jsp:3:7", b.toString());
        JspSourceLocation c = new JspSourceLocation("/a.jsp", 3, 7, "scriptlet");
        assertEquals("scriptlet", c.getElementType());
        assertEquals("/a.jsp:3:7 (scriptlet)", c.toString());
    }
}
