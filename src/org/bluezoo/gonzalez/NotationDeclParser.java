/*
 * NotationDeclParser.java
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
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parser for &lt;!NOTATION declarations within a DTD.
 * 
 * <p>Grammar: &lt;!NOTATION Name S (ExternalID | PublicID) S? &gt;
 * <p>ExternalID: SYSTEM S SystemLiteral | PUBLIC S PubidLiteral S SystemLiteral
 * <p>PublicID: PUBLIC S PubidLiteral
 * 
 * <p>This parser is instantiated for each &lt;!NOTATION declaration and
 * handles tokens until the closing &gt; is encountered. It builds an
 * ExternalID which is then saved to the DTD's notations structure and
 * reported to the DTDHandler.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class NotationDeclParser {
    
    /**
     * Sub-states for parsing &lt;!NOTATION declarations.
     * Tracks position within the notation declaration to enforce well-formedness.
     */
    private enum State {
        EXPECT_NAME,           // After &lt;!NOTATION, expecting notation name
        AFTER_NAME,            // After notation name, expecting whitespace
        EXPECT_EXTERNAL_ID,    // After whitespace, expecting SYSTEM or PUBLIC
        EXPECT_SYSTEM_ID,      // After SYSTEM, expecting quoted system ID
        EXPECT_PUBLIC_ID,      // After PUBLIC, expecting quoted public ID
        AFTER_PUBLIC_ID,       // After public ID, expecting optional system ID or &gt;
        EXPECT_GT              // Expecting &gt; to close declaration
    }
    
    private State state = State.EXPECT_NAME;
    
    /**
     * Current notation declaration being parsed.
     */
    private String currentNotationName;
    private ExternalID currentNotationExternalID;
    private boolean sawPublicInNotation;
    private boolean sawWhitespaceAfterKeyword; // Track whitespace after SYSTEM/PUBLIC
    
    private final DTDParser dtdParser;
    private final Locator locator;
    
    /**
     * Creates a new parser for a single &lt;!NOTATION declaration.
     * 
     * @param dtdParser the parent DTD parser (for validation and saving results)
     * @param locator the locator for error reporting
     */
    NotationDeclParser(DTDParser dtdParser, Locator locator) {
        this.dtdParser = dtdParser;
        this.locator = locator;
        this.currentNotationExternalID = new ExternalID();
    }
    
    /**
     * Processes a single token within the &lt;!NOTATION declaration.
     * Returns true if the declaration is complete (GT was encountered).
     * 
     * @param token the token to process
     * @param data the character buffer for the token (may be null)
     * @return true if the NOTATION declaration is complete
     * @throws SAXException if a parsing error occurs
     */
    boolean handleToken(Token token, CharBuffer data) throws SAXException {
        switch (state) {
            case EXPECT_NAME:
                // After &lt;!NOTATION, expecting notation name
                switch (token) {
                    case S:
                        // Skip whitespace before name
                        break;
                        
                    case NAME:
                        // Notation name
                        currentNotationName = data.toString();
                        // Per Namespaces in XML 1.0 section 6: notation names must not contain colons in namespace-aware mode
                        // (Notation names are not QNames, but colons are still forbidden when namespaces are enabled)
                        dtdParser.validateNameInNamespaceMode(currentNotationName);
                        state = State.AFTER_NAME;
                        break;
                        
                    default:
                        // WFC: Notation Declaration (Production 82)
                        // "Notation declarations must start with notation name"
                        throw fatalError("Expected notation name after <!NOTATION, got: " + token, locator);
                }
                break;
                
            case AFTER_NAME:
                // After notation name, expecting whitespace
                switch (token) {
                    case S:
                        // Whitespace between name and external ID
                        state = State.EXPECT_EXTERNAL_ID;
                        break;
                        
                    default:
                        // WFC: Notation Declaration (Production 82)
                        // "Whitespace is required after notation name"
                        throw fatalError("Expected whitespace after notation name, got: " + token, locator);
                }
                break;
                
            case EXPECT_EXTERNAL_ID:
                // After whitespace, expecting SYSTEM or PUBLIC
                switch (token) {
                    case S:
                        // Skip additional whitespace
                        break;
                        
                    case SYSTEM:
                        // SYSTEM external ID
                        sawPublicInNotation = false;
                        sawWhitespaceAfterKeyword = false; // Reset
                        state = State.EXPECT_SYSTEM_ID;
                        break;
                        
                    case PUBLIC:
                        // PUBLIC external ID (or PublicID)
                        sawPublicInNotation = true;
                        sawWhitespaceAfterKeyword = false; // Reset
                        state = State.EXPECT_PUBLIC_ID;
                        break;
                        
                    default:
                        // WFC: Notation Declaration (Production 82)
                        // "Notation declarations must have SYSTEM or PUBLIC"
                        throw fatalError("Expected SYSTEM or PUBLIC after notation name, got: " + token, locator);
                }
                break;
                
            case EXPECT_SYSTEM_ID:
                // After SYSTEM, expecting quoted system ID
                switch (token) {
                    case S:
                        // Whitespace between SYSTEM and quoted string
                        sawWhitespaceAfterKeyword = true;
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Opening quote
                        // Enforce whitespace after SYSTEM keyword
                        if (!sawWhitespaceAfterKeyword) {
                            // WFC: Notation Declaration (Production 82)
                            // "Whitespace is required after SYSTEM keyword"
                            throw fatalError("Expected whitespace after SYSTEM keyword in <!NOTATION", locator);
                        }
                        // ignore quote
                        break;
                        
                    case CDATA:
                    case NAME:
                        // System ID (tokenizer may emit NAME or CDATA in DOCTYPE context)
                        currentNotationExternalID.systemId = data.toString();
                        state = State.EXPECT_GT;
                        break;
                        
                    default:
                        // WFC: Notation Declaration (Production 82)
                        // "System ID must be a quoted string"
                        throw fatalError("Expected quoted system ID after SYSTEM, got: " + token, locator);
                }
                break;
                
            case EXPECT_PUBLIC_ID:
                // After PUBLIC, expecting quoted public ID
                switch (token) {
                    case S:
                        // Whitespace between PUBLIC and quoted string
                        sawWhitespaceAfterKeyword = true;
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Opening quote
                        // Enforce whitespace after PUBLIC keyword
                        if (!sawWhitespaceAfterKeyword) {
                            // WFC: Notation Declaration (Production 82)
                            // "Whitespace is required after PUBLIC keyword"
                            throw fatalError("Expected whitespace after PUBLIC keyword in <!NOTATION", locator);
                        }
                        // ignore quote
                        break;
                        
                    case CDATA:
                    case NAME:
                        // Public ID (tokenizer may emit NAME or CDATA in DOCTYPE context)
                        String publicId = data.toString();
                        dtdParser.validatePublicId(publicId);
                        currentNotationExternalID.publicId = publicId;
                        state = State.AFTER_PUBLIC_ID;
                        break;
                        
                    default:
                        // WFC: Notation Declaration (Production 82)
                        // "Public ID must be a quoted string"
                        throw fatalError("Expected quoted public ID after PUBLIC, got: " + token, locator);
                }
                break;
                
            case AFTER_PUBLIC_ID:
                // After public ID, expecting optional system ID or &gt;
                switch (token) {
                    case S:
                        // Whitespace, ignore
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Quote (closing previous or opening next)
                        break;
                        
                    case CDATA:
                    case NAME:
                        // Optional system ID after PUBLIC (tokenizer may emit NAME or CDATA)
                        currentNotationExternalID.systemId = data.toString();
                        state = State.EXPECT_GT;
                        break;
                        
                    case GT:
                        // End of declaration (PUBLIC without system ID)
                        finish();
                        return true;
                        
                    default:
                        // WFC: Notation Declaration (Production 82)
                        // "After public ID, must be system ID or '>'"
                        throw fatalError("Expected system ID or '>' after public ID, got: " + token, locator);
                }
                break;
                
            case EXPECT_GT:
                // Expecting &gt; to close declaration
                switch (token) {
                    case S:
                        // Whitespace before &gt;, ignore
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Closing quote, ignore
                        break;
                        
                    case CDATA:
                    case NAME:
                        // Additional text (e.g., continuation of system ID if split by tokenizer)
                        // Append to existing system ID if present
                        if (currentNotationExternalID.systemId != null) {
                            currentNotationExternalID.systemId += data.toString();
                        }
                        break;
                        
                    case GT:
                        // End of declaration
                        finish();
                        return true;
                        
                    default:
                        // WFC: Notation Declaration (Production 82)
                        // "Notation declarations must end with '>'"
                        throw fatalError("Expected '>' to close <!NOTATION declaration, got: " + token, locator);
                }
                break;
                
            default:
                // Internal error - should never happen
                throw fatalError("Invalid NOTATION parser state: " + state, locator);
        }
        
        return false; // Not done yet
    }
    
    /**
     * Validates and saves the completed notation declaration.
     * Performs final validation checks before adding to DTD.
     * 
     * @throws SAXException if validation fails
     */
    private void finish() throws SAXException {
        // Validate system ID doesn't contain fragment identifier
        if (currentNotationExternalID != null && currentNotationExternalID.systemId != null) {
            if (currentNotationExternalID.systemId.indexOf('#') >= 0) {
                // WFC: System Literal (Production 11)
                // "System identifiers must not contain URI fragment identifiers (#)"
                throw fatalError(
                    "System identifier must not contain URI fragment (found '#' in: " + 
                    currentNotationExternalID.systemId + ")", locator);
            }
        }
        
        // Save notation declaration
        dtdParser.addNotationDeclaration(currentNotationName, currentNotationExternalID);
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

