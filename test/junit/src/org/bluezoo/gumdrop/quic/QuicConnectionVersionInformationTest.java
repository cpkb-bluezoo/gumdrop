/*
 * QuicConnectionVersionInformationTest.java
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

package org.bluezoo.gumdrop.quic;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.junit.Test;

import org.bluezoo.gumdrop.quic.packet.QuicVersion;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The version_information checks of RFC 9368 section 4 as applied to a
 * connection that receives its peer's transport parameters: the errors
 * that must close the connection, and compatible version negotiation on
 * a server.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicConnectionVersionInformationTest {

    private static final int V1 = 1;
    private static final int V2 = 0x6b3343cf;
    private static final long TRANSPORT_PARAMETER_ERROR = 0x8;
    private static final long VERSION_NEGOTIATION_ERROR = 0x11;

    private static QuicEngine engine(String versions) {
        QuicTransportFactory factory = new QuicTransportFactory();
        factory.setVersions(versions);
        QuicEngine engine = new QuicEngine(factory, true);
        engine.init(new QuicDatagramPath() {
            @Override
            public int send(SocketAddress address, ByteBuffer packet) {
                return packet.remaining();
            }

            @Override
            public SocketAddress getLocalAddress() {
                return null;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        });
        return engine;
    }

    private static QuicConnection connection(QuicEngine engine, boolean server, QuicVersion version,
            boolean afterVersionNegotiation) {
        byte[] cid = { 1, 2, 3, 4, 5, 6, 7, 8 };
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 4433);
        TransportParameters local = new TransportParameters();
        local.setVersionInformation(version.getWireValue(), new int[] { version.getWireValue() });
        return new QuicConnection(engine, server, addr, addr, cid, cid, cid, local, new byte[32], version,
                afterVersionNegotiation);
    }

    private static TransportParameters versionInformation(int chosen, int... available) {
        TransportParameters params = new TransportParameters();
        params.setVersionInformation(chosen, available);
        return params;
    }

    private static long closeCode(QuicConnection conn) throws Exception {
        Field f = QuicConnection.class.getDeclaredField("deferredCloseErrorCode");
        f.setAccessible(true);
        return f.getLong(conn);
    }

    private static void assertClosedWith(long code, QuicConnection conn) throws Exception {
        assertTrue("connection must be closed", conn.isClosed());
        assertEquals(code, closeCode(conn));
    }

    @Test
    public void testClientClosesWhenServerChosenVersionIsNotTheNegotiatedOne() throws Exception {
        QuicConnection conn = connection(engine("1,2"), false, QuicVersion.V1, false);
        conn.transportParametersReceived(versionInformation(V2, V2, V1));
        assertClosedWith(VERSION_NEGOTIATION_ERROR, conn);
    }

    @Test
    public void testClientAcceptsMatchingServerVersionInformation() throws Exception {
        QuicConnection conn = connection(engine("1,2"), false, QuicVersion.V1, false);
        conn.transportParametersReceived(versionInformation(V1, V1, V2));
        assertFalse(conn.isClosed());
    }

    @Test
    public void testClientToleratesMissingVersionInformationUnlessItReactedToVersionNegotiation() throws Exception {
        QuicConnection plain = connection(engine("1,2"), false, QuicVersion.V1, false);
        plain.transportParametersReceived(new TransportParameters());
        assertFalse(plain.isClosed());

        QuicConnection reacted = connection(engine("1,2"), false, QuicVersion.V1, true);
        reacted.transportParametersReceived(new TransportParameters());
        assertClosedWith(VERSION_NEGOTIATION_ERROR, reacted);
    }

    @Test
    public void testClientDetectsForgedVersionNegotiation() throws Exception {
        // The client prefers v2; an attacker's forged Version Negotiation
        // packet listed only v1, but the server also offers v2.
        QuicConnection conn = connection(engine("2,1"), false, QuicVersion.V1, true);
        conn.transportParametersReceived(versionInformation(V1, V2, V1));
        assertClosedWith(VERSION_NEGOTIATION_ERROR, conn);
    }

    @Test
    public void testClientAcceptsGenuineVersionNegotiation() throws Exception {
        QuicConnection conn = connection(engine("2,1"), false, QuicVersion.V1, true);
        conn.transportParametersReceived(versionInformation(V1, V1));
        assertFalse(conn.isClosed());
    }

    @Test
    public void testClientClosesOnEmptyAvailableVersionsAfterVersionNegotiation() throws Exception {
        QuicConnection conn = connection(engine("2,1"), false, QuicVersion.V1, true);
        conn.transportParametersReceived(versionInformation(V1));
        assertClosedWith(VERSION_NEGOTIATION_ERROR, conn);
    }

    @Test
    public void testMalformedVersionInformationClosesWithTransportParameterError() throws Exception {
        TransportParameters malformed = TransportParameters.decode(ByteBuffer.wrap(new byte[] { 0x11, 0x02, 0, 0 }));
        QuicConnection client = connection(engine("1,2"), false, QuicVersion.V1, false);
        client.transportParametersReceived(malformed);
        assertClosedWith(TRANSPORT_PARAMETER_ERROR, client);
        QuicConnection server = connection(engine("1,2"), true, QuicVersion.V1, false);
        server.transportParametersReceived(malformed);
        assertClosedWith(TRANSPORT_PARAMETER_ERROR, server);
    }

    @Test
    public void testServerClosesWhenClientChosenVersionDiffersFromVersionInUse() throws Exception {
        QuicConnection conn = connection(engine("1,2"), true, QuicVersion.V1, false);
        conn.transportParametersReceived(versionInformation(V2, V2, V1));
        assertClosedWith(VERSION_NEGOTIATION_ERROR, conn);
    }

    @Test
    public void testServerClosesWhenChosenVersionIsNotAvailable() throws Exception {
        QuicConnection conn = connection(engine("1,2"), true, QuicVersion.V1, false);
        conn.transportParametersReceived(versionInformation(V1, V2));
        assertClosedWith(TRANSPORT_PARAMETER_ERROR, conn);
    }

    @Test
    public void testServerSwitchesToClientsPreferredCompatibleVersion() throws Exception {
        QuicConnection conn = connection(engine("1,2"), true, QuicVersion.V1, false);
        conn.transportParametersReceived(versionInformation(V1, V2, V1));
        assertFalse(conn.isClosed());
        assertEquals(QuicVersion.V2, conn.getVersion());
    }

    @Test
    public void testServerStaysInVersionWhenItCannotAcceptTheClientsPreference() throws Exception {
        QuicConnection conn = connection(engine("1"), true, QuicVersion.V1, false);
        conn.transportParametersReceived(versionInformation(V1, V2, V1));
        assertFalse(conn.isClosed());
        assertEquals(QuicVersion.V1, conn.getVersion());
    }

    @Test
    public void testServerWithoutClientVersionInformationStaysInVersion() throws Exception {
        QuicConnection conn = connection(engine("1,2"), true, QuicVersion.V1, false);
        conn.transportParametersReceived(new TransportParameters());
        assertFalse(conn.isClosed());
        assertEquals(QuicVersion.V1, conn.getVersion());
    }
}
