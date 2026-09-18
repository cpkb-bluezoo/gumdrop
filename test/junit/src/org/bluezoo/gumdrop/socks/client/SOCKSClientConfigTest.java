package org.bluezoo.gumdrop.socks.client;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SocksClientConfig}.
 */
public class SOCKSClientConfigTest {

    @Test
    public void testDefaultConfig() {
        SocksClientConfig config = new SocksClientConfig();

        assertEquals(SocksClientConfig.Version.SOCKS5,
                     config.getVersion());
        assertNull(config.getUsername());
        assertNull(config.getPassword());
        assertEquals(30_000, config.getHandshakeTimeoutMs());
        assertFalse(config.hasCredentials());
    }

    @Test
    public void testCredentialConstructor() {
        SocksClientConfig config =
                new SocksClientConfig("alice", "secret123");

        assertEquals("alice", config.getUsername());
        assertEquals("secret123", config.getPassword());
        assertTrue(config.hasCredentials());
    }

    @Test
    public void testSetVersion() {
        SocksClientConfig config = new SocksClientConfig();
        SocksClientConfig returned =
                config.setVersion(SocksClientConfig.Version.SOCKS4);

        assertSame(config, returned);
        assertEquals(SocksClientConfig.Version.SOCKS4,
                     config.getVersion());
    }

    @Test
    public void testSetVersionAuto() {
        SocksClientConfig config = new SocksClientConfig();
        config.setVersion(SocksClientConfig.Version.AUTO);
        assertEquals(SocksClientConfig.Version.AUTO,
                     config.getVersion());
    }

    @Test
    public void testSetUsername() {
        SocksClientConfig config = new SocksClientConfig();
        SocksClientConfig returned = config.setUsername("bob");

        assertSame(config, returned);
        assertEquals("bob", config.getUsername());
        assertFalse(config.hasCredentials());
    }

    @Test
    public void testSetPassword() {
        SocksClientConfig config = new SocksClientConfig();
        SocksClientConfig returned = config.setPassword("pass");

        assertSame(config, returned);
        assertEquals("pass", config.getPassword());
        assertFalse(config.hasCredentials());
    }

    @Test
    public void testHasCredentialsRequiresBoth() {
        SocksClientConfig config = new SocksClientConfig();
        config.setUsername("user");
        assertFalse(config.hasCredentials());

        config.setPassword("pass");
        assertTrue(config.hasCredentials());
    }

    @Test
    public void testHasCredentialsNullUsername() {
        SocksClientConfig config = new SocksClientConfig();
        config.setPassword("pass");
        assertFalse(config.hasCredentials());
    }

    @Test
    public void testSetHandshakeTimeout() {
        SocksClientConfig config = new SocksClientConfig();
        SocksClientConfig returned =
                config.setHandshakeTimeoutMs(60_000);

        assertSame(config, returned);
        assertEquals(60_000, config.getHandshakeTimeoutMs());
    }

    @Test
    public void testFluentChaining() {
        SocksClientConfig config = new SocksClientConfig()
                .setVersion(SocksClientConfig.Version.SOCKS5)
                .setUsername("u")
                .setPassword("p")
                .setHandshakeTimeoutMs(5000);

        assertEquals(SocksClientConfig.Version.SOCKS5,
                     config.getVersion());
        assertEquals("u", config.getUsername());
        assertEquals("p", config.getPassword());
        assertEquals(5000, config.getHandshakeTimeoutMs());
        assertTrue(config.hasCredentials());
    }

    @Test
    public void testVersionEnum() {
        SocksClientConfig.Version[] values =
                SocksClientConfig.Version.values();
        assertEquals(3, values.length);
        assertEquals(SocksClientConfig.Version.AUTO,
                     SocksClientConfig.Version.valueOf("AUTO"));
        assertEquals(SocksClientConfig.Version.SOCKS4,
                     SocksClientConfig.Version.valueOf("SOCKS4"));
        assertEquals(SocksClientConfig.Version.SOCKS5,
                     SocksClientConfig.Version.valueOf("SOCKS5"));
    }
}
