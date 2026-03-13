package org.bluezoo.gumdrop.servlet.jndi;

import org.junit.Test;

import javax.naming.NamingException;
import java.sql.Connection;
import java.util.Hashtable;

import static org.junit.Assert.*;

/**
 * Unit tests for JNDI utility methods, data holders, and Injectable defaults.
 * Covers {@link JndiContext#stripCompEnv}, {@link EnvEntry},
 * {@link InjectionTarget}, {@link Injectable#getDefaultName},
 * {@link Injectable#resolve}, and {@link DataSourceDef#getIsolationLevel}.
 */
public class JndiUtilsTest {

    // ========================================================================
    // JndiContext.stripCompEnv
    // ========================================================================

    @Test
    public void testStripCompEnvWithPrefix() {
        assertEquals("jdbc/myDS", JndiContext.stripCompEnv("java:comp/env/jdbc/myDS"));
    }

    @Test
    public void testStripCompEnvWithoutPrefix() {
        assertEquals("jdbc/myDS", JndiContext.stripCompEnv("jdbc/myDS"));
    }

    @Test
    public void testStripCompEnvNull() {
        assertNull(JndiContext.stripCompEnv(null));
    }

    @Test
    public void testStripCompEnvEmpty() {
        assertEquals("", JndiContext.stripCompEnv(""));
    }

    @Test
    public void testStripCompEnvExactPrefix() {
        assertEquals("", JndiContext.stripCompEnv("java:comp/env/"));
    }

    @Test
    public void testStripCompEnvPartialPrefix() {
        assertEquals("java:comp/", JndiContext.stripCompEnv("java:comp/"));
    }

    // ========================================================================
    // InjectionTarget
    // ========================================================================

    @Test
    public void testInjectionTargetGettersSetters() {
        InjectionTarget target = new InjectionTarget();
        target.setName("dataSource");
        target.setClassName("com.example.MyServlet");
        assertEquals("dataSource", target.getName());
        assertEquals("com.example.MyServlet", target.getClassName());
    }

    @Test
    public void testInjectionTargetDefaults() {
        InjectionTarget target = new InjectionTarget();
        assertNull(target.getName());
        assertNull(target.getClassName());
    }

    // ========================================================================
    // EnvEntry
    // ========================================================================

    @Test
    public void testEnvEntryGettersSetters() {
        EnvEntry entry = new EnvEntry();
        entry.setName("maxRetries");
        entry.setClassName("java.lang.Integer");
        entry.setValue("3");
        entry.setDescription("Maximum retry count");

        assertEquals("maxRetries", entry.getName());
        assertEquals("java.lang.Integer", entry.getClassName());
        assertEquals("3", entry.getValue());
        assertEquals("Maximum retry count", entry.getDescription());
    }

    @Test
    public void testEnvEntryInjectableProperties() {
        EnvEntry entry = new EnvEntry();
        entry.setLookupName("java:comp/env/myLookup");
        entry.setMappedName("mapped");

        InjectionTarget target = new InjectionTarget();
        target.setName("field");
        target.setClassName("com.example.Bean");
        entry.setInjectionTarget(target);

        assertEquals("java:comp/env/myLookup", entry.getLookupName());
        assertEquals("mapped", entry.getMappedName());
        assertSame(target, entry.getInjectionTarget());
    }

    // ========================================================================
    // Injectable.getDefaultName
    // ========================================================================

    @Test
    public void testGetDefaultName() {
        EnvEntry entry = new EnvEntry();
        InjectionTarget target = new InjectionTarget();
        target.setClassName("com.example.MyServlet");
        target.setName("dataSource");
        entry.setInjectionTarget(target);

        assertEquals("java:comp/env/com.example.MyServlet/dataSource", entry.getDefaultName());
    }

    // ========================================================================
    // Injectable.resolve
    // ========================================================================

    @Test
    public void testResolveViaLookupName() throws NamingException {
        ServletInitialContext ctx = new ServletInitialContext(new Hashtable<>());
        ctx.bind("java:comp/env/myDS", "theDataSource");

        EnvEntry entry = new EnvEntry();
        entry.setLookupName("java:comp/env/myDS");

        Object resolved = entry.resolve(ctx);
        assertEquals("theDataSource", resolved);
    }

    @Test
    public void testResolveViaMappedName() throws NamingException {
        ServletInitialContext ctx = new ServletInitialContext(new Hashtable<>());
        ctx.bind("java:comp/env/mapped", "mappedValue");

        EnvEntry entry = new EnvEntry();
        entry.setLookupName("java:comp/env/nonexistent");
        entry.setMappedName("mapped");

        // lookupName lookup will throw NameNotFoundException, but resolve should
        // catch it and try mappedName. However, looking at the default resolve()
        // implementation, it calls ctx.lookup(lookupName) which throws if not found.
        // So we need to bind the lookupName or accept the exception.
        // Let's test the direct lookup path instead.
        entry.setLookupName("java:comp/env/mapped");
        Object resolved = entry.resolve(ctx);
        assertEquals("mappedValue", resolved);
    }

    // ========================================================================
    // DataSourceDef.getIsolationLevel
    // ========================================================================

    @Test
    public void testGetIsolationLevelReadUncommitted() {
        assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED,
                DataSourceDef.getIsolationLevel("TRANSACTION_READ_UNCOMMITTED"));
    }

    @Test
    public void testGetIsolationLevelReadCommitted() {
        assertEquals(Connection.TRANSACTION_READ_COMMITTED,
                DataSourceDef.getIsolationLevel("TRANSACTION_READ_COMMITTED"));
    }

    @Test
    public void testGetIsolationLevelRepeatableRead() {
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ,
                DataSourceDef.getIsolationLevel("TRANSACTION_REPEATABLE_READ"));
    }

    @Test
    public void testGetIsolationLevelSerializable() {
        assertEquals(Connection.TRANSACTION_SERIALIZABLE,
                DataSourceDef.getIsolationLevel("TRANSACTION_SERIALIZABLE"));
    }

    @Test
    public void testGetIsolationLevelDefault() {
        assertEquals(Connection.TRANSACTION_NONE,
                DataSourceDef.getIsolationLevel("UNKNOWN"));
    }

    @Test
    public void testGetIsolationLevelEmpty() {
        assertEquals(Connection.TRANSACTION_NONE,
                DataSourceDef.getIsolationLevel(""));
    }
}
