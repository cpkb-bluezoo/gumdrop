/*
 * ManagerServletTest.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.servlet.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.Before;
import org.junit.Test;
import org.xml.sax.SAXException;

/**
 * Tests {@link ManagerServlet} rendering and configuration posts using
 * JDK dynamic proxies for the servlet, request and manager SPI types, so
 * no container or transport is needed.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ManagerServletTest {

    /** Answers proxy calls from a name-keyed table and records them. */
    private static final class Answers implements InvocationHandler {
        final Map<String, Object> values = new HashMap<String, Object>();
        final List<String> calls = new ArrayList<String>();

        Answers with(String method, Object value) {
            values.put(method, value);
            return this;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            String name = method.getName();
            StringBuilder call = new StringBuilder(name);
            if (args != null && args.length > 0) {
                call.append(':').append(args[0]);
            }
            calls.add(call.toString());
            if (values.containsKey(name)) {
                Object v = values.get(name);
                if (v instanceof Throwable) {
                    throw (Throwable) v;
                }
                return v;
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return Boolean.FALSE;
            }
            if (rt == int.class) {
                return Integer.valueOf(0);
            }
            if (rt == long.class) {
                return Long.valueOf(0);
            }
            return null;
        }
    }

    /** Map-backed HttpSession answering attribute calls for real. */
    private static final class SessionAnswers implements InvocationHandler {
        final Map<String, Object> attributes = new HashMap<String, Object>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getAttribute".equals(name)) {
                return attributes.get(args[0]);
            }
            if ("setAttribute".equals(name)) {
                attributes.put((String) args[0], args[1]);
            }
            return null;
        }
    }

    private static final String TOKEN = "known-session-token";

    private static <T> T proxy(Class<T> type, Answers answers) {
        return type.cast(Proxy.newProxyInstance(
                ManagerServletTest.class.getClassLoader(),
                new Class<?>[] {type}, answers));
    }

    private static final class FixedStats extends HitStatistics {
        @Override
        public long getTotal() {
            return 42;
        }

        @Override
        public long getHits(int type) {
            return type * 10;
        }
    }

    private static final class CapturingStream extends ServletOutputStream {
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
        }

        @Override
        public void write(int b) {
            sink.write(b);
        }
    }

    private ThreadPoolExecutor pool;
    private Answers contextAnswers;
    private Answers otherContextAnswers;
    private Answers containerAnswers;
    private Answers requestAnswers;
    private Answers responseAnswers;
    private CapturingStream body;
    private ManagerServlet servlet;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private final List<String> keepAliveSet = new ArrayList<String>();
    private Map<String, String> params;
    private Answers filterAnswers;
    private SessionAnswers sessionAnswers;
    private Answers servletAnswers;

    @Before
    public void setUp() throws Exception {
        pool = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        body = new CapturingStream();
        params = new LinkedHashMap<String, String>();
        params.put(ManagerServlet.CSRF_PARAMETER, TOKEN);

        filterAnswers = new Answers()
                .with("getName", "audit")
                .with("getServletNameMappings", Arrays.asList("main"))
                .with("getUrlPatternMappings", Arrays.asList("/a/*", "/b"));
        servletAnswers = new Answers()
                .with("getName", "main")
                .with("getDisplayName", "Main <Servlet>")
                .with("getDescription", "the \"main\" one")
                .with("getMappings", Arrays.asList("/x", "/y"));
        Map<String, Object> filters = new LinkedHashMap<String, Object>();
        filters.put("audit", proxy(FilterReg.class, filterAnswers));
        Map<String, Object> servlets = new LinkedHashMap<String, Object>();
        servlets.put("main", proxy(ServletReg.class, servletAnswers));

        containerAnswers = new Answers();
        contextAnswers = new Answers()
                .with("getContextPath", "/app")
                .with("getDisplayName", "My App")
                .with("getRoot", "/srv/app")
                .with("getDescription", "An app & more")
                .with("getSmallIcon", "/app/icon.png")
                .with("getHitStatistics", new FixedStats())
                .with("getFilterRegistrations", filters)
                .with("getServletRegistrations", servlets)
                .with("getServerInfo", "gumdrop/test")
                .with("getWorkerThreadPool", pool)
                .with("getWorkerKeepAlive", java.time.Duration.ofSeconds(90))
                .with("getContainer",
                        proxy(ManagerContainerServer.class, containerAnswers));
        // Registrations are typed maps of registration objects.
        otherContextAnswers = new Answers()
                .with("getContextPath", "/bare")
                .with("getHitStatistics", new FixedStats())
                .with("getFilterRegistrations", Collections.emptyMap())
                .with("getServletRegistrations", Collections.emptyMap());
        final ManagerContextServer ctx =
                proxy(ManagerContextServer.class, contextAnswers);
        final ManagerContextServer bare =
                proxy(ManagerContextServer.class, otherContextAnswers);
        Collection<ManagerContextServer> all =
                new ArrayList<ManagerContextServer>();
        all.add(ctx);
        all.add(bare);
        containerAnswers.with("getContexts", all);
        containerAnswers.with("getContext", ctx);

        servlet = new ManagerServlet();
        Answers config = new Answers().with("getServletContext", ctx);
        servlet.init(proxy(ServletConfig.class, config));

        sessionAnswers = new SessionAnswers();
        sessionAnswers.attributes.put(ManagerServlet.CSRF_ATTRIBUTE, TOKEN);
        requestAnswers = new Answers()
                .with("getSession", Proxy.newProxyInstance(
                        ManagerServletTest.class.getClassLoader(),
                        new Class<?>[] {jakarta.servlet.http.HttpSession.class}, sessionAnswers))
                .with("isUserInRole", Boolean.TRUE)
                .with("getScheme", "http")
                .with("getServerName", "manager.example")
                .with("getServerPort", Integer.valueOf(8080))
                .with("getMethod", "GET")
                .with("getLocale", Locale.ENGLISH)
                .with("getContextPath", "/manager");
        responseAnswers = new Answers().with("getOutputStream", body);
        request = proxy(HttpServletRequest.class, requestAnswers);
        response = proxy(HttpServletResponse.class, responseAnswers);
    }

    private void withParams(String... nameValue) {
        for (int i = 0; i < nameValue.length; i += 2) {
            params.put(nameValue[i], nameValue[i + 1]);
        }
        requestAnswers.with("getParameterNames",
                Collections.enumeration(params.keySet()));
        // getParameter is answered per name below
        request = (HttpServletRequest) Proxy.newProxyInstance(
                ManagerServletTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method m, Object[] a)
                            throws Throwable {
                        if ("getParameter".equals(m.getName())) {
                            return params.get(a[0]);
                        }
                        return requestAnswers.invoke(p, m, a);
                    }
                });
    }

    private String html() {
        return new String(body.sink.toByteArray(),
                StandardCharsets.UTF_8);
    }

    // ── GET ──

    @Test
    public void getRendersPageWithPoolAndContexts() throws Exception {
        servlet.service(request, response);
        String out = html();
        assertTrue(out, out.startsWith("<!DOCTYPE html>"));
        assertTrue(out.contains("<title>Manager</title>"));
        assertTrue(out.contains("href='/manager/manager.css'"));
        assertTrue(out.contains("gumdrop/test on "));
        assertTrue(out.contains("value='2'"));
        assertTrue(out.contains("value='8'"));
        assertTrue(out.contains("value='90s'"));
        assertTrue(out.contains("My App"));
        assertTrue(out.contains("/srv/app"));
        assertTrue(out.contains("An app &amp; more"));
        assertTrue(out.contains("/app/icon.png"));
        assertTrue(out.contains(">42<"));
        assertTrue(out.contains("name='reload' value='/app'"));
        assertTrue(responseAnswers.calls.contains("setStatus:200"));
        assertTrue(responseAnswers.calls.contains(
                "setContentType:text/html; charset=UTF-8"));
    }

    @Test
    public void getListsFiltersAndServletsWithEscaping() throws Exception {
        servlet.service(request, response);
        String out = html();
        assertTrue(out.contains("audit"));
        assertTrue(out.contains("→main /a/* /b"));
        assertTrue(out.contains("Main &lt;Servlet&gt;"));
        assertTrue(out.contains("the &quot;main&quot; one"));
        assertTrue(out.contains("/x /y"));
        assertTrue(out.contains("gumdrop_yellow_16x16.png"));
        assertTrue(out.contains("gumdrop_purple_16x16.png"));
    }

    private static final String ATTRIBUTE_BREAKOUT = "/app/i.png' onerror='alert(1)";

    @Test
    public void getEscapesQuoteInContextIcon() throws Exception {
        contextAnswers.with("getSmallIcon", ATTRIBUTE_BREAKOUT);
        servlet.service(request, response);
        String out = html();
        assertFalse(out, out.contains("onerror='"));
    }

    @Test
    public void getEscapesQuoteInFilterAndServletIcons() throws Exception {
        filterAnswers.with("getSmallIcon", ATTRIBUTE_BREAKOUT);
        servletAnswers.with("getSmallIcon", ATTRIBUTE_BREAKOUT);
        servlet.service(request, response);
        String out = html();
        assertFalse(out, out.contains("onerror='"));
    }

    @Test
    public void getRejectsIconsThatAreNotSameOriginPaths() throws Exception {
        String[] bad = { "http://evil.example/x.png", "//evil.example/x.png",
                "javascript:alert(1)", "data:image/png;base64,AAAA", "x.png", "/a\\b.png" };
        for (int i = 0; i < bad.length; i++) {
            contextAnswers.with("getSmallIcon", bad[i]);
            filterAnswers.with("getSmallIcon", bad[i]);
            servletAnswers.with("getSmallIcon", bad[i]);
            body.sink.reset();
            servlet.service(request, response);
            String out = html();
            assertFalse(bad[i] + " must not be rendered", out.contains("src='" + bad[i]));
            assertFalse(bad[i] + " must not be rendered", out.contains("evil.example"));
            assertTrue(out.contains("/manager/gumdrop_green_16x16.png"));
            assertTrue(out.contains("gumdrop_yellow_16x16.png"));
            assertTrue(out.contains("gumdrop_purple_16x16.png"));
        }
    }

    @Test
    public void getUsesDefaultsForBareContext() throws Exception {
        servlet.service(request, response);
        String out = html();
        assertTrue(out.contains("/bare"));
        assertTrue(out.contains("/manager/gumdrop_green_16x16.png"));
    }

    @Test
    public void getWithoutLocaleStillRenders() throws Exception {
        requestAnswers.with("getLocale", null);
        servlet.service(request, response);
        assertTrue(html().contains("<title>"));
    }

    // ── POST ──

    private void post() throws Exception {
        servlet.service(request, response);
    }

    private void asPost() {
        requestAnswers.with("getMethod", "POST");
    }

    private void assertForbiddenAndUnchanged() {
        assertTrue(responseAnswers.calls.contains("sendError:403"));
        assertEquals(2, pool.getCorePoolSize());
        assertFalse(contextAnswers.calls.contains("reload"));
    }

    @Test
    public void getEmbedsSessionTokenInEveryForm() throws Exception {
        sessionAnswers.attributes.clear();
        servlet.service(request, response);
        Object token = sessionAnswers.attributes.get(ManagerServlet.CSRF_ATTRIBUTE);
        assertTrue("token created and stored in session", token instanceof String
                && ((String) token).length() >= 32);
        String out = html();
        String field = "name='csrf' value='" + token + "'";
        int forms = out.split("<form", -1).length - 1;
        int fields = out.split(java.util.regex.Pattern.quote(field), -1).length - 1;
        assertTrue("forms rendered: " + forms, forms >= 4);
        assertEquals("every form carries the token", forms, fields);
    }

    @Test
    public void postWithoutTokenIsForbidden() throws Exception {
        asPost();
        params.remove(ManagerServlet.CSRF_PARAMETER);
        withParams("core-pool-size", "7", "reload", "/app");
        post();
        assertForbiddenAndUnchanged();
    }

    @Test
    public void postWithWrongTokenIsForbidden() throws Exception {
        asPost();
        withParams(ManagerServlet.CSRF_PARAMETER, "guess", "core-pool-size", "7", "reload", "/app");
        post();
        assertForbiddenAndUnchanged();
    }

    @Test
    public void postWithoutSessionIsForbidden() throws Exception {
        asPost();
        requestAnswers.with("getSession", null);
        withParams("core-pool-size", "7", "reload", "/app");
        post();
        assertForbiddenAndUnchanged();
    }

    @Test
    public void postFromOtherOriginIsForbiddenEvenWithToken() throws Exception {
        asPost();
        requestAnswers.with("getHeader", "http://evil.example");
        withParams("core-pool-size", "7", "reload", "/app");
        post();
        assertForbiddenAndUnchanged();
    }

    @Test
    public void postFromOtherRefererIsForbiddenEvenWithToken() throws Exception {
        asPost();
        requestAnswers.with("getHeader", "http://manager.example.evil.example/x");
        withParams("core-pool-size", "7", "reload", "/app");
        post();
        assertForbiddenAndUnchanged();
    }

    @Test
    public void postFromManagerOriginWithTokenSucceeds() throws Exception {
        asPost();
        requestAnswers.with("getHeader", "http://manager.example:8080");
        withParams("core-pool-size", "3");
        post();
        assertEquals(3, pool.getCorePoolSize());
        assertTrue(responseAnswers.calls.contains("sendRedirect:/manager/"));
    }

    @Test
    public void postWithoutManagerRoleIsForbidden() throws Exception {
        asPost();
        requestAnswers.with("isUserInRole", Boolean.FALSE);
        withParams("core-pool-size", "7", "reload", "/app");
        post();
        assertForbiddenAndUnchanged();
    }

    @Test
    public void getWithoutManagerRoleIsForbidden() throws Exception {
        requestAnswers.with("isUserInRole", Boolean.FALSE);
        servlet.service(request, response);
        assertTrue(responseAnswers.calls.contains("sendError:403"));
        assertFalse(html().contains("context-card"));
    }

    @Test
    public void postUpdatesPoolSizesAndRedirects() throws Exception {
        asPost();
        withParams("core-pool-size", "3", "maximum-pool-size", "12");
        post();
        assertEquals(3, pool.getCorePoolSize());
        assertEquals(12, pool.getMaximumPoolSize());
        assertTrue(responseAnswers.calls.contains("sendRedirect:/manager/"));
    }

    @Test
    public void postSetsKeepAlive() throws Exception {
        asPost();
        contextAnswers.with("setWorkerKeepAlive", null);
        withParams("keep-alive-time", "90s");
        post();
        assertTrue(contextAnswers.calls.contains("setWorkerKeepAlive:" + java.time.Duration.ofSeconds(90)));
        assertTrue(responseAnswers.calls.contains("sendRedirect:/manager/"));
    }

    @Test
    public void postBadNumberIsBadRequest() throws Exception {
        asPost();
        withParams("core-pool-size", "many");
        post();
        assertTrue(responseAnswers.calls.contains("sendError:400"));
        assertEquals(2, pool.getCorePoolSize());
    }

    @Test
    public void postBadMaximumIsBadRequest() throws Exception {
        asPost();
        withParams("maximum-pool-size", "0");
        post();
        assertTrue(responseAnswers.calls.contains("sendError:400"));
    }

    @Test
    public void postBadKeepAliveIsBadRequest() throws Exception {
        asPost();
        contextAnswers.with("setWorkerKeepAlive",
                new IllegalArgumentException("bad"));
        withParams("keep-alive-time", "soon");
        post();
        assertTrue(responseAnswers.calls.contains("sendError:400"));
    }

    @Test
    public void postReloadsNamedContext() throws Exception {
        asPost();
        withParams("reload", "/app");
        post();
        assertTrue(containerAnswers.calls.contains("getContext:/app"));
        assertTrue(contextAnswers.calls.contains("reload"));
        assertTrue(responseAnswers.calls.contains("sendRedirect:/manager/"));
    }

    @Test
    public void postReloadFailureIs500WithLocalisedMessage() throws Exception {
        asPost();
        contextAnswers.with("reload", new SAXException("bad xml"));
        withParams("reload", "/app");
        post();
        assertTrue(responseAnswers.calls.contains("sendError:500"));
    }

    @Test
    public void postIgnoresUnknownAndNullParameters() throws Exception {
        asPost();
        params.put("null-valued", null);
        withParams("something-else", "x");
        post();
        assertTrue(responseAnswers.calls.contains("sendRedirect:/manager/"));
        assertNull(params.get("null-valued"));
    }

    @Test
    public void ioExceptionsPropagateFromResponse() throws Exception {
        responseAnswers.with("getOutputStream", new IOException("gone"));
        try {
            servlet.service(request, response);
            fail("expected IOException");
        } catch (IOException expected) {
            // client went away
        }
    }
}
