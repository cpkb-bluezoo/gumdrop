/*
 * IMAPUtf8AcceptTest.java
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
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
