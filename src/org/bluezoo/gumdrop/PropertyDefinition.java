/*
 * PropertyDefinition.java
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

/**
 * Represents a property to be injected into a component.
 * The value can be a simple string, a component reference, a list, a map,
 * or an inline component definition.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PropertyDefinition {
    
    private final String name;
    private final Object value;
    
    /**
     * Creates a new property definition.
     * 
     * @param name the property name
     * @param value the property value (String, ComponentReference, ListValue, MapValue, or ComponentDefinition)
     */
    public PropertyDefinition(String name, Object value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Property name cannot be null or empty");
        }
        this.name = name;
        this.value = value;
    }
    
    /**
     * Returns the property name.
     * 
     * @return the name
     */
    public String getName() {
        return name;
    }
    
    /**
     * Returns the property value.
     * 
     * @return the value
     */
    public Object getValue() {
        return value;
    }
    
    @Override
    public String toString() {
        return "PropertyDefinition{name=" + name + ", value=" + value + "}";
    }
}

