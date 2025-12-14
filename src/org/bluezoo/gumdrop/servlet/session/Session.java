/*
 * Session.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

import org.bluezoo.gumdrop.util.IteratorEnumeration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionListener;

/**
 * HTTP session implementation.
 *
 * <p>Supports cluster replication with:
 * <ul>
 *   <li>Version tracking for ordering updates</li>
 *   <li>Dirty attribute tracking for delta replication</li>
 *   <li>Full/delta serialization modes</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Session implements HttpSession {

    /**
     * Global version counter for ordering session updates across the cluster.
     * Each session modification increments this counter.
     */
    private static final AtomicLong GLOBAL_VERSION = new AtomicLong(0);

    final SessionContext context;
    final String id; // 32 chars, hex representation of MD5 digest
    long creationTime;
    long lastAccessedTime;
    int maxInactiveInterval;
    Map<String, Object> attributes;

    // Cluster replication state
    /** Session version for ordering updates */
    long version;
    /** Names of attributes modified since last replication */
    private Set<String> dirtyAttributes;
    /** Names of attributes removed since last replication */
    private Set<String> removedAttributes;
    /** True if the session is new and hasn't been replicated yet */
    boolean isNew;

    Session(SessionContext context, String id) {
        this.context = context;
        this.id = id;
        creationTime = System.currentTimeMillis();
        lastAccessedTime = creationTime;
        maxInactiveInterval = context.getSessionTimeout();
        attributes = new LinkedHashMap<>();
        version = GLOBAL_VERSION.incrementAndGet();
        dirtyAttributes = new HashSet<>();
        removedAttributes = new HashSet<>();
        isNew = true;
    }

    // Serialize to a compact form for cluster replication
    void serializeId(ByteBuffer buf) throws IOException {
        // session id is 32 characters representing 16 bytes
        for (int i = 0; i < 32; ) {
            int hi = Character.digit(id.charAt(i++), 0x10);
            int lo = Character.digit(id.charAt(i++), 0x10);
            buf.put((byte) ((hi << 4) | lo));
        }
    }

    /**
     * Serializes this session to a ByteBuffer using Protobuf format.
     *
     * @return a ByteBuffer containing the serialized session (ready for reading)
     * @throws IOException if serialization fails
     */
    ByteBuffer serialize() throws IOException {
        return SessionSerializer.serialize(this);
    }

    static String deserializeId(ByteBuffer buf) throws IOException {
        byte[] idbytes = new byte[16];
        buf.get(idbytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : idbytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Deserializes a session from a ByteBuffer using Protobuf format.
     *
     * @param context the session context
     * @param buf the buffer containing the serialized session
     * @return the deserialized session
     * @throws IOException if deserialization fails
     */
    static Session deserialize(SessionContext context, ByteBuffer buf) throws IOException {
        return SessionSerializer.deserialize(context, buf);
    }

    @Override
    public long getCreationTime() {
        return creationTime;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public long getLastAccessedTime() {
        return lastAccessedTime;
    }

    @Override
    public ServletContext getServletContext() {
        return context;
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        maxInactiveInterval = interval;
    }

    @Override
    public int getMaxInactiveInterval() {
        return maxInactiveInterval;
    }

    @Override
    @SuppressWarnings("deprecation")
    public HttpSessionContext getSessionContext() {
        return null; // deprecated
    }

    @Override
    public synchronized Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Object getValue(String name) {
        return getAttribute(name);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public synchronized Enumeration getAttributeNames() {
        return new IteratorEnumeration(attributes.keySet());
    }

    @Override
    public synchronized String[] getValueNames() {
        List<String> list = new ArrayList<>(attributes.keySet());
        String[] ret = new String[list.size()];
        list.toArray(ret);
        return ret;
    }

    @Override
    public synchronized void setAttribute(String name, Object value) {
        Object oldValue = attributes.put(name, value);
        // Track as dirty for cluster replication
        dirtyAttributes.add(name);
        removedAttributes.remove(name);
        version = GLOBAL_VERSION.incrementAndGet();

        HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, value);
        if (oldValue != null && oldValue instanceof HttpSessionBindingListener) {
            ((HttpSessionBindingListener) oldValue).valueUnbound(event);
        }
        if (value instanceof HttpSessionBindingListener) {
            ((HttpSessionBindingListener) value).valueBound(event);
        }
        // Notify context session attribute listeners
        for (HttpSessionAttributeListener l : context.getSessionAttributeListeners()) {
            if (oldValue == null) {
                l.attributeAdded(event);
            } else {
                l.attributeReplaced(event);
            }
        }
    }

    @Override
    public void putValue(String name, Object value) {
        setAttribute(name, value);
    }

    @Override
    public synchronized void removeAttribute(String name) {
        Object oldValue = attributes.remove(name);
        if (oldValue != null) {
            // Track removal for cluster replication
            dirtyAttributes.remove(name);
            removedAttributes.add(name);
            version = GLOBAL_VERSION.incrementAndGet();

            HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, oldValue);
            if (oldValue instanceof HttpSessionBindingListener) {
                ((HttpSessionBindingListener) oldValue).valueUnbound(event);
            }
            for (HttpSessionAttributeListener l : context.getSessionAttributeListeners()) {
                l.attributeRemoved(event);
            }
        }
    }

    @Override
    public void removeValue(String name) {
        removeAttribute(name);
    }

    @Override
    public void invalidate() {
        // Note: The actual removal from the session map is handled by
        // SessionManager, not directly by the session. This method notifies
        // listeners. The caller (usually Request) should call
        // sessionManager.removeSession(id) after this.
        for (HttpSessionListener l : context.getSessionListeners()) {
            l.sessionDestroyed(new javax.servlet.http.HttpSessionEvent(this));
        }
    }

    @Override
    public boolean isNew() {
        return lastAccessedTime == creationTime;
    }

    // -- Cluster replication support --

    /**
     * Returns true if this session has changes that need to be replicated.
     */
    synchronized boolean isDirty() {
        return isNew || !dirtyAttributes.isEmpty() || !removedAttributes.isEmpty();
    }

    /**
     * Returns true if the session should be replicated as a full snapshot
     * rather than a delta update.
     */
    synchronized boolean needsFullReplication() {
        if (isNew) {
            return true;
        }
        int totalAttributes = attributes.size();
        int changedCount = dirtyAttributes.size() + removedAttributes.size();
        return totalAttributes > 0 && changedCount > totalAttributes / 2;
    }

    /**
     * Returns the set of attribute names that have been modified.
     */
    synchronized Set<String> getDirtyAttributes() {
        return Collections.unmodifiableSet(new HashSet<>(dirtyAttributes));
    }

    /**
     * Returns the set of attribute names that have been removed.
     */
    synchronized Set<String> getRemovedAttributes() {
        return Collections.unmodifiableSet(new HashSet<>(removedAttributes));
    }

    /**
     * Clears the dirty state after successful replication.
     */
    synchronized void clearDirtyState() {
        dirtyAttributes.clear();
        removedAttributes.clear();
        isNew = false;
    }

    /**
     * Serializes only the dirty attributes for delta replication.
     */
    ByteBuffer serializeDelta() throws IOException {
        return SessionSerializer.serializeDelta(this, getDirtyAttributes(), getRemovedAttributes());
    }

    /**
     * Applies a delta update from another node.
     */
    synchronized boolean applyDelta(long deltaVersion, Map<String, Object> updatedAttrs,
                                    Set<String> removedAttrNames) {
        if (deltaVersion <= version) {
            return false;
        }
        for (Map.Entry<String, Object> entry : updatedAttrs.entrySet()) {
            attributes.put(entry.getKey(), entry.getValue());
        }
        for (String name : removedAttrNames) {
            attributes.remove(name);
        }
        version = deltaVersion;
        long currentGlobal = GLOBAL_VERSION.get();
        if (deltaVersion > currentGlobal) {
            GLOBAL_VERSION.compareAndSet(currentGlobal, deltaVersion);
        }
        return true;
    }

    /**
     * Updates this session from a received full session snapshot.
     */
    synchronized void updateFrom(Session receivedSession) {
        if (receivedSession.version <= version) {
            return;
        }
        creationTime = receivedSession.creationTime;
        lastAccessedTime = receivedSession.lastAccessedTime;
        maxInactiveInterval = receivedSession.maxInactiveInterval;
        attributes = new LinkedHashMap<>(receivedSession.attributes);
        version = receivedSession.version;
        dirtyAttributes.clear();
        removedAttributes.clear();
        isNew = false;
    }

}

