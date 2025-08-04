/*
 * ListenerDef.java
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

import java.util.EventListener;

/**
 * Context listener definition.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ListenerDef {

    final Context context;
    String description;
    String displayName;
    String smallIcon;
    String largeIcon;
    String className;

    ListenerDef(Context context) {
        this.context = context;
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

}
