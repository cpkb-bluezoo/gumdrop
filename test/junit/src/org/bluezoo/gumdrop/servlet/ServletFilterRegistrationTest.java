/*
 * ServletFilterRegistrationTest.java
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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.HttpMethodConstraintElement;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.HttpMethodConstraint;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the programmatic registration API implemented by {@link ServletDef}
 * and {@link FilterDef}: init parameters, URL pattern mappings, security
 * elements, multipart configuration and instantiation failure handling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletFilterRegistrationTest {

    /** Annotated servlet whose annotations feed the init methods. */
    @WebServlet(name = "annotated", description = "d", displayName = "dn",
            smallIcon = "s.png", largeIcon = "l.png", loadOnStartup = 4, asyncSupported = true,
            urlPatterns = { "/a" },
            initParams = { @WebInitParam(name = "k", value = "v") })
    @MultipartConfig(location = "/tmp", maxFileSize = 10L, maxRequestSize = 20L, fileSizeThreshold = 5)
    @ServletSecurity(value = @HttpConstraint(rolesAllowed = { "admin" },
            transportGuarantee = ServletSecurity.TransportGuarantee.CONFIDENTIAL),
            httpMethodConstraints = {
                @HttpMethodConstraint(value = "GET", emptyRoleSemantic = ServletSecurity.EmptyRoleSemantic.DENY),
                @HttpMethodConstraint(value = "POST", rolesAllowed = { "poster" }) })
    @jakarta.annotation.security.RunAs("boss")
    public static class AnnotatedServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
    }

    /** Annotated filter. */
    @WebFilter(filterName = "af", description = "d", displayName = "dn", smallIcon = "s", largeIcon = "l",
            asyncSupported = true, urlPatterns = { "/f" },
            initParams = { @WebInitParam(name = "fk", value = "fv") })
    public static class AnnotatedFilter implements jakarta.servlet.Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            chain.doFilter(request, response);
        }
    }

    /** Servlet that is permanently unavailable. */
    public static class PermanentlyUnavailable extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        public void init(ServletConfig config) throws ServletException {
            throw new UnavailableException("never");
        }
    }

    /** Servlet that is temporarily unavailable. */
    public static class TemporarilyUnavailable extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        public void init(ServletConfig config) throws ServletException {
            throw new UnavailableException("later", 30);
        }
    }

    /** Servlet whose init fails. */
    public static class Failing extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        public void init(ServletConfig config) throws ServletException {
            throw new IllegalStateException("broken");
        }
    }

    /** Filter that is temporarily unavailable. */
    public static class UnavailableFilter implements jakarta.servlet.Filter {
        @Override
        public void init(jakarta.servlet.FilterConfig filterConfig) throws ServletException {
            throw new UnavailableException("later", 7);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        }
    }

    /** Filter that is permanently unavailable. */
    public static class PermanentFilter extends UnavailableFilter {
        @Override
        public void init(jakarta.servlet.FilterConfig filterConfig) throws ServletException {
            throw new UnavailableException("never");
        }
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context context;

    @Before
    public void setUp() throws Exception {
        context = new Context(new Container(), "/reg", tmp.newFolder("reg"));
    }

    private ServletDef servlet(String name, Class<?> type) {
        return (ServletDef) context.addServlet(name, type.getName());
    }

    private FilterDef filter(String name, Class<?> type) {
        return (FilterDef) context.addFilter(name, type.getName());
    }

    // ===== ServletDef =====

    @Test
    public void testServletDefFromAnnotations() {
        ServletDef sd = new ServletDef();
        WebServlet ws = AnnotatedServlet.class.getAnnotation(WebServlet.class);
        sd.init(ws, AnnotatedServlet.class.getName());
        assertEquals("annotated", sd.getName());
        assertEquals(AnnotatedServlet.class.getName(), sd.getClassName());
        assertEquals("d", sd.getDescription());
        assertEquals("dn", sd.getDisplayName());
        assertEquals("s.png", sd.getSmallIcon());
        assertEquals("l.png", sd.getLargeIcon());
        assertEquals(4, sd.loadOnStartup);
        assertTrue(sd.asyncSupported);
        assertEquals("v", sd.getInitParameter("k"));
        sd.init(AnnotatedServlet.class.getAnnotation(MultipartConfig.class));
        assertEquals(10L, sd.multipartConfig.maxFileSize);
        assertEquals(20L, sd.multipartConfig.maxRequestSize);
        assertEquals(5L, sd.multipartConfig.fileSizeThreshold);
        sd.init(AnnotatedServlet.class.getAnnotation(ServletSecurity.class));
        assertEquals(3, sd.servletSecurity.size());
        sd.init(AnnotatedServlet.class.getAnnotation(jakarta.annotation.security.RunAs.class));
        assertEquals("boss", sd.getRunAsRole());
    }

    @Test
    public void testServletDefSettersAndGetters() {
        ServletDef sd = servlet("s1", PermanentlyUnavailable.class);
        sd.setDescription("desc");
        sd.setDisplayName("disp");
        sd.setSmallIcon("si");
        sd.setLargeIcon("li");
        assertEquals("desc", sd.getDescription());
        assertEquals("disp", sd.getDisplayName());
        assertEquals("si", sd.getSmallIcon());
        assertEquals("li", sd.getLargeIcon());
        assertSame(context, sd.getServletContext());
        assertEquals("s1", sd.getServletName());
        sd.setLoadOnStartup(7);
        assertEquals(7, sd.loadOnStartup);
        sd.setRunAsRole("r");
        assertEquals("r", sd.getRunAsRole());
        sd.setAsyncSupported(true);
        assertTrue(sd.asyncSupported);
        assertTrue(sd.toString().contains("s1"));
        ServletDef other = new ServletDef();
        other.loadOnStartup = 9;
        assertTrue(sd.compareTo(other) < 0);
        assertTrue(other.compareTo(sd) > 0);
    }

    @Test
    public void testServletInitParameters() {
        ServletDef sd = servlet("s2", PermanentlyUnavailable.class);
        assertTrue(sd.setInitParameter("a", "1"));
        assertFalse(sd.setInitParameter("a", "2"));
        assertEquals("1", sd.getInitParameter("a"));
        assertNull(sd.getInitParameter("zz"));
        Map<String, String> more = new HashMap<String, String>();
        more.put("a", "x");
        more.put("b", "y");
        Set<String> conflicts = sd.setInitParameters(more);
        assertEquals(Collections.singleton("a"), conflicts);
        Map<String, String> all = sd.getInitParameters();
        assertEquals(2, all.size());
        Enumeration<String> names = sd.getInitParameterNames();
        int n = 0;
        while (names.hasMoreElements()) {
            names.nextElement();
            n++;
        }
        assertEquals(2, n);
        try {
            sd.setInitParameter(null, "v");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNull(e.getMessage());
        }
        try {
            sd.setInitParameter("k", null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNull(e.getMessage());
        }
    }

    @Test
    public void testServletMappings() {
        ServletDef a = servlet("a", PermanentlyUnavailable.class);
        ServletDef b = servlet("b", PermanentlyUnavailable.class);
        Set<String> conflicts = a.addMapping("/x", "/y");
        assertTrue(conflicts.isEmpty());
        Set<String> second = b.addMapping("/x", "/z");
        assertEquals(Collections.singleton("/x"), second);
        Collection<String> mappings = a.getMappings();
        assertTrue(mappings.contains("/x"));
        assertTrue(mappings.contains("/y"));
        assertTrue(b.getMappings().isEmpty());
        try {
            a.addMapping();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNull(e.getMessage());
        }
        try {
            a.addMapping((String[]) null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNull(e.getMessage());
        }
    }

    @Test
    public void testSetServletSecurityElement() {
        ServletDef sd = servlet("sec", PermanentlyUnavailable.class);
        sd.addMapping("/secure/*");
        HttpMethodConstraintElement post = new HttpMethodConstraintElement("POST",
                new HttpConstraintElement(ServletSecurity.TransportGuarantee.CONFIDENTIAL, "admin"));
        ServletSecurityElement element = new ServletSecurityElement(
                new HttpConstraintElement(ServletSecurity.EmptyRoleSemantic.DENY),
                Collections.singleton(post));
        Set<String> already = sd.setServletSecurity(element);
        assertTrue(already.isEmpty());
        assertEquals(2, sd.servletSecurity.size());
    }

    @Test
    public void testSetMultipartConfigElement() {
        ServletDef sd = servlet("mp", PermanentlyUnavailable.class);
        sd.setMultipartConfig(new MultipartConfigElement("/loc", 1L, 2L, 3));
        assertEquals("/loc", sd.multipartConfig.location);
        assertEquals(1L, sd.multipartConfig.maxFileSize);
        assertEquals(2L, sd.multipartConfig.maxRequestSize);
        assertEquals(3L, sd.multipartConfig.fileSizeThreshold);
        sd.setMultipartConfig(new MultipartConfigElement("/loc2"));
        assertEquals("/loc2", sd.multipartConfig.location);
    }

    @Test
    public void testServletNewInstanceFailures() throws Exception {
        context.load();
        ServletDef perm = servlet("perm", PermanentlyUnavailable.class);
        try {
            perm.newInstance();
            fail("expected UnavailableException");
        } catch (UnavailableException e) {
            assertTrue(e.isPermanent());
        }
        assertEquals(-1L, perm.unavailableUntil);
        ServletDef temp = servlet("temp", TemporarilyUnavailable.class);
        try {
            temp.newInstance();
            fail("expected UnavailableException");
        } catch (UnavailableException e) {
            assertFalse(e.isPermanent());
        }
        assertTrue(temp.unavailableUntil > 0L);
        ServletDef fail = servlet("fail", Failing.class);
        try {
            fail.newInstance();
            fail("expected ServletException");
        } catch (ServletException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
        ServletDef missing = new ServletDef();
        missing.context = context;
        missing.name = "missing";
        missing.className = "no.such.Servlet";
        try {
            missing.newInstance();
            fail("expected ServletException");
        } catch (ServletException e) {
            assertTrue(e.getCause() instanceof ClassNotFoundException);
        }
    }

    @Test
    public void testServletNewInstanceSucceeds() throws Exception {
        context.load();
        ServletDef ok = servlet("ok", ContextLifecycleTest.HelloServlet.class);
        assertNotNull(ok.newInstance());
    }

    // ===== FilterDef =====

    @Test
    public void testFilterDefFromAnnotation() {
        FilterDef fd = new FilterDef();
        fd.init(AnnotatedFilter.class.getAnnotation(WebFilter.class), AnnotatedFilter.class.getName());
        assertEquals("af", fd.getName());
        assertEquals("af", fd.getFilterName());
        assertEquals(AnnotatedFilter.class.getName(), fd.getClassName());
        assertEquals("d", fd.getDescription());
        assertEquals("dn", fd.getDisplayName());
        assertEquals("s", fd.getSmallIcon());
        assertEquals("l", fd.getLargeIcon());
        assertTrue(fd.asyncSupported);
        assertEquals("fv", fd.getInitParameter("fk"));
        assertTrue(fd.toString().contains("af"));
    }

    @Test
    public void testFilterSettersAndInitParameters() {
        FilterDef fd = filter("f1", AnnotatedFilter.class);
        fd.setDescription("a");
        fd.setDisplayName("b");
        fd.setSmallIcon("c");
        fd.setLargeIcon("d");
        assertEquals("a", fd.getDescription());
        assertEquals("b", fd.getDisplayName());
        assertEquals("c", fd.getSmallIcon());
        assertEquals("d", fd.getLargeIcon());
        assertSame(context, fd.getServletContext());
        fd.setAsyncSupported(true);
        assertTrue(fd.asyncSupported);
        assertTrue(fd.setInitParameter("p", "1"));
        assertFalse(fd.setInitParameter("p", "2"));
        Map<String, String> more = new HashMap<String, String>();
        more.put("p", "x");
        more.put("q", "y");
        assertEquals(Collections.singleton("p"), fd.setInitParameters(more));
        assertEquals(2, fd.getInitParameters().size());
        Enumeration<String> names = fd.getInitParameterNames();
        assertTrue(names.hasMoreElements());
        try {
            fd.setInitParameter(null, "v");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNull(e.getMessage());
        }
    }

    @Test
    public void testFilterMappings() {
        FilterDef f1 = filter("f1", AnnotatedFilter.class);
        FilterDef f2 = filter("f2", AnnotatedFilter.class);
        EnumSet<DispatcherType> types = EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD);
        f1.addMappingForUrlPatterns(types, false, "/a/*", "/b/*");
        f2.addMappingForUrlPatterns(types, false, "/a/*");
        f1.addMappingForServletNames(types, false, "svc1");
        f2.addMappingForServletNames(types, false, "svc1", "svc2");
        f2.addMappingForServletNames(types, false, "other");
        assertTrue(f1.getUrlPatternMappings().contains("/b/*"));
        assertTrue(f2.getUrlPatternMappings().contains("/a/*"));
        assertTrue(f1.getServletNameMappings().contains("svc1"));
        assertEquals(Arrays.asList("svc1", "svc2", "other"),
                new java.util.ArrayList<String>(f2.getServletNameMappings()));
        // the later mapping for a shared pattern is inserted ahead of the earlier one
        assertSame(f2, context.filterMappings.get(0).filterDef);
        try {
            f1.addMappingForUrlPatterns(types, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNull(e.getMessage());
        }
        try {
            f1.addMappingForServletNames(types, false, (String[]) null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNull(e.getMessage());
        }
    }

    @Test
    public void testFilterMappingsMatchAfter() {
        FilterDef f1 = filter("f1", AnnotatedFilter.class);
        FilterDef f2 = filter("f2", AnnotatedFilter.class);
        FilterDef f3 = filter("f3", AnnotatedFilter.class);
        EnumSet<DispatcherType> types = EnumSet.of(DispatcherType.REQUEST);
        f1.addMappingForUrlPatterns(types, false, "/a/*");
        f2.addMappingForUrlPatterns(types, true, "/a/*");
        f1.addMappingForServletNames(types, false, "svc");
        f3.addMappingForServletNames(types, true, "svc");
        assertSame(f1, context.filterMappings.get(0).filterDef);
        assertSame(f2, context.filterMappings.get(1).filterDef);
        assertSame(f1, context.filterMappings.get(2).filterDef);
        assertSame(f3, context.filterMappings.get(3).filterDef);
    }

    @Test
    public void testFilterNewInstanceFailures() throws Exception {
        context.load();
        FilterDef temp = filter("temp", UnavailableFilter.class);
        try {
            temp.newInstance();
            fail("expected UnavailableException");
        } catch (UnavailableException e) {
            assertFalse(e.isPermanent());
        }
        assertTrue(temp.unavailableUntil > 0L);
        FilterDef perm = filter("perm", PermanentFilter.class);
        try {
            perm.newInstance();
            fail("expected UnavailableException");
        } catch (UnavailableException e) {
            assertTrue(e.isPermanent());
        }
        assertEquals(-1L, perm.unavailableUntil);
        FilterDef missing = new FilterDef();
        missing.context = context;
        missing.name = "missing";
        missing.className = "no.such.Filter";
        try {
            missing.newInstance();
            fail("expected ServletException");
        } catch (ServletException e) {
            assertTrue(e.getCause() instanceof ClassNotFoundException);
        }
        FilterDef ok = filter("ok", AnnotatedFilter.class);
        assertNotNull(ok.newInstance());
    }
}
