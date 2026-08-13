/*
 * AMQPClientRecovery.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.Subject;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.amqp.client.handler.ClientConnection;
import org.bluezoo.gumdrop.amqp.client.handler.ClientHandshake;
import org.bluezoo.gumdrop.amqp.client.handler.ClientTuned;
import org.bluezoo.gumdrop.amqp.client.handler.ConnectionReady;
import org.bluezoo.gumdrop.amqp.client.handler.RecoveryHandler;
import org.bluezoo.gumdrop.amqp.client.handler.RecoveryListener;
import org.bluezoo.gumdrop.amqp.client.handler.ServerOpenHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTuneHandler;
import org.bluezoo.gumdrop.auth.SASLClientMechanism;
import org.bluezoo.gumdrop.auth.SASLUtils;

/**
 * AMQP client facade with automatic reconnect and topology recovery.
 *
 * <p>Mirrors {@link org.bluezoo.gumdrop.smtp.client.SMTPClient} for
 * connection setup (host/port/TLS), but where {@code SMTPClient} hands
 * the caller a raw, single-connection protocol handler, this hides the
 * entire low-level handshake (protocol header, {@code connection.start}/
 * {@code start-ok}, {@code tune}/{@code tune-ok}, {@code open}/
 * {@code open-ok}) behind stored credentials/vhost, and transparently
 * reconnects on failure:
 *
 * <pre>{@code
 * AMQPClientRecovery client = new AMQPClientRecovery("broker.example.com", 5672)
 *         .credentials("guest", "guest")
 *         .virtualHost("/")
 *         .recoveryListener(myListener); // optional
 *
 * client.connect(new RecoveryHandler() {
 *     public void onFirstConnect(ClientConnection connection) {
 *         connection.channelOpen(1, new ServerChannelOpenHandler() {
 *             public void handleChannelOpenOk(ClientChannel channel) {
 *                 channel.queueDeclare("my-queue", true, false, false, null,
 *                         new ServerQueueDeclareHandler() {
 *                             public void handleQueueDeclareOk(
 *                                     String queue, long msgCount, long consumerCount) { }
 *                         });
 *                 channel.basicConsume("my-queue", "", false, false, null,
 *                         myDeliveryHandler, new ServerConsumeHandler() {
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
 * order they were first issued, then the same {@link ClientChannel}
 * instances the application is already holding become live again — no
 * further action needed from the application.
 *
 * <p>Reconnect delays are scheduled on a small dedicated daemon thread
 * (see {@code RETRY_EXECUTOR}'s javadoc), deliberately <em>not</em>
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
public class AMQPClientRecovery {

    private static final Logger LOGGER = Logger.getLogger(AMQPClientRecovery.class.getName());
    private static final ResourceBundle L10N = AMQPClientProtocolHandler.L10N;

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
    private static final ScheduledExecutorService RETRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new RecoveryThreadFactory());

    private static final class RecoveryThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "gumdrop-amqp-recovery");
            t.setDaemon(true);
            return t;
        }
    }

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final SelectorLoop selectorLoop;

    private String username = "guest";
    private String password = "guest";
    private String virtualHost = "/";
    private RecoveryPolicy policy = new RecoveryPolicy();
    private RecoveryListener listener;

    private boolean secure;
    private SSLContext sslContext;
    private X509TrustManager trustManager;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat;

    /** SASL mechanism to authenticate with; defaults to PLAIN (issue #188). */
    private String mechanism = "PLAIN";
    private Subject gssapiSubject;
    private String gssapiServicePrincipal;
    private ExecutorService gssapiExecutor;

    private RecoveryHandler appHandler;
    private RecoverableConnectionImpl recoverableConnection;
    private int attempt;
    private volatile boolean closed;
    private volatile AMQPClientProtocolHandler currentHandler;
    private volatile ClientEndpoint currentEndpoint;
    private volatile ScheduledFuture<?> pendingRetry;

    public AMQPClientRecovery(String host, int port) {
        this(null, host, port);
    }

    public AMQPClientRecovery(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.hostAddress = null;
        this.port = port;
    }

    public AMQPClientRecovery(InetAddress host, int port) {
        this(null, host, port);
    }

    public AMQPClientRecovery(SelectorLoop selectorLoop, InetAddress host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = host;
        this.port = port;
    }

    // ── configuration (before connect) ──

    public AMQPClientRecovery credentials(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    public AMQPClientRecovery virtualHost(String virtualHost) {
        this.virtualHost = virtualHost;
        return this;
    }

    public AMQPClientRecovery recoveryPolicy(RecoveryPolicy policy) {
        this.policy = policy;
        return this;
    }

    public AMQPClientRecovery recoveryListener(RecoveryListener listener) {
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
     * #setSecure} plus a configured keystore) and ignores credentials.
     * {@code GSSAPI} requires {@link #gssapiCredentials} to also be
     * called.
     *
     * @param mechanism the SASL mechanism name
     */
    public AMQPClientRecovery mechanism(String mechanism) {
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
    public AMQPClientRecovery gssapiCredentials(Subject subject, String servicePrincipal,
            ExecutorService executor) {
        this.gssapiSubject = subject;
        this.gssapiServicePrincipal = servicePrincipal;
        this.gssapiExecutor = executor;
        return this;
    }

    /** Implicit TLS (AMQPS, typically port 5671). */
    public AMQPClientRecovery setSecure(boolean secure) {
        this.secure = secure;
        return this;
    }

    public AMQPClientRecovery setSSLContext(SSLContext context) {
        this.sslContext = context;
        return this;
    }

    public AMQPClientRecovery setTrustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
        return this;
    }

    public AMQPClientRecovery setKeystoreFile(Path path) {
        this.keystoreFile = path;
        return this;
    }

    public AMQPClientRecovery setKeystorePass(String password) {
        this.keystorePass = password;
        return this;
    }

    public AMQPClientRecovery setKeystoreFormat(String format) {
        this.keystoreFormat = format;
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
    public void connect(RecoveryHandler handler) {
        this.appHandler = handler;
        this.recoverableConnection = new RecoverableConnectionImpl();
        this.attempt = 0;
        doConnect(true);
    }

    private void doConnect(final boolean first) {
        if (closed) {
            return;
        }
        TCPTransportFactory transportFactory = new TCPTransportFactory();
        transportFactory.setSecure(secure);
        if (sslContext != null) {
            transportFactory.setSSLContext(sslContext);
        }
        if (trustManager != null) {
            transportFactory.setTrustManager(trustManager);
        }
        if (keystoreFile != null) {
            transportFactory.setKeystoreFile(keystoreFile);
        }
        if (keystorePass != null) {
            transportFactory.setKeystorePass(keystorePass);
        }
        if (keystoreFormat != null) {
            transportFactory.setKeystoreFormat(keystoreFormat);
        }
        transportFactory.start();

        AMQPClientProtocolHandler handler = new AMQPClientProtocolHandler(new RecoveryConnectionReady(first));
        currentHandler = handler;

        try {
            ClientEndpoint endpoint;
            if (host != null) {
                endpoint = (selectorLoop != null)
                        ? new ClientEndpoint(transportFactory, selectorLoop, host, port)
                        : new ClientEndpoint(transportFactory, host, port);
            } else {
                endpoint = (selectorLoop != null)
                        ? new ClientEndpoint(transportFactory, selectorLoop, hostAddress, port)
                        : new ClientEndpoint(transportFactory, hostAddress, port);
            }
            currentEndpoint = endpoint;
            endpoint.connect(handler);
        } catch (IOException e) {
            scheduleReconnect(e);
        }
    }

    private void scheduleReconnect(Exception cause) {
        if (closed) {
            return;
        }
        recoverableConnection.markDisconnected();
        LOGGER.log(Level.WARNING, L10N.getString("warn.connection_lost"), cause);
        if (listener != null) {
            listener.onConnectionLost(cause);
        }
        attempt++;
        int maxAttempts = policy.getMaxAttempts();
        if (maxAttempts > 0 && attempt > maxAttempts) {
            LOGGER.log(Level.SEVERE,
                    MessageFormat.format(L10N.getString("err.recovery_failed"), attempt), cause);
            if (listener != null) {
                listener.onRecoveryFailed(cause);
            }
            return;
        }
        long delay = policy.delayFor(attempt);
        LOGGER.log(Level.INFO, L10N.getString("info.reconnecting"),
                new Object[] { delay, attempt });
        if (listener != null) {
            listener.onReconnecting(attempt, delay);
        }

        pendingRetry = RETRY_EXECUTOR.schedule(new Runnable() {
            @Override
            public void run() {
                doConnect(false);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** Closes the connection and stops reconnecting. */
    public void close() {
        closed = true;
        ScheduledFuture<?> retry = pendingRetry;
        if (retry != null) {
            retry.cancel(false);
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
                scheduleReconnect(new IOException(MessageFormat.format(
                        L10N.getString("err.sasl_mechanism_not_offered"), mechanism, mechanisms)));
                return;
            }

            ServerTuneHandler tuneHandler = new ServerTuneHandler() {
                @Override
                public void handleTune(int channelMax, long frameMax, int heartbeat,
                        ClientTuned tuned) {
                    tuned.open(virtualHost, new ServerOpenHandler() {
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
                handshake.startOk(new AMQPLainClientMechanism(username, password), tuneHandler);
            } else if ("EXTERNAL".equalsIgnoreCase(mechanism)) {
                handshake.startOk(SASLUtils.createClient("EXTERNAL", username, password, host), tuneHandler);
            } else if ("GSSAPI".equalsIgnoreCase(mechanism)) {
                String principal = (gssapiServicePrincipal != null) ? gssapiServicePrincipal : host;
                SASLClientMechanism client = SASLUtils.createClient(
                        "GSSAPI", username, password, principal, gssapiSubject);
                if (client == null) {
                    scheduleReconnect(new IOException(
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
