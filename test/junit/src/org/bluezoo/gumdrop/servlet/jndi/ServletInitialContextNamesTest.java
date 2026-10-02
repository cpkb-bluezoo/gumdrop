/*
 * ServletInitialContextNamesTest.java
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

import java.util.Hashtable;

import javax.naming.CompositeName;
import javax.naming.InvalidNameException;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises the {@link javax.naming.Name} based overloads and the
 * unsupported operations of {@link ServletInitialContext}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletInitialContextNamesTest {

    private ServletInitialContext ctx;

    @Before
    public void setUp() {
        ctx = new ServletInitialContext(new Hashtable<String, String>());
    }

    private Name multi() throws NamingException {
        return new CompositeName("java:comp/env/a");
    }

    @Test
    public void bindAndRebindByName() throws NamingException {
        Name n = ctx.parse("java:comp/env/x");
        ctx.bind(n, "one");
        assertEquals("one", ctx.lookup("java:comp/env/x"));
        ctx.rebind(n, "two");
        assertEquals("two", ctx.lookup(n));
        ctx.rebind("java:comp/env/x", "three");
        assertEquals("three", ctx.lookup("java:comp/env/x"));
        assertEquals("three", ctx.lookupLink(n));
        assertEquals("three", ctx.lookupLink("java:comp/env/x"));
    }

    @Test
    public void unbindByName() throws NamingException {
        Name n = ctx.parse("java:comp/env/x");
        ctx.bind(n, "one");
        ctx.unbind(n);
        try {
            ctx.lookup(n);
            fail("expected NameNotFoundException");
        } catch (NameNotFoundException e) {
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    public void renameByName() throws NamingException {
        ctx.bind("java:comp/env/old", "v");
        ctx.rename(ctx.parse("java:comp/env/old"), ctx.parse("java:comp/env/new"));
        assertEquals("v", ctx.lookup("java:comp/env/new"));
    }

    @Test(expected = InvalidNameException.class)
    public void renameWithCompoundNamesFails() throws NamingException {
        ctx.rename(multi(), ctx.parse("java:comp/env/new"));
    }

    @Test(expected = InvalidNameException.class)
    public void renameWithCompoundNewNameFails() throws NamingException {
        ctx.rename(ctx.parse("java:comp/env/new"), multi());
    }

    @Test(expected = NameNotFoundException.class)
    public void lookupCompoundNameFails() throws NamingException {
        ctx.lookup(multi());
    }

    @Test(expected = NameNotFoundException.class)
    public void bindCompoundNameFails() throws NamingException {
        ctx.bind(multi(), "x");
    }

    @Test(expected = NameNotFoundException.class)
    public void unbindCompoundNameFails() throws NamingException {
        ctx.unbind(multi());
    }

    @Test
    public void environmentIsCopiedAndMutable() throws NamingException {
        Hashtable<String, String> env = new Hashtable<String, String>();
        env.put("k", "v");
        ServletInitialContext c = new ServletInitialContext(env);
        env.put("k", "changed");
        assertEquals("v", c.getEnvironment().get("k"));
        assertNull(c.addToEnvironment("k2", "v2"));
        assertEquals("v2", c.addToEnvironment("k2", "v3"));
        assertEquals("v3", c.removeFromEnvironment("k2"));
        assertNull(c.removeFromEnvironment("k2"));
        c.close();
    }

    @Test
    public void unsupportedOperationsReportSo() throws NamingException {
        Name n = ctx.parse("java:comp/env/x");
        int failures = 0;
        try {
            ctx.list(n);
        } catch (OperationNotSupportedException e) {
            failures++;
        }
        try {
            ctx.listBindings(n);
        } catch (OperationNotSupportedException e) {
            failures++;
        }
        try {
            ctx.destroySubcontext(n);
        } catch (OperationNotSupportedException e) {
            failures++;
        }
        try {
            ctx.destroySubcontext("x");
        } catch (OperationNotSupportedException e) {
            failures++;
        }
        try {
            ctx.createSubcontext(n);
        } catch (OperationNotSupportedException e) {
            failures++;
        }
        try {
            ctx.createSubcontext("x");
        } catch (OperationNotSupportedException e) {
            failures++;
        }
        try {
            ctx.composeName(n, n);
        } catch (OperationNotSupportedException e) {
            failures++;
        }
        assertEquals(7, failures);
        assertSame(ctx, ctx.getNameParser(n));
    }
}
