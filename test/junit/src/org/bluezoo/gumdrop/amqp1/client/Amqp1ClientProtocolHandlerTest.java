/*
 * Amqp1ClientProtocolHandlerTest.java
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

package org.bluezoo.gumdrop.amqp1.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.amqp1.client.FakeAmqp1Peer.Out;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Frame;
import org.bluezoo.gumdrop.amqp1.codec.Begin;
import org.bluezoo.gumdrop.amqp1.codec.Close;
import org.bluezoo.gumdrop.amqp1.codec.End;
import org.bluezoo.gumdrop.amqp1.codec.Open;
import org.bluezoo.gumdrop.amqp1.codec.SaslChallenge;
import org.bluezoo.gumdrop.amqp1.codec.SaslInit;
import org.bluezoo.gumdrop.amqp1.codec.SaslMechanisms;
import org.bluezoo.gumdrop.amqp1.codec.SaslOutcome;
import org.bluezoo.gumdrop.amqp1.codec.SaslResponse;
import org.bluezoo.gumdrop.auth.SaslClientMechanism;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link Amqp1ClientProtocolHandler} against an in-process
 * fake broker: the SASL and AMQP handshake, sessions, close, idle
 * timeouts and error handling, with input also fed one byte at a time.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Amqp1ClientProtocolHandlerTest {

    private FakeAmqp1Peer peer;
    private Recorder ready;
    private TestClock clock;
    private Amqp1ClientProtocolHandler handler;

    @Before
    public void setUp() {
        peer = new FakeAmqp1Peer();
        ready = new Recorder();
        clock = new TestClock();
        handler = new Amqp1ClientProtocolHandler(ready, clock);
    }

    // ── helpers ──

    private void feed(ByteBuffer... parts) {
        handler.receive(ByteBuffer.wrap(FakeAmqp1Peer.concat(parts)));
    }

    private void feedByteByByte(ByteBuffer... parts) {
        byte[] data = FakeAmqp1Peer.concat(parts);
        ByteBuffer buf = ByteBuffer.allocate(data.length + 16);
        for (int i = 0; i < data.length; i++) {
            buf.put(data[i]);
            buf.flip();
            handler.receive(buf);
            buf.compact();
        }
        assertEquals("all input consumed", 0, buf.position());
    }

    private Open peerOpen(String container) {
        return new Open(container);
    }

    /** Connects and completes SASL with ANONYMOUS, leaving the connection ready to open. */
    private void connectAndAuthenticate() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("ANONYMOUS", "PLAIN"))));
        ready.handshake.authenticateAnonymous(null, ready.auth);
        feed(FakeAmqp1Peer.saslFrame(new SaslOutcome(SaslOutcome.OK, null)));
    }

    /** Runs the whole handshake, leaving an open connection. */
    private void openConnection() {
        openConnection(new Open("client-1"));
    }

    private void openConnection(Open ours) {
        connectAndAuthenticate();
        ready.auth.opener.open(ours, ready.openHandler);
        feed(FakeAmqp1Peer.amqpHeader(), FakeAmqp1Peer.amqpFrame(0, peerOpen("broker")));
        assertNotNull("connection should be open", ready.openHandler.connection);
    }

    private void beginSession() {
        SessionRecorder rec = new SessionRecorder();
        ready.openHandler.connection.beginSession(rec);
        ready.sessions.add(rec);
    }

    private static List<String> kinds(List<Out> out) {
        List<String> k = new ArrayList<String>();
        for (Out o : out) {
            k.add(o.toString());
        }
        return k;
    }

    // ── handshake ──

    @Test
    public void testConnectSendsSaslHeaderFirst() {
        handler.connected(peer);
        assertEquals(Arrays.asList("header 3"), kinds(peer.output()));
        assertTrue(ready.connected);
    }

    @Test
    public void testMechanismsAreDelivered() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("PLAIN", "ANONYMOUS"))));
        assertEquals(Arrays.asList("PLAIN", "ANONYMOUS"), ready.mechanisms);
        assertNotNull(ready.handshake);
    }

    @Test
    public void testPlainSendsInitialResponse() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("PLAIN"))));
        ready.handshake.authenticate("alice", "s3cret", ready.auth);
        List<Out> out = peer.output();
        assertEquals("sasl/0 SaslInit", out.get(1).toString());
        SaslInit init = (SaslInit) out.get(1).performative;
        assertEquals("PLAIN", init.getMechanism());
        assertArrayEquals("\0alice\0s3cret".getBytes(StandardCharsets.UTF_8), init.getInitialResponse());
    }

    @Test
    public void testAnonymousSendsTrace() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("ANONYMOUS"))));
        ready.handshake.authenticateAnonymous("guest@example.org", ready.auth);
        SaslInit init = (SaslInit) peer.output().get(1).performative;
        assertEquals("ANONYMOUS", init.getMechanism());
        assertArrayEquals("guest@example.org".getBytes(StandardCharsets.UTF_8),
                init.getInitialResponse());
    }

    @Test
    public void testAnonymousWithoutTraceSendsNoInitialResponse() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("ANONYMOUS"))));
        ready.handshake.authenticateAnonymous(null, ready.auth);
        assertNull(((SaslInit) peer.output().get(1).performative).getInitialResponse());
    }

    @Test
    public void testSaslSuccessSendsAmqpHeaderThenAuthenticates() {
        connectAndAuthenticate();
        List<Out> out = peer.output();
        assertEquals(Arrays.asList("header 3", "sasl/0 SaslInit", "header 0"), kinds(out));
        assertNotNull("application told it is authenticated", ready.auth.opener);
    }

    @Test
    public void testFullOpenHandshake() {
        openConnection();
        List<Out> out = peer.output();
        assertEquals(Arrays.asList("header 3", "sasl/0 SaslInit", "header 0", "amqp/0 Open"),
                kinds(out));
        Open sent = (Open) out.get(3).performative;
        assertEquals("client-1", sent.getContainerId());
        assertEquals(65536L, sent.getMaxFrameSize());
        assertEquals("broker", ready.openHandler.peerOpen.getContainerId());
        assertTrue(ready.openHandler.connection.getChannelMax() > 0);
    }

    @Test
    public void testConvenienceOpenSendsHostnameAndDefaults() {
        connectAndAuthenticate();
        ready.auth.opener.open("c", "broker.example.org", ready.openHandler);
        Open sent = (Open) peer.output().get(3).performative;
        assertEquals("c", sent.getContainerId());
        assertEquals("broker.example.org", sent.getHostname());
        assertEquals(65536L, sent.getMaxFrameSize());
        assertEquals(0L, sent.getIdleTimeOut());
    }

    @Test
    public void testHandshakeWithInputOneByteAtATime() {
        handler.connected(peer);
        feedByteByByte(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("ANONYMOUS"))));
        assertEquals(Arrays.asList("ANONYMOUS"), ready.mechanisms);
        ready.handshake.authenticateAnonymous(null, ready.auth);
        feedByteByByte(FakeAmqp1Peer.saslFrame(new SaslOutcome(SaslOutcome.OK, null)),
                FakeAmqp1Peer.amqpHeader(),
                FakeAmqp1Peer.amqpFrame(0, peerOpen("broker")));
        assertNotNull(ready.auth.opener);
        ready.auth.opener.open("c", null, ready.openHandler);
        assertEquals("broker", ready.openHandler.peerOpen.getContainerId());
    }

    @Test
    public void testPeerOpenBeforeOurOpenIsHeldUntilWeOpen() {
        connectAndAuthenticate();
        feed(FakeAmqp1Peer.amqpHeader(), FakeAmqp1Peer.amqpFrame(0, peerOpen("broker")));
        assertNull("not open until we have opened too", ready.openHandler.connection);
        ready.auth.opener.open("c", null, ready.openHandler);
        assertNotNull(ready.openHandler.connection);
    }

    @Test
    public void testMultiRoundSaslChallenge() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("X-CHALLENGE"))));
        ready.handshake.authenticate(new SaslClientMechanism() {
            @Override
            public String getMechanismName() {
                return "X-CHALLENGE";
            }

            @Override
            public boolean hasInitialResponse() {
                return false;
            }

            @Override
            public byte[] evaluateChallenge(byte[] challenge) {
                return ("re:" + new String(challenge, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public boolean isComplete() {
                return false;
            }
        }, ready.auth);
        assertNull(((SaslInit) peer.output().get(1).performative).getInitialResponse());
        feed(FakeAmqp1Peer.saslFrame(new SaslChallenge("nonce".getBytes(StandardCharsets.UTF_8))));
        SaslResponse response = (SaslResponse) peer.output().get(2).performative;
        assertArrayEquals("re:nonce".getBytes(StandardCharsets.UTF_8), response.getData());
        feed(FakeAmqp1Peer.saslFrame(new SaslOutcome(SaslOutcome.OK, null)));
        assertNotNull(ready.auth.opener);
    }

    @Test
    public void testMechanismEvaluationOffloadedToExecutor() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("ANONYMOUS"))));
        final List<Runnable> queued = new ArrayList<Runnable>();
        java.util.concurrent.ExecutorService executor =
                new java.util.concurrent.AbstractExecutorService() {
                    @Override
                    public void execute(Runnable r) {
                        queued.add(r);
                    }

                    @Override
                    public void shutdown() {
                    }

                    @Override
                    public List<Runnable> shutdownNow() {
                        return null;
                    }

                    @Override
                    public boolean isShutdown() {
                        return false;
                    }

                    @Override
                    public boolean isTerminated() {
                        return false;
                    }

                    @Override
                    public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) {
                        return false;
                    }
                };
        ready.handshake.authenticate(new Amqp1AnonymousMechanism("t"), ready.auth, executor);
        assertEquals("no init until the worker has run", 1, peer.output().size());
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals("sasl/0 SaslInit", peer.output().get(1).toString());
    }

    @Test
    public void testMechanismNotOfferedFailsConnection() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("ANONYMOUS"))));
        ready.handshake.authenticate("u", "p", ready.auth); // PLAIN not offered
        assertNotNull(ready.error);
        assertTrue(ready.error.getMessage().contains("PLAIN"));
        assertTrue(peer.closed);
    }

    @Test
    public void testSaslFailureReportedAndConnectionClosed() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("PLAIN"))));
        ready.handshake.authenticate("u", "wrong", ready.auth);
        feed(FakeAmqp1Peer.saslFrame(new SaslOutcome(SaslOutcome.AUTH, null)));
        assertEquals(SaslOutcome.AUTH, ready.auth.failureCode);
        assertNull(ready.auth.opener);
        assertTrue(peer.closed);
    }

    @Test
    public void testServerWithoutSaslIsAnError() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.amqpHeader());
        assertNotNull(ready.error);
        assertTrue(peer.closed);
    }

    @Test
    public void testUnsupportedVersionIsAnError() {
        handler.connected(peer);
        handler.receive(ByteBuffer.wrap(new byte[] {'A', 'M', 'Q', 'P', 3, 1, 1, 0}));
        assertNotNull(ready.error);
        assertTrue(ready.error.getMessage().contains("1.1.0"));
    }

    @Test
    public void testGarbageInsteadOfHeaderIsAnError() {
        handler.connected(peer);
        handler.receive(ByteBuffer.wrap("HTTP/1.1 400 Bad Request\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertNotNull(ready.error);
        assertTrue(peer.closed);
    }

    @Test
    public void testAmqpFrameDuringSaslIsAnError() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(), FakeAmqp1Peer.amqpFrame(0, peerOpen("x")));
        assertNotNull(ready.error);
    }

    @Test
    public void testSaslFrameAfterSaslCompleteIsAnError() {
        connectAndAuthenticate();
        feed(FakeAmqp1Peer.amqpHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("PLAIN"))));
        assertNotNull(ready.error);
    }

    @Test
    public void testSaslOutcomeWithoutInitIsAnError() {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("PLAIN"))),
                FakeAmqp1Peer.saslFrame(new SaslOutcome(SaslOutcome.OK, null)));
        assertNotNull(ready.error);
    }

    @Test
    public void testSecurityEstablishedIsForwarded() {
        SecurityInfo info = null;
        handler.securityEstablished(info);
        assertTrue(ready.securityEstablished);
    }

    // ── open parameters ──

    @Test(expected = IllegalArgumentException.class)
    public void testMaxFrameSizeBelowMinimumRejected() {
        connectAndAuthenticate();
        Open o = new Open("c");
        o.setMaxFrameSize(511);
        ready.auth.opener.open(o, ready.openHandler);
    }

    @Test(expected = IllegalStateException.class)
    public void testOpenTwiceRejected() {
        connectAndAuthenticate();
        ready.auth.opener.open("c", null, ready.openHandler);
        ready.auth.opener.open("c", null, ready.openHandler);
    }

    @Test
    public void testChannelMaxIsTheLowerOfBoth() {
        Open ours = new Open("c");
        ours.setChannelMax(10);
        connectAndAuthenticate();
        ready.auth.opener.open(ours, ready.openHandler);
        Open theirs = new Open("broker");
        theirs.setChannelMax(3);
        feed(FakeAmqp1Peer.amqpHeader(), FakeAmqp1Peer.amqpFrame(0, theirs));
        assertEquals(3, ready.openHandler.connection.getChannelMax());
    }

    @Test
    public void testPeerMaxFrameSizeIsExposed() {
        connectAndAuthenticate();
        ready.auth.opener.open("c", null, ready.openHandler);
        Open theirs = new Open("broker");
        theirs.setMaxFrameSize(4096);
        feed(FakeAmqp1Peer.amqpHeader(), FakeAmqp1Peer.amqpFrame(0, theirs));
        assertEquals(4096L, ready.openHandler.connection.getPeerMaxFrameSize());
    }

    @Test
    public void testFrameLargerThanOurMaxFrameSizeIsAnError() {
        Open ours = new Open("c");
        ours.setMaxFrameSize(1024);
        openConnection(ours);
        // a 2000-octet frame: the parser must reject it from the header alone
        handler.receive(ByteBuffer.wrap(new byte[] {0, 0, 0x07, (byte) 0xD0, 2, 0, 0, 0}));
        assertNotNull(ready.error);
        Out last = lastOut();
        assertEquals("amqp/0 Close", last.toString());
        assertEquals(Amqp1Error.FRAMING_ERROR, ((Close) last.performative).getError().getCondition());
    }

    private Out lastOut() {
        List<Out> out = peer.output();
        return out.get(out.size() - 1);
    }

    // ── sessions ──

    @Test
    public void testBeginSessionSendsBeginOnChannelZero() {
        openConnection();
        beginSession();
        Out last = lastOut();
        assertEquals("amqp/0 Begin", last.toString());
        Begin begin = (Begin) last.performative;
        assertNull(begin.getRemoteChannel());
        assertEquals(Amqp1Connection.DEFAULT_WINDOW, begin.getIncomingWindow());
    }

    @Test
    public void testSessionBegunWhenPeerAnswers() {
        openConnection();
        beginSession();
        Begin reply = new Begin(0, 100, 100);
        reply.setRemoteChannel(Integer.valueOf(0));
        feed(FakeAmqp1Peer.amqpFrame(7, reply)); // the broker uses its own channel number
        SessionRecorder s = ready.sessions.get(0);
        assertNotNull(s.session);
        assertEquals(0, s.session.getLocalChannel());
        assertEquals(100L, s.session.getPeerBegin().getIncomingWindow());
    }

    @Test
    public void testSessionsGetDistinctChannelsAndRouteByPeerChannel() {
        openConnection();
        beginSession();
        beginSession();
        List<Out> out = peer.output();
        assertEquals(0, out.get(out.size() - 2).channel);
        assertEquals(1, out.get(out.size() - 1).channel);
        Begin r0 = new Begin(0, 10, 10);
        r0.setRemoteChannel(Integer.valueOf(0));
        Begin r1 = new Begin(0, 20, 20);
        r1.setRemoteChannel(Integer.valueOf(1));
        // the broker answers in the opposite order on channels 9 and 4
        feed(FakeAmqp1Peer.amqpFrame(9, r1), FakeAmqp1Peer.amqpFrame(4, r0));
        assertEquals(1, ready.sessions.get(1).session.getLocalChannel());
        assertEquals(0, ready.sessions.get(0).session.getLocalChannel());
        // an end on peer channel 9 ends the session on our channel 1
        feed(FakeAmqp1Peer.amqpFrame(9, new End()));
        assertTrue(ready.sessions.get(1).ended);
        assertFalse(ready.sessions.get(0).ended);
        assertEquals("amqp/1 End", lastOut().toString());
    }

    @Test
    public void testEndSessionWaitsForPeerEnd() {
        openConnection();
        beginSession();
        Begin reply = new Begin(0, 10, 10);
        reply.setRemoteChannel(Integer.valueOf(0));
        feed(FakeAmqp1Peer.amqpFrame(3, reply));
        ready.sessions.get(0).session.end(null);
        assertEquals("amqp/0 End", lastOut().toString());
        assertFalse(ready.sessions.get(0).ended);
        int sentBefore = peer.output().size();
        feed(FakeAmqp1Peer.amqpFrame(3, new End()));
        assertTrue(ready.sessions.get(0).ended);
        assertNull(ready.sessions.get(0).endError);
        assertEquals("no second end sent", sentBefore, peer.output().size());
    }

    @Test
    public void testPeerEndWithErrorIsReportedAndAnswered() {
        openConnection();
        beginSession();
        Begin reply = new Begin(0, 10, 10);
        reply.setRemoteChannel(Integer.valueOf(0));
        feed(FakeAmqp1Peer.amqpFrame(3, reply));
        feed(FakeAmqp1Peer.amqpFrame(3, new End(new Amqp1Error(Amqp1Error.NOT_ALLOWED, "no"))));
        assertEquals(Amqp1Error.NOT_ALLOWED, ready.sessions.get(0).endError.getCondition());
        assertEquals("amqp/0 End", lastOut().toString());
    }

    @Test
    public void testChannelIsReusedAfterSessionEnds() {
        openConnection();
        beginSession();
        Begin reply = new Begin(0, 10, 10);
        reply.setRemoteChannel(Integer.valueOf(0));
        feed(FakeAmqp1Peer.amqpFrame(3, reply));
        feed(FakeAmqp1Peer.amqpFrame(3, new End()));
        beginSession();
        assertEquals(0, lastOut().channel);
    }

    @Test
    public void testChannelMaxExhaustion() {
        Open ours = new Open("c");
        ours.setChannelMax(0);
        openConnection(ours);
        beginSession();
        try {
            beginSession();
            fail("expected exhaustion");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("channel-max"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBeginWithRemoteChannelRejected() {
        openConnection();
        Begin b = new Begin(0, 1, 1);
        b.setRemoteChannel(Integer.valueOf(0));
        ready.openHandler.connection.beginSession(b, new SessionRecorder());
    }

    @Test
    public void testPeerInitiatedSessionIsRefused() {
        openConnection();
        feed(FakeAmqp1Peer.amqpFrame(0, new Begin(0, 10, 10)));
        assertNotNull(ready.error);
        Out last = lastOut();
        assertEquals("amqp/0 Close", last.toString());
        assertEquals(Amqp1Error.NOT_IMPLEMENTED, ((Close) last.performative).getError().getCondition());
    }

    @Test
    public void testBeginAnsweringNothingIsAnError() {
        openConnection();
        Begin stray = new Begin(0, 10, 10);
        stray.setRemoteChannel(Integer.valueOf(4));
        feed(FakeAmqp1Peer.amqpFrame(2, stray));
        assertNotNull(ready.error);
    }

    @Test
    public void testEndOnUnknownChannelIsAnError() {
        openConnection();
        feed(FakeAmqp1Peer.amqpFrame(5, new End()));
        assertNotNull(ready.error);
    }

    @Test
    public void testEndingSessionTwiceRejected() {
        openConnection();
        beginSession();
        Begin reply = new Begin(0, 10, 10);
        reply.setRemoteChannel(Integer.valueOf(0));
        feed(FakeAmqp1Peer.amqpFrame(3, reply));
        Amqp1Session s = ready.sessions.get(0).session;
        s.end(null);
        try {
            s.end(null);
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    // ── close ──

    @Test
    public void testClosingConnectionAwaitsPeerClose() {
        openConnection();
        ready.openHandler.connection.close(null);
        assertEquals("amqp/0 Close", lastOut().toString());
        assertFalse(peer.closed);
        feed(FakeAmqp1Peer.amqpFrame(0, new Close()));
        assertTrue(ready.closed);
        assertNull(ready.closeError);
        assertTrue(peer.closed);
        assertEquals("only one close sent", 1, countClosesSent());
    }

    private int countClosesSent() {
        int n = 0;
        for (Out o : peer.output()) {
            if (o.performative instanceof Close) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void testPeerInitiatedCloseIsAnsweredAndReported() {
        openConnection();
        feed(FakeAmqp1Peer.amqpFrame(0,
                new Close(new Amqp1Error(Amqp1Error.CONNECTION_FORCED, "shutting down"))));
        assertTrue(ready.closed);
        assertEquals(Amqp1Error.CONNECTION_FORCED, ready.closeError.getCondition());
        assertEquals("amqp/0 Close", lastOut().toString());
        assertTrue(peer.closed);
    }

    @Test
    public void testActiveSessionsEndedWhenConnectionCloses() {
        openConnection();
        beginSession();
        Begin reply = new Begin(0, 10, 10);
        reply.setRemoteChannel(Integer.valueOf(0));
        feed(FakeAmqp1Peer.amqpFrame(3, reply));
        feed(FakeAmqp1Peer.amqpFrame(0, new Close()));
        assertTrue(ready.sessions.get(0).ended);
    }

    @Test
    public void testBeginAfterCloseRejected() {
        openConnection();
        ready.openHandler.connection.close(null);
        try {
            beginSession();
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void testDisconnectEndsSessionsAndReports() {
        openConnection();
        beginSession();
        handler.disconnected();
        assertTrue(ready.disconnected);
        assertTrue(ready.sessions.get(0).ended);
    }

    @Test
    public void testDisconnectCancelsTimers() {
        Open theirs = new Open("broker");
        theirs.setIdleTimeOut(1000);
        Open ours = new Open("c");
        ours.setIdleTimeOut(1000);
        connectAndAuthenticate();
        ready.auth.opener.open(ours, ready.openHandler);
        feed(FakeAmqp1Peer.amqpHeader(), FakeAmqp1Peer.amqpFrame(0, theirs));
        handler.disconnected();
        for (FakeAmqp1Peer.Timer t : peer.timers) {
            assertTrue(t.cancelled);
        }
    }

    // ── idle timeouts ──

    @Test
    public void testHeartbeatSentWhenPeerIdleTimeoutWouldExpire() {
        Open theirs = new Open("broker");
        theirs.setIdleTimeOut(1000);
        connectAndAuthenticate();
        ready.auth.opener.open("c", null, ready.openHandler);
        feed(FakeAmqp1Peer.amqpHeader(), FakeAmqp1Peer.amqpFrame(0, theirs));
        assertEquals(1, peer.timers.size());
        assertEquals("checked at half the peer's timeout", 500L, peer.timers.get(0).delayMs);
        int before = peer.output().size();
        clock.advanceMillis(500);
        peer.fireTimers();
        List<Out> out = peer.output();
        assertEquals(before + 1, out.size());
        assertEquals("heartbeat/0", out.get(out.size() - 1).toString());
        assertEquals("keepalive rescheduled", 2, peer.timers.size());
    }

    @Test
    public void testNoHeartbeatWhenRecentlySent() {
        Open theirs = new Open("broker");
        theirs.setIdleTimeOut(1000);
        connectAndAuthenticate();
        ready.auth.opener.open("c", null, ready.openHandler);
        feed(FakeAmqp1Peer.amqpHeader(), FakeAmqp1Peer.amqpFrame(0, theirs));
        clock.advanceMillis(400);
        beginSession(); // traffic: resets the send clock
        int before = peer.output().size();
        clock.advanceMillis(100);
        peer.fireTimers();
        assertEquals("recent traffic stands in for a heartbeat", before, peer.output().size());
    }

    @Test
    public void testNoKeepaliveWithoutPeerIdleTimeout() {
        openConnection();
        assertTrue(peer.timers.isEmpty());
    }

    @Test
    public void testOwnIdleTimeoutExpiryFailsConnection() {
        Open ours = new Open("c");
        ours.setIdleTimeOut(1000);
        openConnection(ours);
        assertEquals(1, peer.timers.size());
        assertEquals(500L, peer.timers.get(0).delayMs);
        clock.advanceMillis(1100);
        peer.fireTimers();
        assertNotNull(ready.error);
        assertTrue(peer.closed);
        Out last = lastOut();
        assertEquals("amqp/0 Close", last.toString());
        assertEquals(Amqp1Error.RESOURCE_LIMIT_EXCEEDED,
                ((Close) last.performative).getError().getCondition());
    }

    @Test
    public void testTrafficKeepsOwnIdleTimeoutAlive() {
        Open ours = new Open("c");
        ours.setIdleTimeOut(1000);
        openConnection(ours);
        clock.advanceMillis(900);
        handler.receive(Amqp1Frame.encodeHeartbeat(0));
        clock.advanceMillis(500);
        peer.fireTimers();
        assertNull(ready.error);
        assertFalse(peer.closed);
        assertEquals("check rescheduled", 2, peer.timers.size());
    }

    @Test
    public void testHeartbeatFramesAreAccepted() {
        openConnection();
        handler.receive(Amqp1Frame.encodeHeartbeat(0));
        handler.receive(Amqp1Frame.encodeHeartbeat(3));
        assertNull(ready.error);
    }

    // ── malformed input ──

    @Test
    public void testUnsupportedPerformativeIsAnError() {
        openConnection();
        // a performative descriptor (0x99) the codec does not know
        ByteBuffer unknown = Amqp1Frame.encode(Amqp1Frame.TYPE_AMQP, 0,
                ByteBuffer.wrap(new byte[] {0x00, 0x53, (byte) 0x99, 0x45}));
        handler.receive(unknown);
        assertNotNull(ready.error);
        assertEquals("amqp/0 Close", lastOut().toString());
    }

    @Test
    public void testPayloadAfterOpenIsAnError() {
        connectAndAuthenticate();
        ready.auth.opener.open("c", null, ready.openHandler);
        ByteBuffer open = new Open("broker").encode();
        byte[] body = new byte[open.remaining() + 3];
        open.get(body, 0, open.remaining());
        handler.receive(ByteBuffer.wrap(FakeAmqp1Peer.concat(FakeAmqp1Peer.amqpHeader(),
                Amqp1Frame.encode(Amqp1Frame.TYPE_AMQP, 0, ByteBuffer.wrap(body)))));
        assertNotNull(ready.error);
    }

    @Test
    public void testMalformedPerformativeIsAnError() {
        openConnection();
        handler.receive(Amqp1Frame.encode(Amqp1Frame.TYPE_AMQP, 0,
                ByteBuffer.wrap(new byte[] {0x00, 0x53, 0x17, (byte) 0xC0, 0x09, 0x01})));
        assertNotNull(ready.error);
    }

    @Test
    public void testOpenOnNonZeroChannelIsAnError() {
        connectAndAuthenticate();
        ready.auth.opener.open("c", null, ready.openHandler);
        feed(FakeAmqp1Peer.amqpHeader(), FakeAmqp1Peer.amqpFrame(2, peerOpen("broker")));
        assertNotNull(ready.error);
    }

    @Test
    public void testSecondPeerOpenIsAnError() {
        openConnection();
        feed(FakeAmqp1Peer.amqpFrame(0, peerOpen("broker")));
        assertNotNull(ready.error);
    }

    @Test
    public void testNothingProcessedAfterFailure() {
        openConnection();
        feed(FakeAmqp1Peer.amqpFrame(5, new End()));
        assertNotNull(ready.error);
        ready.error = null;
        feed(FakeAmqp1Peer.amqpFrame(5, new End()));
        assertNull("no further errors after the connection failed", ready.error);
    }

    @Test
    public void testTransportErrorIsForwarded() {
        Exception boom = new IOException("boom");
        handler.error(boom);
        assertSame(boom, ready.error);
    }

    @Test(expected = NullPointerException.class)
    public void testNullHandlerRejected() {
        new Amqp1ClientProtocolHandler(null);
    }

    // ── test doubles ──

    /** A clock the test advances by hand. */
    private static final class TestClock implements Amqp1ClientProtocolHandler.NanoClock {
        private long now = 1000000000L;

        @Override
        public long nanoTime() {
            return now;
        }

        void advanceMillis(long ms) {
            now += ms * 1000000L;
        }
    }

    private static final class Recorder implements Amqp1ConnectionReady {
        boolean connected;
        boolean disconnected;
        boolean closed;
        boolean securityEstablished;
        Amqp1Error closeError;
        Exception error;
        List<String> mechanisms;
        Amqp1SaslHandshake handshake;
        final AuthRecorder auth = new AuthRecorder();
        final OpenRecorder openHandler = new OpenRecorder();
        final List<SessionRecorder> sessions = new ArrayList<SessionRecorder>();

        @Override
        public void onConnected(Endpoint endpoint) {
            connected = true;
        }

        @Override
        public void onError(Exception cause) {
            error = cause;
        }

        @Override
        public void onDisconnected() {
            disconnected = true;
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
            securityEstablished = true;
        }

        @Override
        public void handleSaslMechanisms(List<String> m, Amqp1SaslHandshake h) {
            mechanisms = m;
            handshake = h;
        }

        @Override
        public void onConnectionClosed(Amqp1Error e) {
            closed = true;
            closeError = e;
        }
    }

    private static final class AuthRecorder implements Amqp1AuthHandler {
        Amqp1ConnectionOpener opener;
        int failureCode = -1;

        @Override
        public void handleAuthenticated(Amqp1ConnectionOpener o) {
            opener = o;
        }

        @Override
        public void handleAuthenticationFailed(int code, byte[] additionalData) {
            failureCode = code;
        }
    }

    private static final class OpenRecorder implements Amqp1OpenHandler {
        Open peerOpen;
        Amqp1Connection connection;

        @Override
        public void handleOpen(Open p, Amqp1Connection c) {
            peerOpen = p;
            connection = c;
        }
    }

    private static final class SessionRecorder implements Amqp1SessionHandler {
        Amqp1Session session;
        boolean ended;
        Amqp1Error endError;

        @Override
        public void handleBegun(Amqp1Session s, Begin peerBegin) {
            session = s;
        }

        @Override
        public void handleEnded(Amqp1Error error) {
            ended = true;
            endError = error;
        }
    }
}
