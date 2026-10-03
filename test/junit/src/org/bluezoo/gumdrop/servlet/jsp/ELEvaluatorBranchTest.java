/*
 * ELEvaluatorBranchTest.java
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

import com.sun.net.httpserver.Headers;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Branch-level unit tests for {@link ELEvaluator}: operator parsing and
 * word-operator boundaries, every comparison and arithmetic arm, bracket and
 * dotted access on maps, lists, arrays and beans, implicit objects backed by a
 * mock request and servlet context, method invocation and the blocked-class
 * guard, and template scanning. All collaborators are hand-written mocks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ELEvaluatorBranchTest {

    private MockPageContext pageContext;
    private MockRequest request;
    private ELEvaluator evaluator;

    @Before
    public void setUp() {
        request = new MockRequest();
        pageContext = new MockPageContext(request);
        evaluator = new ELEvaluator(pageContext);
    }

    private Object eval(String expression) throws Exception {
        return evaluator.evaluate(expression);
    }

    private void assertEval(Object expected, String expression) throws Exception {
        Object actual = evaluator.evaluate(expression);
        assertEquals(expression, expected, actual);
    }

    private void assertNumber(double expected, String expression) throws Exception {
        Object actual = evaluator.evaluate(expression);
        assertTrue(expression, actual instanceof Number);
        double value = ((Number) actual).doubleValue();
        assertEquals(expression, expected, value, 0.0001);
    }

    private void assertFails(String expression) {
        try {
            evaluator.evaluate(expression);
            fail("expected ELException for " + expression);
        } catch (ELEvaluator.ELException e) {
            assertTrue(e.getMessage().contains(expression));
        }
    }

    private void put(String name, Object value) {
        pageContext.setAttribute(name, value, PageContext.PAGE_SCOPE);
    }

    // ===== evaluate entry and delimiters =====

    @Test
    public void testNullAndEmptyExpressionsEvaluateToNull() throws Exception {
        assertNull(evaluator.evaluate(null));
        assertNull(evaluator.evaluate(""));
    }

    @Test
    public void testDelimitersAreOptionalAndHashDelimiterWorks() throws Exception {
        assertNumber(2, "#{2}");
        assertNumber(3, "  ${3}  ");
        assertEval("", "${}");
        put("plain", "v");
        assertEval("v", "plain");
    }

    @Test
    public void testFailingExpressionIsWrappedInElException() throws Exception {
        put("bean", new Bean());
        try {
            evaluator.evaluate("${bean.boom()}");
            fail("expected ELException");
        } catch (ELEvaluator.ELException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
    }

    // ===== template scanning =====

    @Test
    public void testTemplateNullAndNoExpressions() throws Exception {
        assertNull(evaluator.evaluateTemplate(null));
        assertEquals("plain text", evaluator.evaluateTemplate("plain text"));
        assertEquals("", evaluator.evaluateTemplate(""));
    }

    @Test
    public void testTemplateMixesDollarAndHashDelimiters() throws Exception {
        put("x", "X");
        assertEquals("a X b X", evaluator.evaluateTemplate("a ${x} b #{x}"));
        assertEquals("a X b X c", evaluator.evaluateTemplate("a #{x} b ${x} c"));
        assertEquals("X", evaluator.evaluateTemplate("#{x}"));
    }

    @Test
    public void testTemplateNullValueRendersEmpty() throws Exception {
        assertEquals("[]", evaluator.evaluateTemplate("[${missing}]"));
    }

    @Test
    public void testTemplateBraceInsideStringDoesNotEndExpression() throws Exception {
        assertEquals("a}b", evaluator.evaluateTemplate("${'a}b'}"));
        assertEquals("q}\\\"", evaluator.evaluateTemplate("${\"q}\\\"\"}"));
    }

    @Test
    public void testTemplateNestedBracesAreBalanced() throws Exception {
        assertEquals("", evaluator.evaluateTemplate("${a{b}c}"));
    }

    @Test
    public void testTemplateUnclosedExpressionFails() throws Exception {
        try {
            evaluator.evaluateTemplate("ab ${x");
            fail("expected ELException");
        } catch (ELEvaluator.ELException e) {
            assertTrue(e.getMessage().contains("3"));
        }
    }

    // ===== operators =====

    @Test
    public void testTernary() throws Exception {
        assertEval("y", "${true ? 'y' : 'n'}");
        assertEval("n", "${false ? 'y' : 'n'}");
        put("flag", "yes");
        assertEval("y", "${flag ? 'y' : 'n'}");
    }

    @Test
    public void testTernaryWithoutColonFallsThrough() throws Exception {
        assertNull(eval("${a ? b}"));
    }

    @Test
    public void testLogicalOperators() throws Exception {
        assertEval(Boolean.TRUE, "${true or false}");
        assertEval(Boolean.FALSE, "${false or false}");
        assertEval(Boolean.TRUE, "${false || true}");
        assertEval(Boolean.TRUE, "${true and true}");
        assertEval(Boolean.FALSE, "${true and false}");
        assertEval(Boolean.FALSE, "${true && false}");
        assertEval(Boolean.TRUE, "${true && true}");
    }

    @Test
    public void testWordOperatorsRespectWordBoundaries() throws Exception {
        assertNull(eval("${android}"));
        assertNull(eval("${orange}"));
        assertNull(eval("${brand}"));
        assertNull(eval("${floor}"));
        put("android", "robot");
        assertEval("robot", "${android}");
    }

    @Test
    public void testOperatorsInsideParenthesesStringsAndBracketsAreIgnored() throws Exception {
        assertEval(Boolean.TRUE, "${(1 eq 1) and ('a or b' eq 'a or b')}");
        Map<String, String> map = new HashMap<String, String>();
        map.put("a and b", "found");
        put("m", map);
        assertEval("found", "${m['a and b']}");
        assertEval("found", "${m[\"a and b\"]}");
        assertNumber(9, "${(1 + 2) * 3}");
    }

    @Test
    public void testEscapedQuoteDoesNotEndString() throws Exception {
        assertEval(Boolean.TRUE, "${'it\\'s' eq 'it\\'s'}");
    }

    @Test
    public void testComparisonOperators() throws Exception {
        assertEval(Boolean.TRUE, "${1 == 1}");
        assertEval(Boolean.TRUE, "${1 eq 1}");
        assertEval(Boolean.FALSE, "${1 != 1}");
        assertEval(Boolean.TRUE, "${1 ne 2}");
        assertEval(Boolean.TRUE, "${1 < 2}");
        assertEval(Boolean.TRUE, "${1 lt 2}");
        assertEval(Boolean.FALSE, "${2 < 1}");
        assertEval(Boolean.TRUE, "${2 > 1}");
        assertEval(Boolean.TRUE, "${2 gt 1}");
        assertEval(Boolean.FALSE, "${1 > 2}");
        assertEval(Boolean.TRUE, "${2 <= 2}");
        assertEval(Boolean.TRUE, "${2 le 3}");
        assertEval(Boolean.FALSE, "${3 <= 2}");
        assertEval(Boolean.TRUE, "${3 >= 3}");
        assertEval(Boolean.TRUE, "${3 ge 2}");
        assertEval(Boolean.FALSE, "${2 >= 3}");
    }

    @Test
    public void testStringComparisonOperators() throws Exception {
        assertEval(Boolean.TRUE, "${'a' == 'a'}");
        assertEval(Boolean.TRUE, "${'a' eq 'a'}");
        assertEval(Boolean.TRUE, "${'a' != 'b'}");
        assertEval(Boolean.TRUE, "${'a' ne 'b'}");
        assertEval(Boolean.TRUE, "${'a' < 'b'}");
        assertEval(Boolean.TRUE, "${'a' lt 'b'}");
        assertEval(Boolean.FALSE, "${'b' lt 'a'}");
        assertEval(Boolean.TRUE, "${'b' > 'a'}");
        assertEval(Boolean.TRUE, "${'b' gt 'a'}");
        assertEval(Boolean.FALSE, "${'a' gt 'b'}");
        assertEval(Boolean.TRUE, "${'a' <= 'a'}");
        assertEval(Boolean.TRUE, "${'a' le 'b'}");
        assertEval(Boolean.FALSE, "${'b' le 'a'}");
        assertEval(Boolean.TRUE, "${'b' >= 'b'}");
        assertEval(Boolean.TRUE, "${'b' ge 'a'}");
        assertEval(Boolean.FALSE, "${'a' ge 'b'}");
    }

    @Test
    public void testNullComparisons() throws Exception {
        assertEval(Boolean.TRUE, "${null == null}");
        assertEval(Boolean.TRUE, "${null eq null}");
        assertEval(Boolean.TRUE, "${null <= null}");
        assertEval(Boolean.TRUE, "${null >= null}");
        assertEval(Boolean.TRUE, "${null le null}");
        assertEval(Boolean.TRUE, "${null ge null}");
        assertEval(Boolean.FALSE, "${null != null}");
        assertEval(Boolean.FALSE, "${null < null}");
        assertEval(Boolean.TRUE, "${null != 1}");
        assertEval(Boolean.TRUE, "${1 ne null}");
        assertEval(Boolean.FALSE, "${1 == null}");
    }

    @Test
    public void testMixedNumberAndStringComparisonFallsBackToText() throws Exception {
        assertEval(Boolean.TRUE, "${1 eq '1'}");
        assertEval(Boolean.TRUE, "${'10' < 2}");
    }

    @Test
    public void testArithmetic() throws Exception {
        assertNumber(5, "${2 + 3}");
        assertNumber(-1, "${2 - 3}");
        assertNumber(6, "${2 * 3}");
        assertNumber(2.5, "${5 / 2}");
        assertNumber(2.5, "${5 div 2}");
        assertNumber(1, "${7 % 3}");
        assertNumber(1, "${7 mod 3}");
    }

    @Test
    public void testDivisionByZeroIsInfinity() throws Exception {
        Object v = eval("${1 / 0}");
        assertEquals(Double.POSITIVE_INFINITY, ((Double) v).doubleValue(), 0.0);
        Object w = eval("${1 div 0}");
        assertEquals(Double.POSITIVE_INFINITY, ((Double) w).doubleValue(), 0.0);
    }

    @Test
    public void testArithmeticCoercion() throws Exception {
        put("s", "4");
        put("bad", "xyz");
        put("o", new Object());
        assertNumber(5, "${s + 1}");
        assertNumber(1, "${bad + 1}");
        assertNumber(1, "${o + 1}");
        assertNull(eval("${missing + 1}"));
        assertNull(eval("${1 + missing}"));
    }

    @Test
    public void testNegativeAndDecimalLiterals() throws Exception {
        assertNumber(-3, "${-3}");
        assertNumber(1.5, "${1.5}");
    }

    @Test
    public void testNotAndEmpty() throws Exception {
        assertEval(Boolean.FALSE, "${!true}");
        assertEval(Boolean.TRUE, "${not false}");
        assertEval(Boolean.TRUE, "${!missing}");
        assertEval(Boolean.TRUE, "${empty ''}");
        assertEval(Boolean.FALSE, "${empty 'x'}");
        assertEval(Boolean.TRUE, "${empty missing}");
    }

    @Test
    public void testEmptyAcrossTypes() throws Exception {
        put("list", new ArrayList<String>());
        put("full", Arrays.asList("a"));
        put("map", new HashMap<String, String>());
        put("arr", new String[0]);
        put("arr1", new String[] {"a"});
        put("obj", new Object());
        assertEval(Boolean.TRUE, "${empty list}");
        assertEval(Boolean.FALSE, "${empty full}");
        assertEval(Boolean.TRUE, "${empty map}");
        assertEval(Boolean.TRUE, "${empty arr}");
        assertEval(Boolean.FALSE, "${empty arr1}");
        assertEval(Boolean.FALSE, "${empty obj}");
    }

    @Test
    public void testTruthinessOfValues() throws Exception {
        put("zero", Integer.valueOf(0));
        put("one", Integer.valueOf(1));
        put("blank", "");
        put("text", "t");
        put("obj", new Object());
        assertEval("F", "${zero ? 'T' : 'F'}");
        assertEval("T", "${one ? 'T' : 'F'}");
        assertEval("F", "${blank ? 'T' : 'F'}");
        assertEval("T", "${text ? 'T' : 'F'}");
        assertEval("T", "${obj ? 'T' : 'F'}");
    }

    // ===== value navigation =====

    @Test
    public void testScopeResolutionOrder() throws Exception {
        pageContext.setAttribute("v", "app", PageContext.APPLICATION_SCOPE);
        assertEval("app", "${v}");
        pageContext.setAttribute("v", "session", PageContext.SESSION_SCOPE);
        assertEval("session", "${v}");
        pageContext.setAttribute("v", "request", PageContext.REQUEST_SCOPE);
        assertEval("request", "${v}");
        pageContext.setAttribute("v", "page", PageContext.PAGE_SCOPE);
        assertEval("page", "${v}");
        assertNull(eval("${nothing}"));
    }

    @Test
    public void testBeanPropertyAndNullPropagation() throws Exception {
        Bean bean = new Bean();
        bean.name = "bob";
        put("bean", bean);
        assertEval("bob", "${bean.name}");
        assertNull(eval("${bean.nothing}"));
        assertNull(eval("${bean.child.name}"));
        assertNull(eval("${missing.name}"));
        assertNull(eval("${bean.boomProperty}"));
    }

    @Test
    public void testBracketAccessOnMapListArrayAndBean() throws Exception {
        Bean bean = new Bean();
        bean.name = "bob";
        Map<String, String> map = new HashMap<String, String>();
        map.put("k", "v");
        put("bean", bean);
        put("m", map);
        put("list", Arrays.asList("a", "b"));
        put("arr", new String[] {"x", "y"});
        assertEval("v", "${m['k']}");
        assertEval("b", "${list[1]}");
        assertEval("y", "${arr[1]}");
        assertEval("bob", "${bean['name']}");
        assertNull(eval("${list[idx]}"));
        assertNull(eval("${arr[idx]}"));
        assertFails("${list[7]}");
    }

    @Test
    public void testIdentifierMethodCallSyntaxResolvesName() throws Exception {
        put("fn", "f");
        assertEval("f", "${fn(1)}");
    }

    @Test
    public void testDotOnlyExpressionYieldsNull() throws Exception {
        assertNull(eval("${.}"));
    }

    // ===== dotted and chained access on maps (EL: a.b is a['b']) =====

    @Test
    public void testDottedAccessOnMap() throws Exception {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("k", "v");
        Map<String, Object> inner = new HashMap<String, Object>();
        inner.put("z", "deep");
        map.put("inner", inner);
        put("m", map);
        assertEval("v", "${m.k}");
        assertEval("deep", "${m.inner.z}");
        assertNull(eval("${m.absent}"));
        assertEval("deep", "${m['inner']['z']}");
    }

    @Test
    public void testBracketSuffixOnDottedProperty() throws Exception {
        Bean bean = new Bean();
        put("bean", bean);
        assertEval("b", "${bean.items[1]}");
        assertEval("w", "${bean.map['v']}");
        assertEval("y", "${bean.grid[1][1]}");
    }

    // ===== implicit objects =====

    @Test
    public void testScopeMaps() throws Exception {
        pageContext.setAttribute("a", "pg", PageContext.PAGE_SCOPE);
        pageContext.setAttribute("a", "rq", PageContext.REQUEST_SCOPE);
        pageContext.setAttribute("a", "ss", PageContext.SESSION_SCOPE);
        pageContext.setAttribute("a", "ap", PageContext.APPLICATION_SCOPE);
        assertEval("pg", "${pageScope.a}");
        assertEval("rq", "${requestScope.a}");
        assertEval("ss", "${sessionScope.a}");
        assertEval("ap", "${applicationScope['a']}");
        Object pc = eval("${pageContext}");
        assertSame(pageContext, pc);
    }

    @Test
    public void testParameterHeaderCookieAndInitParamMaps() throws Exception {
        request.parameters.put("p", new String[] {"1", "2"});
        request.headers.put("h", new String[] {"H1", "H2"});
        request.cookies = new Cookie[] {new Cookie("c", "cv")};
        pageContext.context.initParams.put("ip", "ipv");
        assertEval("1", "${param.p}");
        Object values = eval("${paramValues.p}");
        assertEquals(Arrays.asList("1", "2"), Arrays.asList((String[]) values));
        assertEval("H1", "${header.h}");
        Object hv = eval("${headerValues.h}");
        assertEquals(Arrays.asList("H1", "H2"), Arrays.asList((String[]) hv));
        Object cookie = eval("${cookie.c}");
        assertEquals("cv", ((Cookie) cookie).getValue());
        assertNull(eval("${cookie.other}"));
        assertEval("ipv", "${initParam.ip}");
    }

    @Test
    public void testCookieMapWithNoCookies() throws Exception {
        request.cookies = null;
        assertNull(eval("${cookie.c}"));
    }

    // ===== method invocation =====

    @Test
    public void testMethodInvocationWithVariousArity() throws Exception {
        put("bean", new Bean());
        put("s", "hello");
        assertEval("zero", "${bean.noarg()}");
        assertNumber(3, "${bean.add(1, 2)}");
        assertEval("zero", "${bean.noarg(,)}");
        assertNumber(5, "${s.length()}");
    }

    @Test
    public void testMissingMethodFailsEvenWhenCached() throws Exception {
        put("bean", new Bean());
        assertFails("${bean.nosuch()}");
        assertFails("${bean.nosuch()}");
    }

    @Test
    public void testBlockedClassesCannotHaveMethodsInvoked() throws Exception {
        Bean bean = new Bean();
        put("bean", bean);
        put("cl", ClassLoader.getSystemClassLoader());
        put("subloader", new Loader());
        put("pb", new ProcessBuilder("true"));
        put("headers", new Headers());
        put("utf8", java.nio.charset.StandardCharsets.UTF_8);
        assertFails("${bean.runtime.availableProcessors()}");
        assertFails("${bean.type.getName()}");
        assertFails("${bean.method.getName()}");
        assertFails("${bean.field.getName()}");
        assertFails("${bean.constructor.getName()}");
        assertFails("${cl.getName()}");
        assertFails("${subloader.loadClass('x')}");
        assertFails("${pb.command()}");
        assertFails("${headers.size()}");
        assertFails("${utf8.name()}");
    }

    // ===== caches, evaluator reuse =====

    @Test
    public void testSameExpressionEvaluatedTwiceTracksState() throws Exception {
        put("v", "one");
        assertEval("one", "${v}");
        put("v", "two");
        assertEval("two", "${v}");
    }

    // ===== helper types =====

    /** Bean exercised through EL. */
    public static class Bean {
        public String name;

        public String getName() {
            return name;
        }

        public Object getChild() {
            return null;
        }

        public Object getBoomProperty() {
            throw new IllegalStateException("boom");
        }

        public List<String> getItems() {
            return Arrays.asList("a", "b");
        }

        public Map<String, String> getMap() {
            Map<String, String> m = new HashMap<String, String>();
            m.put("v", "w");
            return m;
        }

        public String[][] getGrid() {
            return new String[][] {{"p", "q"}, {"x", "y"}};
        }

        public Runtime getRuntime() {
            return Runtime.getRuntime();
        }

        public Class<?> getType() {
            return String.class;
        }

        public Method getMethod() throws Exception {
            return Object.class.getMethod("toString");
        }

        public java.lang.reflect.Field getField() throws Exception {
            return Integer.class.getField("MAX_VALUE");
        }

        public java.lang.reflect.Constructor<?> getConstructor() throws Exception {
            return Object.class.getConstructor();
        }

        public String noarg() {
            return "zero";
        }

        public long add(Object a, Object b) {
            return ((Number) a).longValue() + ((Number) b).longValue();
        }

        public void boom() {
            throw new IllegalStateException("boom");
        }
    }

    /** Class loader subclass whose inherited methods are declared in a blocked class. */
    public static class Loader extends ClassLoader {
        public Loader() {
            super(null);
        }
    }

    /** Mock request backed by maps. */
    private static class MockRequest implements InvocationHandler {
        final Map<String, String[]> parameters = new HashMap<String, String[]>();
        final Map<String, String[]> headers = new HashMap<String, String[]>();
        Cookie[] cookies;

        HttpServletRequest proxy() {
            Object o = Proxy.newProxyInstance(MockRequest.class.getClassLoader(),
                    new Class<?>[] {HttpServletRequest.class}, this);
            return (HttpServletRequest) o;
        }

        public Object invoke(Object p, Method m, Object[] args) {
            String n = m.getName();
            if (n.equals("getParameter")) {
                String[] v = parameters.get((String) args[0]);
                return v == null ? null : v[0];
            }
            if (n.equals("getParameterValues")) {
                return parameters.get((String) args[0]);
            }
            if (n.equals("getHeader")) {
                String[] v = headers.get((String) args[0]);
                return v == null ? null : v[0];
            }
            if (n.equals("getHeaders")) {
                String[] v = headers.get((String) args[0]);
                List<String> l = new ArrayList<String>();
                if (v != null) {
                    l.addAll(Arrays.asList(v));
                }
                Enumeration<String> e = Collections.enumeration(l);
                return e;
            }
            if (n.equals("getCookies")) {
                return cookies;
            }
            if (n.equals("hashCode")) {
                return Integer.valueOf(System.identityHashCode(p));
            }
            if (n.equals("equals")) {
                return Boolean.valueOf(p == args[0]);
            }
            return null;
        }
    }

    /** Mock servlet context returning init parameters. */
    private static class MockContext implements InvocationHandler {
        final Map<String, String> initParams = new HashMap<String, String>();

        ServletContext proxy() {
            Object o = Proxy.newProxyInstance(MockContext.class.getClassLoader(),
                    new Class<?>[] {ServletContext.class}, this);
            return (ServletContext) o;
        }

        public Object invoke(Object p, Method m, Object[] args) {
            if (m.getName().equals("getInitParameter")) {
                return initParams.get((String) args[0]);
            }
            return null;
        }
    }

    /** Mock page context with four scopes, a request and a servlet context. */
    private static class MockPageContext extends PageContext {
        private final Map<Integer, Map<String, Object>> scopes =
                new HashMap<Integer, Map<String, Object>>();
        private final HttpServletRequest req;
        final MockContextHolder context = new MockContextHolder();

        MockPageContext(MockRequest request) {
            this.req = request.proxy();
            scopes.put(PAGE_SCOPE, new HashMap<String, Object>());
            scopes.put(REQUEST_SCOPE, new HashMap<String, Object>());
            scopes.put(SESSION_SCOPE, new HashMap<String, Object>());
            scopes.put(APPLICATION_SCOPE, new HashMap<String, Object>());
        }

        @Override
        public void setAttribute(String name, Object attribute) {
            scopes.get(PAGE_SCOPE).put(name, attribute);
        }

        @Override
        public void setAttribute(String name, Object o, int scope) {
            scopes.get(scope).put(name, o);
        }

        @Override
        public Object getAttribute(String name) {
            return scopes.get(PAGE_SCOPE).get(name);
        }

        @Override
        public Object getAttribute(String name, int scope) {
            Map<String, Object> m = scopes.get(scope);
            if (m == null) {
                return null;
            }
            return m.get(name);
        }

        @Override
        public Object findAttribute(String name) {
            return getAttribute(name);
        }

        @Override
        public int getAttributesScope(String name) {
            return 0;
        }

        @Override
        public void removeAttribute(String name) {
            scopes.get(PAGE_SCOPE).remove(name);
        }

        @Override
        public void removeAttribute(String name, int scope) {
            scopes.get(scope).remove(name);
        }

        @Override
        public Enumeration<String> getAttributeNamesInScope(int scope) {
            return Collections.enumeration(scopes.get(scope).keySet());
        }

        @Override
        public void initialize(jakarta.servlet.Servlet servlet,
                jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                String errorPageURL, boolean needsSession, int bufferSize, boolean autoFlush) {
        }

        @Override
        public void release() {
        }

        @Override
        public jakarta.servlet.http.HttpSession getSession() {
            return null;
        }

        @Override
        public Object getPage() {
            return null;
        }

        @Override
        public jakarta.servlet.ServletRequest getRequest() {
            return req;
        }

        @Override
        public jakarta.servlet.ServletResponse getResponse() {
            return null;
        }

        @Override
        public Exception getException() {
            return null;
        }

        @Override
        public jakarta.servlet.ServletConfig getServletConfig() {
            return null;
        }

        @Override
        public ServletContext getServletContext() {
            return context.servletContext;
        }

        @Override
        public JspWriter getOut() {
            return null;
        }

        @Override
        public void handlePageException(Exception e) {
        }

        @Override
        public void handlePageException(Throwable t) {
        }

        @Override
        public void forward(String relativeUrlPath) {
        }

        @Override
        public void include(String relativeUrlPath) {
        }

        @Override
        public void include(String relativeUrlPath, boolean flush) {
        }
    }

    /** Pairs the mock context handler with its proxy. */
    private static class MockContextHolder {
        final Map<String, String> initParams;
        final ServletContext servletContext;

        MockContextHolder() {
            MockContext handler = new MockContext();
            this.initParams = handler.initParams;
            this.servletContext = handler.proxy();
        }
    }
}
