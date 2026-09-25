/*
 * Amqp1ClientRecovery.java
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

package org.bluezoo.gumdrop.amqp1.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ScheduledTimer;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Begin;
import org.bluezoo.gumdrop.amqp1.codec.MessageHeader;
import org.bluezoo.gumdrop.amqp1.codec.MessageProperties;
import org.bluezoo.gumdrop.amqp1.codec.Open;
import org.bluezoo.gumdrop.amqp1.codec.SaslOutcome;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * An AMQP 1.0 client that survives losing its connection: it dials the
 * broker, authenticates, opens the connection and a session, and, if the
 * connection is later lost, reconnects with exponential backoff and
 * attaches the application's links again.
 *
 * <p>This is the facade most applications should use. It manages a single
 * session, and links attached through the {@link Amqp1RecoverableSession}
 * it hands out are recorded so they can be attached again on each new
 * connection. See {@link Amqp1RecoverableSession} for exactly what is and
 * is not preserved across a reconnect: in short, links come back, but
 * deliveries in flight are not resumed.
 *
 * <p>Threading: the API is single-threaded. Handler callbacks, and any
 * calls made from them, run on the connection's event loop; to use a link
 * from another thread, go through {@link #execute}.
 *
 * <p>For finer control, use {@link Amqp1ClientProtocolHandler} directly
 * with a {@link ClientEndpoint}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Amqp1ClientRecovery client = new Amqp1ClientRecovery("broker.example.org", 5672)
 *         .credentials("guest", "guest");
 * client.connect(gumdrop, new Amqp1RecoveryHandler() {
 *     public void onFirstConnect(Amqp1RecoverableSession session) {
 *         session.attachSender("orders-out", "/queues/orders", senderHandler);
 *     }
 * });
 * }</pre>
 *
 * <p>Implicit TLS ({@code amqps}, port 5671) is enabled with
 * {@link #setSecure}. Authentication is SASL {@code PLAIN} when
 * {@link #credentials} is set and {@code ANONYMOUS} otherwise, or as chosen
 * with {@link #mechanism}. Rejected credentials end recovery, since
 * retrying cannot help.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Amqp1RecoverableSession
 */
public final class Amqp1ClientRecovery {

    private static final Logger LOGGER = Logger.getLogger(Amqp1ClientRecovery.class.getName());
    private static final ResourceBundle L10N = Amqp1ClientProtocolHandler.L10N;

    /**
     * Deliberately <strong>not</strong> gumdrop's own timer infrastructure.
     * A reconnecting client has, for the whole of its backoff, no active
     * connections, so {@link Gumdrop}'s auto-shutdown may tear down the
     * worker loops (and any timers on them) before the retry fires. A small
     * daemon thread independent of gumdrop's lifecycle avoids that; the
     * reconnection itself still goes through the ordinary, gumdrop-managed
     * {@link ClientEndpoint#connect} path.
     */
    private static final ScheduledTimer RETRY_TIMER = new ScheduledTimer("gumdrop-amqp1-recovery");

    static {
        RETRY_TIMER.start();
    }

    /** Default session window, in transfer frames, advertised in each direction. */
    private static final long SESSION_WINDOW = 2048;

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final String socketPath;
    private final SelectorLoop selectorLoop;

    private String username;
    private String password;
    private String mechanism;
    private String containerId = "gumdrop-" + UUID.randomUUID();
    private String hostname;
    private long idleTimeOut;
    private Amqp1RecoveryPolicy policy = new Amqp1RecoveryPolicy();
    private Amqp1RecoveryListener listener;

    private boolean secure;
    private ServerCredentials clientCredentials;
    private X509TrustManager trustManager;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat;

    private Gumdrop gumdrop;
    private Amqp1RecoveryHandler appHandler;
    private int attempt;
    private volatile boolean closed;
    private volatile ClientEndpoint currentEndpoint;
    /** The endpoint of the latest connection, for {@link #execute}; kept after a loss. */
    private volatile Endpoint loopEndpoint;
    private volatile TimerHandle pendingRetry;
    private volatile Amqp1Session session;

    /** Every link the application attached, in order; guarded by itself. */
    private final List<RecordedLink> links = new ArrayList<RecordedLink>();
    private final RecoverableSessionImpl recoverableSession = new RecoverableSessionImpl();

