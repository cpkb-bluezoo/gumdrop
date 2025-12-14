/*
 * SessionContext.java
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

import java.util.Collection;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionListener;

/**
 * Interface providing session management services from a servlet context.
 *
 * <p>This interface is implemented by the servlet context to allow the
 * session package to work with contexts without a direct dependency on
 * the Context class. It provides access to session configuration,
 * listeners, and context identification for cluster routing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface SessionContext extends ServletContext {

    /**
     * Returns the session timeout in seconds.
     *
     * @return the session timeout, or -1 if sessions never expire
     */
    int getSessionTimeout();

    /**
     * Returns whether sessions in this context are distributable.
     * Distributable contexts have their sessions replicated across
     * the cluster.
     *
     * @return true if sessions are distributable
     */
    boolean isDistributable();

    /**
     * Returns the digest that uniquely identifies this context.
     * Used for routing cluster messages to the correct context.
     *
     * @return the context digest (typically MD5 of context path)
     */
    byte[] getContextDigest();

    /**
     * Returns the class loader for this context.
     * Used for deserializing session attributes that may contain
     * application-specific classes.
     *
     * @return the context class loader
     */
    ClassLoader getContextClassLoader();

    /**
     * Returns the session attribute listeners registered with this context.
     *
     * @return collection of session attribute listeners
     */
    Collection<HttpSessionAttributeListener> getSessionAttributeListeners();

    /**
     * Returns the session lifecycle listeners registered with this context.
     *
     * @return collection of session listeners
     */
    Collection<HttpSessionListener> getSessionListeners();

    /**
     * Returns the session activation listeners registered with this context.
     *
     * @return collection of session activation listeners
     */
    Collection<HttpSessionActivationListener> getSessionActivationListeners();

}

