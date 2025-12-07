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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.util.IteratorEnumeration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

/**
 * Servlet session bean.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Session implements HttpSession {

    final Context context;
    final String id; // 32 chars, hex representation of MD5 digest
    long creationTime;
    long lastAccessedTime;
    int maxInactiveInterval;
    Map<String,Object> attributes;

    Session(Context context, String id) {
        this.context = context;
        this.id = id;
        creationTime = System.currentTimeMillis();
        lastAccessedTime = creationTime;
        maxInactiveInterval = context.sessionTimeout;
        attributes = new LinkedHashMap<>();
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
     * <p>This method uses {@link SessionSerializer} which provides:
     * <ul>
     *   <li>Efficient binary encoding for primitive types</li>
     *   <li>Safe deserialization with filtering for complex objects</li>
     *   <li>Protection against Java deserialization vulnerabilities</li>
     * </ul>
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
     * <p>This method should be called with the context classloader set
     * on the current thread. It uses {@link SessionSerializer} which
     * applies strict deserialization filters to prevent attacks.
     *
     * @param context the servlet context
     * @param buf the buffer containing the serialized session
     * @return the deserialized session
     * @throws IOException if deserialization fails
     */
    static Session deserialize(Context context, ByteBuffer buf) throws IOException {
        return SessionSerializer.deserialize(context, buf);
    }

    public long getCreationTime() {
        return creationTime;
    }

    public String getId() {
        return id;
    }

    public long getLastAccessedTime() {
        return lastAccessedTime;
    }

    public ServletContext getServletContext() {
        return context;
    }

    public void setMaxInactiveInterval(int interval) {
        maxInactiveInterval = interval;
    }

    public int getMaxInactiveInterval() {
        return maxInactiveInterval;
    }

    public HttpSessionContext getSessionContext() {
        return null; // deprecated
    }

    public synchronized Object getAttribute(String name) {
        return attributes.get(name);
    }

    public Object getValue(String name) {
        return getAttribute(name);
    }

    public synchronized Enumeration getAttributeNames() {
        return new IteratorEnumeration(attributes.keySet());
    }

    public synchronized String[] getValueNames() {
        List list = new ArrayList(attributes.keySet());
        String[] ret = new String[list.size()];
        list.toArray(ret);
        return ret;
    }

    public synchronized void setAttribute(String name, Object value) {
        Object oldValue = attributes.put(name, value);
        HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, value);
        if (oldValue != null && oldValue instanceof HttpSessionBindingListener) {
            ((HttpSessionBindingListener) oldValue).valueUnbound(event);
        }
        if (value instanceof HttpSessionBindingListener) {
            ((HttpSessionBindingListener) value).valueBound(event);
        }
        // Notify context session attribute listeners
        for (HttpSessionAttributeListener l : context.sessionAttributeListeners) {
            if (oldValue == null) {
                l.attributeAdded(event);
            } else {
                l.attributeReplaced(event);
            }
        }
    }

    public void putValue(String name, Object value) {
        setAttribute(name, value);
    }

    public synchronized void removeAttribute(String name) {
        Object oldValue = attributes.remove(name);
        if (oldValue != null) {
            HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, oldValue);
            if (oldValue instanceof HttpSessionBindingListener) {
                ((HttpSessionBindingListener) oldValue).valueUnbound(event);
            }
            for (HttpSessionAttributeListener l : context.sessionAttributeListeners) {
                l.attributeRemoved(event);
            }
        }
    }

    public void removeValue(String name) {
        removeAttribute(name);
    }

    public void invalidate() {
        HttpSessionEvent event = new HttpSessionEvent(this);
        for (HttpSessionListener l : context.sessionListeners) {
            l.sessionDestroyed(event);
        }
        synchronized (context.sessions) {
            context.sessions.remove(id);
        }
    }

    public boolean isNew() {
        return lastAccessedTime == creationTime;
    }

}
