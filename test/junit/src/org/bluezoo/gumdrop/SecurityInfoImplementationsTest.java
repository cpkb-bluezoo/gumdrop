/*
 * SecurityInfoImplementationsTest.java
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

package org.bluezoo.gumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.tls.Dtls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.Dtls12RecordEngine;
import org.bluezoo.gumdrop.tls.Dtls13HandshakeConfig;
import org.bluezoo.gumdrop.tls.Dtls13RecordEngine;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.Tls12RecordEngine;
import org.bluezoo.gumdrop.tls.TlsRecordEngine;
import org.junit.Test;

/**
 * Tests for the {@link SecurityInfo} implementations backed by record
 * engines that have not completed a handshake, plus
 * {@link NullSecurityInfo}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SecurityInfoImplementationsTest {

    @Test
    public void nullSecurityInfoReportsNothing() {
        SecurityInfo info = NullSecurityInfo.INSTANCE;
        assertNull(info.getProtocol());
        assertNull(info.getCipherSuite());
        assertEquals(-1, info.getKeySize());
        assertNull(info.getPeerCertificates());
        assertNull(info.getLocalCertificates());
        assertNull(info.getApplicationProtocol());
        assertEquals(-1L, info.getHandshakeDurationMs());
        assertFalse(info.isSessionResumed());
    }

    @Test
    public void tls13InfoBeforeHandshake() {
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.CLIENT);
        TlsRecordEngine engine = new TlsRecordEngine(cfg);
        HandshakeSecurityInfo info = new HandshakeSecurityInfo(engine, cfg, 0L);
        assertEquals("TLSv1.3", info.getProtocol());
        assertNull(info.getCipherSuite());
        assertEquals(-1, info.getKeySize());
        assertNull(info.getApplicationProtocol());
        assertNull(info.getLocalCertificates());
        info.getPeerCertificates();
        assertEquals(-1L, info.getHandshakeDurationMs());
        assertFalse(info.isSessionResumed());
        assertEquals("TLS[TLSv1.3, null]", info.toString());
        HandshakeSecurityInfo timed = new HandshakeSecurityInfo(engine, cfg, 1L);
        assertTrue(timed.getHandshakeDurationMs() >= 0);
        HandshakeConfig server = new HandshakeConfig(HandshakeRole.SERVER);
        HandshakeSecurityInfo serverInfo = new HandshakeSecurityInfo(engine, server, 0L);
        assertNull(serverInfo.getLocalCertificates());
    }

    @Test
    public void tls12InfoBeforeHandshake() {
        Tls12HandshakeConfig cfg = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        Tls12RecordEngine engine = new Tls12RecordEngine(cfg);
        Tls12SecurityInfo info = new Tls12SecurityInfo(engine, cfg, 0L);
        assertEquals("TLSv1.2", info.getProtocol());
        assertNull(info.getCipherSuite());
        assertEquals(-1, info.getKeySize());
        assertNull(info.getApplicationProtocol());
        assertNull(info.getLocalCertificates());
        info.getPeerCertificates();
        assertEquals(-1L, info.getHandshakeDurationMs());
        assertFalse(info.isSessionResumed());
        assertNotNull(info.toString());
        Tls12SecurityInfo timed = new Tls12SecurityInfo(engine, cfg, 5L);
        assertTrue(timed.getHandshakeDurationMs() >= 0);
        Tls12HandshakeConfig server = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        assertNull(new Tls12SecurityInfo(engine, server, 0L).getLocalCertificates());
    }

    @Test
    public void dtls12InfoBeforeHandshake() {
        Dtls12HandshakeConfig cfg = new Dtls12HandshakeConfig(new Tls12HandshakeConfig(HandshakeRole.CLIENT));
        Dtls12RecordEngine engine = new Dtls12RecordEngine(cfg.getBase(), 1200);
        Dtls12SecurityInfo info = new Dtls12SecurityInfo(engine, cfg, 0L);
        assertEquals("DTLSv1.2", info.getProtocol());
        assertNull(info.getCipherSuite());
        assertEquals(-1, info.getKeySize());
        assertNull(info.getApplicationProtocol());
        assertNull(info.getLocalCertificates());
        info.getPeerCertificates();
        assertEquals(-1L, info.getHandshakeDurationMs());
        assertFalse(info.isSessionResumed());
        assertTrue(info.toString().startsWith("DTLS["));
        assertTrue(new Dtls12SecurityInfo(engine, cfg, 5L).getHandshakeDurationMs() >= 0);
    }

    @Test
    public void dtls13InfoBeforeHandshake() {
        Dtls13HandshakeConfig cfg = new Dtls13HandshakeConfig(new HandshakeConfig(HandshakeRole.CLIENT));
        Dtls13RecordEngine engine = new Dtls13RecordEngine(cfg, 1200);
        Dtls13SecurityInfo info = new Dtls13SecurityInfo(engine, cfg, 0L);
        assertEquals("DTLSv1.3", info.getProtocol());
        assertNull(info.getCipherSuite());
        assertEquals(-1, info.getKeySize());
        assertNull(info.getApplicationProtocol());
        assertNull(info.getLocalCertificates());
        info.getPeerCertificates();
        assertEquals(-1L, info.getHandshakeDurationMs());
        assertFalse(info.isSessionResumed());
        assertTrue(info.toString().startsWith("DTLS["));
        assertTrue(new Dtls13SecurityInfo(engine, cfg, 5L).getHandshakeDurationMs() >= 0);
    }
}
