/*
 * RequestApiTest.java
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.Part;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.MappingMatch;
import jakarta.servlet.http.PushBuilder;

import org.bluezoo.gumdrop.NullSecurityInfo;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises the {@link Request} API directly: headers, cookies, parameters,
 * locales, sessions, authentication, asynchronous start and push.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RequestApiTest {

    /** Container that does not run requests. */
    private static final class IdleContainer extends Container {
        @Override
        public void serviceRequest(ServletHandler servletHandler) {
        }
    }

    private static final class StubState implements HttpResponse {
        boolean secure;
        boolean push = true;
        final List<String> pushed = new ArrayList<String>();
        final List<String[]> sentFields = new ArrayList<String[]>();
        boolean sent;
        boolean completed;
        int status;
        String pending;

        @Override public SocketAddress getRemoteAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 40000);
        }
        @Override public SocketAddress getLocalAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 8443);
        }
        @Override public boolean isSecure() { return secure; }
        @Override public SecurityInfo getSecurityInfo() { return NullSecurityInfo.INSTANCE; }
        @Override public HttpVersion getVersion() { return HttpVersion.HTTP_2_0; }
        @Override public String getScheme() { return secure ? "https" : "http"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public void status(int code) {
            if (code >= 200 && !sent) {
                sent = true;
                status = code;
            }
        }
        @Override public void header(String name, String value) {
            sentFields.add(new String[] { name, value });
        }
        @Override public void endHeaders() { }
        @Override public void bodyContent(ByteBuffer data) { }
        @Override public void endMessage() {
            if (!sent) {
                sent = true;
                status = 200;
            }
            completed = true;
        }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public void startPushPromise(org.bluezoo.gumdrop.http.HttpMethod method, String target) {
            pending = target;
        }
        @Override public boolean endPushPromise() {
            pushed.add(pending);
            return push;
        }
        @Override public void upgradeToWebSocket(String protocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { }
    }

    private static final class TestRealm implements Realm {
        @Override public Realm forSelectorLoop(SelectorLoop loop) { return this; }
        @Override public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return Collections.<SaslMechanism>emptySet();
        }
        @Override public boolean passwordMatch(String username, String password) {
            return "bob".equals(username) && "secret".equals(password);
        }
        @Override public String getDigestHA1(String username, String realmName) { return null; }
        @Override public String getPassword(String username) { return "secret"; }
        @Override public boolean isUserInRole(String username, String role) {
            return "staff".equals(role);
        }
    }

    public static MemoryFolder tmp = new MemoryFolder();

    private static IdleContainer container;
    private static Context plain;
    private static Context basic;
    private static Context form;
    private static Context cert;

    private static Context newContext(String path, String loginConfig) throws Exception {
        Path dir = tmp.newFolder("ctx" + path.replace("/", "_"));
        String xml = "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">"
                + "<request-character-encoding>UTF-16</request-character-encoding>"
                + loginConfig + "</web-app>";
        MemoryFolder.write(dir, "WEB-INF/web.xml", xml);
        Context c = new Context(container, path, dir);
        c.load();
        c.addRealm("r", new TestRealm());
        return c;
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
        container = new IdleContainer();
        plain = newContext("/plain", "");
        basic = newContext("/basic",
                "<login-config><auth-method>BASIC</auth-method><realm-name>r</realm-name></login-config>");
        form = newContext("/form",
                "<login-config><auth-method>FORM</auth-method><realm-name>r</realm-name>"
                + "<form-login-config><form-login-page>/login</form-login-page>"
                + "<form-error-page>/fail</form-error-page></form-login-config></login-config>");
        cert = newContext("/cert",
                "<login-config><auth-method>CLIENT-CERT</auth-method><realm-name>r</realm-name></login-config>");
    }

    @AfterClass
    public static void tearDownClass() {
        container.destroy();
    }

    private static Request request(Context ctx, StubState state, String method, String target,
            String... headerPairs) throws Exception {
        return requestWithBody(ctx, state, method, target, null, headerPairs);
    }

    private static Request requestWithBody(Context ctx, StubState state, String method, String target,
            byte[] body, String... headerPairs) throws Exception {
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
        Request r = handler.getRequest();
        r.context = ctx;
        r.contextPath = ctx.contextPath;
        ServletMatch match = new ServletMatch();
        ServletDef def = new ServletDef();
        def.name = "svc";
        def.asyncSupported = true;
        match.servletDef = def;
        match.servletPath = "/svc";
        match.pathInfo = "/info/x";
        match.mappingMatch = MappingMatch.PATH;
        match.matchValue = "info/x";
        r.match = match;
        return r;
    }

    private static Request request(String method, String target, String... headerPairs) throws Exception {
        return request(plain, new StubState(), method, target, headerPairs);
    }

    // ===== Headers =====

    @Test
    public void testHeaderAccessors() throws Exception {
        Request r = request("GET", "/plain/a?x=1", "X-One", "1", "x-one", "2", "x-long", "9999999999",
                "if-modified-since", "Sun, 06 Nov 1994 08:49:37 GMT", "bad-int", "abc");
        assertEquals("1", r.getHeader("x-one"));
        assertEquals("GET", r.getMethod());
        assertEquals(-1, r.getIntHeader("absent"));
        assertEquals(-1L, r.getLongHeader("absent"));
        assertEquals(9999999999L, r.getLongHeader("x-long"));
        assertEquals(784111777000L, r.getDateHeader("if-modified-since"));
        assertEquals(-1L, r.getDateHeader("absent"));
        try {
            r.getIntHeader("bad-int");
            fail("expected NumberFormatException");
        } catch (NumberFormatException e) {
            assertNotNull(e.getMessage());
        }
        Enumeration<String> names = r.getHeaderNames();
        int count = 0;
        while (names.hasMoreElements()) {
            names.nextElement();
            count++;
        }
        assertTrue(count >= 4);
        r.setHeader("x-new", "a");
        r.addHeader("x-new", "b");
        assertEquals("b", r.getHeader("x-new"));
        r.setHeader("x-new", "c");
        assertEquals("c", r.getHeader("x-new"));
        r.addHeader("accept", "text/html");
        r.addHeader("accept", "text/plain");
        Enumeration<String> accepts = r.getHeaders("accept");
        assertTrue(accepts.hasMoreElements());
    }

    @Test
    public void testUrlAndPathAccessors() throws Exception {
        Request r = request("GET", "/plain/a/b?x=1&y=2");
        assertEquals("/plain/a/b", r.getRequestURI());
        assertEquals("/plain/a/b", r.getRequestURL().toString());
        assertEquals("x=1&y=2", r.getQueryString());
        assertEquals("/plain", r.getContextPath());
        assertEquals("/svc", r.getServletPath());
        assertEquals("/info/x", r.getPathInfo());
        assertEquals(MappingMatch.PATH, r.getHttpServletMapping().getMappingMatch());
        assertEquals("svc", r.getHttpServletMapping().getServletName());
        assertEquals("GET /plain/a/b?x=1&y=2 HTTP/2.0", r.toString().replace("HTTP/2", "HTTP/2"));
        Request star = request("OPTIONS", "*");
        assertNull(star.getRequestURI());
        assertEquals("", star.getRequestURL().toString());
        assertNull(star.getURI());
    }

    @Test
    public void testPathTranslatedUnsupported() throws Exception {
        Request r = request("GET", "/plain/a");
        assertNull(r.getPathTranslated());
        r.match.pathInfo = null;
        assertNull(r.getPathTranslated());
    }

    // ===== Cookies =====

    @Test
    public void testCookiesParsed() throws Exception {
        Request r = request("GET", "/plain/a", "cookie", "a=1; b=2, c=3");
        Cookie[] cookies = r.getCookies();
        assertEquals(3, cookies.length);
        assertEquals("a", cookies[0].getName());
        assertEquals("2", cookies[1].getValue());
        assertSame(cookies, r.getCookies());
    }

    @Test
    public void testCookiesWithVersionAndPathParameters() throws Exception {
        Request r = request("GET", "/plain/a", "cookie", "$Version=1; a=1;$Path=/p;$Domain=d.org");
        Cookie[] cookies = r.getCookies();
        assertEquals(1, cookies.length);
        assertEquals("a", cookies[0].getName());
    }

    @Test
    public void testNoCookieHeader() throws Exception {
        Request r = request("GET", "/plain/a");
        assertNull(r.getCookies());
    }

    // ===== Parameters and body =====

    @Test
    public void testParameters() throws Exception {
        Request r = request("GET", "/plain/a?x=1&x=2&y&z=a%20b&=skip&flag");
        assertEquals("1", r.getParameter("x"));
        assertEquals(2, r.getParameterValues("x").length);
        assertNull(r.getParameter("y"));
        assertEquals("a b", r.getParameter("z"));
        assertNull(r.getParameter("missing"));
        assertNull(r.getParameterValues("missing"));
        Map<String, String[]> map = r.getParameterMap();
        assertTrue(map.containsKey("flag"));
        Enumeration<String> names = r.getParameterNames();
        assertTrue(names.hasMoreElements());
        try {
            map.put("q", new String[0]);
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertNull(e.getMessage());
        }
    }

    @Test
    public void testParameterStaticHelpers() {
        Map<String, List<String>> acc = new java.util.LinkedHashMap<String, List<String>>();
        Request.addParameter(acc, "a=1");
        Request.addParameter(acc, "a=2");
        Request.addParameter(acc, "b");
        Request.addParameter(acc, "=nameless");
        Request.addParameter(acc, "c", "3");
        assertEquals(2, acc.get("a").size());
        assertEquals(1, acc.get("b").size());
        assertNull(acc.get("b").get(0));
        assertFalse(acc.containsKey(""));
        assertEquals("3", acc.get("c").get(0));
    }

    @Test
    public void testStreamStateConflicts() throws Exception {
        Request r = request("POST", "/plain/a", "content-type", "text/plain; charset=UTF-8");
        assertNotNull(r.getInputStream());
        assertNotNull(r.getInputStream());
        try {
            r.getReader();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        Request r2 = request("POST", "/plain/a", "content-type", "text/plain; charset=UTF-8");
        BufferedReader reader = r2.getReader();
        assertNotNull(reader);
        assertNotNull(r2.getReader());
        try {
            r2.getInputStream();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testReaderCharsetFallbacks() throws Exception {
        Request r = request(basic, new StubState(), "POST", "/basic/a");
        assertNotNull(r.getReader());
        Request r2 = request("POST", "/plain/a");
        r2.context.requestCharacterEncoding = null;
        assertNotNull(r2.getReader());
    }

    @Test
    public void testCharacterEncoding() throws Exception {
        Request none = request("POST", "/plain/a");
        assertNull(none.getCharacterEncoding());
        Request quoted = request("POST", "/plain/a", "content-type", "text/html; charset=\"UTF-8\"");
        assertEquals("UTF-8", quoted.getCharacterEncoding());
        Request form = request("POST", "/plain/a", "content-type", "application/x-www-form-urlencoded");
        assertEquals("US-ASCII", form.getCharacterEncoding());
        Request other = request("POST", "/plain/a", "content-type", "text/html; foo=bar");
        assertNull(other.getCharacterEncoding());
        other.setCharacterEncoding("ISO-8859-1");
        assertEquals("ISO-8859-1", other.getCharacterEncoding());
        other.setCharacterEncoding((String) null);
        assertNull(other.getCharacterEncoding());
        try {
            other.setCharacterEncoding("no-such-charset");
            fail("expected UnsupportedEncodingException");
        } catch (UnsupportedEncodingException e) {
            assertEquals("no-such-charset", e.getMessage());
        }
    }

    @Test
    public void testContentAccessors() throws Exception {
        Request r = request("POST", "/plain/a", "content-length", "42", "content-type", "text/plain");
        assertEquals(42, r.getContentLength());
        assertEquals(42L, r.getContentLengthLong());
        assertEquals("text/plain", r.getContentType());
        Request none = request("GET", "/plain/a");
        assertEquals(-1, none.getContentLength());
        assertNull(none.getContentType());
    }

    // ===== Connection info =====

    @Test
    public void testConnectionAccessors() throws Exception {
        StubState state = new StubState();
        state.secure = true;
        Request r = request(plain, state, "GET", "/plain/a", "host", "example.org:8443");
        assertTrue(r.isSecure());
        assertEquals("https", r.getScheme());
        assertEquals("HTTP/2.0", r.getProtocol());
        assertEquals("example.org", r.getServerName());
        assertEquals(8443, r.getServerPort());
        assertEquals("127.0.0.1", r.getRemoteAddr());
        assertNotNull(r.getRemoteHost());
        assertEquals(40000, r.getRemotePort());
        assertEquals("127.0.0.1", r.getLocalAddr());
        assertEquals(8443, r.getLocalPort());
        assertNotNull(r.getLocalName());
        assertNotNull(r.getRequestId());
        assertNotNull(r.getProtocolRequestId());
        ServletConnection conn = r.getServletConnection();
        assertSame(conn, r.getServletConnection());
        assertTrue(conn.isSecure());
        assertSame(plain, r.getServletContext());
        Request noPort = request(plain, new StubState(), "GET", "/plain/a", "host", "example.org:abc");
        assertEquals(8443, noPort.getServerPort());
        Request bare = request(plain, new StubState(), "GET", "/plain/a", "host", "example.org");
        assertEquals("example.org", bare.getServerName());
        assertEquals(8443, bare.getServerPort());
    }

    // ===== Locale =====

    @Test
    public void testLocales() throws Exception {
        Request r = request("GET", "/plain/a", "accept-language", "fr;q=0.4, en-GB, de;q=0.8, es;q=0.1");
        assertEquals(Locale.UK, r.getLocale());
        Enumeration<Locale> all = r.getLocales();
        int count = 0;
        while (all.hasMoreElements()) {
            all.nextElement();
            count++;
        }
        assertEquals(4, count);
        Request none = request("GET", "/plain/a");
        assertEquals(Locale.getDefault(), none.getLocale());
        Enumeration<Locale> dflt = none.getLocales();
        assertEquals(Locale.getDefault(), dflt.nextElement());
        assertEquals(1.0, Request.parseDouble("1"), 0.0);
        assertEquals(0.25, Request.parseDouble("0.25"), 0.0001);
        assertEquals(0.05, Request.parseDouble("0.05"), 0.0001);
        assertEquals(2, r.getLocales("en,fr;q=0.5").size());
    }

    // ===== Attributes =====

    @Test
    public void testAttributesAndListeners() throws Exception {
        Request r = request("GET", "/plain/a");
        final List<String> events = new ArrayList<String>();
        ServletRequestAttributeListener l = new ServletRequestAttributeListener() {
            @Override public void attributeAdded(ServletRequestAttributeEvent event) {
                events.add("add:" + event.getName());
            }
            @Override public void attributeRemoved(ServletRequestAttributeEvent event) {
                events.add("remove:" + event.getName());
            }
            @Override public void attributeReplaced(ServletRequestAttributeEvent event) {
                events.add("replace:" + event.getName());
            }
        };
        r.context.servletRequestAttributeListeners.add(l);
        try {
            r.setAttribute("k", "v");
            r.setAttribute("k", "w");
            assertEquals("w", r.getAttribute("k"));
            assertTrue(r.getAttributeNames().hasMoreElements());
            r.removeAttribute("k");
            assertNull(r.getAttribute("k"));
        } finally {
            r.context.servletRequestAttributeListeners.remove(l);
        }
        assertEquals(3, events.size());
        assertEquals("add:k", events.get(0));
    }

    // ===== Sessions =====

    @Test
    public void testSessionCreationAndLookup() throws Exception {
        Request r = request("GET", "/plain/a");
        assertNull(r.getSession(false));
        assertFalse(r.isRequestedSessionIdValid());
        HttpSession s = r.getSession();
        assertNotNull(s);
        assertEquals(s.getId(), r.getRequestedSessionId());
        assertTrue(r.isRequestedSessionIdValid());
        assertSame(s, r.getSession(false));
        s.setAttribute("a", "1");
        String newId = r.changeSessionId();
        assertFalse(newId.equals(s.getId()));
        assertEquals(newId, r.getRequestedSessionId());
        Request none = request("GET", "/plain/a");
        try {
            none.changeSessionId();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testInitSessionFromCookieAndParameter() throws Exception {
        Request cookie = request("GET", "/plain/a", "cookie", "JSESSIONID=abc123; other=1");
        cookie.initSession();
        assertEquals("abc123", cookie.getRequestedSessionId());
        assertTrue(cookie.isRequestedSessionIdFromCookie());
        assertFalse(cookie.isRequestedSessionIdFromURL());
        Request param = request("GET", "/plain/a?jsessionid=zzz");
        param.initSession();
        assertEquals("zzz", param.getRequestedSessionId());
        assertTrue(param.isRequestedSessionIdFromURL());
        assertFalse(param.isRequestedSessionIdFromCookie());
        assertFalse(param.isRequestedSessionIdValid());
        Request neither = request("GET", "/plain/a", "cookie", "x=1");
        neither.initSession();
        assertNull(neither.getRequestedSessionId());
    }

    // ===== Authentication =====

    @Test
    public void testAuthenticateWithoutLoginConfig() throws Exception {
        Request r = request("GET", "/plain/a");
        StubState state = new StubState();
        assertTrue(r.authenticate(r.handler.getResponse()));
        assertNull(r.getAuthType());
        assertNull(r.getRemoteUser());
        assertFalse(r.isUserInRole("staff"));
        assertNull(r.getUserPrincipal());
        assertNotNull(state);
    }

    @Test
    public void testBasicAuthentication() throws Exception {
        StubState state = new StubState();
        Request denied = request(basic, state, "GET", "/basic/a");
        assertFalse(denied.authenticate(denied.handler.getResponse()));
        String good = "Basic " + Base64.getEncoder().encodeToString("bob:secret".getBytes(StandardCharsets.UTF_8));
        Request ok = request(basic, new StubState(), "GET", "/basic/a", "authorization", good);
        assertTrue(ok.authenticate(ok.handler.getResponse()));
        assertEquals("bob", ok.getRemoteUser());
        assertEquals("bob", ok.getUserPrincipal().getName());
        assertEquals("BASIC", ok.getAuthType());
        assertTrue(ok.isUserInRole("staff"));
        assertTrue(ok.isUserInRole("bob"));
        assertFalse(ok.isUserInRole("other"));
        ok.logout();
        assertNull(ok.getUserPrincipal());
    }

    @Test
    public void testFormAuthentication() throws Exception {
        Request missing = request(form, new StubState(), "GET", "/form/a");
        assertFalse(missing.authenticate(missing.handler.getResponse()));
        Request wrong = request(form, new StubState(), "GET", "/form/a?j_username=bob&j_password=nope");
        assertFalse(wrong.authenticate(wrong.handler.getResponse()));
        Request ok = request(form, new StubState(), "GET", "/form/a?j_username=bob&j_password=secret");
        assertTrue(ok.authenticate(ok.handler.getResponse()));
        assertEquals("bob", ok.getRemoteUser());
    }

    @Test
    public void testClientCertAuthenticationWithoutCertificates() throws Exception {
        Request r = request(cert, new StubState(), "GET", "/cert/a");
        assertFalse(r.authenticate(r.handler.getResponse()));
    }

    @Test
    public void testProgrammaticLogin() throws Exception {
        Request r = request(basic, new StubState(), "GET", "/basic/a");
        try {
            r.login("bob", "wrong");
            fail("expected ServletException");
        } catch (ServletException e) {
            assertNotNull(e.getMessage());
        }
        r.login("bob", "secret");
        assertEquals("bob", r.getRemoteUser());
        try {
            r.login("bob", "secret");
            fail("expected ServletException");
        } catch (ServletException e) {
            assertNotNull(e.getMessage());
        }
    }

    // ===== Parts, upgrade =====

    @Test
    public void testGetPartsRejectsWrongRequests() throws Exception {
        Request noType = request("POST", "/plain/a");
        try {
            noType.getParts();
            fail("expected ServletException");
        } catch (ServletException e) {
            assertNotNull(e.getMessage());
        }
        Request wrongType = request("POST", "/plain/a", "content-type", "text/plain");
        try {
            wrongType.getPart("x");
            fail("expected ServletException");
        } catch (ServletException e) {
            assertNotNull(e.getMessage());
        }
        Request noBoundary = request("POST", "/plain/a", "content-type", "multipart/form-data");
        try {
            noBoundary.getParts();
            fail("expected ServletException");
        } catch (ServletException e) {
            assertNotNull(e.getMessage());
        }
        Request noConfig = request("POST", "/plain/a", "content-type", "multipart/form-data; boundary=x");
        try {
            noConfig.getParts();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testUpgradeRequiresWebSocketRequest() throws Exception {
        Request r = request("GET", "/plain/a");
        try {
            r.upgrade(jakarta.servlet.http.HttpUpgradeHandler.class);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        assertFalse(r.isUpgraded());
        assertTrue(r.getTrailerFields().isEmpty());
    }

    // ===== Async and push =====

    @Test
    public void testAsyncLifecycle() throws Exception {
        Request r = request("GET", "/plain/a");
        assertFalse(r.isAsyncStarted());
        assertEquals(DispatcherType.REQUEST, r.getDispatcherType());
        try {
            r.getAsyncContext();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        AsyncContext ac = r.startAsync();
        assertTrue(r.isAsyncStarted());
        assertEquals(DispatcherType.ASYNC, r.getDispatcherType());
        assertSame(ac, r.getAsyncContext());
        ac.complete();
        assertFalse(r.isAsyncStarted());
        try {
            r.startAsync();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testStartAsyncWithRequestAndResponse() throws Exception {
        Request r = request("GET", "/plain/a");
        HttpServletResponse resp = r.handler.getResponse();
        AsyncContext ac = r.startAsync(r, resp);
        assertTrue(ac.hasOriginalRequestAndResponse());
        ac.complete();
        Request other = request("GET", "/plain/a");
        try {
            other.startAsync(r, other.handler.getResponse());
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        Request third = request("GET", "/plain/a");
        try {
            third.startAsync(third, resp);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testAsyncNotSupported() throws Exception {
        Request r = request("GET", "/plain/a");
        r.match.servletDef.asyncSupported = false;
        assertFalse(r.isAsyncSupported());
        try {
            r.startAsync();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        r.match = null;
        assertTrue(r.isAsyncSupported());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testPushBuilder() throws Exception {
        StubState state = new StubState();
        Request r = request(plain, state, "GET", "/plain/a?q=1", "accept", "text/html",
                "if-none-match", "tag", "cookie", "k=v");
        PushBuilder pb = r.newPushBuilder();
        assertNotNull(pb);
        assertEquals("GET", pb.getMethod());
        assertNull(pb.getPath());
        assertTrue(pb.getHeaderNames().contains("accept") || pb.getHeaderNames().contains("Accept"));
        assertNull(pb.getHeader("if-none-match"));
        assertNotNull(pb.getHeader("referer"));
        pb.method("head").queryString("a=b").sessionId("sid").path("/res.css")
                .setHeader("X-A", "1").addHeader("X-B", "2").removeHeader("accept");
        assertEquals("HEAD", pb.getMethod());
        assertEquals("a=b", pb.getQueryString());
        assertEquals("sid", pb.getSessionId());
        assertEquals("/res.css", pb.getPath());
        pb.push();
        assertEquals(1, state.pushed.size());
        assertNull(pb.getPath());
        try {
            pb.push();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        try {
            pb.method("POST");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
        try {
            pb.method("");
            fail("expected NullPointerException");
        } catch (NullPointerException e) {
            assertNotNull(e.getMessage());
        }
        try {
            pb.setHeader(null, "v");
            fail("expected NullPointerException");
        } catch (NullPointerException e) {
            assertNotNull(e.getMessage());
        }
        try {
            pb.addHeader(null, "v");
            fail("expected NullPointerException");
        } catch (NullPointerException e) {
            assertNotNull(e.getMessage());
        }
        pb.removeHeader(null);
        pb.sessionId(null).queryString(null).path("/b").push();
        assertEquals(2, state.pushed.size());
    }

    @Test
    public void testPushBuilderWithSessionRewriting() throws Exception {
        StubState state = new StubState();
        Request r = request(plain, state, "GET", "/plain/a");
        HttpSession session = r.getSession();
        assertNotNull(session);
        PushBuilder pb = r.newPushBuilder();
        assertEquals(session.getId(), pb.getSessionId());
        pb.path("/x").push();
        pb.queryString("a=1").path("/y").push();
        assertEquals(2, state.pushed.size());
    }

    // ===== Dispatcher lookup =====

    @Test
    public void testRelativeRequestDispatcher() throws Exception {
        Request r = request("GET", "/plain/a");
        RequestDispatcher d1 = r.getRequestDispatcher("/abs");
        assertNotNull(d1);
        RequestDispatcher d2 = r.getRequestDispatcher("rel");
        assertNotNull(d2);
        r.match.pathInfo = null;
        RequestDispatcher d3 = r.getRequestDispatcher("rel");
        assertNotNull(d3);
        assertNull(r.getRealPath("/x"));
        assertNull(r.getRealPath("rel"));
    }

    @Test
    public void testUnquote() {
        assertEquals("abc", Request.unq("\"abc\""));
        assertEquals("abc", Request.unq("abc"));
        assertEquals("\"", Request.unq("\""));
        assertNull(Request.unq(null));
    }

    @Test
    public void testRequestBodyStateHelpers() throws Exception {
        Request r = request("POST", "/plain/a");
        assertFalse(r.isUpgraded());
        assertEquals(Request.InputStreamState.NONE, r.inputStreamState);
        r.getInputStream();
        assertEquals(Request.InputStreamState.GET_INPUT_STREAM_CALLED, r.inputStreamState);
        assertNotNull(r.getURI());
    }

    @Test
    public void testIoFailureOnFormBodyIsLogged() throws Exception {
        Request r = request("POST", "/plain/a?q=1", "content-type", "application/x-www-form-urlencoded");
        r.handler.getRequest().in.toString();
        assertEquals("1", r.getParameter("q"));
        r.in.close();
        assertNull(r.getParameter("other"));
        try {
            r.in.available();
        } catch (IOException e) {
            assertNotNull(e);
        }
    }

    // ===== Regression tests =====

    private static final String MULTIPART_BODY = "--bnd\r\n"
            + "Content-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--bnd--\r\n";

    private static Request multipartRequest(boolean limits) throws Exception {
        byte[] body = MULTIPART_BODY.getBytes(StandardCharsets.UTF_8);
        Request r = requestWithBody(plain, new StubState(), "POST", "/plain/a", body,
                "content-type", "multipart/form-data; boundary=bnd",
                "content-length", Integer.toString(body.length));
        MultipartConfigDef config = new MultipartConfigDef();
        config.locationPath = tmp.newFolder("uploads");
        if (limits) {
            config.maxRequestSize = 5L;
        }
        r.match.servletDef.multipartConfig = config;
        return r;
    }

    @Test
    public void testGetPartsParsesBodyWithUnlimitedDefaults() throws Exception {
        Request r = multipartRequest(false);
        Collection<Part> parts = r.getParts();
        assertEquals(1, parts.size());
        Part p = r.getPart("field");
        assertNotNull(p);
        assertEquals(5L, p.getSize());
    }

    @Test
    public void testGetPartsEnforcesMaxRequestSize() throws Exception {
        Request r = multipartRequest(true);
        try {
            r.getParts();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testDateHeaderUnparseable() throws Exception {
        Request r = request("GET", "/plain/a", "if-modified-since", "not a date");
        try {
            r.getDateHeader("if-modified-since");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testLocaleMalformedQuality() throws Exception {
        Request r = request("GET", "/plain/a", "accept-language", "en-GB;q=abc, fr;q=0.5");
        assertEquals(Locale.FRENCH, r.getLocale());
        assertTrue(r.getLocales().hasMoreElements());
    }

    @Test
    public void testCookieWithoutEquals() throws Exception {
        Request r = request("GET", "/plain/a", "cookie", "flag; a=1");
        Cookie[] cookies = r.getCookies();
        assertEquals("a", cookies[cookies.length - 1].getName());
        assertEquals("1", cookies[cookies.length - 1].getValue());
    }

    @Test
    public void testCookiePathAndDomainParameters() throws Exception {
        Request r = request("GET", "/plain/a", "cookie", "$Version=1; a=1;$Path=/p;$Domain=d.org");
        Cookie[] cookies = r.getCookies();
        assertEquals(1, cookies.length);
        assertEquals("/p", cookies[0].getPath());
        assertEquals("d.org", cookies[0].getDomain());
    }

    @Test
    public void testFormBodyDecodedWithDeclaredCharset() throws Exception {
        byte[] body = "name=caf%C3%A9&plus=a+b".getBytes(StandardCharsets.UTF_8);
        Request r = requestWithBody(plain, new StubState(), "POST", "/plain/a", body,
                "content-type", "application/x-www-form-urlencoded; charset=UTF-8");
        assertEquals("caf\u00e9", r.getParameter("name"));
        assertEquals("a b", r.getParameter("plus"));
    }

    @Test
    public void testFormBodyDecodedWithSetCharacterEncoding() throws Exception {
        byte[] body = "name=caf%E9".getBytes(StandardCharsets.US_ASCII);
        Request r = requestWithBody(plain, new StubState(), "POST", "/plain/a", body,
                "content-type", "application/x-www-form-urlencoded");
        r.setCharacterEncoding("ISO-8859-1");
        assertEquals("caf\u00e9", r.getParameter("name"));
    }

    @Test
    public void testAsyncDispatchCompletesResponse() throws Exception {
        StubState state = new StubState();
        Request r = request(plain, state, "GET", "/plain/a");
        AsyncContext ac = r.startAsync();
        final CountDownLatch done = new CountDownLatch(1);
        ac.addListener(new AsyncListener() {
            @Override public void onComplete(AsyncEvent event) {
                done.countDown();
            }
            @Override public void onTimeout(AsyncEvent event) { }
            @Override public void onError(AsyncEvent event) { }
            @Override public void onStartAsync(AsyncEvent event) { }
        });
        ac.dispatch("/plain/nothing");
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertFalse(r.isAsyncStarted());
        assertTrue(((AsyncContextImpl) ac).isCompleted());
        assertTrue(state.completed);
    }
}
