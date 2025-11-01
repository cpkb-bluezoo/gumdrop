/*
 * ListenerDef.java
 * Copyright (C) 2005 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.servlet;

import java.util.EventListener;

/**
 * Context listener definition.
 * This corresponds to a <code>listener</code> element in the deployment
 * descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ListenerDef implements Description {

    Context context;

    // Description
    String description;
    String displayName;
    String smallIcon;
    String largeIcon;

    String className; // listener-class

    void init(String className) {
        this.className = className;
    }

    EventListener newInstance() {
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        ClassLoader contextLoader = context.getContextClassLoader();
        try {
            thread.setContextClassLoader(contextLoader);
            Class t = contextLoader.loadClass(className);
            return (EventListener) t.newInstance();
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            RuntimeException e2 = new RuntimeException("error instantiating " + className);
            e2.initCause(e);
            throw e2;
        } finally {
            thread.setContextClassLoader(loader);
        }
    }

    // -- Description --

    @Override public String getDescription() {
        return description;
    }

    @Override public void setDescription(String description) {
        this.description = description;
    }

    @Override public String getDisplayName() {
        return displayName;
    }

    @Override public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override public String getSmallIcon() {
        return smallIcon;
    }

    @Override public void setSmallIcon(String smallIcon) {
        this.smallIcon = smallIcon;
    }

    @Override public String getLargeIcon() {
        return largeIcon;
    }

    @Override public void setLargeIcon(String largeIcon) {
        this.largeIcon = largeIcon;
    }

}
