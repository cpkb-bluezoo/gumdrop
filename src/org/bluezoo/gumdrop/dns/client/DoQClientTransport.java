/*
 * DoQClientTransport.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;

/**
 * DNS-over-QUIC (DoQ) transport for DNS client queries.
 * RFC 9250 section 4.1: ALPN token is "doq".
 * RFC 9250 section 4.1.1: default port is 853.
 * RFC 9250 section 4.2: each query uses a separate bidirectional stream.
 * The client MUST indicate STREAM FIN after the query. All messages MUST
 * use 2-octet length framing (RFC 1035 section 4.2.2).
 * RFC 9250 section 4.2.1: Message ID MUST be set to 0 on DoQ.
 *
 * <p>RFC 9250 section 5.4: implementations MUST protect against traffic
 * analysis by padding messages. EDNS(0) padding (RFC 7830) is added,
 * aligning to 128-byte boundaries as recommended by RFC 8467.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSClientTransport
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9250">RFC 9250</a>
 */
public class DoQClientTransport implements DNSClientTransport {

    // RFC 9250 section 4.1.1
    private static final int DEFAULT_DOQ_PORT = 853;
    // RFC 9250 section 5.4: pad to 128-byte blocks
    private static final int PADDING_BLOCK_SIZE = 128;

    private QuicTransportFactory factory;
    private QuicEngine engine;
    private volatile boolean connected;
    private SelectorLoop loop;
    private DNSClientTransportHandler handler;
    private String pinnedCertFingerprint;
    private java.nio.file.Path caFile;

    // Set from whichever of ConnectionAcceptedHandler/EarlyDataHandler
    // fires first (see open()) -- used to check isEstablished() so send()
    // can gate non-eligible-opcode queries behind full establishment (RFC
    // 9250 section 4.5) via QuicTransportFactory's shared SessionTicketCache/
    // 0-RTT machinery, the same as HTTP3ClientHandler does for HTTP methods.
    private QuicConnection quicConnection;
    // Queries deferred because their opcode isn't 0-RTT-eligible and the
    // connection isn't yet established -- drained once it is (see
    // connectionAccepted below). Only ever touched from the QuicConnection's
    // own SelectorLoop thread (both send() and connectionAccepted always run
    // there), so no synchronization needed.
    private final List<Runnable> deferredSends = new ArrayList<Runnable>();

    /**
     * Pins the expected server certificate fingerprint instead of
     * relying on the platform CA trust store, e.g. to trust a private
     * or self-signed CA. Must be called before {@link #open}.
     *
     * @param fingerprint colon-separated hex with optional
     *                    "SHA-256:" prefix
     * @see org.bluezoo.gumdrop.TransportFactory#setPinnedCertFingerprint
     */
    public void setPinnedCertFingerprint(String fingerprint) {
        this.pinnedCertFingerprint = fingerprint;
    }

    /**
     * Sets a CA certificate file to trust instead of the platform
     * default trust store. Must be called before {@link #open}.
     *
     * @param caFile the CA certificate file path
     * @see org.bluezoo.gumdrop.TransportFactory#setCaFile(java.nio.file.Path)
     */
    public void setCaFile(java.nio.file.Path caFile) {
        this.caFile = caFile;
    }

    @Override
    public void open(InetAddress server, int port, SelectorLoop loop,
                     DNSClientTransportHandler handler) throws IOException {
        this.loop = loop;
        this.handler = handler;
        if (port <= 0) {
            port = DEFAULT_DOQ_PORT;
        }
        factory = new QuicTransportFactory();
        // RFC 9250 section 4.1: ALPN token "doq"
        factory.setApplicationProtocols("doq");
        // RFC 9250 section 4.5: enable early data for 0-RTT
        factory.setEarlyDataEnabled(true);
        // DoQ connects directly to a resolved IP with no real hostname to
        // offer (see the serverName comment on the connect() call below);
        // trust is established via setPinnedCertFingerprint/setCaFile
        // instead of hostname matching, matching RFC 8310 section 8.1's
        // SPKI-pinning-as-alternative precedent for DNS-over-TLS clients
        // in the same situation.
        factory.setVerifyHostname(false);
        if (pinnedCertFingerprint != null) {
            factory.setPinnedCertFingerprint(pinnedCertFingerprint);
        }
        if (caFile != null) {
            factory.setCaFile(caFile);
        }
        factory.start();
        engine = factory.connect(server, port,
                new QuicEngine.ConnectionAcceptedHandler() {
                    @Override
                    public void connectionAccepted(
                            QuicConnection conn) {
                        // Idempotent: may already have been set from
                        // earlyDataReady below -- either way, this is
                        // the signal that the connection is now (also)
                        // fully established, so anything deferred behind
                        // that can go out now.
                        quicConnection = conn;
                        connected = true;
                        runDeferredSends();
                    }
                },
                new QuicEngine.EarlyDataHandler() {
                    @Override
                    public void earlyDataReady(QuicConnection conn) {
                        // RFC 9250 section 4.5: 0-RTT send keys are ready,
                        // well before the handshake completes -- let the
                        // caller start issuing QUERY/NOTIFY queries now;
                        // send() defers anything else until establishment.
                        quicConnection = conn;
                        connected = true;
                    }
                },
                // Agent15's TlsClientEngineImpl.startHandshake requires a
                // non-null server name unconditionally (throws
                // IllegalStateException otherwise) -- DoQ has no real
                // hostname to offer (it connects directly to a resolved
                // IP), so the literal address is used instead. RFC 6066
                // section 3 disallows IP literals in a real SNI extension,
                // but this only matters for servers that select a
                // certificate by SNI; gumdrop's own DoQListener serves one
                // configured certificate regardless of the value received,
                // and SessionTicketCache already keys on this same string
                // (QuicEngine.connectTo falls back to it when serverName
                // is null), so nothing else depends on it looking like a
                // real hostname.
                loop, server.getHostAddress());
    }

