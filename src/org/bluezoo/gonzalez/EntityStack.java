/*
 * EntityStack.java
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

import java.util.ArrayDeque;
import java.util.List;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Stack of entity expansion entries with integrated expansion logic.
 * 
 * <p>Combines entity expansion state tracking with context-aware expansion logic.
 * This class is responsible for:
 * <ul>
 * <li>Managing the stack of nested entity expansions ({@link EntityStackEntry})</li>
 * <li>Tracking XML version inheritance across entity boundaries</li>
 * <li>Detecting circular entity references (by name and systemId)</li>
 * <li>Validating entity references based on expansion context ({@link EntityExpansionContext})</li>
 * <li>Expanding entity values with nested references</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class EntityStack extends ArrayDeque<EntityStackEntry> {
    
    private static final long serialVersionUID = 1L;
    
    /** DTD parser for entity lookups */
    private final DTDParser dtdParser;
    
    /** Locator for error reporting */
    private final Locator locator;
    
    /**
     * Creates an entity stack.
     * 
     * @param dtdParser the DTD parser for entity lookups
     * @param locator the locator for error reporting
     */
    EntityStack(DTDParser dtdParser, Locator locator) {
        super();
        this.dtdParser = dtdParser;
        this.locator = locator;
        // Initialize with document entity (XML 1.0 by default)
        push(new EntityStackEntry(false));
    }
    
    /**
     * Gets the XML version flag from the top entity on the stack.
     * 
     * @return true if current entity is XML 1.1, false if XML 1.0
     */
    boolean peekIsXML11() {
        EntityStackEntry top = peek();
        return top != null ? top.xml11 : false;
    }
    
    /**
     * Updates the XML version flag for the current entity.
     * Called when an XML or text declaration is parsed.
     * 
     * @param xml11 true if XML 1.1, false if XML 1.0
     */
    void xmlVersion(boolean xml11) {
        EntityStackEntry top = peek();
        if (top != null) {
            top.xml11 = xml11;
        }
    }
    
    /**
     * Resets the stack to its initial state (document entity only).
     */
    void reset() {
        clear();
        push(new EntityStackEntry(false));
    }
    
    /**
     * Checks if an entity is currently being expanded (recursion detection).
     * 
     * @param entityName the entity name to check
     * @param isParameterEntity true for parameter entity, false for general entity
     * @return true if the entity is on the stack
     */
    boolean isExpanding(String entityName, boolean isParameterEntity) {
        for (EntityStackEntry entry : this) {
            if (entry.isParameterEntity == isParameterEntity && 
                entityName.equals(entry.entityName)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Checks if a systemId is currently being resolved (external recursion detection).
     * Checks ALL entries on the stack, including DTD subsets and entity expansions.
     * This prevents circular references when the same external file is referenced
     * multiple times in nested contexts.
     * 
     * @param systemId the system identifier to check
     * @return true if the systemId is currently being resolved
     */
    boolean isResolvingSystemId(String systemId) {
        if (systemId == null) {
            return false;
        }
        for (EntityStackEntry entry : this) {
            if (systemId.equals(entry.systemId)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Expands a general entity reference in the given context.
     * 
     * @param entityName the entity name (without &amp; and ;)
     * @param context the expansion context
     * @return the expanded value (for internal entities) or null (for external entities requiring async resolution)
     * @throws SAXException if expansion fails or entity reference is invalid in context
     */
    String expandGeneralEntity(String entityName, EntityExpansionContext context) 
            throws SAXException {
        // Check if we have a DTD
        if (dtdParser == null) {
            // This should never happen as EntityStack is always created with a dtdParser
            // But if it does, we can't use fatalError, so throw directly
            throw new SAXParseException(
                "General entity reference '&" + entityName + ";' used but no DTD present",
                locator);
        }
        
        // Look up the entity declaration
        EntityDeclaration entity = dtdParser.getGeneralEntity(entityName);
        if (entity == null) {
            // WFC: Entity Declared (Section 4.1)
            // "For a well-formed document, the Name given in the entity reference must match
            // that in an entity declaration"
            throw dtdParser.fatalError(
                "General entity reference '&" + entityName + ";' used but entity not declared",
                locator);
        }
        
        // Check circular reference
        if (isExpanding(entityName, false)) {
            // WFC: No Recursion (Section 4.4.4)
            // "A parsed entity must not contain a recursive reference to itself"
            throw dtdParser.fatalError(
                "Circular entity reference detected: &" + entityName + ";",
                locator);
        }
        
        // Context-specific validation
        switch (context) {
            case ATTRIBUTE_VALUE:
                // External entities forbidden in attribute values
                if (entity.isExternal()) {
                    // WFC: Entity Declared (Section 4.1) - external entities not allowed in attribute values
                    throw dtdParser.fatalError(
                        "External entity reference '&" + entityName + ";' is forbidden in attribute values",
                        locator);
                }
                // Unparsed entities forbidden in attribute values
                if (entity.isUnparsed()) {
                    // WFC: Entity Declared (Section 4.1) - unparsed entities not allowed in attribute values
                    throw dtdParser.fatalError(
                        "Unparsed entity reference '&" + entityName + ";' is forbidden in attribute values",
                        locator);
                }
                break;
                
            case CONTENT:
                // Unparsed entities forbidden in content
                if (entity.isUnparsed()) {
                    // WFC: Entity Declared (Section 4.1) - unparsed entities not allowed in content
                    throw dtdParser.fatalError(
                        "Unparsed entity reference '&" + entityName + ";' is forbidden in content",
                        locator);
                }
                // External entities allowed but require async resolution
                if (entity.isExternal()) {
                    // Check for systemId recursion
                    if (entity.externalID != null && entity.externalID.systemId != null &&
                        isResolvingSystemId(entity.externalID.systemId)) {
                        // WFC: No Recursion (Section 4.4.4) - circular external entity reference
                        throw dtdParser.fatalError(
                            "Circular external entity reference detected: &" + entityName + "; via systemId: " + 
                            entity.externalID.systemId,
                            locator);
                    }
                    // Return null to signal async resolution needed
                    return null;
                }
                break;
                
            case ENTITY_VALUE:
                // In entity values, external entities are forbidden
                if (entity.isExternal()) {
                    // WFC: Entity Declared (Section 4.1) - external entities not allowed in entity values
                    throw dtdParser.fatalError(
                        "External entity reference in entity value is forbidden",
                        locator);
                }
                // Unparsed entities forbidden in entity values
                if (entity.isUnparsed()) {
                    // WFC: Entity Declared (Section 4.1) - unparsed entities not allowed in entity values
                    throw dtdParser.fatalError(
                        "Unparsed entity reference '&" + entityName + ";' is forbidden in entity values",
                        locator);
                }
                break;
                
            default:
                throw dtdParser.fatalError(
                    "General entity references not allowed in context: " + context,
                    locator);
        }
        
        // Push onto stack before expanding (for recursion detection during value expansion)
        String publicId = entity.externalID != null ? entity.externalID.publicId : null;
        String systemId = entity.externalID != null ? entity.externalID.systemId : null;
        push(new EntityStackEntry(
            entityName,
            publicId,
            systemId,
            false, // isParameterEntity
            peekIsXML11(),
            -1, // element depth not relevant for general entities
            null, // dtdParserState not relevant for general entities
            -1)); // contentModelDepth not relevant for general entities
        
        try {
            // Expand internal entity
            return expandEntityValue(entity.replacementText, context);
        } finally {
            // Pop from stack
            pop();
        }
    }
    
    /**
     * Expands a parameter entity reference in the given context.
     * 
     * <p>Parameter entities can only be expanded in DTD and ENTITY_VALUE contexts.
     * They are forbidden in element content and attribute values.
     * 
     * @param entityName the parameter entity name (without % and ;)
     * @param context the expansion context
     * @return the expanded value (for internal entities) or null (for external entities requiring async resolution)
     * @throws SAXException if expansion fails or entity reference is invalid in context
     */
    String expandParameterEntity(String entityName, EntityExpansionContext context)
            throws SAXException {
        // Check for DTD
        if (dtdParser == null) {
            // This should never happen as EntityStack is always created with a dtdParser
            // But if it does, we can't use fatalError, so throw directly
            throw new SAXParseException(
                "Parameter entity reference '%" + entityName + ";' used but no DTD present",
                locator);
        }
        
        // Parameter entities only allowed in DTD and ENTITY_VALUE contexts
        if (context != EntityExpansionContext.DTD && context != EntityExpansionContext.ENTITY_VALUE) {
            // WFC: Entity Declared (Section 4.1) - parameter entities only in DTD and entity values
            throw dtdParser.fatalError(
                "Parameter entity reference '%" + entityName + ";' is forbidden in context: " + context,
                locator);
        }
        
        // Look up entity
        EntityDeclaration entity = dtdParser.getParameterEntity(entityName);
        if (entity == null) {
            // WFC: Entity Declared (Section 4.1)
            // "For a well-formed document, the Name given in the entity reference must match
            // that in an entity declaration"
            throw dtdParser.fatalError(
                "Undefined parameter entity: %" + entityName + ";",
                locator);
        }
        
        // Check circular reference
        if (isExpanding(entityName, true)) {
            // WFC: No Recursion (Section 4.4.4)
            // "A parsed entity must not contain a recursive reference to itself"
            throw dtdParser.fatalError(
                "Circular parameter entity reference detected: %" + entityName + ";",
                locator);
        }
        
        // External entities in DTD context require async resolution
        if (entity.isExternal() && context == EntityExpansionContext.DTD) {
            // Check for systemId recursion
            if (entity.externalID != null && entity.externalID.systemId != null &&
                isResolvingSystemId(entity.externalID.systemId)) {
                // WFC: No Recursion (Section 4.4.4) - circular external parameter entity reference
                throw dtdParser.fatalError(
                    "Circular external parameter entity reference detected: %" + entityName + "; via systemId: " +
                    entity.externalID.systemId,
                    locator);
            }
            // Return null to signal async resolution needed
            return null;
        }
        
        // In ENTITY_VALUE context, external parameter entities are forbidden
        if (entity.isExternal() && context == EntityExpansionContext.ENTITY_VALUE) {
            // WFC: Entity Declared (Section 4.1) - external parameter entities not allowed in entity values
            throw dtdParser.fatalError(
                "External parameter entity reference in entity value is forbidden: %" + entityName + ";",
                locator);
        }
        
        // Push onto stack before expanding (for recursion detection during value expansion)
        String publicId = entity.externalID != null ? entity.externalID.publicId : null;
        String systemId = entity.externalID != null ? entity.externalID.systemId : null;
        push(new EntityStackEntry(
            entityName,
            publicId,
            systemId,
            true, // isParameterEntity
            peekIsXML11(),
            -1, // element depth not relevant for parameter entities
            null, // dtdParserState - for entity values, state tracking not needed
            -1)); // contentModelDepth - for entity values, not needed
        
        try {
            // Expand internal entity - when expanding a parameter entity's value, 
            // we're still in ENTITY_VALUE context
            return expandEntityValue(entity.replacementText, EntityExpansionContext.ENTITY_VALUE);
        } finally {
            // Pop from stack
            pop();
        }
    }
    
    /**
     * Recursively expands an entity value, processing both literal strings
     * and nested entity references.
     * 
     * <p>This method allows expansion of entity values (e.g., default
     * attribute values from DTD) without requiring an entity name lookup.
     * 
     * @param replacementText the entity value as list of String and entity references
     * @param context the expansion context
     * @return the fully expanded value
     * @throws SAXException if expansion fails
     */
    String expandEntityValue(List<Object> replacementText, EntityExpansionContext context) 
            throws SAXException {
        if (replacementText == null || replacementText.isEmpty()) {
            return "";
        }
        
        StringBuilder result = new StringBuilder();
        
        for (Object part : replacementText) {
            if (part instanceof String) {
                // Literal text
                result.append((String) part);
            } else if (part instanceof GeneralEntityReference) {
                // Nested general entity reference
                GeneralEntityReference ref = (GeneralEntityReference) part;
                try {
                    String expanded = expandGeneralEntity(ref.name, context);
                    if (expanded == null) {
                        // External entity requiring async resolution
                        // WFC: Entity Declared (Section 4.1) - external entities not allowed in entity values
                        throw dtdParser.fatalError(
                            "External entity reference in entity value requires async resolution",
                            locator);
                    }
                    result.append(expanded);
                } catch (SAXParseException e) {
                    // If entity doesn't exist, it might be inside a CDATA section that was
                    // incorrectly tokenized during DTD parsing (e.g., "<![CDATA[&foo;]]>").
                    // Check if we're in a CDATA-like context by looking at surrounding text.
                    if (e.getMessage() != null && e.getMessage().contains("not declared")) {
                        // Check if previous text ends with "[CDATA[" or similar
                        String resultSoFar = result.toString();
                        if (resultSoFar.endsWith("[CDATA[") || 
                            resultSoFar.contains("<![CDATA[")) {
                            // Likely inside CDATA section - reconstruct as literal
                            result.append("&").append(ref.name).append(";");
                        } else {
                            // Not in CDATA context - rethrow the error
                            throw e;
                        }
                    } else {
                        throw e;
                    }
                }
            } else if (part instanceof ParameterEntityReference) {
                // Nested parameter entity reference
                ParameterEntityReference ref = (ParameterEntityReference) part;
                // Use the same context as the outer expansion - this allows external parameter
                // entities in DTD context but forbids them in ENTITY_VALUE context
                String expanded = expandParameterEntity(ref.name, context);
                if (expanded == null) {
                    // External parameter entity in nested expansion
                    if (context == EntityExpansionContext.DTD) {
                        // In DTD context, external PEs are allowed but require special handling
                        // Return null to signal to the caller that async resolution is needed
                        return null;
                    } else {
                        // In ENTITY_VALUE context, external PEs are forbidden
                        throw dtdParser.fatalError(
                            "External parameter entity reference %" + ref.name + 
                            "; is forbidden in entity values (ENTITY_VALUE context)",
                            locator);
                    }
                }
                result.append(expanded);
            } else {
                throw dtdParser.fatalError(
                    "Unexpected entity value part type: " + part.getClass().getName(),
                    locator);
            }
        }
        
        return result.toString();
    }
    
    /**
     * Converts a replacement text list to its string representation,
     * with entity references unexpanded (as &name; or %name;).
     * Package-private to allow DTDParser access.
     * 
     * @param replacementText the replacement text list
     * @return the string representation
     */
    String replacementTextToString(List<Object> replacementText) {
        if (replacementText == null || replacementText.isEmpty()) {
            return "";
        }
        
        StringBuilder result = new StringBuilder();
        for (Object part : replacementText) {
            result.append(part.toString()); // String or GeneralEntityReference/ParameterEntityReference
        }
        return result.toString();
    }
}

