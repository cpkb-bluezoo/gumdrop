/*
 * EntityDeclaration.java
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
 * Represents an entity declaration in a DTD.
 * 
 * <p>Entities can be:
 * <ul>
 * <li><b>General entities</b>: Referenced as &amp;name;</li>
 * <li><b>Parameter entities</b>: Referenced as %name; (DTD only)</li>
 * </ul>
 * 
 * <p>And can be:
 * <ul>
 * <li><b>Internal</b>: Contains replacement text directly</li>
 * <li><b>External parsed</b>: References external XML content</li>
 * <li><b>External unparsed</b>: References non-XML data with NDATA notation</li>
 * </ul>
 * 
 * <p>Examples:
 * <pre>
 * &lt;!ENTITY copyright "Copyright © 2025"&gt;              (internal general)
 * &lt;!ENTITY chapter1 SYSTEM "chap1.xml"&gt;               (external parsed general)
 * &lt;!ENTITY logo SYSTEM "logo.gif" NDATA gif&gt;          (external unparsed)
 * &lt;!ENTITY % common SYSTEM "common.dtd"&gt;              (external parsed parameter)
 * </pre>
 * 
 * <p>For internal entities, the replacement text is stored as a sequence of
 * {@link String} (literal text) and {@link GeneralEntityReference} (entity
 * references that must be expanded later). This allows entity references in
 * entity values to refer to entities not yet declared.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class EntityDeclaration {
    
    /** The entity name */
    String name;
    
    /** True if this is a parameter entity (%) */
    boolean isParameter;
    
    /**
     * Replacement text for internal entities (null for external).
     * Each element is either a String (literal text) or a GeneralEntityReference
     * (entity reference to be expanded when the entity is resolved).
     */
    List<Object> replacementText;
    
    /**
     * True if the entity value contains character references (e.g., &#60; or &lt;).
     * When true, markup delimiters in the expanded text came from character references
     * and should be treated as literal data, not as markup (XML 1.0 § 4.4.8 bypass rule).
     */
    boolean containsCharacterReferences = false;
    
    /**
     * True if the entity value contains character references that expand to RestrictedChar
     * in XML 1.1 (e.g., &amp;#x0C;). When true and in XML 1.1 context, these characters should
     * be allowed during retokenization even though they're normally illegal.
     */
    boolean containsRestrictedCharFromCharRef = false;
    
    /**
     * True if the entity value contains literal markup start characters (&lt;) in the source.
     * When false and containsCharacterReferences is true, the entity value is pure character
     * data and should not be re-tokenized.
     */
    boolean containsLiteralMarkup = false;
    
    /** External ID for external entities (null for internal) */
    ExternalID externalID;
    
    /** Notation name for unparsed entities (null for parsed) */
    String notationName;
    
    /**
     * The systemId of the context where this entity was declared.
     * Used as base URI for resolving relative systemIds in the entity's replacement text.
     * This is important when an internal entity is declared in an external entity and 
     * contains references to other external entities with relative paths.
     */
    String declarationBaseURI;
    
    /**
     * Creates an entity declaration.
     */
    public EntityDeclaration() {
    }
    
    /**
     * Returns true if this is an internal entity (has replacement text).
     * 
     * @return true if internal
     */
    public boolean isInternal() {
        return replacementText != null;
    }
    
    /**
     * Returns true if this is an external entity (has external ID).
     * 
     * @return true if external
     */
    public boolean isExternal() {
        return externalID != null;
    }
    
    /**
     * Returns true if this is a parsed entity (can be parsed as XML).
     * Internal entities and external entities without NDATA are parsed.
     * 
     * @return true if parsed
     */
    public boolean isParsed() {
        return notationName == null;
    }
    
    /**
     * Returns true if this is an unparsed entity (has NDATA notation).
     * Only external general entities can be unparsed.
     * 
     * @return true if unparsed
     */
    public boolean isUnparsed() {
        return notationName != null;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!ENTITY ");
        if (isParameter) {
            sb.append("% ");
        }
        sb.append(name).append(" ");
        
        if (isInternal()) {
            sb.append("\"");
            for (Object part : replacementText) {
                sb.append(part); // String.toString() or GeneralEntityReference.toString()
            }
            sb.append("\"");
        } else {
            sb.append(externalID);
            if (notationName != null) {
                sb.append(" NDATA ").append(notationName);
            }
        }
        sb.append(">");
        
        return sb.toString();
    }
}

