/*
 * IMAPCompressDeflateTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.SelectorLoop;
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
import java.util.zip.DataFormatException;

/**
 * Server-side RFC 4978 COMPRESS DEFLATE negotiation and compressed NOOP.
 */
public class IMAPCompressDeflateTest {

    @Test(timeout = 10000)
    public void testCompressDeflateThenNoop() throws Exception {
        ImapListener listener = new ImapListener();
        listener.setRealm(new AcceptingRealm());
        listener.setAllowPlaintextLogin(true);

        ImapProtocolHandler handler = new ImapProtocolHandler(listener);
        RecordingStubEndpoint endpoint = new RecordingStubEndpoint(143);
        handler.connected(endpoint);

        endpoint.clearResponses();
        sendPlain(handler, "a1 LOGIN user pass");
        endpoint.awaitLineContaining("a1 OK");

        endpoint.clearResponses();
        sendPlain(handler, "c1 COMPRESS DEFLATE");
        endpoint.awaitLineContaining("c1 OK");
        assertTrue(endpoint.findLineContaining("c1 OK").contains("DEFLATE active"));

        final ImapDeflateLayer clientDeflate = new ImapDeflateLayer();
        endpoint.setSendFilter(new RecordingStubEndpoint.SendFilter() {
            @Override
            public byte[] filter(byte[] outbound) {
                try {
                    return clientDeflate.inflate(ByteBuffer.wrap(outbound));
                } catch (DataFormatException e) {
                    fail("failed to inflate server response: " + e.getMessage());
                    return outbound;
                }
            }
        });

        endpoint.clearResponses();
        sendCompressed(handler, clientDeflate, "c2 NOOP");
        endpoint.awaitLineContaining("c2 OK");

        endpoint.clearResponses();
        sendCompressed(handler, clientDeflate, "c3 CAPABILITY");
        endpoint.awaitLineContaining("c3 OK");
        String capLine = endpoint.findLineContaining("* CAPABILITY");
        assertFalse("COMPRESS=DEFLATE must disappear after negotiation",
                capLine.contains("COMPRESS=DEFLATE"));

        clientDeflate.close();
    }

    private static void sendPlain(org.bluezoo.gumdrop.ProtocolHandler handler,
            String line) {
        byte[] data = (line + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private static void sendCompressed(org.bluezoo.gumdrop.ProtocolHandler handler,
            ImapDeflateLayer layer, String line) {
        byte[] plain = (line + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(layer.compressAndFlush(plain)));
    }

    private static final class AcceptingRealm implements Realm {
        private static final Set<SaslMechanism> SUPPORTED =
                Collections.unmodifiableSet(
                        EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN));

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
