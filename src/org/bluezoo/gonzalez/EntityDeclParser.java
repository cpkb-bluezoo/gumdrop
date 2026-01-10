/*
 * EntityDeclParser.java
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
import java.util.List;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parser for &lt;!ENTITY declarations within a DTD.
 * 
 * <p>Grammar: &lt;!ENTITY Name EntityDef &gt; | &lt;!ENTITY % Name PEDef &gt;
 * <p>EntityDef: EntityValue | (ExternalID NDataDecl?)
 * <p>PEDef: EntityValue | ExternalID
 * <p>EntityValue: '"' ([^%&"] | PEReference | Reference)* '"' | "'" ([^%&'] | PEReference | Reference)* "'"
 * <p>ExternalID: 'SYSTEM' S SystemLiteral | 'PUBLIC' S PubidLiteral S SystemLiteral
 * <p>NDataDecl: S 'NDATA' S Name
 * 
 * <p>This parser is instantiated for each &lt;!ENTITY declaration and
 * handles tokens until the closing &gt; is encountered. It builds an
 * EntityDeclaration which is then saved to the DTD's entity or parameter
 * entity map.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class EntityDeclParser {
    
    /**
     * Sub-states for parsing &lt;!ENTITY declarations.
     */
    private enum State {
        EXPECT_PERCENT_OR_NAME,       // After &lt;!ENTITY, expecting optional % or entity name
        EXPECT_NAME,                  // After %, expecting parameter entity name
        AFTER_NAME,                   // After entity name, expecting whitespace
        EXPECT_VALUE_OR_ID,           // After whitespace, expecting quoted value, SYSTEM, or PUBLIC
        IN_ENTITY_VALUE,              // Accumulating entity value between quotes
        AFTER_ENTITY_VALUE,           // After closing quote of entity value
        
        // SYSTEM ExternalID states
        AFTER_SYSTEM_KEYWORD,         // After SYSTEM, before whitespace
        EXPECT_SYSTEM_QUOTE,          // After whitespace, expecting opening quote
        IN_SYSTEM_ID,                 // Inside system ID quotes, accumulating content
        AFTER_SYSTEM_CLOSING_QUOTE,   // After closing quote of system ID
        
        // PUBLIC ExternalID states  
        AFTER_PUBLIC_KEYWORD,         // After PUBLIC, before whitespace
        EXPECT_PUBLIC_QUOTE,          // After whitespace, expecting opening quote for public ID
        IN_PUBLIC_ID,                 // Inside public ID quotes, accumulating content
        AFTER_PUBLIC_CLOSING_QUOTE,   // After closing quote of public ID
        EXPECT_SYSTEM_QUOTE_AFTER_PUBLIC,  // After whitespace following public ID, expecting system ID quote
        IN_SYSTEM_ID_AFTER_PUBLIC,    // Inside system ID quotes (after public ID), accumulating content
        AFTER_SYSTEM_CLOSING_QUOTE_AFTER_PUBLIC,  // After closing quote of system ID (in PUBLIC declaration)
        
        EXPECT_NDATA_NAME,            // After NDATA, expecting notation name
        EXPECT_GT                     // Expecting &gt; to close declaration
    }
    
    private State state = State.EXPECT_PERCENT_OR_NAME;
    
    /**
     * State tracking flags (minimal - most tracking is via states now).
     */
    private boolean sawWhitespaceAfterEntityKeyword;
    private boolean sawWhitespaceBeforeNData;  // Track whitespace before NDATA
    
    /**
     * Current entity declaration being parsed.
     */
    private EntityDeclaration currentEntity;
    private List<Object> entityValueBuilder;  // Accumulates String and GeneralEntityReference
    private StringBuilder entityValueTextBuilder;  // Accumulates current text segment
    private char entityValueQuote;           // The opening quote character (' or ")
    
    private final DTDParser dtdParser;
    private final Locator locator;
    private final DTDParser.State savedState; // State to return to after completing
    
    /**
     * Creates a new parser for a single &lt;!ENTITY declaration.
     * 
     * @param dtdParser the parent DTD parser (for validation and saving results)
     * @param locator the locator for error reporting
     * @param savedState the state to return to when parsing completes
     */
    EntityDeclParser(DTDParser dtdParser, Locator locator, DTDParser.State savedState) {
        this.dtdParser = dtdParser;
        this.locator = locator;
        this.savedState = savedState;
        this.currentEntity = new EntityDeclaration();
        this.sawWhitespaceAfterEntityKeyword = false;
    }
    
    /**
     * Processes a single token within the &lt;!ENTITY declaration.
     * Returns true if the declaration is complete (&gt; was encountered).
     * 
     * @param token the token to process
     * @param data the character buffer for the token (may be null)
     * @return true if the ENTITY declaration is complete
     * @throws SAXException if a parsing error occurs
     */
    boolean handleToken(Token token, CharBuffer data) throws SAXException {
        switch (state) {
            case EXPECT_PERCENT_OR_NAME:
                // After <!ENTITY, expecting optional % or entity name
                switch (token) {
                    case S:
                        // Whitespace after <!ENTITY keyword is required
                        sawWhitespaceAfterEntityKeyword = true;
                        break;
                    case PERCENT:
                        // Parameter entity - must have whitespace before %
                        if (!sawWhitespaceAfterEntityKeyword) {
                            // WFC: Entity Declaration (Production 70)
                            // "Whitespace is required after <!ENTITY keyword"
                            throw fatalError(
                                "Expected whitespace after <!ENTITY keyword", locator);
                        }
                        currentEntity.isParameter = true;
                        state = State.EXPECT_NAME;
                        break;
                    case NAME:
                        // General entity name - must have whitespace before name
                        if (!sawWhitespaceAfterEntityKeyword) {
                            // WFC: Entity Declaration (Production 70)
                            // "Whitespace is required after <!ENTITY keyword"
                            throw fatalError(
                                "Expected whitespace after <!ENTITY keyword", locator);
                        }
                        currentEntity.isParameter = false;
                        currentEntity.name = data.toString();
                        // Entity names must be valid XML Names (P5 in the XML spec)
                        validateEntityName(currentEntity.name);
                        // Per Namespaces in XML 1.0 section 6: entity names must not contain colons in namespace-aware mode
                        // (Entity names are not QNames, but colons are still forbidden when namespaces are enabled)
                        dtdParser.validateNameInNamespaceMode(currentEntity.name);
                        state = State.AFTER_NAME;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Entity declarations must start with entity name or '%' for parameter entities"
                        throw fatalError("Expected entity name or '%' in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_NAME:
                // After %, expecting parameter entity name
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case NAME:
                        currentEntity.name = data.toString();
                        // Entity names must be valid XML Names (P5 in the XML spec)
                        validateEntityName(currentEntity.name);
                        // Per Namespaces in XML 1.0 section 6: entity names must not contain colons in namespace-aware mode
                        // (Entity names are not QNames, but colons are still forbidden when namespaces are enabled)
                        dtdParser.validateNameInNamespaceMode(currentEntity.name);
                        state = State.AFTER_NAME;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Parameter entity declarations must have a name after '%'"
                        throw fatalError("Expected parameter entity name after '%' in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case AFTER_NAME:
                // After entity name, expecting whitespace
                if (token == Token.S) {
                    state = State.EXPECT_VALUE_OR_ID;
                } else {
                    // WFC: Entity Declaration (Production 70)
                    // "Whitespace is required after entity name"
                    throw fatalError("Expected whitespace after entity name in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_VALUE_OR_ID:
                // After whitespace, expecting quoted value, SYSTEM, or PUBLIC
                switch (token) {
                    case S:
                        // Skip additional whitespace
                        break;
                    case QUOT:
                    case APOS:
                        // Start of entity value
                        entityValueQuote = (token == Token.QUOT) ? '"' : '\'';
                        entityValueBuilder = new ArrayList<>();
                        entityValueTextBuilder = new StringBuilder();
                        state = State.IN_ENTITY_VALUE;
                        break;
                    case SYSTEM:
                        // External entity with system ID
                        currentEntity.externalID = new ExternalID();
                        state = State.AFTER_SYSTEM_KEYWORD;
                        break;
                    case PUBLIC:
                        // External entity with public ID
                        currentEntity.externalID = new ExternalID();
                        state = State.AFTER_PUBLIC_KEYWORD;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Entity declarations must have quoted value, SYSTEM, or PUBLIC"
                        throw fatalError("Expected quoted value, SYSTEM, or PUBLIC in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case IN_ENTITY_VALUE:
                // Accumulating entity value between quotes
                switch (token) {
                    case CDATA:
                    case S:
                        // Accumulate text
                        String text = data.toString();
                        // WFC: No bare '%' in entity values (only in internal subset)
                        // In the internal subset, parameter entity references are not allowed within
                        // markup declarations (including entity values). Any '%' character must be
                        // escaped as &#x25;.
                        // In the external subset, PEs are allowed and handled by the tokenizer, which
                        // produces PARAMETERENTITYREF tokens. We don't need to check for '%' there.
                        if (!dtdParser.isProcessingExternalEntity() && text.indexOf('%') >= 0) {
                            // WFC: PEs in Internal Subset (Production 28a)
                            // "Parameter entity references are not allowed within entity values in the internal subset"
                            throw fatalError(
                                "Parameter entity references are not allowed within entity values " +
                                "in the internal subset (WFC: PEs in Internal Subset). " +
                                "Use character reference &#x25; to include a literal percent sign.", locator);
                        }
                        entityValueTextBuilder.append(text);
                        break;
                    case CHARENTITYREF:
                        // Character reference (already expanded) - e.g., &#60; -> '<'
                        // Per XML 1.0 § 4.4.4, character references are expanded during entity
                        // declaration parsing, and the resulting entity value must be re-tokenized
                        // when used (unlike predefined entities which trigger the bypass rule).
                        String charRefText = data.toString();
                        entityValueTextBuilder.append(charRefText);
                        
                        // Check if this character reference expands to a RestrictedChar in XML 1.1
                        // RestrictedChar: [#x1-#x8] | [#xB-#xC] | [#xE-#x1F]
                        if (!charRefText.isEmpty()) {
                            char c = charRefText.charAt(0);
                            if ((c >= 0x1 && c <= 0x8) || (c == 0xB) || (c == 0xC) || (c >= 0xE && c <= 0x1F)) {
                                // This entity contains a RestrictedChar from a character reference
                                // Mark it so we can allow it during retokenization in XML 1.1
                                currentEntity.containsRestrictedCharFromCharRef = true;
                            }
                        }
                        break;
                    case PREDEFENTITYREF:
                        // Predefined entity reference (already expanded) - e.g., &lt; -> '<'
                        // Per XML 1.0 § 4.4.8, markup delimiters from predefined entity
                        // references are bypassed (not recognized as markup)
                        currentEntity.containsCharacterReferences = true;
                        entityValueTextBuilder.append(data.toString());
                        break;
                    case GENERALENTITYREF:
                        // General entity reference - flush text and add reference
                        if (entityValueTextBuilder.length() > 0) {
                            entityValueBuilder.add(entityValueTextBuilder.toString());
                            entityValueTextBuilder.setLength(0);
                        }
                        String entityName = data.toString();
                        entityValueBuilder.add(new GeneralEntityReference(entityName));
                        break;
                    case PARAMETERENTITYREF:
                        // Parameter entity reference in entity value
                        // WFC: PEs in Internal Subset - parameter entity references are NOT allowed
                        // within markup declarations in the internal subset (including entity values)
                        // This restriction only applies to the internal subset, not external subset
                        if (!dtdParser.isProcessingExternalEntity()) {
                            // WFC: PEs in Internal Subset (Production 28a)
                            // "Parameter entity references are not allowed within entity values in the internal subset"
                            throw fatalError(
                                "Parameter entity references are not allowed within entity values " +
                                "in the internal subset (WFC: PEs in Internal Subset)", locator);
                        }
                        
                        // In external subset, parameter entities are allowed in entity values
                        // Store the reference for later expansion during entity usage
                        String paramEntityName = data.toString();
                        if (entityValueTextBuilder.length() > 0) {
                            entityValueBuilder.add(entityValueTextBuilder.toString());
                            entityValueTextBuilder.setLength(0);
                        }
                        entityValueBuilder.add(new ParameterEntityReference(paramEntityName));
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Check if this is the closing quote
                        char quoteChar = (token == Token.QUOT) ? '"' : '\'';
                        if (quoteChar == entityValueQuote) {
                            // Flush final text segment
                            if (entityValueTextBuilder.length() > 0) {
                                entityValueBuilder.add(entityValueTextBuilder.toString());
                            }
                            
                            // WFC: In external DTD subset, all % characters must be part of PE references
                            // Check if we're processing an external entity
                            if (dtdParser.isProcessingExternalEntity()) {
                                validatePercentInEntityValue(entityValueBuilder);
                            }
                            
                            // Set replacement text on entity
                            currentEntity.replacementText = entityValueBuilder;
                            state = State.AFTER_ENTITY_VALUE;
                        } else {
                            // Wrong quote, treat as text
                            entityValueTextBuilder.append(quoteChar);
                        }
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Entity values must contain only valid characters and entity references"
                        throw fatalError("Unexpected token in entity value: " + token, locator);
                }
                break;
                
            case AFTER_ENTITY_VALUE:
                // After closing quote, expecting > or whitespace
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case GT:
                        // End of entity declaration - save and return true
                        finish();
                        return true;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Entity declarations must end with '>'"
                        throw fatalError("Expected '>' after entity value in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case AFTER_SYSTEM_KEYWORD:
                // After SYSTEM keyword, expecting whitespace then quote
                switch (token) {
                    case S:
                        // Required whitespace after SYSTEM
                        state = State.EXPECT_SYSTEM_QUOTE;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Whitespace is required after SYSTEM keyword"
                        throw fatalError("Expected whitespace after SYSTEM keyword in <!ENTITY", locator);
                }
                break;
                
            case EXPECT_SYSTEM_QUOTE:
                // After whitespace following SYSTEM, expecting opening quote
                switch (token) {
                    case S:
                        // Additional whitespace, ignore
                        break;
                    case QUOT:
                    case APOS:
                        // Opening quote for system ID
                        state = State.IN_SYSTEM_ID;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "System ID must be a quoted string"
                        throw fatalError("Expected opening quote for system ID after SYSTEM in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case IN_SYSTEM_ID:
                // Inside system ID quotes, accumulating content
                switch (token) {
                    case CDATA:
                    case NAME:
                        // System ID content
                        if (currentEntity.externalID.systemId == null) {
                            currentEntity.externalID.systemId = data.toString();
                        } else {
                            currentEntity.externalID.systemId += data.toString();
                        }
                        break;
                    case QUOT:
                    case APOS:
                        // Closing quote of system ID
                        sawWhitespaceBeforeNData = false;  // Reset for NDATA check
                        state = State.AFTER_SYSTEM_CLOSING_QUOTE;
                        break;
                    default:
                        throw fatalError("Unexpected token in system ID: " + token, locator);
                }
                break;
                
            case AFTER_SYSTEM_CLOSING_QUOTE:
                // After closing quote of SYSTEM declaration, expecting NDATA or >
                switch (token) {
                    case S:
                        // Whitespace after system ID - required before NDATA
                        sawWhitespaceBeforeNData = true;
                        break;
                    case QUOT:
                    case APOS:
                        // Second quoted literal after SYSTEM - this is an error
                        // WFC: Entity Declaration (Production 70)
                        // "SYSTEM declarations must have only one quoted literal"
                        throw fatalError("SYSTEM declaration must have only one quoted literal", locator);
                    case GT:
                        // End of entity declaration
                        finish();
                        return true;
                    case NDATA:
                        // Unparsed entity with notation - must have whitespace first
                        if (!sawWhitespaceBeforeNData) {
                            // WFC: Entity Declaration (Production 70)
                            // "Whitespace is required before NDATA"
                            throw fatalError("Expected whitespace before NDATA in <!ENTITY", locator);
                        }
                        if (currentEntity.isParameter) {
                            // WFC: Entity Declaration (Production 70)
                            // "Parameter entities cannot have NDATA annotation"
                            throw fatalError("Parameter entities cannot have NDATA annotation", locator);
                        }
                        state = State.EXPECT_NDATA_NAME;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "After SYSTEM declaration, must be '>' or NDATA"
                        throw fatalError("Expected '>' or NDATA after SYSTEM declaration, got: " + token, locator);
                }
                break;
                
            case AFTER_PUBLIC_KEYWORD:
                // After PUBLIC keyword, expecting whitespace then quote
                switch (token) {
                    case S:
                        // Required whitespace after PUBLIC
                        state = State.EXPECT_PUBLIC_QUOTE;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Whitespace is required after PUBLIC keyword"
                        throw fatalError("Expected whitespace after PUBLIC keyword in <!ENTITY", locator);
                }
                break;
                
            case EXPECT_PUBLIC_QUOTE:
                // After whitespace following PUBLIC, expecting opening quote for public ID
                switch (token) {
                    case S:
                        // Additional whitespace, ignore
                        break;
                    case QUOT:
                    case APOS:
                        // Opening quote for public ID
                        state = State.IN_PUBLIC_ID;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Public ID must be a quoted string"
                        throw fatalError("Expected opening quote for public ID after PUBLIC in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case IN_PUBLIC_ID:
                // Inside public ID quotes, accumulating content
                switch (token) {
                    case CDATA:
                    case NAME:
                        // Public ID content
                        if (currentEntity.externalID.publicId == null) {
                            currentEntity.externalID.publicId = data.toString();
                        } else {
                            currentEntity.externalID.publicId += data.toString();
                        }
                        break;
                    case QUOT:
                    case APOS:
                        // Closing quote of public ID
                        dtdParser.validatePublicId(currentEntity.externalID.publicId);
                        state = State.AFTER_PUBLIC_CLOSING_QUOTE;
                        break;
                    default:
                        // WFC: Public ID (Production 75)
                        // "Public identifiers must be properly quoted"
                        throw fatalError("Unexpected token in public ID: " + token, locator);
                }
                break;
                
            case AFTER_PUBLIC_CLOSING_QUOTE:
                // After closing quote of public ID, expecting whitespace then system ID
                switch (token) {
                    case S:
                        // Required whitespace between public ID and system ID
                        state = State.EXPECT_SYSTEM_QUOTE_AFTER_PUBLIC;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Whitespace is required between public ID and system ID"
                        throw fatalError("Expected whitespace between public ID and system ID in <!ENTITY", locator);
                }
                break;
                
            case EXPECT_SYSTEM_QUOTE_AFTER_PUBLIC:
                // After whitespace following public ID, expecting opening quote for system ID
                switch (token) {
                    case S:
                        // Additional whitespace, ignore
                        break;
                    case QUOT:
                    case APOS:
                        // Opening quote for system ID
                        state = State.IN_SYSTEM_ID_AFTER_PUBLIC;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "System ID must be a quoted string after public ID"
                        throw fatalError("Expected opening quote for system ID after public ID in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case IN_SYSTEM_ID_AFTER_PUBLIC:
                // Inside system ID quotes (after public ID), accumulating content
                switch (token) {
                    case CDATA:
                    case NAME:
                        // System ID content
                        if (currentEntity.externalID.systemId == null) {
                            currentEntity.externalID.systemId = data.toString();
                        } else {
                            currentEntity.externalID.systemId += data.toString();
                        }
                        break;
                    case QUOT:
                    case APOS:
                        // Closing quote of system ID
                        sawWhitespaceBeforeNData = false;  // Reset (though PUBLIC entities can't have NDATA)
                        state = State.AFTER_SYSTEM_CLOSING_QUOTE_AFTER_PUBLIC;
                        break;
                    default:
                        // WFC: System Literal (Production 11)
                        // "System identifiers must be properly quoted"
                        throw fatalError("Unexpected token in system ID: " + token, locator);
                }
                break;
                
            case AFTER_SYSTEM_CLOSING_QUOTE_AFTER_PUBLIC:
                // After closing quote of system ID in PUBLIC declaration, expecting >
                switch (token) {
                    case S:
                        // Whitespace after system ID
                        break;
                    case GT:
                        // End of entity declaration
                        finish();
                        return true;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Entity declarations must end with '>'"
                        throw fatalError("Expected '>' after PUBLIC declaration, got: " + token, locator);
                }
                break;
                
            case EXPECT_NDATA_NAME:
                // After NDATA, expecting notation name
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case NAME:
                        currentEntity.notationName = data.toString();
                        // Validate notation name is a valid XML Name
                        validateNotationName(currentEntity.notationName);
                        state = State.EXPECT_GT;
                        break;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "NDATA must be followed by a notation name"
                        throw fatalError("Expected notation name after NDATA in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_GT:
                // Expecting > to close declaration
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case GT:
                        // End of entity declaration - save and return true
                        finish();
                        return true;
                    default:
                        // WFC: Entity Declaration (Production 70)
                        // "Entity declarations must end with '>'"
                        throw fatalError("Expected '>' to close <!ENTITY declaration, got: " + token, locator);
                }
                break;
                
            default:
                // Internal error - should never happen
                throw fatalError("Invalid ENTITY parser state: " + state, locator);
        }
        
        return false; // Not done yet
    }
    
    /**
     * Validates that all '%' characters in an entity value are part of parameter entity references.
     * This is a well-formedness constraint for external DTD subsets:
     * In the external subset, '%' must always be part of a parameter entity reference (%name;).
     * 
     * @param entityValue the entity value as a list of String and reference objects
     * @throws SAXException if a literal '%' is found that is not part of a valid PE reference syntax
     */
    private void validatePercentInEntityValue(List<Object> entityValue) throws SAXException {
        for (Object part : entityValue) {
            if (part instanceof String) {
                String text = (String) part;
                // Check for '%' characters
                int pos = 0;
                while ((pos = text.indexOf('%', pos)) >= 0) {
                    // Found a '%' - check if it's followed by a valid PE reference pattern (%name;)
                    // This is a heuristic since the tokenizer doesn't currently tokenize PE refs in entity values
                    
                    // Look ahead to see if we have %name; pattern
                    int nameStart = pos + 1;
                    int nameEnd = nameStart;
                    
                    // Check if we have at least one NameStartChar after %
                    if (nameEnd >= text.length() || !Character.isJavaIdentifierStart(text.charAt(nameEnd))) {
                        // WFC: PEs in Internal Subset (Production 28a)
                        // "In external DTD subset, '%' character must be part of a parameter entity reference"
                        throw fatalError(
                            "In external DTD subset, '%' character must be part of a parameter entity reference",
                            locator);
                    }
                    
                    // Scan for end of name (NameChars until ';')
                    nameEnd++;
                    while (nameEnd < text.length() && Character.isJavaIdentifierPart(text.charAt(nameEnd))) {
                        nameEnd++;
                    }
                    
                    // Check if followed by ';'
                    if (nameEnd >= text.length() || text.charAt(nameEnd) != ';') {
                        // WFC: PEs in Internal Subset (Production 28a)
                        // "In external DTD subset, '%' character must be part of a parameter entity reference"
                        throw fatalError(
                            "In external DTD subset, '%' character must be part of a parameter entity reference",
                            locator);
                    }
                    
                    // Move past this PE reference to check for more '%' characters
                    pos = nameEnd + 1;
                }
            }
            // ParameterEntityReference objects are fine - they represent valid PE references
        }
    }
    
    /**
     * Validates and saves the completed entity declaration.
     * Performs final validation checks before adding to DTD.
     * 
     * @throws SAXException if validation fails
     */
    private void finish() throws SAXException {
        // Validate system ID doesn't contain fragment identifier
        if (currentEntity.externalID != null && currentEntity.externalID.systemId != null) {
            if (currentEntity.externalID.systemId.indexOf('#') >= 0) {
                // WFC: System Literal (Production 11)
                // "System identifiers must not contain URI fragment identifiers (#)"
                throw fatalError(
                    "System identifier must not contain URI fragment (found '#' in: " + 
                    currentEntity.externalID.systemId + ")", locator);
            }
        }
        
        // VC: Notation Declared (Section 4.7.2)
        // If entity has NDATA, the notation must be declared
        if (dtdParser.getValidationEnabled() && currentEntity.notationName != null) {
            ExternalID notation = dtdParser.getNotation(currentEntity.notationName);
            if (notation == null) {
                dtdParser.reportValidationError(
                    "Validity Constraint: Notation Declared (Section 4.7.2). " +
                    "Entity '" + currentEntity.name + "' references notation '" + 
                    currentEntity.notationName + "' which is not declared in the DTD.");
            }
        }
        
        // Save entity declaration
        dtdParser.addEntityDeclaration(currentEntity);
    }
    
    /**
     * Checks if a string contains any markup delimiter characters.
     * These are characters that have special meaning in XML: &lt;, &gt;, &amp;, ', "
     * 
     * @param text the text to check
     * @return true if the text contains markup delimiters
     */
    private boolean containsMarkupDelimiter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '>' || c == '&' || c == '\'' || c == '"') {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a string contains any XML 1.1 RestrictedChar characters.
     * RestrictedChar ::= [#x1-#x8] | [#xB-#xC] | [#xE-#x1F] | [#x7F-#x84] | [#x86-#x9F]
     * These characters are only legal in XML 1.1 via character references.
     * 
     * @param text the text to check
     * @return true if the text contains RestrictedChar characters
     */
    private boolean containsRestrictedChar(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // C0 controls (except tab, LF, CR)
            if ((c >= 0x1 && c <= 0x8) || c == 0xB || c == 0xC || (c >= 0xE && c <= 0x1F)) {
                return true;
            }
            // DEL and C1 controls
            if ((c >= 0x7F && c <= 0x84) || (c >= 0x86 && c <= 0x9F)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates that a notation name is a valid XML Name.
     * Per XML 1.0 Production [5]: Name ::= NameStartChar (NameChar)*
     * Notation names must start with a NameStartChar, not just any NameChar.
     * 
     * @param name the notation name to validate
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
    }

    /**
     * Validates that an entity name is a valid XML Name.
     * Per XML 1.0 Production [5]: Name ::= NameStartChar (NameChar)*
     * Entity names must start with a NameStartChar, not just any NameChar.
     * 
     * @param name the entity name to validate
     * @throws SAXException if the name is invalid
     */
    private void validateEntityName(String name) throws SAXException {
        if (name == null || name.isEmpty()) {
            // WFC: Entity Declaration (Production 70)
            // "Entity names must not be empty"
            throw fatalError("Entity name cannot be empty", locator);
        }
        
        // Check that first character is a NameStartChar
        // NameStartChar ::= ":" | [A-Z] | "_" | [a-z] | [#xC0-#xD6] | [#xD8-#xF6] | ...
        // For simplicity, check common cases: must not be a digit, hyphen, or period
        char firstChar = name.charAt(0);
        if (firstChar == '-' || firstChar == '.' || (firstChar >= '0' && firstChar <= '9')) {
            // WFC: Names (Production 5)
            // "Entity names must be valid XML Names"
            throw fatalError(
                "Entity name '" + name + "' is not a valid XML Name: " +
                "must start with a letter, underscore, or colon, not '" + firstChar + "'",
                locator);
        }
        
        // Note: This is a simplified check. A full implementation would check all
        // NameStartChar ranges from the XML specification.
    }
    
    /**
     * Checks if the parser is currently in the entity value state.
     * Used by DTDParser to determine whether to expand parameter entities inline
     * or let EntityDeclParser handle them (for storage and later expansion).
     * 
     * @return true if currently accumulating an entity value
     */
    boolean isInEntityValue() {
        return state == State.IN_ENTITY_VALUE;
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
    
}

