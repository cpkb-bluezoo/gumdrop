/*
 * ImapListenerCoverageTest.java
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

package org.bluezoo.gumdrop.imap;

import java.net.InetAddress;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.imap.server.ClientConnected;
import org.bluezoo.gumdrop.imap.server.DefaultIMAPHandler;
import org.bluezoo.gumdrop.imap.server.ImapServer;
import org.bluezoo.gumdrop.imap.server.ImapServerSessionProvider;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.quota.RoleBasedQuotaManager;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.TlsConfig;

import static org.junit.Assert.*;

/**
 * Covers {@link ImapListener}: configuration accessors, start defaults,
 * capability advertisement for every enable flag and session state, and
 * application session opening with failing providers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapListenerCoverageTest {

    private static final class MechRealm implements Realm {
        private final Set<SaslMechanism> mechs;

        MechRealm(SaslMechanism... m) {
            Set<SaslMechanism> set = EnumSet.noneOf(SaslMechanism.class);
            for (int i = 0; i < m.length; i++) {
                set.add(m[i]);
            }
            this.mechs = Collections.unmodifiableSet(set);
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return mechs;
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return false;
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            return null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }
    }

    @Test
    public void testDescriptionAndFluentSetters() throws Exception {
        ImapListener l = new ImapListener();
        assertEquals("imap", l.getDescription());
        assertSame(l, l.port(1143));
        assertEquals(1143, l.getPort());
        assertSame(l, l.bindWildcard());
        assertSame(l, l.addresses(new InetAddress[0]));
        assertSame(l, l.secure(true));
        assertEquals("imaps", l.getDescription());
        assertSame(l, l.tls(TlsConfig.credentials(
                TestCertificates.ec256().credentials())));
        l.setPort(993);
        assertEquals(993, l.getPort());
    }

    @Test
    public void testAccessors() {
        ImapListener l = new ImapListener();
        assertNull(l.getRealm());
        assertNull(l.getGSSAPIServer());
        assertNull(l.getMailboxFactory());
        assertNull(l.getQuotaManager());
        assertNull(l.getServer());
        assertNull(l.getSessionProvider());
        assertNull(l.getMetrics());
        assertTrue(l.isEnableIDLE());
        assertTrue(l.isEnableNAMESPACE());
        assertTrue(l.isEnableQUOTA());
        assertTrue(l.isEnableMOVE());
        assertTrue(l.isEnableCOMPRESS());
        assertTrue(l.isEnableUTF8ACCEPT());
        assertTrue(l.isEnableSORT());
        assertTrue(l.isEnableCONDSTORE());
        assertTrue(l.isEnableQRESYNC());
        assertTrue(l.isEnableOBJECTID());
        assertTrue(l.isEnableNOTIFY());
        assertTrue(l.isEnableMETADATA());
        assertFalse(l.isAllowPlaintextLogin());
        assertEquals(60000L, l.getLoginTimeoutMs());
        assertEquals(300000L, l.getCommandTimeoutMs());
        assertEquals(8192, l.getMaxLineLength());
        assertEquals(25 * 1024 * 1024, l.getMaxLiteralSize());

        Realm realm = new MechRealm();
        l.setRealm(realm);
        assertSame(realm, l.getRealm());
        l.setGSSAPIServer(null);
        assertNull(l.getGSSAPIServer());
        MailboxFactory f = new MailboxFactory() {
            @Override
            public MailboxStore createStore() {
                return null;
            }
        };
        l.setMailboxFactory(f);
        assertSame(f, l.getMailboxFactory());
        RoleBasedQuotaManager qm = new RoleBasedQuotaManager();
        l.setQuotaManager(qm);
        assertSame(qm, l.getQuotaManager());
        l.setLoginTimeoutMs(1L);
        l.setCommandTimeoutMs(2L);
        l.setEnableIDLE(false);
        l.setEnableNAMESPACE(false);
        l.setEnableQUOTA(false);
        l.setEnableMOVE(false);
        l.setEnableCOMPRESS(false);
        l.setEnableUTF8ACCEPT(false);
        l.setEnableSORT(false);
        l.setEnableCONDSTORE(false);
        l.setEnableQRESYNC(false);
        l.setEnableOBJECTID(false);
        l.setEnableNOTIFY(false);
        l.setEnableMETADATA(false);
        l.setAllowPlaintextLogin(true);
        l.setMaxLineLength(10);
        l.setMaxLiteralSize(20);
        assertEquals(1L, l.getLoginTimeoutMs());
        assertEquals(2L, l.getCommandTimeoutMs());
        assertFalse(l.isEnableIDLE());
        assertFalse(l.isEnableNAMESPACE());
        assertFalse(l.isEnableQUOTA());
        assertFalse(l.isEnableMOVE());
        assertFalse(l.isEnableCOMPRESS());
        assertFalse(l.isEnableUTF8ACCEPT());
        assertFalse(l.isEnableSORT());
        assertFalse(l.isEnableCONDSTORE());
        assertFalse(l.isEnableQRESYNC());
        assertFalse(l.isEnableOBJECTID());
        assertFalse(l.isEnableNOTIFY());
        assertFalse(l.isEnableMETADATA());
        assertTrue(l.isAllowPlaintextLogin());
        assertEquals(10, l.getMaxLineLength());
        assertEquals(20, l.getMaxLiteralSize());
        Map<String, String> ids = new HashMap<String, String>();
        ids.put("name", "x");
        l.setServerIdFields(ids);
        assertEquals("x", l.getServerIdFields().get("name"));
    }

    @Test
    public void testStartDefaultsPlainAndNoRealm() {
        ImapListener l = new ImapListener();
        l.start();
        assertEquals(143, l.getPort());
        assertEquals(30 * 60 * 1000L, l.getIdleTimeoutMs());
        assertNull(l.getMetrics());
        l.stop();
    }

    @Test
    public void testStartSecureDefaultsToImapsPort() throws Exception {
        ImapListener l = new ImapListener();
        l.secure(true);
        l.tls(TlsConfig.credentials(TestCertificates.ec256().credentials()));
        l.start();
        assertEquals(993, l.getPort());
        assertTrue(l.getCapabilities(false, false).contains("STARTTLS"));
    }

    @Test
    public void testStartKeepsExplicitPortAndIdleTimeout() {
        ImapListener l = new ImapListener();
        l.setRealm(new MechRealm(SaslMechanism.PLAIN));
        l.port(2143);
        l.setIdleTimeoutMs(12345L);
        l.start();
        assertEquals(2143, l.getPort());
        assertEquals(12345L, l.getIdleTimeoutMs());
    }

    @Test
    public void testStartWithMetricsEnabled() {
        ImapListener l = new ImapListener();
        TelemetryConfig tc = new TelemetryConfig();
        tc.setMetricsEnabled(true);
        l.setTelemetryConfig(tc);
        l.start();
        assertNotNull(l.getMetrics());
    }

    @Test
    public void testUnauthenticatedCapabilities() {
        ImapListener l = new ImapListener();
        l.setRealm(new MechRealm(SaslMechanism.PLAIN,
                SaslMechanism.CRAM_MD5));
        String clear = l.getCapabilities(false, false);
        assertTrue(clear, clear.contains("AUTH=CRAM-MD5"));
        assertFalse(clear, clear.contains("AUTH=PLAIN"));
        assertTrue(clear, clear.contains("LOGINDISABLED"));
        assertFalse(clear, clear.contains("STARTTLS"));
        String tls = l.getCapabilities(false, true);
        assertTrue(tls, tls.contains("AUTH=PLAIN"));
        assertFalse(tls, tls.contains("LOGINDISABLED"));
        l.setAllowPlaintextLogin(true);
        assertFalse(l.getCapabilities(false, false).contains("LOGINDISABLED"));
        l.setRealm(null);
        assertFalse(l.getCapabilities(false, false).contains("AUTH="));
    }

    @Test
    public void testAuthenticatedCapabilitiesAllDisabled() {
        ImapListener l = new ImapListener();
        l.setEnableIDLE(false);
        l.setEnableNAMESPACE(false);
        l.setEnableQUOTA(false);
        l.setEnableMOVE(false);
        l.setEnableCOMPRESS(false);
        l.setEnableUTF8ACCEPT(false);
        l.setEnableSORT(false);
        l.setEnableCONDSTORE(false);
        l.setEnableQRESYNC(false);
        l.setEnableOBJECTID(false);
        l.setEnableNOTIFY(false);
        l.setEnableMETADATA(false);
        String caps = l.getCapabilities(true, true, false);
        String[] absent = {"IDLE", "NAMESPACE", "QUOTA", "MOVE", "CONDSTORE",
                "QRESYNC", "COMPRESS=DEFLATE", "UTF8=ACCEPT", "SORT",
                "THREAD=", "OBJECTID", "NOTIFY", "METADATA"};
        for (int i = 0; i < absent.length; i++) {
            assertFalse(absent[i] + " in " + caps, caps.contains(absent[i]));
        }
        assertTrue(caps, caps.contains("UNSELECT"));
        assertTrue(caps, caps.contains("LITERAL-"));
    }

    @Test
    public void testAuthenticatedCapabilitiesAllEnabled() {
        ImapListener l = new ImapListener();
        String caps = l.getCapabilities(true, true, false);
        String[] present = {"IDLE", "NAMESPACE", "QUOTA", "MOVE", "CONDSTORE",
                "QRESYNC", "COMPRESS=DEFLATE", "UTF8=ACCEPT", "SORT",
                "THREAD=REFERENCES", "OBJECTID", "NOTIFY", "METADATA"};
        for (int i = 0; i < present.length; i++) {
            assertTrue(present[i] + " in " + caps, caps.contains(present[i]));
        }
        assertFalse(l.getCapabilities(true, true, true)
                .contains("COMPRESS=DEFLATE"));
    }

    @Test
    public void testOpenApplicationSessionPrefersSessionProvider() {
        final ClientConnected expected = new DefaultIMAPHandler();
        ImapListener l = new ImapListener();
        l.setSessionProvider(new ImapServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return expected;
            }
        });
        assertSame(expected, l.openApplicationSession());
    }

    @Test
    public void testOpenApplicationSessionFallsBackWhenProviderThrows() {
        final ClientConnected fromServer = new DefaultIMAPHandler();
        ImapListener l = new ImapListener();
        l.setSessionProvider(new ImapServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                throw new IllegalStateException("provider down");
            }
        });
        ImapServer server = new ImapServer() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return fromServer;
            }
        };
        l.setServer(server);
        assertSame(server, l.getServer());
        assertSame(fromServer, l.openApplicationSession());
    }

    @Test
    public void testOpenApplicationSessionNullWhenEverythingFails() {
        ImapListener l = new ImapListener();
        assertNull(l.openApplicationSession());
        l.setSessionProvider(new ImapServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                throw new IllegalStateException("provider down");
            }
        });
        l.setServer(new ImapServer() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                throw new IllegalStateException("server down");
            }
        });
        assertNull(l.openApplicationSession());
    }

    @Test
    public void testSessionProviderFluentAndCreateHandler() {
        ImapListener l = new ImapListener();
        ImapServerSessionProvider p = new ImapServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return null;
            }
        };
        assertSame(l, l.sessionProvider(p));
        assertSame(p, l.getSessionProvider());
        ProtocolHandler h = l.createHandler();
        assertTrue(h instanceof ImapProtocolHandler);
    }
}
