/*
 * AmqpClient.java
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

package org.bluezoo.gumdrop.amqp.client;

import org.bluezoo.gumdrop.amqp.FieldTable;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;
import javax.security.auth.Subject;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.ScheduledTimer;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.amqp.client.ClientConnection;
import org.bluezoo.gumdrop.amqp.client.ClientHandshake;
import org.bluezoo.gumdrop.amqp.client.ClientTuned;
import org.bluezoo.gumdrop.amqp.client.ConnectionReady;
import org.bluezoo.gumdrop.amqp.client.RecoveryHandler;
import org.bluezoo.gumdrop.amqp.client.RecoveryListener;
import org.bluezoo.gumdrop.amqp.client.OpenHandler;
import org.bluezoo.gumdrop.amqp.client.TuneHandler;
import org.bluezoo.gumdrop.auth.SaslClientMechanism;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * AMQP client facade with automatic reconnect and topology recovery.
 *
 * <p>Mirrors {@link org.bluezoo.gumdrop.smtp.client.SmtpClient} for
 * connection setup (host/port/TLS), but where {@code SmtpClient} hands
 * the caller a raw, single-connection protocol handler, this hides the
 * entire low-level handshake (protocol header, {@code connection.start}/
 * {@code start-ok}, {@code tune}/{@code tune-ok}, {@code open}/
 * {@code open-ok}) behind stored credentials/vhost, and transparently
 * reconnects on failure:
 *
 * <pre>{@code
 * AmqpClient client = new AmqpClient("broker.example.com", 5672)
 *         .credentials("guest", "guest")
 *         .virtualHost("/")
 *         .recoveryListener(myListener); // optional
 *
 * client.connect(new RecoveryHandler() {
 *     public void onFirstConnect(ClientConnection connection) {
 *         connection.channelOpen(1, new ChannelOpenHandler() {
 *             public void handleChannelOpenOk(ClientChannel channel) {
 *                 channel.queueDeclare("my-queue", true, false, false, null,
 *                         new QueueDeclareHandler() {
 *                             public void handleQueueDeclareOk(
 *                                     String queue, long msgCount, long consumerCount) { }
 *                         });
 *                 channel.basicConsume("my-queue", "", false, false, null,
 *                         myDeliveryHandler, new ConsumeHandler() {
 *                             public void handleConsumeOk(String consumerTag) { }
 *                         });
 *             }
 *         });
 *     }
 * });
 * }</pre>
 *
 * <p>{@code onFirstConnect} runs once. If the connection later drops,
 * this class waits (per {@link RecoveryPolicy}), reconnects, redeclares
 * every exchange/queue/binding and re-registers every consumer in the
 * order they were first issued, then the same {@link
 * org.bluezoo.gumdrop.amqp.client.ClientChannel} instances the
 * application is already holding become live again — no further action
 * needed from the application.
 *
 * <p>Reconnect delays are scheduled on a small dedicated daemon thread
 * (see {@code RetryTimerHolder}'s javadoc), deliberately <em>not</em>
 * gumdrop's own {@link SelectorLoop} timer infrastructure that the rest
 * of this codebase prefers: {@link Gumdrop} auto-shuts-down every worker
 * loop (and any timers on them) once it has no active clients, services,
 * or listeners — which is exactly the state a reconnecting client is in
 * for the whole span of its own backoff window. Once the delay elapses,
 * the actual reconnect attempt goes through the normal, gumdrop-managed
 * {@link ClientEndpoint#connect} path like any other connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RecoveryHandler
 * @see RecoveryListener
 * @see RecoveryPolicy
 */
public class AmqpClient {

    private static final Logger LOGGER = Logger.getLogger(AmqpClient.class.getName());

    private EventLogger events() {
        return (selectorLoop != null ? selectorLoop.getTelemetryConfig() : new TelemetryConfig()).getLogger(AmqpClient.class, L10N);
    }
    private static final ResourceBundle L10N = AmqpClientProtocolHandler.L10N;

