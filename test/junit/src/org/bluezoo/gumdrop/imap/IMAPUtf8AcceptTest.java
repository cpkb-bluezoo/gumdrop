/*
 * IMAPUtf8AcceptTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * RFC 6855 UTF8=ACCEPT on the IMAP server.
 */
public class IMAPUtf8AcceptTest {

    @Test(timeout = 10000)
    public void testEnableUtf8AcceptAdvertisesAndActivates() throws Exception {
        ImapListener listener = new ImapListener();
        listener.setRealm(new AcceptingRealm());
        listener.setAllowPlaintextLogin(true);

        ImapProtocolHandler handler = new ImapProtocolHandler(listener);
        RecordingStubEndpoint endpoint = new RecordingStubEndpoint(143);
        handler.connected(endpoint);

        sendPlain(handler, "a1 LOGIN user pass");
        endpoint.awaitLineContaining("a1 OK");

        endpoint.clearResponses();
        sendPlain(handler, "a2 ENABLE UTF8=ACCEPT");
        endpoint.awaitLineContaining("a2 OK");
        assertTrue(endpoint.findLineContaining("* ENABLED")
                .contains("UTF8=ACCEPT"));
        assertTrue(handler.isUtf8AcceptEnabled());

        endpoint.clearResponses();
        sendUtf8(handler, "a3 NOOP");
        endpoint.awaitLineContaining("a3 OK");
    }

    private static void sendPlain(ImapProtocolHandler handler, String line) {
        byte[] data = (line + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private static void sendUtf8(ImapProtocolHandler handler, String line) {
        byte[] data = (line + "\r\n").getBytes(StandardCharsets.UTF_8);
        handler.receive(ByteBuffer.wrap(data));
    }

    private static final class AcceptingRealm implements Realm {
        private static final Set<SaslMechanism> SUPPORTED =
                Collections.unmodifiableSet(
                        EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN));

        @Override
        public Realm forSelectorLoop(
                org.bluezoo.gumdrop.SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return SUPPORTED;
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return "user".equals(username) && "pass".equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            return "user".equals(username) ? "pass" : null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }
    }
}