    public Amqp1ClientRecovery(String host, int port) {
        this(null, host, port);
    }

    public Amqp1ClientRecovery(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.hostAddress = null;
        this.port = port;
        this.socketPath = null;
    }

    public Amqp1ClientRecovery(InetAddress host, int port) {
        this(null, host, port);
    }

    public Amqp1ClientRecovery(SelectorLoop selectorLoop, InetAddress host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = host;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates a client for a broker reached over a UNIX domain socket.
     *
     * @param socketPath the broker's UNIX domain socket path
     */
    public Amqp1ClientRecovery(String socketPath) {
        this(null, socketPath);
    }

    /**
     * Creates a client for a broker reached over a UNIX domain socket, with
     * an explicit selector loop.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param socketPath the broker's UNIX domain socket path
     */
    public Amqp1ClientRecovery(SelectorLoop selectorLoop, String socketPath) {
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

    /**
     * Sets the credentials for SASL {@code PLAIN}, which then becomes the
     * default mechanism. Use only with TLS, or on a trusted network.
     */
    public Amqp1ClientRecovery credentials(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    /**
     * Chooses the SASL mechanism: {@code "PLAIN"} (using {@link #credentials}),
     * {@code "ANONYMOUS"}, or {@code "EXTERNAL"} (which relies on the
     * client certificate from the TLS handshake and so needs
     * {@link #setSecure} and a keystore). Defaults to {@code PLAIN} if
     * credentials are set, otherwise {@code ANONYMOUS}.
     *
     * @throws IllegalArgumentException for any other mechanism
     */
    public Amqp1ClientRecovery mechanism(String mechanism) {
        if (mechanism != null && !"PLAIN".equalsIgnoreCase(mechanism)
                && !"ANONYMOUS".equalsIgnoreCase(mechanism)
                && !"EXTERNAL".equalsIgnoreCase(mechanism)) {
            throw new IllegalArgumentException("Unsupported SASL mechanism " + mechanism);
        }
        this.mechanism = mechanism == null ? null : mechanism.toUpperCase();
        return this;
    }

    /** The container id announced in {@code open}; a random one is used by default. */
    public Amqp1ClientRecovery containerId(String containerId) {
        this.containerId = containerId;
        return this;
    }

    /** The hostname announced in {@code open}, for brokers that host several virtual hosts. */
    public Amqp1ClientRecovery hostname(String hostname) {
        this.hostname = hostname;
        return this;
    }

    /**
     * The idle timeout to request of the broker, in milliseconds: it
     * should send something at least this often. Both this and the
     * broker's own timeout are honoured. Unset (0) requests none.
     */
    public Amqp1ClientRecovery idleTimeOut(long idleTimeOutMs) {
        this.idleTimeOut = idleTimeOutMs;
        return this;
    }

    public Amqp1ClientRecovery recoveryPolicy(Amqp1RecoveryPolicy policy) {
        this.policy = policy;
        return this;
    }

    public Amqp1ClientRecovery recoveryListener(Amqp1RecoveryListener listener) {
        this.listener = listener;
        return this;
    }

    /** Implicit TLS (AMQPS, typically port 5671). */
    public Amqp1ClientRecovery setSecure(boolean secure) {
        this.secure = secure;
        return this;
    }

    public Amqp1ClientRecovery setClientCredentials(ServerCredentials clientCredentials) {
        this.clientCredentials = clientCredentials;
        return this;
    }

    public Amqp1ClientRecovery setTrustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
        return this;
    }

    public Amqp1ClientRecovery setKeystoreFile(Path path) {
        this.keystoreFile = path;
        return this;
    }

    public Amqp1ClientRecovery setKeystorePass(String password) {
        this.keystorePass = password;
        return this;
    }

    public Amqp1ClientRecovery setKeystoreFormat(String format) {
        this.keystoreFormat = format;
        return this;
    }

    // ── lifecycle ──

    /**
     * Connects, calling {@code handler.onFirstConnect} once the connection
     * is open and a session has begun. If the connection is lost at any
     * point afterwards (including during the initial connect),
     * reconnects automatically per the recovery policy and attaches the
     * recorded links again, without calling {@code handler} again.
     *
     * @param gumdrop the runtime the connection is made under
     * @param handler receives the recoverable session
     */
    public void connect(Gumdrop gumdrop, Amqp1RecoveryHandler handler) {
        this.gumdrop = gumdrop;
        this.appHandler = handler;
        this.attempt = 0;
        doConnect(true);
    }

    /**
     * Runs a task on the event loop of the connection.
     *
     * <p>The links, deliveries and sessions of this API are not thread-safe:
     * they belong to the connection's event loop, on which every handler
     * callback runs, so calls made from a callback are always safe. To
     * send, receive credit or detach from any other thread, submit the call
     * here. If the caller is already on the loop the task runs at once;
     * otherwise it is queued.
     *
     * <p>While the connection is being re-established the task runs on the
     * loop of the connection that was lost, so it may find its links
     * detached; the stable link objects then throw
     * {@link IllegalStateException}.
     *
     * @param task the task to run
     * @throws IllegalStateException if no connection has been made yet
     */
    public void execute(Runnable task) {
        Endpoint endpoint = loopEndpoint;
        if (endpoint == null) {
            throw new IllegalStateException("not connected yet");
        }
        endpoint.execute(task);
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

    private boolean shouldStopRecovery() {
        return closed || (gumdrop != null && gumdrop.isDraining());
    }

    private void doConnect(final boolean first) {
        if (shouldStopRecovery()) {
            return;
        }
        TcpTransportFactory transportFactory = new TcpTransportFactory();
        transportFactory.setSecure(secure);
        if (clientCredentials != null) {
            transportFactory.setClientCredentials(clientCredentials);
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

        Amqp1ClientProtocolHandler handler = new Amqp1ClientProtocolHandler(new Attempt(first));
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
            endpoint.connect(gumdrop, handler);
        } catch (IOException e) {
            scheduleReconnect(e);
        }
    }

    private void scheduleReconnect(Exception cause) {
        if (shouldStopRecovery()) {
            return;
        }
        session = null;
        ClientEndpoint endpoint = currentEndpoint;
        if (endpoint != null) {
            endpoint.close();
        }
        if (listener != null) {
            listener.onConnectionLost(cause);
        }
        attempt++;
        int maxAttempts = policy.getMaxAttempts();
        if (maxAttempts > 0 && attempt > maxAttempts) {
            abandon(cause, true);
            return;
        }
        long delay = policy.delayFor(attempt);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, L10N.getString("warn.connection_lost"), cause);
        }
        LOGGER.log(Level.INFO, L10N.getString("info.reconnecting"), new Object[] {delay, attempt});
        if (listener != null) {
            listener.onReconnecting(attempt, delay);
        }
        pendingRetry = RETRY_TIMER.schedule(null, delay, new Runnable() {
            @Override
            public void run() {
                doConnect(false);
            }
        });
    }

    /**
     * Gives up: stops reconnecting and tells the listener.
     *
     * @param retriesExhausted true if the policy's attempts ran out, false
     *      for a failure that retrying cannot fix
     */
    private void abandon(Exception cause, boolean retriesExhausted) {
        closed = true;
        String key = retriesExhausted ? "err.recovery_failed" : "err.recovery_not_retried";
        String detail = cause != null && cause.getMessage() != null ? ": " + cause.getMessage() : "";
        LOGGER.log(Level.WARNING, MessageFormat.format(L10N.getString(key), attempt) + detail);
        if (listener != null) {
            listener.onRecoveryFailed(cause);
        }
    }

    // ── the recorded links ──

    /** A link the application attached, with the state needed to attach it again. */
    private abstract class RecordedLink {
        final Attach template;
        /** True while the link has no current attachment and should get one on the next session. */
        boolean needsAttach = true;
        /** The application asked to close the link, so it must not come back. */
        boolean closeRequested;

        RecordedLink(Attach template) {
            this.template = template.copy();
        }

        abstract void attachOn(Amqp1Session s);

        abstract boolean isReceiver();

        final void remove() {
            synchronized (links) {
                links.remove(this);
            }
        }
    }

    private final class RecoverableSender extends RecordedLink
            implements Amqp1SenderHandler, Amqp1Sender {

        private final Amqp1SenderHandler app;
        private volatile Amqp1Sender current;

        RecoverableSender(Attach template, Amqp1SenderHandler app) {
            super(template);
            this.app = app;
        }

        @Override
        boolean isReceiver() {
            return false;
        }

        @Override
        void attachOn(Amqp1Session s) {
            needsAttach = false;
            s.attachSender(template.copy(), this);
        }

        // ── as handler: forward to the application, presenting ourselves as the link ──

        @Override
        public void handleAttached(Amqp1Sender sender, Attach peerAttach) {
            current = sender;
            app.handleAttached(this, peerAttach);
        }

        @Override
        public void handleCredit(Amqp1Sender sender) {
            app.handleCredit(this);
        }

        @Override
        public void handleWritable(Amqp1OutgoingDelivery delivery) {
            app.handleWritable(delivery);
        }

        @Override
        public void handleOutcome(Amqp1OutgoingDelivery delivery,
                org.bluezoo.gumdrop.amqp1.codec.DeliveryState state, boolean settled) {
            app.handleOutcome(delivery, state, settled);
        }

        @Override
        public void handleDetached(Amqp1Error error, boolean closed) {
            current = null;
            boolean permanent = closed || closeRequested;
            if (permanent) {
                remove();
            } else {
                needsAttach = true;
            }
            app.handleDetached(error, permanent);
        }

        // ── as the stable link ──

        private Amqp1Sender requireCurrent() {
            Amqp1Sender s = current;
            if (s == null) {
                throw new IllegalStateException("link is not attached (recovering)");
            }
            return s;
        }

        @Override
        public String getName() {
            return template.getName();
        }

        @Override
        public Attach getPeerAttach() {
            Amqp1Sender s = current;
            return s == null ? null : s.getPeerAttach();
        }

        @Override
        public long getLinkCredit() {
            Amqp1Sender s = current;
            return s == null ? 0L : s.getLinkCredit();
        }

        @Override
        public Amqp1OutgoingDelivery startDelivery(byte[] deliveryTag, MessageHeader header,
                MessageProperties properties, Map<Object, Object> applicationProperties,
                boolean settled) {
            return requireCurrent().startDelivery(deliveryTag, header, properties,
                    applicationProperties, settled);
        }

        @Override
        public Amqp1OutgoingDelivery send(byte[] deliveryTag, MessageProperties properties,
                Map<Object, Object> applicationProperties, ByteBuffer body) {
            return requireCurrent().send(deliveryTag, properties, applicationProperties, body);
        }

        @Override
        public void detach(Amqp1Error error, boolean close) {
            Amqp1Sender s = requireCurrent();
            if (close) {
                closeRequested = true;
            }
            s.detach(error, close);
        }
    }

    private final class RecoverableReceiver extends RecordedLink
            implements Amqp1ReceiverHandler, Amqp1Receiver {

        private final Amqp1ReceiverHandler app;
        private volatile Amqp1Receiver current;

        RecoverableReceiver(Attach template, Amqp1ReceiverHandler app) {
            super(template);
            this.app = app;
        }

        @Override
        boolean isReceiver() {
            return true;
        }

        @Override
        void attachOn(Amqp1Session s) {
            needsAttach = false;
            s.attachReceiver(template.copy(), this);
        }

        // ── as handler ──

        @Override
        public void handleAttached(Amqp1Receiver receiver, Attach peerAttach) {
            current = receiver;
            app.handleAttached(this, peerAttach);
        }

        @Override
        public void startDelivery(Amqp1IncomingDelivery delivery) {
            app.startDelivery(delivery);
        }

        @Override
        public void handleAborted(Amqp1IncomingDelivery delivery) {
            app.handleAborted(delivery);
        }

        @Override
        public void handleDetached(Amqp1Error error, boolean closed) {
            current = null;
            boolean permanent = closed || closeRequested;
            if (permanent) {
                remove();
            } else {
                needsAttach = true;
            }
            app.handleDetached(error, permanent);
        }

        @Override
        public void header(MessageHeader header) {
            app.header(header);
        }

        @Override
        public void deliveryAnnotations(Map<Object, Object> annotations) {
            app.deliveryAnnotations(annotations);
        }

        @Override
        public void messageAnnotations(Map<Object, Object> annotations) {
            app.messageAnnotations(annotations);
        }

        @Override
        public void properties(MessageProperties properties) {
            app.properties(properties);
        }

        @Override
        public void applicationProperties(Map<Object, Object> properties) {
            app.applicationProperties(properties);
        }

        @Override
        public void startData(long length) {
            app.startData(length);
        }

        @Override
        public void dataChunk(ByteBuffer chunk) {
            app.dataChunk(chunk);
        }

        @Override
        public void endData() {
            app.endData();
        }

        @Override
        public void amqpSequence(List<Object> row) {
            app.amqpSequence(row);
        }

        @Override
        public void amqpValue(Object value) {
            app.amqpValue(value);
        }

        @Override
        public void footer(Map<Object, Object> footer) {
            app.footer(footer);
        }

        @Override
        public void endMessage() {
            app.endMessage();
        }

        @Override
        public void messageError(String message) {
            app.messageError(message);
        }

        // ── as the stable link ──

        private Amqp1Receiver requireCurrent() {
            Amqp1Receiver r = current;
            if (r == null) {
                throw new IllegalStateException("link is not attached (recovering)");
            }
            return r;
        }

        @Override
        public String getName() {
            return template.getName();
        }

        @Override
        public Attach getPeerAttach() {
            Amqp1Receiver r = current;
            return r == null ? null : r.getPeerAttach();
        }

        @Override
        public long getLinkCredit() {
            Amqp1Receiver r = current;
            return r == null ? 0L : r.getLinkCredit();
        }

        @Override
        public void addCredit(long credit) {
            requireCurrent().addCredit(credit);
        }

        @Override
        public void detach(Amqp1Error error, boolean close) {
            Amqp1Receiver r = requireCurrent();
            if (close) {
                closeRequested = true;
            }
            r.detach(error, close);
        }
    }

    private final class RecoverableSessionImpl implements Amqp1RecoverableSession {

        @Override
        public void attachSender(String linkName, String address, Amqp1SenderHandler handler) {
            Attach a = new Attach(linkName, 0, false);
            a.setTarget(new org.bluezoo.gumdrop.amqp1.codec.Target(address));
            attachSender(a, handler);
        }

        @Override
        public void attachSender(Attach attach, Amqp1SenderHandler handler) {
            if (attach.isReceiver()) {
                throw new IllegalArgumentException("attach describes the receiving end");
            }
            if (handler == null) {
                throw new NullPointerException("handler");
            }
            record(new RecoverableSender(attach, handler));
        }

        @Override
        public void attachReceiver(String linkName, String address, Amqp1ReceiverHandler handler) {
            Attach a = new Attach(linkName, 0, true);
            a.setSource(new org.bluezoo.gumdrop.amqp1.codec.Source(address));
            attachReceiver(a, handler);
        }

        @Override
        public void attachReceiver(Attach attach, Amqp1ReceiverHandler handler) {
            if (!attach.isReceiver()) {
                throw new IllegalArgumentException("attach describes the sending end");
            }
            if (handler == null) {
                throw new NullPointerException("handler");
            }
            record(new RecoverableReceiver(attach, handler));
        }

        private void record(RecordedLink link) {
            synchronized (links) {
                for (RecordedLink l : links) {
                    if (l.isReceiver() == link.isReceiver()
                            && l.template.getName().equals(link.template.getName())) {
                        throw new IllegalStateException("link name '" + link.template.getName()
                                + "' is already in use");
                    }
                }
                links.add(link);
            }
            Amqp1Session s = session;
            if (s != null) {
                link.attachOn(s);
            }
        }
    }

    /** Attaches every recorded link that has no current attachment to the new session. */
    private void attachRecordedLinks(Amqp1Session s) {
        List<RecordedLink> toAttach = new ArrayList<RecordedLink>();
        synchronized (links) {
            for (RecordedLink l : links) {
                if (l.needsAttach) {
                    toAttach.add(l);
                }
            }
        }
        for (RecordedLink l : toAttach) {
            l.attachOn(s);
        }
    }

    // ── one connection attempt ──

    /**
     * Drives one attempt from the transport up: SASL, open, then a
     * session, reporting any failure to {@link #scheduleReconnect}.
     * Created afresh for each attempt, so a plain field suffices to
     * recognise that a loss has already been handled: a broker-initiated
     * close delivers both an AMQP {@code close} and, moments later, the
     * TCP end-of-file for the same socket, and without that guard the two
     * would each schedule a reconnect and race.
     */
    private final class Attempt implements Amqp1ConnectionReady, Amqp1AuthHandler,
            Amqp1OpenHandler, Amqp1SessionHandler {

        private final boolean first;
        private boolean lossHandled;
        private Amqp1Connection connection;

        Attempt(boolean first) {
            this.first = first;
        }

        private void lost(Exception cause) {
            synchronized (this) {
                if (lossHandled) {
                    return;
                }
                lossHandled = true;
            }
            scheduleReconnect(cause);
        }

        private void permanent(Exception cause) {
            synchronized (this) {
                if (lossHandled) {
                    return;
                }
                lossHandled = true;
            }
            ClientEndpoint endpoint = currentEndpoint;
            if (endpoint != null) {
                endpoint.close();
            }
            abandon(cause, false);
        }

        // ── Amqp1ConnectionReady ──

        @Override
        public void onConnected(Endpoint endpoint) {
            loopEndpoint = endpoint;
        }

        @Override
        public void handleSaslMechanisms(List<String> mechanisms, Amqp1SaslHandshake handshake) {
            String chosen = mechanism != null ? mechanism
                    : (username != null ? "PLAIN" : "ANONYMOUS");
            if (!mechanisms.contains(chosen)) {
                permanent(new IOException(MessageFormat.format(
                        L10N.getString("err.sasl_mechanism_not_offered"), chosen, mechanisms)));
                return;
            }
            if ("PLAIN".equals(chosen)) {
                if (username == null) {
                    permanent(new IOException("SASL PLAIN requires credentials"));
                    return;
                }
                handshake.authenticate(username, password, this);
            } else if ("EXTERNAL".equals(chosen)) {
                handshake.authenticate(SaslUtils.createClient("EXTERNAL", username, password, host),
                        this);
            } else {
                handshake.authenticateAnonymous(null, this);
            }
        }

        @Override
        public void onConnectionClosed(Amqp1Error error) {
            lost(new IOException("Connection closed by broker"
                    + (error != null ? ": " + error : "")));
        }

        @Override
        public void onDisconnected() {
            lost(new IOException("Connection closed"));
        }

        @Override
        public void onError(Exception cause) {
            lost(cause);
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        // ── Amqp1AuthHandler ──

        @Override
        public void handleAuthenticated(Amqp1ConnectionOpener opener) {
            Open open = new Open(containerId);
            open.setHostname(hostname);
            if (idleTimeOut > 0) {
                open.setIdleTimeOut(idleTimeOut);
            }
            opener.open(open, this);
        }

        @Override
        public void handleAuthenticationFailed(int code, byte[] additionalData) {
            IOException cause = new IOException(L10N.getString("err.authentication_failed")
                    + " (SASL code " + code + ")");
            if (code == SaslOutcome.AUTH) {
                permanent(cause); // bad credentials will be bad on every attempt
            } else {
                lost(cause);
            }
        }

        // ── Amqp1OpenHandler ──

        @Override
        public void handleOpen(Open peerOpen, Amqp1Connection c) {
            connection = c;
            c.beginSession(new Begin(0, SESSION_WINDOW, SESSION_WINDOW), this);
        }

        // ── Amqp1SessionHandler ──

        @Override
        public void handleBegun(Amqp1Session s, Begin peerBegin) {
            attempt = 0;
            session = s;
            attachRecordedLinks(s);
            if (first) {
                appHandler.onFirstConnect(recoverableSession);
            } else if (listener != null) {
                listener.onRecovered();
            }
        }

        @Override
        public void handleEnded(Amqp1Error error) {
            // The session is the unit of recovery: if it ends under us
            // (broker error, or the connection going away) start again
            lost(new IOException("Session ended" + (error != null ? ": " + error : "")));
        }
    }
}
