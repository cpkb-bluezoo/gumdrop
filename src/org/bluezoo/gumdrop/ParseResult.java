/*
 * ParseResult.java
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

package org.bluezoo.gumdrop;

import java.util.Collection;

/**
 * Result of parsing a configuration file.
 * Provides access to the component registry and convenience methods
 * for retrieving specific component types.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ParseResult {
    
    private final ComponentRegistry registry;
    
    /**
     * Creates a new parse result.
     * 
     * @param registry the component registry containing all parsed components
     */
    public ParseResult(ComponentRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("Registry cannot be null");
        }
        this.registry = registry;
    }
    
    /**
     * Returns the component registry.
     * 
     * @return the registry
     */
    public ComponentRegistry getRegistry() {
        return registry;
    }
    
    /**
     * Returns all server components.
     * This is a convenience method for retrieving all servers to pass to SelectorLoop.
     * 
     * @return collection of all Server instances
     */
    public Collection<Server> getServers() {
        return registry.getComponentsOfType(Server.class);
    }
    
    /**
     * Gets a specific component by ID.
     * 
     * @param id the component ID
     * @param type the expected type
     * @return the component instance
     */
    public <T> T getComponent(String id, Class<T> type) {
        return registry.getComponent(id, type);
    }
    
    /**
     * Gets all components of a specific type.
     * 
     * @param type the component type
     * @return collection of all matching components
     */
    public <T> Collection<T> getComponentsOfType(Class<T> type) {
        return registry.getComponentsOfType(type);
    }
}

