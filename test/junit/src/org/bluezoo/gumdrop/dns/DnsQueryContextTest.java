/*
 * DnsQueryContextTest.java
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

package org.bluezoo.gumdrop.dns;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandler;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandlers;
import org.bluezoo.gumdrop.dns.server.DnsServer;
import org.bluezoo.gumdrop.dns.server.SyncDnsQueryHandler;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;

import static org.junit.Assert.*;

/**
 * Verifies that every DNS transport hands a {@link DnsQueryContext} to the
 * {@link DnsQueryHandler}: who sent the query, over which transport, and
 * with what security parameters.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsQueryContextTest {

    /** Records the context of the last query; answers NOERROR/empty. */
    private static final class ContextHandler implements DnsQueryHandler {
        DnsQueryContext context;
        int contextCalls;
        int legacyCalls;
        DnsQueryContext opcodeContext;
        int opcodeCalls;

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop,
                                DnsQueryCallback callback) {
            legacyCalls++;
            callback.onResponse(query.createResponse(
                    Collections.<DnsResourceRecord>emptyList()));
        }

        @Override
        public boolean handleNonQueryOpcode(DnsMessage query, SelectorLoop loop,
                                            DnsQueryContext ctx,
                                            DnsQueryCallback callback) {
            opcodeCalls++;
            opcodeContext = ctx;
            callback.onResponse(query.createResponse(
                    Collections.<DnsResourceRecord>emptyList()));
            return true;
        }

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop,
                                DnsQueryContext ctx, DnsQueryCallback callback) {
            contextCalls++;
            context = ctx;
            callback.onResponse(query.createResponse(
                    Collections.<DnsResourceRecord>emptyList()));
        }
    }

    /** Datagram listener that swallows responses. */
    private static final class MockListener extends DnsListener {
        @Override
        public void sendTo(ByteBuffer data, InetSocketAddress destination) {
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public TelemetryConfig getTelemetryConfig() {
            return new TelemetryConfig();
        }

        @Override
        public void start(Gumdrop gumdrop) {
        }

        @Override
        public void stop() {
        }
    }

    private DnsServer server;
    private ContextHandler handler;
    private BinaryRecordingEndpoint endpoint;

    @Before
    public void setUp() {
        server = new DnsServer();
        handler = new ContextHandler();
        server.handler(handler);
        endpoint = new BinaryRecordingEndpoint();
    }

    private static byte[] frame(DnsMessage m) {
        ByteBuffer b = m.serialize();
        byte[] body = new byte[b.remaining()];
        b.get(body);
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) (body.length >> 8);
        out[1] = (byte) body.length;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    private static DnsMessage query() {
        return DnsMessage.createQuery(4, "www.example.com.", DnsType.A);
    }

    private static SecurityInfo securityInfo() {
        return (SecurityInfo) Proxy.newProxyInstance(
                SecurityInfo.class.getClassLoader(),
                new Class<?>[] {SecurityInfo.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method,
                                         Object[] args) {
                        return null;
                    }
                });
    }

    @Test
    public void dotQueryCarriesClientAddressAndSecurity() {
        SecurityInfo info = securityInfo();
        endpoint.setSecure(true);
        endpoint.setSecurityInfo(info);
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.securityEstablished(info);
        h.receive(ByteBuffer.wrap(frame(query())));

        assertEquals(1, handler.contextCalls);
        DnsQueryContext ctx = handler.context;
        assertEquals(endpoint.getRemoteAddress(), ctx.getRemoteAddress());
        assertEquals(endpoint.getLocalAddress(), ctx.getLocalAddress());
        assertEquals(DnsQueryTransport.FRAMED_TCP, ctx.getTransport());
        assertEquals("dot", ctx.getTransportName());
        assertSame(info, ctx.getSecurityInfo());
    }

    @Test
    public void doqQueryCarriesClientAddressAndSecurity() {
        SecurityInfo info = securityInfo();
        endpoint.setSecure(true);
        endpoint.setSecurityInfo(info);
        DoQStreamHandler h = new DoQStreamHandler(server);
        h.connected(endpoint);
        h.securityEstablished(info);
        h.receive(ByteBuffer.wrap(frame(query())));
        h.readFinished();

        assertEquals(1, handler.contextCalls);
        DnsQueryContext ctx = handler.context;
        assertEquals(endpoint.getRemoteAddress(), ctx.getRemoteAddress());
        assertEquals(DnsQueryTransport.FRAMED_TCP, ctx.getTransport());
        assertEquals("doq", ctx.getTransportName());
        assertSame(info, ctx.getSecurityInfo());
    }

    @Test
    public void udpQueryCarriesSourceAddressAndNoSecurity() throws Exception {
        InetSocketAddress source = new InetSocketAddress(
                InetAddress.getByName("192.0.2.77"), 5300);
        server.handleDatagram(new MockListener(), query().serialize(), source,
                new Runnable() {
                    @Override
                    public void run() {
                    }
                });

        assertEquals(1, handler.contextCalls);
        DnsQueryContext ctx = handler.context;
        assertEquals(source, ctx.getRemoteAddress());
        assertEquals(DnsQueryTransport.UDP, ctx.getTransport());
        assertEquals("udp", ctx.getTransportName());
        assertNull(ctx.getSecurityInfo());
    }

    @Test
    public void plaintextStreamHasNoSecurityInfo() {
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.receive(ByteBuffer.wrap(frame(query())));
        assertNull(handler.context.getSecurityInfo());
    }

    @Test
    public void chainPassesContextToBothHandlers() {
        final ContextHandler first = new ContextHandler();
        DnsQueryHandler declining = new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage q, SelectorLoop loop,
                                    DnsQueryCallback cb) {
                cb.onResponse(q.createErrorResponse(DnsMessage.RCODE_REFUSED));
            }

            @Override
            public void handleQuery(DnsMessage q, SelectorLoop loop,
                                    DnsQueryContext ctx, DnsQueryCallback cb) {
                first.context = ctx;
                first.contextCalls++;
                cb.onResponse(q.createResponse(
                        Collections.<DnsResourceRecord>emptyList()));
            }
        };
        server.handler(DnsQueryHandlers.chain(declining, handler));
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.receive(ByteBuffer.wrap(frame(query())));
        assertEquals(1, first.contextCalls);
        assertEquals("dot", first.context.getTransportName());
        assertEquals(1, handler.contextCalls);
        assertEquals("dot", handler.context.getTransportName());
    }

    @Test
    public void handlerWithoutContextOverrideStillWorks() {
        final int[] calls = new int[1];
        server.handler(new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage q, SelectorLoop loop,
                                    DnsQueryCallback cb) {
                calls[0]++;
                cb.onResponse(q.createResponse(
                        Collections.<DnsResourceRecord>emptyList()));
            }
        });
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.receive(ByteBuffer.wrap(frame(query())));
        assertEquals(1, calls[0]);
        assertEquals(1, framed(endpoint.getAllBytes()));
    }

    @Test
    public void dotSecurityBeforeConnectedDoesNotThrow() {
        Logger logger = Logger.getLogger(DoTProtocolHandler.class.getName());
        Level old = logger.getLevel();
        logger.setLevel(Level.FINE);
        try {
            DoTProtocolHandler h = new DoTProtocolHandler(server);
            h.securityEstablished(securityInfo());
        } finally {
            logger.setLevel(old);
        }
    }

    // ── non-query opcodes ──

    private static DnsMessage notifyMessage() {
        return DnsMessage.createNotify(9, "example.com.");
    }

    @Test
    public void nonQueryOpcodeOverDotCarriesContext() {
        SecurityInfo info = securityInfo();
        endpoint.setSecure(true);
        endpoint.setSecurityInfo(info);
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.securityEstablished(info);
        h.receive(ByteBuffer.wrap(frame(notifyMessage())));

        assertEquals(1, handler.opcodeCalls);
        DnsQueryContext ctx = handler.opcodeContext;
        assertEquals(endpoint.getRemoteAddress(), ctx.getRemoteAddress());
        assertEquals(endpoint.getLocalAddress(), ctx.getLocalAddress());
        assertEquals(DnsQueryTransport.FRAMED_TCP, ctx.getTransport());
        assertEquals("dot", ctx.getTransportName());
        assertSame(info, ctx.getSecurityInfo());
    }

    @Test
    public void nonQueryOpcodeOverPlainTcpCarriesContext() {
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.receive(ByteBuffer.wrap(frame(notifyMessage())));

        assertEquals(1, handler.opcodeCalls);
        DnsQueryContext ctx = handler.opcodeContext;
        assertEquals(endpoint.getRemoteAddress(), ctx.getRemoteAddress());
        assertEquals(endpoint.getLocalAddress(), ctx.getLocalAddress());
        assertEquals(DnsQueryTransport.FRAMED_TCP, ctx.getTransport());
        assertNull(ctx.getSecurityInfo());
    }

    @Test
    public void nonQueryOpcodeOverDoqCarriesContext() {
        SecurityInfo info = securityInfo();
        endpoint.setSecure(true);
        endpoint.setSecurityInfo(info);
        DoQStreamHandler h = new DoQStreamHandler(server);
        h.connected(endpoint);
        h.securityEstablished(info);
        h.receive(ByteBuffer.wrap(frame(notifyMessage())));
        h.readFinished();

        assertEquals(1, handler.opcodeCalls);
        DnsQueryContext ctx = handler.opcodeContext;
        assertEquals(endpoint.getRemoteAddress(), ctx.getRemoteAddress());
        assertEquals("doq", ctx.getTransportName());
        assertSame(info, ctx.getSecurityInfo());
    }

    @Test
    public void nonQueryOpcodeOverUdpCarriesContext() throws Exception {
        InetSocketAddress source = new InetSocketAddress(
                InetAddress.getByName("192.0.2.78"), 5301);
        server.handleDatagram(new MockListener(), notifyMessage().serialize(), source,
                new Runnable() {
                    @Override
                    public void run() {
                    }
                });

        assertEquals(1, handler.opcodeCalls);
        assertEquals(source, handler.opcodeContext.getRemoteAddress());
        assertEquals(DnsQueryTransport.UDP, handler.opcodeContext.getTransport());
        assertEquals("udp", handler.opcodeContext.getTransportName());
    }

    @Test
    public void chainPassesContextToNonQueryOpcodeHandlers() {
        DnsQueryHandler declining = new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage q, SelectorLoop loop,
                                    DnsQueryCallback cb) {
            }
        };
        server.handler(DnsQueryHandlers.chain(declining, handler));
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.receive(ByteBuffer.wrap(frame(notifyMessage())));
        assertEquals(1, handler.opcodeCalls);
        assertEquals("dot", handler.opcodeContext.getTransportName());
    }

    @Test
    public void nonQueryOpcodeHandlerWithoutContextOverrideStillClaims() {
        final int[] calls = new int[1];
        server.handler(new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage q, SelectorLoop loop,
                                    DnsQueryCallback cb) {
            }

            @Override
            public boolean handleNonQueryOpcode(DnsMessage q, SelectorLoop loop,
                                                DnsQueryCallback cb) {
                calls[0]++;
                cb.onResponse(q.createResponse(
                        Collections.<DnsResourceRecord>emptyList()));
                return true;
            }
        });
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.receive(ByteBuffer.wrap(frame(notifyMessage())));
        assertEquals(1, calls[0]);
    }

    // ── synchronous handlers ──

    private static final class ContextSync extends SyncDnsQueryHandler {
        DnsQueryContext context;
        int plainCalls;

        @Override
        protected DnsMessage resolveQuery(DnsMessage query) {
            plainCalls++;
            return null;
        }

        @Override
        protected DnsMessage resolveQuery(DnsMessage query, DnsQueryContext ctx) {
            context = ctx;
            return query.createResponse(Collections.<DnsResourceRecord>emptyList());
        }
    }

    @Test
    public void syncHandlerSeesTheContext() {
        ContextSync sync = new ContextSync();
        server.handler(sync);
        SecurityInfo info = securityInfo();
        endpoint.setSecure(true);
        endpoint.setSecurityInfo(info);
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.securityEstablished(info);
        h.receive(ByteBuffer.wrap(frame(query())));

        assertNotNull(sync.context);
        assertEquals(endpoint.getRemoteAddress(), sync.context.getRemoteAddress());
        assertEquals("dot", sync.context.getTransportName());
        assertSame(info, sync.context.getSecurityInfo());
        assertEquals(0, sync.plainCalls);
    }

    @Test
    public void syncHandlerWithoutContextOverrideStillWorks() {
        final int[] calls = new int[1];
        server.handler(new SyncDnsQueryHandler() {
            @Override
            protected DnsMessage resolveQuery(DnsMessage q) {
                calls[0]++;
                return q.createResponse(Collections.<DnsResourceRecord>emptyList());
            }
        });
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.receive(ByteBuffer.wrap(frame(query())));
        assertEquals(1, calls[0]);
    }

    private static int framed(byte[] all) {
        return all.length > 2 ? 1 : 0;
    }
}
