/*
 * DTLSSession.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.util.ByteBufferPool;

/**
 * Manages DTLS wrap/unwrap operations for one peer of a datagram
 * connection (issue #190).
 *
 * <p>Unlike TLS over TCP, DTLS operates on individual datagrams rather than
 * a continuous byte stream. Each datagram is independently encrypted/decrypted.
 *
 * <p>Key differences from TCP TLS:
 * <ul>
 * <li>No stream reassembly - each datagram is self-contained</li>
 * <li>Handshake flights may be lost and must be retransmitted by the
 *     application (this class), per RFC 6347 §4.2.4</li>
 * <li>No guaranteed ordering - datagrams may arrive out of order</li>
 * </ul>
 *
 * <p>The scratch buffers ({@code netOut}, {@code appIn}) are acquired once
 * from {@link ByteBufferPool} and reused for the life of the session; the
 * per-call buffers this class hands back to its caller (decrypted
 * application data from {@link #unwrap}, encrypted records from
 * {@link #wrap}, and retransmittable handshake flight records) are also
 * pool-sourced rather than freshly allocated per datagram. Handshake flight
 * buffers are held until the peer's next datagram arrives (confirming the
 * flight was received) or a retransmit gives up, then released back to the
 * pool.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DTLSSession {

    private static final Logger LOGGER = Logger.getLogger(DTLSSession.class.getName());

    /**
     * RFC 6347 §4.2.4.1 — initial retransmission timeout 1s, doubling on
     * each retry up to a minimum ceiling of 60s. The final two entries
     * are both 60s so a session gets two full-length tries at the ceiling
     * before giving up, rather than failing immediately on reaching it.
     */
    private static final long[] RETRANSMIT_TIMEOUTS_MS =
            { 1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L };

    /** A genuine zero-remaining buffer; see the comment at its NEED_WRAP use site. */
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private final SSLEngine engine;
    private final SSLSession session;
    private final InetSocketAddress remoteAddress;

    // Reference to parent for sending handshake messages
    private final UDPEndpoint endpoint;

    // Buffers for DTLS operations; acquired once and reused for the life
    // of this session (not per-datagram).
    private ByteBuffer netOut;
    private ByteBuffer appIn;

    private boolean handshakeComplete;
    private boolean closed;

    // Whether a delegated task (SSLEngine NEED_TASK) is currently running
    // off-loop on the CryptoExecutor pool (issue #274). Like the rest of
    // this class's mutable state, only ever touched from this session's
    // endpoint's own SelectorLoop thread (see the class-level note on
    // UDPEndpoint.dtlsSessions) -- unwrap()/wrap()/close() are guarded
    // against it below, but those guards assume single-threaded access,
    // not concurrent-thread safety.
    private boolean taskInFlight;

    // Encrypted datagrams that arrived from the peer while a delegated
    // task was in flight, queued for replay through unwrap() once the
    // task completes (see resumeAfterTask/drainPendingIncoming). Not
    // merely dropped: the peer's handshake flight arriving during this
    // brief window would otherwise only be recovered via its own
    // multi-second retransmit timeout.
    private final Deque<ByteBuffer> pendingIncoming = new ArrayDeque<ByteBuffer>();

    private final long handshakeStartTime;
    private SecurityInfo securityInfo;

    // -- Handshake flight retransmission (RFC 6347 §4.2.4) --
    private final List<ByteBuffer> currentFlight = new ArrayList<ByteBuffer>();
    private int retransmitAttempt;
    private TimerHandle retransmitTimer;

    /**
     * Creates a DTLS session for one peer.
     *
     * @param engine the SSLEngine for DTLS operations
     * @param endpoint the datagram endpoint for sending handshake data
     * @param remoteAddress the remote peer address
     */
    DTLSSession(SSLEngine engine, UDPEndpoint endpoint, InetSocketAddress remoteAddress) {
        this.engine = engine;
        this.session = engine.getSession();
        this.endpoint = endpoint;
        this.remoteAddress = remoteAddress;
        this.handshakeStartTime = System.currentTimeMillis();
        initBuffers();
    }

    private void initBuffers() {
        int netSize = Math.max(32768, session.getPacketBufferSize());
        int appSize = Math.max(32768, session.getApplicationBufferSize());

        netOut = ByteBufferPool.acquire(netSize);
        appIn = ByteBufferPool.acquire(appSize);
    }

    /**
     * Initiates the DTLS handshake. Must be called for both client-side
     * and server-side sessions immediately after construction: for a
     * client engine this sends the initial {@code ClientHello}; for a
     * server engine this simply puts the engine into the
     * {@code NEED_UNWRAP} state so it is ready to process one when it
     * arrives.
     */
    void beginHandshake() {
        if (handshakeComplete || closed) {
            return;
        }

        try {
            engine.beginHandshake();
            processHandshakeStatus(engine.getHandshakeStatus());
        } catch (SSLException e) {
            LOGGER.log(Level.SEVERE, "DTLS handshake initiation failed", e);
        }
    }

    /**
     * Unwraps (decrypts) an incoming DTLS record.
     *
     * @param datagram the incoming encrypted datagram
     * @return the decrypted application data, or null if handshake in progress
     */
    ByteBuffer unwrap(ByteBuffer datagram) {
        if (closed) {
            return null;
        }

        // Any datagram from the peer means our last-sent flight (if any)
        // got through far enough to provoke a reply; stop retransmitting it.
        cancelRetransmitTimer();
        releaseFlightBuffers();
        retransmitAttempt = 0;

        if (taskInFlight) {
            // A delegated task is running off-loop; the JDK forbids
            // calling unwrap()/wrap() on the engine while one is in
            // flight. Queue the raw bytes and replay through this same
            // method once the task completes (see resumeAfterTask /
            // drainPendingIncoming) rather than corrupting engine state.
            ByteBuffer copy = ByteBufferPool.acquire(datagram.remaining());
            copy.put(datagram);
            copy.flip();
            pendingIncoming.add(copy);
            return null;
        }

        try {
            // DTLS datagrams are self-contained, so we process each one individually
            appIn.clear();

            SSLEngineResult result = engine.unwrap(datagram, appIn);

            switch (result.getStatus()) {
                case OK:
                    processHandshakeStatus(result.getHandshakeStatus());
                    if (appIn.position() > 0) {
                        appIn.flip();
                        ByteBuffer data = ByteBufferPool.acquire(appIn.remaining());
                        data.put(appIn);
                        data.flip();
                        return data;
                    }
                    return null;

                case BUFFER_OVERFLOW:
                    // Application buffer too small
                    growAppIn();
                    return unwrap(datagram);

                case BUFFER_UNDERFLOW:
                    // Incomplete datagram - shouldn't happen with UDP
                    LOGGER.warning(Gumdrop.L10N.getString("warn.dtls_buffer_underflow"));
                    return null;

                case CLOSED:
                    handleClosed();
                    return null;
            }
        } catch (SSLException e) {
            LOGGER.log(Level.SEVERE, "DTLS unwrap error", e);
        }

        return null;
    }

    /**
     * Wraps (encrypts) outgoing application data.
     *
     * @param data the application data to encrypt
     * @return the encrypted DTLS record, or null if encryption failed
     */
    ByteBuffer wrap(ByteBuffer data) {
        if (closed || engine.isOutboundDone()) {
            return null;
        }

        if (taskInFlight) {
            // Engine busy with an async delegated task. Unlike the
            // inbound handshake-flight case, dropping outbound
            // application data here is consistent with DTLS's own
            // loss-tolerant contract -- and in practice rare, since
            // application data is only ever sent after the
            // handshake-complete callback fires.
            return null;
        }

        try {
            netOut.clear();

            SSLEngineResult result = engine.wrap(data, netOut);

            switch (result.getStatus()) {
                case OK:
                    processHandshakeStatus(result.getHandshakeStatus());
                    if (netOut.position() > 0) {
                        netOut.flip();
                        ByteBuffer encrypted = ByteBufferPool.acquire(netOut.remaining());
                        encrypted.put(netOut);
                        encrypted.flip();
                        return encrypted;
                    }
                    return null;

                case BUFFER_OVERFLOW:
                    // Network buffer too small
                    growNetOut();
                    return wrap(data);

                case CLOSED:
                    handleClosed();
                    return null;

                default:
                    return null;
            }
        } catch (SSLException e) {
            LOGGER.log(Level.SEVERE, "DTLS wrap error", e);
            return null;
        }
    }

    private void growAppIn() {
        int newSize = appIn.capacity() * 2;
        ByteBufferPool.release(appIn);
        appIn = ByteBufferPool.acquire(newSize);
    }

    private void growNetOut() {
        int newSize = netOut.capacity() * 2;
        ByteBufferPool.release(netOut);
        netOut = ByteBufferPool.acquire(newSize);
    }

    /**
     * Processes the handshake status and performs any required actions.
     */
    private void processHandshakeStatus(SSLEngineResult.HandshakeStatus hs) throws SSLException {
        while (hs != SSLEngineResult.HandshakeStatus.FINISHED &&
               hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

            switch (hs) {
                case NEED_TASK: {
                    // Run delegated tasks off-loop (issue #274): this is
                    // CPU-bound work (RSA/ECDHE key exchange, certificate
                    // chain validation) with no non-blocking JDK API, and
                    // must not run inline on the SelectorLoop thread. Must
                    // return here rather than fall through to a fresh
                    // engine.getHandshakeStatus() query below: that would
                    // still read NEED_TASK (the tasks haven't run yet) and
                    // spin forever.
                    List<Runnable> tasks = new ArrayList<Runnable>();
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        tasks.add(task);
                    }
                    taskInFlight = true;
                    submitDelegatedTasksAsync(tasks);
                    return;
                }

                case NEED_WRAP: {
                    // Need to send handshake data. EMPTY_BUFFER (not a
                    // pooled buffer) is passed as the source: a real
                    // zero-remaining buffer, signalling "no application
                    // data, handshake message only" -- a pooled buffer
                    // would come back with position 0/limit at its full
                    // (non-zero) bucket capacity, which is not the same
                    // thing and would confuse the engine.
                    netOut.clear();
                    SSLEngineResult result = engine.wrap(EMPTY_BUFFER, netOut);

                    if (netOut.position() > 0) {
                        netOut.flip();
                        ByteBuffer handshakeData = ByteBufferPool.acquire(netOut.remaining());
                        handshakeData.put(netOut);
                        handshakeData.flip();
                        // Retained (not released) until the peer's next
                        // datagram or a retransmit give-up, so it can be
                        // resent verbatim if this flight is lost.
                        currentFlight.add(handshakeData);
                        sendHandshakeData(handshakeData.duplicate());
                    }
                    // Take the status from this call's own result, not a
                    // fresh engine.getHandshakeStatus() query: JSSE only
                    // reports FINISHED on the result of the exact call
                    // that completed the handshake (e.g. the wrap() that
                    // sends the client's/server's own Finished message) --
                    // a later separate query can already read back
                    // NOT_HANDSHAKING instead, silently skipping the
                    // securityEstablished notification below.
                    hs = result.getHandshakeStatus();
                    break;
                }

                case NEED_UNWRAP:
                    // Need more data from remote - arm the retransmit
                    // timer for the flight just sent (if any) and return;
                    // wait for the peer.
                    scheduleRetransmit();
                    return;

                case NEED_UNWRAP_AGAIN: {
                    // DTLS-specific: the engine has already-buffered data
                    // (e.g. a coalesced flight) to reprocess without new
                    // network input.
                    appIn.clear();
                    SSLEngineResult result = engine.unwrap(EMPTY_BUFFER, appIn);
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        growAppIn();
                    }
                    // See the NEED_WRAP case above for why this must come
                    // from the call's own result, not a fresh query.
                    hs = result.getHandshakeStatus();
                    break;
                }

                default:
                    // Handle any unknown status (including future additions)
                    return;
            }
        }

        // NOT_HANDSHAKING is included here for the same reason noted at
        // the NEED_WRAP/NEED_UNWRAP_AGAIN cases: depending on which call
        // completes the handshake, its result may already have moved
        // past the one-shot FINISHED signal by the time this checks it.
        if ((hs == SSLEngineResult.HandshakeStatus.FINISHED
                || hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
                && !handshakeComplete) {
            handshakeComplete = true;
            cancelRetransmitTimer();
            releaseFlightBuffers();
            securityInfo = new JSSESecurityInfo(engine, handshakeStartTime);
            if (LOGGER.isLoggable(Level.FINE)) {
                String message = MessageFormat.format(
                        Gumdrop.L10N.getString("info.dtls_handshake_complete"),
                        remoteAddress, engine.getSession().getProtocol());
                LOGGER.fine(message);
            }
            endpoint.notifyDtlsHandshakeComplete(remoteAddress, securityInfo);
        }
    }

    /**
     * Submits {@code tasks} (drained from {@code engine.getDelegatedTask()})
     * to the shared {@link CryptoExecutor} and arranges for
     * {@link #resumeAfterTask} to run on this session's endpoint's loop
     * thread once they finish. Falls back to running them inline if no
     * {@link Gumdrop} instance is available (e.g. a unit test constructing
     * a {@code DTLSSession} directly) -- mirrors the same fallback in
     * {@code SSLState.submitDelegatedTasksAsync}.
     */
    private void submitDelegatedTasksAsync(final List<Runnable> tasks) {
        Gumdrop gumdrop = Gumdrop.getInstance();
        CryptoExecutor exec = (gumdrop != null) ? gumdrop.getCryptoExecutor() : null;
        if (exec == null) {
            for (Runnable task : tasks) {
                task.run();
            }
            resumeAfterTask();
            return;
        }
        exec.submit(endpoint, new Callable<Void>() {
            @Override
            public Void call() {
                for (Runnable task : tasks) {
                    task.run();
                }
                return null;
            }
        }, new CryptoExecutor.Callback<Void>() {
            @Override
            public void completed(Void result) {
                resumeAfterTask();
            }
            @Override
            public void failed(Throwable error) {
                taskInFlight = false;
                if (closed) {
                    return;
                }
                LOGGER.log(Level.SEVERE, "DTLS delegated task failed", error);
                fail("DTLS delegated task failed: " + error);
            }
        });
    }

    /**
     * Resumes handshake processing after an async delegated task
     * completes, then replays any datagrams that arrived from the peer
     * while it was in flight (see {@link #pendingIncoming}).
     */
    private void resumeAfterTask() {
        taskInFlight = false;
        if (closed) {
            return;
        }
        try {
            processHandshakeStatus(engine.getHandshakeStatus());
        } catch (SSLException e) {
            LOGGER.log(Level.SEVERE, "DTLS error resuming after delegated task", e);
            fail("DTLS error resuming after delegated task: " + e.getMessage());
            return;
        }
        drainPendingIncoming();
    }

    /**
     * Replays datagrams queued by {@link #unwrap} while a delegated task
     * was in flight. Stops early (leaving the remainder queued) if
     * processing one of them starts another delegated task -- the next
     * {@link #resumeAfterTask} continues the drain.
     */
    private void drainPendingIncoming() {
        while (!taskInFlight && !closed && !pendingIncoming.isEmpty()) {
            ByteBuffer queued = pendingIncoming.poll();
            ByteBuffer plaintext;
            try {
                plaintext = unwrap(queued);
            } finally {
                ByteBufferPool.release(queued);
            }
            if (plaintext != null) {
                endpoint.deliverPlaintext(plaintext);
            }
        }
    }

    /**
     * Resends the current handshake flight after a timeout, per RFC 6347
     * §4.2.4. Gives up (failing the session) once
     * {@link #RETRANSMIT_TIMEOUTS_MS} is exhausted.
     */
    private void scheduleRetransmit() {
        if (currentFlight.isEmpty() || closed || handshakeComplete) {
            return;
        }
        cancelRetransmitTimer();
        if (retransmitAttempt >= RETRANSMIT_TIMEOUTS_MS.length) {
            String message = MessageFormat.format(
                    Gumdrop.L10N.getString("warn.dtls_handshake_timeout"),
                    remoteAddress, Integer.valueOf(retransmitAttempt));
            LOGGER.warning(message);
            fail(message);
            return;
        }
        long timeoutMs = RETRANSMIT_TIMEOUTS_MS[retransmitAttempt];
        retransmitTimer = endpoint.scheduleTimer(timeoutMs, new Runnable() {
            @Override
            public void run() {
                onRetransmitTimeout();
            }
        });
    }

    private void onRetransmitTimeout() {
        retransmitTimer = null;
        if (closed || handshakeComplete || currentFlight.isEmpty()) {
            return;
        }
        retransmitAttempt++;
        for (ByteBuffer flightRecord : currentFlight) {
            sendHandshakeData(flightRecord.duplicate());
        }
        scheduleRetransmit();
    }

    private void cancelRetransmitTimer() {
        if (retransmitTimer != null) {
            retransmitTimer.cancel();
            retransmitTimer = null;
        }
    }

    private void releaseFlightBuffers() {
        for (ByteBuffer flightRecord : currentFlight) {
            ByteBufferPool.release(flightRecord);
        }
        currentFlight.clear();
    }

    /** Handshake or session failure that isn't a normal peer-initiated close. */
    private void fail(String reason) {
        closed = true;
        cancelRetransmitTimer();
        releaseFlightBuffers();
        releasePendingIncoming();
        releaseScratchBuffers();
        endpoint.onDtlsSessionFailed(remoteAddress, new IOException(reason));
    }

    /** Common cleanup for a {@code CLOSED} engine status observed during unwrap/wrap. */
    private void handleClosed() {
        if (closed) {
            return;
        }
        closed = true;
        cancelRetransmitTimer();
        releaseFlightBuffers();
        releasePendingIncoming();
        releaseScratchBuffers();
        endpoint.removeDtlsSession(remoteAddress);
    }

    private void releaseScratchBuffers() {
        ByteBufferPool.release(netOut);
        ByteBufferPool.release(appIn);
    }

    private void releasePendingIncoming() {
        ByteBuffer queued;
        while ((queued = pendingIncoming.poll()) != null) {
            ByteBufferPool.release(queued);
        }
    }

    /**
     * Sends handshake data to the remote peer.
     */
    private void sendHandshakeData(ByteBuffer data) {
        if (endpoint != null) {
            endpoint.sendRawDatagram(data, remoteAddress);
        }
    }

    /**
     * Returns whether the handshake has completed.
     */
    boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    /**
     * Returns the negotiated security parameters, or null before the
     * handshake completes.
     */
    SecurityInfo getSecurityInfo() {
        return securityInfo;
    }

    /**
     * Closes the DTLS session.
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelRetransmitTimer();
        releaseFlightBuffers();
        releasePendingIncoming();

        if (taskInFlight) {
            // A delegated task is still running on the crypto pool for
            // this session; engine.closeOutbound()/wrap() below must not
            // be called concurrently with it (see unwrap()). Skip the
            // close_notify handshake -- the peer simply sees this session
            // go silent, same as it would for any other network-level
            // loss -- and let the task's own completion callback finish
            // quietly (it already checks `closed` before touching
            // anything further).
            releaseScratchBuffers();
            endpoint.removeDtlsSession(remoteAddress);
            return;
        }

        try {
            engine.closeOutbound();

            // Send close_notify
            netOut.clear();
            SSLEngineResult result = engine.wrap(EMPTY_BUFFER, netOut);

            if (netOut.position() > 0) {
                netOut.flip();
                ByteBuffer closeNotify = ByteBufferPool.acquire(netOut.remaining());
                closeNotify.put(netOut);
                closeNotify.flip();
                sendHandshakeData(closeNotify);
                ByteBufferPool.release(closeNotify);
            }
        } catch (SSLException e) {
            LOGGER.log(Level.WARNING, "Error sending DTLS close_notify", e);
        } finally {
            releaseScratchBuffers();
            endpoint.removeDtlsSession(remoteAddress);
        }
    }

    /**
     * Returns the cipher suite in use after handshake.
     */
    String getCipherSuite() {
        return handshakeComplete ? session.getCipherSuite() : null;
    }

}
