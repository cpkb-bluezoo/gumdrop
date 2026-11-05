/*
 * NntpServer.java
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

package org.bluezoo.gumdrop.nntp.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.nntp.NntpListener;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * NNTP protocol server: listeners, configuration and session composition.
 * Assemble one with {@link #compose()}.
 *
 * <p>Service-level configuration is pushed into each listener when the
 * server starts. The {@link NntpServerSessionProvider} supplies the handler
 * for each connection; typically that is a
 * {@link MailboxStoreNntpSessionProvider}, which serves the mailboxes of a
 * mailbox store as newsgroups.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * NntpServer server = NntpServer.compose()
 *         .listener(new NntpListener().port(119).tls(tls))
 *         .listener(new NntpListener().port(563).secure(true).tls(tls))
 *         .sessionProvider(new MailboxStoreNntpSessionProvider()
 *                 .mailboxFactory(new MaildirMailboxFactory(spool)))
 *         .server();
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see NntpListener
 */
public final class NntpServer implements Server, NntpServerSessionProvider {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.nntp.L10N");

    private volatile Gumdrop runtime;
    private final List<Listener> listeners = new ArrayList<Listener>();
    private NntpServerSessionProvider sessionProvider;
    private Realm realm;
    private String anonymousUser;
    private boolean postingAllowed;
    private boolean authenticationRequired;

    private NntpServer() {
    }

    private EventLogger events() {
        return (runtime != null ? runtime.getTelemetryConfig() : new TelemetryConfig())
                .getLogger(NntpServer.class, L10N);
    }

    /**
     * Starts the assembly of a server.
     *
     * @return a new composer
     */
    public static Composer compose() {
        return new Composer();
    }

    @Override
    public List<Listener> getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        NntpServerSessionProvider provider = sessionProvider;
        return provider == null ? null : provider.openSession(listener);
    }

    @Override
    public void start(Gumdrop gumdrop) {
        this.runtime = gumdrop;
        if (sessionProvider != null) {
            sessionProvider.start();
        }
        for (int i = 0; i < listeners.size(); i++) {
            Listener listener = listeners.get(i);
            if (listener instanceof NntpListener) {
                wire((NntpListener) listener);
            }
            try {
                listener.start(gumdrop);
            } catch (Exception e) {
                events().error("warn.failed_start_listener")
                        .attr("listener", String.valueOf(listener)).thrown(e).emit();
            }
        }
    }

    @Override
    public void stop() {
        for (int i = 0; i < listeners.size(); i++) {
            try {
                listeners.get(i).stop();
            } catch (Exception e) {
                events().warn("warn.error_stopping_listener")
                        .attr("listener", String.valueOf(listeners.get(i))).thrown(e).emit();
            }
        }
        if (sessionProvider != null) {
            sessionProvider.stop();
        }
    }

    private void wire(NntpListener listener) {
        if (realm != null) {
            listener.realm(realm);
        }
        if (anonymousUser != null) {
            listener.anonymousUser(anonymousUser);
        }
        if (postingAllowed) {
            listener.postingAllowed(true);
        }
        if (authenticationRequired) {
            listener.authenticationRequired(true);
        }
        if (sessionProvider != null) {
            listener.sessionProvider(sessionProvider);
        }
        listener.server(this);
    }

    /** Assembles an {@link NntpServer}. */
    public static final class Composer {

        private final List<NntpListener> listeners = new ArrayList<NntpListener>();
        private NntpServerSessionProvider sessionProvider;
        private Realm realm;
        private String anonymousUser;
        private boolean postingAllowed;
        private boolean authenticationRequired;

        private Composer() {
        }

        /**
         * Adds a listener. At least one is required.
         *
         * @param listener the listener
         * @return this composer
         */
        public Composer listener(NntpListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener");
            }
            listeners.add(listener);
            return this;
        }

        /**
         * Sets the supplier of the handler for each connection.
         *
         * @param provider the provider
         * @return this composer
         */
        public Composer sessionProvider(NntpServerSessionProvider provider) {
            if (provider == null) {
                throw new NullPointerException("provider");
            }
            this.sessionProvider = provider;
            return this;
        }

        /**
         * Sets the authentication realm, given to every listener.
         *
         * @param realm the realm
         * @return this composer
         */
        public Composer realm(Realm realm) {
            this.realm = realm;
            return this;
        }

        /**
         * Sets the user whose store serves unauthenticated readers, given to
         * every listener that has not set its own. The default is {@code news}.
         *
         * @param anonymousUser the user name
         * @return this composer
         */
        public Composer anonymousUser(String anonymousUser) {
            this.anonymousUser = anonymousUser;
            return this;
        }

        /**
         * Sets whether readers are told they may post, given to every listener.
         *
         * @param postingAllowed true to allow posting
         * @return this composer
         */
        public Composer postingAllowed(boolean postingAllowed) {
            this.postingAllowed = postingAllowed;
            return this;
        }

        /**
         * Sets whether clients must authenticate before reading news, given
         * to every listener. Needs a realm.
         *
         * @param authenticationRequired true to require authentication
         * @return this composer
         */
        public Composer authenticationRequired(boolean authenticationRequired) {
            this.authenticationRequired = authenticationRequired;
            return this;
        }

        /**
         * Builds the server.
         *
         * @return the server
         * @throws IllegalStateException if there is no listener
         */
        public NntpServer server() {
            if (listeners.isEmpty()) {
                throw new IllegalStateException("at least one listener is required");
            }
            NntpServer server = new NntpServer();
            server.sessionProvider = sessionProvider;
            server.realm = realm;
            server.anonymousUser = anonymousUser;
            server.postingAllowed = postingAllowed;
            server.authenticationRequired = authenticationRequired;
            for (int i = 0; i < listeners.size(); i++) {
                server.listeners.add(listeners.get(i));
            }
            return server;
        }
    }

}