    /**
     * Deliberately <strong>not</strong> gumdrop's own timer/{@link
     * SelectorLoop} infrastructure, despite that being the norm
     * elsewhere in this codebase. {@link Gumdrop#checkAutoShutdown} tears
     * down every worker loop (and any timers scheduled on them) the
     * moment {@code activeClients} is empty and there are no services or
     * listeners — and a reconnecting client has, by definition, zero
     * active connections for the whole span of its own backoff window.
     * Anchoring the retry timer to a gumdrop worker loop was tried and
     * empirically fails exactly because of this: the loop (and the
     * pending timer on it) gets shut down out from under the retry
     * before it fires, for any application that's a bare client with
     * nothing else keeping gumdrop alive. A small dedicated daemon
     * thread, independent of gumdrop's own lifecycle, is the correct
     * fix here, not an oversight — the actual reconnect attempt once the
     * delay elapses still goes through the normal, gumdrop-managed
     * {@link ClientEndpoint#connect} path like any other connection.
     */
    private static final class RetryTimerHolder {
        // Started lazily, on the first real retry, rather than at class load.
        static final ScheduledTimer TIMER = new ScheduledTimer("gumdrop-amqp-recovery");

        static {
            TIMER.start();
        }
    }

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final String socketPath;
    private final SelectorLoop selectorLoop;

    private String username = "guest";
    private String password = "guest";
    private String virtualHost = "/";
    private RecoveryPolicy policy = new RecoveryPolicy();
    private RecoveryListener listener;

    private final TlsConfig tls = new TlsConfig();
    private boolean secure;

    /** SASL mechanism to authenticate with; defaults to PLAIN (issue #188). */
    private String mechanism = "PLAIN";
    private Subject gssapiSubject;
    private String gssapiServicePrincipal;
    private ExecutorService gssapiExecutor;

    private Gumdrop gumdrop;
    private RecoveryHandler appHandler;
    private RecoverableConnectionImpl recoverableConnection;
    private int attempt;
    private volatile boolean closed;
    private volatile AmqpClientProtocolHandler currentHandler;
    private volatile ClientEndpoint currentEndpoint;
    private volatile TimerHandle pendingRetry;

    public AmqpClient(String host, int port) {
        this(null, host, port);
    }

    public AmqpClient(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.hostAddress = null;
        this.port = port;
        this.socketPath = null;
    }

    public AmqpClient(InetAddress host, int port) {
        this(null, host, port);
    }

