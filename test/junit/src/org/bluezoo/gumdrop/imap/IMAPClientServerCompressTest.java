/*
 * IMAPClientServerCompressTest.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.imap.client.ClientAuthenticatedState;
import org.bluezoo.gumdrop.imap.client.CompressReplyHandler;
import org.bluezoo.gumdrop.imap.client.ImapClientProtocolHandler;
import org.bluezoo.gumdrop.imap.client.LoginReplyHandler;
import org.bluezoo.gumdrop.imap.client.NoopReplyHandler;
import org.bluezoo.gumdrop.imap.client.RemoteGreeting;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import org.junit.Test;

import static org.junit.Assert.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Client and server handlers wired back-to-back for RFC 4978 COMPRESS DEFLATE.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPClientServerCompressTest {

    @Test(timeout = 10000)
    public void testClientServerCompressThenNoop() {
        RecordingGreetingHandler greeting = new RecordingGreetingHandler();
        ImapClientProtocolHandler client =
                new ImapClientProtocolHandler(greeting);

        ImapListener listener = new ImapListener();
        listener.setRealm(new AcceptingRealm("alice", "secret"));
        listener.setAllowPlaintextLogin(true);
        ImapProtocolHandler server = new ImapProtocolHandler(listener);

        client.connected(forwardingTo(server));
        server.connected(forwardingTo(client));

        assertTrue(greeting.greetingReceived);

        RecordingLoginHandler loginHandler = new RecordingLoginHandler();
        greeting.authState.login("alice", "secret", loginHandler);
        assertTrue(loginHandler.authenticated);

        RecordingCompressHandler compressHandler =
                new RecordingCompressHandler();
        loginHandler.session.compress(compressHandler);
        assertTrue(compressHandler.ok);
        assertTrue(client.isDeflateActive());

        RecordingNoopHandler noopHandler = new RecordingNoopHandler();
        compressHandler.session.noop(noopHandler);
        assertTrue(noopHandler.ok);
    }

    private static Endpoint forwardingTo(final ProtocolHandler peer) {
        return new Endpoint() {
            private boolean open = true;

            @Override
            public void send(ByteBuffer data) {
                byte[] copy = new byte[data.remaining()];
                data.get(copy);
                peer.receive(ByteBuffer.wrap(copy));
            }

            @Override public boolean isOpen() { return open; }
            @Override public boolean isClosing() { return false; }
            @Override public void close() { open = false; }
            @Override public SocketAddress getLocalAddress() {
                return new InetSocketAddress("127.0.0.1", 143);
            }
            @Override public SocketAddress getRemoteAddress() {
                return new InetSocketAddress("127.0.0.1", 54321);
            }
            @Override public boolean isSecure() { return false; }
            @Override public SecurityInfo getSecurityInfo() { return null; }
            @Override public void startTLS() { }
            @Override public SelectorLoop getSelectorLoop() { return null; }
            @Override public void execute(Runnable task) { task.run(); }
            @Override public TimerHandle scheduleTimer(long delayMs,
                    Runnable cb) {
                return new TimerHandle() {
                    @Override public void cancel() { }
                    @Override public boolean isCancelled() {
                        return false;
                    }
                };
            }
            @Override public Trace getTrace() { return null; }
            @Override public void setTrace(Trace trace) { }
            @Override public boolean isTelemetryEnabled() { return false; }
            @Override public TelemetryConfig getTelemetryConfig() {
                return null;
            }
            @Override public void pauseRead() { }
            @Override public void resumeRead() { }
            @Override public void onWriteReady(Runnable callback) {
                if (callback != null) {
                    callback.run();
                }
            }
        };
    }

    private static final class AcceptingRealm implements Realm {
        private final String user;
        private final String pass;
        private static final Set<SaslMechanism> SUPPORTED =
                Collections.unmodifiableSet(
                        EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN));

        AcceptingRealm(String user, String pass) {
            this.user = user;
            this.pass = pass;
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return SUPPORTED;
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return user.equals(username) && pass.equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            return user.equals(username) ? pass : null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }
    }

    private static final class RecordingGreetingHandler
            implements RemoteGreeting {
        boolean greetingReceived;
        org.bluezoo.gumdrop.imap.client.ClientNotAuthenticatedState authState;

        @Override
        public void handleGreeting(
                org.bluezoo.gumdrop.imap.client.ClientNotAuthenticatedState auth,
                String greeting,
                List<String> preAuthCapabilities) {
            greetingReceived = true;
            authState = auth;
        }

        @Override
        public void handlePreAuthenticated(
                ClientAuthenticatedState auth, String greeting) { }

        @Override
        public void handleServiceUnavailable(String message) { }

        @Override public void onConnected(Endpoint endpoint) { }
        @Override public void onError(Exception cause) { }
        @Override public void onDisconnected() { }
        @Override public void onSecurityEstablished(SecurityInfo info) { }
    }

    private static final class RecordingLoginHandler
            implements LoginReplyHandler {
        boolean authenticated;
        ClientAuthenticatedState session;

        @Override
        public void handleAuthenticated(ClientAuthenticatedState session,
                List<String> capabilities) {
            authenticated = true;
            this.session = session;
        }

        @Override
        public void handleAuthFailed(
                org.bluezoo.gumdrop.imap.client.ClientNotAuthenticatedState auth,
                String message) { }

        @Override
        public void handleServiceClosing(String message) { }
    }

    private static final class RecordingCompressHandler
            implements CompressReplyHandler {
        boolean ok;
        ClientAuthenticatedState session;

        @Override
        public void handleOk(ClientAuthenticatedState session) {
            ok = true;
            this.session = session;
        }

        @Override
        public void handleError(ClientAuthenticatedState session,
                String message) {
            fail("COMPRESS failed: " + message);
        }

        @Override
        public void handleServiceClosing(String message) { }
    }

    private static final class RecordingNoopHandler
            implements NoopReplyHandler {
        boolean ok;

        @Override
        public void handleOk(ClientAuthenticatedState session) {
            ok = true;
        }

        @Override
        public void handleServiceClosing(String message) { }
    }
}
