/*
 * DefaultPop3HandlerTest.java
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

package org.bluezoo.gumdrop.pop3.server;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for {@link DefaultPOP3Handler} policy hooks.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultPop3HandlerTest {

    @Test
    public void testConnectedAcceptsWithConfiguredGreeting() {
        DefaultPOP3Handler handler = new DefaultPOP3Handler("POP3 test ready");
        AtomicReference<String> greeting = new AtomicReference<String>();
        AtomicReference<AuthorizationHandler> next = new AtomicReference<AuthorizationHandler>();

        handler.connected(new ConnectedState() {
            @Override
            public void acceptConnection(String message, AuthorizationHandler authorizationHandler) {
                greeting.set(message);
                next.set(authorizationHandler);
            }

            @Override
            public void acceptConnectionWithApop(String greetingMsg, String timestamp,
                                                 AuthorizationHandler authorizationHandler) {
            }

            @Override
            public void rejectConnection(String message) {
            }

            @Override
            public void rejectConnection() {
            }

            @Override
            public void serverShuttingDown() {
            }
        }, new NoOpEndpoint());

        assertEquals("POP3 test ready", greeting.get());
        assertSame(handler, next.get());
    }

    private static final class NoOpEndpoint implements Endpoint {
        @Override public void send(ByteBuffer buf) {}
        @Override public boolean isOpen() { return true; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() {}
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() throws IOException {}
        @Override public void pauseRead() {}
        @Override public void resumeRead() {}
        @Override public void onWriteReady(Runnable callback) {}
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void setTrace(Trace trace) {}
        @Override public Trace getTrace() { return null; }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
    }
}
