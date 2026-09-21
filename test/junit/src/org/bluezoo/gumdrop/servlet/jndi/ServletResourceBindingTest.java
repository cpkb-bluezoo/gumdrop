/*
 * ServletResourceBindingTest.java
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

import javax.naming.Context;
import javax.naming.NamingException;
import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Mirrors {@link org.bluezoo.gumdrop.servlet.Context} JNDI resource binding and
 * {@link ResourceInjector} wiring without booting a full web application.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletResourceBindingTest {

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
    public void testBindAdministeredObjectLikeContextInit() throws Exception {
        AdministeredObject resource = new AdministeredObject();
        resource.jndiName = "app/widget";
        resource.administeredObjectClass = SampleWidget.class.getName();
        resource.administeredObjectInterface = SampleWidget.class.getName();

        Object instance = resource.newInstance();
        assertNotNull(instance);

        String name = JndiContext.stripCompEnv(resource.getName());
        namingContext.bind("java:comp/env/" + name,
                resource.getInterfaceName(), instance);

        SampleWidgetTarget target = new SampleWidgetTarget();
        ResourceInjector.injectResources(target, emptyContext());
        assertSame(instance, target.widget);
    }

    @Test
    public void testBindConnectionFactoryAndInjectByMappedName() throws Exception {
        ConnectionFactory resource = new ConnectionFactory();
        resource.jndiName = "jca/ERP";
        resource.properties.put("eisProductName", "Loopback ERP");

        Object instance = resource.newInstance();
        assertNotNull(instance);

        namingContext.bind("java:comp/env/jca/ERP",
                BasicJCAConnectionFactory.class.getName(), instance);

        FactoryTarget target = new FactoryTarget();
        ResourceInjector.injectResources(target, emptyContext());
        assertEquals("Loopback ERP", target.getFactory().getEISProductName());
    }

    @Test
    public void testInjectableResolveThenFieldInjection() throws Exception {
        namingContext.bind("java:comp/env/app/title", "Bound Title");

        ResourceEnvRef injectable = new ResourceEnvRef();
        injectable.setMappedName("app/title");
        InjectionTarget it = new InjectionTarget();
        it.setClassName(EnvEntryConsumer.class.getName());
        it.setName("title");
        injectable.setInjectionTarget(it);

        Object resolved = injectable.resolve(namingContext);
        assertEquals("Bound Title", resolved);

        EnvEntryConsumer consumer = new EnvEntryConsumer();
        consumer.title = (String) resolved;
        assertEquals("Bound Title", consumer.title);
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
                return ServletResourceBindingTest.class.getClassLoader();
            }
        };
    }

    public static final class SampleWidget {
    }

    public static class SampleWidgetTarget {
        @javax.annotation.Resource(name = "app/widget")
        private SampleWidget widget;
    }

    public static class FactoryTarget {
        private BasicJCAConnectionFactory factory;

        @javax.annotation.Resource(name = "jca/ERP")
        public void setFactory(BasicJCAConnectionFactory factory) {
            this.factory = factory;
        }

        BasicJCAConnectionFactory getFactory() {
            return factory;
        }
    }

    public static class EnvEntryConsumer {
        public String title;
    }
}
