/*
 * IMAPClientServerUtf8AcceptTest.java
 * Copyright (C) 2026 Chris Burdess
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
import org.bluezoo.gumdrop.imap.client.EnableReplyHandler;
import org.bluezoo.gumdrop.imap.client.ImapClientProtocolHandler;
import org.bluezoo.gumdrop.imap.client.LoginReplyHandler;
import org.bluezoo.gumdrop.imap.client.RemoteGreeting;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import org.junit.Test;

import static org.junit.Assert.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Client ENABLE UTF8=ACCEPT against the in-process IMAP server.
 */
public class IMAPClientServerUtf8AcceptTest {

    @Test(timeout = 10000)
    public void testClientServerEnableUtf8Accept() {
        RecordingGreetingHandler greeting = new RecordingGreetingHandler();
        ImapClientProtocolHandler client =
                new ImapClientProtocolHandler(greeting);

        ImapListener listener = new ImapListener();
        listener.setRealm(new AcceptingRealm("alice", "secret"));
        listener.setAllowPlaintextLogin(true);
        ImapProtocolHandler server = new ImapProtocolHandler(listener);

        client.connected(forwardingTo(server));
        server.connected(forwardingTo(client));

        RecordingLoginHandler loginHandler = new RecordingLoginHandler();
        greeting.authState.login("alice", "secret", loginHandler);
        assertTrue(loginHandler.authenticated);

        RecordingEnableHandler enableHandler = new RecordingEnableHandler();
        loginHandler.session.enable(
                new String[]{"UTF8=ACCEPT"}, enableHandler);
        assertTrue(enableHandler.ok);
        assertTrue(client.isUtf8AcceptEnabled());
        assertTrue(server.isUtf8AcceptEnabled());
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
        org.bluezoo.gumdrop.imap.client.ClientNotAuthenticatedState authState;

        @Override
        public void handleGreeting(
                org.bluezoo.gumdrop.imap.client.ClientNotAuthenticatedState auth,
                String greeting,
                List<String> preAuthCapabilities) {
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

    private static final class RecordingEnableHandler
            implements EnableReplyHandler {
        boolean ok;
        ClientAuthenticatedState session;

        @Override
        public void handleEnabled(ClientAuthenticatedState session,
                List<String> enabled) {
            ok = true;
            this.session = session;
            assertTrue(enabled.contains("UTF8=ACCEPT"));
        }

        @Override
        public void handleError(ClientAuthenticatedState session,
                String message) {
            fail("ENABLE failed: " + message);
        }

        @Override
        public void handleServiceClosing(String message) { }
    }
}
