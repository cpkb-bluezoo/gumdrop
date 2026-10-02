/*
 * JspPropertyGroupResolverTest.java
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

package org.bluezoo.gumdrop.servlet.jsp;

import jakarta.servlet.descriptor.JspConfigDescriptor;
import jakarta.servlet.descriptor.JspPropertyGroupDescriptor;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for JspPropertyGroupResolver.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspPropertyGroupResolverTest {

    private JspPropertyGroupDescriptor group(String pattern, Map<String, Object> props) {
        List<String> patterns = new ArrayList<String>();
        patterns.add(pattern);
        return JspStubSupport.group(patterns, props);
    }

    private JspConfigDescriptor config(JspPropertyGroupDescriptor... groups) {
        List<JspPropertyGroupDescriptor> list = new ArrayList<JspPropertyGroupDescriptor>();
        for (JspPropertyGroupDescriptor g : groups) {
            list.add(g);
        }
        return JspStubSupport.config(null, list);
    }

    @Test
    public void testNoConfig() {
        JspPropertyGroupResolver.ResolvedJSPProperties p = JspPropertyGroupResolver.resolve("/a.jsp", null);
        assertNotNull(p);
        assertEquals("UTF-8", p.getPageEncoding());
        assertNull(p.getElIgnored());
        assertTrue(p.getIncludePreludes().isEmpty());
        assertTrue(p.getIncludeCodas().isEmpty());
        assertNotNull(p.toString());
        p = JspPropertyGroupResolver.resolve(null, config());
        assertNull(p.getBuffer());
    }

    @Test
    public void testEmptyGroups() {
        JspPropertyGroupResolver.ResolvedJSPProperties p =
                JspPropertyGroupResolver.resolve("/a.jsp", config());
        assertNull(p.getDefaultContentType());
    }

    @Test
    public void testExtensionMatchAndAllProperties() {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("getPageEncoding", "ISO-8859-1");
        props.put("getElIgnored", "true");
        props.put("getScriptingInvalid", "true");
        props.put("getIsXml", "false");
        props.put("getDefaultContentType", "text/plain");
        props.put("getBuffer", "16kb");
        props.put("getTrimDirectiveWhitespaces", "true");
        props.put("getDeferredSyntaxAllowedAsLiteral", "true");
        props.put("getErrorOnUndeclaredNamespace", "true");
        props.put("getIncludePreludes", Arrays.asList("/pre.jspf"));
        props.put("getIncludeCodas", Arrays.asList("/coda.jspf"));
        JspConfigDescriptor cfg = config(group("*.jsp", props));
        JspPropertyGroupResolver.ResolvedJSPProperties p =
                JspPropertyGroupResolver.resolve("/x/a.jsp", cfg);
        assertEquals("ISO-8859-1", p.getPageEncoding());
        assertEquals(Boolean.TRUE, p.getElIgnored());
        assertEquals(Boolean.TRUE, p.getScriptingInvalid());
        assertEquals(Boolean.FALSE, p.getIsXml());
        assertEquals("text/plain", p.getDefaultContentType());
        assertEquals("16kb", p.getBuffer());
        assertEquals(Boolean.TRUE, p.getTrimDirectiveWhitespaces());
        assertEquals(Boolean.TRUE, p.getDeferredSyntaxAllowedAsLiteral());
        assertEquals(Boolean.TRUE, p.getErrorOnUndeclaredNamespace());
        assertEquals(1, p.getIncludePreludes().size());
        assertEquals(1, p.getIncludeCodas().size());
        assertTrue(p.toString().indexOf("ISO-8859-1") > 0);
    }

    @Test
    public void testPrefixExactAndNoMatch() {
        Map<String, Object> admin = new HashMap<String, Object>();
        admin.put("getPageEncoding", "UTF-16");
        Map<String, Object> exact = new HashMap<String, Object>();
        exact.put("getBuffer", "none");
        JspConfigDescriptor cfg = config(group("/admin/*", admin), group("/exact.jsp", exact));
        assertEquals("UTF-16", JspPropertyGroupResolver.resolve("/admin/x.jsp", cfg).getPageEncoding());
        assertEquals("UTF-16", JspPropertyGroupResolver.resolve("/admin", cfg).getPageEncoding());
        assertEquals("UTF-8", JspPropertyGroupResolver.resolve("/administrator/x.jsp", cfg).getPageEncoding());
        assertEquals("none", JspPropertyGroupResolver.resolve("/exact.jsp", cfg).getBuffer());
        assertNull(JspPropertyGroupResolver.resolve("/other.html", cfg).getBuffer());
    }

    @Test
    public void testLaterMatchWinsAndAccumulates() {
        Map<String, Object> one = new HashMap<String, Object>();
        one.put("getPageEncoding", "A");
        one.put("getIncludePreludes", Arrays.asList("/p1"));
        Map<String, Object> two = new HashMap<String, Object>();
        two.put("getPageEncoding", "B");
        two.put("getIncludePreludes", Arrays.asList("/p2"));
        JspConfigDescriptor cfg = config(group("*.jsp", one), group("/a/*", two));
        JspPropertyGroupResolver.ResolvedJSPProperties p =
                JspPropertyGroupResolver.resolve("/a/b.jsp", cfg);
        assertEquals("B", p.getPageEncoding());
        assertEquals(2, p.getIncludePreludes().size());
    }

    @Test
    public void testGroupWithoutPatternsOrNullPattern() {
        JspPropertyGroupDescriptor none = JspStubSupport.group(new ArrayList<String>(),
                new HashMap<String, Object>());
        List<String> withNull = new ArrayList<String>();
        withNull.add(null);
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("getPageEncoding", "Z");
        JspPropertyGroupDescriptor nullPat = JspStubSupport.group(withNull, props);
        JspConfigDescriptor cfg = config(none, nullPat);
        assertEquals("UTF-8", JspPropertyGroupResolver.resolve("/a.jsp", cfg).getPageEncoding());
    }
}
