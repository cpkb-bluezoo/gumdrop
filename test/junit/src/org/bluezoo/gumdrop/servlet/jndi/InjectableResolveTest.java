/*
 * InjectableResolveTest.java
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

import org.junit.Test;

import javax.naming.NamingException;
import java.util.Hashtable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link Injectable#resolve} lookup order against a real {@link ServletInitialContext}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class InjectableResolveTest {

    @Test
    public void testResourceEnvRefResolvesMappedName() throws NamingException {
        ServletInitialContext ctx = new ServletInitialContext(new Hashtable<String, String>());
        ctx.bind("java:comp/env/config/limit", Integer.valueOf(42));

        ResourceEnvRef ref = new ResourceEnvRef();
        ref.setMappedName("config/limit");

        assertEquals(Integer.valueOf(42), ref.resolve(ctx));
    }

    @Test
    public void testResourceRefPrefersExplicitLookupName() throws NamingException {
        ServletInitialContext ctx = new ServletInitialContext(new Hashtable<String, String>());
        ctx.bind("java:comp/env/primary", "primary-ds");
        ctx.bind("java:comp/env/secondary", "secondary-ds");

        ResourceRef ref = new ResourceRef();
        ref.setLookupName("java:comp/env/primary");
        ref.setMappedName("secondary");

        assertEquals("primary-ds", ref.resolve(ctx));
    }

    @Test
    public void testDefaultNameFromInjectionTarget() throws NamingException {
        ServletInitialContext ctx = new ServletInitialContext(new Hashtable<String, String>());

        InjectionTarget target = new InjectionTarget();
        target.setClassName("com.example.Config");
        target.setName("timeout");

        ctx.bind("java:comp/env/com.example.Config/timeout", Long.valueOf(5000L));

        ResourceEnvRef ref = new ResourceEnvRef();
        ref.setInjectionTarget(target);

        assertEquals(Long.valueOf(5000L), ref.resolve(ctx));
    }

    @Test
    public void testResolveReturnsNullWhenNothingBound() throws NamingException {
        ResourceRef ref = new ResourceRef();
        ref.setMappedName("missing/ref");
        ServletInitialContext ctx = new ServletInitialContext(new Hashtable<String, String>());
        assertNull(ref.resolve(ctx));
    }
}
