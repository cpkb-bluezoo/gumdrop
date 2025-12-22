/*
 * SessionManager.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.servlet.session;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionEvent;

/**
 * Manages HTTP sessions for a servlet context.
 *
 * <p>Each servlet context creates one SessionManager instance to handle
 * session lifecycle, including creation, retrieval, invalidation, and
 * optional cluster replication.
 *
 * <p>The SessionManager has a unique context UUID that identifies this
 * context instance within the cluster. This UUID is regenerated on hot
 * deployment to trigger session repopulation from other nodes.
 *
 * <p>Usage:
 * <pre>
 * // Create manager for a context
 * SessionManager manager = new SessionManager(context);
 *
 * // Register with cluster (done by Container)
 * cluster.registerContext(manager.getContextUuid(), manager);
 *
 * // Create or get sessions
 * HttpSession session = manager.getSession(requestedId, true);
 *
 * // Replicate after request processing
 * manager.replicateSession(session);
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SessionManager {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.session.L10N");
    private static final Logger LOGGER = Logger.getLogger(SessionManager.class.getName());

    private final SessionContext context;
    private final Map<String, Session> sessions;
    private final SecureRandom random;
    private UUID contextUuid;
    private Cluster cluster;

    /**
     * Creates a new session manager for the given context.
     *
     * @param context the session context
     */
    public SessionManager(SessionContext context) {
        this.context = context;
        this.sessions = new HashMap<>();
        this.random = new SecureRandom();
        this.contextUuid = UUID.randomUUID();
    }

    /**
     * Returns the context UUID for this session manager.
     * This UUID uniquely identifies this context instance within the cluster
     * and is regenerated on hot deployment.
     *
     * @return the context UUID
     */
    public UUID getContextUuid() {
        return contextUuid;
    }

    /**
     * Regenerates the context UUID.
     * This should be called when the context is reloaded (hot deployment)
     * to signal to other cluster nodes that this context needs session
     * repopulation.
     *
     * @return the new context UUID
     */
    public UUID regenerateContextUuid() {
        this.contextUuid = UUID.randomUUID();
        if (LOGGER.isLoggable(Level.FINE)) {
            String message = L10N.getString("info.context_uuid_regenerated");
            message = java.text.MessageFormat.format(message, contextUuid,
                    context.getServletContextName());
            LOGGER.fine(message);
        }
        return contextUuid;
    }

    /**
     * Sets the cluster reference for this session manager.
     * Called by the Container when clustering is enabled.
     *
     * @param cluster the cluster, or null to disable clustering
     */
    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    /**
     * Returns whether clustering is enabled for this manager.
     *
     * @return true if clustering is enabled
     */
    public boolean isClusteringEnabled() {
        return cluster != null;
    }

    /**
     * Returns the session context.
     *
     * @return the session context
     */
    public SessionContext getContext() {
        return context;
    }

    /**
     * Creates a new session with a randomly generated ID.
     *
     * @return the new session
     */
    public HttpSession createSession() {
        String id = generateSessionId();
        Session session = new Session(context, id);
        synchronized (sessions) {
            sessions.put(id, session);
        }
        return session;
    }

    /**
     * Gets or creates a session.
     *
     * @param id the session ID to look up, or null to create a new session
     * @param create true to create a new session if not found
     * @return the session, or null if not found and create is false
     */
    public HttpSession getSession(String id, boolean create) {
        Session session = null;
        if (id != null) {
            synchronized (sessions) {
                session = sessions.get(id);
            }
            if (session != null) {
                session.lastAccessedTime = System.currentTimeMillis();
            }
        }
        if (session == null && create) {
            session = (Session) createSession();
        }
        return session;
    }

    /**
     * Gets a session by ID without creating.
     *
     * @param id the session ID
     * @return the session, or null if not found
     */
    public HttpSession getSession(String id) {
        synchronized (sessions) {
            return sessions.get(id);
        }
    }

    /**
     * Removes a session.
     *
     * @param id the session ID
     */
    public void removeSession(String id) {
        removeSession(id, true);
    }

    /**
     * Removes a session with optional cluster notification.
     *
     * @param id the session ID
     * @param notifyCluster true to notify the cluster of removal
     */
    public void removeSession(String id, boolean notifyCluster) {
        Session session;
        synchronized (sessions) {
            session = sessions.remove(id);
        }
        if (session != null) {
            // Notify activation listeners
            Collection<HttpSessionActivationListener> listeners =
                    context.getSessionActivationListeners();
            if (!listeners.isEmpty()) {
                HttpSessionEvent event = new HttpSessionEvent(session);
                for (HttpSessionActivationListener l : listeners) {
                    l.sessionWillPassivate(event);
                }
            }
            // Notify cluster
            if (notifyCluster && cluster != null) {
                try {
                    cluster.passivate(contextUuid, session);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Replicates a session to the cluster if clustering is enabled
     * and the session is dirty.
     *
     * @param session the session to replicate
     */
    public void replicateSession(HttpSession session) {
        if (cluster != null && session instanceof Session) {
            try {
                cluster.replicate(contextUuid, (Session) session);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("err.session_replication"), e);
            }
        }
    }

    /**
     * Returns all sessions. Used for cluster replication when a new
     * node joins.
     *
     * @return collection of all sessions
     */
    public Collection<Session> getAllSessions() {
        synchronized (sessions) {
            return sessions.values();
        }
    }

    /**
     * Adds a session received from the cluster.
     * If the session already exists, it is updated with the received data.
     *
     * @param session the session received from the cluster
     */
    void addClusterSession(Session session) {
        synchronized (sessions) {
            Session existing = sessions.get(session.getId());
            if (existing != null) {
                existing.updateFrom(session);
            } else {
                sessions.put(session.getId(), session);
            }
        }
        // Notify activation listeners
        Collection<HttpSessionActivationListener> listeners =
                context.getSessionActivationListeners();
        if (!listeners.isEmpty()) {
            HttpSessionEvent event = new HttpSessionEvent(session);
            for (HttpSessionActivationListener l : listeners) {
                l.sessionDidActivate(event);
            }
        }
    }

    /**
     * Passivates a session received from the cluster.
     *
     * @param sessionId the ID of the session to passivate
     */
    void passivateClusterSession(String sessionId) {
        removeSession(sessionId, false);
    }

    /**
     * Generates a random session ID.
     *
     * @return a 32-character hex string
     */
    private String generateSessionId() {
        try {
            // Generate 16 random bytes
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);

            // Hash with MD5 for consistent 16-byte output
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);

            // Convert to hex string
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(L10N.getString("err.md5_unavailable"), e);
        }
    }

    /**
     * Invalidates expired sessions.
     * Called periodically by the container.
     */
    public void invalidateExpiredSessions() {
        int timeout = context.getSessionTimeout();
        if (timeout <= 0) {
            return; // Sessions never expire
        }
        long now = System.currentTimeMillis();
        long maxAge = timeout * 1000L;
        synchronized (sessions) {
            Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Session> entry = it.next();
                Session session = entry.getValue();
                if (now - session.lastAccessedTime > maxAge) {
                    session.invalidate();
                    it.remove();
                }
            }
        }
    }

}
