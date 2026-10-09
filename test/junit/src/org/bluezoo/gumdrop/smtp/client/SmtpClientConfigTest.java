/*
 * SmtpClientConfigTest.java
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

package org.bluezoo.gumdrop.smtp.client;

import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.tls.KeystoreFormat;

import static org.junit.Assert.*;

/**
 * Tests for the configuration surface of {@link SmtpClient} and its
 * deprecated builder; no connection is attempted.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@SuppressWarnings("deprecation")
public class SmtpClientConfigTest {

    @Test
    public void testConstructors() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        SelectorLoop loop = new InlineSelectorLoop();
        assertNotNull(new SmtpClient());
        assertNotNull(new SmtpClient("mx.example.com", 25));
        assertNotNull(new SmtpClient(loop, "mx.example.com", 587));
        assertNotNull(new SmtpClient(loopback, 25));
        assertNotNull(new SmtpClient(loop, loopback, 25));
        assertNotNull(new SmtpClient("/tmp/smtp.sock"));
        assertNotNull(new SmtpClient(loop, "/tmp/smtp.sock"));
    }

    @Test
    public void testFluentConfiguration() throws Exception {
        SmtpClient client = new SmtpClient();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        DnsResolver resolver = new DnsResolver();
        Path keystore = Paths.get("keystore.p12");
        assertSame(client, client.host("mx.example.com"));
        assertSame(client, client.host(loopback));
        assertSame(client, client.port(2525));
        assertSame(client, client.socketPath("/tmp/smtp.sock"));
        assertSame(client, client.selectorLoop(null));
        assertSame(client, client.secure(true));
        assertSame(client, client.tls(new TlsConfig()));
        assertSame(client, client.daneResolver(resolver));
        assertSame(client, client.dnsResolver(resolver));
    }

    @Test
    public void testSetters() {
        SmtpClient client = new SmtpClient("mx.example.com", 25);
        DnsResolver resolver = new DnsResolver();
        client.secure(true);
        client.tls(new TlsConfig());
        client.daneResolver(resolver);
        client.dnsResolver(resolver);
        client.tls(new TlsConfig());
    }

    @Test
    public void testNotOpenBeforeConnect() {
        SmtpClient client = new SmtpClient("mx.example.com", 25);
        assertFalse(client.isOpen());
        client.close();
        assertFalse(client.isOpen());
    }

}
