/*
 * ServletWebConnection.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.websocket.DefaultWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebConnection implementation that bridges the servlet WebSocket API
 * with Gumdrop's WebSocket implementation.
 *
 * <p>Incoming WebSocket messages are delivered through a non-blocking
 * {@link RequestBodyStream} (replacing a pipe that could block the
 * SelectorLoop thread when the servlet read side was slow). Backpressure
 * is applied via {@link HTTPResponseState#pauseRequestBody()} when the
 * buffer reaches its high-water mark. {@link HttpUpgradeHandler#init} and
 * {@link HttpUpgradeHandler#destroy} are dispatched to the servlet worker pool
 * so handler lifecycle never blocks the SelectorLoop thread. Outbound messages
 * are sent on the connection's I/O thread with transport backpressure matching
 * the HTTP response path; flushed output buffers are transferred without an
 * extra servlet-layer copy.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletWebConnection implements WebConnection {

    private static final Logger LOGGER = Logger.getLogger(ServletWebConnection.class.getName());

    private final HttpUpgradeHandler upgradeHandler;
    private final HTTPResponseState state;
    private final ServletHandler handler;
    private final RequestBodyStream messageStream;
    private final WebSocketServletInputStream inputStream;
    private final WebSocketServletOutputStream outputStream;
    private final WebSocketEventHandler eventHandler;

    private volatile WebSocketSession session;
    private volatile boolean closed = false;
    private volatile boolean writePossibleScheduled;
    private final CountDownLatch upgradeInitStarted = new CountDownLatch(1);
    private final AtomicBoolean upgradeDestroyDone = new AtomicBoolean();

    private static final int PENDING_RESPONSE_HIGH_WATERMARK = 4 * 1024 * 1024;
    private static final long PENDING_RESPONSE_WAIT_TIMEOUT_MS = 30000L;

    /**
     * Creates a new WebConnection for the given upgrade handler.
     *
     * @param upgradeHandler the servlet's upgrade handler
     * @param state the HTTP response state for backpressure and callbacks
     * @param handler the servlet handler for container callback dispatch
     */
    ServletWebConnection(HttpUpgradeHandler upgradeHandler,
            HTTPResponseState state, ServletHandler handler) {
        this.upgradeHandler = upgradeHandler;
        this.state = state;
        this.handler = handler;

        this.messageStream = new RequestBodyStream();
        messageStream.setResumeCallback(new Runnable() {
            @Override
            public void run() {
                // May be called from the worker thread (inside
                // RequestBodyStream.read()); resumeRequestBody() must
                // run on the SelectorLoop thread.
                if (ServletWebConnection.this.state != null) {
                    ServletWebConnection.this.state.execute(new Runnable() {
                        @Override
                        public void run() {
                            ServletWebConnection.this.state.resumeRequestBody();
                        }
                    });
                }
            }
        });

        this.inputStream = new WebSocketServletInputStream(handler, messageStream);
        this.outputStream = new WebSocketServletOutputStream(this);
        this.eventHandler = new WebConnectionEventHandler();
    }

    /**
     * Returns the WebSocket event handler to pass to upgradeToWebSocket.
     */
    WebSocketEventHandler getEventHandler() {
        return eventHandler;
    }

    /**
     * Called when the WebSocket session is established.
     */
    void sessionOpened(WebSocketSession session) {
        this.session = session;
    }

    /**
     * Sends buffered servlet output as a WebSocket message.
     *
     * <p>Called from the servlet worker thread. The send is marshalled onto
     * the connection's I/O thread and respects transport backpressure.
     */
    void sendMessage(byte[] data) throws IOException {
        if (data.length == 0) {
            return;
        }
        sendMessage(ByteBuffer.wrap(data), false);
    }

    /**
     * Sends buffered servlet output as a WebSocket message.
     *
     * @param buf message bytes to send
     * @param transferOwnership if true, {@code buf} is handed off to the
     *     I/O thread and must not be reused by the caller; if false, a copy
     *     is made because the caller may still mutate or reuse the buffer
     */
    void sendMessage(ByteBuffer buf, boolean transferOwnership) throws IOException {
        if (session == null) {
            throw new IOException("WebSocket session not established");
        }
        if (!session.isOpen()) {
            throw new IOException("WebSocket session is closed");
        }
        if (!buf.hasRemaining()) {
            return;
        }

        final ByteBuffer payload;
        if (transferOwnership) {
            payload = buf;
        } else {
            payload = ByteBuffer.allocate(buf.remaining());
            payload.put(buf.duplicate());
            payload.flip();
        }
        final boolean asText = isUtf8Text(payload);

        if (!isResponseWritable()) {
            if (outputStream.hasWriteListener()) {
                scheduleWritePossibleNotification();
                throw new IllegalStateException(
                        ServletService.L10N.getString("err.write_not_ready"));
            }
            awaitResponseWritable();
        }

        if (state == null) {
            sendMessageDirect(payload, asText);
            return;
        }

        final ByteBuffer message = payload;
        state.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    sendMessageDirect(message, asText);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error sending WebSocket message", e);
                }
                if (outputStream.hasWriteListener()) {
                    dispatchContainerCallback(new Runnable() {
                        @Override
                        public void run() {
                            outputStream.notifyWritePossible();
                            if (!isResponseWritable()) {
                                scheduleWritePossibleNotification();
                            }
                        }
                    });
                }
            }
        });
    }

    boolean isResponseWritable() {
        return state == null
                || state.pendingResponseBytes() <= PENDING_RESPONSE_HIGH_WATERMARK;
    }

    void dispatchContainerCallback(Runnable task) {
        if (handler != null) {
            handler.dispatchContainerCallback(task);
        } else if (state != null) {
            state.execute(task);
        } else {
            task.run();
        }
    }

    void notifyWritePossible() {
        outputStream.notifyWritePossible();
    }

    private void sendMessageDirect(ByteBuffer data, boolean asText) throws IOException {
        if (session == null || !session.isOpen()) {
            throw new IOException("WebSocket session is closed");
        }
        if (asText) {
            session.sendText(StandardCharsets.UTF_8.decode(data.duplicate()).toString());
        } else {
            session.sendBinary(data.duplicate());
        }
    }

    private static boolean isUtf8Text(ByteBuffer data) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(data.duplicate());
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private void awaitResponseWritable() throws IOException {
        if (isResponseWritable() || state == null) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        state.execute(new Runnable() {
            @Override
            public void run() {
                state.onWritable(new Runnable() {
                    @Override
                    public void run() {
                        latch.countDown();
                    }
                });
            }
        });
        try {
            if (!latch.await(PENDING_RESPONSE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                state.execute(new Runnable() {
                    @Override
                    public void run() {
                        state.onWritable(null);
                        state.cancel();
                    }
                });
                throw new IOException(
                        "Timed out waiting for WebSocket write backpressure to clear");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "Interrupted while waiting for WebSocket write backpressure", e);
        }
    }

    private void scheduleWritePossibleNotification() {
        if (writePossibleScheduled || state == null
                || !outputStream.hasWriteListener()) {
            return;
        }
        writePossibleScheduled = true;
        state.execute(new Runnable() {
            @Override
            public void run() {
                state.onWritable(new Runnable() {
                    @Override
                    public void run() {
                        writePossibleScheduled = false;
                        if (outputStream.hasWriteListener()) {
                            dispatchContainerCallback(new Runnable() {
                                @Override
                                public void run() {
                                    outputStream.notifyWritePossible();
                                    if (!isResponseWritable()) {
                                        scheduleWritePossibleNotification();
                                    }
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private void closeSessionOnIoThread() {
        if (session == null || !session.isOpen()) {
            return;
        }
        if (state != null) {
            state.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        session.close();
                    } catch (IOException e) {
                        LOGGER.log(Level.FINE, "Error closing WebSocket session", e);
                    }
                }
            });
        } else {
            try {
                session.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing WebSocket session", e);
            }
        }
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return inputStream;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return outputStream;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        messageStream.finish();
        inputStream.dispatchDataAvailable();
        messageStream.close();

        closeSessionOnIoThread();
    }

    /**
     * Returns true if the connection is closed.
     */
    boolean isClosed() {
        return closed;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket Event Handler
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Event handler that bridges WebSocket events to the servlet WebConnection.
     */
    private class WebConnectionEventHandler extends DefaultWebSocketEventHandler {

        @Override
        public void opened(WebSocketSession session) {
            sessionOpened(session);
            dispatchUpgradeInit();
        }

        @Override
        public void textMessageReceived(WebSocketSession session,
                                        String message) {
            deliverMessage(message.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void binaryMessageReceived(WebSocketSession session,
                                          ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            deliverMessage(bytes);
        }

        @Override
        public void closed(int code, String reason) {
            if (!closed) {
                messageStream.finish();
                inputStream.dispatchDataAvailable();
            }

            dispatchUpgradeDestroy();
        }

        @Override
        public void error(Throwable cause) {
            LOGGER.log(Level.WARNING, "WebSocket error", cause);
            if (!closed) {
                messageStream.fail(new IOException("WebSocket error", cause));
                inputStream.dispatchDataAvailable();
            }
            try {
                close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing on WebSocket error", e);
            }
        }
    }

    private void deliverMessage(byte[] data) {
        if (closed || data.length == 0) {
            return;
        }
        if (messageStream.offer(data) && state != null) {
            state.pauseRequestBody();
        }
        inputStream.dispatchDataAvailable();
    }

    private void dispatchUpgradeInit() {
        Runnable initTask = new Runnable() {
            @Override
            public void run() {
                upgradeInitStarted.countDown();
                try {
                    upgradeHandler.init(ServletWebConnection.this);
                } catch (Exception e) {
                    handleUpgradeInitFailure(e);
                }
            }
        };
        Runnable onRejected = new Runnable() {
            @Override
            public void run() {
                upgradeInitStarted.countDown();
                LOGGER.log(Level.WARNING,
                        "Worker pool saturated; closing WebSocket upgrade");
                try {
                    close();
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Error closing rejected upgrade", e);
                }
            }
        };
        if (handler != null) {
            handler.dispatchWorkerTask(initTask, onRejected);
        } else {
            initTask.run();
        }
    }

    private void dispatchUpgradeDestroy() {
        Runnable destroyTask = new Runnable() {
            @Override
            public void run() {
                runUpgradeDestroy();
            }
        };
        if (handler != null) {
            handler.dispatchWorkerTask(destroyTask, null);
        } else {
            destroyTask.run();
        }
    }

    private void runUpgradeDestroy() {
        if (!upgradeDestroyDone.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!upgradeInitStarted.await(PENDING_RESPONSE_WAIT_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS)) {
                LOGGER.log(Level.WARNING,
                        "Timed out waiting for upgrade handler init before destroy");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.FINE,
                    "Interrupted waiting for upgrade handler init before destroy", e);
            return;
        }
        try {
            upgradeHandler.destroy();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error destroying upgrade handler", e);
        }
    }

    private void handleUpgradeInitFailure(final Exception e) {
        LOGGER.log(Level.WARNING, "Error initializing upgrade handler", e);
        Runnable closeTask = new Runnable() {
            @Override
            public void run() {
                try {
                    close();
                } catch (IOException ioe) {
                    LOGGER.log(Level.FINE, "Error closing after upgrade init failure", ioe);
                }
            }
        };
        if (state != null) {
            state.execute(closeTask);
        } else {
            closeTask.run();
        }
    }

}
