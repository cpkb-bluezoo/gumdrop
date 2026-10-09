/*
 * TcpEndpointEdgeTest.java
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

package org.bluezoo.gumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.StubSocketChannel;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.junit.Test;

/**
 * Edge cases of {@link TcpEndpoint} not reached by the other endpoint
 * tests: telemetry recording on every error path, STARTTLS preconditions per
 * TLS policy, input and output buffer ceilings, behaviour without a
 * selector loop and after the buffers have been released. A real
 * {@link Trace} is attached and its root span inspected.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TcpEndpointEdgeTest {

    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 4100);
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 4101);

    private static final class Recorder implements ProtocolHandler {
        final List<String> events = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();
        boolean failDisconnect;

        @Override
        public void receive(ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void connected(Endpoint endpoint) {
            events.add("connected");
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            events.add("secure");
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
            if (failDisconnect) {
                throw new IllegalStateException("handler failure");
            }
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    /** Runs tasks inline and counts write requests; its timer thread is never started. */
    private static final class ManualLoop extends SelectorLoop {
        int writeRequests;

        ManualLoop() {
            super(0);
        }

        @Override
        public boolean tryInvokeLater(Runnable task) {
            task.run();
            return true;
        }

        @Override
        void requestWrite(TcpEndpoint endpoint) {
            writeRequests++;
        }
    }

    private static final class Counting extends TcpListener {
        @Override
        protected ProtocolHandler createHandler() {
            return null;
        }

        @Override
        public String getDescription() {
            return "counting";
        }
    }

    private static TcpEndpoint withChannel(Recorder h, Trace trace) throws IOException {
        TcpEndpoint ep = new TcpEndpoint(h);
        ep.setChannel(new StubSocketChannel(LOCAL, REMOTE));
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        if (trace != null) {
            ep.setTrace(trace);
        }
        return ep;
    }

    private static String categoryOf(Trace trace) {
        Span root = trace.getRootSpan();
        List<Attribute> attrs = root.getAttributes();
        for (int i = 0; i < attrs.size(); i++) {
            Attribute a = attrs.get(i);
            if ("error.category".equals(a.getKey())) {
                return a.getStringValue();
            }
        }
        return null;
    }

    private static void assertRecorded(Trace trace, ErrorCategory expected) {
        Span root = trace.getRootSpan();
        assertEquals(1, root.getEvents().size());
        assertEquals("exception", root.getEvents().get(0).getName());
        assertEquals(expected.getCode(), categoryOf(trace));
    }

    @Test
    public void readErrorRecordsConnectionLostOnTheTrace() throws IOException {
        Recorder h = new Recorder();
        Trace trace = new Trace("read");
        TcpEndpoint ep = withChannel(h, trace);
        ep.handleReadError(new IOException("Connection reset by peer"));
        assertRecorded(trace, ErrorCategory.CONNECTION_LOST);
        assertEquals(1, h.errors.size());
        assertTrue(trace.getRootSpan().isEnded());
    }

    @Test
    public void readErrorWithBrokenPipeOrOtherMessageIsClassified() throws IOException {
        Trace pipe = new Trace("pipe");
        withChannel(new Recorder(), pipe).handleReadError(new IOException("Broken pipe"));
        assertRecorded(pipe, ErrorCategory.CONNECTION_LOST);

        Trace other = new Trace("other");
        withChannel(new Recorder(), other).handleReadError(new IOException("disk on fire"));
        assertEquals(1, other.getRootSpan().getEvents().size());
        assertEquals(ErrorCategory.IO_ERROR.getCode(), categoryOf(other));

        Trace none = new Trace("none");
        withChannel(new Recorder(), none).handleReadError(new IOException());
        assertEquals(ErrorCategory.IO_ERROR.getCode(), categoryOf(none));
    }

    @Test
    public void writeErrorRecordsClassifiedCategoryOnTheTrace() throws IOException {
        Trace reset = new Trace("w1");
        withChannel(new Recorder(), reset).handleWriteError(new IOException("Connection reset"));
        assertRecorded(reset, ErrorCategory.CONNECTION_LOST);

        Trace pipe = new Trace("w2");
        withChannel(new Recorder(), pipe).handleWriteError(new IOException("Broken pipe"));
        assertRecorded(pipe, ErrorCategory.CONNECTION_LOST);

        Trace other = new Trace("w3");
        withChannel(new Recorder(), other).handleWriteError(new IOException("quota exceeded"));
        assertEquals(ErrorCategory.IO_ERROR.getCode(), categoryOf(other));

        Trace none = new Trace("w4");
        withChannel(new Recorder(), none).handleWriteError(new IOException());
        assertEquals(ErrorCategory.IO_ERROR.getCode(), categoryOf(none));
    }

    @Test
    public void connectErrorRecordsMappedCategoryOnTheTrace() throws IOException {
        Trace refused = new Trace("c1");
        withChannel(new Recorder(), refused).handleConnectError(new ConnectException("refused"));
        assertEquals(1, refused.getRootSpan().getEvents().size());
        assertEquals(ErrorCategory.CONNECTION_ERROR.getCode(), categoryOf(refused));

        Trace plain = new Trace("c2");
        withChannel(new Recorder(), plain).handleConnectError(new IOException("odd"));
        assertEquals(1, plain.getRootSpan().getEvents().size());
        String code = categoryOf(plain);
        assertNotNull(code);
    }

    @Test
    public void dispatchErrorRecordsInternalErrorOnTheTrace() throws IOException {
        Recorder h = new Recorder();
        Trace trace = new Trace("d");
        TcpEndpoint ep = withChannel(h, trace);
        ep.handleDispatchError(new IllegalStateException("boom"));
        assertRecorded(trace, ErrorCategory.INTERNAL_ERROR);
        assertEquals(1, h.errors.size());
    }

    @Test
    public void dispatchErrorWithoutAChannelStillNotifies() throws IOException {
        Recorder h = new Recorder();
        TcpEndpoint ep = new TcpEndpoint(h);
        ep.init();
        ep.handleDispatchError(new IllegalStateException("boom"));
        assertEquals(1, h.errors.size());
        assertTrue(h.events.contains("disconnected"));
    }

    @Test
    public void failingDisconnectHandlerIsRecordedOnTheTrace() throws IOException {
        Recorder h = new Recorder();
        h.failDisconnect = true;
        Trace trace = new Trace("dc");
        TcpEndpoint ep = withChannel(h, trace);
        ep.deliverDisconnected();
        assertEquals(1, trace.getRootSpan().getEvents().size());
        assertEquals("exception", trace.getRootSpan().getEvents().get(0).getName());
    }

    @Test
    public void startTlsOnAnEndpointFlaggedSecureButNotInitialisedIsRejected() throws IOException {
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.CLIENT);
        TcpEndpoint ep = new TcpEndpoint(new Recorder(), cfg, true);
        try {
            ep.startTLS();
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("already secure"));
        }
    }

    @Test
    public void negotiatingPolicyWithOnlyTls12ConfigUpgradesWithTls12() throws IOException {
        Tls12HandshakeConfig cfg12 = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        TcpEndpoint ep = new TcpEndpoint(new Recorder(), null, cfg12, TlsVersion.NEGOTIATE, false);
        ep.setClientMode(true);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        ep.startTLS();
        assertTrue(ep.isSecure());
        assertTrue(ep.hasPendingWrite());
        assertTrue(ep.getSecurityInfo() != NullSecurityInfo.INSTANCE);
    }

    @Test
    public void nullPolicyDefaultsToNegotiate() throws IOException {
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.CLIENT);
        Tls12HandshakeConfig cfg12 = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        TcpEndpoint ep = new TcpEndpoint(new Recorder(), cfg, cfg12, null, true);
        ep.setChannel(new StubSocketChannel(LOCAL, REMOTE));
        ep.setClientMode(true);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        assertTrue(ep.isSecure());
        assertTrue(ep.getSecurityInfo() != NullSecurityInfo.INSTANCE);
    }

    @Test
    public void secureTls12OnlyEndpointReportsTls12SecurityInfo() throws IOException {
        Tls12HandshakeConfig cfg12 = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        TcpEndpoint ep = new TcpEndpoint(new Recorder(), null, cfg12, TlsVersion.TLS_1_2, true);
        ep.setChannel(new StubSocketChannel(LOCAL, REMOTE));
        ep.setClientMode(true);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        SecurityInfo info = ep.getSecurityInfo();
        assertNotNull(info);
        assertTrue(info != NullSecurityInfo.INSTANCE);
    }

    @Test
    public void secureFlagWithoutAnyConfigReportsNullSecurityInfo() throws IOException {
        TcpEndpoint ep = new TcpEndpoint(new Recorder(), null, null, TlsVersion.NEGOTIATE, true);
        ep.setChannel(new StubSocketChannel(LOCAL, REMOTE));
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        assertTrue(ep.isSecure());
        assertSame(NullSecurityInfo.INSTANCE, ep.getSecurityInfo());
    }

    @Test
    public void secondConnectedDoesNotRearmTheHandshakeTimeout() throws IOException {
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.SERVER);
        Recorder h = new Recorder();
        TcpEndpoint ep = new TcpEndpoint(h, cfg, null, TlsVersion.TLS_1_3, true);
        ep.setChannel(new StubSocketChannel(LOCAL, REMOTE));
        ManualLoop loop = new ManualLoop();
        ep.setSelectorLoop(loop);
        ep.init();
        Counting listener = new Counting();
        listener.connectionTimeoutMs(5000L);
        ep.setListener(listener, REMOTE);
        ep.connected();
        ep.connected();
        assertEquals(2, countOf(h.events, "connected"));
        assertEquals(1, loop.getTimer().pendingEntries().size());
    }

    private static int countOf(List<String> events, String what) {
        int n = 0;
        for (int i = 0; i < events.size(); i++) {
            if (what.equals(events.get(i))) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void pauseAndResumeWithoutALoopOnlyFlipTheFlag() throws IOException {
        TcpEndpoint ep = new TcpEndpoint(new Recorder());
        ep.init();
        ep.pauseRead();
        assertTrue(ep.isReadPaused());
        ep.resumeRead();
        assertFalse(ep.isReadPaused());
    }

    @Test
    public void closeAndIdleCloseWithoutALoopStillMarkClosing() throws IOException {
        TcpEndpoint ep = new TcpEndpoint(new Recorder());
        ep.init();
        ep.closeWhenOutboundIdle();
        assertNotNull(ep.getWriteCompleteCallback());
        ep.close();
        assertTrue(ep.isClosing());
    }

    @Test
    public void attachingALoopRequestsAWriteForPendingOutput() throws IOException {
        TcpEndpoint ep = new TcpEndpoint(new Recorder());
        ep.init();
        ep.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        ManualLoop loop = new ManualLoop();
        ep.setSelectorLoop(loop);
        assertEquals(1, loop.writeRequests);
    }

    @Test
    public void sendAfterBuffersReleasedIsDropped() throws IOException {
        Recorder h = new Recorder();
        TcpEndpoint ep = new TcpEndpoint(h);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        ep.doClose();
        ep.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertFalse(ep.hasPendingWrite());
        ep.closeRequested = true;
        assertTrue(ep.hasPendingWrite());
    }

    @Test
    public void clientModeWithoutALoopClosesCleanly() throws IOException {
        Recorder h = new Recorder();
        TcpEndpoint ep = new TcpEndpoint(h);
        ep.setClientMode(true);
        ep.init();
        ep.doClose();
        assertTrue(h.events.contains("disconnected"));
    }

    @Test
    public void inputBufferAtItsCeilingIsRejected() throws IOException {
        TcpEndpoint ep = withChannel(new Recorder(), null);
        TcpTransportFactory f = new TcpTransportFactory();
        ByteBuffer in = ep.prepareNetInForRead();
        f.setMaxNetInSize(in.capacity());
        ep.setFactory(f);
        in.position(in.limit());
        try {
            ep.prepareNetInForRead();
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("exceeded maximum size"));
        }
    }

    @Test
    public void inputBufferGrowthIsClampedToTheCeiling() throws IOException {
        TcpEndpoint ep = withChannel(new Recorder(), null);
        TcpTransportFactory f = new TcpTransportFactory();
        ByteBuffer in = ep.prepareNetInForRead();
        int cap = in.capacity();
        f.setMaxNetInSize(cap + cap / 2);
        ep.setFactory(f);
        in.position(in.limit() - 10);
        ByteBuffer grown = ep.prepareNetInForRead();
        assertTrue("the pool may round the clamped size up", grown.capacity() >= cap + cap / 2);
        assertTrue(grown.remaining() >= 10);
    }

    @Test
    public void outputBufferGrowthIsClampedToTheCeiling() throws IOException {
        TcpEndpoint ep = withChannel(new Recorder(), null);
        TcpTransportFactory f = new TcpTransportFactory();
        int cap = ep.getNetOut().capacity();
        f.setMaxNetOutSize(cap + cap / 2);
        ep.setFactory(f);
        ep.send(ByteBuffer.wrap(new byte[cap - 100]));
        ep.send(ByteBuffer.wrap(new byte[cap / 4]));
        assertFalse(ep.isClosing());
        assertEquals(cap - 100 + cap / 4, ep.getNetOut().position());
    }

    @Test
    public void orderlyShutdownWithACancelledKeyJustMarksClosing() throws IOException {
        Recorder h = new Recorder();
        StubSocketChannel channel = new StubSocketChannel(LOCAL, REMOTE);
        TcpEndpoint ep = new TcpEndpoint(h);
        ep.setChannel(channel);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        StubSocketChannel.Key key = new StubSocketChannel.Key(channel, 1 | 4);
        ep.setSelectionKey(key);
        key.cancel();
        ep.closeForShutdown(true);
        assertTrue(ep.isClosing());
    }
}
