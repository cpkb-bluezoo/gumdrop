/*
 * JndiContext.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import java.util.Collection;

/**
 * Interface for servlet context JNDI operations.
 *
 * <p>This interface defines the operations that a servlet context
 * must provide for JNDI resource management. It is implemented by
 * the Context class to allow the JNDI package to operate without
 * a direct dependency on Context.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface JndiContext {

    /**
     * Returns the collection of JNDI resources defined for this context.
     */
    Collection<Resource> getResources();

    /**
     * Returns the collection of injectable references defined for this context.
     */
    Collection<Injectable> getInjectables();

    /**
     * Returns the classloader for this context.
     */
    ClassLoader getContextClassLoader();

    /**
     * Strips the "java:comp/env/" prefix from a JNDI name if present.
     *
     * @param name the JNDI name
     * @return the name without the prefix
     */
    static String stripCompEnv(String name) {
        if (name != null && name.startsWith("java:comp/env/")) {
            return name.substring(14);
        }
        return name;
    }

}

