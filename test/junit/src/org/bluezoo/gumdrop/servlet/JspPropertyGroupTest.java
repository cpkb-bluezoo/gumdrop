/*
 * JspPropertyGroupTest.java
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

import org.junit.Test;

import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the descriptor accessors of {@link JspPropertyGroup} and the lookup
 * rules of {@link MessageDestination}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspPropertyGroupTest {

    @Test
    public void testUnsetPropertiesAreNull() {
        JspPropertyGroup g = new JspPropertyGroup();
        assertNull(g.getElIgnored());
        assertNull(g.getPageEncoding());
        assertNull(g.getScriptingInvalid());
        assertNull(g.getIsXml());
        assertNull(g.getDeferredSyntaxAllowedAsLiteral());
        assertNull(g.getTrimDirectiveWhitespaces());
        assertNull(g.getDefaultContentType());
        assertNull(g.getBuffer());
        assertNull(g.getErrorOnUndeclaredNamespace());
        assertNull(g.getErrorOnELNotFound());
        assertTrue(g.getUrlPatterns().isEmpty());
        assertTrue(g.getIncludePreludes().isEmpty());
        assertTrue(g.getIncludeCodas().isEmpty());
    }

    @Test
    public void testSetPropertiesAreReportedAsText() {
        JspPropertyGroup g = new JspPropertyGroup();
        g.urlPatterns.add("*.jsp");
        g.urlPatterns.add("*.jspx");
        g.includePrelude.add("/pre.jspf");
        g.includeCoda.add("/coda.jspf");
        g.elIgnored = Boolean.TRUE;
        g.pageEncoding = "UTF-8";
        g.scriptingInvalid = Boolean.FALSE;
        g.isXml = Boolean.TRUE;
        g.deferredSyntaxAllowedAsLiteral = Boolean.FALSE;
        g.trimDirectiveWhitespaces = Boolean.TRUE;
        g.defaultContentType = "text/html";
        g.buffer = Long.valueOf(8L);
        g.errorOnUndeclaredNamespace = Boolean.TRUE;
        g.errorOnELNotFound = Boolean.FALSE;
        Collection<String> patterns = g.getUrlPatterns();
        assertEquals(2, patterns.size());
        assertEquals("true", g.getElIgnored());
        assertEquals("UTF-8", g.getPageEncoding());
        assertEquals("false", g.getScriptingInvalid());
        assertEquals("true", g.getIsXml());
        assertEquals("false", g.getDeferredSyntaxAllowedAsLiteral());
        assertEquals("true", g.getTrimDirectiveWhitespaces());
        assertEquals("text/html", g.getDefaultContentType());
        assertEquals("8", g.getBuffer());
        assertEquals("true", g.getErrorOnUndeclaredNamespace());
        assertEquals("false", g.getErrorOnELNotFound());
        assertEquals(1, g.getIncludePreludes().size());
        assertEquals(1, g.getIncludeCodas().size());
    }

    @Test
    public void testMessageDestinationAccessorsAndJndiNamePreference() {
        MessageDestination d = new MessageDestination();
        d.setDescription("desc");
        d.setDisplayName("display");
        d.setSmallIcon("s.png");
        d.setLargeIcon("l.png");
        d.messageDestinationName = "queue";
        assertEquals("desc", d.getDescription());
        assertEquals("display", d.getDisplayName());
        assertEquals("s.png", d.getSmallIcon());
        assertEquals("l.png", d.getLargeIcon());
        assertEquals("queue", d.getName());
        assertNull(d.getJndiName());
        d.lookupName = "";
        d.mappedName = "";
        assertNull(d.getJndiName());
        d.mappedName = "legacy";
        assertEquals("legacy", d.getJndiName());
        d.lookupName = "java:comp/env/q";
        assertEquals("java:comp/env/q", d.getJndiName());
    }
}
