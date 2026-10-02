/*
 * DnsZoneClientUdpTest.java
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

package org.bluezoo.gumdrop.dns.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsTsig;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.TsigKey;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;

import static org.junit.Assert.*;

/**
 * {@link DnsZoneClient} UDP exchanges (SOA query, NOTIFY, UPDATE, AXFR/IXFR
 * first round trip) through a scripted in-memory transport injected via the
 * package-private transport seam. No sockets, no real time: timers the
 * client schedules are captured and fired by hand.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsZoneClientUdpTest {

    /** Produces the datagram payload to answer with, or null for silence. */
    private interface Responder {
        byte[] respond(DnsMessage request) throws Exception;
    }

    private static final class StubTimer implements TimerHandle {
        final Runnable callback;
        boolean cancelled;

        StubTimer(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** In-memory transport answering each send from the responder. */
    private static class StubUdp implements DnsClientTransport {
        final Responder responder;
        final List<StubTimer> timers = new ArrayList<StubTimer>();
        DnsClientTransportHandler handler;
        boolean closed;
        int sends;

        StubUdp(Responder responder) {
            this.responder = responder;
        }

        @Override
        public void open(InetAddress server, int port, SelectorLoop loop,
                         DnsClientTransportHandler handler) throws IOException {
            this.handler = handler;
        }

        @Override
        public void send(ByteBuffer data) {
            sends++;
            try {
                DnsMessage req = DnsMessage.parse(data);
                byte[] out = responder.respond(req);
                if (out != null) {
                    handler.onReceive(ByteBuffer.wrap(out));
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            StubTimer t = new StubTimer(callback);
            timers.add(t);
            return t;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class Outcome implements DnsZoneClient.MessageCallback,
            DnsZoneClient.TransferCallback {
        DnsMessage message;
        List<DnsResourceRecord> records;
        Exception error;
        int calls;

        @Override
        public void onSuccess(DnsMessage response) {
            message = response;
            calls++;
        }

        @Override
        public void onSuccess(List<DnsResourceRecord> result) {
            records = result;
            calls++;
        }

        @Override
        public void onFailure(Exception e) {
            error = e;
            calls++;
        }
    }

    private final SelectorLoop loop = new InlineSelectorLoop();
    private final InetSocketAddress address =
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 5353);
    private StubUdp stub;

    @Before
    public void setUp() {
        stub = null;
    }

    @After
    public void tearDown() {
        DnsZoneClient.udpTransportSource = null;
    }

    private StubUdp install(Responder responder) {
        final StubUdp s = new StubUdp(responder);
        stub = s;
        DnsZoneClient.udpTransportSource = new DnsZoneClient.UdpTransportSource() {
            @Override
            public DnsClientTransport create() {
                return s;
            }
        };
        return s;
    }

    private static byte[] bytes(DnsMessage m) {
        ByteBuffer b = m.serialize();
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private static List<DnsResourceRecord> answers() throws Exception {
        List<DnsResourceRecord> l = new ArrayList<DnsResourceRecord>();
        l.add(DnsResourceRecord.soa("example.com.", 300, "ns.example.com.",
                "h.example.com.", 5, 1, 2, 3, 4));
        l.add(DnsResourceRecord.a("www.example.com.", 60,
                InetAddress.getByName("192.0.2.9")));
        return l;
    }

    private static final Responder ANSWERING = new Responder() {
        @Override
        public byte[] respond(DnsMessage request) throws Exception {
            return bytes(request.createResponse(answers()));
        }
    };

    private static final Responder SILENT = new Responder() {
        @Override
        public byte[] respond(DnsMessage request) {
            return null;
        }
    };

    private static final Responder REFUSING = new Responder() {
        @Override
        public byte[] respond(DnsMessage request) {
            return bytes(request.createErrorResponse(DnsMessage.RCODE_REFUSED));
        }
    };

    private static final Responder SIGNING = new Responder() {
        @Override
        public byte[] respond(DnsMessage request) throws Exception {
            DnsMessage resp = request.createResponse(answers());
            return bytes(DnsTsig.signResponse(resp, key(), request));
        }
    };

    private static TsigKey key() {
        return TsigKey.fromBase64("k.", TsigKey.HMAC_SHA256, "c2VjcmV0");
    }

    private static DnsResourceRecord soa() {
        return DnsResourceRecord.soa("example.com.", 300, "ns.example.com.",
                "h.example.com.", 1, 2, 3, 4, 5);
    }

    private DnsMessage update() throws Exception {
        return DnsMessage.createDynamicUpdate(7,
                Collections.singletonList(soa()),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(DnsResourceRecord.a("x.example.com.", 60,
                        InetAddress.getByName("192.0.2.5"))));
    }

    @Test
    public void querySoaGetsAnswer() throws Exception {
        install(ANSWERING);
        Outcome o = new Outcome();
        DnsZoneClient.querySoa(loop, address, "example.com.", 5000, o);
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(2, o.message.getAnswers().size());
        assertTrue(stub.closed);
    }

    @Test
    public void notifyDefaultAndExplicitTimeout() throws Exception {
        install(new Responder() {
            @Override
            public byte[] respond(DnsMessage request) throws Exception {
                assertEquals(DnsMessage.OPCODE_NOTIFY, request.getOpcode());
                return bytes(request.createResponse(
                        Collections.<DnsResourceRecord>emptyList()));
            }
        });
        Outcome o = new Outcome();
        DnsZoneClient.sendNotify(loop, address, "example.com.", o);
        assertNull(String.valueOf(o.error), o.error);
        assertNotNull(o.message);
        o = new Outcome();
        DnsZoneClient.sendNotify(loop, address, "example.com.", 5000, o);
        assertNotNull(o.message);
    }

    @Test
    public void updateWithoutTsig() throws Exception {
        install(ANSWERING);
        Outcome o = new Outcome();
        DnsZoneClient.sendUpdate(loop, address, update(), null, o);
        assertNotNull(o.message);
    }

    @Test
    public void updateWithTsigAcceptsSignedResponse() throws Exception {
        install(SIGNING);
        Outcome o = new Outcome();
        DnsZoneClient.sendUpdate(loop, address, update(), key(), 5000, o);
        assertNull(String.valueOf(o.error), o.error);
        assertNotNull(o.message);
    }

    @Test
    public void updateWithTsigRejectsUnsignedResponse() throws Exception {
        install(ANSWERING);
        Outcome o = new Outcome();
        DnsZoneClient.sendUpdate(loop, address, update(), key(), o);
        assertNotNull(o.error);
        assertTrue(o.error.getMessage().contains("TSIG"));
    }

    @Test
    public void malformedResponseFails() throws Exception {
        install(new Responder() {
            @Override
            public byte[] respond(DnsMessage request) {
                return new byte[] {1, 2, 3};
            }
        });
        Outcome o = new Outcome();
        DnsZoneClient.querySoa(loop, address, "example.com.", 5000, o);
        assertTrue(o.error instanceof IOException);
    }

    @Test
    public void silentServerTimesOutWhenTimerIsFired() throws Exception {
        install(SILENT);
        Outcome o = new Outcome();
        DnsZoneClient.querySoa(loop, address, "example.com.", 1, o);
        assertEquals(0, o.calls);
        assertEquals(1, stub.timers.size());
        stub.timers.get(0).callback.run();
        assertEquals(1, o.calls);
        assertEquals("DNS timeout", o.error.getMessage());
        assertTrue(stub.closed);
        // firing again, or a late answer, must not report twice
        stub.timers.get(0).callback.run();
        assertEquals(1, o.calls);
    }

    @Test
    public void transportErrorIsReportedOnce() throws Exception {
        install(SILENT);
        Outcome o = new Outcome();
        DnsZoneClient.querySoa(loop, address, "example.com.", 5000, o);
        stub.handler.onError(new IOException("unreachable"));
        assertEquals("unreachable", o.error.getMessage());
        stub.handler.onError(new IOException("again"));
        assertEquals(1, o.calls);
        assertTrue(stub.timers.get(0).cancelled);
    }

    @Test
    public void openFailureIsReported() throws Exception {
        final StubUdp failing = new StubUdp(SILENT) {
            @Override
            public void open(InetAddress server, int port, SelectorLoop loop,
                             DnsClientTransportHandler handler) throws IOException {
                throw new IOException("no socket");
            }
        };
        DnsZoneClient.udpTransportSource = new DnsZoneClient.UdpTransportSource() {
            @Override
            public DnsClientTransport create() {
                return failing;
            }
        };
        Outcome o = new Outcome();
        DnsZoneClient.querySoa(loop, address, "example.com.", 5000, o);
        assertEquals("no socket", o.error.getMessage());
        assertTrue(failing.closed);
    }

    @Test
    public void missingArgumentsReportFailure() throws Exception {
        Outcome o = new Outcome();
        DnsZoneClient.querySoa(null, address, "example.com.", 100, o);
        assertTrue(o.error instanceof IllegalArgumentException);
        o = new Outcome();
        DnsZoneClient.querySoa(loop, null, "example.com.", 100, o);
        assertTrue(o.error instanceof IllegalArgumentException);
        DnsZoneClient.querySoa(loop, address, "example.com.", 100, null);
        Outcome t = new Outcome();
        DnsZoneClient.axfrOverTcp(null, address, "example.com.", t);
        assertTrue(t.error instanceof IllegalArgumentException);
        t = new Outcome();
        DnsZoneClient.ixfrOverTcp(loop, null, "example.com.", soa(), t);
        assertTrue(t.error instanceof IllegalArgumentException);
        DnsZoneClient.axfrOverTcp(loop, address, "example.com.", 100, null);
    }

    @Test
    public void axfrOverUdpSucceedsWithAnswers() throws Exception {
        install(ANSWERING);
        Outcome o = new Outcome();
        DnsZoneClient.axfr(loop, address, "example.com.", (TsigKey) null, o);
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(2, o.records.size());
    }

    @Test
    public void axfrDefaultOverloadAndRefusal() throws Exception {
        install(REFUSING);
        Outcome o = new Outcome();
        DnsZoneClient.axfr(loop, address, "example.com.", o);
        assertTrue(o.error.getMessage().contains("AXFR failed"));
    }

    @Test
    public void axfrWithTsigVerifiesResponse() throws Exception {
        install(SIGNING);
        Outcome o = new Outcome();
        DnsZoneClient.axfr(loop, address, "example.com.", key(), o);
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(2, o.records.size());
    }

    @Test
    public void axfrWithTsigRejectsUnsigned() throws Exception {
        install(ANSWERING);
        Outcome o = new Outcome();
        DnsZoneClient.axfr(loop, address, "example.com.", key(), o);
        assertNotNull(o.error);
    }

    @Test
    public void ixfrOverUdpSucceeds() throws Exception {
        install(ANSWERING);
        Outcome o = new Outcome();
        DnsZoneClient.ixfr(loop, address, "example.com.", soa(), o);
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(2, o.records.size());
    }

    @Test
    public void ixfrRefusedWithTsigKey() throws Exception {
        install(REFUSING);
        Outcome o = new Outcome();
        DnsZoneClient.ixfr(loop, address, "example.com.", soa(), key(), o);
        assertNotNull(o.error);
    }

    @Test
    public void ixfrWithTsigVerifiesResponse() throws Exception {
        install(SIGNING);
        Outcome o = new Outcome();
        DnsZoneClient.ixfr(loop, address, "example.com.", soa(), key(), 5000, null, o);
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(DnsType.SOA, o.records.get(0).getType());
    }
}
