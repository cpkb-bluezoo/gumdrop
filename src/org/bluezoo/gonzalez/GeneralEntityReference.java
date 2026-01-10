/*
 * GeneralEntityReference.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

/**
 * Represents a reference to a general entity that needs to be expanded later.
 * Used in entity values where entity references can refer to entities that
 * haven't been declared yet in the DTD.
 * 
 * <p>Example:
 * <pre>
 * &lt;!ENTITY combined "before &amp;middle; after"&gt;
 * </pre>
 * 
 * <p>The entity value for "combined" would be stored as:
 * <pre>
 * ["before ", GeneralEntityReference("middle"), " after"]
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class GeneralEntityReference {
    
    /** The name of the entity being referenced */
    final String name;
    
    /**
     * Creates a general entity reference.
     * 
     * @param name the entity name (without &amp; and ;)
     */
    public GeneralEntityReference(String name) {
        this.name = name;
    }
    
    @Override
    public String toString() {
        return "&" + name + ";";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof GeneralEntityReference)) return false;
        GeneralEntityReference other = (GeneralEntityReference) obj;
        return name.equals(other.name);
    }
    
    @Override
    public int hashCode() {
        return name.hashCode();
    }
    
}

