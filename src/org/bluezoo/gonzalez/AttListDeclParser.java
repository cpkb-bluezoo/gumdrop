/*
 * AttListDeclParser.java
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

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parser for &lt;!ATTLIST declarations within a DTD.
 * 
 * <p>Grammar: &lt;!ATTLIST Name AttDef* &gt;
 * <p>AttDef: S Name S AttType S DefaultDecl
 * <p>AttType: CDATA | ID | IDREF | IDREFS | ENTITY | ENTITIES | NMTOKEN | NMTOKENS | 
 *             NotationType | Enumeration
 * <p>DefaultDecl: #REQUIRED | #IMPLIED | ((#FIXED S)? AttValue)
 * 
 * <p>This parser is instantiated for each &lt;!ATTLIST declaration and
 * handles tokens until the closing &gt; is encountered. It builds a map
 * of attribute declarations which is then merged into the DTD's overall
 * attribute declarations structure.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class AttListDeclParser {
    
    /**
     * Sub-states for parsing &lt;!ATTLIST declarations.
     * Tracks position within the attribute list declaration to enforce well-formedness.
     */
    private enum State {
        EXPECT_ELEMENT_NAME,    // After &lt;!ATTLIST, expecting element name
        AFTER_ELEMENT_NAME,     // After element name, expecting whitespace
        EXPECT_ATTR_OR_GT,      // Expecting attribute name or &gt;
        AFTER_ATTR_NAME,        // After attribute name, expecting whitespace
        EXPECT_ATTR_TYPE,       // After whitespace, expecting type
        AFTER_ATTR_TYPE,        // After type, expecting whitespace
        EXPECT_DEFAULT_DECL,    // After whitespace, expecting #REQUIRED|#IMPLIED|#FIXED|value
        AFTER_HASH,             // After #, expecting REQUIRED|IMPLIED|FIXED keyword
        AFTER_FIXED,            // After #FIXED, expecting whitespace
        EXPECT_DEFAULT_VALUE,   // After whitespace following #FIXED, expecting quoted value
        AFTER_DEFAULT_VALUE,    // After CDATA value, expecting closing quote
        IN_NOTATION_ENUM        // Inside NOTATION enumeration (name1|name2...)
    }
    
    private State state = State.EXPECT_ELEMENT_NAME;
    private boolean sawWhitespaceAfterAttrType; // Track whitespace before enumeration
    
    /**
     * Current attribute list declaration being parsed.
     * 
     * <p>currentAttlistElement: The element name these attributes belong to
     * <p>currentAttlistMap: Map of attribute name → AttributeDeclaration being built
     * <p>currentAttributeDecl: The current attribute being parsed (added to map when complete)
     * <p>defaultValueBuilder: Accumulates CDATA chunks for default values (asynchronous parsing)
     * 
     * <p>When an attribute is complete, it's added to currentAttlistMap keyed by its name.
     * When GT is encountered, currentAttlistMap is merged into attributeDecls keyed by currentAttlistElement.
     */
    private String currentAttlistElement;
    private Map<String, AttributeDeclaration> currentAttlistMap;
    private AttributeDeclaration currentAttributeDecl;
    private List<Object> defaultValueBuilder;  // List of String and GeneralEntityReference
    private StringBuilder defaultValueTextBuilder;  // For accumulating text segments
    private List<String> enumerationBuilder;  // For accumulating enumeration values
    
    private final DTDParser dtdParser;
    private final Locator locator;
    
    /**
     * True if this ATTLIST declaration is being parsed from the external DTD subset.
     * Used for VC: Standalone Document Declaration validation.
     */
    private final boolean fromExternalSubset;
    
    /**
     * Creates a new parser for a single &lt;!ATTLIST declaration.
     * 
     * @param dtdParser the parent DTD parser (for accessing entities and saving results)
     * @param locator the locator for error reporting
     * @param fromExternalSubset true if parsing from external DTD subset
     */
    AttListDeclParser(DTDParser dtdParser, Locator locator, boolean fromExternalSubset) {
        this.dtdParser = dtdParser;
        this.locator = locator;
        this.fromExternalSubset = fromExternalSubset;
        this.currentAttlistMap = new HashMap<>();
    }
    
    /**
     * Checks if the parser is still expecting or processing the element name.
     * Used by DTDParser to determine if NAME token coalescing should be applied.
     * 
     * @return true if we're in EXPECT_ELEMENT_NAME or AFTER_ELEMENT_NAME state
     */
    boolean isInElementNamePhase() {
        return state == State.EXPECT_ELEMENT_NAME || state == State.AFTER_ELEMENT_NAME;
    }
    
    /**
     * Processes a single token within the &lt;!ATTLIST declaration.
     * Returns true if the declaration is complete (GT was encountered).
     * 
     * @param token the token to process
     * @param data the character buffer for the token (may be null)
     * @return true if the ATTLIST declaration is complete
     * @throws SAXException if a parsing error occurs
     */
    boolean handleToken(Token token, CharBuffer data) throws SAXException {
        switch (state) {
            case EXPECT_ELEMENT_NAME:
                // Must see NAME for element name (whitespace allowed first)
                // In external subset, parameter entity references are allowed and expanded inline
                switch (token) {
                    case S:
                        // Skip whitespace before element name
                        break;
                        
                    case NAME:
                        currentAttlistElement = data.toString();
                        state = State.AFTER_ELEMENT_NAME;
                        break;
                        
                    case PARAMETERENTITYREF:
                        // Parameter entity reference in element name position (external subset only)
                        // The expansion is handled by DTDParser, which will feed the expanded tokens back
                        // We'll receive the expanded NAME token next, so just wait for it
                        // (The expansion happens inline, so we don't need to do anything here)
                        break;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "Attribute list declarations must start with element name"
                        throw fatalError("Expected element name after <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_ELEMENT_NAME:
                // May see whitespace after element name, or GT if no attributes
                // Per XML 1.0 Production [52]: AttlistDecl ::= '<!ATTLIST' S Name AttDef* S? '>'
                // When AttDef* is empty (no attributes), we can go directly to GT
                switch (token) {
                    case S:
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case GT:
                        // Empty ATTLIST (no attributes) - valid per spec
                        // Save the element (even with no attributes) and finish
                        saveCurrentAttlist();
                        return true;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "Whitespace or '>' is required after element name"
                        throw fatalError("Expected whitespace or '>' after element name in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_ATTR_OR_GT:
                // Expecting attribute name or GT to close
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case NAME:
                        // Start new attribute
                        currentAttributeDecl = new AttributeDeclaration();
                        currentAttributeDecl.name = data.toString();
                        currentAttributeDecl.fromExternalSubset = fromExternalSubset;
                        state = State.AFTER_ATTR_NAME;
                        break;
                        
                    case COLON:
                        // Colon is a valid XML 1.0 name (though discouraged by XML Namespaces)
                        currentAttributeDecl = new AttributeDeclaration();
                        currentAttributeDecl.name = ":";
                        currentAttributeDecl.fromExternalSubset = fromExternalSubset;
                        state = State.AFTER_ATTR_NAME;
                        break;
                        
                    case GT:
                        // End of ATTLIST - save and return true to signal completion
                        saveCurrentAttlist();
                        return true;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "Attribute declarations must start with attribute name or end with '>'"
                        throw fatalError("Expected attribute name or '>' in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_ATTR_NAME:
                // Must see whitespace after attribute name
                switch (token) {
                    case S:
                        sawWhitespaceAfterAttrType = false; // Reset for new attribute
                        state = State.EXPECT_ATTR_TYPE;
                        break;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "Whitespace is required after attribute name"
                        throw fatalError("Expected whitespace after attribute name in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_ATTR_TYPE:
                // Expecting attribute type
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case CDATA_TYPE:
                        currentAttributeDecl.type = "CDATA";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case ID:
                        currentAttributeDecl.type = "ID";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case IDREF:
                        currentAttributeDecl.type = "IDREF";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case IDREFS:
                        currentAttributeDecl.type = "IDREFS";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case ENTITY:
                        currentAttributeDecl.type = "ENTITY";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case ENTITIES:
                        currentAttributeDecl.type = "ENTITIES";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case NMTOKEN:
                        currentAttributeDecl.type = "NMTOKEN";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case NMTOKENS:
                        currentAttributeDecl.type = "NMTOKENS";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case NOTATION:
                        currentAttributeDecl.type = "NOTATION";
                        // NOTATION type may be followed by (name1|name2|...)
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case OPEN_PAREN:
                        // Enumeration starting - collect values
                        currentAttributeDecl.type = "ENUMERATION";
                        enumerationBuilder = new ArrayList<>();
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "Attribute types must be valid (CDATA, ID, IDREF, IDREFS, ENTITY, ENTITIES, NMTOKEN, NMTOKENS, NOTATION, or enumeration)"
                        throw fatalError("Invalid attribute type in <!ATTLIST: " + 
                            (data != null ? data.toString() : token.toString()), locator);
                }
                break;
                
            case AFTER_ATTR_TYPE:
                // Must see whitespace after type (or part of enumeration)
                switch (token) {
                    case S:
                        // Whitespace handling depends on context
                        if (enumerationBuilder != null) {
                            // We're inside an enumeration - whitespace is allowed but doesn't end it
                            // Stay in AFTER_ATTR_TYPE waiting for more values or CLOSE_PAREN
                        } else {
                            // Whitespace after type means we're done (no enumeration)
                            sawWhitespaceAfterAttrType = true;
                            state = State.EXPECT_DEFAULT_DECL;
                        }
                        break;
                        
                    case OPEN_PAREN:
                        // Start of enumeration (for NOTATION or after OPEN_PAREN in EXPECT_ATTR_TYPE)
                        // Only NOTATION and ENUMERATION types can have enumerations
                        if (currentAttributeDecl.type != null && 
                            !currentAttributeDecl.type.equals("NOTATION") && 
                            !currentAttributeDecl.type.equals("ENUMERATION")) {
                            // WFC: Attribute List Declaration (Production 52)
                            // "Only NOTATION and ENUMERATION types can have enumerations"
                            throw fatalError(
                                "Attribute type " + currentAttributeDecl.type + " cannot be followed by an enumeration in <!ATTLIST", 
                                locator);
                        }
                        
                        // For NOTATION type, require whitespace before enumeration
                        if ("NOTATION".equals(currentAttributeDecl.type) && !sawWhitespaceAfterAttrType) {
                            // WFC: Attribute List Declaration (Production 52)
                            // "Whitespace is required after NOTATION keyword before enumeration"
                            throw fatalError("Expected whitespace after NOTATION keyword in <!ATTLIST", locator);
                        }
                        if (enumerationBuilder == null) {
                            enumerationBuilder = new ArrayList<>();
                        }
                        // Stay in AFTER_ATTR_TYPE to collect values
                        break;
                        
                    case CLOSE_PAREN:
                        // End of enumeration
                        // Validate that enumeration is not empty
                        if (enumerationBuilder != null && enumerationBuilder.isEmpty()) {
                            // WFC: Attribute List Declaration (Production 52)
                            // "Enumerations must not be empty"
                            throw fatalError("Empty enumeration () not allowed in <!ATTLIST", locator);
                        }
                        if (enumerationBuilder != null) {
                            currentAttributeDecl.enumeration = enumerationBuilder;
                            enumerationBuilder = null;
                        }
                        // Stay in AFTER_ATTR_TYPE waiting for whitespace
                        break;
                        
                    case PIPE:
                        // Separator in enumeration - stay in AFTER_ATTR_TYPE
                        break;
                        
                    case NAME:
                        // Part of enumeration - collect it
                        // Note: Regular enumerations accept Nmtoken (can start with digit)
                        // NOTATION enumerations require Name (cannot start with digit)
                        // Since we're in AFTER_ATTR_TYPE, this is a regular enumeration
                        if (enumerationBuilder != null) {
                            String name = data.toString();
                            
                            // VC: No Duplicate Tokens (Section 3.3.1)
                            // Check for duplicates in enumerated attribute types
                            if (dtdParser.getValidationEnabled() && enumerationBuilder.contains(name)) {
                                dtdParser.reportValidationError(
                                    "Validity Constraint: No Duplicate Tokens (Section 3.3.1). " +
                                    "Duplicate token '" + name + "' in enumerated attribute declaration for element '" + 
                                    currentAttlistElement + "', attribute '" + currentAttributeDecl.name + "'.");
                            }
                            
                            // Regular enumerations accept any Nmtoken, no validation needed
                            enumerationBuilder.add(name);
                        }
                        // Stay in AFTER_ATTR_TYPE
                        break;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "Whitespace is required after attribute type"
                        throw fatalError("Expected whitespace after attribute type in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_DEFAULT_DECL:
                // Expecting #REQUIRED | #IMPLIED | #FIXED | default value
                // Or OPEN_PAREN for NOTATION enumeration
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case OPEN_PAREN:
                        // Start of NOTATION enumeration
                        // Only NOTATION type can have enumeration in this position
                        if (!"NOTATION".equals(currentAttributeDecl.type)) {
                            // WFC: Attribute List Declaration (Production 52)
                            // "Only NOTATION type can have enumeration in this position"
                            throw fatalError(
                                "Unexpected '(' after attribute type " + currentAttributeDecl.type + " in <!ATTLIST " +
                                "(only NOTATION type can have enumeration)", 
                                locator);
                        }
                        enumerationBuilder = new ArrayList<>();
                        state = State.IN_NOTATION_ENUM;
                        break;
                        
                    case HASH:
                        // Hash before keyword - transition to AFTER_HASH to expect keyword
                        state = State.AFTER_HASH;
                        break;
                        
                    case REQUIRED:
                        // #REQUIRED without hash (tokenizer combined them)
                        currentAttributeDecl.mode = Token.REQUIRED;
                        saveCurrentAttribute();
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case IMPLIED:
                        // #IMPLIED without hash (tokenizer combined them)
                        currentAttributeDecl.mode = Token.IMPLIED;
                        saveCurrentAttribute();
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case FIXED:
                        // #FIXED without hash (tokenizer combined them)
                        currentAttributeDecl.mode = Token.FIXED;
                        state = State.AFTER_FIXED;
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Default value starting (no #FIXED) - initialize accumulators
                        defaultValueBuilder = new ArrayList<>();
                        defaultValueTextBuilder = new StringBuilder();
                        state = State.AFTER_DEFAULT_VALUE;
                        break;
                        
                    case CDATA:
                        // Default value chunk - accumulate (may receive multiple CDATA tokens)
                        if (defaultValueBuilder == null) {
                            defaultValueBuilder = new ArrayList<>();
                            defaultValueTextBuilder = new StringBuilder();
                        }
                        defaultValueTextBuilder.append(data.toString());
                        state = State.AFTER_DEFAULT_VALUE;
                        break;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "Default declaration must be #REQUIRED, #IMPLIED, #FIXED, or a value"
                        throw fatalError("Expected default declaration (#REQUIRED, #IMPLIED, #FIXED, or value) in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_HASH:
                // After #, must see REQUIRED | IMPLIED | FIXED keyword
                switch (token) {
                    case REQUIRED:
                        currentAttributeDecl.mode = Token.REQUIRED;
                        saveCurrentAttribute();
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case IMPLIED:
                        currentAttributeDecl.mode = Token.IMPLIED;
                        saveCurrentAttribute();
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case FIXED:
                        currentAttributeDecl.mode = Token.FIXED;
                        state = State.AFTER_FIXED;
                        break;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "After '#', must be REQUIRED, IMPLIED, or FIXED"
                        throw fatalError("Expected REQUIRED, IMPLIED, or FIXED after # in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_FIXED:
                // Must see whitespace after #FIXED
                switch (token) {
                    case S:
                        state = State.EXPECT_DEFAULT_VALUE;
                        break;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "Whitespace is required after #FIXED"
                        throw fatalError("Expected whitespace after #FIXED in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_DEFAULT_VALUE:
                // Expecting quoted default value after #FIXED
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Quote starting value - initialize accumulators
                        defaultValueBuilder = new ArrayList<>();
                        defaultValueTextBuilder = new StringBuilder();
                        state = State.AFTER_DEFAULT_VALUE;
                        break;
                        
                    case CDATA:
                        // Fixed default value chunk - accumulate (may receive multiple CDATA tokens)
                        if (defaultValueBuilder == null) {
                            defaultValueBuilder = new ArrayList<>();
                            defaultValueTextBuilder = new StringBuilder();
                        }
                        defaultValueTextBuilder.append(data.toString());
                        state = State.AFTER_DEFAULT_VALUE;
                        break;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "#FIXED must be followed by a quoted value"
                        throw fatalError("Expected quoted value after #FIXED in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_DEFAULT_VALUE:
                // After default value CDATA, expecting more CDATA chunks, entity refs, or closing quote
                switch (token) {
                    case CDATA:
                    case S:
                        // Additional chunk of default value - accumulate
                        defaultValueTextBuilder.append(data.toString());
                        break;
                        
                    case CHARENTITYREF:
                        // Character reference (already expanded) - e.g., &#60; -> '<'
                        defaultValueTextBuilder.append(data.toString());
                        break;
                        
                    case PREDEFENTITYREF:
                        // Predefined entity reference (already expanded) - e.g., &lt; -> '<'
                        defaultValueTextBuilder.append(data.toString());
                        break;
                        
                    case GENERALENTITYREF:
                        // General entity reference - flush text and add reference
                        if (defaultValueTextBuilder.length() > 0) {
                            defaultValueBuilder.add(defaultValueTextBuilder.toString());
                            defaultValueTextBuilder.setLength(0);
                        }
                        String entityName = data.toString();
                        // WFC: Entity Declared (Section 4.1)
                        // "Entity must be declared before use in attribute default value"
                        EntityDeclaration entity = dtdParser.getGeneralEntity(entityName);
                        if (entity == null) {
                            throw fatalError(
                                "Entity '" + entityName + "' must be declared before use in attribute default value " +
                                "(WFC: Entity Declared)", locator);
                        }
                        // WFC: No External Entity References (Section 4.4.4)
                        // "External entities are forbidden in attribute default values"
                        if (entity.isExternal()) {
                            throw fatalError(
                                "External entity reference '&" + entityName + ";' is forbidden in attribute default values", 
                                locator);
                        }
                        defaultValueBuilder.add(new GeneralEntityReference(entityName));
                        break;
                        
                    case PARAMETERENTITYREF:
                        // Parameter entity reference in default value - flush text and add reference
                        if (defaultValueTextBuilder.length() > 0) {
                            defaultValueBuilder.add(defaultValueTextBuilder.toString());
                            defaultValueTextBuilder.setLength(0);
                        }
                        String paramEntityName = data.toString();
                        // Parameter entities are allowed in default values
                        defaultValueBuilder.add(new ParameterEntityReference(paramEntityName));
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Closing quote - finalize default value, save attribute, and return to expecting next attribute
                        // Flush final text segment
                        if (defaultValueTextBuilder.length() > 0) {
                            defaultValueBuilder.add(defaultValueTextBuilder.toString());
                        }
                        // Set default value on attribute
                        currentAttributeDecl.defaultValue = defaultValueBuilder;
                        defaultValueBuilder = null;
                        defaultValueTextBuilder = null;
                        saveCurrentAttribute();
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "Default values must be properly quoted"
                        throw fatalError("Expected closing quote after default value in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case IN_NOTATION_ENUM:
                // Inside NOTATION enumeration: (name1|name2|name3)
                switch (token) {
                    case NAME:
                        // Collect notation name
                        if (enumerationBuilder != null) {
                            String name = data.toString();
                            
                            // VC: No Duplicate Tokens (Section 3.3.1)
                            // Check for duplicates in NOTATION enumeration
                            if (dtdParser.getValidationEnabled() && enumerationBuilder.contains(name)) {
                                dtdParser.reportValidationError(
                                    "Validity Constraint: No Duplicate Tokens (Section 3.3.1). " +
                                    "Duplicate notation name '" + name + "' in NOTATION attribute declaration for element '" + 
                                    currentAttlistElement + "', attribute '" + currentAttributeDecl.name + "'.");
                            }
                            
                            // Validate notation name (must be a valid XML Name, per production [58])
                            validateNotationName(name);
                            enumerationBuilder.add(name);
                        }
                        break;
                        
                    case PIPE:
                        // Separator between notation names
                        break;
                        
                    case CLOSE_PAREN:
                        // End of notation enumeration
                        // Validate that enumeration is not empty
                        if (enumerationBuilder != null && enumerationBuilder.isEmpty()) {
                            // WFC: Attribute List Declaration (Production 52)
                            // "NOTATION enumerations must not be empty"
                            throw fatalError("Empty NOTATION enumeration () not allowed in <!ATTLIST", locator);
                        }
                        if (enumerationBuilder != null) {
                            currentAttributeDecl.enumeration = enumerationBuilder;
                            enumerationBuilder = null;
                        }
                        // Back to expecting default declaration
                        state = State.EXPECT_DEFAULT_DECL;
                        break;
                        
                    case S:
                        // Whitespace in enumeration, ignore
                        break;
                        
                    default:
                        // WFC: Attribute List Declaration (Production 52)
                        // "NOTATION enumerations must contain only notation names, |, or )"
                        throw fatalError("Expected notation name, |, or ) in NOTATION enumeration, got: " + token, locator);
                }
                break;
                
            default:
                // Internal error - should never happen
                throw fatalError("Invalid ATTLIST parser state: " + state, locator);
        }
        
        return false; // Not done yet
    }
    
    /**
     * Saves the current attribute to the attribute map.
     * Called when an attribute declaration is complete.
     */
    private void saveCurrentAttribute() throws SAXException {
        if (currentAttributeDecl != null && currentAttributeDecl.name != null) {
            // Set default type if not specified
            if (currentAttributeDecl.type == null) {
                currentAttributeDecl.type = "CDATA";
            }
            
            // VC: Attribute Default Legal (Section 3.3.2)
            // Validate that default values match their declared type
            if (dtdParser.getValidationEnabled() && currentAttributeDecl.defaultValue != null) {
                validateDefaultValue(currentAttributeDecl);
            }
            
            // VC: ID Attribute Default (Section 3.3.1)
            // ID attributes cannot have default values (must be #REQUIRED or #IMPLIED)
            if (dtdParser.getValidationEnabled() && "ID".equals(currentAttributeDecl.type)) {
                if (currentAttributeDecl.mode == Token.FIXED) {
                    dtdParser.reportValidationError(
                        "Validity Constraint: ID Attribute Default (Section 3.3.1). " +
                        "ID attribute '" + currentAttributeDecl.name + "' cannot have #FIXED default.");
                } else if (currentAttributeDecl.defaultValue != null) {
                    dtdParser.reportValidationError(
                        "Validity Constraint: ID Attribute Default (Section 3.3.1). " +
                        "ID attribute '" + currentAttributeDecl.name + "' cannot have a default value " +
                        "(must be #REQUIRED or #IMPLIED).");
                }
            }
            
            // VC: One ID per Element Type (Section 3.3.1)
            // Check if this element already has an ID attribute
            if (dtdParser.getValidationEnabled() && "ID".equals(currentAttributeDecl.type)) {
                // Check existing attributes for this element
                for (AttributeDeclaration existing : currentAttlistMap.values()) {
                    if ("ID".equals(existing.type) && !existing.name.equals(currentAttributeDecl.name)) {
                        dtdParser.reportValidationError(
                            "Validity Constraint: One ID per Element Type (Section 3.3.1). " +
                            "Element '" + currentAttlistElement + "' has multiple ID attributes: " +
                            "'" + existing.name + "' and '" + currentAttributeDecl.name + "'.");
                        break;
                    }
                }
            }
            
            // Add to current ATTLIST map keyed by attribute name
            currentAttlistMap.put(currentAttributeDecl.name.intern(), currentAttributeDecl);
            currentAttributeDecl = null;
        }
    }
    
    /**
     * Saves the completed ATTLIST to the DTD's attribute declarations.
     * Merges with existing attributes for this element if present.
     * 
     * @throws SAXException if validation fails
     */
    private void saveCurrentAttlist() throws SAXException {
        if (currentAttlistElement != null && !currentAttlistMap.isEmpty()) {
            dtdParser.addAttributeDeclarations(currentAttlistElement, currentAttlistMap);
        }
    }
    
    /**
     * Reports a fatal error through the error handler and returns the exception.
     * 
     * @param message the error message
     * @param locator the locator for error position
     * @return the SAXException to throw
     * @throws SAXException if the ErrorHandler itself throws
     */
    private SAXException fatalError(String message, Locator locator) throws SAXException {
        return dtdParser.fatalError(message, locator);
    }
    
    /**
     * Validates that a notation name is a valid XML Name.
     * Per XML 1.0 Production [5]: Name ::= NameStartChar (NameChar)*
     * Per XML 1.0 Production [58]: NotationType uses Name, not Nmtoken
     * Names must start with a NameStartChar, not just any NameChar (e.g., not a digit).
     * 
     * @param name the name to validate
     * @throws SAXException if the name is invalid
     */
    private void validateNotationName(String name) throws SAXException {
        if (name == null || name.isEmpty()) {
            // WFC: Notation Declaration (Production 82)
            // "Notation names must not be empty"
            throw fatalError("Notation name cannot be empty", locator);
        }
        
        // Check that first character is a NameStartChar
        // NameStartChar ::= ":" | [A-Z] | "_" | [a-z] | [#xC0-#xD6] | [#xD8-#xF6] | ...
        // For simplicity, check common cases: must not be a digit, hyphen, or period
        char firstChar = name.charAt(0);
        if (firstChar == '-' || firstChar == '.' || (firstChar >= '0' && firstChar <= '9')) {
            // WFC: Names (Production 5)
            // "Notation names must be valid XML Names"
            throw fatalError(
                "Notation name '" + name + "' is not a valid XML Name: " +
                "must start with a letter, underscore, or colon, not '" + firstChar + "'",
                locator);
        }
        
        // Note: This is a simplified check. A full implementation would check all
        // NameStartChar ranges from the XML specification.
    }
    
    /**
     * Validates that a default attribute value is syntactically correct for its declared type.
     * VC: Attribute Default Legal (Section 3.3.2)
     * 
     * @param decl the attribute declaration to validate
     * @throws SAXException if validation fails
     */
    private void validateDefaultValue(AttributeDeclaration decl) throws SAXException {
        if (decl.defaultValue == null || decl.defaultValue.isEmpty()) {
            return;
        }
        
        // Convert default value to string for validation
        // Default values are stored as List<Object> where each element is either:
        // - String (literal text)
        // - GeneralEntityReference (entity reference)
        StringBuilder valueBuilder = new StringBuilder();
        for (Object part : decl.defaultValue) {
            if (part instanceof String) {
                valueBuilder.append((String) part);
            } else if (part instanceof GeneralEntityReference) {
                // Entity references in default values - would need to expand
                // For now, skip validation if entity references are present
                // (These would be validated at usage time)
                return;
            }
        }
        String value = valueBuilder.toString();
        
        // Validate based on attribute type
        String errorMessage = null;
        switch (decl.type) {
            case "IDREF":
            case "ID":
            case "ENTITY":
                // Must be a valid Name (not Nmtoken)
                if (!isName(value)) {
                    errorMessage = "Default value '" + value + "' for " + decl.type + 
                        " attribute '" + decl.name + "' is not a valid Name.";
                }
                break;
                
            case "IDREFS":
            case "ENTITIES":
                // Must be a valid Names (space-separated Name tokens)
                if (!isNames(value)) {
                    errorMessage = "Default value '" + value + "' for " + decl.type + 
                        " attribute '" + decl.name + "' is not a valid Names list.";
                }
                break;
                
            case "NMTOKEN":
                // Must be a valid Nmtoken
                if (!isNmtoken(value)) {
                    errorMessage = "Default value '" + value + "' for NMTOKEN attribute '" + 
                        decl.name + "' is not a valid Nmtoken.";
                }
                break;
                
            case "NMTOKENS":
                // Must be valid Nmtokens (space-separated)
                if (!isNmtokens(value)) {
                    errorMessage = "Default value '" + value + "' for NMTOKENS attribute '" + 
                        decl.name + "' is not a valid Nmtokens list.";
                }
                break;
                
            case "NOTATION":
                // Must be one of the enumerated notation names
                if (decl.enumeration != null && !decl.enumeration.contains(value)) {
                    errorMessage = "Default value '" + value + "' for NOTATION attribute '" + 
                        decl.name + "' is not in the enumerated list: " + decl.enumeration;
                }
                break;
                
            case "ENUMERATION":
                // Must be one of the enumerated values
                if (decl.enumeration != null && !decl.enumeration.contains(value)) {
                    errorMessage = "Default value '" + value + "' for enumerated attribute '" + 
                        decl.name + "' is not in the enumerated list: " + decl.enumeration;
                }
                break;
                
            case "CDATA":
                // Any value is valid for CDATA
                break;
                
            default:
                // Unknown type - no validation
                break;
        }
        
        if (errorMessage != null) {
            dtdParser.reportValidationError(
                "Validity Constraint: Attribute Default Legal (Section 3.3.2). " + errorMessage);
        }
    }
    
    /**
     * Checks if a string is a valid XML Name.
     * Name ::= NameStartChar (NameChar)*
     */
    private boolean isName(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        
        char first = s.charAt(0);
        // NameStartChar: must not start with digit, hyphen, or period
        if (first >= '0' && first <= '9' || first == '-' || first == '.') {
            return false;
        }
        
        // Rest of characters can be NameChar (letters, digits, -, ., :, _)
        // For simplicity, we allow most characters except control characters
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a string is a valid Names list (space-separated Names).
     */
    private boolean isNames(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        
        String[] names = s.trim().split("\\s+");
        for (String name : names) {
            if (!isName(name)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a string is a valid Nmtoken.
     * Nmtoken ::= (NameChar)+
     */
    private boolean isNmtoken(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        
        // Nmtoken must consist only of NameChar characters
        // NameChar includes: letters, digits, -, ., :, _, and Unicode ranges
        // But excludes: whitespace, @, #, $, +, etc.
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isNameChar(c)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a character is a NameChar per XML spec.
     * NameChar ::= NameStartChar | '-' | '.' | [0-9] | #xB7 | [#x0300-#x036F] | [#x203F-#x2040]
     */
    private boolean isNameChar(char c) {
        // Check if it's a NameStartChar
        if (isNameStartChar(c)) {
            return true;
        }
        // Check other NameChar characters
        return c == '-' || c == '.' || (c >= '0' && c <= '9') ||
               c == 0xB7 || (c >= 0x0300 && c <= 0x036F) || (c >= 0x203F && c <= 0x2040);
    }
    
    /**
     * Checks if a character is a NameStartChar per XML spec.
     */
    private boolean isNameStartChar(char c) {
        // Letters
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
            return true;
        }
        // Special characters
        if (c == ':' || c == '_') {
            return true;
        }
        // Unicode ranges (simplified - full spec has more ranges)
        if ((c >= 0xC0 && c <= 0xD6) || (c >= 0xD8 && c <= 0xF6) || (c >= 0xF8 && c <= 0x2FF) ||
            (c >= 0x370 && c <= 0x37D) || (c >= 0x37F && c <= 0x1FFF) ||
            (c >= 0x200C && c <= 0x200D) || (c >= 0x2070 && c <= 0x218F) ||
            (c >= 0x2C00 && c <= 0x2FEF) || (c >= 0x3001 && c <= 0xD7FF) ||
            (c >= 0xF900 && c <= 0xFDCF) || (c >= 0xFDF0 && c <= 0xFFFD)) {
            return true;
        }
        return false;
    }
    
    /**
     * Checks if a string is a valid Nmtokens list (space-separated Nmtokens).
     */
    private boolean isNmtokens(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        
        String[] tokens = s.trim().split("\\s+");
        for (String token : tokens) {
            if (!isNmtoken(token)) {
                return false;
            }
        }
        return true;
    }
    
}

