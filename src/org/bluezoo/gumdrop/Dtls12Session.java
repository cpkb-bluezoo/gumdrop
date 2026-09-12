/*
 * Dtls12Session.java
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.tls.AlertDescription;
import org.bluezoo.gumdrop.tls.Dtls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.Dtls12HelloVerify;
import org.bluezoo.gumdrop.tls.Dtls12RecordEngine;
import org.bluezoo.gumdrop.tls.DtlsRetransmitState;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.TlsProtocolError;
import org.bluezoo.gumdrop.tls.TlsRecordSink;
import org.bluezoo.gumdrop.util.ByteBufferPool;

/**
 * In-tree DTLS 1.2 session for one UDP peer (issue #190).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class Dtls12Session implements TlsRecordSink {

    private static final Logger LOGGER = Logger.getLogger(Dtls12Session.class.getName());

    private final Dtls12HandshakeConfig config;
    private final UDPEndpoint endpoint;
    private final InetSocketAddress remoteAddress;

    private Dtls12RecordEngine engine;
    private final DtlsRetransmitState retransmit = new DtlsRetransmitState();
    private final List<byte[]> flightBuilder = new ArrayList<byte[]>();

    private TimerHandle retransmitTimer;
    private boolean handshakeComplete;
    private boolean closed;
    private SecurityInfo securityInfo;
    private final long handshakeStartTime = System.currentTimeMillis();

    Dtls12Session(Dtls12HandshakeConfig config, UDPEndpoint endpoint, InetSocketAddress remoteAddress) {
        this.config = config;
        this.endpoint = endpoint;
        this.remoteAddress = remoteAddress;
    }

    void beginHandshake() {
        if (handshakeComplete || closed) {
            return;
        }
        if (config.getRole() != HandshakeRole.CLIENT) {
            return;
        }
        ensureEngine(null);
        flightBuilder.clear();
        engine.start(this);
        commitFlightIfNeeded();
    }

    void receive(byte[] datagram) {
        if (closed) {
            return;
        }
        retransmit.onProgress();
        cancelRetransmitTimer();

        if (config.getRole() == HandshakeRole.SERVER && config.isRequireCookie() && engine == null) {
            if (!handleServerCookieExchange(datagram)) {
                return;
            }
        }

        ensureEngine(null);
        flightBuilder.clear();
        engine.feedDatagram(datagram, this);
        commitFlightIfNeeded();
    }

    ByteBuffer send(ByteBuffer data) {
        if (closed || !handshakeComplete || engine == null) {
            return null;
        }
        byte[] plaintext = new byte[data.remaining()];
        data.get(plaintext);
        sendApplicationData(plaintext);
        return null;
    }

    /**
     * Sends application data, enqueueing ciphertext datagrams on the endpoint.
     */
    void sendApplicationData(byte[] plaintext) {
        if (closed || !handshakeComplete || engine == null) {
            return;
        }
        flightBuilder.clear();
        engine.sendApplicationData(plaintext, this);
        flushOutboundFlight();
    }

    boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    SecurityInfo getSecurityInfo() {
        return securityInfo;
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelRetransmitTimer();
        if (engine != null && handshakeComplete) {
            flightBuilder.clear();
            engine.sendCloseNotify(this);
            flushOutboundFlight();
        }
        endpoint.removeDtlsSession(remoteAddress);
    }

    @Override
    public void ciphertextReady(byte[] data) {
        flightBuilder.add(data);
        if (handshakeComplete) {
            flushOutboundFlight();
        }
    }

    @Override
    public void applicationDataReady(byte[] plaintext) {
        ByteBuffer buf = ByteBufferPool.acquire(plaintext.length);
        buf.put(plaintext);
        buf.flip();
        endpoint.deliverPlaintext(buf);
    }

    @Override
    public void handshakeComplete() {
        if (handshakeComplete) {
            return;
        }
        handshakeComplete = true;
        cancelRetransmitTimer();
        retransmit.onProgress();
        flushOutboundFlight();
        securityInfo = new Dtls12SecurityInfo(engine, config, handshakeStartTime);
        if (LOGGER.isLoggable(Level.FINE)) {
            String message = MessageFormat.format(
                    Gumdrop.L10N.getString("info.dtls_handshake_complete"),
                    remoteAddress, "DTLSv1.2");
            LOGGER.fine(message);
        }
        endpoint.notifyDtlsHandshakeComplete(remoteAddress, securityInfo);
    }

    @Override
    public void protocolError(TlsProtocolError error) {
        fail(error.getMessage());
    }

    @Override
    public void peerClosed() {
        closed = true;
        cancelRetransmitTimer();
        endpoint.removeDtlsSession(remoteAddress);
    }

    private boolean handleServerCookieExchange(byte[] datagram) {
        Dtls12HelloVerify.ClientHelloFields fields = Dtls12HelloVerify.parseClientHelloFields(datagram);
        if (fields == null) {
            return false;
        }
        byte[] secret = config.getCookieSecret();
        if (secret == null || secret.length == 0) {
            fail("DTLS cookie exchange enabled but no cookie secret configured");
            return false;
        }
        if (Dtls12HelloVerify.validateCookie(secret, fields.random, remoteAddress, fields.cookie)) {
            ensureEngine(fields.cookie);
            return true;
        }
        try {
            byte[] cookie = Dtls12HelloVerify.computeCookie(secret, fields.random, remoteAddress);
            byte[] hvr = Dtls12HelloVerify.buildHelloVerifyRequestDatagram(cookie);
            sendRawDatagram(hvr);
        } catch (Exception e) {
            fail("Failed to build HelloVerifyRequest: " + e.getMessage());
        }
        return false;
    }

    private void ensureEngine(byte[] cookie) {
        if (engine != null) {
            return;
        }
        Tls12HandshakeConfig base = config.copyBaseForEngine();
        if (cookie != null) {
            base.setDtlsCookie(cookie);
        }
        engine = new Dtls12RecordEngine(base, config.getMaxFragmentSize());
        if (config.getRole() == HandshakeRole.CLIENT) {
            engine.setHelloVerifyCallback(new Dtls12RecordEngine.HelloVerifyCallback() {
                @Override
                public void onHelloVerifyRequest(byte[] cookieFromServer) {
                    restartClientWithCookie(cookieFromServer);
                }
            });
        }
    }

    private void restartClientWithCookie(byte[] cookieFromServer) {
        byte[] preservedRandom = engine.getClientRandom();
        engine = null;
        Tls12HandshakeConfig base = config.copyBaseForEngine();
        base.setDtlsClientRandom(preservedRandom);
        base.setDtlsCookie(cookieFromServer);
        engine = new Dtls12RecordEngine(base, config.getMaxFragmentSize());
        engine.setHelloVerifyCallback(new Dtls12RecordEngine.HelloVerifyCallback() {
            @Override
            public void onHelloVerifyRequest(byte[] ignored) {
                fail("unexpected second HelloVerifyRequest");
            }
        });
        flightBuilder.clear();
        engine.start(this);
        commitFlightIfNeeded();
    }

    private void commitFlightIfNeeded() {
        if (engine != null && engine.isFailed()) {
            fail("DTLS handshake failed");
            return;
        }
        if (handshakeComplete || closed || engine == null || flightBuilder.isEmpty()) {
            return;
        }
        List<byte[]> flight = new ArrayList<byte[]>(flightBuilder);
        flightBuilder.clear();
        for (int i = 0; i < flight.size(); i++) {
            sendRawDatagram(flight.get(i));
        }
        retransmit.onFlightSent(flight);
        scheduleRetransmit();
    }

    private void flushOutboundFlight() {
        for (int i = 0; i < flightBuilder.size(); i++) {
            sendRawDatagram(flightBuilder.get(i));
        }
        flightBuilder.clear();
    }

    private void sendRawDatagram(byte[] data) {
        ByteBuffer buf = ByteBufferPool.acquire(data.length);
        buf.put(data);
        buf.flip();
        endpoint.sendOwnedRawDatagram(buf, remoteAddress);
    }

    private void scheduleRetransmit() {
        if (closed || handshakeComplete || !retransmit.hasFlight()) {
            return;
        }
        long timeoutMs = retransmit.currentTimeoutMs();
        if (timeoutMs < 0L) {
            String message = MessageFormat.format(
                    Gumdrop.L10N.getString("warn.dtls_handshake_timeout"),
                    remoteAddress, Integer.valueOf(retransmit.currentFlight().size()));
            LOGGER.warning(message);
            fail(message);
            return;
        }
        retransmitTimer = endpoint.scheduleTimer(timeoutMs, new Runnable() {
            @Override
            public void run() {
                onRetransmitTimeout();
            }
        });
    }

    private void onRetransmitTimeout() {
        retransmitTimer = null;
        if (closed || handshakeComplete) {
            return;
        }
        List<byte[]> flight = retransmit.onTimerFired();
        if (flight == null) {
            fail(MessageFormat.format(
                    Gumdrop.L10N.getString("warn.dtls_handshake_timeout"), remoteAddress, 0));
            return;
        }
        for (int i = 0; i < flight.size(); i++) {
            sendRawDatagram(flight.get(i));
        }
        scheduleRetransmit();
    }

    private void cancelRetransmitTimer() {
        if (retransmitTimer != null) {
            retransmitTimer.cancel();
            retransmitTimer = null;
        }
    }

    private void fail(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        cancelRetransmitTimer();
        retransmit.onProgress();
        flightBuilder.clear();
        endpoint.onDtlsSessionFailed(remoteAddress, new IOException(reason));
    }

}
