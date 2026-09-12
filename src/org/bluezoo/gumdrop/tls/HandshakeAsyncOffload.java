/*
 * HandshakeAsyncOffload.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

/**
 * Offloads handshake CPU work off a connection's I/O thread while deferring
 * {@link TlsEventSink} callbacks until the batch completes on the loop thread.
 *
 * <p>Implementations such as {@link org.bluezoo.gumdrop.TlsHandshakeAsyncOffload}
 * live outside this package because they depend on {@code Gumdrop} and
 * {@code CryptoExecutor}, which compile after the early {@code tls} unit.
 */
public interface HandshakeAsyncOffload {

    interface BatchProcessor {
        void process();
    }

    interface CompletionHandler {
        boolean onBatchDone();
    }

    interface FailureHandler {
        void failed(Throwable error);
    }

    Object lock();

    boolean isBusy();

    boolean isDeferring();

    void dispatch(Runnable call);

    void submit(BatchProcessor processor, CompletionHandler onDone, FailureHandler onFailure);

    /**
     * Optional hook invoked on the loop thread once a submitted batch and
     * any follow-up batches it queued are fully complete.
     */
    default void setIdleListener(Runnable listener) {
    }
}
