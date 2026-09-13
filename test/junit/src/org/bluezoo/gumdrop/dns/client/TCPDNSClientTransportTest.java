/*
 * TCPDNSClientTransportTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TcpDnsClientTransport}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TCPDNSClientTransportTest {

    @Test
    public void testImplementsInterface() {
        TcpDnsClientTransport transport = new TcpDnsClientTransport();
        assertTrue(transport instanceof DnsClientTransport);
    }

    @Test
    public void testCloseBeforeOpen() {
        TcpDnsClientTransport transport = new TcpDnsClientTransport();
        transport.close();
    }

    @Test
    public void testCreateDoT() {
        TcpDnsClientTransport transport = TcpDnsClientTransport.createDoT();
        assertNotNull(transport);
        transport.close();
    }

    @Test
    public void testSetSecureChangesDefaults() {
        TcpDnsClientTransport transport = new TcpDnsClientTransport();
        transport.setSecure(true);
        transport.setSecure(false);
        transport.close();
    }

    @Test
    public void testSetDefaultPort() {
        TcpDnsClientTransport transport = new TcpDnsClientTransport();
        transport.setDefaultPort(5353);
        transport.close();
    }

    /**
     * RFC 7858 section 3.4, RFC 7413: DoT transport should enable TCP
     * Fast Open on the underlying factory.
     */
    @Test
    public void testDoTEnablesTcpFastOpen() {
        TcpDnsClientTransport transport = TcpDnsClientTransport.createDoT();
        TcpTransportFactory factory = transport.createTransportFactory();
        assertTrue(factory.isTcpFastOpen());
        transport.close();
    }

    // -- DoT ALPN and trust manager configuration --

    /**
     * RFC 7858 section 3.1: the "dot" ALPN identifier MUST be
     * advertised for DNS-over-TLS.
     */
    @Test
    public void testCreateDoTAdvertisesDotAlpn() {
        TcpDnsClientTransport transport = TcpDnsClientTransport.createDoT();
        TcpTransportFactory factory = transport.createTransportFactory();
        assertArrayEquals(new String[]{ "dot" },
                factory.getApplicationProtocols());
        transport.close();
    }

    @Test
    public void testPlainTcpTransportHasNoAlpn() {
        TcpDnsClientTransport transport = new TcpDnsClientTransport();
        TcpTransportFactory factory = transport.createTransportFactory();
        assertNull(factory.getApplicationProtocols());
        transport.close();
    }

    @Test
    public void testSetTrustManagerUsedDirectlyWhenNoSpkiPins() {
        TcpDnsClientTransport transport = TcpDnsClientTransport.createDoT();
        X509TrustManager custom = new X509TrustManager() {
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
        transport.setTrustManager(custom);

        TcpTransportFactory factory = transport.createTransportFactory();
        assertSame(custom, factory.getTrustManager());
        transport.close();
    }

    @Test
    public void testPlainTransportHasNoTrustManagerByDefault() {
        TcpDnsClientTransport transport = TcpDnsClientTransport.createDoT();
        TcpTransportFactory factory = transport.createTransportFactory();
        assertNull(factory.getTrustManager());
        transport.close();
    }

    /**
     * RFC 7858 section 4.2: when both a custom trust manager and SPKI
     * pins are configured, the trust manager must be used as the SPKI
     * check's delegate rather than being discarded.
     */
    @Test
    public void testCustomTrustManagerUsedAsSpkiDelegate() throws Exception {
        TcpDnsClientTransport transport = TcpDnsClientTransport.createDoT();
        final AtomicBoolean delegateCalled = new AtomicBoolean();
        X509TrustManager custom = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                delegateCalled.set(true);
                throw new CertificateException("marker: delegate reached");
            }
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        transport.setTrustManager(custom);
        Set<String> pins = new HashSet<>(Arrays.asList(
                "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"
                        + ":aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"));
        transport.setPinnedSPKIFingerprints(pins);

        TcpTransportFactory factory = transport.createTransportFactory();
        X509TrustManager wrapped = factory.getTrustManager();
        assertTrue(wrapped instanceof
                org.bluezoo.gumdrop.util.SpkiPinnedCertTrustManager);

        try {
            wrapped.checkServerTrusted(new X509Certificate[0], "RSA");
            fail("Expected the custom trust manager's exception to propagate");
        } catch (CertificateException e) {
            assertEquals("marker: delegate reached", e.getMessage());
        }
        assertTrue("Custom trust manager should be consulted as the "
                        + "SPKI check's delegate",
                delegateCalled.get());
        transport.close();
    }

    /**
     * Regression guard: SPKI pinning without an explicit custom trust
     * manager must still fall back to a real (JVM default) delegate,
     * not a null one that would NPE on the first handshake.
     */
    @Test
    public void testSpkiPinningWithoutCustomTrustManagerUsesJvmDefault()
            throws Exception {
        TcpDnsClientTransport transport = TcpDnsClientTransport.createDoT();
        transport.setPinnedSPKIFingerprints(new HashSet<>(Arrays.asList(
                "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"
                        + ":aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99")));

        TcpTransportFactory factory = transport.createTransportFactory();
        X509TrustManager wrapped = factory.getTrustManager();

        try {
            wrapped.checkServerTrusted(new X509Certificate[0], "RSA");
            fail("Expected rejection of an empty chain, not silent success");
        } catch (NullPointerException e) {
            fail("A null delegate was wired in instead of the JVM "
                    + "default trust manager: " + e);
        } catch (Exception expected) {
            // The JVM default X509TrustManagerImpl rejects the empty
            // chain itself (IllegalArgumentException) before the SPKI
            // fingerprint check ever runs -- reaching any exception
            // *other than* NPE proves a real delegate was wired in.
        }
        transport.close();
    }

    // -- Framing tests using the inner TcpProtocolHandler --

    @Test
    public void testFramingSingleMessage() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        DnsMessage query = DnsMessage.createQuery(1, "example.com",
                DnsType.A);
        ByteBuffer payload = query.serialize();
        ByteBuffer framed = frame(payload);

        ph.receive(framed);

        assertEquals(1, handler.received.size());
        DnsMessage parsed = DnsMessage.parse(handler.received.get(0));
        assertEquals(1, parsed.getId());
    }

    @Test
    public void testFramingTwoMessagesInOneChunk() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        ByteBuffer msg1 = DnsMessage.createQuery(10, "a.example.com",
                DnsType.A).serialize();
        ByteBuffer msg2 = DnsMessage.createQuery(20, "b.example.com",
                DnsType.AAAA).serialize();

        ByteBuffer combined = ByteBuffer.allocate(
                2 + msg1.remaining() + 2 + msg2.remaining());
        combined.putShort((short) msg1.remaining());
        combined.put(msg1);
        combined.putShort((short) msg2.remaining());
        combined.put(msg2);
        combined.flip();

        ph.receive(combined);

        assertEquals(2, handler.received.size());
        assertEquals(10, DnsMessage.parse(handler.received.get(0)).getId());
        assertEquals(20, DnsMessage.parse(handler.received.get(1)).getId());
    }

    @Test
    public void testFramingSplitAcrossChunks() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        ByteBuffer payload = DnsMessage.createQuery(42, "split.example.com",
                DnsType.MX).serialize();
        ByteBuffer framed = frame(payload);

        int splitPoint = framed.remaining() / 2;
        ByteBuffer part1 = ByteBuffer.allocate(splitPoint);
        ByteBuffer part2 = ByteBuffer.allocate(framed.remaining() - splitPoint);

        framed.limit(splitPoint);
        part1.put(framed);
        part1.flip();

        framed.limit(framed.capacity());
        part2.put(framed);
        part2.flip();

        ph.receive(part1);
        assertEquals("No message until fully received",
                0, handler.received.size());

        ph.receive(part2);
        assertEquals(1, handler.received.size());
        assertEquals(42, DnsMessage.parse(handler.received.get(0)).getId());
    }

    @Test
    public void testFramingLengthPrefixSplitAcrossChunks() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        ByteBuffer payload = DnsMessage.createQuery(7, "tiny.example.com",
                DnsType.A).serialize();
        ByteBuffer framed = frame(payload);

        // Send just the first byte of the length prefix
        ByteBuffer firstByte = ByteBuffer.allocate(1);
        firstByte.put(framed.get());
        firstByte.flip();

        ByteBuffer rest = framed.slice();

        ph.receive(firstByte);
        assertEquals(0, handler.received.size());

        ph.receive(rest);
        assertEquals(1, handler.received.size());
        assertEquals(7, DnsMessage.parse(handler.received.get(0)).getId());
    }

    @Test
    public void testFramingInvalidLengthReportsError() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        // Zero-length message is invalid
        ByteBuffer invalid = ByteBuffer.allocate(2);
        invalid.putShort((short) 0);
        invalid.flip();

        ph.receive(invalid);

        assertEquals(0, handler.received.size());
        assertNotNull("Should report error for zero-length message",
                handler.error);
    }

    @Test
    public void testDisconnectedReportsError() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        ph.disconnected();

        assertNotNull("disconnected() should report an error", handler.error);
        assertTrue(handler.error instanceof IOException);
    }

    @Test
    public void testErrorDelegatesToHandler() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ProtocolHandler ph = createProtocolHandler(handler);

        IOException cause = new IOException("test error");
        ph.error(cause);

        assertSame(cause, handler.error);
    }

    // -- Helpers --

    private static ByteBuffer frame(ByteBuffer payload) {
        payload.rewind();
        ByteBuffer framed = ByteBuffer.allocate(2 + payload.remaining());
        framed.putShort((short) payload.remaining());
        framed.put(payload);
        framed.flip();
        return framed;
    }

    /**
     * Creates the private inner TcpProtocolHandler via reflection.
     */
    private static ProtocolHandler createProtocolHandler(
            DnsClientTransportHandler handler) throws Exception {
        Class<?> handlerClass = null;
        for (Class<?> c : TcpDnsClientTransport.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals("TcpProtocolHandler")) {
                handlerClass = c;
                break;
            }
        }
        assertNotNull("TcpProtocolHandler inner class should exist",
                handlerClass);
        Constructor<?> ctor = handlerClass.getDeclaredConstructor(
                DnsClientTransportHandler.class);
        ctor.setAccessible(true);
        return (ProtocolHandler) ctor.newInstance(handler);
    }

    private static class RecordingHandler
            implements DnsClientTransportHandler {
        final List<ByteBuffer> received = new ArrayList<>();
        Exception error;

        @Override
        public void onReceive(ByteBuffer data) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            received.add(copy);
        }

        @Override
        public void onError(Exception cause) {
            error = cause;
        }
    }

}
