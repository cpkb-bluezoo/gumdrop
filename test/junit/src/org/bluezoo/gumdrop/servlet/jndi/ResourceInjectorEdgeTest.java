/*
 * ResourceInjectorEdgeTest.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Properties;

import javax.annotation.Resource;
import jakarta.mail.Session;
import javax.naming.Context;
import javax.sql.DataSource;

import jakarta.servlet.ServletException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * Covers the name derivation, inheritance and failure branches of
 * {@link ResourceInjector} that the basic injector tests leave out.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResourceInjectorEdgeTest {

    private String previousFactory;
    private ServletInitialContext naming;

    @Before
    public void setUp() {
        previousFactory = System.getProperty(Context.INITIAL_CONTEXT_FACTORY);
        System.setProperty(Context.INITIAL_CONTEXT_FACTORY,
                ServletInitialContextFactory.class.getName());
        naming = new ServletInitialContext(new Hashtable<String, String>());
        ServletInitialContextFactory.ctx = naming;
    }

    @After
    public void tearDown() {
        ServletInitialContextFactory.ctx = null;
        if (previousFactory != null) {
            System.setProperty(Context.INITIAL_CONTEXT_FACTORY, previousFactory);
        } else {
            System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
        }
    }

    private static JndiContext emptyContext() {
        return new JndiContext() {
            @Override
            public Collection<org.bluezoo.gumdrop.servlet.jndi.Resource> getResources() {
                return Collections.emptyList();
            }

            @Override
            public Collection<Injectable> getInjectables() {
                return Collections.emptyList();
            }

            @Override
            public ClassLoader getContextClassLoader() {
                return ResourceInjectorEdgeTest.class.getClassLoader();
            }
        };
    }

    /** Base class with an injected field and setter. */
    public static class Base {
        @Resource(name = "base/field")
        String baseField;
        String baseSetter;

        @Resource(name = "base/setter")
        public void setBaseSetter(String v) {
            baseSetter = v;
        }
    }

    /** Subclass adding its own injected members. */
    public static class Derived extends Base {
        @Resource(name = "derived/field")
        String derivedField;
        String plain = "untouched";
    }

    public static class MappedTarget {
        @Resource(mappedName = "mapped/thing")
        String viaMapped;
        @Resource(lookup = "java:comp/env/looked/up")
        String viaLookup;
    }

    public static class AbsoluteTarget {
        @Resource(name = "java:comp/env/abs/name")
        String value;
    }

    public static class TypedTarget {
        @Resource(name = "typed/value")
        Integer number;
    }

    public static class DefaultNames {
        @Resource
        DataSource ds;
        @Resource
        Session mailSession;
        @Resource
        String custom;
    }

    public static class OddSetter {
        String got;

        @Resource(name = "odd/value")
        public void wire(String v) {
            got = v;
        }
    }

    public static class DefaultSetter {
        String got;

        @Resource
        public void setCustomName(String v) {
            got = v;
        }
    }

    public static class ShortSetter {
        String got;

        @Resource
        public void set(String v) {
            got = v;
        }
    }

    public static class ThrowingSetter {
        @Resource(name = "boom/value")
        public void setBoom(String v) {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    public void inheritedMembersAreInjected() throws Exception {
        naming.bind("java:comp/env/base/field", "bf");
        naming.bind("java:comp/env/base/setter", "bs");
        naming.bind("java:comp/env/derived/field", "df");
        Derived d = new Derived();
        ResourceInjector.injectResources(d, emptyContext());
        assertEquals("bf", d.baseField);
        assertEquals("bs", d.baseSetter);
        assertEquals("df", d.derivedField);
        assertEquals("untouched", d.plain);
    }

    @Test
    public void mappedAndLookupNamesAreUsed() throws Exception {
        naming.bind("java:comp/env/mapped/thing", "m");
        naming.bind("java:comp/env/looked/up", "l");
        MappedTarget t = new MappedTarget();
        ResourceInjector.injectResources(t, emptyContext());
        assertEquals("m", t.viaMapped);
        assertEquals("l", t.viaLookup);
    }

    @Test
    public void absoluteJavaNamesAreNotPrefixed() throws Exception {
        naming.bind("java:comp/env/abs/name", "abs");
        AbsoluteTarget t = new AbsoluteTarget();
        ResourceInjector.injectResources(t, emptyContext());
        assertEquals("abs", t.value);
    }

    @Test
    public void typeMismatchIsRejected() throws Exception {
        naming.bind("java:comp/env/typed/value", "not a number");
        TypedTarget t = new TypedTarget();
        try {
            ResourceInjector.injectResources(t, emptyContext());
            fail("expected ServletException");
        } catch (ServletException e) {
            assertNull(t.number);
        }
    }

    @Test
    public void defaultNamesFollowResourceType() throws Exception {
        DataSource ds = (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { DataSource.class },
                new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        return null;
                    }
                });
        Session session = Session.getInstance(new Properties());
        naming.bind("java:comp/env/jdbc/ds", ds);
        naming.bind("java:comp/env/mail/mailSession", session);
        naming.bind("java:comp/env/custom", "c");
        DefaultNames t = new DefaultNames();
        ResourceInjector.injectResources(t, emptyContext());
        assertSame(ds, t.ds);
        assertSame(session, t.mailSession);
        assertEquals("c", t.custom);
    }

    @Test
    public void methodNameWithoutSetPrefixIsUsedVerbatim() throws Exception {
        naming.bind("java:comp/env/odd/value", "odd");
        OddSetter t = new OddSetter();
        ResourceInjector.injectResources(t, emptyContext());
        assertEquals("odd", t.got);
    }

    @Test
    public void defaultSetterNameIsDecapitalised() throws Exception {
        naming.bind("java:comp/env/customName", "cn");
        DefaultSetter t = new DefaultSetter();
        ResourceInjector.injectResources(t, emptyContext());
        assertEquals("cn", t.got);
    }

    @Test
    public void bareSetNameIsUsedVerbatim() throws Exception {
        naming.bind("java:comp/env/set", "s");
        ShortSetter t = new ShortSetter();
        ResourceInjector.injectResources(t, emptyContext());
        assertEquals("s", t.got);
    }

    @Test
    public void setterFailureIsWrapped() throws Exception {
        naming.bind("java:comp/env/boom/value", "x");
        try {
            ResourceInjector.injectResources(new ThrowingSetter(), emptyContext());
            fail("expected ServletException");
        } catch (ServletException e) {
            assertSame(java.lang.reflect.InvocationTargetException.class, e.getCause().getClass());
        }
    }

    @Test
    public void missingMethodResourceFails() throws Exception {
        try {
            ResourceInjector.injectResources(new OddSetter(), emptyContext());
            fail("expected ServletException");
        } catch (ServletException e) {
            assertEquals(ServletException.class, e.getClass());
        }
    }
}
