/*
 * HandshakeAsyncScheduler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Queues handshake starts and messages while a {@link HandshakeAsyncOffload}
 * batch is in flight, then drains the queue from the completion handler.
 */
public final class HandshakeAsyncScheduler {

    /**
     * Handshake steps invoked on a crypto thread by {@link HandshakeAsyncOffload}.
     */
    public interface Runner {
        void runStart();

        void runMessages(List<byte[]> messages);
    }

    private final HandshakeAsyncOffload offload;
    private final Runner runner;
    private final HandshakeAsyncOffload.FailureHandler onFailure;
    private final ArrayDeque<byte[]> pendingMessages = new ArrayDeque<byte[]>();
    private final ArrayList<byte[]> singleMessageBatch = new ArrayList<byte[]>(1);
    private final ArrayList<byte[]> drainBatch = new ArrayList<byte[]>();
    private final HandshakeAsyncOffload.BatchProcessor startBatch =
            new HandshakeAsyncOffload.BatchProcessor() {
                @Override
                public void process() {
                    runner.runStart();
                }
            };
    private final HandshakeAsyncOffload.BatchProcessor messageBatch =
            new HandshakeAsyncOffload.BatchProcessor() {
                @Override
                public void process() {
                    runner.runMessages(activeBatch);
                }
            };
    private final HandshakeAsyncOffload.CompletionHandler drainHandler =
            new HandshakeAsyncOffload.CompletionHandler() {
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

    public HandshakeAsyncScheduler(HandshakeAsyncOffload offload, Runner runner,
            HandshakeAsyncOffload.FailureHandler onFailure) {
        this.offload = offload;
        this.runner = runner;
        this.onFailure = onFailure;
    }

    public boolean isEnabled() {
        return offload != null;
    }

    public boolean isDeferring() {
        return offload != null && offload.isDeferring();
    }

    public Object lock() {
        return offload != null ? offload.lock() : this;
    }

    public boolean isBusy() {
        return offload != null && offload.isBusy();
    }

    public void dispatch(Runnable call) {
        if (offload != null) {
            offload.dispatch(call);
        } else {
            call.run();
        }
    }

    public void scheduleStart() {
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

    public void scheduleMessage(byte[] message) {
        singleMessageBatch.clear();
        singleMessageBatch.add(message);
        scheduleMessages(singleMessageBatch);
    }

    public void scheduleMessages(List<byte[]> messages) {
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
