/*
 * HandshakeAsyncOffload.java
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

/**
 * Offloads handshake CPU work off a connection's I/O thread while deferring
 * {@link TlsEventSink} callbacks until the batch completes on the loop thread.
 *
 * <p>Implementations such as {@link org.bluezoo.gumdrop.TlsHandshakeAsyncOffload}
 * live outside this package because they depend on {@code Gumdrop} and
 * {@code CryptoExecutor}, which compile after the early {@code tls} unit.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
