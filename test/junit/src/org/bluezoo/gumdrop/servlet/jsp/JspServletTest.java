/*
 * JspServletTest.java
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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.Test;

import org.bluezoo.gumdrop.servlet.Container;
import org.bluezoo.gumdrop.servlet.Context;
import org.bluezoo.gumdrop.servlet.MemoryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Drives {@link JspServlet} against a real {@link Context} serving JSP files
 * from a temporary web application, with proxy request and response objects.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspServletTest {

    public MemoryFolder tmp = new MemoryFolder();

    /** Request stub answering path queries. */
    private static class Req implements InvocationHandler {
        String servletPath;
        String pathInfo;
        String method = "GET";

        public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            if (n.equals("getMethod")) {
                return method;
            }
            if (n.equals("getServletPath")) {
                return servletPath;
            }
            if (n.equals("getPathInfo")) {
                return pathInfo;
            }
            Class<?> rt = m.getReturnType();
            if (rt == boolean.class) {
                return Boolean.FALSE;
            }
            if (rt == int.class) {
                return Integer.valueOf(0);
            }
            return null;
        }
    }

    /** Response stub recording status and body. */
    private static final class Resp implements InvocationHandler {
        int error;
        final StringWriter body = new StringWriter();
        final PrintWriter writer = new PrintWriter(body);

        public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            if (n.equals("sendError")) {
                error = ((Integer) args[0]).intValue();
                return null;
            }
            if (n.equals("getWriter")) {
                return writer;
            }
            Class<?> rt = m.getReturnType();
            if (rt == boolean.class) {
                return Boolean.FALSE;
            }
            if (rt == int.class) {
                return Integer.valueOf(0);
            }
            return null;
        }
    }

    /** Config stub exposing a servlet context. */
    private static final class Cfg implements InvocationHandler {
        final ServletContext context;

        Cfg(ServletContext context) {
            this.context = context;
        }

        public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            if (n.equals("getServletContext")) {
                return context;
            }
            if (n.equals("getServletName")) {
                return "jsp";
            }
            return null;
        }
    }

    private HttpServletRequest request(Req h) {
        Object o = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { HttpServletRequest.class }, h);
        return (HttpServletRequest) o;
    }

    private HttpServletResponse response(Resp h) {
        Object o = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { HttpServletResponse.class }, h);
        return (HttpServletResponse) o;
    }

    private ServletConfig config(ServletContext ctx) {
        Object o = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { ServletConfig.class }, new Cfg(ctx));
        return (ServletConfig) o;
    }

    private void write(Path root, String path, String content) throws IOException {
        MemoryFolder.write(root, path, content);
    }

    private Context newContext() throws Exception {
        Path root = tmp.newFolder("webapp");
        write(root, "WEB-INF/web.xml",
                "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"/>");
        write(root, "hello.jsp", "Hello <%= 1 + 1 %> world");
        write(root, "broken.jsp", "<% this is not java %>");
        Container container = new Container();
        Context context = MemoryFolder.context(container, "/app", root);
        container.addContext(context);
        context.load();
        return context;
    }

    @Test
    public void compilesCachesAndServesJsp() throws Exception {
        Context context = newContext();
        JspServlet servlet = new JspServlet();
        servlet.init(config(context));
        Req rq = new Req();
        rq.servletPath = "/hello.jsp";
        Resp rs = new Resp();
        servlet.service(request(rq), response(rs));
        assertEquals(0, rs.error);
        assertEquals(1, servlet.getCacheSize());
        assertTrue(rs.body.toString().contains("Hello 2 world"));
        Resp rs2 = new Resp();
        servlet.service(request(rq), response(rs2));
        assertEquals(1, servlet.getCacheSize());
        assertTrue(rs2.body.toString().contains("Hello 2 world"));
        servlet.clearCache();
        assertEquals(0, servlet.getCacheSize());
    }

    @Test
    public void servesViaPathInfo() throws Exception {
        Context context = newContext();
        JspServlet servlet = new JspServlet();
        servlet.init(config(context));
        Req rq = new Req();
        rq.servletPath = "/hello";
        rq.pathInfo = ".jsp";
        Resp rs = new Resp();
        servlet.service(request(rq), response(rs));
        assertEquals(0, rs.error);
        assertEquals(1, servlet.getCacheSize());
    }

    @Test
    public void nonJspPathIsNotFound() throws Exception {
        Context context = newContext();
        JspServlet servlet = new JspServlet();
        servlet.init(config(context));
        Req rq = new Req();
        rq.servletPath = "/hello.txt";
        Resp rs = new Resp();
        servlet.service(request(rq), response(rs));
        assertEquals(HttpServletResponse.SC_NOT_FOUND, rs.error);
    }

    @Test
    public void missingAndBrokenJspYieldInternalError() throws Exception {
        Context context = newContext();
        JspServlet servlet = new JspServlet();
        servlet.init(config(context));
        Req rq = new Req();
        rq.servletPath = "/missing.jsp";
        Resp rs = new Resp();
        servlet.service(request(rq), response(rs));
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, rs.error);
        rq.servletPath = "/broken.jsp";
        Resp rs2 = new Resp();
        servlet.service(request(rq), response(rs2));
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, rs2.error);
        assertEquals(0, servlet.getCacheSize());
    }

    @Test
    public void foreignServletContextYieldsInternalError() throws Exception {
        JspStubSupport.MapHandler h = new JspStubSupport.MapHandler();
        ServletContext foreign = JspStubSupport.stub(ServletContext.class, h);
        JspServlet servlet = new JspServlet();
        servlet.init(config(foreign));
        Req rq = new Req();
        rq.servletPath = "/hello.jsp";
        Resp rs = new Resp();
        servlet.service(request(rq), response(rs));
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, rs.error);
    }

    @Test
    public void contextFoundViaAttribute() throws Exception {
        Context context = newContext();
        JspStubSupport.MapHandler h = new JspStubSupport.MapHandler();
        h.attributes.put("org.bluezoo.gumdrop.servlet.Context", context);
        ServletContext foreign = JspStubSupport.stub(ServletContext.class, h);
        JspServlet servlet = new JspServlet();
        servlet.init(config(foreign));
        Req rq = new Req();
        rq.servletPath = "/hello.jsp";
        Resp rs = new Resp();
        servlet.service(request(rq), response(rs));
        assertEquals(0, rs.error);
        assertEquals(1, servlet.getCacheSize());
    }

    @Test
    public void otherMethodsDelegateAndInfo() throws Exception {
        Context context = newContext();
        JspServlet servlet = new JspServlet();
        servlet.init(config(context));
        assertTrue(servlet.getServletInfo().contains("JSP"));
        Req rq = new Req();
        rq.servletPath = "/hello.jsp";
        List<Integer> codes = new ArrayList<Integer>();
        String[] methods = { "POST", "PUT", "DELETE" };
        for (int i = 0; i < methods.length; i++) {
            Req r = new Req();
            r.servletPath = "/nothing.txt";
            Resp rs = new Resp();
            r.method = methods[i];
            servlet.service(request(r), response(rs));
            codes.add(Integer.valueOf(rs.error));
        }
        for (int i = 0; i < codes.size(); i++) {
            assertEquals(HttpServletResponse.SC_NOT_FOUND, codes.get(i).intValue());
        }
    }
}
