/*
 * ClientDialTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.client;

import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ClientDialTest {

    @Test
    public void testDefaultPort() {
        ClientDial dial = ClientDial.withDefaultPort(443);
        assertEquals(443, dial.getPort());
    }

    @Test
    public void testFluentHostAndPort() {
        ClientDial dial = ClientDial.withDefaultPort(25)
                .host("smtp.example.com")
                .port(587);
        assertEquals("smtp.example.com", dial.getHost());
        assertEquals(587, dial.getPort());
    }

    @Test
    public void testSocketPathClearsHost() {
        ClientDial dial = ClientDial.withDefaultPort(443)
                .host("example.com")
                .socketPath("/var/run/app.sock");
        assertEquals("/var/run/app.sock", dial.getSocketPath());
        assertEquals(-1, dial.getPort());
    }

    @Test
    public void testRequireTarget() {
        ClientDial dial = ClientDial.withDefaultPort(443);
        try {
            dial.requireTarget();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("host"));
        }
        dial.host(InetAddress.getLoopbackAddress());
        dial.requireTarget();
    }

    @Test
    public void testDnsResolverWiring() {
        DnsResolver resolver = new DnsResolver();
        ClientDial dial = ClientDial.withDefaultPort(443)
                .host("example.com")
                .dnsResolver(resolver);
        assertSame(resolver, dial.getDnsResolver());
    }

}
