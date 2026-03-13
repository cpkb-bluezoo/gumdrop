package org.bluezoo.gumdrop.servlet.jndi;

import org.junit.Before;
import org.junit.Test;

import javax.naming.InvalidNameException;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;
import java.util.Hashtable;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ServletInitialContext}.
 */
public class ServletInitialContextTest {

    private ServletInitialContext ctx;

    @Before
    public void setUp() {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("test.key", "test.value");
        ctx = new ServletInitialContext(env);
    }

    // ── lookup ──

    @Test
    public void testLookupEmptyStringReturnsSelf() throws NamingException {
        assertSame(ctx, ctx.lookup(""));
    }

    @Test
    public void testLookupBoundValue() throws NamingException {
        ctx.bind("java:comp/env/myValue", "hello");
        assertEquals("hello", ctx.lookup("java:comp/env/myValue"));
    }

    @Test(expected = NameNotFoundException.class)
    public void testLookupUnboundName() throws NamingException {
        ctx.lookup("java:comp/env/missing");
    }

    @Test(expected = NameNotFoundException.class)
    public void testLookupNull() throws NamingException {
        ctx.lookup((String) null);
    }

    @Test(expected = NameNotFoundException.class)
    public void testLookupWithoutPrefix() throws NamingException {
        ctx.lookup("myValue");
    }

    @Test
    public void testLookupViaName() throws NamingException {
        ctx.bind("java:comp/env/item", Integer.valueOf(42));
        Name name = ctx.parse("java:comp/env/item");
        assertEquals(Integer.valueOf(42), ctx.lookup(name));
    }

    // ── bind ──

    @Test
    public void testBindAndLookup() throws NamingException {
        ctx.bind("java:comp/env/ds", "dataSource");
        assertEquals("dataSource", ctx.lookup("java:comp/env/ds"));
    }

    @Test
    public void testBindWithClassName() throws NamingException {
        ctx.bind("java:comp/env/num", "java.lang.Integer", Integer.valueOf(99));
        assertEquals(Integer.valueOf(99), ctx.lookup("java:comp/env/num"));
    }

    @Test(expected = InvalidNameException.class)
    public void testBindInvalidName() throws NamingException {
        ctx.bind("invalidName", "value");
    }

    @Test(expected = InvalidNameException.class)
    public void testBindNullName() throws NamingException {
        ctx.bind((String) null, "value");
    }

    @Test
    public void testBindOverwrites() throws NamingException {
        ctx.bind("java:comp/env/x", "first");
        ctx.bind("java:comp/env/x", "second");
        assertEquals("second", ctx.lookup("java:comp/env/x"));
    }

    // ── rebind ──

    @Test
    public void testRebind() throws NamingException {
        ctx.bind("java:comp/env/a", "old");
        ctx.rebind("java:comp/env/a", "new");
        assertEquals("new", ctx.lookup("java:comp/env/a"));
    }

    // ── unbind ──

    @Test
    public void testUnbind() throws NamingException {
        ctx.bind("java:comp/env/remove", "value");
        ctx.unbind("java:comp/env/remove");
        try {
            ctx.lookup("java:comp/env/remove");
            fail("Expected NameNotFoundException");
        } catch (NameNotFoundException e) {
            // expected
        }
    }

    @Test(expected = NameNotFoundException.class)
    public void testUnbindNonExistent() throws NamingException {
        ctx.unbind("java:comp/env/nope");
    }

    @Test(expected = InvalidNameException.class)
    public void testUnbindInvalidName() throws NamingException {
        ctx.unbind("badname");
    }

    // ── rename ──

    @Test
    public void testRename() throws NamingException {
        ctx.bind("java:comp/env/old", "value");
        ctx.rename("java:comp/env/old", "java:comp/env/new");
        assertEquals("value", ctx.lookup("java:comp/env/new"));
        try {
            ctx.lookup("java:comp/env/old");
            fail("Expected NameNotFoundException");
        } catch (NameNotFoundException e) {
            // expected
        }
    }

    @Test(expected = InvalidNameException.class)
    public void testRenameInvalidOldName() throws NamingException {
        ctx.rename("bad", "java:comp/env/new");
    }

    @Test(expected = InvalidNameException.class)
    public void testRenameInvalidNewName() throws NamingException {
        ctx.bind("java:comp/env/x", "v");
        ctx.rename("java:comp/env/x", "bad");
    }

    @Test(expected = NameNotFoundException.class)
    public void testRenameNonExistent() throws NamingException {
        ctx.rename("java:comp/env/missing", "java:comp/env/new");
    }

    // ── environment ──

    @Test
    public void testGetEnvironment() throws NamingException {
        Hashtable env = ctx.getEnvironment();
        assertEquals("test.value", env.get("test.key"));
    }

    @Test
    public void testGetEnvironmentIsClone() throws NamingException {
        Hashtable env = ctx.getEnvironment();
        env.put("new.key", "new.value");
        assertNull(ctx.getEnvironment().get("new.key"));
    }

    @Test
    public void testAddToEnvironment() throws NamingException {
        ctx.addToEnvironment("added", "yes");
        assertEquals("yes", ctx.getEnvironment().get("added"));
    }

    @Test
    public void testRemoveFromEnvironment() throws NamingException {
        ctx.removeFromEnvironment("test.key");
        assertNull(ctx.getEnvironment().get("test.key"));
    }

    // ── NameParser ──

    @Test
    public void testGetNameParser() throws NamingException {
        assertSame(ctx, ctx.getNameParser(""));
        assertSame(ctx, ctx.getNameParser(ctx.parse("")));
    }

    @Test
    public void testParse() throws NamingException {
        Name name = ctx.parse("java:comp/env/test");
        assertNotNull(name);
        assertEquals(1, name.size());
    }

    // ── lookupLink ──

    @Test
    public void testLookupLink() throws NamingException {
        ctx.bind("java:comp/env/link", "linkValue");
        assertEquals("linkValue", ctx.lookupLink("java:comp/env/link"));
    }

    // ── unsupported operations ──

    @Test(expected = OperationNotSupportedException.class)
    public void testListNotSupported() throws NamingException {
        ctx.list("x");
    }

    @Test(expected = OperationNotSupportedException.class)
    public void testListBindingsNotSupported() throws NamingException {
        ctx.listBindings("x");
    }

    @Test(expected = OperationNotSupportedException.class)
    public void testCreateSubcontextNotSupported() throws NamingException {
        ctx.createSubcontext("x");
    }

    @Test(expected = OperationNotSupportedException.class)
    public void testDestroySubcontextNotSupported() throws NamingException {
        ctx.destroySubcontext("x");
    }

    @Test(expected = OperationNotSupportedException.class)
    public void testComposeNameNotSupported() throws NamingException {
        ctx.composeName("a", "b");
    }

    @Test(expected = OperationNotSupportedException.class)
    public void testGetNameInNamespaceNotSupported() throws NamingException {
        ctx.getNameInNamespace();
    }

    // ── close ──

    @Test
    public void testCloseDoesNotThrow() throws NamingException {
        ctx.close();
    }
}
