/*
 * QuicTlsClientEngineTest.java
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

package org.bluezoo.gumdrop.quic.tls;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.SessionTicket;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link QuicTlsClientEngine}'s named-group resolution
 * ({@code setNamedGroups} wiring) -- verifies the string configured on
 * {@code QuicTransportFactory} actually resolves to the {@link
 * NamedGroup} list the underlying {@code HandshakeEngine} is configured
 * to offer, rather than being silently ignored.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicTlsClientEngineTest {

    private static final NoopListener LISTENER = new NoopListener();

    private static List<NamedGroup> resolvedGroups(String namedGroups) throws Exception {
        QuicTlsClientEngine engine = new QuicTlsClientEngine(
                new TransportParameters(), LISTENER, null, namedGroups);
        Field f = QuicTlsClientEngine.class.getDeclaredField("config");
        f.setAccessible(true);
        HandshakeConfig config = (HandshakeConfig) f.get(engine);
        return config.getNamedGroups();
    }

    @Test
    public void testNullNamedGroupsResolvesToDefaultOrder() throws Exception {
        // No override -- HandshakeConfig's own default (hybrid PQC group first) applies.
        assertEquals(NamedGroup.X25519_MLKEM768, resolvedGroups(null).get(0));
    }

    @Test
    public void testEmptyNamedGroupsResolvesToDefaultOrder() throws Exception {
        assertEquals(NamedGroup.X25519_MLKEM768, resolvedGroups("").get(0));
    }

    @Test
    public void testSingleSupportedGroupResolves() throws Exception {
        assertEquals(List.of(NamedGroup.X25519), resolvedGroups("x25519"));
    }

    @Test
    public void testCaseInsensitiveResolution() throws Exception {
        assertEquals(List.of(NamedGroup.SECP256R1), resolvedGroups("SECP256R1"));
    }

    @Test
    public void testMultipleSupportedNamesPreserveConfiguredOrder() throws Exception {
        assertEquals(List.of(NamedGroup.SECP384R1, NamedGroup.X25519),
                resolvedGroups("secp384r1:x25519"));
    }

    @Test
    public void testUnsupportedNameSkippedInFavorOfLaterSupportedOne() throws Exception {
        // "x448" is a real IANA group name this engine does not implement
        // -- must be skipped, not cause the whole list to be discarded.
        assertEquals(List.of(NamedGroup.SECP384R1), resolvedGroups("x448:secp384r1"));
    }

    @Test
    public void testAllUnsupportedNamesFallBackToDefaultOrder() throws Exception {
        assertEquals(NamedGroup.X25519_MLKEM768, resolvedGroups("x448:x448ml").get(0));
    }

    @Test
    public void testBlankTokensInListIgnored() throws Exception {
        assertEquals(List.of(NamedGroup.X25519), resolvedGroups(":: x25519 :"));
    }

    private static final class NoopListener implements QuicTlsEngineListener {
        @Override
        public void cryptoDataReady(EncryptionLevel level, long offset, byte[] data) {
        }

        @Override
        public void handshakeSecretsAvailable() {
        }

        @Override
        public void handshakeFinished() {
        }

        @Override
        public void transportParametersReceived(TransportParameters transportParameters) {
        }

        @Override
        public void earlySecretsAvailable() {
        }

        @Override
        public void newSessionTicketReceived(SessionTicket ticket) {
        }

        @Override
        public void earlyDataOutcomeKnown(boolean accepted) {
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void cryptoProcessingFailed(EncryptionLevel level, Throwable cause) {
        }
    }

}
