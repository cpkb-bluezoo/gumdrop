/*
 * EjbRefTest.java
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

import org.junit.Test;

import javax.ejb.EJB;
import javax.naming.NamingException;
import java.util.Hashtable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Tests {@link EjbRef}: field setters, annotation initialisation and the
 * JNDI lookup resolution order.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EjbRefTest {

    /** Carrier for an {@link EJB} annotation. */
    static class Holder {
        @EJB(name = "ejb/annotated", beanInterface = Runnable.class, beanName = "beanLink",
                lookup = "java:global/looked", mappedName = "mapped", description = "annotated ref")
        Object field;
    }

    private static ServletInitialContext newContext() {
        return new ServletInitialContext(new Hashtable<String, String>());
    }

    @Test
    public void testSettersAndAccessors() {
        EjbRef ref = new EjbRef(false);
        ref.setDescription("d");
        ref.setName("ejb/x");
        ref.setClassName("Session");
        ref.setHome("h");
        ref.setRemoteOrLocal("r");
        ref.setEjbLink("link");
        ref.setLookupName("lookup");
        ref.setMappedName("mapped");
        InjectionTarget target = new InjectionTarget();
        ref.setInjectionTarget(target);
        assertEquals("ejb/x", ref.getName());
        assertEquals("lookup", ref.getLookupName());
        assertEquals("mapped", ref.getMappedName());
        assertEquals("link", ref.getDefaultName());
        assertSame(target, ref.getInjectionTarget());
    }

    @Test
    public void testInitFromAnnotation() throws Exception {
        EJB config = Holder.class.getDeclaredField("field").getAnnotation(EJB.class);
        EjbRef ref = new EjbRef(true);
        ref.init(config);
        assertEquals("ejb/annotated", ref.getName());
        assertEquals("java:global/looked", ref.getLookupName());
        assertEquals("mapped", ref.getMappedName());
        assertEquals("beanLink", ref.getDefaultName());
        assertEquals("java.lang.Runnable", ref.className);
        assertEquals("annotated ref", ref.description);
    }

    @Test
    public void testResolveNothingBound() throws NamingException {
        EjbRef ref = new EjbRef(true);
        assertNull(ref.resolve(newContext()));
        ref.setLookupName("");
        ref.setEjbLink("");
        ref.setMappedName("");
        ref.setRemoteOrLocal("");
        ref.setName("");
        assertNull(ref.resolve(newContext()));
    }

    @Test
    public void testResolveByLookupName() throws NamingException {
        ServletInitialContext ctx = newContext();
        ctx.bind("java:comp/env/by-lookup", "lookup-value");
        EjbRef ref = new EjbRef(true);
        ref.setLookupName("java:comp/env/by-lookup");
        ref.setEjbLink("ignored");
        assertEquals("lookup-value", ref.resolve(ctx));
    }

    @Test
    public void testResolveByEjbLink() throws NamingException {
        ServletInitialContext ctx = newContext();
        ctx.bind("java:comp/env/direct-link", "direct");
        ctx.bind("java:comp/env/ejb/prefixed", "prefixed");
        EjbRef direct = new EjbRef(true);
        direct.setLookupName("java:comp/env/unbound");
        direct.setEjbLink("java:comp/env/direct-link");
        assertEquals("direct", direct.resolve(ctx));
        EjbRef prefixed = new EjbRef(false);
        prefixed.setEjbLink("prefixed");
        assertEquals("prefixed", prefixed.resolve(ctx));
    }

    @Test
    public void testResolveByMappedName() throws NamingException {
        ServletInitialContext ctx = newContext();
        ctx.bind("java:comp/env/mapped-one", "one");
        EjbRef relative = new EjbRef(true);
        relative.setMappedName("mapped-one");
        assertEquals("one", relative.resolve(ctx));
        EjbRef absolute = new EjbRef(true);
        absolute.setMappedName("java:global/two");
        // the context only serves java:comp/env, so a global name is not found
        assertNull(absolute.resolve(ctx));
    }

    @Test
    public void testResolveByInterfaceDefaults() throws NamingException {
        ServletInitialContext ctx = newContext();
        EjbRef global = new EjbRef(true);
        global.setRemoteOrLocal("com.example.Remote");
        assertNull(global.resolve(ctx));
        EjbRef app = new EjbRef(false);
        app.setRemoteOrLocal("com.example.Local");
        assertNull(app.resolve(ctx));
    }

    @Test
    public void testResolveByReferenceName() throws NamingException {
        ServletInitialContext ctx = newContext();
        ctx.bind("java:comp/env/ejb/byname", "named");
        EjbRef ref = new EjbRef(true);
        ref.setName("ejb/byname");
        assertEquals("named", ref.resolve(ctx));
    }
}