    public AmqpClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = host;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates an AMQP client for a broker reached over a UNIX domain
     * socket, mirroring {@link org.bluezoo.gumdrop.TcpListener#path(java.nio.file.Path)}
     * on the server side.
     *
     * @param socketPath the broker's UNIX domain socket path
     */
    public AmqpClient(String socketPath) {
        this(null, socketPath);
    }

    /**
     * Creates an AMQP client for a broker reached over a UNIX domain
     * socket, with an explicit selector loop.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param socketPath the broker's UNIX domain socket path
     */
    public AmqpClient(SelectorLoop selectorLoop, String socketPath) {
        if (socketPath == null) {
            throw new NullPointerException("socketPath");
        }
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = null;
        this.port = -1;
        this.socketPath = socketPath;
    }

    // ── configuration (before connect) ──

    public AmqpClient credentials(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    public AmqpClient virtualHost(String virtualHost) {
        this.virtualHost = virtualHost;
        return this;
    }

    public AmqpClient recoveryPolicy(RecoveryPolicy policy) {
        this.policy = policy;
        return this;
    }

    public AmqpClient recoveryListener(RecoveryListener listener) {
        this.listener = listener;
        return this;
    }

    /**
     * Sets the SASL mechanism to authenticate with (issue #188): one of
     * {@code "PLAIN"} (the default), {@code "AMQPLAIN"}, {@code
     * "EXTERNAL"}, or {@code "GSSAPI"}.
     *
     * <p>{@code PLAIN} and {@code AMQPLAIN} use the credentials set via
     * {@link #credentials}. {@code EXTERNAL} relies on the client
     * certificate presented during TLS handshake (requires {@link
     * #secure} plus a configured keystore) and ignores credentials.
     * {@code GSSAPI} requires {@link #gssapiCredentials} to also be
     * called.
     *
     * @param mechanism the SASL mechanism name
     */
    public AmqpClient mechanism(String mechanism) {
        this.mechanism = mechanism;
        return this;
    }

    /**
     * Configures {@code GSSAPI} (Kerberos) authentication (issue #188).
     * Required when {@link #mechanism} is set to {@code "GSSAPI"}.
     *
     * @param subject the JAAS Subject with Kerberos credentials (from
     *        keytab login or {@code kinit})
     * @param servicePrincipal the broker's service principal name (e.g.
     *        {@code "amqp@broker.example.com"})
     * @param executor worker executor for the potentially blocking KDC
     *        contact made by the first challenge evaluation; never called
     *        on the connection's own event-loop thread
     */
    public AmqpClient gssapiCredentials(Subject subject, String servicePrincipal,
            ExecutorService executor) {
        this.gssapiSubject = subject;
        this.gssapiServicePrincipal = servicePrincipal;
        this.gssapiExecutor = executor;
        return this;
    }

    /** Implicit TLS (AMQPS, typically port 5671). */
    public AmqpClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * Sets this client's TLS settings (certificates, trust, ECH and so on). The
     * settings are copied, so later changes to {@code source} are not seen.
     * Whether TLS is used at all is decided by {@link #secure(boolean)}.
     *
     * @param source the TLS configuration
     * @return this client
     */
    public AmqpClient tls(TlsConfig source) {
        tls.copyFrom(source);
        return this;
    }

    // ── lifecycle ──

    /**
     * Connects, calling {@code handler.onFirstConnect} once the
     * connection and handshake succeed. If the connection is lost at
     * any point afterwards (including during the initial connect),
     * reconnects automatically per the configured {@link RecoveryPolicy}
     * and replays recorded topology, without calling {@code handler}
     * again.
     */
    public void connect(Gumdrop gumdrop, RecoveryHandler handler) {
        this.gumdrop = gumdrop;
        this.appHandler = handler;
        this.recoverableConnection = new RecoverableConnectionImpl();
        this.attempt = 0;
        doConnect(true);
    }

    /**
     * Creates the transport factory for one connection attempt. Package-private
     * so tests can substitute a factory whose connect fails deterministically.
     */
    TcpTransportFactory newTransportFactory() {
        return new TcpTransportFactory();
    }

    private void doConnect(final boolean first) {
        if (shouldStopRecovery()) {
            return;
        }
        TcpTransportFactory transportFactory = newTransportFactory();
        ClientConnect.prepareTls(secure, tls, transportFactory);

        AmqpClientProtocolHandler handler = new AmqpClientProtocolHandler(new RecoveryConnectionReady(first));
        currentHandler = handler;

        try {
            ClientEndpoint endpoint;
            if (socketPath != null) {
                endpoint = (selectorLoop != null)
                        ? new ClientEndpoint(transportFactory, selectorLoop, socketPath)
                        : new ClientEndpoint(transportFactory, socketPath);
            } else if (host != null) {
                endpoint = (selectorLoop != null)
                        ? new ClientEndpoint(transportFactory, selectorLoop, host, port)
                        : new ClientEndpoint(transportFactory, host, port);
            } else {
                endpoint = (selectorLoop != null)
                        ? new ClientEndpoint(transportFactory, selectorLoop, hostAddress, port)
                        : new ClientEndpoint(transportFactory, hostAddress, port);
            }
            currentEndpoint = endpoint;
            Connector connector = testConnector;
            if (connector != null) {
                connector.connect(handler);
            } else {
                endpoint.connect(gumdrop, handler);
            }
        } catch (IOException e) {
            scheduleReconnect(e);
        }
    }

    // ── test seams (package-private; production never sets them) ──

    /** Replaces the endpoint's connect (the endpoint itself is still created). */
    interface Connector {
        void connect(ProtocolHandler handler) throws IOException;
    }

    /** Replaces the retry timer. */
    interface RetryScheduler {
        TimerHandle schedule(long delayMs, Runnable task);
    }

    private Connector testConnector;
    private RetryScheduler testScheduler;

    void useConnectorForTesting(Connector connector) {
        this.testConnector = connector;
    }

    void useRetrySchedulerForTesting(RetryScheduler scheduler) {
        this.testScheduler = scheduler;
    }

    private TimerHandle scheduleRetry(long delayMs, Runnable task) {
        RetryScheduler substitute = testScheduler;
        if (substitute != null) {
            return substitute.schedule(delayMs, task);
        }
        return RetryTimerHolder.TIMER.schedule(null, delayMs, task);
    }

    private void scheduleReconnect(Exception cause) {
        if (shouldStopRecovery()) {
            return;
        }
        recoverableConnection.markDisconnected();
        if (listener != null) {
            listener.onConnectionLost(cause);
        }
        attempt++;
        int maxAttempts = policy.getMaxAttempts();
        if (maxAttempts > 0 && attempt > maxAttempts) {
            logRecoveryAbandoned(cause);
            if (listener != null) {
                listener.onRecoveryFailed(cause);
            }
            return;
        }
        logRetryableConnectionLoss(cause);
        long delay = policy.delayFor(attempt);
        events().info("info.reconnecting").attr("delay_ms", delay).attr("attempt", attempt).emit();
        if (listener != null) {
            listener.onReconnecting(attempt, delay);
        }

        pendingRetry = scheduleRetry(delay, new Runnable() {
            @Override
            public void run() {
                doConnect(false);
            }
        });
    }

    private boolean shouldStopRecovery() {
        return closed || (gumdrop != null && gumdrop.isDraining());
    }

    private void logRetryableConnectionLoss(Exception cause) {
        if (isCleanLocalClose(cause)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, L10N.getString("warn.connection_lost"), cause);
            }
            return;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, L10N.getString("warn.connection_lost"), cause);
            return;
        }
        if (LOGGER.isLoggable(Level.INFO)) {
            String detail = cause != null ? cause.getMessage() : null;
            if (detail != null && !detail.isEmpty()) {
                LOGGER.log(Level.INFO, L10N.getString("warn.connection_lost") + ": " + detail);
            } else {
                events().info("warn.connection_lost").emit();
            }
        }
    }

