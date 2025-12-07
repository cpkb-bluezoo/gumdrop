/*
 * MapValue.java
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a map value in component configuration.
 * Values can be simple strings, component references, or inline components.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MapValue {
    
    private final Map<Object, Object> entries = new LinkedHashMap<>();
    
    /**
     * Adds an entry to the map.
     * 
     * @param key the key (typically a String)
     * @param value the value (String, ComponentReference, or ComponentDefinition)
     */
    public void put(Object key, Object value) {
        entries.put(key, value);
    }
    
    /**
     * Returns all entries in the map.
     * 
     * @return the map entries
     */
    public Map<Object, Object> getEntries() {
        return entries;
    }
    
    @Override
    public String toString() {
        return "MapValue{entries=" + entries.size() + "}";
    }
}

