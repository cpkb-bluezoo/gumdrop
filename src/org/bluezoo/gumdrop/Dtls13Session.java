/*
 * Dtls13Session.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.tls.HandshakeAsyncOffload;
import org.bluezoo.gumdrop.tls.AlertDescription;
import org.bluezoo.gumdrop.tls.Dtls13HandshakeConfig;
import org.bluezoo.gumdrop.tls.Dtls13RecordEngine;
import org.bluezoo.gumdrop.tls.DtlsRetransmitState;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.TlsProtocolError;
import org.bluezoo.gumdrop.tls.TlsRecordSink;
import org.bluezoo.gumdrop.util.ByteBufferPool;

/**
 * In-tree DTLS 1.3 session for one UDP peer.
 */
final class Dtls13Session implements TlsRecordSink {

    private static final Logger LOGGER = Logger.getLogger(Dtls13Session.class.getName());

    private final Dtls13HandshakeConfig config;
    private final UDPEndpoint endpoint;
    private final InetSocketAddress remoteAddress;

    private Dtls13RecordEngine engine;
    private final DtlsRetransmitState retransmit = new DtlsRetransmitState();
    private final List<byte[]> flightBuilder = new ArrayList<byte[]>();

    private TimerHandle retransmitTimer;
    private boolean handshakeComplete;
    private boolean closed;
    private SecurityInfo securityInfo;
    private final long handshakeStartTime = System.currentTimeMillis();

    Dtls13Session(Dtls13HandshakeConfig config, UDPEndpoint endpoint, InetSocketAddress remoteAddress) {
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
        ensureEngine();
        flightBuilder.clear();
        engine.start(this);
        commitFlightIfNeeded();
    }

    void receive(byte[] datagram) {
        receive(datagram, 0, datagram.length);
    }

    void receive(byte[] datagram, int offset, int length) {
        if (closed) {
            return;
        }
        retransmit.onProgress();
        cancelRetransmitTimer();
        ensureEngine();
        flightBuilder.clear();
        engine.feedDatagram(datagram, offset, length, this);
        commitFlightIfNeeded();
    }

    void sendApplicationData(byte[] plaintext) {
        sendApplicationData(plaintext, 0, plaintext.length);
    }

    void sendApplicationData(byte[] plaintext, int offset, int length) {
        if (closed || !handshakeComplete || engine == null) {
            return;
        }
        flightBuilder.clear();
        engine.sendApplicationData(plaintext, offset, length, this);
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
        endpoint.removeDtls13Session(remoteAddress);
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
        securityInfo = new Dtls13SecurityInfo(engine, config, handshakeStartTime);
        if (LOGGER.isLoggable(Level.FINE)) {
            String message = MessageFormat.format(
                    Gumdrop.L10N.getString("info.dtls_handshake_complete"),
                    remoteAddress, "DTLSv1.3");
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
        endpoint.removeDtls13Session(remoteAddress);
    }

    private void ensureEngine() {
        if (engine != null) {
            return;
        }
        HandshakeConfig base = config.copyBaseForEngine();
        if (config.getRole() == HandshakeRole.SERVER && config.isRequireCookie()) {
            byte[] secret = config.getCookieSecret();
            if (secret == null || secret.length == 0) {
                fail("DTLS cookie exchange enabled but no cookie secret configured");
                return;
            }
            base.setCookieValidator(new Dtls13CookieValidator(secret, remoteAddress));
        }
        Dtls13HandshakeConfig engineConfig = wrapConfig(base);
        engine = new Dtls13RecordEngine(engineConfig, config.getMaxFragmentSize(), handshakeOffload(endpoint));
    }

    private Dtls13HandshakeConfig wrapConfig(HandshakeConfig base) {
        Dtls13HandshakeConfig wrapped = new Dtls13HandshakeConfig(base);
        wrapped.setRequireCookie(config.isRequireCookie());
        wrapped.setCookieSecret(config.getCookieSecret());
        wrapped.setMaxFragmentSize(config.getMaxFragmentSize());
        return wrapped;
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
        endpoint.onDtls13SessionFailed(remoteAddress, new IOException(reason));
    }

    private static HandshakeAsyncOffload handshakeOffload(final UDPEndpoint endpoint) {
        return new TlsHandshakeAsyncOffload(loopExecutor(endpoint));
    }

    private static Executor loopExecutor(final UDPEndpoint endpoint) {
        return new Executor() {
            @Override
            public void execute(Runnable task) {
                endpoint.execute(task);
            }
        };
    }
}