    private void logRecoveryAbandoned(Exception cause) {
        String summary = MessageFormat.format(L10N.getString("err.recovery_failed"), attempt);
        if (isPermanentConfigurationFailure(cause)) {
            String detail = cause != null ? cause.getMessage() : null;
            if (detail != null && !detail.isEmpty()) {
                LOGGER.log(Level.WARNING, summary + ": " + detail);
            } else {
                LOGGER.log(Level.WARNING, summary);
            }
            if (LOGGER.isLoggable(Level.FINE) && cause != null) {
                LOGGER.log(Level.FINE, summary, cause);
            }
            return;
        }
        LOGGER.log(Level.SEVERE, summary, cause);
    }

    private static boolean isPermanentConfigurationFailure(Exception cause) {
        String msg = cause != null ? cause.getMessage() : null;
        return msg != null && msg.startsWith("Broker does not offer the requested SASL mechanism");
    }

    /** TCP EOF from {@code onDisconnected} during an expected close or drop. */
    private static boolean isCleanLocalClose(Exception cause) {
        if (!(cause instanceof IOException)) {
            return false;
        }
        return "Connection closed".equals(cause.getMessage());
    }

    /** Closes the connection and stops reconnecting. */
    public void close() {
        closed = true;
        TimerHandle retry = pendingRetry;
        if (retry != null) {
            retry.cancel();
        }
        ClientEndpoint endpoint = currentEndpoint;
        if (endpoint != null) {
            endpoint.close();
        }
    }

    /** ConnectionReady implementation driving the full handshake automatically. */
    private final class RecoveryConnectionReady implements ConnectionReady {
        private final boolean first;

