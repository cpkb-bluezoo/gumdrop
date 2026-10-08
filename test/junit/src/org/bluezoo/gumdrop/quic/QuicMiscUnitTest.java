/*
 * ClientConnectApplyTest.java
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

package org.bluezoo.gumdrop.quic;

import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.quic.packet.QuicVersion;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.tls.SessionTicket;
import org.junit.After;
import org.junit.Test;

/**
 * Small value-class and configuration tests for the top-level QUIC package:
 * exceptions, session ticket cache, factory setters and stream endpoint
 * state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicMiscUnitTest {

    @After
    public void clearTickets() {
        SessionTicketCache.clear();
    }

    @Test
    public void closeExceptionDescribesTransportErrors() {
        QuicConnectionCloseException e = new QuicConnectionCloseException(false, 0x3, "too much");
        assertFalse(e.isApplicationError());
        assertEquals(3L, e.getErrorCode());
        assertEquals("too much", e.getReason());
        assertTrue(e.getMessage(), e.getMessage().contains("FLOW_CONTROL_ERROR"));
        assertTrue(e.getMessage().contains("too much"));
    }

    @Test
    public void closeExceptionDescribesApplicationErrors() {
        QuicConnectionCloseException e = new QuicConnectionCloseException(true, 0x10c, null);
        assertTrue(e.isApplicationError());
        assertNull(e.getReason());
        assertTrue(e.getMessage(), e.getMessage().contains("0x10c"));
        e = new QuicConnectionCloseException(true, 5, "");
        assertFalse(e.getMessage().contains(": "));
    }

    @Test
    public void everyTransportErrorHasAName() {
        String[] names = {"NO_ERROR", "INTERNAL_ERROR", "CONNECTION_REFUSED", "FLOW_CONTROL_ERROR",
            "STREAM_LIMIT_ERROR", "STREAM_STATE_ERROR", "FINAL_SIZE_ERROR", "FRAME_ENCODING_ERROR",
            "TRANSPORT_PARAMETER_ERROR", "CONNECTION_ID_LIMIT_ERROR", "PROTOCOL_VIOLATION", "INVALID_TOKEN",
            "APPLICATION_ERROR", "CRYPTO_BUFFER_EXCEEDED", "KEY_UPDATE_ERROR", "AEAD_LIMIT_REACHED",
            "NO_VIABLE_PATH"};
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], QuicConnectionCloseException.transportErrorToString(i));
        }
        assertEquals("CRYPTO_ERROR(40)", QuicConnectionCloseException.transportErrorToString(0x128));
        assertEquals("UNKNOWN(99)", QuicConnectionCloseException.transportErrorToString(99));
    }

    @Test
    public void otherExceptionsConstruct() {
        assertNotNull(new QuicStatelessResetException().getMessage());
        assertNotNull(new QuicVersionNegotiationException().getMessage());
    }

    @Test
    public void sessionTicketCacheStoresReplacesAndExpires() {
        SessionTicket ticket = new SessionTicket(new byte[] {1}, 3600, 7, 0, 0, null, new byte[] {2});
        TransportParameters tp = new TransportParameters();
        assertNull(SessionTicketCache.get("Example.org", 443));
        SessionTicketCache.put("Example.org", 443, QuicVersion.V1, ticket, tp);
        SessionTicketCache.Entry e = SessionTicketCache.get("example.ORG", 443);
        assertNotNull(e);
        assertEquals(QuicVersion.V1, e.getVersion());
        assertSame(ticket, e.toTicket());
        assertSame(tp, e.toTransportParameters());
        assertNull(SessionTicketCache.get("example.org", 444));
        SessionTicketCache.put("example.org", 443, QuicVersion.V2, ticket, tp);
        assertEquals(QuicVersion.V2, SessionTicketCache.get("example.org", 443).getVersion());
        final int[] observed = new int[1];
        SessionTicketCache.putObserver = new Runnable() {
            @Override
            public void run() {
                observed[0]++;
            }
        };
        try {
            SessionTicketCache.put("a", 1, QuicVersion.V1, ticket, tp);
        } finally {
            SessionTicketCache.putObserver = null;
        }
        assertEquals(1, observed[0]);
        SessionTicket expired = new SessionTicket(new byte[] {1}, 0, 7, 0, 0, null, new byte[] {2});
        SessionTicketCache.put("old", 1, QuicVersion.V1, expired, tp);
        assertNull(SessionTicketCache.get("old", 1));
        SessionTicketCache.clear();
        assertNull(SessionTicketCache.get("example.org", 443));
    }

    @Test
    public void factoryVersionsParsing() {
        QuicTransportFactory f = new QuicTransportFactory();
        f.setVersions("2, 1 2");
        QuicVersion[] v = f.getVersions();
        assertEquals(2, v.length);
        assertEquals(QuicVersion.V2, v[0]);
        assertEquals(QuicVersion.V1, v[1]);
        try {
            f.setVersions("3");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("3"));
        }
        try {
            f.setVersions("");
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void factoryVersionsAcceptCommasAndWhitespaceInAnyMix() {
        QuicTransportFactory f = new QuicTransportFactory();
        String[] lists = { "2,1", "2, 1", " 2 ,\t1 ", "2 1", ",2,,1," };
        for (int i = 0; i < lists.length; i++) {
            f.setVersions(lists[i]);
            QuicVersion[] v = f.getVersions();
            assertEquals(lists[i], 2, v.length);
            assertEquals(lists[i], QuicVersion.V2, v[0]);
            assertEquals(lists[i], QuicVersion.V1, v[1]);
        }
    }

    @Test
    public void factoryVersionsRejectSeparatorsOnly() {
        QuicTransportFactory f = new QuicTransportFactory();
        String[] lists = { "", " ", ",", " , ,\t" };
        for (int i = 0; i < lists.length; i++) {
            try {
                f.setVersions(lists[i]);
                fail("accepted: [" + lists[i] + "]");
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void factorySettersFeedTransportParameters() {
        QuicTransportFactory f = new QuicTransportFactory();
        f.setMaxIdleTimeout(1234);
        f.setMaxData(11);
        f.setMaxStreamDataBidiLocal(12);
        f.setMaxStreamDataBidiRemote(13);
        f.setMaxStreamDataUni(14);
        f.setMaxStreamsBidi(15);
        f.setMaxStreamsUni(16);
        f.setMaxDatagramFrameSize(17);
        f.setCongestionControl(QuicTransportFactory.CC_CUBIC);
        f.setApplicationProtocols("h3,doq");
        assertEquals("h3,doq", f.getApplicationProtocols());
        assertEquals(1234, f.getMaxIdleTimeout());
        assertEquals(17, f.getMaxDatagramFrameSize());
        TransportParameters p = f.buildTransportParameters(new byte[] {1, 2, 3, 4}, true);
        assertEquals(11, p.getInitialMaxData());
        assertEquals(12, p.getInitialMaxStreamDataBidiLocal());
        assertEquals(13, p.getInitialMaxStreamDataBidiRemote());
        assertEquals(14, p.getInitialMaxStreamDataUni());
        assertEquals(15, p.getInitialMaxStreamsBidi());
        assertEquals(16, p.getInitialMaxStreamsUni());
        assertEquals(17, p.getMaxDatagramFrameSize());
        assertNotNull(p.getStatelessResetToken());
        TransportParameters c = f.buildTransportParameters(new byte[] {1, 2, 3, 4});
        assertNull(c.getStatelessResetToken());
    }

    @Test
    public void factoryTrustAndSniConfiguration() throws Exception {
        QuicTransportFactory f = new QuicTransportFactory();
        assertFalse(f.isSNIEnabled());
        Map<String, String> m = new HashMap<String, String>();
        m.put("a.example", "alias");
        f.setSniHostnames(m);
        assertTrue(f.isSNIEnabled());
        f.setSniHostnames(null);
        assertFalse(f.isSNIEnabled());
        f.setSniDefaultAlias("x");
        assertFalse(f.isNeedClientAuth());
        f.setNeedClientAuth(true);
        assertTrue(f.isNeedClientAuth());
        f.setClientEchRequired(true);
        f.setClientEchGreaseEnabled(true);
        assertNull(f.getClientEchConfig());
        f.setClientEchConfig(null);

        X509TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        f.setTrustManager(tm);
        f.start();
        assertSame(tm, f.getTrustManager());
    }

    @Test
    public void factoryStartUsesDefaultTrustStoreAndPinning() {
        QuicTransportFactory f = new QuicTransportFactory();
        f.start();
        assertNotNull(f.getTrustManager());
        QuicTransportFactory g = new QuicTransportFactory();
        g.setVerifyPeer(false);
        g.start();
        X509TrustManager permissive = g.getTrustManager();
        assertEquals(0, permissive.getAcceptedIssuers().length);
        try {
            permissive.checkClientTrusted(null, "x");
            permissive.checkServerTrusted(null, "x");
        } catch (Exception e) {
            fail(e.toString());
        }
        QuicTransportFactory h = new QuicTransportFactory();
        h.setVerifyPeer(false);
        h.setPinnedCertFingerprint("00:11");
        h.start();
        assertNotNull(h.getTrustManager());
    }

    @Test
    public void factoryStartFailsOnMissingFiles() {
        Path missing = MemoryFileSystem.create().getPath("/nonexistent-gumdrop-quic-test/none.pem");
        QuicTransportFactory f = new QuicTransportFactory();
        f.setCaFile(missing);
        try {
            f.start();
            fail();
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        QuicTransportFactory g = new QuicTransportFactory();
        g.setCertFile(missing);
        g.setKeyFile(missing);
        try {
            g.start();
            fail();
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void streamEndpointLifecycleOnRealConnection() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(false);
        Rec r = new Rec();
        Endpoint e = conn.openStream(r);
        assertNotNull(e);
        QuicStreamEndpoint s = (QuicStreamEndpoint) e;
        assertEquals(0L, s.getStreamId());
        assertTrue(s.isOpen());
        assertFalse(s.isClosing());
        assertTrue(s.isSecure());
        assertNotNull(s.getSelectorLoop());
        assertNotNull(s.getLocalAddress());
        assertNotNull(s.getRemoteAddress());
        s.setTrace(null);
        assertNull(s.getTrace());
        s.getTelemetryConfig();
        final int[] ran = new int[2];
        s.execute(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });
        assertEquals(1, ran[0]);
        assertNotNull(s.scheduleTimer(10000, new Runnable() {
            @Override
            public void run() {
                ran[1]++;
            }
        }));
        try {
            s.startTLS();
            fail();
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected.getMessage());
        }
        s.onWriteReady(new Runnable() {
            @Override
            public void run() {
                ran[1]++;
            }
        });
        s.notifyWriteReady();
        s.notifyWriteReady();
        assertEquals(1, ran[1]);
        assertFalse(s.sendDatagram(ByteBuffer.wrap(new byte[2])));
        s.send(ByteBuffer.wrap(new byte[3]));
        s.pauseRead();
        assertTrue(s.isReadPaused());
        s.resumeRead();
        s.send(null);
        assertTrue(s.isClosing());
        assertFalse(s.isOpen());
        s.send(ByteBuffer.wrap(new byte[3]));
        s.close();
        s.resetStream(3);
        assertFalse(s.isFullyClosed());
        s.markPeerFinished();
        assertTrue(s.isFullyClosed());
    }

    @Test
    public void streamResetAndMarkClosed() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(false);
        QuicStreamEndpoint s = (QuicStreamEndpoint) conn.openStream(new Rec());
        s.resetStream(8);
        assertTrue(s.isClosing());
        QuicStreamEndpoint t = (QuicStreamEndpoint) conn.openStream(new Rec());
        t.markClosed();
        assertFalse(t.isOpen());
        assertTrue(t.isClosing());
    }

    @Test
    public void openStreamOnClosedConnectionReportsError() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(false);
        conn.close();
        Rec r = new Rec();
        assertNull(conn.openStream(r));
        assertTrue(r.events.contains("error"));
    }

    @Test
    public void serverListenerHonoursItsConfiguredNamedGroups() throws Exception {
        // The server picks the group, by its own preference among those
        // the client supports (RFC 8446 section 4.2.8): a server configured
        // without the hybrid group must not negotiate it, even though the
        // client offers it first.
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setNamedGroups("secp256r1:x25519");
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertEquals("secp256r1", client.conn.getSecurityInfo().getNamedGroup());
        assertEquals("secp256r1", server.conn.getSecurityInfo().getNamedGroup());
    }

    @Test
    public void handshakeFlightsAreSplitAcrossDatagramsOfAtMostTheMinimumMtu() throws Exception {
        // RFC 9000 section 14: no datagram larger than 1200 bytes before
        // the path is known to carry more. A ClientHello with a hybrid
        // key share, and a ServerHello plus an RSA-4096 certificate, are
        // each bigger than that and must span several packets.
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setServerCredentials(TestCertificates.rsa4096().credentials());
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertNotNull("handshake did not complete", client.conn);
        assertEquals("X25519MLKEM768", client.conn.getSecurityInfo().getNamedGroup());
        assertTrue("ClientHello should not fit one datagram", lb.toServerLog.size() > 1);
        for (int i = 0; i < lb.toServerLog.size(); i++) {
            assertTrue("client datagram " + i + " is " + lb.toServerLog.get(i).length + " bytes",
                    lb.toServerLog.get(i).length <= 1200);
        }
        for (int i = 0; i < lb.toClientLog.size(); i++) {
            assertTrue("server datagram " + i + " is " + lb.toClientLog.get(i).length + " bytes",
                    lb.toClientLog.get(i).length <= 1200);
        }
    }

    @Test
    public void securityInfoReportsTheNegotiatedApplicationProtocol() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setApplicationProtocols("h3,hq-interop");
        lb.clientFactory.setApplicationProtocols("hq-interop,h3");
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        // the server's preference wins
        assertEquals("h3", client.conn.getSecurityInfo().getApplicationProtocol());
        assertEquals("h3", server.conn.getSecurityInfo().getApplicationProtocol());
    }

    @Test
    public void securityInfoReportsHandshakeDetails() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        SecurityInfo c = client.conn.getSecurityInfo();
        SecurityInfo s = server.conn.getSecurityInfo();
        assertEquals("QUICv1", c.getProtocol());
        assertNotNull(c.getCipherSuite());
        assertEquals("X25519MLKEM768", c.getNamedGroup());
        assertEquals("X25519MLKEM768", s.getNamedGroup());
        assertTrue(c.getKeySize() == 128 || c.getKeySize() == 256);
        assertNotNull(c.getPeerCertificates());
        assertNull(s.getPeerCertificates());
        assertNull(c.getLocalCertificates());
        // the harness offers "test" on both sides
        assertEquals("test", c.getApplicationProtocol());
        assertEquals("test", s.getApplicationProtocol());
        assertTrue(c.getHandshakeDurationMs() >= 0);
        assertFalse(c.isSessionResumed());
        assertFalse(c.isEarlyDataAccepted());
    }
}
