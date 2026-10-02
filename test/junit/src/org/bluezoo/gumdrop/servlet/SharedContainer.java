/*
 * SharedContainer.java
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

/**
 * Provides the one {@link Container} whose {@link Container#init()} installs
 * the JVM-wide {@code resource:} URL handler. Contexts that need their
 * resource URLs to resolve must be registered with this container, because
 * the handler is bound to whichever container initialises first.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SharedContainer {

    private static Container instance;

    private SharedContainer() {
    }

    static synchronized Container get() {
        if (instance == null) {
            instance = new Container();
            instance.init();
        }
        return instance;
    }
}