    private void runDeferredSends() {
        List<Runnable> pending = new ArrayList<Runnable>(deferredSends);
        deferredSends.clear();
        for (Runnable task : pending) {
            task.run();
        }
    }

    // RFC 9250 section 4.2: client selects a new bidirectional stream for
    // each query, sends the message, and indicates STREAM FIN.
    // RFC 9250 section 4.2: 2-octet length prefix required.
    // RFC 9250 section 4.2.1: Message ID MUST be set to 0.
    @Override
    public void send(ByteBuffer data) {
        if (!connected) {
            handler.onError(new IOException(
                    "DoQ connection not yet established"));
            return;
        }
        if (quicConnection != null && !quicConnection.isEstablished() && !isEarlyDataEligible(data)) {
            // RFC 9250 section 4.5: only QUERY/NOTIFY may ride 0-RTT --
            // snapshot now (the caller may reuse/refill data once this
            // call returns) and defer until the connection is fully
            // established, mirroring HTTP3ClientHandler's method-safety
            // gating for HTTP/3 requests.
            final byte[] snapshot = new byte[data.remaining()];
            data.get(snapshot);
            deferredSends.add(new Runnable() {
                @Override
                public void run() {
                    sendNow(ByteBuffer.wrap(snapshot));
                }
            });
            return;
        }
        sendNow(data);
    }

    // RFC 1035 section 4.1.1: the header's second byte is laid out as
    // QR(1) OPCODE(4) AA(1) TC(1) RD(1) -- checked directly against the
    // raw wire bytes since this runs before any DNSMessage parse.
    private static boolean isEarlyDataEligible(ByteBuffer data) {
        if (data.remaining() < 3) {
            return false;
        }
        int opcode = (data.get(data.position() + 2) >> 3) & 0x0F;
        return opcode == DNSMessage.OPCODE_QUERY || opcode == DNSMessage.OPCODE_NOTIFY;
    }

    private void sendNow(ByteBuffer data) {
        // RFC 9250 section 4.2.1: rewrite Message ID to 0 on the wire.
        // DNSResolver's pendingQueries map (RFC 1035 section 7.3
        // correlation) is keyed by the real, non-zero ID it allocated,
        // so that original ID is captured here and restored onto the
        // response in DoQStreamHandler.disconnected() below -- otherwise
        // every response would parse to ID 0 and never match any pending
        // query, regardless of which query it actually answers.
        int originalId = 0;
        if (data.remaining() >= 2) {
            int pos = data.position();
            originalId = ((data.get(pos) & 0xFF) << 8) | (data.get(pos + 1) & 0xFF);
            data.put(pos, (byte) 0);
            data.put(pos + 1, (byte) 0);
        }
        // RFC 9250 section 5.4: add EDNS(0) padding
        ByteBuffer padded = DNSMessage.padToBlockSize(data, PADDING_BLOCK_SIZE);
        int len = padded.remaining();
        ByteBuffer framed = ByteBuffer.allocate(2 + len);
        framed.putShort((short) len);
        framed.put(padded);
        framed.flip();
        Endpoint stream = engine.openStream(
                new DoQStreamHandler(handler, originalId));
        stream.send(framed);
        stream.close();
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return engine.scheduleTimer(delayMs, callback);
    }

    @Override
    public void close() {
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * Handles a single DoQ response stream. Accumulates data until the
     * peer sends FIN ({@code disconnected}), then delivers the complete
     * response to the transport handler.
     */
    private static class DoQStreamHandler implements ProtocolHandler {

        private static final int MAX_DNS_MESSAGE_SIZE = 65535;

        private final DNSClientTransportHandler handler;
        private final int originalId;
        private final ByteArrayOutputStream accumulator =
                new ByteArrayOutputStream(512);

        DoQStreamHandler(DNSClientTransportHandler handler, int originalId) {
            this.handler = handler;
            this.originalId = originalId;
        }

        @Override
        public void connected(Endpoint ep) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void receive(ByteBuffer data) {
            int len = data.remaining();
            if (accumulator.size() + len > MAX_DNS_MESSAGE_SIZE) {
                handler.onError(new IOException(
                        "DoQ response too large"));
                return;
            }
            byte[] buf = new byte[len];
            data.get(buf);
            accumulator.write(buf, 0, buf.length);
        }

        @Override
        public void disconnected() {
            if (accumulator.size() < 2) {
                return;
            }
            byte[] raw = accumulator.toByteArray();
            // RFC 9250 section 4.2: strip 2-octet length prefix
            int msgLen = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
            if (msgLen > raw.length - 2) {
                handler.onError(new IOException(
                        "DoQ response length mismatch"));
                return;
            }
            // Restore the original Message ID (see sendNow) so
            // DNSResolver's ID-keyed correlation finds the right
            // pending query -- the server's ID field is 0 per RFC 9250
            // section 4.2.1, same as what was actually sent.
            if (msgLen >= 2) {
                raw[2] = (byte) (originalId >> 8);
                raw[3] = (byte) originalId;
            }
            handler.onReceive(ByteBuffer.wrap(raw, 2, msgLen));
        }

        @Override
        public void error(Exception cause) {
            handler.onError(cause);
        }
    }

}
