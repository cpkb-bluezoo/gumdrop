/*
 * LifecycleCallback.java
 * Copyright (C) 2025 Chris Burdess
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
