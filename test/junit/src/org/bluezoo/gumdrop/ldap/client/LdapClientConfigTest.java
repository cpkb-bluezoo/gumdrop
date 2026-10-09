/*
 * LdapClientConfigTest.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.tls.TlsConfig;

import static org.junit.Assert.*;

/**
 * Tests for the configuration surface of {@link LdapClient}: constructors,
 * fluent setters and bean-style setters. No connection is attempted.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LdapClientConfigTest {

    @Test
    public void hostPortConstructor() {
        LdapClient c = new LdapClient("ldap.example.org", 6380);
        assertFalse(c.isOpen());
    }

    @Test
    public void defaultPortWhenUnset() {
        LdapClient c = new LdapClient();
    }

    @Test
    public void addressConstructors() {
        InetAddress lo = InetAddress.getLoopbackAddress();
        LdapClient c = new LdapClient(lo, 7000);
        LdapClient d = new LdapClient(null, lo, 7001);
        LdapClient e = new LdapClient(null, "localhost", 7002);
    }

    @Test
    public void socketPathConstructors() {
        LdapClient c = new LdapClient("/tmp/ldap.sock");
        LdapClient d = new LdapClient(null, "/tmp/other.sock");
    }

    @Test
    public void fluentSettersReturnSameInstance() {
        LdapClient c = new LdapClient();
        Path p = Paths.get("keystore.p12");
        assertSame(c, c.host("h"));
        assertSame(c, c.host(InetAddress.getLoopbackAddress()));
        assertSame(c, c.port(1234));
        assertSame(c, c.socketPath("/tmp/s"));
        assertSame(c, c.selectorLoop(null));
        assertSame(c, c.dnsResolver(null));
        assertSame(c, c.secure(true));
        assertSame(c, c.tls(new TlsConfig()));
    }

    @Test
    public void closeBeforeConnectIsSafe() {
        LdapClient c = new LdapClient();
        c.close();
        assertFalse(c.isOpen());
    }
}
