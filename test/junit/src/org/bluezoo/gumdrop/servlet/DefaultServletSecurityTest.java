/*
 * DefaultServletSecurityTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests {@link DefaultServlet} protected-resource path checks (SEC-002).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultServletSecurityTest {

    private static final class TestableDefaultServlet extends DefaultServlet {
        private static final long serialVersionUID = 1L;

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

    // Regression tests for issue #175: the collection trailing-slash
    // redirect used to build its Location header from
    // request.getRequestURL() (scheme + client-supplied Host + port),
    // string-concatenated with the raw query string. It now builds a
    // path-relative reference instead, never touching Host at all.

    @Test
    public void testCollectionRedirectLocationIsPathRelative() {
        String location = DefaultServlet.buildCollectionRedirectLocation(
                "/app", "/dir", null);
        assertEquals("/app/dir/", location);
    }

    @Test
    public void testCollectionRedirectLocationPreservesQueryString() {
        String location = DefaultServlet.buildCollectionRedirectLocation(
                "/app", "/dir", "sort=asc&page=2");
        assertEquals("/app/dir/?sort=asc&page=2", location);
    }

    @Test
    public void testCollectionRedirectLocationHandlesRootContext() {
        // Root context path is "" per the servlet spec, not null, but
        // handle null defensively too.
        assertEquals("/dir/", DefaultServlet.buildCollectionRedirectLocation(
                "", "/dir", null));
        assertEquals("/dir/", DefaultServlet.buildCollectionRedirectLocation(
                null, "/dir", null));
    }

    @Test
    public void testCollectionRedirectLocationNeverContainsSchemeOrHost() {
        // The whole point of the fix: nothing derived from Host/scheme
        // (e.g. request.getRequestURL()) can leak in, because the helper
        // is never handed those values at all.
        String location = DefaultServlet.buildCollectionRedirectLocation(
                "/app", "/dir", "next=http://evil.example/");
        assertTrue(location.startsWith("/"));
        assertFalse(location.toLowerCase().startsWith("http://"));
        assertFalse(location.toLowerCase().startsWith("https://"));
    }

    @Test
    public void testCollectionRedirectLocationCollapsesLeadingDoubleSlash() {
        // A Location value starting with "//" has no scheme of its own but
        // is still interpreted by browsers as a protocol-relative absolute
        // URL to an attacker-chosen host - the standard bypass for a "just
        // make it relative" open-redirect fix. Must be collapsed to a
        // single leading "/", not merely lack an explicit scheme.
        String location = DefaultServlet.buildCollectionRedirectLocation(
                "", "//evil.example/phish", null);
        assertFalse("must not start with '//': " + location,
                location.startsWith("//"));
        assertEquals("/evil.example/phish/", location);
    }

    @Test
    public void testCollectionRedirectLocationCollapsesManyLeadingSlashes() {
        String location = DefaultServlet.buildCollectionRedirectLocation(
                null, "////evil.example/phish", null);
        assertFalse(location.startsWith("//"));
        assertEquals("/evil.example/phish/", location);
    }
}
