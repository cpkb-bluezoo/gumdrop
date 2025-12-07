/*
 * ListValue.java
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
 * Represents a list value in component configuration.
 * Items can be simple values, component references, or inline components.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ListValue {
    
    private final List<Object> items = new ArrayList<>();
    
    /**
     * Adds an item to the list.
     * 
     * @param item the item (String, ComponentReference, or ComponentDefinition)
     */
    public void addItem(Object item) {
        items.add(item);
    }
    
    /**
     * Returns all items in the list.
     * 
     * @return the list of items
     */
    public List<Object> getItems() {
        return items;
    }
    
    @Override
    public String toString() {
        return "ListValue{items=" + items.size() + "}";
    }
}

