/*
 * POP3ServerSmallTypesTest.java
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


package org.bluezoo.gumdrop.pop3;

import java.io.IOException;
import java.net.InetAddress;

import org.junit.Test;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.pop3.server.ClientConnected;
import org.bluezoo.gumdrop.pop3.server.Pop3Server;
import org.bluezoo.gumdrop.pop3.server.Pop3ServerSessionProvider;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Pop3Exception}, {@link Pop3ServerMetrics} and the
 * configuration and session-opening surface of {@link Pop3Listener}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3ServerSmallTypesTest {

    @Test
    public void testExceptionMessageAndCause() {
        Pop3Exception plain = new Pop3Exception("bad");
        assertEquals("bad", plain.getMessage());
        assertNull(plain.getCause());
        IOException cause = new IOException("root");
        Pop3Exception wrapped = new Pop3Exception("outer", cause);
        assertEquals("outer", wrapped.getMessage());
        assertSame(cause, wrapped.getCause());
    }

    @Test
    public void testMetricsRecordWithoutFailure() {
        TelemetryConfig config = new TelemetryConfig();
        Pop3ServerMetrics metrics = new Pop3ServerMetrics(config);
        metrics.connectionOpened();
        metrics.commandExecuted("STAT");
        metrics.authAttempt("PLAIN");
        metrics.authSuccess("PLAIN");
        metrics.authFailure("LOGIN");
        metrics.messageRetrieved(1234L);
        metrics.messageDeleted();
        metrics.starttlsUpgraded();
        metrics.connectionClosed(250.0);
    }

    @Test
    public void testListenerDescriptionAndPort() {
        Pop3Listener listener = new Pop3Listener();
        assertEquals("pop3", listener.getDescription());
        assertEquals(-1, listener.getPort());
        Pop3Listener same = listener.port(1110);
        assertSame(listener, same);
        assertEquals(1110, listener.getPort());
        listener.port(2110);
        assertEquals(2110, listener.getPort());
        Pop3Listener secured = listener.secure(true);
        assertSame(listener, secured);
        assertEquals("pop3s", listener.getDescription());
    }

    @Test
    public void testListenerFluentBindingAndAccessors() {
        Pop3Listener listener = new Pop3Listener();
        assertSame(listener, listener.bindWildcard());
        InetAddress loopback = InetAddress.getLoopbackAddress();
        assertSame(listener, listener.addresses(loopback));
        listener.transactionTimeoutMs(1234L);
        assertEquals(1234L, listener.getTransactionTimeoutMs());
        listener.enableAPOP(false);
        assertFalse(listener.isEnableAPOP());
        listener.enableUTF8(false);
        assertFalse(listener.isEnableUTF8());
        assertNull(listener.getRealm());
        assertNull(listener.getMailboxFactory());
        assertNull(listener.getGSSAPIServer());
        listener.gssapiServer(null);
        assertNull(listener.getMetrics());
        assertNull(listener.getServer());
        assertNull(listener.getSessionProvider());
    }

    @Test
    public void testListenerCreatesProtocolHandler() {
        Pop3Listener listener = new Pop3Listener();
        assertNotNull(listener.createHandler());
    }

    @Test
    public void testOpenApplicationSessionWithoutProviderOrServer() {
        Pop3Listener listener = new Pop3Listener();
        assertNull(listener.openApplicationSession());
    }

    @Test
    public void testOpenApplicationSessionPrefersProvider() {
        Pop3Listener listener = new Pop3Listener();
        final ClientConnected expected = new POP3ProtocolHandlerTest.RecordingClientHandler();
        Pop3ServerSessionProvider provider = new Pop3ServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                return expected;
            }
        };
        Pop3Listener returned = listener.sessionProvider(provider);
        assertSame(listener, returned);
        assertSame(provider, listener.getSessionProvider());
        assertSame(expected, listener.openApplicationSession());
    }

    @Test
    public void testOpenApplicationSessionFallsBackToServerWhenProviderFails() {
        Pop3Listener listener = new Pop3Listener();
        listener.sessionProvider(new Pop3ServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                throw new IllegalStateException("provider broke");
            }
        });
        final ClientConnected expected = new POP3ProtocolHandlerTest.RecordingClientHandler();
        listener.server(new POP3ProtocolHandlerTest.TestPOP3Service(expected));
        assertSame(expected, listener.openApplicationSession());
    }

    @Test
    public void testOpenApplicationSessionReturnsNullWhenServerFails() {
        Pop3Listener listener = new Pop3Listener();
        listener.server(new Pop3Server() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                throw new IllegalStateException("server broke");
            }
        });
        assertNull(listener.openApplicationSession());
    }
}
