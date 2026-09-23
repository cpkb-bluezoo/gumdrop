/*
 * HandshakeAsyncScheduler.java
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

package org.bluezoo.gumdrop.tls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Queues handshake starts and messages while a {@link HandshakeAsyncOffload}
 * batch is in flight, then drains the queue from the completion handler.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HandshakeAsyncScheduler {

    /**
     * Handshake steps invoked on a crypto thread by {@link HandshakeAsyncOffload}.
     */
    public interface Runner {
        void runStart();

        void runInputs(List<HandshakeInput> inputs);
    }

    private final HandshakeAsyncOffload offload;
    private final Runner runner;
    private final HandshakeAsyncOffload.FailureHandler onFailure;
    private final ArrayDeque<HandshakeInput> pendingMessages = new ArrayDeque<HandshakeInput>();
    private final ArrayList<HandshakeInput> drainBatch = new ArrayList<HandshakeInput>();
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
                    runner.runInputs(activeBatch);
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
                        HandshakeInput next;
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
    private List<HandshakeInput> activeBatch;
    private Runnable onIdle;

    public HandshakeAsyncScheduler(HandshakeAsyncOffload offload, Runner runner,
            HandshakeAsyncOffload.FailureHandler onFailure) {
        this.offload = offload;
        this.runner = runner;
        this.onFailure = onFailure;
    }

    /**
     * Invoked on the loop thread once all in-flight and queued handshake
     * batches have finished. Used by record engines to resume parsing bytes
     * left in their inbound buffer after pausing for async handshake work.
     */
    public void setOnIdle(Runnable onIdle) {
        this.onIdle = onIdle;
    }

    void notifyIdle() {
        if (onIdle != null && !isBusy()) {
            onIdle.run();
        }
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
        List<HandshakeInput> batch = new ArrayList<HandshakeInput>(1);
        batch.add(HandshakeInput.message(message));
        scheduleInputs(batch);
    }

    public void scheduleMessages(List<byte[]> messages) {
        List<HandshakeInput> inputs = new ArrayList<HandshakeInput>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            inputs.add(HandshakeInput.message(messages.get(i)));
        }
        scheduleInputs(inputs);
    }

    /**
     * Schedules all inputs derived from one record together, so that an
     * idle notification after an earlier input cannot resume inbound
     * parsing ahead of the later ones.
     */
    void scheduleInputs(List<HandshakeInput> inputs) {
        if (offload == null) {
            runner.runInputs(inputs);
            return;
        }
        synchronized (offload.lock()) {
            if (offload.isBusy()) {
                for (int i = 0; i < inputs.size(); i++) {
                    pendingMessages.addLast(inputs.get(i));
                }
                return;
            }
            submitMessages(inputs);
        }
    }

    private void submitStart() {
        offload.submit(startBatch, drainHandler, onFailure);
    }

    private void submitMessages(List<HandshakeInput> messages) {
        activeBatch = messages;
        offload.submit(messageBatch, drainHandler, onFailure);
    }
}
