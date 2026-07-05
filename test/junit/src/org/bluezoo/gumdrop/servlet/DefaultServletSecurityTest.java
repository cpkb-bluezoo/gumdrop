/*
 * DefaultServletSecurityTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.servlet;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests {@link DefaultServlet} protected-resource path checks (SEC-002).
 */
public class DefaultServletSecurityTest {

    private static final class TestableDefaultServlet extends DefaultServlet {
        boolean isProtected(String path) {
            return isWebInf(path);
        }
    }

    private final TestableDefaultServlet servlet = new TestableDefaultServlet();

    @Test
    public void testNormalizeServletPathCollapsesDotDot() {
        assertEquals("/WEB-INF/web.xml",
                DefaultServlet.normalizeServletPath("/public/../WEB-INF/web.xml"));
        assertEquals("/public/index.html",
                DefaultServlet.normalizeServletPath("/public/./index.html"));
        assertNull(DefaultServlet.normalizeServletPath("/../outside"));
        assertNull(DefaultServlet.normalizeServletPath("/x/../../WEB-INF/web.xml"));
    }

    @Test
    public void testIsWebInfRejectsDirectPaths() {
        assertTrue(servlet.isProtected("/WEB-INF/web.xml"));
        assertTrue(servlet.isProtected("/web-inf/web.xml"));
        assertTrue(servlet.isProtected("/META-INF/MANIFEST.MF"));
        assertTrue(servlet.isProtected("/meta-inf/MANIFEST.MF"));
    }

    @Test
    public void testIsWebInfRejectsTraversalToProtectedHierarchy() {
        assertTrue(servlet.isProtected("/x/../../WEB-INF/web.xml"));
        assertTrue(servlet.isProtected("/public/../WEB-INF/classes/Foo.class"));
        assertTrue(servlet.isProtected("/a/b/../../../META-INF/MANIFEST.MF"));
    }

    @Test
    public void testIsWebInfRejectsNestedProtectedSegment() {
        assertTrue(servlet.isProtected("/public/WEB-INF/secret.txt"));
    }

    @Test
    public void testIsWebInfAllowsPublicPaths() {
        assertFalse(servlet.isProtected("/index.html"));
        assertFalse(servlet.isProtected("/css/app.css"));
        assertFalse(servlet.isProtected("/WEB-INFEXTRA/public.txt"));
        assertFalse(servlet.isProtected("/META-INFEXTRA/public.txt"));
    }
}
