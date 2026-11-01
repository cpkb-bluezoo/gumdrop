/*
 * SmtpListenerSessionTest.java
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

package org.bluezoo.gumdrop.smtp;

import org.junit.Test;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.smtp.server.ClientConnected;
import org.bluezoo.gumdrop.smtp.server.SmtpServer;
import org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProvider;

import static org.junit.Assert.*;

/**
 * How {@link SmtpListener} obtains its session handler and defaults its port.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpListenerSessionTest {

    private int opened;

    @Test
    public void startDefaultsThePortFromSecurity() {
        SmtpListener plain = new SmtpListener();
        plain.start();
        assertEquals(25, plain.getPort());
        assertNull("metrics are off without telemetry", plain.getMetrics());
    }

    @Test
    public void explicitPortSurvivesStart() {
        SmtpListener listener = new SmtpListener();
        listener.port(2525);
        listener.start();
        assertEquals(2525, listener.getPort());
    }

    @Test
    public void gssapiServerCanBeSetAndCleared() {
        SmtpListener listener = new SmtpListener();
        assertNull(listener.getGSSAPIServer());
        assertSame(listener, listener.gssapiServer(null));
        assertNull(listener.getGSSAPIServer());
    }

    @Test
    public void handlerWithoutProviderOrServerHasNoSession() {
        SmtpListener listener = new SmtpListener();
        ProtocolHandler handler = listener.createHandler();
        assertTrue(handler instanceof SmtpProtocolHandler);
    }

    @Test
    public void sessionProviderIsAskedForEachHandler() {
        SmtpListener listener = new SmtpListener();
        listener.sessionProvider(new SmtpServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                opened++;
                return null;
            }
        });
        assertNotNull(listener.createHandler());
        assertNotNull(listener.createHandler());
        assertEquals(2, opened);
    }

    @Test
    public void failingSessionProviderStillYieldsAHandler() {
        SmtpListener listener = new SmtpListener();
        listener.sessionProvider(new SmtpServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                throw new IllegalStateException("boom");
            }
        });
        assertTrue(listener.createHandler() instanceof SmtpProtocolHandler);
    }

    @Test
    public void legacyServerIsUsedWhenNoProviderIsSet() {
        SmtpListener listener = new SmtpListener();
        listener.server(new SmtpServer() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                opened++;
                return null;
            }
        });
        assertNotNull(listener.createHandler());
        assertEquals(1, opened);
    }

    @Test
    public void failingServerStillYieldsAHandler() {
        SmtpListener listener = new SmtpListener();
        listener.server(new SmtpServer() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                throw new IllegalStateException("boom");
            }
        });
        assertTrue(listener.createHandler() instanceof SmtpProtocolHandler);
    }

    @Test
    public void providerTakesPrecedenceOverServer() {
        SmtpListener listener = new SmtpListener();
        listener.server(new SmtpServer() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                fail("server must not be consulted");
                return null;
            }
        });
        listener.sessionProvider(new SmtpServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                opened++;
                return null;
            }
        });
        listener.createHandler();
        assertEquals(1, opened);
    }
}
