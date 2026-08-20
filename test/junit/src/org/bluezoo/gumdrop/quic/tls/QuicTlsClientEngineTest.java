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

import tech.kwik.agent15.NewSessionTicket;
import tech.kwik.agent15.TlsConstants;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link QuicTlsClientEngine}'s named-group resolution
 * ({@code setNamedGroups} wiring) -- verifies the string configured on
 * {@code QuicTransportFactory} actually resolves to the {@link
 * TlsConstants.NamedGroup} offered in the handshake, rather than being
 * silently ignored.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicTlsClientEngineTest {

    private static final NoopListener LISTENER = new NoopListener();

    private static TlsConstants.NamedGroup preferredGroup(String namedGroups) throws Exception {
        QuicTlsClientEngine engine = new QuicTlsClientEngine(
                new TransportParameters(), LISTENER, null, namedGroups);
        Field f = QuicTlsClientEngine.class.getDeclaredField("preferredNamedGroup");
        f.setAccessible(true);
        return (TlsConstants.NamedGroup) f.get(engine);
    }

    @Test
    public void testNullNamedGroupsResolvesToNull() throws Exception {
        assertNull(preferredGroup(null));
    }

    @Test
    public void testEmptyNamedGroupsResolvesToNull() throws Exception {
        assertNull(preferredGroup(""));
    }

    @Test
    public void testSingleSupportedGroupResolves() throws Exception {
        assertEquals(TlsConstants.NamedGroup.x25519, preferredGroup("x25519"));
    }

    @Test
    public void testCaseInsensitiveResolution() throws Exception {
        assertEquals(TlsConstants.NamedGroup.secp256r1, preferredGroup("SECP256R1"));
    }

    @Test
    public void testFirstSupportedNameInListWins() throws Exception {
        // First name ("x448") is supported by Agent15, so it should win
        // even though other names follow.
        assertEquals(TlsConstants.NamedGroup.x448, preferredGroup("x448:secp256r1"));
    }

    @Test
    public void testUnsupportedNameSkippedInFavorOfLaterSupportedOne() throws Exception {
        // "X25519MLKEM768" is a real IANA hybrid PQC group name Agent15
        // does not implement (no ML-KEM support at all) -- must be
        // skipped, not cause the whole list to be discarded.
        assertEquals(TlsConstants.NamedGroup.secp384r1,
                preferredGroup("X25519MLKEM768:secp384r1"));
    }

    @Test
    public void testAllUnsupportedNamesResolveToNull() throws Exception {
        // Neither name is something Agent15's NamedGroup enum defines --
        // must fall back to null (Agent15's own default), not throw.
        assertNull(preferredGroup("X25519MLKEM768:MLKEM768"));
    }

    @Test
    public void testBlankTokensInListIgnored() throws Exception {
        assertEquals(TlsConstants.NamedGroup.x25519, preferredGroup(":: x25519 :"));
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
        public void newSessionTicketReceived(NewSessionTicket ticket) {
        }

        @Override
        public void earlyDataOutcomeKnown(boolean accepted) {
        }
    }

}
