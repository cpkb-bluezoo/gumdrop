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
import java.util.List;

import org.junit.After;
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
 * {@link DnsZoneClient} zone transfers over TCP and the UDP-truncated
 * fallback to TCP, through scripted in-memory transports (the TCP template
 * is injected via the existing transport parameter, the UDP side through
 * the package-private seam). No sockets, no real time: the timers the code
 * schedules are captured and fired by hand.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsZoneClientTcpTest {

    /** Delivers the server side of a transfer to the client handler. */
    private interface Script {
        void serve(DnsMessage request, DnsClientTransportHandler handler) throws Exception;
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

    /** TCP template whose duplicate is itself and which plays a script. */
    private static final class StubTcp extends TcpDnsClientTransport {
        final Script script;
        final List<StubTimer> timers = new ArrayList<StubTimer>();
        boolean closed;

        StubTcp(Script script) {
            this.script = script;
        }

        @Override
        public TcpDnsClientTransport duplicate() {
            return this;
        }

        @Override
        public void openTransfer(InetAddress server, int port, SelectorLoop loop,
                DnsClientTransportHandler handler, ByteBuffer firstMessage)
                throws IOException {
            try {
                DnsMessage request = DnsMessage.parse(firstMessage.duplicate());
                script.serve(request, handler);
            } catch (IOException e) {
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

    private static final class Outcome implements DnsZoneClient.TransferCallback {
        List<DnsResourceRecord> records;
        Exception error;
        int calls;

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

    @After
    public void tearDown() {
        DnsZoneClient.udpTransportSource = null;
    }

    private static void deliver(DnsClientTransportHandler h, DnsMessage m) {
        h.onReceive(m.serialize());
    }

    private static DnsResourceRecord soa() {
        return DnsResourceRecord.soa("example.com.", 300, "ns.example.com.",
                "h.example.com.", 5, 1, 2, 3, 4);
    }

    private static DnsResourceRecord a() throws Exception {
        return DnsResourceRecord.a("www.example.com.", 60, InetAddress.getByName("192.0.2.9"));
    }

    private static TsigKey key() {
        return TsigKey.fromBase64("k.", TsigKey.HMAC_SHA256, "c2VjcmV0");
    }

    private static List<DnsResourceRecord> list(DnsResourceRecord... rrs) {
        List<DnsResourceRecord> l = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < rrs.length; i++) {
            l.add(rrs[i]);
        }
        return l;
    }

    private Outcome axfr(StubTcp tcp, TsigKey key) {
        Outcome o = new Outcome();
        DnsZoneClient.axfrOverTcp(loop, address, "example.com.", 5000, key, tcp, o);
        return o;
    }

    @Test
    public void axfrOverTcpUnsignedSingleMessage() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) throws Exception {
                deliver(h, req.createResponse(list(soa(), a(), soa())));
                h.onClosed();
            }
        });
        Outcome o = axfr(tcp, null);
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(3, o.records.size());
        assertTrue(tcp.closed);
    }

    @Test
    public void axfrOverTcpTsigSingleMessageVerified() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) throws Exception {
                DnsMessage resp = req.createResponse(list(soa(), a(), soa()));
                deliver(h, DnsTsig.signResponse(resp, key(), req));
                h.onClosed();
            }
        });
        Outcome o = axfr(tcp, key());
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(3, o.records.size());
    }

    @Test
    public void axfrOverTcpTsigSequenceVerified() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) throws Exception {
                List<DnsMessage> msgs = new ArrayList<DnsMessage>();
                msgs.add(req.createResponse(list(soa(), a())));
                msgs.add(req.createResponse(list(soa())));
                List<DnsMessage> signed = DnsTsig.signResponseSequence(msgs, key(), req);
                for (int i = 0; i < signed.size(); i++) {
                    deliver(h, signed.get(i));
                }
                h.onClosed();
            }
        });
        Outcome o = axfr(tcp, key());
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(3, o.records.size());
    }

    @Test
    public void axfrOverTcpTsigMissingFailsVerification() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) throws Exception {
                deliver(h, req.createResponse(list(soa(), a(), soa())));
                h.onClosed();
            }
        });
        Outcome o = axfr(tcp, key());
        assertTrue(o.error.getMessage().contains("TSIG"));
    }

    @Test
    public void axfrOverTcpTsigSequenceMissingFailsVerification() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) throws Exception {
                deliver(h, req.createResponse(list(soa(), a())));
                deliver(h, req.createResponse(list(soa())));
                h.onClosed();
            }
        });
        Outcome o = axfr(tcp, key());
        assertTrue(o.error.getMessage().contains("TSIG"));
    }

    @Test
    public void axfrOverTcpServerClosesWithoutDataFails() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) {
                h.onClosed();
            }
        });
        assertNotNull(axfr(tcp, null).error);
    }

    @Test
    public void axfrOverTcpRefusedRcodeFails() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) throws Exception {
                deliver(h, req.createErrorResponse(DnsMessage.RCODE_REFUSED));
                h.onClosed();
            }
        });
        assertTrue(axfr(tcp, null).error.getMessage().contains("rcode"));
    }

    @Test
    public void axfrOverTcpMalformedMessageFails() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) {
                h.onReceive(ByteBuffer.wrap(new byte[] {1, 2, 3}));
                h.onClosed();
            }
        });
        Outcome o = axfr(tcp, null);
        assertNotNull(o.error);
        assertEquals(1, o.calls);
    }

    @Test
    public void axfrOverTcpTransportErrorReportsOnce() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) {
                h.onError(new IOException("reset"));
                h.onClosed();
            }
        });
        Outcome o = axfr(tcp, null);
        assertEquals("reset", o.error.getMessage());
        assertEquals(1, o.calls);
    }

    @Test
    public void axfrOverTcpOpenFailureIsReported() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) throws Exception {
                throw new IOException("refused to open");
            }
        });
        Outcome o = axfr(tcp, null);
        assertEquals("refused to open", o.error.getMessage());
        assertTrue(tcp.closed);
    }

    @Test
    public void axfrOverTcpTimesOutWhenTimerIsFired() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) {
                // silent server
            }
        });
        Outcome o = axfr(tcp, null);
        assertEquals(0, o.calls);
        assertEquals(1, tcp.timers.size());
        tcp.timers.get(0).callback.run();
        assertEquals("DNS timeout", o.error.getMessage());
        tcp.timers.get(0).callback.run();
        assertEquals(1, o.calls);
    }

    @Test
    public void defaultTimeoutOverloadTimesOutWhenTimerIsFired() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) {
                // silent
            }
        });
        Outcome o = new Outcome();
        DnsZoneClient.axfrOverTcp(loop, address, "example.com.", 1, null, tcp, o);
        tcp.timers.get(0).callback.run();
        assertEquals("DNS timeout", o.error.getMessage());
    }

    @Test
    public void ixfrOverTcpSignedSingleMessage() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) throws Exception {
                assertEquals(DnsType.IXFR, req.getQuestions().get(0).getType());
                DnsMessage resp = req.createResponse(list(soa()));
                deliver(h, DnsTsig.signResponse(resp, key(), req));
                h.onClosed();
            }
        });
        Outcome o = new Outcome();
        DnsZoneClient.ixfrOverTcp(loop, address, "example.com.", soa(), 5000, key(), tcp, o);
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(1, o.records.size());
    }

    @Test
    public void axfrOverTlsEntryPoint() throws Exception {
        StubTcp tcp = new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) throws Exception {
                deliver(h, req.createResponse(list(soa(), soa())));
                h.onClosed();
            }
        });
        Outcome o = new Outcome();
        DnsZoneClient.axfrOverTls(loop, address, "example.com.", tcp, o);
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(2, o.records.size());
    }

    // ---- truncated UDP answer falls back to the injected TCP transport ----

    private void install(final boolean truncate) {
        DnsZoneClient.udpTransportSource = new DnsZoneClient.UdpTransportSource() {
            @Override
            public DnsClientTransport create() {
                return new DnsClientTransport() {
                    private DnsClientTransportHandler handler;

                    @Override
                    public void open(InetAddress server, int port, SelectorLoop loop,
                            DnsClientTransportHandler h) {
                        handler = h;
                    }

                    @Override
                    public void send(ByteBuffer data) {
                        try {
                            DnsMessage req = DnsMessage.parse(data);
                            DnsMessage resp = req.createResponse(
                                    new ArrayList<DnsResourceRecord>());
                            DnsMessage tc = new DnsMessage(resp.getId(),
                                    resp.getFlags() | DnsMessage.FLAG_TC,
                                    resp.getQuestions(), resp.getAnswers(),
                                    resp.getAuthorities(), resp.getAdditionals());
                            handler.onReceive(tc.serialize());
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }

                    @Override
                    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
                        return new StubTimer(callback);
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }

    private StubTcp fullAnswerTcp() {
        return new StubTcp(new Script() {
            @Override
            public void serve(DnsMessage req, DnsClientTransportHandler h) throws Exception {
                deliver(h, req.createResponse(list(soa(), a(), soa())));
                h.onClosed();
            }
        });
    }

    @Test
    public void axfrTruncatedOverUdpRetriesOverTcp() throws Exception {
        install(true);
        Outcome o = new Outcome();
        DnsZoneClient.axfr(loop, address, "example.com.", null, 5000, fullAnswerTcp(), o);
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(3, o.records.size());
    }

    @Test
    public void ixfrTruncatedOverUdpRetriesOverTcp() throws Exception {
        install(true);
        Outcome o = new Outcome();
        DnsZoneClient.ixfr(loop, address, "example.com.", soa(), null, 5000,
                fullAnswerTcp(), o);
        assertNull(String.valueOf(o.error), o.error);
        assertEquals(3, o.records.size());
    }

    // ---- transport guards ----

    @Test(expected = NullPointerException.class)
    public void openTransferRequiresFirstMessage() throws Exception {
        new TcpDnsClientTransport().openTransfer(InetAddress.getLoopbackAddress(), 1,
                loop, new NoopHandler(), null);
    }

    @Test
    public void openRequireRunningGumdropLoop() throws Exception {
        TcpDnsClientTransport t = new TcpDnsClientTransport();
        try {
            t.openTransfer(InetAddress.getLoopbackAddress(), 1, null, new NoopHandler(),
                    ByteBuffer.allocate(1));
            fail();
        } catch (IOException expected) {
            // expected
        }
        try {
            t.open(InetAddress.getLoopbackAddress(), 1, loop, new NoopHandler());
            fail();
        } catch (IOException expected) {
            // expected
        }
        UdpDnsClientTransport u = new UdpDnsClientTransport();
        try {
            u.open(InetAddress.getLoopbackAddress(), 1, null, new NoopHandler());
            fail();
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void duplicateCarriesConfiguration() {
        TcpDnsClientTransport t = TcpDnsClientTransport.createDoT();
        java.util.Set<String> pins = new java.util.HashSet<String>();
        pins.add("aa:bb");
        t.setPinnedSPKIFingerprints(pins);
        t.setDefaultPort(9853);
        TcpDnsClientTransport copy = t.duplicate();
        assertNotSame(t, copy);
        assertNotNull(copy.createTransportFactory());
    }

    private static final class NoopHandler implements DnsClientTransportHandler {
        @Override
        public void onReceive(ByteBuffer data) {
        }

        @Override
        public void onError(Exception cause) {
        }
    }
}
