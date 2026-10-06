/*
 * JavaSnippetsTest.java
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

/**
 * Unit tests for {@link JavaSnippets}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JavaSnippetsTest {

    private static final int NONE = 0;
    private static final int M = JavaSnippets.METHODS;
    private static final int F = JavaSnippets.FIELDS;

    // ---- isBlank ----

    @Test
    public void testIsBlank() {
        assertTrue(JavaSnippets.isBlank(""));
        assertTrue(JavaSnippets.isBlank(" \t\r\n"));
        assertTrue(JavaSnippets.isBlank("// a\n/* b\n c */ // d"));
        assertTrue(JavaSnippets.isBlank("//"));
        assertTrue(JavaSnippets.isBlank("/**/"));
        assertFalse(JavaSnippets.isBlank("x"));
        assertFalse(JavaSnippets.isBlank("/"));
        assertFalse(JavaSnippets.isBlank("/ /"));
        assertFalse(JavaSnippets.isBlank("/* open"));
        assertFalse(JavaSnippets.isBlank("// a\nx"));
    }

    // ---- memberKinds: classification ----

    @Test
    public void testEmptyAndBlank() {
        assertEquals(NONE, JavaSnippets.memberKinds(""));
        assertEquals(NONE, JavaSnippets.memberKinds("  \n"));
    }

    @Test
    public void testFields() {
        assertEquals(F, JavaSnippets.memberKinds("int x;"));
        assertEquals(F, JavaSnippets.memberKinds("private static final int X = 1;"));
        assertEquals(F, JavaSnippets.memberKinds("int x = foo(1, 2);"));
        assertEquals(F, JavaSnippets.memberKinds("java.util.Map<String, Integer> m = new java.util.HashMap<String, Integer>();"));
        assertEquals(F, JavaSnippets.memberKinds("int[] a = { 1, 2, 3 };"));
    }

    @Test
    public void testUnterminatedFieldIsNotAField() {
        assertEquals(NONE, JavaSnippets.memberKinds("int x"));
    }

    @Test
    public void testEmptyStatementIsNotAField() {
        assertEquals(NONE, JavaSnippets.memberKinds(" ; ; "));
    }

    @Test
    public void testMethods() {
        assertEquals(M, JavaSnippets.memberKinds("void f() { }"));
        assertEquals(M, JavaSnippets.memberKinds("Foo() { }"));
        assertEquals(M, JavaSnippets.memberKinds("int f(int a, String b) throws Exception { return a; }"));
        assertEquals(M, JavaSnippets.memberKinds("void f() { if (x) { y(); } else { z(); } }"));
    }

    @Test
    public void testAbstractSignatureIsNeither() {
        assertEquals(NONE, JavaSnippets.memberKinds("void f();"));
    }

    @Test
    public void testInitializersAndNestedTypesAreNeither() {
        assertEquals(NONE, JavaSnippets.memberKinds("static { x(); }"));
        assertEquals(NONE, JavaSnippets.memberKinds("{ x(); }"));
        assertEquals(NONE, JavaSnippets.memberKinds("class A { int x; void f() { } }"));
        assertEquals(NONE, JavaSnippets.memberKinds("enum E { A, B }"));
    }

    @Test
    public void testMixedMembers() {
        assertEquals(F | M, JavaSnippets.memberKinds("int n;\nvoid f() { }"));
        assertEquals(F | M, JavaSnippets.memberKinds("void f() { }\nint n;"));
        assertEquals(M, JavaSnippets.memberKinds("void f() { }\nstatic { }"));
    }

    @Test
    public void testAnonymousClassFieldIsAFieldNotAMethod() {
        assertEquals(F, JavaSnippets.memberKinds(
            "Runnable r = new Runnable() { public void run() { } };"));
    }

    @Test
    public void testAnonymousClassInsideArguments() {
        assertEquals(F, JavaSnippets.memberKinds(
            "Object o = make(new Runnable() { public void run() { go(); } });"));
    }

    @Test
    public void testSemicolonInsideParenthesesDoesNotEndMember() {
        assertEquals(NONE, JavaSnippets.memberKinds("void f(int a; int b) { }".replace("{ }", "")));
        assertEquals(M, JavaSnippets.memberKinds("void f(int a; int b) { }"));
    }

    @Test
    public void testUnbalancedCloseParenDoesNotBreakScan() {
        assertEquals(F, JavaSnippets.memberKinds(") int x;"));
    }

    @Test
    public void testEqualsInsideParenthesesIsIgnored() {
        assertEquals(M, JavaSnippets.memberKinds("@A(x = 1) void f() { }"));
        assertEquals(NONE, JavaSnippets.memberKinds("@A(x = 1) void f();"));
    }

    @Test
    public void testDivisionOperatorIsNotAComment() {
        assertEquals(F, JavaSnippets.memberKinds("int x = 4 / 2;"));
        assertEquals(F, JavaSnippets.memberKinds("int x = 4 /"+ " 2;"));
        assertEquals(NONE, JavaSnippets.memberKinds("/"));
    }

    // ---- memberKinds: comments and literals ----

    @Test
    public void testCommentsAreIgnored() {
        assertEquals(NONE, JavaSnippets.memberKinds("// int x;\n/* void f() { } */"));
        assertEquals(F, JavaSnippets.memberKinds("int /* ; */ x; // trailing"));
        assertEquals(NONE, JavaSnippets.memberKinds("/* unterminated void f() { }"));
        assertEquals(NONE, JavaSnippets.memberKinds("// unterminated line"));
        assertEquals(NONE, JavaSnippets.memberKinds("/* a */ // b"));
    }

    @Test
    public void testCommentInsideBodyIsSkipped() {
        assertEquals(M, JavaSnippets.memberKinds("void f() { /* } */ // }\n int y; }"));
    }

    @Test
    public void testStringAndCharLiterals() {
        assertEquals(F, JavaSnippets.memberKinds("String s = \"a(){\"; char c = '{';"));
        assertEquals(F, JavaSnippets.memberKinds("String s = \"q\\\"; void f() { }\";"));
        assertEquals(F, JavaSnippets.memberKinds("char c = '\\'';"));
        assertEquals(M, JavaSnippets.memberKinds("void f() { String s = \"}\"; }"));
    }

    @Test
    public void testLiteralInsideParentheses() {
        assertEquals(F, JavaSnippets.memberKinds("int x = f(\";\", ')');"));
    }

    @Test
    public void testUnterminatedLiteralStopsAtNewline() {
        assertEquals(F, JavaSnippets.memberKinds("String s = \"open\nint x;"));
        assertEquals(NONE, JavaSnippets.memberKinds("String s = \"open"));
        assertEquals(NONE, JavaSnippets.memberKinds("String s = \"esc\\"));
    }

    @Test
    public void testTextBlock() {
        assertEquals(F, JavaSnippets.memberKinds(
            "String s = \"\"\"\n  void f() { }; \" \\\"\"\" \"\"\";"));
        assertEquals(NONE, JavaSnippets.memberKinds("String s = \"\"\"\nunterminated"));
        assertEquals(F, JavaSnippets.memberKinds("String s = \"\"\"\n\\\"\"\"\n\"\"\";"));
    }

    @Test
    public void testUnterminatedBodyConsumesRest() {
        assertEquals(M, JavaSnippets.memberKinds("void f() { int x;"));
    }

    @Test
    public void testLiteralMakesAMemberNonEmpty() {
        assertEquals(F, JavaSnippets.memberKinds("\"x\";"));
        assertEquals(NONE, JavaSnippets.memberKinds("/* c */;"));
    }
}
