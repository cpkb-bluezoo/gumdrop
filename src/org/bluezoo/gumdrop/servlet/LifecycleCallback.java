/*
 * LifecycleCallback.java
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

package org.bluezoo.gumdrop.servlet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.logging.Level;

/**
 * Declaration of a lifecycle callback. This corresponds to either a
 * <code>post-construct</code> or <code>pre-destroy</code> element in a web
 * application deployment descriptor, and specifies a method that should be
 * called when the context is initialized or destroyed, respectively.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class LifecycleCallback {

    String className; // lifecycle-callback-class
    String methodName; // lifecycle-callback-method

    /**
     * This will be invoked within the context classloader.
     */
    void execute() {
        try {
            Class t = Class.forName(className);
            Method m = t.getMethod(methodName);
            Object o = t.newInstance();
            m.invoke(o);
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            String message = Context.L10N.getString("err.bad_lifecycle_callback");
            message = MessageFormat.format(message, className, methodName);
            Context.LOGGER.log(Level.SEVERE, message, e);
        }
    }

}
