/*
 * ComponentReference.java
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
 * Represents a reference to another component using # fragment syntax.
 * This is resolved to the actual component instance during dependency injection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ComponentReference {
    
    private final String refId;
    
    /**
     * Creates a new component reference.
     * 
     * @param refId the component identifier (without the # prefix)
     * @throws IllegalArgumentException if refId is null or empty
     */
    public ComponentReference(String refId) {
        if (refId == null || refId.isEmpty()) {
            throw new IllegalArgumentException("Reference ID cannot be null or empty");
        }
        this.refId = refId;
    }
    
    /**
     * Returns the referenced component ID.
     * 
     * @return the component ID (without # prefix)
     */
    public String getRefId() {
        return refId;
    }
    
    @Override
    public String toString() {
        return "#" + refId;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ComponentReference)) return false;
        ComponentReference other = (ComponentReference) obj;
        return refId.equals(other.refId);
    }
    
    @Override
    public int hashCode() {
        return refId.hashCode();
    }
}

