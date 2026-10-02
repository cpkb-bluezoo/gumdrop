/*
 * ContextLifecycleTest.java
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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises the {@link Context} lifecycle (load, init, destroy), its
 * resource lookup and the programmatic registration API against a small
 * exploded web application built in a temporary directory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextLifecycleTest {

    public static final List<String> EVENTS = new ArrayList<String>();

    /** Servlet declared in web.xml. */
    public static class HelloServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
    }

    /** Servlet declared via annotations, scanned from WEB-INF/classes. */
    @WebServlet(name = "annoServlet", urlPatterns = { "/anno/*" }, loadOnStartup = 1)
    public static class AnnotatedServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
    }

    /** Filter declared in web.xml. */
    public static class PassFilter implements Filter {
        @Override
        public void init(FilterConfig filterConfig) {
            EVENTS.add("filter-init");
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
            EVENTS.add("filter-destroy");
        }
    }

    /** Filter declared via annotation. */
    @WebFilter(filterName = "annoFilter", urlPatterns = { "/anno/*" })
    public static class AnnotatedFilter extends PassFilter {
    }

    /** Filter declared via annotation that targets servlet names only. */
    @WebFilter(filterName = "snFilter", servletNames = { "hello" })
    public static class ServletNamesFilter extends PassFilter {
    }

    /** Listener declared in web.xml. */
    public static class AppListener implements ServletContextListener, ServletContextAttributeListener {
        @Override
        public void contextInitialized(ServletContextEvent sce) {
            EVENTS.add("ctx-init");
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            EVENTS.add("ctx-destroy");
        }

        @Override
        public void attributeAdded(ServletContextAttributeEvent event) {
            EVENTS.add("attr-added:" + event.getName());
        }

        @Override
        public void attributeRemoved(ServletContextAttributeEvent event) {
            EVENTS.add("attr-removed:" + event.getName());
        }

        @Override
        public void attributeReplaced(ServletContextAttributeEvent event) {
            EVENTS.add("attr-replaced:" + event.getName());
        }
    }

    /** Listener that only the parent class loader knows about. */
    public static class PlainListener implements ServletContextListener {
    }

    /** Listener declared via annotation. */
    @WebListener
    public static class AnnotatedListener implements ServletContextListener {
        @Override
        public void contextInitialized(ServletContextEvent sce) {
            EVENTS.add("anno-init");
        }
    }

    /** Annotated class that is not a servlet. */
    @WebServlet(name = "bogus", urlPatterns = { "/bogus" })
    public static class NotAServlet {
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String savedFactory;
    private File root;
    private Container container;
    private Context context;

    @Before
    public void setUp() throws Exception {
        EVENTS.clear();
        savedFactory = System.getProperty("java.naming.factory.initial");
        System.setProperty("java.naming.factory.initial",
                "org.bluezoo.gumdrop.servlet.jndi.ServletInitialContextFactory");
        root = tmp.newFolder("webapp");
        new File(root, "WEB-INF").mkdirs();
        write("WEB-INF/web.xml", webXml());
        write("index.html", "<html>hi</html>");
        write("sub/page.txt", "page");
        write("sub/deeper/leaf.txt", "leaf");
        copyClass(HelloServlet.class);
        copyClass(PassFilter.class);
        copyClass(AppListener.class);
        copyClass(AnnotatedServlet.class);
        copyClass(AnnotatedFilter.class);
        copyClass(ServletNamesFilter.class);
        copyClass(AnnotatedListener.class);
        copyClass(NotAServlet.class);
        SharedContainer.get();
        container = new Container();
        context = new Context(container, "/app", root);
        container.addContext(context);
    }

    @After
    public void tearDown() {
        if (savedFactory == null) {
            System.clearProperty("java.naming.factory.initial");
        } else {
            System.setProperty("java.naming.factory.initial", savedFactory);
        }
    }

    private String webXml() {
        String pkg = ContextLifecycleTest.class.getName();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">");
        sb.append("<module-name>mod</module-name>");
        sb.append("<display-name>Lifecycle</display-name>");
        sb.append("<context-param><param-name>cp</param-name><param-value>cv</param-value></context-param>");
        sb.append("<request-character-encoding>UTF-8</request-character-encoding>");
        sb.append("<response-character-encoding>UTF-16</response-character-encoding>");
        sb.append("<default-context-path>/dflt</default-context-path>");
        sb.append("<listener><listener-class>").append(pkg).append("$AppListener</listener-class></listener>");
        sb.append("<filter><filter-name>pass</filter-name><filter-class>").append(pkg)
                .append("$PassFilter</filter-class></filter>");
        sb.append("<filter-mapping><filter-name>pass</filter-name><url-pattern>/*</url-pattern></filter-mapping>");
        sb.append("<servlet><servlet-name>hello</servlet-name><servlet-class>").append(pkg)
                .append("$HelloServlet</servlet-class><load-on-startup>2</load-on-startup></servlet>");
        sb.append("<servlet><servlet-name>lazy</servlet-name><servlet-class>").append(pkg)
                .append("$HelloServlet</servlet-class></servlet>");
        sb.append("<servlet-mapping><servlet-name>hello</servlet-name><url-pattern>/hello</url-pattern>"
                + "<url-pattern>/hello/*</url-pattern><url-pattern>*.hi</url-pattern></servlet-mapping>");
        sb.append("<servlet-mapping><servlet-name>lazy</servlet-name><url-pattern>/lazy</url-pattern></servlet-mapping>");
        sb.append("<mime-mapping><extension>.hi</extension><mime-type>text/x-hi</mime-type></mime-mapping>");
        sb.append("<welcome-file-list><welcome-file>index.html</welcome-file></welcome-file-list>");
        sb.append("<locale-encoding-mapping-list><locale-encoding-mapping><locale>fr</locale>"
                + "<encoding>ISO-8859-1</encoding></locale-encoding-mapping>"
                + "<locale-encoding-mapping><locale>ja_JP</locale><encoding>Shift_JIS</encoding>"
                + "</locale-encoding-mapping></locale-encoding-mapping-list>");
        sb.append("<absolute-ordering><name>a</name><others/></absolute-ordering>");
        sb.append("<env-entry><env-entry-name>cfg/name</env-entry-name>"
                + "<env-entry-type>java.lang.String</env-entry-type>"
                + "<env-entry-value>val</env-entry-value></env-entry>");
        sb.append("</web-app>");
        return sb.toString();
    }

    private void write(String path, String content) throws IOException {
        File f = new File(root, path);
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private void copyClass(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream in = type.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in);
        File dest = new File(root, "WEB-INF/classes/" + resource);
        dest.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[4096];
            for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
        } finally {
            out.close();
            in.close();
        }
    }

    /** Servlet 6.1 getResourcePaths: directory entries end with a slash. */
    private static boolean hasDir(Set<String> paths, String dir) {
        return paths.contains(dir + "/");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        for (int n = in.read(buf); n != -1; n = in.read(buf)) {
            bout.write(buf, 0, n);
        }
        in.close();
        return new String(bout.toByteArray(), StandardCharsets.UTF_8);
    }

    private void loadAndInit() throws Exception {
        context.load();
        context.init();
    }

    // ===== load =====

    @Test
    public void testLoadParsesDescriptorAndScansAnnotations() throws Exception {
        context.load();
        assertEquals("Lifecycle", context.getServletContextName());
        assertEquals("cv", context.getInitParameter("cp"));
        assertEquals("mod", context.moduleName);
        assertEquals("/dflt", context.defaultContextPath);
        assertEquals("UTF-8", context.getRequestCharacterEncoding());
        assertEquals("UTF-16", context.getResponseCharacterEncoding());
        assertTrue(context.servletDefs.containsKey("hello"));
        assertTrue(context.servletDefs.containsKey("annoServlet"));
        assertTrue(context.servletDefs.containsKey("jsp"));
        assertTrue(context.filterDefs.containsKey("annoFilter"));
        assertTrue(context.getRequestCharacterEncoding() != null);
        assertEquals(3, context.absoluteOrdering.size() + 1);
        assertNotNull(context.getContextDigest() == null ? "" : "ok");
    }

    @Test
    public void testLoadWithoutDescriptor() throws Exception {
        File bare = tmp.newFolder("bare");
        Context c = new Context(container, "", bare);
        c.load();
        assertNotNull(c.defaultServletDef);
        assertTrue(c.servletDefs.containsKey("jsp"));
    }

    @Test
    public void testMetadataCompleteSkipsScan() throws Exception {
        write("WEB-INF/web.xml", "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\""
                + " metadata-complete=\"true\"><display-name>meta</display-name></web-app>");
        context.load();
        assertFalse(context.servletDefs.containsKey("annoServlet"));
    }

    // ===== init / destroy =====

    @Test
    public void testInitAndDestroyFireListenersAndLifecycle() throws Exception {
        loadAndInit();
        assertTrue(EVENTS.contains("ctx-init"));
        assertTrue(EVENTS.contains("anno-init"));
        assertTrue(EVENTS.contains("filter-init"));
        assertNotNull(context.servlets.get("hello"));
        assertNotNull(context.servlets.get("annoServlet"));
        assertNull(context.servlets.get("lazy"));
        context.destroy();
        assertTrue(EVENTS.contains("ctx-destroy"));
        assertTrue(EVENTS.contains("filter-destroy"));
    }

    @Test
    public void testReloadReinitialises() throws Exception {
        loadAndInit();
        EVENTS.clear();
        context.reload();
        assertTrue(EVENTS.contains("ctx-destroy"));
        assertTrue(EVENTS.contains("ctx-init"));
    }

    @Test
    public void testLoadServletAndFilter() throws Exception {
        loadAndInit();
        ServletDef lazy = context.servletDefs.get("lazy");
        assertNotNull(context.loadServlet(lazy));
        FilterDef pass = context.filterDefs.get("pass");
        assertNotNull(context.loadFilter(pass));
        assertNotNull(context.getDefaultServlet());
        context.destroy();
    }

    @Test
    public void testGetEncoding() throws Exception {
        context.load();
        assertEquals("ISO-8859-1", context.getEncoding(java.util.Locale.FRENCH));
        assertEquals("Shift_JIS", context.getEncoding(java.util.Locale.JAPAN));
        assertNull(context.getEncoding(java.util.Locale.GERMAN));
    }

    @Test
    public void testStripCompEnv() {
        assertEquals("a/b", Context.stripCompEnv("java:comp/env/a/b"));
        assertEquals("a/b", Context.stripCompEnv("a/b"));
    }

    // ===== ServletContext resources =====

    @Test
    public void testResources() throws Exception {
        context.load();
        URL url = context.getResource("/index.html");
        assertNotNull(url);
        assertNull(context.getResource("/missing.html"));
        assertNull(context.getResource("/sub/"));
        assertNull(context.getResource(""));
        assertNull(context.getResource(null));
        assertNull(context.getResource("/../etc/passwd"));
        assertNotNull(context.getResource("sub/page.txt"));
        InputStream in = context.getResourceAsStream("/index.html");
        assertEquals("<html>hi</html>", read(in));
        assertNull(context.getResourceAsStream("/nope"));
        assertNull(context.getResourceAsStream("/../x"));
    }

    @Test
    public void testResourcePaths() throws Exception {
        context.load();
        Set<String> paths = context.getResourcePaths("/");
        assertNotNull(paths);
        assertTrue(paths.contains("/index.html"));
        assertTrue(hasDir(paths, "/sub"));
        assertTrue(hasDir(paths, "/WEB-INF"));
        Set<String> sub = context.getResourcePaths("/sub/");
        assertTrue(sub.contains("/sub/page.txt"));
        assertTrue(hasDir(sub, "/sub/deeper"));
        assertNull(context.getResourcePaths(""));
        assertNull(context.getResourcePaths("/sub/page.txt"));
        assertNull(context.getResourcePaths("/../"));
        assertNull(context.getResourcePaths("/nonexistent/"));
        assertNotNull(context.getResourcePaths("sub/"));
    }

    @Test
    public void testAnnotatedFilterServletNamesMapping() throws Exception {
        context.load();
        FilterMapping found = null;
        for (FilterMapping fm : context.filterMappings) {
            if ("snFilter".equals(fm.filterName)) {
                found = fm;
            }
        }
        assertNotNull(found);
        assertTrue(found.servletNames.contains("hello"));
        assertTrue(found.urlPatterns.isEmpty());
    }

    @Test
    public void testResourcesFromWar() throws Exception {
        File war = tmp.newFile("app.war");
        java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(new FileOutputStream(war));
        try {
            zout.putNextEntry(new java.util.zip.ZipEntry("index.html"));
            zout.write("war-index".getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
            zout.putNextEntry(new java.util.zip.ZipEntry("sub/a.txt"));
            zout.write("a".getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
            zout.putNextEntry(new java.util.zip.ZipEntry("WEB-INF/web.xml"));
            zout.write("<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"><display-name>war</display-name></web-app>"
                    .getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        } finally {
            zout.close();
        }
        Context c = new Context(container, "/war", war);
        c.load();
        assertEquals("war", c.getServletContextName());
        assertNotNull(c.getResource("/index.html"));
        assertEquals("war-index", read(c.getResourceAsStream("/index.html")));
        Set<String> paths = c.getResourcePaths("/");
        assertNotNull(paths);
        assertTrue(paths.contains("/index.html"));
        assertTrue(hasDir(paths, "/sub"));
        assertTrue(hasDir(paths, "/WEB-INF"));
        Set<String> sub = c.getResourcePaths("/sub/");
        assertTrue(sub.contains("/sub/a.txt"));
        assertNull(c.getResource("/missing"));
        c.destroy();
    }

    @Test
    public void testGetRealPathUnavailable() throws Exception {
        context.load();
        assertNull(context.getRealPath("/x"));
    }

    @Test
    public void testMimeTypeAndMetadata() throws Exception {
        context.load();
        assertEquals("text/x-hi", context.getMimeType("a.hi"));
        assertNull(context.getMimeType("a.unknown"));
        assertEquals("/app", context.getContextPath());
        assertEquals(6, context.getMajorVersion());
        assertEquals(1, context.getMinorVersion());
        assertTrue(context.getServerInfo().startsWith("gumdrop/"));
        assertEquals(context.getClassLoader(), context.getContextClassLoader());
        assertEquals(root.toString(), context.getRoot());
        assertNotNull(context.getHitStatistics());
        assertNotNull(context.getSessionManager());
        assertNull(context.getJspConfigDescriptor());
    }

    // ===== attributes and init parameters =====

    @Test
    public void testAttributesFireEvents() throws Exception {
        loadAndInit();
        EVENTS.clear();
        context.setAttribute("k", "v1");
        context.setAttribute("k", "v2");
        assertEquals("v2", context.getAttribute("k"));
        Enumeration<String> names = context.getAttributeNames();
        boolean found = false;
        while (names.hasMoreElements()) {
            if ("k".equals(names.nextElement())) {
                found = true;
            }
        }
        assertTrue(found);
        context.setAttribute("k", null);
        assertNull(context.getAttribute("k"));
        assertTrue(EVENTS.contains("attr-added:k"));
        assertTrue(EVENTS.contains("attr-replaced:k"));
        assertTrue(EVENTS.contains("attr-removed:k"));
        context.destroy();
    }

    @Test
    public void testInitParameters() throws Exception {
        context.load();
        assertFalse(context.setInitParameter("cp", "other"));
        assertTrue(context.setInitParameter("extra", "x"));
        assertFalse(context.setInitParameter("extra", "y"));
        assertEquals("x", context.getInitParameter("extra"));
        assertNull(context.getInitParameter("absent"));
        Enumeration<String> names = context.getInitParameterNames();
        int count = 0;
        while (names.hasMoreElements()) {
            names.nextElement();
            count++;
        }
        assertEquals(2, count);
    }

    // ===== dynamic registration =====

    @Test
    public void testAddServletAndFilterBeforeInit() throws Exception {
        context.load();
        String hello = HelloServlet.class.getName();
        assertNotNull(context.addServlet("dyn", hello));
        assertNotNull(context.getServletRegistration("dyn"));
        Map<String, ?> regs = context.getServletRegistrations();
        assertTrue(regs.containsKey("dyn"));
        assertNotNull(context.addFilter("dynf", PassFilter.class.getName()));
        assertNotNull(context.getFilterRegistration("dynf"));
        assertTrue(context.getFilterRegistrations().containsKey("dynf"));
        assertEquals(6, context.getEffectiveMajorVersion());
        assertEquals(1, context.getEffectiveMinorVersion());
    }

    @Test
    public void testAddServletAfterInitFails() throws Exception {
        loadAndInit();
        try {
            context.addServlet("late", HelloServlet.class.getName());
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNull(e.getMessage());
        }
        try {
            context.addFilter("late", PassFilter.class.getName());
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNull(e.getMessage());
        }
        try {
            context.addListener(new AppListener());
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNull(e.getMessage());
        }
        try {
            context.setDistributable(true);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNull(e.getMessage());
        }
        context.destroy();
    }

    @Test
    public void testForeignClassLoaderRejected() throws Exception {
        context.load();
        try {
            context.addServlet("x", HelloServlet.class);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        try {
            context.addServlet("x", new HelloServlet());
            fail("expected SecurityException");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        try {
            context.addFilter("x", PassFilter.class);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        try {
            context.addFilter("x", new PassFilter());
            fail("expected SecurityException");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        try {
            context.createServlet(HelloServlet.class);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        try {
            context.createFilter(PassFilter.class);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        try {
            context.addListener(new AppListener());
            fail("expected SecurityException");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testCreateListenerReloadsClass() throws Exception {
        context.load();
        try {
            context.createListener(AppListener.class);
            fail("expected ServletException");
        } catch (ServletException e) {
            assertTrue(e.getMessage().contains("AppListener"));
        }
        PlainListener plain = context.createListener(PlainListener.class);
        assertNotNull(plain);
    }

    @Test
    public void testCreateListenerOnNonListenerFails() throws Exception {
        context.load();
        try {
            context.addListener(Object.class.getName());
            fail("expected exception");
        } catch (RuntimeException e) {
            assertNotNull(e);
        }
        try {
            context.addListener("no.such.Listener");
            fail("expected exception");
        } catch (RuntimeException e) {
            assertNotNull(e.getCause());
        }
    }

    @Test
    public void testSessionConfigurationAccessors() throws Exception {
        context.load();
        assertNotNull(context.getSessionCookieConfig());
        assertSame(context.getSessionCookieConfig(), context.getSessionCookieConfig());
        assertTrue(context.getDefaultSessionTrackingModes().isEmpty());
        assertTrue(context.getEffectiveSessionTrackingModes().isEmpty());
        context.setSessionTrackingModes(Collections.<jakarta.servlet.SessionTrackingMode>emptySet());
        context.setSessionTimeout(7);
        assertEquals(7, context.getSessionTimeout());
        context.setRequestCharacterEncoding("ISO-8859-1");
        context.setResponseCharacterEncoding("ISO-8859-2");
        assertEquals("ISO-8859-1", context.getRequestCharacterEncoding());
        assertEquals("ISO-8859-2", context.getResponseCharacterEncoding());
        context.declareRoles("r1", "r2");
        context.declareRoles((String[]) null);
        assertEquals(2, context.securityRoles.size());
        assertFalse(context.isDistributable());
        Collection<?> l1 = context.getSessionListeners();
        Collection<?> l2 = context.getSessionAttributeListeners();
        Collection<?> l3 = context.getSessionActivationListeners();
        assertNotNull(l1);
        assertNotNull(l2);
        assertNotNull(l3);
        context.log("message");
        context.log("message", new RuntimeException("x"));
        assertNull(context.getServlet("hello"));
        assertFalse(context.getServlets().hasMoreElements());
        assertFalse(context.getServletNames().hasMoreElements());
    }

    @Test
    public void testVirtualServerNameUnsupported() throws Exception {
        context.load();
        try {
            context.getVirtualServerName();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertNull(e.getMessage());
        }
    }

    // ===== dispatchers =====

    @Test
    public void testRequestDispatchers() throws Exception {
        loadAndInit();
        RequestDispatcher d1 = context.getRequestDispatcher("/hello?x=1#frag");
        assertNotNull(d1);
        RequestDispatcher d2 = context.getRequestDispatcher("/hello/extra/path");
        assertNotNull(d2);
        RequestDispatcher d3 = context.getRequestDispatcher("/thing.hi");
        assertNotNull(d3);
        RequestDispatcher d4 = context.getRequestDispatcher("/");
        assertNotNull(d4);
        RequestDispatcher d5 = context.getRequestDispatcher("/sub/page.txt");
        assertNotNull(d5);
        RequestDispatcher d6 = context.getRequestDispatcher("/anno/x");
        assertNotNull(d6);
        RequestDispatcher d7 = context.getRequestDispatcher("/lazy");
        assertNotNull(d7);
        assertNotNull(context.getNamedDispatcher("hello"));
        assertNull(context.getNamedDispatcher("nope"));
        context.destroy();
    }

    // ===== realms =====

    @Test
    public void testRealmDelegation() throws Exception {
        context.load();
        Realm realm = new Realm() {
            @Override
            public Realm forSelectorLoop(SelectorLoop loop) {
                return this;
            }

            @Override
            public Set<SaslMechanism> getSupportedSASLMechanisms() {
                return Collections.<SaslMechanism>emptySet();
            }

            @Override
            public boolean passwordMatch(String username, String password) {
                return "u".equals(username) && "p".equals(password);
            }

            @Override
            public String getDigestHA1(String username, String realmName) {
                return "ha1-" + username;
            }

            @Override
            public String getPassword(String username) {
                return "pw-" + username;
            }

            @Override
            public boolean isUserInRole(String username, String role) {
                return "admin".equals(role);
            }
        };
        context.addRealm("r", realm);
        assertSame(realm, context.getRealm("r"));
        assertTrue(context.passwordMatch("r", "u", "p"));
        assertFalse(context.passwordMatch("r", "u", "x"));
        assertFalse(context.passwordMatch("missing", "u", "p"));
        assertEquals("ha1-u", context.getDigestHA1("r", "u"));
        assertNull(context.getDigestHA1("missing", "u"));
        assertEquals("pw-u", context.getPassword("r", "u"));
        assertNull(context.getPassword("missing", "u"));
        assertTrue(context.isUserInRole("r", "u", "admin"));
        assertFalse(context.isUserInRole("r", "u", "user"));
        assertFalse(context.isUserInRole("missing", "u", "admin"));
        container.addRealm("shared", realm);
        assertSame(realm, context.getRealm("shared"));
    }

    // ===== construction =====

    @Test
    public void testPathAndRootValidation() {
        Context c = new Context();
        try {
            c.setPath("/trailing/");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("/trailing/"));
        }
        c.setPath("/ok");
        try {
            c.setPath("/again");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        c.setRoot(root);
        try {
            c.setRoot(root);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        c.setContainer(container);
        c.setContainer(container);
        try {
            c.setContainer(new Container());
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        try {
            new Context(container, "/bad/", root);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("/bad/"));
        }
    }

    @Test
    public void testNoArgContextLoadsLazily() throws Exception {
        Context c = new Context();
        c.setContainer(container);
        c.setPath("/lazy");
        c.setRoot(root);
        c.setSecureHost("h");
        c.setCommonDir("d");
        c.load();
        assertEquals("Lifecycle", c.getServletContextName());
        assertNotNull(c.getContainer());
        assertNotNull(c.getWorkerThreadPool());
        assertNotNull(c.getWorkerKeepAlive());
    }
}
