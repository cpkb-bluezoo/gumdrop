/*
 * ComponentDefinition.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a component definition parsed from configuration.
 * Contains the component class and property values to be injected.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ComponentDefinition {
    
    private final String id;
    private final Class<?> componentClass;
    private final List<PropertyDefinition> properties = new ArrayList<>();
    private boolean singleton = true;
    
    /**
     * Creates a new component definition.
     * 
     * @param id the component identifier (null for anonymous components)
     * @param componentClass the component class
     */
    public ComponentDefinition(String id, Class<?> componentClass) {
        if (componentClass == null) {
            throw new IllegalArgumentException("Component class cannot be null");
        }
        this.id = id;
        this.componentClass = componentClass;
    }
    
    /**
     * Returns the component identifier.
     * 
     * @return the ID, or null for anonymous components
     */
    public String getId() {
        return id;
    }
    
    /**
     * Returns the component class.
     * 
     * @return the class
     */
    public Class<?> getComponentClass() {
        return componentClass;
    }
    
    /**
     * Returns the list of properties to be injected.
     * 
     * @return the property definitions
     */
    public List<PropertyDefinition> getProperties() {
        return properties;
    }
    
    /**
     * Returns whether this component is a singleton.
     * 
     * @return true if singleton (default), false for prototype scope
     */
    public boolean isSingleton() {
        return singleton;
    }
    
    /**
     * Sets whether this component is a singleton.
     * 
     * @param singleton true for singleton scope, false for prototype
     */
    public void setSingleton(boolean singleton) {
        this.singleton = singleton;
    }
    
    /**
     * Adds a property to be injected.
     * 
     * @param property the property definition
     */
    public void addProperty(PropertyDefinition property) {
        properties.add(property);
    }
    
    @Override
    public String toString() {
        return "ComponentDefinition{id=" + id + ", class=" + componentClass.getName() + 
               ", properties=" + properties.size() + "}";
    }
}

