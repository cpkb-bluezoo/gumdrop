/*
 * ContextScanClassTest.java
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

import java.io.IOException;
import java.io.InputStream;
import java.util.EventListener;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resources;
import javax.ejb.EJB;
import javax.ejb.EJBs;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContexts;
import javax.persistence.PersistenceUnit;
import javax.persistence.PersistenceUnits;
import javax.xml.ws.WebServiceRef;
import javax.xml.ws.WebServiceRefs;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives {@link Context#scanClass} with annotated fixture classes: the
 * servlet, filter, listener, security, resource-injection and lifecycle
 * annotations, the misplaced-annotation rejections and a missing class.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextScanClassTest {

    /** Filter annotation on something that is not a filter. */
    @WebFilter(filterName = "bad", urlPatterns = { "/x" })
    public static class NotAFilter {
    }

    /** Listener annotation on something that is not a listener. */
    @WebListener
    public static class NotAListener {
    }

    /** Servlet annotation on something that is not a servlet. */
    @WebServlet(name = "bad", urlPatterns = { "/x" })
    public static class NotAServlet {
    }

    /** Multipart annotation on something that is not a servlet. */
    @MultipartConfig
    public static class NotAMultipartServlet {
    }

    /** Security annotation on something that is not a servlet. */
    @ServletSecurity
    public static class NotASecuredServlet {
    }

    /** Filter with url patterns, servlet names and dispatcher types. */
    @WebFilter(filterName = "both", urlPatterns = { "/a", "/b" }, servletNames = { "s1", "s2" },
            dispatcherTypes = { DispatcherType.REQUEST, DispatcherType.ERROR })
    public static class BothFilter implements jakarta.servlet.Filter {
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                jakarta.servlet.ServletResponse response, jakarta.servlet.FilterChain chain) {
        }
    }

    /** Listener. */
    @WebListener
    public static class Listener implements EventListener {
    }

    /** Servlet with mapping first, then the other servlet annotations. */
    @WebServlet(name = "full", urlPatterns = { "/full" })
    @MultipartConfig(maxFileSize = 5L)
    @ServletSecurity
    @javax.annotation.security.RunAs("javaxrole")
    @javax.annotation.security.DeclareRoles({ "r1", "r2" })
    public static class FullServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
    }

    /** Servlet whose first annotation is not the servlet one. */
    @javax.annotation.security.RunAs("first")
    @MultipartConfig
    @WebServlet(name = "late")
    public static class LateServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
    }

    /** Servlet configured with the jakarta annotations. */
    @ServletSecurity
    @jakarta.annotation.security.RunAs("jakartarole")
    @jakarta.annotation.security.DeclareRoles({ "j1" })
    public static class JakartaServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
    }

    /** Container annotations. */
    @EJBs({ @EJB(name = "ejb1", beanInterface = Runnable.class) })
    @PersistenceContexts({ @PersistenceContext(name = "pc1", unitName = "u") })
    @jakarta.persistence.PersistenceContexts({ @jakarta.persistence.PersistenceContext(name = "pc2", unitName = "u") })
    @PersistenceUnits({ @PersistenceUnit(name = "pu1", unitName = "u") })
    @jakarta.persistence.PersistenceUnits({ @jakarta.persistence.PersistenceUnit(name = "pu2", unitName = "u") })
    @Resources({ @javax.annotation.Resource(name = "res1", type = String.class) })
    @jakarta.annotation.Resources({ @jakarta.annotation.Resource(name = "res2", type = String.class) })
    @WebServiceRefs({ @WebServiceRef(name = "ws1") })
    public static class ContainerAnnotated {
    }

    /** Field and method annotations. */
    public static class Injected {
        @EJB(name = "fe", beanInterface = Runnable.class)
        Object ejb;
        @javax.annotation.Resource(name = "fr1", type = String.class)
        Object resource1;
        @jakarta.annotation.Resource(name = "fr2", type = String.class)
        Object resource2;
        @PersistenceContext(name = "fpc1", unitName = "u")
        Object context1;
        @jakarta.persistence.PersistenceContext(name = "fpc2", unitName = "u")
        Object context2;
        @PersistenceUnit(name = "fpu1", unitName = "u")
        Object unit1;
        @jakarta.persistence.PersistenceUnit(name = "fpu2", unitName = "u")
        Object unit2;
        @WebServiceRef(name = "fws")
        Object service;
        Object plain;

        @PostConstruct
        public void javaxStart() {
        }

        @jakarta.annotation.PostConstruct
        public void jakartaStart() {
        }

        @PreDestroy
        public void javaxStop() {
        }

        @jakarta.annotation.PreDestroy
        public void jakartaStop() {
        }

        public void plainMethod() {
        }
    }

    /** Same refs again so that the existing entries are updated. */
    public static class InjectedAgain {
        @EJB(name = "fe", beanInterface = Runnable.class)
        Object ejb;
        @javax.annotation.Resource(name = "fr1", type = String.class)
        Object resource1;
        @jakarta.annotation.Resource(name = "fr2", type = String.class)
        Object resource2;
        @PersistenceContext(name = "fpc1", unitName = "u")
        Object context1;
        @jakarta.persistence.PersistenceContext(name = "fpc2", unitName = "u")
        Object context2;
        @PersistenceUnit(name = "fpu1", unitName = "u")
        Object unit1;
        @jakarta.persistence.PersistenceUnit(name = "fpu2", unitName = "u")
        Object unit2;
        @WebServiceRef(name = "fws")
        Object service;
    }

    public MemoryFolder tmp = new MemoryFolder();

    private Context context;
    private WebFragment descriptor;

    @Before
    public void setUp() throws Exception {
        context = new Context(new Container(), "/scan", tmp.newFolder("scan"));
        descriptor = new WebFragment();
    }

    private void scan(Class<?> type) throws Exception {
        String name = type.getName();
        String resource = name.replace('.', '/') + ".class";
        InputStream in = type.getClassLoader().getResourceAsStream(resource);
        assertNotNull(resource, in);
        try {
            context.scanClass(descriptor, name, in);
        } finally {
            in.close();
        }
    }

    @Test
    public void testMisplacedAnnotationsAreRejected() throws Exception {
        scan(NotAFilter.class);
        scan(NotAListener.class);
        scan(NotAServlet.class);
        scan(NotAMultipartServlet.class);
        scan(NotASecuredServlet.class);
        assertTrue(descriptor.filterDefs.isEmpty());
        assertTrue(descriptor.listenerDefs.isEmpty());
        assertTrue(descriptor.servletDefs.isEmpty());
        assertEquals(5, context.scannedApplicationClasses.size());
    }

    @Test
    public void testFilterMappingsFromAnnotation() throws Exception {
        scan(BothFilter.class);
        assertEquals(1, descriptor.filterDefs.size());
        assertEquals(2, descriptor.filterMappings.size());
    }

    @Test
    public void testListenerFromAnnotation() throws Exception {
        scan(Listener.class);
        assertEquals(1, descriptor.listenerDefs.size());
    }

    @Test
    public void testServletAnnotationsAccumulateOnOneDefinition() throws Exception {
        scan(FullServlet.class);
        assertEquals(1, descriptor.servletDefs.size());
        assertEquals(1, descriptor.servletMappings.size());
        assertEquals(2, descriptor.securityRoles.size());
        ServletDef def = descriptor.servletDefs.get("full");
        assertNotNull(def);
        assertEquals(5L, def.multipartConfig.maxFileSize);
        assertEquals("javaxrole", def.getRunAsRole());
    }

    @Test
    public void testServletAnnotationOrderDoesNotMatter() throws Exception {
        scan(LateServlet.class);
        assertEquals(1, descriptor.servletDefs.size());
        assertEquals("first", descriptor.servletDefs.values().iterator().next().getRunAsRole());
    }

    @Test
    public void testJakartaSecurityAnnotations() throws Exception {
        scan(JakartaServlet.class);
        assertEquals(1, descriptor.securityRoles.size());
        assertEquals("j1", descriptor.securityRoles.get(0).roleName);
    }

    @Test
    public void testContainerAnnotationsRegisterReferences() throws Exception {
        scan(ContainerAnnotated.class);
        assertEquals(1, context.ejbRefs.size());
        assertEquals(2, context.persistenceContextRefs.size());
        assertEquals(2, context.persistenceUnitRefs.size());
        assertEquals(2, context.resourceRefs.size());
        assertEquals(1, context.serviceRefs.size());
    }

    @Test
    public void testFieldAndMethodAnnotations() throws Exception {
        scan(Injected.class);
        assertEquals(1, context.ejbRefs.size());
        assertEquals(2, context.resourceRefs.size());
        assertEquals(2, context.persistenceContextRefs.size());
        assertEquals(2, context.persistenceUnitRefs.size());
        assertEquals(1, context.serviceRefs.size());
        assertEquals(2, context.postConstructs.size());
        assertEquals(2, context.preDestroys.size());
        assertFalse(context.postConstructs.get(0).methodName.isEmpty());
    }

    @Test
    public void testExistingReferencesAreReused() throws Exception {
        scan(Injected.class);
        scan(InjectedAgain.class);
        assertEquals(1, context.ejbRefs.size());
        assertEquals(2, context.resourceRefs.size());
        assertEquals(2, context.persistenceContextRefs.size());
        assertEquals(2, context.persistenceUnitRefs.size());
        assertEquals(1, context.serviceRefs.size());
    }

    @Test
    public void testUnreadableClassDataIsLoggedNotThrown() throws Exception {
        InputStream in = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unreadable");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("unreadable");
            }
        };
        context.scanClass(descriptor, "com.example.DoesNotExist", in);
        assertTrue(descriptor.servletDefs.isEmpty());
    }
}