        // A broker-initiated close delivers both an AMQP-level
        // Connection.Close (-> onConnectionClosed) and, moments later, the
        // underlying TCP EOF for the same now-closing socket (->
        // onDisconnected) -- occasionally close enough together that both
        // fire before the first has scheduled its reconnect. Without this
        // guard each one independently calls scheduleReconnect(), racing
        // two reconnect attempts against each other over the shared
        // currentEndpoint/attempt/pendingRetry state and reliably breaking
        // recovery (issue #203). This instance is created fresh per
        // connection attempt (see doConnect()), so a plain instance field
        // is sufficient to recognise "already handled" scoped to exactly
        // the one connection these notifications are both about.
        private boolean disconnectHandled;

        RecoveryConnectionReady(boolean first) {
            this.first = first;
        }

        /**
         * Schedules a reconnect for this connection's loss, unless one was
         * already scheduled by an earlier disconnect notification for the
         * same connection attempt (see the disconnectHandled field
         * comment).
         */
        private void scheduleReconnectOnce(Exception cause) {
            synchronized (this) {
                if (disconnectHandled) {
                    return;
                }
                disconnectHandled = true;
            }
            scheduleReconnect(cause);
        }

        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void handleStart(FieldTable serverProperties, String mechanisms, String locales,
                ClientHandshake handshake) {
            if (!"PLAIN".equalsIgnoreCase(mechanism) && !isMechanismOffered(mechanisms, mechanism)) {
                scheduleReconnectOnce(new IOException(MessageFormat.format(
                        L10N.getString("err.sasl_mechanism_not_offered"), mechanism, mechanisms)));
                return;
            }

            TuneHandler tuneHandler = new TuneHandler() {
                @Override
                public void handleTune(int channelMax, long frameMax, int heartbeat,
                        ClientTuned tuned) {
                    tuned.open(virtualHost, new OpenHandler() {
                        @Override
                        public void handleOpenOk(ClientConnection connection) {
                            attempt = 0;
                            recoverableConnection.bind(connection);
                            if (first) {
                                appHandler.onFirstConnect(recoverableConnection);
                            } else {
                                recoverableConnection.reopenAndReplayAll(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (listener != null) {
                                            listener.onRecovered();
                                        }
                                    }
                                });
                            }
                        }
                    });
                }
            };

            if ("AMQPLAIN".equalsIgnoreCase(mechanism)) {
                handshake.startOk(new AmqpPlainClientMechanism(username, password), tuneHandler);
            } else if ("EXTERNAL".equalsIgnoreCase(mechanism)) {
                handshake.startOk(SaslUtils.createClient("EXTERNAL", username, password, host), tuneHandler);
            } else if ("GSSAPI".equalsIgnoreCase(mechanism)) {
                String principal = (gssapiServicePrincipal != null) ? gssapiServicePrincipal : host;
                SaslClientMechanism client = SaslUtils.createClient(
                        "GSSAPI", username, password, principal, gssapiSubject);
                if (client == null) {
                    scheduleReconnectOnce(new IOException(
                            "GSSAPI mechanism requires gssapiCredentials(subject, servicePrincipal, executor)"));
                    return;
                }
                handshake.startOk(client, tuneHandler, gssapiExecutor);
            } else {
                handshake.startOk(username, password, tuneHandler);
            }
        }

        /** RFC 4422 §3.1 — {@code mechanisms} is a space-separated list. */
        private boolean isMechanismOffered(String mechanisms, String wanted) {
            if (mechanisms == null || wanted == null) {
                return false;
            }
            for (String offered : mechanisms.split(" ")) {
                if (offered.equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onConnectionClosed(int replyCode, String replyText) {
            scheduleReconnectOnce(new IOException(
                    "Connection closed by broker: " + replyCode + " " + replyText));
        }

        @Override
        public void onDisconnected() {
            scheduleReconnectOnce(new IOException("Connection closed"));
        }

        @Override
        public void onError(Exception cause) {
            scheduleReconnectOnce(cause);
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }
    }
}
