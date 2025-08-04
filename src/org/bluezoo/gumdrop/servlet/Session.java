/*
 * Session.java
 * Copyright (C) 2005 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.util.IteratorEnumeration;

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
    final String id;
    final long creationTime;
    long lastAccessedTime;
    int maxInactiveInterval;
    final Map<String,Object> attributes;

    Session(Context context, String id) {
        this.context = context;
        this.id = id;
        creationTime = System.currentTimeMillis();
        lastAccessedTime = creationTime;
        maxInactiveInterval = context.sessionTimeout;
        attributes = new LinkedHashMap<>();
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
