/*
 * NewSessionTicketEarlyDataTest.java
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

package org.bluezoo.gumdrop.tls;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A QUIC server sets the {@code early_data} extension of a NewSessionTicket
 * to 0xffffffff (RFC 9001 section 4.6.1). Read as a signed int that is -1,
 * which the client took for "no 0-RTT" and so never sent any: the 0-RTT
 * interop case failed against every server.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NewSessionTicketEarlyDataTest {

    private static final int EXT_EARLY_DATA = 0x002a;

    private static byte[] ticketWithEarlyData(int rawMaxEarlyDataSize) {
        WireWriter extBody = new WireWriter();
        extBody.u32(rawMaxEarlyDataSize);
        WireWriter ext = new WireWriter();
        ext.u16(EXT_EARLY_DATA);
        ext.opaque16(extBody.toByteArray());
        WireWriter body = new WireWriter();
        body.u32(3600);
        body.u32(0x01020304);
        body.opaque8(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        body.opaque16(new byte[] { 9, 9, 9 });
        body.opaque16(ext.toByteArray());
        return WireWriter.frameHandshakeMessage(HandshakeMessages.HANDSHAKE_TYPE_NEW_SESSION_TICKET,
                body.toByteArray());
    }

    @Test
    public void quicMaximumEarlyDataSizeIsPositive() throws Exception {
        HandshakeMessages.NewSessionTicket nst =
                HandshakeMessages.parseNewSessionTicket(ticketWithEarlyData(0xffffffff));
        assertTrue("0xffffffff must read as a positive size: " + nst.maxEarlyDataSize,
                nst.maxEarlyDataSize > 0);
    }

    // RFC 9001 section 4.6.1: a QUIC server MUST send exactly 0xffffffff, and
    // a client treats any other value as a connection error, so strict
    // clients (quic-go, ngtcp2, quiche) never used our tickets for 0-RTT.
    @Test
    public void builderWritesTheQuicMaximumAsFourFfBytes() throws Exception {
        byte[] message = HandshakeMessages.buildNewSessionTicket(3600, 1, new byte[8], new byte[] { 9 }, -1);
        int n = message.length;
        // the early_data extension is the last thing in the message: type, length 4, value
        assertEquals((byte) 0x00, message[n - 8]);
        assertEquals((byte) 0x2a, message[n - 7]);
        assertEquals((byte) 0x04, message[n - 5]);
        for (int i = 1; i <= 4; i++) {
            assertEquals((byte) 0xff, message[n - i]);
        }
    }

    @Test
    public void ordinaryEarlyDataSizeIsKept() throws Exception {
        HandshakeMessages.NewSessionTicket nst =
                HandshakeMessages.parseNewSessionTicket(ticketWithEarlyData(16384));
        assertEquals(16384, nst.maxEarlyDataSize);
    }
}
