/*
 * ConnectUdpUpgradeFlowTest.java
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

package org.bluezoo.gumdrop.http.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.HttpDatagramContext;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Feeds a complete HTTP/1.1 {@code 101} upgrade response, with and without
 * pipelined capsule bytes, through the real parsing path of
 * {@link ConnectUdpClientProtocolHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectUdpUpgradeFlowTest {

    private static final String SWITCH = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Connection: Upgrade\r\nUpgrade: connect-udp\r\nCapsule-Protocol: ?1\r\n\r\n";

    private final List<String> events = new ArrayList<String>();
    private final List<String> datagrams = new ArrayList<String>();

    private final class Events implements ConnectUdpEventHandler {
        @Override
        public void opened(ConnectUdpSession session) {
            events.add("opened");
        }

        @Override
        public void datagramReceived(ByteBuffer payload) {
            byte[] b = new byte[payload.remaining()];
            payload.get(b);
            datagrams.add(new String(b, StandardCharsets.US_ASCII));
        }

        @Override
        public void closed() {
            events.add("closed");
        }

        @Override
        public void error(Throwable cause) {
            events.add("error:" + cause);
        }
    }

    private static byte[] capsule(String text) {
        ByteBuffer encoded = HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID,
                ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
        byte[] ctx = new byte[encoded.remaining()];
        encoded.get(ctx);
        return Capsule.datagram(ctx).encode();
    }

    private ConnectUdpClientProtocolHandler connected() {
        ConnectUdpClientProtocolHandler h = new ConnectUdpClientProtocolHandler(
                null, new Events(), "proxy.example", 80, false);
        h.setH2cUpgradeEnabled(false);
        h.connected(new BinaryRecordingEndpoint());
        HttpRequest r = h.get("/.well-known/masque/udp/h/1/");
        r.header("connection", "upgrade");
        r.header("upgrade", "connect-udp");
        r.send(new DefaultHttpResponseHandler());
        return h;
    }

    @Test
    public void upgradeResponseAloneOpensTheTunnel() {
        ConnectUdpClientProtocolHandler h = connected();
        h.receive(ByteBuffer.wrap(SWITCH.getBytes(StandardCharsets.US_ASCII)));
        assertEquals("[opened]", events.toString());
    }

    @Test
    public void capsulesInTheSameBufferAsTheUpgradeResponseAreDelivered() throws Exception {
        ConnectUdpClientProtocolHandler h = connected();
        byte[] head = SWITCH.getBytes(StandardCharsets.US_ASCII);
        byte[] cap = capsule("early");
        byte[] both = new byte[head.length + cap.length];
        System.arraycopy(head, 0, both, 0, head.length);
        System.arraycopy(cap, 0, both, head.length, cap.length);
        h.receive(ByteBuffer.wrap(both));
        assertEquals("[opened]", events.toString());
        assertEquals("[early]", datagrams.toString());
    }

    @Test
    public void capsulesInALaterBufferAreDelivered() throws Exception {
        ConnectUdpClientProtocolHandler h = connected();
        h.receive(ByteBuffer.wrap(SWITCH.getBytes(StandardCharsets.US_ASCII)));
        h.receive(ByteBuffer.wrap(capsule("late")));
        assertEquals("[late]", datagrams.toString());
        h.disconnected();
        assertTrue(events.toString(), events.contains("closed"));
    }
}
