/*
 * QuicHandshakeAsyncOffload.java
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

package org.bluezoo.gumdrop.quic.tls;

import java.util.concurrent.Executor;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TlsHandshakeAsyncOffload;

/**
 * QUIC-facing wrapper around {@link TlsHandshakeAsyncOffload}: same offload
 * model as TCP ({@link org.bluezoo.gumdrop.tls.TlsRecordEngine}) and DTLS
 * ({@link org.bluezoo.gumdrop.tls.Dtls13RecordEngine}), with QUIC-specific
 * failure reporting via {@link EncryptionLevel}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicTlsServerEngine
 * @see QuicTlsClientEngine
 */
final class QuicHandshakeAsyncOffload {

    private static final Logger LOGGER = Logger.getLogger(QuicHandshakeAsyncOffload.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.quic.L10N");

    interface BatchProcessor {
        void process();
    }

    interface CompletionHandler {
        boolean onBatchDone();
    }

    private final QuicTlsEngineListener listener;
    private final TlsHandshakeAsyncOffload delegate;

    QuicHandshakeAsyncOffload(QuicTlsEngineListener listener) {
        this.listener = listener;
        SelectorLoop loop = listener.getSelectorLoop();
        Gumdrop gumdrop = (loop != null) ? loop.getGumdrop() : null;
        this.delegate = new TlsHandshakeAsyncOffload(new Executor() {
            @Override
            public void execute(Runnable command) {
                listener.execute(command);
            }
        }, gumdrop);
    }

    Object lock() {
        return delegate.lock();
    }

    boolean isBusy() {
        return delegate.isBusy();
    }

    boolean isDeferring() {
        return delegate.isDeferring();
    }

    void dispatch(Runnable call) {
        delegate.dispatch(call);
    }

    void submit(final EncryptionLevel level, final BatchProcessor processor, final CompletionHandler onDone) {
        delegate.submit(new TlsHandshakeAsyncOffload.BatchProcessor() {
            @Override
            public void process() {
                processor.process();
            }
        }, new TlsHandshakeAsyncOffload.CompletionHandler() {
            @Override
            public boolean onBatchDone() {
                return onDone.onBatchDone();
            }
        }, new TlsHandshakeAsyncOffload.FailureHandler() {
            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.SEVERE, L10N.getString("severe.handshake_async_failed"), error);
                listener.cryptoProcessingFailed(level, error);
            }
        });
    }
}
