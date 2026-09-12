/*
 * HandshakeAsyncScheduler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Queues handshake starts and messages while a {@link TlsHandshakeAsyncOffload}
 * batch is in flight, then drains the queue from the completion handler.
 */
final class HandshakeAsyncScheduler {

    interface Runner {
        void runStart();

        void runMessages(List<byte[]> messages);
    }

    private final TlsHandshakeAsyncOffload offload;
    private final Runner runner;
    private final TlsHandshakeAsyncOffload.FailureHandler onFailure;
    private final ArrayDeque<byte[]> pendingMessages = new ArrayDeque<byte[]>();
    private final ArrayList<byte[]> singleMessageBatch = new ArrayList<byte[]>(1);
    private final ArrayList<byte[]> drainBatch = new ArrayList<byte[]>();
    private final TlsHandshakeAsyncOffload.BatchProcessor startBatch =
            new TlsHandshakeAsyncOffload.BatchProcessor() {
                @Override
                public void process() {
                    runner.runStart();
                }
            };
    private final TlsHandshakeAsyncOffload.BatchProcessor messageBatch =
            new TlsHandshakeAsyncOffload.BatchProcessor() {
                @Override
                public void process() {
                    runner.runMessages(activeBatch);
                }
            };
    private final TlsHandshakeAsyncOffload.CompletionHandler drainHandler =
            new TlsHandshakeAsyncOffload.CompletionHandler() {
                @Override
                public boolean onBatchDone() {
                    if (pendingStart) {
                        pendingStart = false;
                        submitStart();
                        return true;
                    }
                    if (!pendingMessages.isEmpty()) {
                        drainBatch.clear();
                        byte[] next;
                        while ((next = pendingMessages.poll()) != null) {
                            drainBatch.add(next);
                        }
                        submitMessages(drainBatch);
                        return true;
                    }
                    return false;
                }
            };

    private boolean pendingStart;
    private List<byte[]> activeBatch;

    HandshakeAsyncScheduler(Executor loopExecutor, Runner runner,
            TlsHandshakeAsyncOffload.FailureHandler onFailure) {
        this.runner = runner;
        this.onFailure = onFailure;
        this.offload = loopExecutor != null ? new TlsHandshakeAsyncOffload(loopExecutor) : null;
    }

    boolean isEnabled() {
        return offload != null;
    }

    boolean isDeferring() {
        return offload != null && offload.isDeferring();
    }

    Object lock() {
        return offload != null ? offload.lock() : this;
    }

    boolean isBusy() {
        return offload != null && offload.isBusy();
    }

    void dispatch(Runnable call) {
        if (offload != null) {
            offload.dispatch(call);
        } else {
            call.run();
        }
    }

    void scheduleStart() {
        if (offload == null) {
            runner.runStart();
            return;
        }
        synchronized (offload.lock()) {
            if (offload.isBusy()) {
                pendingStart = true;
                return;
            }
            submitStart();
        }
    }

    void scheduleMessage(byte[] message) {
        singleMessageBatch.clear();
        singleMessageBatch.add(message);
        scheduleMessages(singleMessageBatch);
    }

    void scheduleMessages(List<byte[]> messages) {
        if (offload == null) {
            runner.runMessages(messages);
            return;
        }
        synchronized (offload.lock()) {
            if (offload.isBusy()) {
                for (int i = 0; i < messages.size(); i++) {
                    pendingMessages.addLast(messages.get(i));
                }
                return;
            }
            submitMessages(messages);
        }
    }

    private void submitStart() {
        offload.submit(startBatch, drainHandler, onFailure);
    }

    private void submitMessages(List<byte[]> messages) {
        activeBatch = messages;
        offload.submit(messageBatch, drainHandler, onFailure);
    }
}
