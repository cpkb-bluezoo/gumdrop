package org.bluezoo.gumdrop.socks.client;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SOCKSClientConfig}.
 */
public class SOCKSClientConfigTest {

    @Test
    public void testDefaultConfig() {
        SOCKSClientConfig config = new SOCKSClientConfig();

        assertEquals(SOCKSClientConfig.Version.SOCKS5,
                     config.getVersion());
        assertNull(config.getUsername());
        assertNull(config.getPassword());
        assertEquals(30_000, config.getHandshakeTimeoutMs());
        assertFalse(config.hasCredentials());
    }

    @Test
    public void testCredentialConstructor() {
        SOCKSClientConfig config =
                new SOCKSClientConfig("alice", "secret123");

        assertEquals("alice", config.getUsername());
        assertEquals("secret123", config.getPassword());
        assertTrue(config.hasCredentials());
    }

    @Test
    public void testSetVersion() {
        SOCKSClientConfig config = new SOCKSClientConfig();
        SOCKSClientConfig returned =
                config.setVersion(SOCKSClientConfig.Version.SOCKS4);

        assertSame(config, returned);
        assertEquals(SOCKSClientConfig.Version.SOCKS4,
                     config.getVersion());
    }

    @Test
    public void testSetVersionAuto() {
        SOCKSClientConfig config = new SOCKSClientConfig();
        config.setVersion(SOCKSClientConfig.Version.AUTO);
        assertEquals(SOCKSClientConfig.Version.AUTO,
                     config.getVersion());
    }

    @Test
    public void testSetUsername() {
        SOCKSClientConfig config = new SOCKSClientConfig();
        SOCKSClientConfig returned = config.setUsername("bob");

        assertSame(config, returned);
        assertEquals("bob", config.getUsername());
        assertFalse(config.hasCredentials());
    }

    @Test
    public void testSetPassword() {
        SOCKSClientConfig config = new SOCKSClientConfig();
        SOCKSClientConfig returned = config.setPassword("pass");

        assertSame(config, returned);
        assertEquals("pass", config.getPassword());
        assertFalse(config.hasCredentials());
    }

    @Test
    public void testHasCredentialsRequiresBoth() {
        SOCKSClientConfig config = new SOCKSClientConfig();
        config.setUsername("user");
        assertFalse(config.hasCredentials());

        config.setPassword("pass");
        assertTrue(config.hasCredentials());
    }

    @Test
    public void testHasCredentialsNullUsername() {
        SOCKSClientConfig config = new SOCKSClientConfig();
        config.setPassword("pass");
        assertFalse(config.hasCredentials());
    }

    @Test
    public void testSetHandshakeTimeout() {
        SOCKSClientConfig config = new SOCKSClientConfig();
        SOCKSClientConfig returned =
                config.setHandshakeTimeoutMs(60_000);

        assertSame(config, returned);
        assertEquals(60_000, config.getHandshakeTimeoutMs());
    }

    @Test
    public void testFluentChaining() {
        SOCKSClientConfig config = new SOCKSClientConfig()
                .setVersion(SOCKSClientConfig.Version.SOCKS5)
                .setUsername("u")
                .setPassword("p")
                .setHandshakeTimeoutMs(5000);

        assertEquals(SOCKSClientConfig.Version.SOCKS5,
                     config.getVersion());
        assertEquals("u", config.getUsername());
        assertEquals("p", config.getPassword());
        assertEquals(5000, config.getHandshakeTimeoutMs());
        assertTrue(config.hasCredentials());
    }

    @Test
    public void testVersionEnum() {
        SOCKSClientConfig.Version[] values =
                SOCKSClientConfig.Version.values();
        assertEquals(3, values.length);
        assertEquals(SOCKSClientConfig.Version.AUTO,
                     SOCKSClientConfig.Version.valueOf("AUTO"));
        assertEquals(SOCKSClientConfig.Version.SOCKS4,
                     SOCKSClientConfig.Version.valueOf("SOCKS4"));
        assertEquals(SOCKSClientConfig.Version.SOCKS5,
                     SOCKSClientConfig.Version.valueOf("SOCKS5"));
    }
}
