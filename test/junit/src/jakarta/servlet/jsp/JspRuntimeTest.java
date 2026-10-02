/*
 * JspRuntimeTest.java
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

package jakarta.servlet.jsp;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for the JSP runtime classes (JspFactory, DefaultPageContext,
 * DefaultJspWriter, JspException) using reflective stubs for the servlet API.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspRuntimeTest {

    /** Generic attribute-store stub handler. */
    private static class Store implements InvocationHandler {
        final Map<String, Object> attrs = new HashMap<String, Object>();
        final List<String> calls = new ArrayList<String>();
        Object extra;
        boolean dispatcherNull;
        boolean dispatcherThrows;

        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String n = m.getName();
            calls.add(n);
            if (n.equals("setAttribute")) {
                attrs.put((String) args[0], args[1]);
                return null;
            }
            if (n.equals("getAttribute")) {
                return attrs.get((String) args[0]);
            }
            if (n.equals("removeAttribute")) {
                attrs.remove((String) args[0]);
                return null;
            }
            if (n.equals("getAttributeNames")) {
                return Collections.enumeration(new ArrayList<String>(attrs.keySet()));
            }
            if (n.equals("hashCode")) {
                return Integer.valueOf(1);
            }
            if (n.equals("equals")) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if (n.equals("toString")) {
                return "stub";
            }
            if (n.equals("getRequestDispatcher")) {
                if (dispatcherNull) {
                    return null;
                }
                return extra;
            }
            if (n.equals("forward") || n.equals("include")) {
                if (dispatcherThrows) {
                    throw new ServletException("boom");
                }
                return null;
            }
            if (n.equals("getSession")) {
                return extra;
            }
            return null;
        }
    }

    private Store reqStore;
    private Store sessStore;
    private Store ctxStore;
    private Store dispStore;
    private StringWriter sink;
    private ServletRequest request;
    private ServletResponse response;
    private Servlet servlet;
    private ServletContext context;
    private JspFactory factory;

    private Object proxy(Class<?> iface, InvocationHandler h) {
        return Proxy.newProxyInstance(JspRuntimeTest.class.getClassLoader(),
                new Class<?>[] { iface }, h);
    }

    @Before
    public void setUp() {
        reqStore = new Store();
        sessStore = new Store();
        ctxStore = new Store();
        dispStore = new Store();
        sink = new StringWriter();
        final PrintWriter pw = new PrintWriter(sink);
        Object disp = proxy(RequestDispatcher.class, dispStore);
        ctxStore.extra = disp;
        context = (ServletContext) proxy(ServletContext.class, ctxStore);
        final ServletContext ctx = context;
        final ServletConfig cfg = (ServletConfig) proxy(ServletConfig.class,
                new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("getServletContext")) {
                    return ctx;
                }
                return null;
            }
        });
        servlet = (Servlet) proxy(Servlet.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("getServletConfig")) {
                    return cfg;
                }
                return null;
            }
        });
        Object sess = proxy(HttpSession.class, sessStore);
        reqStore.extra = sess;
        request = (ServletRequest) proxy(jakarta.servlet.http.HttpServletRequest.class, reqStore);
        response = (ServletResponse) proxy(ServletResponse.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("getWriter")) {
                    return pw;
                }
                return null;
            }
        });
        factory = JspFactory.getDefaultFactory();
    }

    @After
    public void tearDown() {
        JspFactory.setDefaultFactory(null);
    }

    private PageContext pc(String errorPage) {
        return factory.getPageContext(servlet, request, response, errorPage, true, 8192, true);
    }

    @Test
    public void testFactoryDefaultAndSet() {
        JspFactory a = JspFactory.getDefaultFactory();
        JspFactory b = JspFactory.getDefaultFactory();
        assertSame(a, b);
        JspFactory custom = new JspFactory() {
            public PageContext getPageContext(Servlet s, ServletRequest rq, ServletResponse rs,
                    String e, boolean n, int b2, boolean af) {
                return null;
            }
            public void releasePageContext(PageContext p) {
            }
        };
        JspFactory.setDefaultFactory(custom);
        assertSame(custom, JspFactory.getDefaultFactory());
    }

    @Test
    public void testReleaseNullAndReal() {
        factory.releasePageContext(null);
        PageContext p = pc(null);
        p.setAttribute("a", "b");
        factory.releasePageContext(p);
        assertNull(p.getRequest());
        assertNull(p.getPage());
        assertNull(p.getOut());
        assertNull(p.getAttribute("a"));
    }

    @Test
    public void testAccessors() throws IOException {
        PageContext p = pc(null);
        assertSame(servlet, p.getPage());
        assertSame(request, p.getRequest());
        assertSame(response, p.getResponse());
        assertSame(context, p.getServletContext());
        assertNotNull(p.getServletConfig());
        assertNotNull(p.getOut());
        assertNotNull(p.getSession());
        p.initialize(servlet, request, response, null, true, 1, true);
        reqStore.attrs.put("jakarta.servlet.error.exception", new IllegalStateException("x"));
        assertTrue(p.getException() instanceof IllegalStateException);
    }

    @Test
    public void testSessionNullForNonHttp() {
        ServletRequest plain = (ServletRequest) proxy(ServletRequest.class, reqStore);
        PageContext p = factory.getPageContext(servlet, plain, response, null, false, 0, false);
        assertNull(p.getSession());
        assertNull(p.getAttribute("x", PageContext.SESSION_SCOPE));
        p.setAttribute("x", "y", PageContext.SESSION_SCOPE);
        Enumeration<String> names = p.getAttributeNamesInScope(PageContext.SESSION_SCOPE);
        assertFalse(names.hasMoreElements());
    }

    @Test
    public void testScopes() {
        PageContext p = pc(null);
        p.setAttribute("pg", "1");
        p.setAttribute("rq", "2", PageContext.REQUEST_SCOPE);
        p.setAttribute("ss", "3", PageContext.SESSION_SCOPE);
        p.setAttribute("ap", "4", PageContext.APPLICATION_SCOPE);
        assertEquals("1", p.getAttribute("pg"));
        assertEquals("2", p.getAttribute("rq", PageContext.REQUEST_SCOPE));
        assertEquals("3", p.getAttribute("ss", PageContext.SESSION_SCOPE));
        assertEquals("4", p.getAttribute("ap", PageContext.APPLICATION_SCOPE));
        assertEquals("1", p.findAttribute("pg"));
        assertEquals("2", p.findAttribute("rq"));
        assertEquals("3", p.findAttribute("ss"));
        assertEquals("4", p.findAttribute("ap"));
        assertNull(p.findAttribute("none"));
        assertEquals(PageContext.PAGE_SCOPE, p.getAttributesScope("pg"));
        assertEquals(PageContext.REQUEST_SCOPE, p.getAttributesScope("rq"));
        assertEquals(PageContext.SESSION_SCOPE, p.getAttributesScope("ss"));
        assertEquals(PageContext.APPLICATION_SCOPE, p.getAttributesScope("ap"));
        assertEquals(0, p.getAttributesScope("none"));
        assertTrue(p.getAttributeNamesInScope(PageContext.PAGE_SCOPE).hasMoreElements());
        assertTrue(p.getAttributeNamesInScope(PageContext.REQUEST_SCOPE).hasMoreElements());
        assertTrue(p.getAttributeNamesInScope(PageContext.SESSION_SCOPE).hasMoreElements());
        assertTrue(p.getAttributeNamesInScope(PageContext.APPLICATION_SCOPE).hasMoreElements());
        p.removeAttribute("pg");
        p.removeAttribute("rq", PageContext.REQUEST_SCOPE);
        p.removeAttribute("ss", PageContext.SESSION_SCOPE);
        p.removeAttribute("ap", PageContext.APPLICATION_SCOPE);
        assertNull(p.getAttribute("pg"));
        assertNull(p.getAttribute("rq", PageContext.REQUEST_SCOPE));
        assertNull(p.getAttribute("ss", PageContext.SESSION_SCOPE));
        assertNull(p.getAttribute("ap", PageContext.APPLICATION_SCOPE));
    }

    @Test
    public void testInvalidScope() {
        PageContext p = pc(null);
        try {
            p.setAttribute("a", "b", 99);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().indexOf("99") >= 0);
        }
        try {
            p.getAttribute("a", 99);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
        try {
            p.getAttributeNamesInScope(99);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testForwardAndInclude() throws IOException {
        PageContext p = pc(null);
        p.forward("/x");
        p.include("/y");
        p.include("/z", true);
        assertTrue(dispStore.calls.contains("forward"));
        assertTrue(dispStore.calls.contains("include"));
    }

    @Test
    public void testForwardNoDispatcher() {
        ctxStore.dispatcherNull = true;
        PageContext p = pc(null);
        try {
            p.forward("/x");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().indexOf("/x") >= 0);
        }
        try {
            p.include("/y");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().indexOf("/y") >= 0);
        }
    }

    @Test
    public void testForwardServletException() {
        dispStore.dispatcherThrows = true;
        PageContext p = pc(null);
        try {
            p.forward("/x");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getCause() instanceof ServletException);
        }
        try {
            p.include("/y");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getCause() instanceof ServletException);
        }
    }

    @Test
    public void testHandlePageExceptionWithErrorPage() throws IOException {
        PageContext p = pc("/err");
        p.handlePageException(new Exception("bad"));
        assertTrue(dispStore.calls.contains("forward"));
    }

    @Test
    public void testHandlePageExceptionErrorPageFails() {
        dispStore.dispatcherThrows = true;
        PageContext p = pc("/err");
        try {
            p.handlePageException(new Exception("bad"));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().indexOf("/err") >= 0);
        }
    }

    @Test
    public void testHandlePageExceptionNoErrorPage() throws IOException {
        PageContext p = pc(null);
        try {
            p.handlePageException(new IllegalStateException("rt"));
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("rt", expected.getMessage());
        }
        try {
            p.handlePageException(new IOException("io"));
            fail();
        } catch (IOException expected) {
            assertEquals("io", expected.getMessage());
        }
        try {
            p.handlePageException(new Exception("chk"));
            fail();
        } catch (IOException expected) {
            assertEquals("JSP exception", expected.getMessage());
        }
        PageContext q = pc("");
        try {
            q.handlePageException(new Exception("chk"));
            fail();
        } catch (IOException expected) {
            assertNotNull(expected.getCause());
        }
    }

    @Test
    public void testWriter() throws IOException {
        PageContext p = pc(null);
        JspWriter w = p.getOut();
        w.print(true);
        w.print('c');
        w.print(1);
        w.print(2L);
        w.print(1.5f);
        w.print(2.5d);
        char[] chars = new char[] { 'a', 'b' };
        w.print(chars);
        w.print("s");
        String nul = null;
        w.print(nul);
        w.print(Integer.valueOf(7));
        w.newLine();
        w.println();
        w.println(true);
        w.println('c');
        w.println(1);
        w.println(2L);
        w.println(1.5f);
        w.println(2.5d);
        w.println(chars);
        w.println("s");
        w.println(Integer.valueOf(7));
        w.write(65);
        w.write(chars);
        w.write("str");
        w.write("string", 1, 2);
        w.write(chars, 0, 1);
        w.clear();
        w.clearBuffer();
        assertEquals(8192, w.getRemaining());
        w.flush();
        String out = sink.toString();
        assertTrue(out.startsWith("truec12"));
        assertTrue(out.indexOf("null") > 0);
        assertTrue(out.indexOf("strtr") > 0);
        w.close();
    }

    @Test
    public void testWriterRemainingUnbuffered() {
        PageContext p = factory.getPageContext(servlet, request, response, null, true, 0, false);
        assertEquals(Integer.MAX_VALUE, p.getOut().getRemaining());
    }

    @Test
    public void testResponseWriterFailure() {
        ServletResponse bad = (ServletResponse) proxy(ServletResponse.class,
                new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) throws IOException {
                throw new IOException("no writer");
            }
        });
        try {
            factory.getPageContext(servlet, request, bad, null, true, 1, true);
            fail();
        } catch (RuntimeException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        }
    }

    @Test
    public void testJspException() {
        Throwable cause = new RuntimeException("c");
        assertNull(new JspException().getMessage());
        assertEquals("m", new JspException("m").getMessage());
        JspException e = new JspException("m", cause);
        assertSame(cause, e.getCause());
        assertSame(cause, new JspException(cause).getCause());
    }
}
