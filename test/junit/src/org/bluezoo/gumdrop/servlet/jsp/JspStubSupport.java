/*
 * JspStubSupport.java
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

import jakarta.servlet.ServletContext;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import jakarta.servlet.descriptor.JspPropertyGroupDescriptor;
import jakarta.servlet.descriptor.TaglibDescriptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflective stubs of servlet descriptor and context interfaces for JSP tests.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class JspStubSupport {

    private JspStubSupport() {
    }

    /** Handler returning canned values by method name. */
    static class MapHandler implements InvocationHandler {
        final Map<String, Object> values = new HashMap<String, Object>();
        final Map<String, String> resources = new HashMap<String, String>();
        final Map<String, Object> attributes = new HashMap<String, Object>();

        public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            if (n.equals("getResourceAsStream")) {
                String r = resources.get((String) args[0]);
                if (r == null) {
                    return null;
                }
                return new ByteArrayInputStream(r.getBytes(StandardCharsets.UTF_8));
            }
            if (n.equals("getResourcePaths")) {
                String prefix = (String) args[0];
                Set<String> out = new HashSet<String>();
                for (String key : resources.keySet()) {
                    if (key.startsWith(prefix) && !key.equals(prefix)) {
                        out.add(key);
                    }
                }
                if (out.isEmpty()) {
                    return null;
                }
                return out;
            }
            if (n.equals("setAttribute")) {
                attributes.put((String) args[0], args[1]);
                return null;
            }
            if (n.equals("getAttribute")) {
                return attributes.get((String) args[0]);
            }
            if (n.equals("hashCode")) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if (n.equals("equals")) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if (n.equals("toString")) {
                return "stub";
            }
            Object v = values.get(n);
            if (v != null) {
                return v;
            }
            Class<?> rt = m.getReturnType();
            if (rt == boolean.class) {
                return Boolean.FALSE;
            }
            if (rt == int.class) {
                return Integer.valueOf(0);
            }
            if (rt == long.class) {
                return Long.valueOf(0L);
            }
            return null;
        }
    }

    static <T> T stub(Class<T> iface, MapHandler h) {
        Object o = Proxy.newProxyInstance(JspStubSupport.class.getClassLoader(),
                new Class<?>[] { iface }, h);
        return iface.cast(o);
    }

    static ServletContext context(Map<String, String> resources, JspConfigDescriptor cfg) {
        MapHandler h = new MapHandler();
        h.resources.putAll(resources);
        if (cfg != null) {
            h.values.put("getJspConfigDescriptor", cfg);
        }
        return stub(ServletContext.class, h);
    }

    static TaglibDescriptor taglib(String uri, String location) {
        MapHandler h = new MapHandler();
        h.values.put("getTaglibURI", uri);
        h.values.put("getTaglibLocation", location);
        return stub(TaglibDescriptor.class, h);
    }

    static JspConfigDescriptor config(Collection<TaglibDescriptor> taglibs,
            Collection<JspPropertyGroupDescriptor> groups) {
        MapHandler h = new MapHandler();
        if (taglibs == null) {
            taglibs = new ArrayList<TaglibDescriptor>();
        }
        if (groups == null) {
            groups = new ArrayList<JspPropertyGroupDescriptor>();
        }
        h.values.put("getTaglibs", taglibs);
        h.values.put("getJspPropertyGroups", groups);
        return stub(JspConfigDescriptor.class, h);
    }

    static JspPropertyGroupDescriptor group(List<String> patterns, Map<String, Object> props) {
        MapHandler h = new MapHandler();
        h.values.put("getUrlPatterns", patterns);
        h.values.putAll(props);
        return stub(JspPropertyGroupDescriptor.class, h);
    }

    static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
