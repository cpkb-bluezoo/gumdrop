/*
 * ElementDeclParser.java
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
import java.util.ArrayDeque;
import java.util.Deque;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parser for &lt;!ELEMENT declarations within a DTD.
 * 
 * <p>This class is responsible for parsing a single &lt;!ELEMENT declaration
 * from start to finish. It maintains its own state machine and temporary
 * structures for building the content model. When parsing is complete (&gt;
 * token received), it adds the completed {@link ElementDeclaration} to the
 * parent {@link DTDParser}'s {@code elementDecls} map.
 * 
 * <p>Parsing states:
 * <ul>
 * <li>EXPECT_NAME: Waiting for element name</li>
 * <li>AFTER_NAME: After element name, expecting whitespace</li>
 * <li>EXPECT_CONTENTSPEC: Expecting EMPTY, ANY, or content model</li>
 * <li>IN_CONTENT_MODEL: Inside parenthesized content model</li>
 * <li>AFTER_CONTENTSPEC: After content spec, expecting whitespace or &gt;</li>
 * <li>EXPECT_GT: Expecting &gt; to close declaration</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ElementDeclParser {
    
    /**
     * Sub-states for parsing &lt;!ELEMENT declarations.
     * Tracks position within the element declaration to enforce well-formedness.
     */
    private enum State {
        EXPECT_NAME,           // After <!ELEMENT, expecting element name
        AFTER_NAME,            // After element name, expecting whitespace
        EXPECT_CONTENTSPEC,    // After whitespace, expecting EMPTY|ANY|(
        IN_CONTENT_MODEL,      // Inside ( ... ), building content model
        AFTER_CONTENTSPEC,     // After content spec, expecting whitespace or >
        EXPECT_GT              // Expecting > to close declaration
    }
    
    /** Reference to parent DTD parser */
    private final DTDParser dtdParser;
    
    /** Locator for error reporting */
    private final Locator locator;
    
    /** Current state */
    private State state = State.EXPECT_NAME;
    
    /** Element declaration being built */
    private final ElementDeclaration elementDecl = new ElementDeclaration();
    
    /** Stack for building nested content models */
    private final Deque<ElementDeclaration.ContentModel> contentModelStack = new ArrayDeque<>();
    
    /** Track parenthesis nesting depth in content models */
    private int contentModelDepth = 0;
    
    /** Track whitespace before occurrence indicators */
    private boolean sawWhitespaceInContentModel = false;
    
    /** Track if we just saw a separator (| or ,) expecting a following element */
    private boolean sawSeparator = false;
    
    /**
     * Creates an element declaration parser.
     * 
     * @param dtdParser the parent DTD parser
     * @param locator the locator for error reporting
     */
    ElementDeclParser(DTDParser dtdParser, Locator locator) {
        this.dtdParser = dtdParser;
        this.locator = locator;
    }
    
    /**
     * Gets the current parenthesis nesting depth in the content model.
     * Used for validating Proper Group/PE Nesting validity constraint.
     * 
     * @return the current nesting depth (0 if not in a content model)
     */
    int getContentModelDepth() {
        return contentModelDepth;
    }
    
    /**
     * Processes a token for this element declaration.
     * 
     * @param token the token type
     * @param data the character data for the token
     * @return true if declaration is complete (GT received), false otherwise
     * @throws SAXException if parsing fails
     */
    boolean receive(Token token, CharBuffer data) throws SAXException {
        switch (state) {
            case EXPECT_NAME:
                return handleExpectName(token, data);
                
            case AFTER_NAME:
                return handleAfterName(token, data);
                
            case EXPECT_CONTENTSPEC:
                return handleExpectContentspec(token, data);
                
            case IN_CONTENT_MODEL:
                return handleInContentModel(token, data);
                
            case AFTER_CONTENTSPEC:
                return handleAfterContentspec(token, data);
                
            case EXPECT_GT:
                return handleExpectGT(token, data);
                
            default:
                // Internal error - should never happen
                throw fatalError("Unknown state in ElementDeclParser: " + state, locator);
        }
    }
    
    private boolean handleExpectName(Token token, CharBuffer data) throws SAXException {
        // Must see NAME token for element name (whitespace allowed first)
        if (token == Token.NAME) {
            elementDecl.name = data.toString();
            state = State.AFTER_NAME;
        } else if (token == Token.S) {
            // Skip whitespace before element name
        } else {
            // WFC: Element Type Declaration (Production 45)
            // "Element declarations must start with element name"
            throw fatalError("Expected element name after <!ELEMENT, got: " + token, locator);
        }
        return false;
    }
    
    private boolean handleAfterName(Token token, CharBuffer data) throws SAXException {
        // Must see whitespace after element name
        if (token == Token.S) {
            state = State.EXPECT_CONTENTSPEC;
        } else {
            // WFC: Element Type Declaration (Production 45)
            // "Whitespace is required after element name"
            throw fatalError("Expected whitespace after element name in <!ELEMENT, got: " + token, locator);
        }
        return false;
    }
    
    private boolean handleExpectContentspec(Token token, CharBuffer data) throws SAXException {
        // Expecting EMPTY | ANY | content model
        switch (token) {
            case S:
                // Skip extra whitespace
                break;
                
            case EMPTY:
                elementDecl.contentType = ElementDeclaration.ContentType.EMPTY;
                state = State.AFTER_CONTENTSPEC;
                break;
                
            case ANY:
                elementDecl.contentType = ElementDeclaration.ContentType.ANY;
                state = State.AFTER_CONTENTSPEC;
                break;
                
            case OPEN_PAREN:
                // Start content model group
                ElementDeclaration.ContentModel group = new ElementDeclaration.ContentModel();
                // Don't know if it's SEQUENCE or CHOICE yet - will determine from first separator
                contentModelStack.push(group);
                contentModelDepth = 1;
                state = State.IN_CONTENT_MODEL;
                break;
                
            case PARAMETERENTITYREF:
                // Parameter entity expansion in element content specification
                // Example: <!ELEMENT doc %content-model;>
                String refName = data.toString();
                dtdParser.expandParameterEntityInline(refName, token, data);
                // After expansion, stay in EXPECT_CONTENTSPEC to process the expanded tokens
                break;
                
            default:
                // WFC: Element Type Declaration (Production 45)
                // "Content specification must be EMPTY, ANY, or a content model"
                throw fatalError("Expected content specification (EMPTY, ANY, or '(') in <!ELEMENT, got: " + token, locator);
        }
        return false;
    }
    
    private boolean handleInContentModel(Token token, CharBuffer data) throws SAXException {
        // Inside ( ... ), building content model
        switch (token) {
            case S:
                // Whitespace in content model - track it for occurrence indicator validation
                sawWhitespaceInContentModel = true;
                break;
                
            case NAME:
                String nameStr = data.toString();
                
                // Validate that we're not adding a second element without a separator
                ElementDeclaration.ContentModel currentGroup = contentModelStack.peek();
                if (currentGroup.children != null && !currentGroup.children.isEmpty() && !sawSeparator) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Elements in content models must be separated by '|' or ','"
                    throw fatalError(
                        "Missing separator between elements in content model (use '|' for choice or ',' for sequence)", 
                        locator);
                }
                
                // Handle PCDATA special case (tokenizer may emit as NAME)
                // PCDATA must be uppercase - reject lowercase "pcdata"
                if (nameStr.equals("PCDATA")) {
                    // Create #PCDATA leaf node
                    ElementDeclaration.ContentModel leaf = new ElementDeclaration.ContentModel();
                    leaf.type = ElementDeclaration.ContentModel.NodeType.PCDATA;
                    leaf.occurrence = ElementDeclaration.ContentModel.Occurrence.ONCE;
                    currentGroup.addChild(leaf);
                } else if (nameStr.equalsIgnoreCase("PCDATA")) {
                    // Case mismatch - #PCDATA is case-sensitive
                    // WFC: Mixed Content Declaration (Production 51)
                    // "#PCDATA keyword is case-sensitive and must be uppercase"
                    throw fatalError(
                        "Invalid keyword '#" + nameStr + "' in content model (must be '#PCDATA' in uppercase)", locator);
                } else {
                    // Create element name leaf node
                    ElementDeclaration.ContentModel leaf = new ElementDeclaration.ContentModel();
                    leaf.type = ElementDeclaration.ContentModel.NodeType.ELEMENT;
                    leaf.elementName = nameStr;
                    leaf.occurrence = ElementDeclaration.ContentModel.Occurrence.ONCE;
                    currentGroup.addChild(leaf);
                }
                // Reset flags after adding element
                sawWhitespaceInContentModel = false;
                sawSeparator = false;
                break;
                
            case PCDATA:
                // Validate that we're not adding a second element without a separator
                ElementDeclaration.ContentModel currentForPcdata = contentModelStack.peek();
                if (currentForPcdata.children != null && !currentForPcdata.children.isEmpty() && !sawSeparator) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Elements in content models must be separated by '|' or ','"
                    throw fatalError(
                        "Missing separator between elements in content model (use '|' for choice or ',' for sequence)", 
                        locator);
                }
                
                // Create #PCDATA leaf node
                ElementDeclaration.ContentModel pcdataLeaf = new ElementDeclaration.ContentModel();
                pcdataLeaf.type = ElementDeclaration.ContentModel.NodeType.PCDATA;
                pcdataLeaf.occurrence = ElementDeclaration.ContentModel.Occurrence.ONCE;
                currentForPcdata.addChild(pcdataLeaf);
                // Reset flags after adding PCDATA
                sawWhitespaceInContentModel = false;
                sawSeparator = false;
                break;
                
            case OPEN_PAREN:
                // Validate that we're not adding a nested group without a separator
                ElementDeclaration.ContentModel currentForNested = contentModelStack.peek();
                if (currentForNested.children != null && !currentForNested.children.isEmpty() && !sawSeparator) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Elements in content models must be separated by '|' or ','"
                    throw fatalError(
                        "Missing separator between elements in content model (use '|' for choice or ',' for sequence)", 
                        locator);
                }
                
                // Nested group
                ElementDeclaration.ContentModel nestedGroup = new ElementDeclaration.ContentModel();
                contentModelStack.push(nestedGroup);
                contentModelDepth++;
                break;
                
            case CLOSE_PAREN:
                // Check for trailing separator
                if (sawSeparator) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Content models must not have trailing separators"
                    throw fatalError("Trailing separator in content model (missing element after '|' or ',')", locator);
                }
                
                contentModelDepth--;
                if (contentModelDepth == 0) {
                    // Exited the top-level content model
                    ElementDeclaration.ContentModel root = contentModelStack.pop();
                    
                    // Validate that content model is not empty
                    if (root.children == null || root.children.isEmpty()) {
                        // WFC: Element Type Declaration (Production 45)
                        // "Content models must not be empty"
                        throw fatalError("Empty content model () is not allowed in <!ELEMENT", locator);
                    }
                    
                    // Set default type if not already set (single element case like "(a)")
                    if (root.type == null) {
                        // Single child or no separator - treat as sequence
                        root.type = ElementDeclaration.ContentModel.NodeType.SEQUENCE;
                    }
                    
                    elementDecl.contentModel = root;
                    
                    // Determine content type (validation happens later in finish())
                    if (containsPCDATA(root)) {
                        elementDecl.contentType = ElementDeclaration.ContentType.MIXED;
                    } else {
                        elementDecl.contentType = ElementDeclaration.ContentType.ELEMENT;
                    }
                    
                    // Reset flags after closing paren
                    sawWhitespaceInContentModel = false;
                    sawSeparator = false;
                    state = State.AFTER_CONTENTSPEC;
                } else if (contentModelDepth < 0) {
                    throw fatalError("Unmatched closing parenthesis in <!ELEMENT content model", locator);
                } else {
                    // Finished a nested group - pop it and add to parent
                    ElementDeclaration.ContentModel completed = contentModelStack.pop();
                    
                    // Set default type if not already set (single element case)
                    if (completed.type == null) {
                        completed.type = ElementDeclaration.ContentModel.NodeType.SEQUENCE;
                    }
                    
                    contentModelStack.peek().addChild(completed);
                    // Reset flags after closing paren
                    sawWhitespaceInContentModel = false;
                    sawSeparator = false;
                }
                break;

            case PIPE:
                // Check for double separator
                if (sawSeparator) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Content models must not have consecutive separators"
                    throw fatalError("Consecutive separators not allowed in content model (e.g., '||' or ',|')", locator);
                }
                
                // Set group type to CHOICE if not already set
                ElementDeclaration.ContentModel current = contentModelStack.peek();
                if (current.type == null) {
                    current.type = ElementDeclaration.ContentModel.NodeType.CHOICE;
                } else if (current.type != ElementDeclaration.ContentModel.NodeType.CHOICE) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Cannot mix choice (|) and sequence (,) at the same level"
                    throw fatalError("Cannot mix ',' and '|' at same level in content model", locator);
                }
                // Validate that there's a preceding element
                if (current.children == null || current.children.isEmpty()) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Separators must have preceding elements"
                    throw fatalError("'|' must have a preceding element or group in content model", locator);
                }
                // Mark that we're expecting another element after this separator
                sawSeparator = true;
                break;
                
            case COMMA:
                // Check for double separator
                if (sawSeparator) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Content models must not have consecutive separators"
                    throw fatalError("Consecutive separators not allowed in content model (e.g., ',,' or '|,')", locator);
                }
                
                // Set group type to SEQUENCE if not already set
                ElementDeclaration.ContentModel currentSeq = contentModelStack.peek();
                if (currentSeq.type == null) {
                    currentSeq.type = ElementDeclaration.ContentModel.NodeType.SEQUENCE;
                } else if (currentSeq.type != ElementDeclaration.ContentModel.NodeType.SEQUENCE) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Cannot mix choice (|) and sequence (,) at the same level"
                    throw fatalError("Cannot mix ',' and '|' at same level in content model", locator);
                }
                // Validate that there's a preceding element
                if (currentSeq.children == null || currentSeq.children.isEmpty()) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Separators must have preceding elements"
                    throw fatalError("',' must have a preceding element or group in content model", locator);
                }
                // Mark that we're expecting another element after this separator
                sawSeparator = true;
                break;
                
            case QUERY:
            case PLUS:
            case STAR:
                // Occurrence indicator must immediately follow element or ) with no whitespace
                if (sawWhitespaceInContentModel) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Occurrence indicators must immediately follow element names or groups"
                    throw fatalError(
                        "Whitespace not allowed before occurrence indicator in content model", locator);
                }
                
                // Apply occurrence indicator to last child added
                ElementDeclaration.ContentModel parent = contentModelStack.peek();
                if (parent.children == null || parent.children.isEmpty()) {
                    // No preceding element or group - invalid
                    // WFC: Element Type Declaration (Production 45)
                    // "Occurrence indicators must follow element names or groups"
                    throw fatalError(
                        "Occurrence indicator '" + token + "' must follow an element name or parenthesized group in content model", locator);
                }
                
                ElementDeclaration.ContentModel lastChild = parent.children.get(parent.children.size() - 1);
                
                // Check for double occurrence indicator (e.g., *?)
                if (lastChild.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE) {
                    // WFC: Element Type Declaration (Production 45)
                    // "Only one occurrence indicator is allowed per element or group"
                    throw fatalError(
                        "Multiple occurrence indicators not allowed in content model", locator);
                }
                
                lastChild.occurrence = token == Token.QUERY ? ElementDeclaration.ContentModel.Occurrence.OPTIONAL :
                                      token == Token.PLUS ? ElementDeclaration.ContentModel.Occurrence.ONE_OR_MORE :
                                      ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE;
                break;
                
            case HASH:
                // Hash by itself (followed by PCDATA token or NAME("PCDATA"))
                // Just ignore - will be handled by PCDATA or NAME token
                break;
                
            default:
                // WFC: Element Type Declaration (Production 45)
                // "Content models must contain only valid tokens"
                throw fatalError("Unexpected token in <!ELEMENT content model: " + token, locator);
        }
        return false;
    }
    
    private boolean handleAfterContentspec(Token token, CharBuffer data) throws SAXException {
        // After content spec, expecting optional whitespace then GT
        switch (token) {
            case S:
                // Optional whitespace before GT
                state = State.EXPECT_GT;
                break;
                
            case GT:
                // Save and return true (complete)
                finish();
                return true;
                
            case QUERY:
            case PLUS:
            case STAR:
                // Occurrence indicator after content spec - apply to root
                if (elementDecl.contentModel != null) {
                    elementDecl.contentModel.occurrence = 
                        token == Token.QUERY ? ElementDeclaration.ContentModel.Occurrence.OPTIONAL :
                        token == Token.PLUS ? ElementDeclaration.ContentModel.Occurrence.ONE_OR_MORE :
                        ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE;
                }
                // Stay in AFTER_CONTENTSPEC to allow optional whitespace before GT
                break;
                
            default:
                // WFC: Element Type Declaration (Production 45)
                // "Element declarations must end with '>'"
                throw fatalError("Expected '>' to close <!ELEMENT declaration, got: " + token, locator);
        }
        return false;
    }
    
    private boolean handleExpectGT(Token token, CharBuffer data) throws SAXException {
        // Must see GT to close declaration
        if (token == Token.GT) {
            finish();
            return true;
        } else if (token == Token.S) {
            // Allow extra whitespace
        } else {
            // WFC: Element Type Declaration (Production 45)
            // "Element declarations must end with '>'"
            throw fatalError("Expected '>' to close <!ELEMENT declaration, got: " + token, locator);
        }
        return false;
    }
    
    /**
     * Helper to check if a content model contains #PCDATA.
     */
    private boolean containsPCDATA(ElementDeclaration.ContentModel model) {
        if (model.type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
            return true;
        }
        if (model.children != null) {
            for (ElementDeclaration.ContentModel child : model.children) {
                if (containsPCDATA(child)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Validates mixed content constraints per XML spec:
     * 1. If content model contains #PCDATA, it must be a CHOICE group at top level (or just #PCDATA alone)
     * 2. #PCDATA must be first in the choice
     * 3. The occurrence indicator on #PCDATA must be * or none (not + or ?)
     * 4. No nested groups allowed in mixed content
     */
    private void validateMixedContent(ElementDeclaration.ContentModel model) throws SAXException {
        // Special case: (#PCDATA) alone is valid - could be SEQUENCE with single PCDATA child
        if (model.children != null && model.children.size() == 1) {
            ElementDeclaration.ContentModel child = model.children.get(0);
            if (child.type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
                // This is (#PCDATA) which is valid
                // Check occurrence on the PCDATA itself
                if (child.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                    child.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                    // WFC: Mixed Content Declaration (Production 51)
                    // "Mixed content with #PCDATA can only have * or no occurrence indicator"
                    throw fatalError(
                        "Mixed content with #PCDATA can only have * or no occurrence indicator", locator);
                }
                // Check occurrence on the group
                if (model.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                    model.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                    // WFC: Mixed Content Declaration (Production 51)
                    // "Mixed content can only have * or no occurrence indicator"
                    throw fatalError(
                        "Mixed content can only have * or no occurrence indicator", locator);
                }
                return; // Valid
            }
            // If single child that is a group, check for unnecessary nesting
            if (child.type == ElementDeclaration.ContentModel.NodeType.CHOICE ||
                child.type == ElementDeclaration.ContentModel.NodeType.SEQUENCE) {
                if (child.children != null && child.children.size() == 1 &&
                    child.children.get(0).type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
                    // This is ((#PCDATA)) - unnecessary nesting
                    // WFC: Mixed Content Declaration (Production 51)
                    // "Mixed content must not have unnecessary nested parentheses"
                    throw fatalError(
                        "Unnecessary nested parentheses in mixed content", locator);
                }
            }
        }
        
        // If the model itself is just PCDATA (not wrapped), check occurrence
        if (model.type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
            if (model.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                model.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                // WFC: Mixed Content Declaration (Production 51)
                // "Mixed content with #PCDATA can only have * or no occurrence indicator"
                throw fatalError(
                    "Mixed content with #PCDATA can only have * or no occurrence indicator", locator);
            }
            return; // Valid
        }
        
        // Mixed content with multiple elements must be a CHOICE at top level
        if (model.type != ElementDeclaration.ContentModel.NodeType.CHOICE) {
            // Only error if there are multiple children or if first child is not PCDATA alone
            if (model.children != null && model.children.size() > 1) {
                // WFC: Mixed Content Declaration (Production 51)
                // "Mixed content must use | (choice), not , (sequence)"
                throw fatalError(
                    "Mixed content must use | (choice), not , (sequence)", locator);
            }
        }
        
        // If CHOICE, #PCDATA must be first child
        if (model.type == ElementDeclaration.ContentModel.NodeType.CHOICE) {
            if (model.children != null && !model.children.isEmpty()) {
                ElementDeclaration.ContentModel first = model.children.get(0);
                if (first.type != ElementDeclaration.ContentModel.NodeType.PCDATA) {
                    // WFC: Mixed Content Declaration (Production 51)
                    // "#PCDATA must be first in mixed content choice"
                    throw fatalError(
                        "#PCDATA must be first in mixed content choice", locator);
                }
            }
            
            // VC: No Duplicate Types (Section 3.2.1)
            // Check for duplicate element types in mixed content
            if (dtdParser.getValidationEnabled() && model.children != null) {
                java.util.Set<String> seenElements = new java.util.HashSet<>();
                for (ElementDeclaration.ContentModel child : model.children) {
                    if (child.type == ElementDeclaration.ContentModel.NodeType.ELEMENT && child.elementName != null) {
                        if (seenElements.contains(child.elementName)) {
                            dtdParser.reportValidationError(
                                "Validity Constraint: No Duplicate Types (Section 3.2.1). " +
                                "Element type '" + child.elementName + "' appears more than once in mixed content " +
                                "declaration for element '" + elementDecl.name + "'.");
                        }
                        seenElements.add(child.elementName);
                    }
                }
            }
            
            // Check for nested groups (not allowed in mixed content)
            if (model.children != null) {
                for (ElementDeclaration.ContentModel child : model.children) {
                    if (child.type == ElementDeclaration.ContentModel.NodeType.CHOICE ||
                        child.type == ElementDeclaration.ContentModel.NodeType.SEQUENCE) {
                        // WFC: Mixed Content Declaration (Production 51)
                        // "Nested groups are not allowed in mixed content"
                        throw fatalError(
                            "Nested groups not allowed in mixed content", locator);
                    }
                    // Element names in mixed content cannot have occurrence indicators
                    if (child.type == ElementDeclaration.ContentModel.NodeType.ELEMENT &&
                        child.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE) {
                        // WFC: Mixed Content Declaration (Production 51)
                        // "Element names in mixed content cannot have occurrence indicators"
                        throw fatalError(
                            "Element names in mixed content cannot have occurrence indicators", locator);
                    }
                }
            }
            
            // Occurrence indicator on choice must be * or none
            // But if there are element names (more than just #PCDATA), * is REQUIRED
            if (model.children != null && model.children.size() > 1) {
                // Mixed content with element names - must have *
                if (model.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                    // WFC: Mixed Content Declaration (Production 51)
                    // "Mixed content with element names must have * occurrence indicator"
                    throw fatalError(
                        "Mixed content with element names must have * occurrence indicator", locator);
                }
            } else {
                // Just (#PCDATA) alone - * is optional
                if (model.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                    model.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                    throw fatalError(
                        "Mixed content can only have * or no occurrence indicator", locator);
                }
            }
        }
    }
    
    /**
     * Completes parsing and adds the element declaration to the parent DTD parser.
     * Called when the &lt;!ELEMENT declaration is complete (&gt; encountered).
     */
    private void finish() throws SAXException {
        if (elementDecl.name == null) {
            throw fatalError("No element name in <!ELEMENT declaration", locator);
        }
        
        if (elementDecl.contentType == null) {
            throw fatalError("No content specification in <!ELEMENT declaration", locator);
        }
        
        // Validate mixed content constraints (after occurrence indicators have been applied)
        if (elementDecl.contentType == ElementDeclaration.ContentType.MIXED &&
            elementDecl.contentModel != null) {
            validateMixedContent(elementDecl.contentModel);
        }
        
        // Validate that content model is deterministic (XML Spec Section 3.2.1)
        // A non-deterministic content model is an error even if the element type is not used
        if (elementDecl.contentType == ElementDeclaration.ContentType.ELEMENT &&
            elementDecl.contentModel != null) {
            validateDeterministic(elementDecl.contentModel);
        }
        
        // Add to parent DTD parser's element declarations
        dtdParser.addElementDeclaration(elementDecl);
    }
    
    /**
     * Validates that a content model is deterministic.
     * XML Spec Section 3.2.1: "For compatibility, it is an error if the content model
     * allows an element to match more than one occurrence of an element type in the
     * content model."
     * 
     * A non-deterministic content model is an error even if the element type is not used.
     * 
     * @param model the content model to validate
     * @throws SAXException if the content model is non-deterministic
     */
    private void validateDeterministic(ElementDeclaration.ContentModel model) throws SAXException {
        if (model == null) {
            return;
        }
        
        switch (model.type) {
            case ELEMENT:
                // Leaf node - no determinism issue
                return;
                
            case SEQUENCE:
                // For sequences, check each child recursively
                if (model.children != null) {
                    for (ElementDeclaration.ContentModel child : model.children) {
                        validateDeterministic(child);
                    }
                }
                return;
                
            case CHOICE:
                // For choices, check for duplicate element names in the same choice group
                if (model.children != null) {
                    java.util.Set<String> seenElementNames = new java.util.HashSet<>();
                    for (ElementDeclaration.ContentModel child : model.children) {
                        if (child.type == ElementDeclaration.ContentModel.NodeType.ELEMENT) {
                            String elementName = child.elementName;
                            if (seenElementNames.contains(elementName)) {
                                throw fatalError(
                                    "Non-deterministic content model: element '" + elementName + 
                                    "' appears more than once in the same choice group. " +
                                    "This is an error even if the element type is not used.",
                                    locator);
                            }
                            seenElementNames.add(elementName);
                        } else {
                            // Recursively check nested groups
                            validateDeterministic(child);
                        }
                    }
                }
                return;
                
            case PCDATA:
                // #PCDATA should not appear in element content models
                return;
                
            default:
                return;
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
    
}

