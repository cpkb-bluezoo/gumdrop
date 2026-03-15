/*
 * MQTTWebSocketHandler.java
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

package org.bluezoo.gumdrop.mqtt;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketSession;
import org.bluezoo.gumdrop.mqtt.broker.SubscriptionManager;
import org.bluezoo.gumdrop.mqtt.broker.WillManager;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageStore;

/**
 * Bridges MQTT over WebSocket (RFC 6455 subprotocol "mqtt").
 *
 * <p>Implements {@link WebSocketEventHandler} to receive WebSocket
 * binary frames and feeds them into an {@link MQTTProtocolHandler}.
 * Outbound MQTT packets are sent as WebSocket binary messages via a
 * {@link WebSocketEndpointAdapter} that presents the {@link Endpoint}
 * interface to the protocol handler.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MQTTWebSocketHandler implements WebSocketEventHandler {

    private static final Logger LOGGER =
            Logger.getLogger(MQTTWebSocketHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mqtt.L10N");

    private final MQTTListener listener;
    private final SubscriptionManager subscriptionManager;
    private final WillManager willManager;
    private final MQTTMessageStore messageStore;

    private MQTTProtocolHandler protocolHandler;
    private WebSocketEndpointAdapter endpointAdapter;

    public MQTTWebSocketHandler(MQTTListener listener,
                                SubscriptionManager subscriptionManager,
                                WillManager willManager,
                                MQTTMessageStore messageStore) {
        this.listener = listener;
        this.subscriptionManager = subscriptionManager;
        this.willManager = willManager;
        this.messageStore = messageStore;
    }

    @Override
    public void opened(WebSocketSession session) {
        endpointAdapter = new WebSocketEndpointAdapter(session);
        TelemetryConfig telemetryConfig = listener.getTelemetryConfig();
        if (telemetryConfig != null) {
            endpointAdapter.setTelemetryConfig(telemetryConfig);
        }
        protocolHandler = new MQTTProtocolHandler(
                listener, subscriptionManager, willManager, messageStore);
        protocolHandler.connected(endpointAdapter);
    }

    @Override
    public void textMessageReceived(WebSocketSession session, String message) {
        // MQTT-over-WebSocket uses binary frames per the spec
        LOGGER.warning(L10N.getString("log.ws_unexpected_text"));
    }

    @Override
    public void binaryMessageReceived(WebSocketSession session, ByteBuffer data) {
        if (protocolHandler != null) {
            protocolHandler.receive(data);
        }
    }

    @Override
    public void closed(int code, String reason) {
        if (protocolHandler != null) {
            protocolHandler.disconnected();
        }
    }

    @Override
    public void error(Throwable cause) {
        LOGGER.log(Level.WARNING, L10N.getString("log.ws_error"), cause);
        if (protocolHandler != null) {
            protocolHandler.error(cause instanceof Exception
                    ? (Exception) cause
                    : new Exception(cause));
        }
    }

    /**
     * Adapts a {@link WebSocketSession} to the {@link Endpoint} interface
     * so that {@link MQTTProtocolHandler} can send data without knowing
     * the underlying transport.
     */
    static class WebSocketEndpointAdapter implements Endpoint {

        private final WebSocketSession session;
        private volatile boolean open = true;
        private TelemetryConfig telemetryConfig;
        private Trace trace;

        WebSocketEndpointAdapter(WebSocketSession session) {
            this.session = session;
        }

        void setTelemetryConfig(TelemetryConfig config) {
            this.telemetryConfig = config;
        }

        @Override
        public void send(ByteBuffer data) {
            try {
                session.sendBinary(data);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("log.ws_send_error"), e);
                open = false;
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean isClosing() {
            return !open;
        }

        @Override
        public void close() {
            open = false;
            try {
                session.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, L10N.getString("log.ws_close_error"), e);
            }
        }

        @Override
        public SocketAddress getLocalAddress() {
            return null; // Not available through WebSocket API
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return null; // Not available through WebSocket API
        }

        @Override
        public boolean isSecure() {
            return false; // TLS is handled at the HTTP layer
        }

        @Override
        public SecurityInfo getSecurityInfo() {
            return null;
        }

        @Override
        public void startTLS() {
            throw new UnsupportedOperationException(
                    "TLS is managed at the HTTP/WebSocket layer");
        }

        @Override
        public void pauseRead() {
            // Flow control not available through WebSocket
        }

        @Override
        public void resumeRead() {
            // Flow control not available through WebSocket
        }

        @Override
        public void onWriteReady(Runnable callback) {
            // Immediately invoke — WebSocket sendBinary is synchronous
            if (callback != null) {
                callback.run();
            }
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            // Use a simple thread-based timer as fallback
            Thread timer = new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                    if (open) {
                        callback.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            timer.setDaemon(true);
            timer.start();
            return new TimerHandle() {
                @Override
                public void cancel() {
                    timer.interrupt();
                }

                @Override
                public boolean isCancelled() {
                    return timer.isInterrupted();
                }
            };
        }

        @Override
        public Trace getTrace() {
            return trace;
        }

        @Override
        public void setTrace(Trace trace) {
            this.trace = trace;
        }

        @Override
        public boolean isTelemetryEnabled() {
            return telemetryConfig != null;
        }

        @Override
        public TelemetryConfig getTelemetryConfig() {
            return telemetryConfig;
        }
    }
}
