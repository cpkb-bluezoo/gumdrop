/*
 * HTTP3ListenerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * Tests for Http3Listener QUIC transport parameter configuration
 * (RFC 9000 section 18).
 */

package org.bluezoo.gumdrop.http.h3;

import java.lang.reflect.Field;

import org.junit.Test;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;

import static org.junit.Assert.*;

public class HTTP3ListenerTest {

    @Test
    public void testDefaultQuicParameters() throws Exception {
        Http3Listener listener = new Http3Listener();

        assertEquals(-1L, getField(listener, "quicMaxIdleTimeout"));
        assertEquals(-1L, getField(listener, "quicMaxData"));
        assertEquals(-1L, getField(listener, "quicMaxStreamDataBidiLocal"));
        assertEquals(-1L, getField(listener, "quicMaxStreamDataBidiRemote"));
        assertEquals(-1L, getField(listener, "quicMaxStreamDataUni"));
        assertEquals(-1L, getField(listener, "quicMaxStreamsBidi"));
        assertEquals(-1L, getField(listener, "quicMaxStreamsUni"));
    }

    @Test
    public void testSetQuicMaxIdleTimeout() throws Exception {
        Http3Listener listener = new Http3Listener();
        listener.setQuicMaxIdleTimeout(60000);
        assertEquals(60000L, getField(listener, "quicMaxIdleTimeout"));
    }

    @Test
    public void testSetQuicMaxData() throws Exception {
        Http3Listener listener = new Http3Listener();
        listener.setQuicMaxData(10_000_000);
        assertEquals(10_000_000L, getField(listener, "quicMaxData"));
    }

    @Test
    public void testSetQuicMaxStreamDataBidiLocal() throws Exception {
        Http3Listener listener = new Http3Listener();
        listener.setQuicMaxStreamDataBidiLocal(1_000_000);
        assertEquals(1_000_000L, getField(listener, "quicMaxStreamDataBidiLocal"));
    }

    @Test
    public void testSetQuicMaxStreamDataBidiRemote() throws Exception {
        Http3Listener listener = new Http3Listener();
        listener.setQuicMaxStreamDataBidiRemote(2_000_000);
        assertEquals(2_000_000L, getField(listener, "quicMaxStreamDataBidiRemote"));
    }

    @Test
    public void testSetQuicMaxStreamDataUni() throws Exception {
        Http3Listener listener = new Http3Listener();
        listener.setQuicMaxStreamDataUni(500_000);
        assertEquals(500_000L, getField(listener, "quicMaxStreamDataUni"));
    }

    @Test
    public void testSetQuicMaxStreamsBidi() throws Exception {
        Http3Listener listener = new Http3Listener();
        listener.setQuicMaxStreamsBidi(128);
        assertEquals(128L, getField(listener, "quicMaxStreamsBidi"));
    }

    @Test
    public void testSetQuicMaxStreamsUni() throws Exception {
        Http3Listener listener = new Http3Listener();
        listener.setQuicMaxStreamsUni(64);
        assertEquals(64L, getField(listener, "quicMaxStreamsUni"));
    }

    @Test
    public void testDefaultPort() {
        Http3Listener listener = new Http3Listener();
        assertEquals(-1, listener.getPort());
    }

    @Test
    public void testSetPort() {
        Http3Listener listener = new Http3Listener();
        listener.setPort(8443);
        assertEquals(8443, listener.getPort());
    }

    @Test
    public void testRequireRetryEnabledByDefault() {
        Http3Listener listener = new Http3Listener();
        assertTrue(listener.isRequireRetry());
    }

    @Test
    public void testSetRequireRetry() {
        Http3Listener listener = new Http3Listener();
        listener.setRequireRetry(false);
        assertFalse(listener.isRequireRetry());
    }

    @Test
    public void testCreateTransportFactoryEnablesRetryByDefault() throws Exception {
        Http3Listener listener = new Http3Listener();
        QuicTransportFactory factory = invokeCreateTransportFactory(listener);
        assertTrue(factory.isRequireRetry());
    }

    @Test
    public void testCreateTransportFactoryHonoursRetryOptOut() throws Exception {
        Http3Listener listener = new Http3Listener();
        listener.setRequireRetry(false);
        QuicTransportFactory factory = invokeCreateTransportFactory(listener);
        assertFalse(factory.isRequireRetry());
    }

    private static QuicTransportFactory invokeCreateTransportFactory(
            Http3Listener listener) throws Exception {
        java.lang.reflect.Method method =
                Http3Listener.class.getDeclaredMethod("createTransportFactory");
        method.setAccessible(true);
        return (QuicTransportFactory) method.invoke(listener);
    }

    private long getField(Http3Listener listener, String name) throws Exception {
        Field f = Http3Listener.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(listener);
    }
}
