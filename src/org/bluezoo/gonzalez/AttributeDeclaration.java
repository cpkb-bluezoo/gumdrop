/*
 * AttributeDeclaration.java
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

import java.util.List;

/**
 * Represents an attribute declaration from the DTD.
 *
 * <p>Attribute declarations define the allowed attributes for an element,
 * their types, default values, and whether they are required, implied, or fixed.
 *
 * <p>For default values, the value is stored as a sequence of {@link String}
 * (literal text) and {@link GeneralEntityReference} (entity references that must
 * be expanded later). This allows entity references in default values to refer to
 * entities not yet declared, similar to entity values.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class AttributeDeclaration {

    /**
     * The attribute name.
     */
    String name;

    /**
     * The attribute type (CDATA, ID, IDREF, IDREFS, ENTITY, ENTITIES,
     * NMTOKEN, NMTOKENS, NOTATION, or an enumeration).
     */
    String type;
    
    /**
     * For enumeration and NOTATION types, the list of allowed values.
     * Null for non-enumeration types.
     */
    List<String> enumeration;

    /**
     * The default value mode: Token.REQUIRED, Token.IMPLIED, Token.FIXED, or null for default value.
     */
    Token mode;

    /**
     * The default value, or null if not specified or if mode is #REQUIRED or #IMPLIED.
     * Each element is either a String (literal text) or a GeneralEntityReference
     * (entity reference to be expanded when the attribute is used).
     */
    List<Object> defaultValue;
    
    /**
     * True if this declaration came from the external DTD subset.
     * Used for VC: Standalone Document Declaration validation.
     */
    boolean fromExternalSubset;

    /**
     * Returns true if this attribute is required.
     * @return true if this attribute is required
     */
    public boolean isRequired() {
        return mode == Token.REQUIRED;
    }

    /**
     * Returns true if this attribute has a default value.
     * @return true if this attribute has a default value
     */
    public boolean hasDefault() {
        return defaultValue != null && mode == null;
    }

    /**
     * Returns true if this attribute has a fixed value.
     * @return true if this attribute has a fixed value
     */
    public boolean isFixed() {
        return mode == Token.FIXED;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" ").append(type);
        if (mode != null) {
            sb.append(" #").append(mode);
        }
        if (defaultValue != null) {
            sb.append(" \"");
            for (Object part : defaultValue) {
                sb.append(part); // String.toString() or GeneralEntityReference.toString()
            }
            sb.append("\"");
        }
        return sb.toString();
    }
}

