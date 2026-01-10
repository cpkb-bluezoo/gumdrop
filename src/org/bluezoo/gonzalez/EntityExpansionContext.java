/*
 * EntityExpansionContext.java
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
 * Enumeration of entity expansion contexts.
 * 
 * <p>Different contexts have different rules for entity expansion per
 * XML 1.0 specification section 4.4:
 * 
 * <ul>
 * <li><b>CONTENT</b>: Element content - allows internal and external general entities</li>
 * <li><b>ATTRIBUTE_VALUE</b>: Attribute values - allows only internal general entities</li>
 * <li><b>ENTITY_VALUE</b>: Entity replacement text - allows only internal entities</li>
 * <li><b>DTD</b>: DTD declarations - allows parameter entities</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
enum EntityExpansionContext {
    /**
     * Element content context.
     * Allows both internal and external general entities.
     * External entities require async resolution.
     */
    CONTENT,
    
    /**
     * Attribute value context.
     * Allows only internal general entities.
     * External and unparsed entities are forbidden.
     */
    ATTRIBUTE_VALUE,
    
    /**
     * Entity value context (replacement text).
     * Allows only internal entities (both general and parameter).
     * External entities are forbidden.
     */
    ENTITY_VALUE,
    
    /**
     * DTD context (DOCTYPE declarations).
     * Allows parameter entities (internal and external).
     * External parameter entities require async resolution.
     */
    DTD
}

