/*
 * ServletInitialContextFactoryTest.java
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
import java.util.Hashtable;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link ServletInitialContextFactory}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletInitialContextFactoryTest {

    @Before
    @After
    public void resetFactoryContext() {
        ServletInitialContextFactory.ctx = null;
    }

    @Test
    public void testGetInitialContextCreatesSingleton() throws NamingException {
        ServletInitialContextFactory factory = new ServletInitialContextFactory();
        Hashtable<String, String> env = new Hashtable<String, String>();
        env.put("sample", "value");
        Context first = factory.getInitialContext(env);
        Context second = factory.getInitialContext(new Hashtable<String, String>());
        assertSame(first, second);
        assertEquals("value", first.getEnvironment().get("sample"));
    }

    @Test
    public void testReusesStaticContextWhenAlreadySet() throws NamingException {
        ServletInitialContext preset = new ServletInitialContext(new Hashtable<String, String>());
        ServletInitialContextFactory.ctx = preset;
        ServletInitialContextFactory factory = new ServletInitialContextFactory();
        assertSame(preset, factory.getInitialContext(new Hashtable<String, String>()));
    }
}
