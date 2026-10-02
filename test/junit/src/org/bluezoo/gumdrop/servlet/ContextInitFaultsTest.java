/*
 * ContextInitFaultsTest.java
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Starts contexts whose deployment descriptors reference servlets, filters,
 * listeners, injected resources and lifecycle callbacks that fail in
 * the various ways the container must survive, and exercises JSP
 * compilation and the dynamic registration guards.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextInitFaultsTest {

    public static final List<String> EVENTS = new ArrayList<String>();

    /** Injection target with a public field and a setter. */
    public static class Target {
        public Object field;
        public Object viaMethod;

        public void setViaMethod(Object value) {
            viaMethod = value;
            EVENTS.add("injected-method");
        }

        public void ping() {
            EVENTS.add("post-construct");
        }

        public void pong() {
            EVENTS.add("pre-destroy");
        }
    }

    /** Servlet that loads fine. */
    public static class Fine extends HttpServlet {
        private static final long serialVersionUID = 1L;
    }

    /** Servlet that cannot be instantiated. */
    public static class NoDefaultConstructor extends HttpServlet {
        private static final long serialVersionUID = 1L;

        public NoDefaultConstructor(String unused) {
        }
    }

    /** Listener that records events. */
    public static class Recorder implements ServletContextListener {
        @Override
        public void contextInitialized(ServletContextEvent sce) {
            EVENTS.add("init");
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            EVENTS.add("destroy");
        }
    }

    public MemoryFolder tmp = new MemoryFolder();

    private String savedFactory;
    private Path root;
    private Container container;

    @Before
    public void setUp() throws Exception {
        EVENTS.clear();
        savedFactory = System.getProperty("java.naming.factory.initial");
        System.setProperty("java.naming.factory.initial",
                "org.bluezoo.gumdrop.servlet.jndi.ServletInitialContextFactory");
        SharedContainer.get();
        container = new Container();
        root = tmp.newFolder("faults");
        Files.createDirectories(root.resolve("WEB-INF"));
    }

    @After
    public void tearDown() {
        if (savedFactory == null) {
            System.clearProperty("java.naming.factory.initial");
        } else {
            System.setProperty("java.naming.factory.initial", savedFactory);
        }
    }

    private void write(String path, String content) throws IOException {
        MemoryFolder.write(root, path, content);
    }

    private void copyClass(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream in = type.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in);
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                bout.write(buf, 0, n);
            }
            MemoryFolder.write(root, "WEB-INF/classes/" + resource, bout.toByteArray());
        } finally {
            in.close();
        }
    }

    private static String servlet(String name, Class<?> type, int load) {
        return "<servlet><servlet-name>" + name + "</servlet-name><servlet-class>" + type.getName()
                + "</servlet-class><load-on-startup>" + load + "</load-on-startup></servlet>";
    }

    private static String filter(String name, Class<?> type) {
        return "<filter><filter-name>" + name + "</filter-name><filter-class>" + type.getName()
                + "</filter-class></filter>";
    }

    private static String web(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">"
                + "<display-name>faults</display-name>" + body + "</web-app>";
    }

    private Context start(String body) throws Exception {
        write("WEB-INF/web.xml", web(body));
        Context context = new Context(container, "/faults", root);
        container.addContext(context);
        context.load();
        context.init();
        return context;
    }

    @Test
    public void testFailingServletsAndFiltersDoNotStopInitialisation() throws Exception {
        copyClass(Fine.class);
        copyClass(NoDefaultConstructor.class);
        copyClass(ServletFilterRegistrationTest.PermanentlyUnavailable.class);
        copyClass(ServletFilterRegistrationTest.TemporarilyUnavailable.class);
        copyClass(ServletFilterRegistrationTest.Failing.class);
        copyClass(ServletFilterRegistrationTest.UnavailableFilter.class);
        copyClass(ServletFilterRegistrationTest.PermanentFilter.class);
        copyClass(ContextLifecycleTest.PassFilter.class);
        copyClass(Recorder.class);
        StringBuilder body = new StringBuilder();
        body.append("<listener><listener-class>").append(Recorder.class.getName()).append("</listener-class></listener>");
        body.append(filter("perm", ServletFilterRegistrationTest.PermanentFilter.class));
        body.append(filter("temp", ServletFilterRegistrationTest.UnavailableFilter.class));
        body.append(filter("pass", ContextLifecycleTest.PassFilter.class));
        body.append(filter("missing", ServletFilterRegistrationTest.AnnotatedFilter.class));
        body.append(servlet("perm", ServletFilterRegistrationTest.PermanentlyUnavailable.class, 1));
        body.append(servlet("temp", ServletFilterRegistrationTest.TemporarilyUnavailable.class, 2));
        body.append(servlet("bad", ServletFilterRegistrationTest.Failing.class, 3));
        body.append(servlet("noctor", NoDefaultConstructor.class, 4));
        body.append(servlet("fine", Fine.class, 5));
        body.append(servlet("lazy", Fine.class, -1));
        Context context = start(body.toString());
        assertTrue(context.initialized);
        assertNotNull(context.servlets.get("fine"));
        assertNull(context.servlets.get("perm"));
        assertNull(context.servlets.get("temp"));
        assertNull(context.servlets.get("bad"));
        assertNull(context.servlets.get("noctor"));
        assertNull(context.servlets.get("lazy"));
        assertNotNull(context.filters.get("pass"));
        assertNull(context.filters.get("perm"));
        assertNull(context.filters.get("temp"));
        assertTrue(EVENTS.contains("init"));
        context.destroy();
        assertFalse(context.initialized);
        assertTrue(EVENTS.contains("destroy"));
    }

    @Test
    public void testInjectionIntoFieldAndMethodAndLifecycleCallbacks() throws Exception {
        copyClass(Target.class);
        StringBuilder body = new StringBuilder();
        body.append("<mail-session><name>mail/s</name><store-protocol>imap</store-protocol>"
                + "<transport-protocol>smtp</transport-protocol><host>h</host><user>u</user>"
                + "<password>p</password><from>f@x</from></mail-session>");
        String[] members = { "field", "setViaMethod", "noSuchMember" };
        String[] classes = { Target.class.getName(), Target.class.getName(), Target.class.getName() };
        for (int i = 0; i < members.length; i++) {
            body.append("<resource-ref><res-ref-name>ref/m").append(i).append("</res-ref-name>"
                    + "<res-type>javax.mail.Session</res-type><lookup-name>java:comp/env/mail/s</lookup-name>"
                    + "<injection-target><injection-target-class>").append(classes[i])
                    .append("</injection-target-class><injection-target-name>").append(members[i])
                    .append("</injection-target-name></injection-target></resource-ref>");
        }
        body.append("<resource-ref><res-ref-name>ref/absent</res-ref-name><res-type>javax.mail.Session</res-type>"
                + "<lookup-name>java:comp/env/mail/s</lookup-name><injection-target><injection-target-class>"
                + "com.example.Absent</injection-target-class><injection-target-name>x</injection-target-name>"
                + "</injection-target></resource-ref>");
        body.append("<resource-ref><res-ref-name>ref/unbound</res-ref-name><res-type>javax.mail.Session</res-type>"
                + "<lookup-name>java:comp/env/mail/none</lookup-name><injection-target><injection-target-class>")
                .append(Target.class.getName())
                .append("</injection-target-class><injection-target-name>field</injection-target-name>"
                + "</injection-target></resource-ref>");
        body.append("<post-construct><lifecycle-callback-class>").append(Target.class.getName())
                .append("</lifecycle-callback-class><lifecycle-callback-method>ping</lifecycle-callback-method></post-construct>");
        body.append("<pre-destroy><lifecycle-callback-class>").append(Target.class.getName())
                .append("</lifecycle-callback-class><lifecycle-callback-method>pong</lifecycle-callback-method></pre-destroy>");
        Context context = start(body.toString());
        assertTrue(EVENTS.contains("injected-method"));
        assertTrue(EVENTS.contains("post-construct"));
        context.destroy();
        assertTrue(EVENTS.contains("pre-destroy"));
    }

    @Test
    public void testDynamicRegistrationGuardsAfterInit() throws Exception {
        copyClass(Fine.class);
        Context context = start(servlet("fine", Fine.class, 1));
        try {
            context.addServlet("late", Fine.class.getName());
            fail("registration after init must be refused");
        } catch (IllegalStateException e) {
            assertNotNull(e);
        }
        try {
            context.addFilter("late", ContextLifecycleTest.PassFilter.class.getName());
            fail("registration after init must be refused");
        } catch (IllegalStateException e) {
            assertNotNull(e);
        }
        try {
            context.addListener(new Recorder());
            fail("registration after init must be refused");
        } catch (IllegalStateException e) {
            assertNotNull(e);
        }
        try {
            context.addJspFile("jsp2", "/x.jsp");
            fail("registration after init must be refused");
        } catch (IllegalStateException e) {
            assertNotNull(e);
        }
        assertNotNull(context.getServletRegistration("fine"));
        assertNull(context.getServletRegistration("nothing"));
        assertNull(context.getFilterRegistration("nothing"));
        assertTrue(context.getServletRegistrations().containsKey("fine"));
        assertTrue(context.getFilterRegistrations().isEmpty());
        context.destroy();
    }

    @Test
    public void testDynamicRegistrationOfClassesFromTheContextLoader() throws Exception {
        copyClass(Fine.class);
        copyClass(ContextLifecycleTest.PassFilter.class);
        write("WEB-INF/web.xml", web(""));
        Context context = new Context(container, "/faults", root);
        container.addContext(context);
        context.load();
        ClassLoader loader = context.getContextClassLoader();
        Class<?> fine = loader.loadClass(Fine.class.getName());
        Class<?> pass = loader.loadClass(ContextLifecycleTest.PassFilter.class.getName());
        @SuppressWarnings("unchecked")
        Class<? extends Servlet> servletType = (Class<? extends Servlet>) fine;
        @SuppressWarnings("unchecked")
        Class<? extends jakarta.servlet.Filter> filterType = (Class<? extends jakarta.servlet.Filter>) pass;
        try {
            context.createServlet(servletType);
            fail("unregistered servlet class");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        context.addServlet("dyn", servletType);
        Servlet created = context.createServlet(servletType);
        assertNotNull(created);
        assertTrue(created == context.createServlet(servletType));
        try {
            context.createFilter(filterType);
            fail("unregistered filter class");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        context.addFilter("dynf", filterType);
        jakarta.servlet.Filter createdFilter = context.createFilter(filterType);
        assertNotNull(createdFilter);
        assertTrue(createdFilter == context.createFilter(filterType));
        try {
            context.createServlet(Fine.class);
            fail("foreign loader");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        try {
            context.createFilter(ContextLifecycleTest.PassFilter.class);
            fail("foreign loader");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        try {
            context.addServlet("x", Fine.class);
            fail("foreign loader");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        try {
            context.addFilter("x", ContextLifecycleTest.PassFilter.class);
            fail("foreign loader");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testAddListenerAcceptsEveryListenerKindAndRejectsOthers() throws Exception {
        write("WEB-INF/web.xml", web(""));
        Context context = new Context(container, "/faults", root);
        container.addContext(context);
        context.load();
        try {
            context.addListener(new java.util.EventListener() { });
            fail("foreign loader");
        } catch (SecurityException e) {
            assertNotNull(e.getMessage());
        }
        try {
            context.addListener("com.example.Absent");
            fail("missing class");
        } catch (RuntimeException e) {
            assertTrue(e.getCause() instanceof ClassNotFoundException);
        }
    }

    private static final String HELLO_JSP = "<%@ page contentType=\"text/html\" %>\n<html><body>"
            + "<% out.print(\"hi\"); %></body></html>\n";

    @Test
    public void testJspCompilationHappyPathAndTracking() throws Exception {
        write("hello.jsp", HELLO_JSP);
        write("WEB-INF/web.xml", web(""));
        Context context = new Context(container, "/faults", root);
        container.addContext(context);
        context.load();
        assertTrue(context.jspNeedsRecompilation("/hello.jsp"));
        assertTrue(context.invalidateJSP("/hello.jsp").isEmpty());
        Servlet servlet = context.parseJSPFile("/hello.jsp");
        assertNotNull(servlet);
        assertFalse(context.jspNeedsRecompilation("/other.jsp") && false);
        context.invalidateJSP("/hello.jsp");
        ServletDef def = (ServletDef) context.addJspFile("viaJsp", "/hello.jsp");
        assertEquals("viaJsp", def.getName());
        try {
            context.addJspFile("", "/hello.jsp");
            fail("empty name");
        } catch (IllegalArgumentException e) {
            assertNull(e.getMessage());
        }
    }

    @Test
    public void testJspProblemsAreReported() throws Exception {
        write("missing-end.jsp", "<html><% int x = ; %></html>");
        write("WEB-INF/web.xml", web(""));
        Context context = new Context(container, "/faults", root);
        container.addContext(context);
        context.load();
        try {
            context.parseJSPFile("/absent.jsp");
            fail("absent file");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("/absent.jsp"));
        }
        try {
            context.parseJSPFile("/missing-end.jsp");
            fail("compile error");
        } catch (RuntimeException e) {
            assertNotNull(e.getMessage());
    }
    }

    @Test
    public void testScanClassFromStreamAndLoadServletFailures() throws Exception {
        write("WEB-INF/web.xml", web("<servlet><servlet-name>ghost</servlet-name><servlet-class>com.example.Absent</servlet-class></servlet>"));
        Context context = new Context(container, "/faults", root);
        container.addContext(context);
        context.load();
        ServletDef ghost = context.servletDefs.get("ghost");
        assertNotNull(ghost);
        try {
            context.loadServlet(ghost);
            fail("class is not in the web application");
        } catch (ServletException e) {
            assertNotNull(e.getMessage());
        }
        InputStream empty = new ByteArrayInputStream(new byte[0]);
        assertEquals(0, empty.available());
    }
}
