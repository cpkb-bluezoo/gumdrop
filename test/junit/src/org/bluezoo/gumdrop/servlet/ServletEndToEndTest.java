/*
 * ServletEndToEndTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;

import org.bluezoo.gumdrop.NullSecurityInfo;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives complete requests through {@link ServletHandler}, {@link
 * RequestHandler}, {@link ContextRequestDispatcher}, filters, servlets and
 * {@link Response} on the calling thread, against a stub transport.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletEndToEndTest {

    // ===== Fixture servlets and filters =====

    static final CountDownLatch ASYNC_SEEN = new CountDownLatch(1);

    /** Echoes request properties as plain text. */
    public static class EchoServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            if (req.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
                ASYNC_SEEN.countDown();
            }
            resp.setContentType("text/plain; charset=UTF-8");
            PrintWriter w = resp.getWriter();
            w.println("method=" + req.getMethod());
            w.println("servletPath=" + req.getServletPath());
            w.println("pathInfo=" + req.getPathInfo());
            w.println("query=" + req.getQueryString());
            w.println("a=" + req.getParameter("a"));
            w.println("uri=" + req.getRequestURI());
            w.println("url=" + req.getRequestURL());
            w.println("ctx=" + req.getContextPath());
            w.println("scheme=" + req.getScheme());
            w.println("server=" + req.getServerName() + ":" + req.getServerPort());
            w.println("xtest=" + req.getHeader("X-Test"));
            w.println("filter=" + req.getAttribute("filtered"));
            w.println("locale=" + req.getLocale());
            w.println("remote=" + req.getRemoteAddr() + ":" + req.getRemotePort());
            w.println("local=" + req.getLocalAddr() + ":" + req.getLocalPort());
            w.println("secure=" + req.isSecure());
            w.println("dispatch=" + req.getDispatcherType());
            w.println("ct=" + req.getContentType() + "/" + req.getCharacterEncoding());
            w.println("mapping=" + req.getHttpServletMapping().getMappingMatch());
            Enumeration<String> names = req.getParameterNames();
            int count = 0;
            while (names.hasMoreElements()) {
                names.nextElement();
                count++;
            }
            w.println("paramCount=" + count);
            String[] values = req.getParameterValues("a");
            w.println("aValues=" + (values == null ? 0 : values.length));
            w.println("map=" + req.getParameterMap().size());
            Cookie[] cookies = req.getCookies();
            w.println("cookies=" + (cookies == null ? 0 : cookies.length));
            if (cookies != null) {
                for (int i = 0; i < cookies.length; i++) {
                    w.println("cookie." + cookies[i].getName() + "=" + cookies[i].getValue());
                }
            }
            w.println("user=" + req.getRemoteUser());
        }
    }

    /** Reads the request body and writes it back reversed in length. */
    public static class BodyServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            byte[] buf = new byte[1024];
            int total = 0;
            java.io.InputStream in = req.getInputStream();
            for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                total += n;
            }
            resp.setContentType("text/plain");
            resp.getWriter().print("read=" + total);
        }

        @Override
        protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            java.io.BufferedReader r = req.getReader();
            StringBuilder sb = new StringBuilder();
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                sb.append(line);
            }
            resp.getWriter().print("lines=" + sb);
        }
    }

    /** Session handling. */
    public static class SessionServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            if (req.getParameter("noCreate") != null) {
                HttpSession none = req.getSession(false);
                resp.getWriter().print("none=" + (none == null));
                return;
            }
            HttpSession session = req.getSession();
            Integer count = (Integer) session.getAttribute("count");
            int next = count == null ? 1 : count.intValue() + 1;
            session.setAttribute("count", Integer.valueOf(next));
            if (req.getParameter("invalidate") != null) {
                session.invalidate();
            }
            if (req.getParameter("change") != null) {
                req.changeSessionId();
            }
            resp.getWriter().print("count=" + next + ",fromCookie=" + req.isRequestedSessionIdFromCookie()
                    + ",fromUrl=" + req.isRequestedSessionIdFromURL()
                    + ",valid=" + req.isRequestedSessionIdValid());
        }
    }

    /** Produces assorted error and redirect responses. */
    public static class ErrServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            String mode = req.getParameter("mode");
            if ("404".equals(mode)) {
                resp.sendError(404, "gone");
            } else if ("500".equals(mode)) {
                resp.sendError(500);
            } else if ("servlet".equals(mode)) {
                throw new ServletException("boom");
            } else if ("runtime".equals(mode)) {
                throw new IllegalStateException("bad");
            } else if ("io".equals(mode)) {
                throw new IOException("ioerr");
            } else if ("unavailable".equals(mode)) {
                throw new UnavailableException("later", 5);
            } else if ("redirect".equals(mode)) {
                resp.sendRedirect("/app/echo/redirected");
            } else if ("redirectRel".equals(mode)) {
                resp.sendRedirect("other");
            } else if ("204".equals(mode)) {
                resp.setStatus(204);
            } else if ("headers".equals(mode)) {
                resp.setHeader("X-One", "1");
                resp.addHeader("X-One", "2");
                resp.setIntHeader("X-Int", 5);
                resp.addIntHeader("X-Int2", 6);
                resp.setDateHeader("X-Date", 0L);
                resp.addDateHeader("X-Date2", 1000L);
                resp.addCookie(new Cookie("c1", "v1"));
                Cookie c2 = new Cookie("c2", "v2");
                c2.setPath("/app");
                c2.setMaxAge(60);
                c2.setHttpOnly(true);
                c2.setSecure(true);
                c2.setDomain("example.org");
                resp.addCookie(c2);
                resp.setLocale(java.util.Locale.FRANCE);
                resp.setContentType("text/html");
                resp.setCharacterEncoding("UTF-8");
                resp.getWriter().print("ok:" + resp.containsHeader("X-One") + resp.getHeader("X-One")
                        + resp.getHeaders("X-One").size() + resp.getHeaderNames().size());
            } else if ("encode".equals(mode)) {
                resp.getWriter().print(resp.encodeURL("/x") + "|" + resp.encodeRedirectURL("/y"));
            } else if ("reset".equals(mode)) {
                resp.setHeader("X-Before-Reset", "1");
                resp.setStatus(404);
                resp.getWriter().print("discarded");
                resp.reset();
                resp.setStatus(202);
                resp.getWriter().print("kept");
            } else if ("resetcommitted".equals(mode)) {
                resp.getWriter().print("x");
                resp.flushBuffer();
                try {
                    resp.reset();
                } catch (IllegalStateException e) {
                    resp.getWriter().print("rejected");
                }
            } else if ("committed".equals(mode)) {
                resp.getWriter().print("x");
                resp.flushBuffer();
                boolean c = resp.isCommitted();
                try {
                    resp.sendError(500);
                } catch (IllegalStateException e) {
                    resp.getWriter().print("committed=" + c);
                }
            } else if ("both".equals(mode)) {
                resp.getWriter();
                try {
                    resp.getOutputStream();
                } catch (IllegalStateException e) {
                    resp.getWriter().print("state");
                }
            } else {
                resp.getWriter().print("default");
            }
        }
    }

    /** Writes binary output. */
    public static class BinServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/octet-stream");
            resp.setBufferSize(64);
            ServletOutputStream out = resp.getOutputStream();
            byte[] chunk = new byte[200];
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = (byte) i;
            }
            out.write(chunk);
            out.write(7);
            out.print("tail");
            out.println("!");
            out.flush();
            resp.setHeader("X-After", "late");
        }

        @Override
        protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentLengthLong(3L);
            resp.getOutputStream().write(new byte[] {1, 2, 3});
        }

        @Override
        protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentLength(10);
        }
    }

    /** Forwards and includes. */
    public static class DispatchServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            String target = req.getParameter("to");
            String how = req.getParameter("how");
            RequestDispatcher d;
            if ("named".equals(req.getParameter("kind"))) {
                d = getServletContext().getNamedDispatcher(target);
            } else if ("rel".equals(req.getParameter("kind"))) {
                d = req.getRequestDispatcher(target);
            } else {
                d = getServletContext().getRequestDispatcher(target);
            }
            if (d == null) {
                resp.getWriter().print("nodispatcher");
            } else if ("include-raw".equals(how)) {
                d.include(req, resp);
            } else if ("include".equals(how)) {
                resp.getWriter().print("before|");
                d.include(req, resp);
                resp.getWriter().print("|after");
            } else {
                d.forward(req, resp);
            }
        }
    }

    /** Uses asynchronous processing. */
    public static class AsyncServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            AsyncContext ac = req.startAsync();
            ac.setTimeout(5000L);
            String mode = req.getParameter("mode");
            if ("dispatch".equals(mode)) {
                ac.dispatch("/echo/async");
                return;
            }
            ac.getResponse().getWriter().print("async=" + req.isAsyncStarted());
            ac.complete();
        }
    }

    /** Receives a multipart upload. */
    public static class UploadServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            StringBuilder sb = new StringBuilder();
            for (Part p : req.getParts()) {
                sb.append(p.getName()).append(':').append(p.getSize());
                if (p.getSubmittedFileName() != null) {
                    sb.append(':').append(p.getSubmittedFileName());
                }
                sb.append(';');
            }
            Part one = req.getPart("field");
            sb.append("one=").append(one == null ? "null" : one.getContentType());
            resp.getWriter().print(sb.toString());
        }
    }

    /** Prints error request attributes. */
    public static class ErrorPageServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("errorpage:" + req.getAttribute("jakarta.servlet.error.status_code")
                    + ":" + req.getAttribute("jakarta.servlet.error.message")
                    + ":" + req.getAttribute("jakarta.servlet.error.request_uri"));
        }
    }

    /** Adds an attribute and a header. */
    public static class MarkFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            request.setAttribute("filtered", "yes");
            ((HttpServletResponse) response).setHeader("X-Filter", "ran");
            chain.doFilter(request, response);
        }
    }

    /** Short-circuits the chain. */
    public static class BlockFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            response.getWriter().print("blocked");
        }
    }

    // ===== Harness =====

    /** Container that captures the handler instead of using worker threads. */
    static class TestContainer extends Container {
        ServletHandler pending;

        @Override
        public void serviceRequest(ServletHandler servletHandler) {
            pending = servletHandler;
        }

        @Override
        public void executeWorker(Runnable task, Runnable onRejected) {
            task.run();
        }

        void runPending() {
            new RequestHandler(pending, this).run();
        }
    }

    /** Captured response. */
    static final class Result {
        int status;
        Headers headers;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean complete;
        final CountDownLatch done = new CountDownLatch(1);
        boolean cancelled;
        int headerCalls;

        String text() {
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        }

        String header(String name) {
            return headers == null ? null : headers.getValue(name);
        }
    }

    static class StubState implements HttpResponse {
        final Result result = new Result();
        boolean secure;

        @Override public SocketAddress getRemoteAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public SocketAddress getLocalAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 8080);
        }
        @Override public boolean isSecure() { return secure; }
        @Override public SecurityInfo getSecurityInfo() { return NullSecurityInfo.INSTANCE; }
        @Override public HttpVersion getVersion() { return HttpVersion.HTTP_1_1; }
        @Override public String getScheme() { return secure ? "https" : "http"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public void headers(Headers headers) {
            result.headerCalls++;
            if (result.headers == null) {
                result.headers = headers;
                String s = headers.getValue(":status");
                result.status = s == null ? 0 : Integer.parseInt(s);
            }
        }
        @Override public void startResponseBody() { }
        @Override public void responseBodyContent(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            result.body.write(b, 0, b.length);
        }
        @Override public void endResponseBody() { }
        @Override public void complete() {
            result.complete = true;
            result.done.countDown();
        }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public boolean pushPromise(Headers headers) { return true; }
        @Override public void upgradeToWebSocket(String protocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { result.cancelled = true; }
    }

    /** Realm that authenticates alice/pw as an admin. */
    static final class TestRealm implements Realm {
        @Override public Realm forSelectorLoop(SelectorLoop loop) { return this; }
        @Override public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return Collections.<SaslMechanism>emptySet();
        }
        @Override public boolean passwordMatch(String username, String password) {
            return "alice".equals(username) && "pw".equals(password);
        }
        @Override public String getDigestHA1(String username, String realmName) { return null; }
        @Override public String getPassword(String username) { return "pw"; }
        @Override public boolean isUserInRole(String username, String role) {
            return "alice".equals(username) && "admin".equals(role);
        }
    }

    public static MemoryFolder tmp = new MemoryFolder();

    private static TestContainer container;
    private static Context context;
    private static Path root;
    private static String savedFactory;

    @BeforeClass
    public static void setUpClass() throws Exception {
        savedFactory = System.getProperty("java.naming.factory.initial");
        System.setProperty("java.naming.factory.initial",
                "org.bluezoo.gumdrop.servlet.jndi.ServletInitialContextFactory");
        root = tmp.newFolder("e2e");
        write("WEB-INF/web.xml", webXml());
        write("index.html", "<html>welcome</html>");
        write("hello.txt", "hello text");
        write("sub/index.html", "sub welcome");
        write("sub/readme.txt", "readme");
        write("noindex/file.bin", "bin");
        Container shared = SharedContainer.get();
        shared.addRealm("testrealm", new TestRealm());
        container = new TestContainer();
        container.addRealm("testrealm", new TestRealm());
        context = new Context(shared, "/app", root);
        shared.addContext(context);
        container.addContext(context);
        context.load();
        ServletDef upload = context.servletDefs.get("upload");
        upload.multipartConfig.maxFileSize = 1000L;
        upload.multipartConfig.maxRequestSize = 5000L;
        upload.multipartConfig.fileSizeThreshold = 10;
        context.init();
    }

    @AfterClass
    public static void tearDownClass() {
        context.destroy();
        if (savedFactory == null) {
            System.clearProperty("java.naming.factory.initial");
        } else {
            System.setProperty("java.naming.factory.initial", savedFactory);
        }
    }

    private static String webXml() {
        String p = ServletEndToEndTest.class.getName();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">");
        sb.append("<display-name>e2e</display-name>");
        String[][] servlets = {
            { "echo", "EchoServlet", "/echo/*" },
            { "body", "BodyServlet", "/body" },
            { "session", "SessionServlet", "/session" },
            { "err", "ErrServlet", "/err" },
            { "bin", "BinServlet", "/bin" },
            { "dispatch", "DispatchServlet", "/dispatch" },
            { "async", "AsyncServlet", "/async" },
            { "errpage", "ErrorPageServlet", "/errpage" },
            { "secure", "EchoServlet", "/secure/*" },
        };
        for (int i = 0; i < servlets.length; i++) {
            sb.append("<servlet><servlet-name>").append(servlets[i][0]).append("</servlet-name>");
            sb.append("<servlet-class>").append(p).append('$').append(servlets[i][1]).append("</servlet-class>");
            sb.append("<async-supported>true</async-supported>");
            sb.append("</servlet>");
        }
        sb.append("<servlet><servlet-name>upload</servlet-name><servlet-class>").append(p)
                .append("$UploadServlet</servlet-class><multipart-config><max-file-size>1000</max-file-size>"
                        + "<max-request-size>5000</max-request-size><file-size-threshold>10</file-size-threshold>"
                        + "</multipart-config></servlet>");
        for (int i = 0; i < servlets.length; i++) {
            sb.append("<servlet-mapping><servlet-name>").append(servlets[i][0]).append("</servlet-name>");
            sb.append("<url-pattern>").append(servlets[i][2]).append("</url-pattern></servlet-mapping>");
        }
        sb.append("<servlet-mapping><servlet-name>upload</servlet-name><url-pattern>/upload</url-pattern>"
                + "</servlet-mapping>");
        sb.append("<servlet-mapping><servlet-name>echo</servlet-name><url-pattern>*.ext</url-pattern></servlet-mapping>");
        sb.append("<filter><filter-name>mark</filter-name><filter-class>").append(p)
                .append("$MarkFilter</filter-class></filter>");
        sb.append("<filter><filter-name>block</filter-name><filter-class>").append(p)
                .append("$BlockFilter</filter-class></filter>");
        sb.append("<filter-mapping><filter-name>mark</filter-name><url-pattern>/echo/*</url-pattern>"
                + "<dispatcher>REQUEST</dispatcher><dispatcher>FORWARD</dispatcher>"
                + "<dispatcher>INCLUDE</dispatcher><dispatcher>ASYNC</dispatcher></filter-mapping>");
        sb.append("<filter-mapping><filter-name>block</filter-name><url-pattern>/blocked</url-pattern>"
                + "</filter-mapping>");
        sb.append("<filter-mapping><filter-name>mark</filter-name><servlet-name>bin</servlet-name></filter-mapping>");
        sb.append("<servlet><servlet-name>blockedservlet</servlet-name><servlet-class>").append(p)
                .append("$EchoServlet</servlet-class></servlet>");
        sb.append("<servlet-mapping><servlet-name>blockedservlet</servlet-name><url-pattern>/blocked</url-pattern>"
                + "</servlet-mapping>");
        sb.append("<error-page><error-code>404</error-code><location>/errpage</location></error-page>");
        sb.append("<error-page><exception-type>jakarta.servlet.ServletException</exception-type>"
                + "<location>/errpage</location></error-page>");
        sb.append("<mime-mapping><extension>txt</extension><mime-type>text/plain</mime-type></mime-mapping>");
        sb.append("<welcome-file-list><welcome-file>index.html</welcome-file></welcome-file-list>");
        sb.append("<security-constraint><web-resource-collection><web-resource-name>s</web-resource-name>"
                + "<url-pattern>/secure/*</url-pattern></web-resource-collection>"
                + "<auth-constraint><role-name>admin</role-name></auth-constraint></security-constraint>");
        sb.append("<login-config><auth-method>BASIC</auth-method><realm-name>testrealm</realm-name></login-config>");
        sb.append("<security-role><role-name>admin</role-name></security-role>");
        sb.append("</web-app>");
        return sb.toString();
    }

    private static void write(String path, String content) throws IOException {
        MemoryFolder.write(root, path, content);
    }

    private Result send(String method, String target, String... headerPairs) throws Exception {
        return sendBody(method, target, null, headerPairs);
    }

    private Result sendBody(String method, String target, byte[] body, String... headerPairs)
            throws Exception {
        StubState state = new StubState();
        return sendWith(state, method, target, body, headerPairs);
    }

    private Result sendWith(StubState state, String method, String target, byte[] body,
            String... headerPairs) throws Exception {
        ServletHandler handler = new ServletHandler(container, state, 8192);
        Headers h = new Headers();
        h.add(":method", method);
        h.add(":path", target);
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            h.add(headerPairs[i], headerPairs[i + 1]);
        }
        MessageEvents.headers(handler, h);
        if (body != null) {
            handler.bodyContent(ByteBuffer.wrap(body));
        }
        handler.endMessage();
        container.runPending();
        return state.result;
    }

    // ===== Tests =====

    @Test
    public void testEchoGet() throws Exception {
        Result r = send("GET", "/app/echo/some/path?a=1&a=2&b=x%20y", "x-test", "hello",
                "accept-language", "fr;q=0.5, en-GB;q=0.9");
        assertEquals(200, r.status);
        String t = r.text();
        assertTrue(t, t.contains("method=GET"));
        assertTrue(t, t.contains("servletPath=/echo"));
        assertTrue(t, t.contains("pathInfo=/some/path"));
        assertTrue(t, t.contains("a=1"));
        assertTrue(t, t.contains("aValues=2"));
        assertTrue(t, t.contains("paramCount=2"));
        assertTrue(t, t.contains("xtest=hello"));
        assertTrue(t, t.contains("filter=yes"));
        assertTrue(t, t.contains("ctx=/app"));
        assertTrue(t, t.contains("mapping=PATH"));
        assertTrue(t, t.contains("locale=en_GB"));
        assertEquals("ran", r.header("x-filter"));
        assertTrue(r.complete);
    }

    @Test
    public void testEchoCookiesAndHostHeader() throws Exception {
        Result r = send("GET", "/app/echo", "cookie", "a=1; b=2", "host", "example.org:9090");
        String t = r.text();
        assertTrue(t, t.contains("cookie.a=1"));
        assertTrue(t, t.contains("cookie.b=2"));
        assertTrue(t, t.contains("server=example.org:9090"));
        Result r2 = send("GET", "/app/echo", "host", "example.org");
        assertTrue(r2.text(), r2.text().contains("server=example.org:8080"));
        Result r3 = send("GET", "/app/echo");
        assertTrue(r3.text(), r3.text().contains(":8080"));
    }

    @Test
    public void testExtensionMappingAndSecureScheme() throws Exception {
        StubState state = new StubState();
        state.secure = true;
        Result r = sendWith(state, "GET", "/app/thing.ext", null);
        String t = r.text();
        assertTrue(t, t.contains("scheme=https"));
        assertTrue(t, t.contains("secure=true"));
        assertTrue(t, t.contains("mapping=EXTENSION"));
    }

    @Test
    public void testFormPostParameters() throws Exception {
        byte[] body = "a=posted&c=d".getBytes(StandardCharsets.US_ASCII);
        Result r = sendBody("POST", "/app/echo?q=1", body,
                "content-type", "application/x-www-form-urlencoded; charset=UTF-8",
                "content-length", "12");
        String t = r.text();
        assertTrue(t, t.contains("a=posted"));
        assertTrue(t, t.contains("paramCount=3"));
        assertTrue(t, t.contains("ct=application/x-www-form-urlencoded; charset=UTF-8/UTF-8"));
    }

    @Test
    public void testBodyServlet() throws Exception {
        Result r = sendBody("POST", "/app/body", "0123456789".getBytes(StandardCharsets.US_ASCII),
                "content-length", "10");
        assertEquals("read=10", r.text());
        Result r2 = sendBody("PUT", "/app/body", "line1\nline2".getBytes(StandardCharsets.US_ASCII));
        assertEquals("lines=line1line2", r2.text());
    }

    @Test
    public void testSessionLifecycle() throws Exception {
        Result r1 = send("GET", "/app/session");
        assertEquals(200, r1.status);
        assertTrue(r1.text(), r1.text().startsWith("count=1"));
        String setCookie = r1.header("set-cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie, setCookie.startsWith("JSESSIONID="));
        String id = setCookie.substring("JSESSIONID=".length(), setCookie.indexOf(';') > 0
                ? setCookie.indexOf(';') : setCookie.length());
        Result r2 = send("GET", "/app/session", "cookie", "JSESSIONID=" + id);
        assertTrue(r2.text(), r2.text().contains("count=2"));
        assertTrue(r2.text(), r2.text().contains("fromCookie=true"));
        Result r3 = send("GET", "/app/session?change=1", "cookie", "JSESSIONID=" + id);
        assertTrue(r3.text(), r3.text().contains("count=3"));
        Result r4 = send("GET", "/app/session?noCreate=1");
        assertEquals("none=true", r4.text());
        Result r5 = send("GET", "/app/session?jsessionid=" + id + "&x=1");
        assertEquals(200, r5.status);
        Result r6 = send("GET", "/app/session?jsessionid=bogus");
        assertTrue(r6.text(), r6.text().contains("fromUrl=true"));
    }

    @Test
    public void testSessionInvalidate() throws Exception {
        Result r1 = send("GET", "/app/session?invalidate=1");
        assertTrue(r1.text(), r1.text().contains("count=1"));
    }

    @Test
    public void testErrorModes() throws Exception {
        Result r404 = send("GET", "/app/err?mode=404");
        assertEquals(404, r404.status);
        assertTrue(r404.text(), r404.text().contains("errorpage:404"));
        Result r500 = send("GET", "/app/err?mode=500");
        assertEquals(500, r500.status);
        Result rs = send("GET", "/app/err?mode=servlet");
        assertTrue(rs.text(), rs.text().contains("errorpage"));
        Result rr = send("GET", "/app/err?mode=runtime");
        assertEquals(500, rr.status);
        Result ri = send("GET", "/app/err?mode=io");
        assertEquals(500, ri.status);
        Result ru = send("GET", "/app/err?mode=unavailable");
        assertEquals(503, ru.status);
        Result miss = send("GET", "/app/nothing-here");
        assertEquals(404, miss.status);
        Result nocontext = send("GET", "/other/path");
        assertEquals(404, nocontext.status);
    }

    @Test
    public void testRedirectsAndStatuses() throws Exception {
        Result r1 = send("GET", "/app/err?mode=redirect");
        assertEquals(302, r1.status);
        assertNotNull(r1.header("location"));
        Result r2 = send("GET", "/app/err?mode=redirectRel");
        assertEquals(302, r2.status);
        assertTrue(r2.header("location"), r2.header("location").endsWith("other"));
        Result r3 = send("GET", "/app/err?mode=204");
        assertEquals(204, r3.status);
    }

    @Test
    public void testResponseHeaders() throws Exception {
        Result r = send("GET", "/app/err?mode=headers");
        assertEquals(200, r.status);
        assertTrue(r.text(), r.text().startsWith("ok:true"));
        assertEquals("5", r.header("x-int"));
        assertEquals("6", r.header("x-int2"));
        assertNotNull(r.header("x-date"));
        assertTrue(r.header("content-type"), r.header("content-type").startsWith("text/html"));
        int cookies = 0;
        for (Header header : r.headers) {
            if ("set-cookie".equalsIgnoreCase(header.getName())) {
                cookies++;
            }
        }
        assertEquals(2, cookies);
    }

    @Test
    public void testResponseEncodeAndReset() throws Exception {
        Result r = send("GET", "/app/err?mode=encode");
        assertTrue(r.text(), r.text().startsWith("/x|"));
        assertTrue(r.text(), r.text().endsWith("/y"));
        Result r2 = send("GET", "/app/err?mode=reset");
        assertEquals("kept", r2.text());
        assertEquals(202, r2.status);
        assertNull(r2.header("x-before-reset"));
        Result r2b = send("GET", "/app/err?mode=resetcommitted");
        assertEquals("xrejected", r2b.text());
        Result r3 = send("GET", "/app/err?mode=committed");
        assertEquals("xcommitted=true", r3.text());
        Result r4 = send("GET", "/app/err?mode=both");
        assertTrue(r4.text(), r4.text().contains("state"));
        Result r5 = send("GET", "/app/err");
        assertEquals("default", r5.text());
    }

    @Test
    public void testBinaryOutput() throws Exception {
        Result r = send("GET", "/app/bin");
        assertEquals(200, r.status);
        assertTrue(r.body.size() > 200);
        assertEquals("ran", r.header("x-filter"));
        Result put = send("PUT", "/app/bin");
        assertEquals("3", put.header("content-length"));
        assertEquals(3, put.body.size());
        Result head = send("HEAD", "/app/bin");
        assertEquals(200, head.status);
        assertEquals(0, head.body.size());
    }

    @Test
    public void testForwardAndInclude() throws Exception {
        Result fwd = send("GET", "/app/dispatch?to=/echo/fwd?a=9");
        assertEquals(200, fwd.status);
        assertTrue(fwd.text(), fwd.text().contains("pathInfo=/fwd"));
        assertTrue(fwd.text(), fwd.text().contains("dispatch=FORWARD"));
        Result inc = send("GET", "/app/dispatch?to=/echo/inc&how=include");
        assertTrue(inc.text(), inc.text().startsWith("before|"));
        assertTrue(inc.text(), inc.text().endsWith("|after"));
        assertTrue(inc.text(), inc.text().contains("dispatch=INCLUDE"));
        Result named = send("GET", "/app/dispatch?kind=named&to=echo");
        assertTrue(named.text(), named.text().contains("method=GET"));
        Result rel = send("GET", "/app/dispatch?kind=rel&to=echo/rel");
        assertEquals(200, rel.status);
        Result none = send("GET", "/app/dispatch?kind=named&to=unknown");
        assertEquals("nodispatcher", none.text());
        Result missing = send("GET", "/app/dispatch?to=/no/such/resource");
        assertEquals(404, missing.status);
        Result incStatic = send("GET", "/app/dispatch?to=/hello.txt&how=include-raw");
        assertTrue(incStatic.text(), incStatic.text().contains("hello text"));
    }

    @Test
    public void testFilterShortCircuit() throws Exception {
        Result r = send("GET", "/app/blocked");
        assertEquals("blocked", r.text());
    }

    @Test
    public void testAsync() throws Exception {
        Result r = send("GET", "/app/async");
        assertTrue(r.done.await(10, TimeUnit.SECONDS));
        assertEquals(200, r.status);
        assertEquals("async=true", r.text());
        Result d = send("GET", "/app/async?mode=dispatch");
        assertTrue(ASYNC_SEEN.await(10, TimeUnit.SECONDS));
        assertNotNull(d);
    }

    @Test
    public void testMultipartRejectsNonMultipart() throws Exception {
        Result r = sendBody("POST", "/app/upload", "x=1".getBytes(StandardCharsets.US_ASCII),
                "content-type", "text/plain", "content-length", "3");
        assertEquals(500, r.status);
    }

    @Test
    public void testBasicAuthentication() throws Exception {
        Result denied = send("GET", "/app/secure/x");
        assertEquals(401, denied.status);
        assertNotNull(denied.header("www-authenticate"));
        String bad = "Basic " + Base64.getEncoder().encodeToString("alice:wrong".getBytes(StandardCharsets.UTF_8));
        Result wrong = send("GET", "/app/secure/x", "authorization", bad);
        assertEquals(401, wrong.status);
        String good = "Basic " + Base64.getEncoder().encodeToString("alice:pw".getBytes(StandardCharsets.UTF_8));
        Result ok = send("GET", "/app/secure/x", "authorization", good);
        assertEquals(200, ok.status);
        assertTrue(ok.text(), ok.text().contains("user=alice"));
    }

    // ===== Default servlet =====

    @Test
    public void testDefaultServletStaticFile() throws Exception {
        Result r = send("GET", "/app/hello.txt");
        assertEquals(200, r.status);
        assertEquals("hello text", r.text());
        assertEquals("text/plain", r.header("content-type"));
        assertNotNull(r.header("last-modified"));
        Result head = send("HEAD", "/app/hello.txt");
        assertEquals(200, head.status);
        assertEquals(0, head.body.size());
        assertEquals("10", head.header("content-length"));
    }

    @Test
    public void testDefaultServletWelcomeFile() throws Exception {
        Result root1 = send("GET", "/app/");
        assertEquals(200, root1.status);
        assertTrue(root1.text(), root1.text().contains("welcome"));
        Result sub = send("GET", "/app/sub/");
        assertEquals(200, sub.status);
        assertEquals("sub welcome", sub.text());
        Result noSlash = send("GET", "/app/sub");
        assertTrue(noSlash.status == 200 || noSlash.status == 302 || noSlash.status == 301);
        Result none = send("GET", "/app/noindex/");
        assertTrue(none.status == 404 || none.status == 403 || none.status == 200);
    }

    @Test
    public void testDefaultServletConditionalAndRange() throws Exception {
        Result first = send("GET", "/app/hello.txt");
        String lastModified = first.header("last-modified");
        assertNotNull(lastModified);
        Result notModified = send("GET", "/app/hello.txt", "if-modified-since", lastModified);
        assertEquals(304, notModified.status);
        Result range = send("GET", "/app/hello.txt", "range", "bytes=0-4");
        assertTrue(range.status == 206 || range.status == 200);
        Result etag = first.header("etag") == null ? null
                : send("GET", "/app/hello.txt", "if-none-match", first.header("etag"));
        if (etag != null) {
            assertEquals(304, etag.status);
        }
    }

    @Test
    public void testDefaultServletRejectsProtectedAndTraversal() throws Exception {
        Result webinf = send("GET", "/app/WEB-INF/web.xml");
        assertEquals(404, webinf.status);
        Result trav = send("GET", "/app/../etc/passwd");
        assertTrue(trav.status == 404 || trav.status == 400);
        Result post = send("POST", "/app/hello.txt");
        assertTrue(post.status == 405 || post.status == 200 || post.status == 404);
        Result opts = send("OPTIONS", "/app/hello.txt");
        assertNotNull(opts);
        assertFalse(opts.cancelled);
        assertNull(opts.header("x-nonexistent"));
    }
}
