/*
 * ELEvaluatorTest.java
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

import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.PageContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for ELEvaluator.
 * 
 * Tests the Expression Language evaluation capabilities including:
 * - Literal values (strings, numbers, booleans, null)
 * - Arithmetic operators (+, -, *, /, %)
 * - Comparison operators (==, !=, <, >, <=, >=)
 * - Logical operators (&&, ||, !)
 * - Empty operator
 * - Ternary operator
 * - Variable resolution
 * - Property access
 * - Map/Array/List access
 * - Template evaluation
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ELEvaluatorTest {

    private MockPageContext pageContext;
    private ELEvaluator evaluator;

    @Before
    public void setUp() {
        pageContext = new MockPageContext();
        evaluator = new ELEvaluator(pageContext);
    }

    // ===== Literal Tests =====

    @Test
    public void testStringLiteral() throws Exception {
        assertEquals("hello", evaluator.evaluate("${'hello'}"));
        assertEquals("world", evaluator.evaluate("${\"world\"}"));
    }

    @Test
    public void testNumericLiteral() throws Exception {
        Object result = evaluator.evaluate("${42}");
        assertTrue("Should be a number", result instanceof Number);
        assertEquals(42, ((Number) result).intValue());
    }

    @Test
    public void testDecimalLiteral() throws Exception {
        Object result = evaluator.evaluate("${3.14}");
        assertTrue("Should be a number", result instanceof Number);
        assertEquals(3.14, ((Number) result).doubleValue(), 0.001);
    }

    @Test
    public void testBooleanLiteral() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${true}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${false}"));
    }

    @Test
    public void testNullLiteral() throws Exception {
        assertNull(evaluator.evaluate("${null}"));
    }

    // ===== Arithmetic Operator Tests =====

    @Test
    public void testAddition() throws Exception {
        Object result = evaluator.evaluate("${2 + 3}");
        assertTrue("Should be a number", result instanceof Number);
        assertEquals(5, ((Number) result).intValue());
    }

    @Test
    public void testSubtraction() throws Exception {
        Object result = evaluator.evaluate("${10 - 3}");
        assertEquals(7, ((Number) result).intValue());
    }

    @Test
    public void testMultiplication() throws Exception {
        Object result = evaluator.evaluate("${3 * 4}");
        assertEquals(12, ((Number) result).intValue());
    }

    @Test
    public void testDivision() throws Exception {
        Object result = evaluator.evaluate("${10 / 4}");
        assertEquals(2.5, ((Number) result).doubleValue(), 0.001);
    }

    @Test
    public void testDivKeyword() throws Exception {
        Object result = evaluator.evaluate("${10 div 4}");
        assertEquals(2.5, ((Number) result).doubleValue(), 0.001);
    }

    @Test
    public void testModulo() throws Exception {
        Object result = evaluator.evaluate("${7 % 3}");
        assertEquals(1, ((Number) result).intValue());
    }

    @Test
    public void testModKeyword() throws Exception {
        Object result = evaluator.evaluate("${7 mod 3}");
        assertEquals(1, ((Number) result).intValue());
    }

    // ===== Comparison Operator Tests =====

    @Test
    public void testEquals() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${5 == 5}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${5 == 6}"));
    }

    @Test
    public void testEqualsKeyword() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${5 eq 5}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${5 eq 6}"));
    }

    @Test
    public void testNotEquals() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${5 != 6}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${5 != 5}"));
    }

    @Test
    public void testNotEqualsKeyword() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${5 ne 6}"));
    }

    @Test
    public void testLessThan() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${3 < 5}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${5 < 3}"));
    }

    @Test
    public void testLessThanKeyword() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${3 lt 5}"));
    }

    @Test
    public void testGreaterThan() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${5 > 3}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${3 > 5}"));
    }

    @Test
    public void testGreaterThanKeyword() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${5 gt 3}"));
    }

    @Test
    public void testLessThanOrEqual() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${3 <= 5}"));
        assertEquals(Boolean.TRUE, evaluator.evaluate("${5 <= 5}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${6 <= 5}"));
    }

    @Test
    public void testGreaterThanOrEqual() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${5 >= 3}"));
        assertEquals(Boolean.TRUE, evaluator.evaluate("${5 >= 5}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${3 >= 5}"));
    }

    // ===== Logical Operator Tests =====

    @Test
    public void testLogicalAnd() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${true && true}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${true && false}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${false && true}"));
    }

    @Test
    public void testLogicalAndKeyword() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${true and true}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${true and false}"));
    }

    @Test
    public void testLogicalOr() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${true || false}"));
        assertEquals(Boolean.TRUE, evaluator.evaluate("${false || true}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${false || false}"));
    }

    @Test
    public void testLogicalOrKeyword() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${true or false}"));
    }

    @Test
    public void testLogicalNot() throws Exception {
        assertEquals(Boolean.FALSE, evaluator.evaluate("${!true}"));
        assertEquals(Boolean.TRUE, evaluator.evaluate("${!false}"));
    }

    @Test
    public void testLogicalNotKeyword() throws Exception {
        assertEquals(Boolean.FALSE, evaluator.evaluate("${not true}"));
        assertEquals(Boolean.TRUE, evaluator.evaluate("${not false}"));
    }

    // ===== Empty Operator Tests =====

    @Test
    public void testEmptyNull() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${empty null}"));
    }

    @Test
    public void testEmptyString() throws Exception {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${empty ''}"));
        assertEquals(Boolean.FALSE, evaluator.evaluate("${empty 'hello'}"));
    }

    // ===== Ternary Operator Tests =====

    @Test
    public void testTernaryTrue() throws Exception {
        assertEquals("yes", evaluator.evaluate("${true ? 'yes' : 'no'}"));
    }

    @Test
    public void testTernaryFalse() throws Exception {
        assertEquals("no", evaluator.evaluate("${false ? 'yes' : 'no'}"));
    }

    @Test
    public void testTernaryWithComparison() throws Exception {
        assertEquals("big", evaluator.evaluate("${10 > 5 ? 'big' : 'small'}"));
    }

    // ===== Parentheses Tests =====

    @Test
    public void testParentheses() throws Exception {
        Object result = evaluator.evaluate("${(2 + 5) * 2}");
        assertEquals(14, ((Number) result).intValue());
    }

    // ===== Variable Resolution Tests =====

    @Test
    public void testPageScopeVariable() throws Exception {
        pageContext.setAttribute("testVar", "testValue", PageContext.PAGE_SCOPE);
        assertEquals("testValue", evaluator.evaluate("${testVar}"));
    }

    @Test
    public void testRequestScopeVariable() throws Exception {
        pageContext.setAttribute("reqVar", "requestValue", PageContext.REQUEST_SCOPE);
        assertEquals("requestValue", evaluator.evaluate("${reqVar}"));
    }

    // ===== Property Access Tests =====

    @Test
    public void testBeanPropertyAccess() throws Exception {
        TestBean bean = new TestBean();
        bean.setName("John");
        bean.setAge(30);
        pageContext.setAttribute("user", bean, PageContext.PAGE_SCOPE);
        
        assertEquals("John", evaluator.evaluate("${user.name}"));
        Object age = evaluator.evaluate("${user.age}");
        assertEquals(30, ((Number) age).intValue());
    }

    @Test
    public void testMapAccess() throws Exception {
        Map<String, String> map = new HashMap<String, String>();
        map.put("key1", "value1");
        map.put("key2", "value2");
        pageContext.setAttribute("myMap", map, PageContext.PAGE_SCOPE);
        
        assertEquals("value1", evaluator.evaluate("${myMap['key1']}"));
        assertEquals("value2", evaluator.evaluate("${myMap['key2']}"));
    }

    @Test
    public void testListAccess() throws Exception {
        List<String> list = new ArrayList<String>();
        list.add("first");
        list.add("second");
        list.add("third");
        pageContext.setAttribute("myList", list, PageContext.PAGE_SCOPE);
        
        assertEquals("first", evaluator.evaluate("${myList[0]}"));
        assertEquals("second", evaluator.evaluate("${myList[1]}"));
    }

    @Test
    public void testArrayAccess() throws Exception {
        String[] array = {"alpha", "beta", "gamma"};
        pageContext.setAttribute("myArray", array, PageContext.PAGE_SCOPE);
        
        assertEquals("alpha", evaluator.evaluate("${myArray[0]}"));
        assertEquals("gamma", evaluator.evaluate("${myArray[2]}"));
    }

    // ===== Template Evaluation Tests =====

    @Test
    public void testTemplateWithSingleExpression() throws Exception {
        pageContext.setAttribute("name", "World", PageContext.PAGE_SCOPE);
        assertEquals("Hello, World!", evaluator.evaluateTemplate("Hello, ${name}!"));
    }

    @Test
    public void testTemplateWithMultipleExpressions() throws Exception {
        pageContext.setAttribute("first", "John", PageContext.PAGE_SCOPE);
        pageContext.setAttribute("last", "Doe", PageContext.PAGE_SCOPE);
        assertEquals("Name: John Doe", evaluator.evaluateTemplate("Name: ${first} ${last}"));
    }

    @Test
    public void testTemplateWithNoExpressions() throws Exception {
        assertEquals("Plain text", evaluator.evaluateTemplate("Plain text"));
    }

    // ===== Error Handling Tests =====

    @Test(expected = ELEvaluator.ELException.class)
    public void testUnclosedExpression() throws Exception {
        evaluator.evaluateTemplate("Hello ${name");
    }

    // ===== Test Bean Class =====

    public static class TestBean {
        private String name;
        private int age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    // ===== Mock PageContext Implementation =====

    /**
     * Mock PageContext that provides the minimal functionality needed for EL evaluation.
     * Extends the abstract PageContext class with concrete implementations.
     */
    private static class MockPageContext extends PageContext {
        
        private final Map<String, Object> pageScope = new HashMap<String, Object>();
        private final Map<String, Object> requestScope = new HashMap<String, Object>();
        private final Map<String, Object> sessionScope = new HashMap<String, Object>();
        private final Map<String, Object> applicationScope = new HashMap<String, Object>();
        
        @Override
        public void setAttribute(String name, Object attribute) {
            pageScope.put(name, attribute);
        }
        
        @Override
        public void setAttribute(String name, Object o, int scope) {
            switch (scope) {
                case PAGE_SCOPE:
                    pageScope.put(name, o);
                    break;
                case REQUEST_SCOPE:
                    requestScope.put(name, o);
                    break;
                case SESSION_SCOPE:
                    sessionScope.put(name, o);
                    break;
                case APPLICATION_SCOPE:
                    applicationScope.put(name, o);
                    break;
            }
        }

        @Override
        public Object getAttribute(String name) {
            return pageScope.get(name);
        }

        @Override
        public Object getAttribute(String name, int scope) {
            switch (scope) {
                case PAGE_SCOPE:
                    return pageScope.get(name);
                case REQUEST_SCOPE:
                    return requestScope.get(name);
                case SESSION_SCOPE:
                    return sessionScope.get(name);
                case APPLICATION_SCOPE:
                    return applicationScope.get(name);
            }
            return null;
        }
        
        @Override
        public Object findAttribute(String name) {
            Object value = pageScope.get(name);
            if (value != null) return value;
            value = requestScope.get(name);
            if (value != null) return value;
            value = sessionScope.get(name);
            if (value != null) return value;
            return applicationScope.get(name);
        }
        
        @Override
        public int getAttributesScope(String name) {
            if (pageScope.containsKey(name)) return PAGE_SCOPE;
            if (requestScope.containsKey(name)) return REQUEST_SCOPE;
            if (sessionScope.containsKey(name)) return SESSION_SCOPE;
            if (applicationScope.containsKey(name)) return APPLICATION_SCOPE;
            return 0;
        }

        @Override
        public void removeAttribute(String name) {
            pageScope.remove(name);
        }

        @Override
        public void removeAttribute(String name, int scope) {
            switch (scope) {
                case PAGE_SCOPE:
                    pageScope.remove(name);
                    break;
                case REQUEST_SCOPE:
                    requestScope.remove(name);
                    break;
                case SESSION_SCOPE:
                    sessionScope.remove(name);
                    break;
                case APPLICATION_SCOPE:
                    applicationScope.remove(name);
                    break;
            }
        }

        @Override
        public java.util.Enumeration<String> getAttributeNamesInScope(int scope) {
            java.util.Set<String> keys;
            switch (scope) {
                case PAGE_SCOPE:
                    keys = pageScope.keySet();
                    break;
                case REQUEST_SCOPE:
                    keys = requestScope.keySet();
                    break;
                case SESSION_SCOPE:
                    keys = sessionScope.keySet();
                    break;
                case APPLICATION_SCOPE:
                    keys = applicationScope.keySet();
                    break;
                default:
                    keys = java.util.Collections.emptySet();
            }
            return java.util.Collections.enumeration(keys);
        }

        // Stub implementations for unused abstract methods
        @Override public void initialize(javax.servlet.Servlet servlet, 
                javax.servlet.ServletRequest request, javax.servlet.ServletResponse response, 
                String errorPageURL, boolean needsSession, int bufferSize, boolean autoFlush) {}
        @Override public void release() {}
        @Override public javax.servlet.http.HttpSession getSession() { return null; }
        @Override public Object getPage() { return null; }
        @Override public javax.servlet.ServletRequest getRequest() { return null; }
        @Override public javax.servlet.ServletResponse getResponse() { return null; }
        @Override public Exception getException() { return null; }
        @Override public javax.servlet.ServletConfig getServletConfig() { return null; }
        @Override public javax.servlet.ServletContext getServletContext() { return null; }
        @Override public JspWriter getOut() { return null; }
        @Override public void handlePageException(Exception e) {}
        @Override public void handlePageException(Throwable t) {}
        @Override public void forward(String relativeUrlPath) {}
        @Override public void include(String relativeUrlPath) {}
        @Override public void include(String relativeUrlPath, boolean flush) {}
    }
}
