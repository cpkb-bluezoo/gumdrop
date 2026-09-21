/*
 * ResourceInjectorTest.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.annotation.Resource;
import javax.naming.Context;
import javax.naming.NamingException;
import jakarta.servlet.ServletException;

import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link ResourceInjector}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResourceInjectorTest {

    private String previousFactory;
    private ServletInitialContext namingContext;

    @Before
    public void setUpNaming() throws NamingException {
        previousFactory = System.getProperty(Context.INITIAL_CONTEXT_FACTORY);
        System.setProperty(Context.INITIAL_CONTEXT_FACTORY,
                ServletInitialContextFactory.class.getName());
        namingContext = new ServletInitialContext(new Hashtable<String, String>());
        ServletInitialContextFactory.ctx = namingContext;
    }

    @After
    public void tearDownNaming() {
        ServletInitialContextFactory.ctx = null;
        if (previousFactory != null) {
            System.setProperty(Context.INITIAL_CONTEXT_FACTORY, previousFactory);
        } else {
            System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
        }
    }

    @Test
    public void testInjectNullTargetIsNoOp() throws ServletException {
        ResourceInjector.injectResources(null, emptyContext());
    }

    @Test
    public void testInjectFieldByExplicitName() throws Exception {
        namingContext.bind("java:comp/env/app/config", "production");
        FieldTarget target = new FieldTarget();
        ResourceInjector.injectResources(target, emptyContext());
        assertEquals("production", target.configValue);
    }

    @Test
    public void testInjectViaSetterMethod() throws Exception {
        namingContext.bind("java:comp/env/app/title", "Gumdrop");
        SetterTarget target = new SetterTarget();
        ResourceInjector.injectResources(target, emptyContext());
        assertEquals("Gumdrop", target.getTitle());
    }

    @Test(expected = ServletException.class)
    public void testMissingRequiredResourceFails() throws Exception {
        ResourceInjector.injectResources(new FieldTarget(), emptyContext());
    }

    @Test(expected = ServletException.class)
    public void testSetterWithWrongParameterCountFails() throws Exception {
        namingContext.bind("java:comp/env/app/bad", "x");
        ResourceInjector.injectResources(new BadSetterTarget(), emptyContext());
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
                return ResourceInjectorTest.class.getClassLoader();
            }
        };
    }

    public static class FieldTarget {
        @Resource(name = "app/config")
        private String configValue;
    }

    public static class SetterTarget {
        private String title;

        @Resource(name = "app/title")
        public void setTitle(String title) {
            this.title = title;
        }

        String getTitle() {
            return title;
        }
    }

    public static class BadSetterTarget {
        @Resource(name = "app/bad")
        public void setBad(String a, String b) {
        }
    }
}
