/*
 * AttributeValidator.java
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

import org.xml.sax.Attributes;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates attribute values against DTD-declared types.
 * 
 * <p>This validator checks attribute values conform to their declared types:
 * <ul>
 * <li>CDATA - any character data (no validation needed)</li>
 * <li>NMTOKEN - name token</li>
 * <li>NMTOKENS - space-separated list of name tokens</li>
 * <li>ID - unique identifier</li>
 * <li>IDREF - reference to an ID</li>
 * <li>IDREFS - space-separated list of IDREFs</li>
 * <li>ENTITY - reference to an unparsed entity</li>
 * <li>ENTITIES - space-separated list of entity references</li>
 * <li>NOTATION - notation name from DTD</li>
 * <li>Enumeration - value from enumerated list</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class AttributeValidator {
    
    private final DTDParser dtdParser;
    private final Set<String> declaredIds;      // IDs encountered in document
    private final List<String> idrefValues;     // IDREFs to validate at end
    private final List<String> idrefLocations;  // Locations for IDREF errors
    
    /**
     * Creates a new attribute validator.
     * 
     * @param dtdParser the DTD parser containing declarations
     */
    public AttributeValidator(DTDParser dtdParser) {
        this.dtdParser = dtdParser;
        this.declaredIds = new HashSet<>();
        this.idrefValues = new ArrayList<>();
        this.idrefLocations = new ArrayList<>();
    }
    
    /**
     * Resets the validator state for a new document.
     */
    public void reset() {
        declaredIds.clear();
        idrefValues.clear();
        idrefLocations.clear();
    }
    
    /**
     * Validates attributes for an element.
     * 
     * @param elementName the element name
     * @param attributes the attributes
     * @param location location string for error messages
     * @return error message if validation fails, null if valid
     */
    public String validateAttributes(String elementName, Attributes attributes, String location) {
        if (dtdParser == null) {
            return null; // No DTD, no validation
        }
        
        // Get all attribute declarations for this element
        java.util.Map<String, AttributeDeclaration> declarations = dtdParser.getAttributeDeclarations(elementName);
        if (declarations == null || declarations.isEmpty()) {
            // No declarations, check if there are any attributes (undeclared attributes are errors)
            if (attributes.getLength() > 0) {
                return "Element '" + elementName + "' has attributes but no ATTLIST declaration";
            }
            return null;
        }
        
        // Check #REQUIRED attributes are present
        for (AttributeDeclaration decl : declarations.values()) {
            if (decl.mode == org.bluezoo.gonzalez.Token.REQUIRED) {
                boolean found = false;
                for (int i = 0; i < attributes.getLength(); i++) {
                    if (decl.name.equals(attributes.getQName(i))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return "Required attribute '" + decl.name + "' missing in element '" + elementName + "'";
                }
            }
        }
        
        // Validate each attribute value against its type
        for (int i = 0; i < attributes.getLength(); i++) {
            String attrName = attributes.getQName(i);
            String attrValue = attributes.getValue(i);
            
            // Find declaration
            AttributeDeclaration decl = dtdParser.getAttributeDeclaration(elementName, attrName);
            if (decl == null) {
                return "Attribute '" + attrName + "' not declared for element '" + elementName + "'";
            }
            
            // Validate type
            String error = validateAttributeType(decl, attrValue, elementName, attrName, location);
            if (error != null) {
                return error;
            }
        }
        
        return null;
    }
    
    /**
     * Validates an attribute value against its declared type.
     */
    private String validateAttributeType(AttributeDeclaration decl, String value, 
                                         String elementName, String attrName, String location) {
        String type = decl.type;
        if (type == null) {
            return "Attribute '" + attrName + "' has no declared type";
        }
        
        // Handle empty values for types that require at least one token
        if (value == null || value.isEmpty()) {
            if ("IDREFS".equals(type)) {
                // VC: Attribute Default Legal (Section 3.3.2)
                return "Attribute '" + attrName + "' value is empty (IDREFS requires at least one Name)";
            }
            if ("NMTOKENS".equals(type)) {
                // VC: Attribute Default Legal (Section 3.3.2)
                return "Attribute '" + attrName + "' value is empty (NMTOKENS requires at least one Nmtoken)";
            }
            // Other types allow empty values (checked elsewhere or not required)
            return null;
        }
        
        switch (type) {
            case "CDATA":
                // Any character data allowed
                return null;
                
            case "NMTOKEN":
                if (!isNmtoken(value)) {
                    return "Attribute '" + attrName + "' value '" + value + "' is not a valid NMTOKEN";
                }
                return null;
                
            case "NMTOKENS":
                return validateNmtokens(value, attrName);
                
            case "ID":
                return validateId(value, attrName, elementName, location);
                
            case "IDREF":
                return validateIdref(value, attrName, location);
                
            case "IDREFS":
                return validateIdrefs(value, attrName, location);
                
            case "ENTITY":
                return validateEntity(value, attrName);
                
            case "ENTITIES":
                return validateEntities(value, attrName);
                
            case "NOTATION":
                return validateNotation(value, attrName, decl);
                
            case "ENUMERATION":
                return validateEnumeration(value, attrName, decl);
                
            default:
                // Unknown type - could be enumeration stored as type name
                // If the declaration has an enumeration list, validate against it
                if (decl.enumeration != null && !decl.enumeration.isEmpty()) {
                    return validateEnumeration(value, attrName, decl);
                }
                return null;
        }
    }
    
    /**
     * Checks if a string is a valid NMTOKEN.
     * NMTOKEN ::= (NameChar)+
     */
    private boolean isNmtoken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isNameChar(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a string is a valid Name.
     * Name ::= NameStartChar (NameChar)*
     */
    private boolean isName(String value) {
        if (value.isEmpty()) {
            return false;
        }
        if (!isNameStartChar(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!isNameChar(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a character is a NameStartChar per XML spec.
     */
    private boolean isNameStartChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == ':' || c == '_' ||
               (c >= 0xC0 && c <= 0xD6) || (c >= 0xD8 && c <= 0xF6) || (c >= 0xF8 && c <= 0x2FF) ||
               (c >= 0x370 && c <= 0x37D) || (c >= 0x37F && c <= 0x1FFF) ||
               (c >= 0x200C && c <= 0x200D) || (c >= 0x2070 && c <= 0x218F) ||
               (c >= 0x2C00 && c <= 0x2FEF) || (c >= 0x3001 && c <= 0xD7FF) ||
               (c >= 0xF900 && c <= 0xFDCF) || (c >= 0xFDF0 && c <= 0xFFFD);
    }
    
    /**
     * Checks if a character is a NameChar per XML spec.
     */
    private boolean isNameChar(char c) {
        return isNameStartChar(c) || c == '-' || c == '.' || (c >= '0' && c <= '9') ||
               c == 0xB7 || (c >= 0x0300 && c <= 0x036F) || (c >= 0x203F && c <= 0x2040);
    }
    
    /**
     * Validates NMTOKENS (space-separated list).
     * VC: Attribute Default Legal (Section 3.3.2)
     * After normalization, tokens must be separated by space (#x20), not other whitespace.
     */
    private String validateNmtokens(String value, String attrName) {
        if (value == null || value.trim().isEmpty()) {
            // VC: Attribute Default Legal (Section 3.3.2)
            // NMTOKENS must contain at least one Nmtoken
            return "Attribute '" + attrName + "' value is empty (NMTOKENS requires at least one Nmtoken)";
        }
        
        // VC: Attribute Default Legal (Section 3.3.2)
        // After normalization, tokens must be separated by space (#x20), not other whitespace
        // Check that the normalized value doesn't contain non-space whitespace between tokens
        // (The value should already be normalized, but we check for any remaining non-space whitespace)
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) && c != ' ') {
                return "Attribute '" + attrName + "' value contains non-space whitespace (tokens must be separated by space after normalization)";
            }
        }
        
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) {
            // VC: Attribute Default Legal (Section 3.3.2)
            return "Attribute '" + attrName + "' value is empty (NMTOKENS requires at least one Nmtoken)";
        }
        
        for (String token : tokens) {
            if (!isNmtoken(token)) {
                return "Attribute '" + attrName + "' value '" + token + "' is not a valid NMTOKEN";
            }
        }
        return null;
    }
    
    /**
     * Validates ID attribute value.
     * - Must be a valid Name
     * - Must be unique in document
     * - VC: ID Attribute (Section 3.3.1) - cannot contain colon
     */
    private String validateId(String value, String attrName, String elementName, String location) {
        if (!isName(value)) {
            return "Attribute '" + attrName + "' value '" + value + "' is not a valid Name (ID must be a Name)";
        }
        
        // VC: ID Attribute (Section 3.3.1)
        // ID values cannot contain colons (per XML Namespaces spec)
        if (value.indexOf(':') >= 0) {
            return "Attribute '" + attrName + "' value '" + value + "' contains colon (ID values cannot contain colons)";
        }
        
        if (declaredIds.contains(value)) {
            return "ID '" + value + "' already declared (IDs must be unique)";
        }
        
        declaredIds.add(value);
        return null;
    }
    
    /**
     * Validates IDREF attribute value.
     * - Must be a valid Name
     * - VC: IDREF Attribute (Section 3.3.1) - cannot contain colon
     * - Will be checked against IDs at end of document
     */
    private String validateIdref(String value, String attrName, String location) {
        if (!isName(value)) {
            return "Attribute '" + attrName + "' value '" + value + "' is not a valid Name (IDREF must be a Name)";
        }
        
        // VC: IDREF Attribute (Section 3.3.1)
        // IDREF values cannot contain colons (per XML Namespaces spec)
        if (value.indexOf(':') >= 0) {
            return "Attribute '" + attrName + "' value '" + value + "' contains colon (IDREF values cannot contain colons)";
        }
        
        // Store for later validation
        idrefValues.add(value);
        idrefLocations.add(location + " attribute '" + attrName + "'");
        return null;
    }
    
    /**
     * Validates IDREFS attribute value (space-separated list).
     */
    private String validateIdrefs(String value, String attrName, String location) {
        if (value == null || value.trim().isEmpty()) {
            // VC: Attribute Default Legal (Section 3.3.2)
            // IDREFS must contain at least one Name
            return "Attribute '" + attrName + "' value is empty (IDREFS requires at least one Name)";
        }
        
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) {
            // VC: Attribute Default Legal (Section 3.3.2)
            return "Attribute '" + attrName + "' value is empty (IDREFS requires at least one Name)";
        }
        
        for (String token : tokens) {
            String error = validateIdref(token, attrName, location);
            if (error != null) {
                return error;
            }
        }
        return null;
    }
    
    /**
     * Validates ENTITY attribute value.
     * - Must reference an unparsed entity declared in DTD
     */
    private String validateEntity(String value, String attrName) {
        if (!isName(value)) {
            return "Attribute '" + attrName + "' value '" + value + "' is not a valid Name (ENTITY must be a Name)";
        }
        
        // Check entity exists and is unparsed
        EntityDeclaration entity = dtdParser.getGeneralEntity(value);
        if (entity == null) {
            return "ENTITY '" + value + "' not declared";
        }
        if (entity.notationName == null) {
            return "ENTITY '" + value + "' is not an unparsed entity (must have NDATA)";
        }
        
        return null;
    }
    
    /**
     * Validates ENTITIES attribute value (space-separated list).
     */
    private String validateEntities(String value, String attrName) {
        String[] tokens = value.trim().split("\\s+");
        for (String token : tokens) {
            String error = validateEntity(token, attrName);
            if (error != null) {
                return error;
            }
        }
        return null;
    }
    
    /**
     * Validates NOTATION attribute value.
     * - Must be a valid Name
     * - If enumeration is specified, must be in the list
     * - Otherwise, must be a declared notation
     */
    private String validateNotation(String value, String attrName, AttributeDeclaration decl) {
        if (!isName(value)) {
            return "Attribute '" + attrName + "' value '" + value + "' is not a valid Name (NOTATION must be a Name)";
        }
        
        // If enumeration is specified, validate against it
        if (decl.enumeration != null && !decl.enumeration.isEmpty()) {
            if (!decl.enumeration.contains(value)) {
                return "NOTATION value '" + value + "' not in declared enumeration: " + decl.enumeration;
            }
            // Still check that the notation is declared
            ExternalID notation = dtdParser.getNotation(value);
            if (notation == null) {
                return "NOTATION '" + value + "' not declared in DTD";
            }
        } else {
            // No enumeration - just check it's declared
            ExternalID notation = dtdParser.getNotation(value);
            if (notation == null) {
                return "NOTATION '" + value + "' not declared in DTD";
            }
        }
        
        return null;
    }
    
    /**
     * Validates enumeration attribute value.
     * - Must be one of the enumerated values
     */
    private String validateEnumeration(String value, String attrName, AttributeDeclaration decl) {
        if (decl.enumeration == null || decl.enumeration.isEmpty()) {
            return "Attribute '" + attrName + "' has enumeration type but no values declared";
        }
        
        if (!decl.enumeration.contains(value)) {
            return "Attribute '" + attrName + "' value '" + value + "' not in enumeration: " + decl.enumeration;
        }
        
        return null;
    }
    
    /**
     * Validates all IDREFs against declared IDs (call at end of document).
     * 
     * @return error message if any IDREF is invalid, null if all valid
     */
    public String validateIdrefs() {
        for (int i = 0; i < idrefValues.size(); i++) {
            String idref = idrefValues.get(i);
            if (!declaredIds.contains(idref)) {
                String location = idrefLocations.get(i);
                return "IDREF '" + idref + "' at " + location + " references undeclared ID";
            }
        }
        return null;
    }
}

