/*
 * DTDParser.java
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

import java.io.IOException;
import java.nio.CharBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.LexicalHandler;

/**
 * DTD parser.
 * <p>
 * This is a consumer of tokens within a DOCTYPE declaration. It is lazily
 * constructed by the ContentParser when a DOCTYPE token is encountered, and
 * receives tokens until the final GT of the doctypedecl production is
 * detected.
 * <p>
 * The DTDParser builds internal structures for element declarations, attribute
 * list declarations, entity declarations, and notation declarations. These
 * structures are only created when a DOCTYPE is present, avoiding memory
 * allocation for the majority of documents that don't include a DTD.
 * <p>
 * The parser delegates to SAX2 handlers (DTDHandler, LexicalHandler) to
 * report DTD events as they are encountered.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DTDParser implements TokenConsumer {

    /**
     * Parsing states within the DOCTYPE declaration.
     */
    enum State {
        INITIAL,                // Just started, expecting DOCTYPE name
        AFTER_NAME,             // Read name, expecting SYSTEM/PUBLIC/[/GT
        AFTER_SYSTEM_PUBLIC,    // Read SYSTEM/PUBLIC, expecting quoted string
        AFTER_PUBLIC_ID,        // Read public ID, expecting system ID
        AFTER_EXTERNAL_ID,      // Read external ID, expecting [/GT
        IN_INTERNAL_SUBSET,     // Inside [ ... ], processing declarations
        AFTER_INTERNAL_SUBSET,  // Read ], expecting GT
        IN_ELEMENTDECL,         // Parsing &lt;!ELEMENT declaration
        IN_ATTLISTDECL,         // Parsing &lt;!ATTLIST declaration
        IN_ENTITYDECL,          // Parsing &lt;!ENTITY declaration
        IN_NOTATIONDECL,        // Parsing &lt;!NOTATION declaration
        IN_COMMENT,             // Parsing comment (<!-- ... -->)
        IN_PI,                  // Parsing processing instruction (<? ... ?>)
        IN_CONDITIONAL,         // Parsing conditional section (<![)
        IN_CONDITIONAL_INCLUDE, // Inside INCLUDE conditional section
        IN_CONDITIONAL_IGNORE,  // Inside IGNORE conditional section (skip content)
        DONE                    // Read final GT, done processing
    }

    private State state = State.INITIAL;
    
    /**
     * Package-private accessor for the current DTD parser state.
     * Used by ContentParser to determine if external entities should be tokenized as DTD content.
     */
    State getState() {
        return state;
    }
    private State savedState = State.IN_INTERNAL_SUBSET; // For returning after declarations/comments/PIs
    private State conditionalSavedState = State.IN_INTERNAL_SUBSET; // For returning after conditional sections
    
    // Current tokenizer state (updated via tokenizerState callback)
    private TokenizerState currentTokenizerState = TokenizerState.DOCTYPE_INTERNAL;
    
    // Nested tokenizer currently being used for parameter entity expansion
    // Used to identify when the nested tokenizer calls tokenizerState()
    private Tokenizer currentNestedTokenizer = null;
    
    // Final state of nested tokenizer during parameter entity expansion
    // Used to sync parent tokenizer when PE expands to conditional section keywords
    private TokenizerState nestedTokenizerFinalState = null;
    
    /**
     * Entity stack - tracks all entity expansion entries and provides expansion logic.
     * Unified stack for both general and parameter entities, used for:
     * - Detecting infinite recursion (by name and systemId)
     * - Tracking XML version across entity boundaries
     * - Validating element nesting (WFC: Parsed Entity)
     * - Context-aware entity value expansion
     * 
     * The bottom of the stack is always the document entity.
     * Package-private to allow ContentParser access.
     */
    final EntityStack entityStack;
    
    
    private Locator locator;
    private ContentHandler contentHandler;
    private DTDHandler dtdHandler;
    private LexicalHandler lexicalHandler;
    private ErrorHandler errorHandler;
    
    /**
     * Stack of Tokenizers (via Locator) for nested entity expansion.
     * When a parameter entity is expanded inline, a new Tokenizer is created
     * and its locator is set. We maintain this stack so we can notify the
     * parent Tokenizer when conditional keywords are seen in nested expansions.
     */
    private java.util.ArrayDeque<Tokenizer> tokenizerStack = new java.util.ArrayDeque<>();
    
    /**
     * Reference to the parent ContentParser for processing external entities.
     */
    private final ContentParser xmlParser;

    /**
     * The DOCTYPE name (root element name).
     */
    private String doctypeName;

    /**
     * External ID for the external DTD subset (if present).
     */
    private ExternalID doctypeExternalID;
    
    /**
     * Tracks whether any parameter entity references have been encountered in the DTD.
     * Per XML 1.0 Section 5.1, if parameter entity references are present, undeclared
     * general entities become validity errors rather than well-formedness errors.
     */
    private boolean hasParameterEntityReferences = false;
    
    /**
     * The root tokenizer for the document. Used for checking standalone status.
     * Unlike 'locator', this is not changed during nested entity processing.
     */
    private Tokenizer rootTokenizer;
    
    /**
     * Track whether we saw SYSTEM or PUBLIC keyword.
     */
    private boolean sawPublicKeyword;
    
    /**
     * Track whether required whitespace was seen after SYSTEM/PUBLIC keyword.
     */
    private boolean sawWhitespaceAfterKeyword;
    
    /**
     * Track quote depth to detect empty quoted strings.
     * Incremented on opening quote, decremented on closing quote.
     */
    private int quoteDepth;
    
    /**
     * Track which quote token (QUOT or APOS) opened the current quoted string.
     * Only a matching close quote should close it.
     */
    private Token openingQuoteToken;
    
    /**
     * Track whether required whitespace was seen after public ID.
     * Used to enforce whitespace between public and system IDs.
     */
    private boolean sawWhitespaceAfterPublicId;
    
    /**
     * Track whether we've seen the closing quote of the public ID.
     * Used to distinguish the closing quote of public ID from opening quote of system ID.
     */
    private boolean sawClosingQuoteOfPublicId;

    /**
     * Buffer for coalescing NAME tokens across parameter entity boundaries.
     * When a parameter entity expands to partial names (e.g., %e1;%e2; → "do" + "c" = "doc"),
     * we need to coalesce them into a single NAME token.
     */
    private StringBuilder nameCoalesceBuffer = null;
    
    /**
     * Entity stack depth when the current ATTLIST declaration started.
     * Used to detect when we've returned to the base level and should emit coalesced names.
     */
    private int attlistBaseDepth = -1;
    
    /**
     * Tracks whether we have pending whitespace from PE expansion that needs to be emitted.
     * This is the trailing whitespace from a PE that serves as separator before the next token.
     */
    private boolean pendingWhitespaceFromPE = false;
    
    /**
     * Tracks whether we've seen a PARAMETERENTITYREF since the last whitespace.
     * If true, the next NAME should be coalesced (it's from a different PE).
     * If false, the next NAME is from the same entity and should be treated as separate.
     */
    private boolean sawPERefSinceWhitespace = false;

    /**
     * Element declarations: element name → ElementDeclaration.
     * Uses HashMap for O(1) lookup during validation.
     * Keys are interned strings.
     */
    private Map<String, ElementDeclaration> elementDecls;

    /**
     * Attribute declarations: element name → (attribute name → AttributeDeclaration).
     * Two-level map structure for efficient lookup by element and attribute.
     * Uses HashMap as DTDs are typically small (dozens to hundreds of declarations)
     * and O(1) lookup is more important than sorted iteration.
     * All keys are interned strings for fast comparison.
     */
    private Map<String, Map<String, AttributeDeclaration>> attributeDecls;
    
    /**
     * Entity declarations: entity name → EntityDeclaration.
     * Stores general entities (referenced as &amp;name;).
     * Keys are interned strings.
     */
    private Map<String, EntityDeclaration> entities;
    
    /**
     * Parameter entity declarations: entity name → EntityDeclaration.
     * Stores parameter entities (referenced as %name; in DTD).
     * Keys are interned strings.
     */
    private Map<String, EntityDeclaration> parameterEntities;
    
    /**
     * Notation declarations: notation name → ExternalID.
     * Keys are interned strings.
     */
    private Map<String, ExternalID> notations;

    /**
     * Depth tracking for nested structures (e.g., conditional sections).
     */
    private int nestingDepth = 0;
    
    /**
     * Buffer for tokens when in buffering mode (forward parameter entity references).
     * When we encounter a parameter entity reference to an undeclared entity in the
     * external subset, we buffer all subsequent tokens until the entity is declared.
     */
    private List<TokenStreamEvent> tokenStreamBuffer;
    
    /**
     * Set of parameter entity names we're waiting for to be declared.
     * When this set is non-empty, we're in buffering mode.
     * When it becomes empty, we process the buffered tokens and resume normal processing.
     */
    private java.util.Set<String> unresolvedParameterEntities;
    
    /**
     * Sub-states for parsing conditional sections.
     */
    private enum ConditionalSectionState {
        EXPECT_KEYWORD,      // After <![, expecting INCLUDE/IGNORE or %
        AFTER_KEYWORD,       // After keyword, expecting whitespace
        EXPECT_OPEN_BRACKET, // After whitespace, expecting [
    }
    
    private ConditionalSectionState conditionalState;
    private int conditionalDepth = 0; // Track nesting depth of conditional sections
    private boolean conditionalIsInclude; // true for INCLUDE, false for IGNORE
    
    /**
     * Current element declaration parser (null when not parsing &lt;!ELEMENT).
     * Created on START_ELEMENTDECL, parses until GT is encountered.
     */
    private ElementDeclParser elementDeclParser;
    
    /**
     * Current attribute list declaration parser (null when not parsing &lt;!ATTLIST).
     * Created on START_ATTLISTDECL, parses until GT is encountered.
     */
    private AttListDeclParser attListDeclParser;
    
    /**
     * Current notation declaration parser (null when not parsing &lt;!NOTATION).
     * Created on START_NOTATIONDECL, parses until GT is encountered.
     */
    private NotationDeclParser notationDeclParser;
    
    /**
     * Current entity declaration parser (null when not parsing &lt;!ENTITY).
     * Created on START_ENTITYDECL, parses until GT is encountered.
     */
    private EntityDeclParser entityDeclParser;
    
    /**
     * Comment text accumulator.
     * Accumulates CDATA chunks for comments (asynchronous parsing).
     * Created on START_COMMENT, emitted on END_COMMENT.
     */
    private StringBuilder commentBuilder;
    
    /**
     * Entity depth when the current comment started.
     * Used to enforce WFC: PE Between Declarations - markup declarations
     * (including comments) must not span entity boundaries.
     */
    private int commentStartEntityDepth = -1;
    
    /**
     * Processing instruction accumulators.
     * PI target captured as single NAME token, PI data accumulated across CDATA chunks.
     * Created on START_PI, emitted on END_PI.
     */
    private String piTarget;
    private StringBuilder piDataBuilder;
    
    /**
     * Entity depth when the current PI started.
     * Used to enforce WFC: PE Between Declarations - markup declarations
     * (including PIs) must not span entity boundaries.
     */
    private int piStartEntityDepth = -1;
    
    /**
     * Current notation declaration being parsed.
     * 
     * <p>currentNotationName: The notation name
     * <p>currentNotationExternalID: The external ID being built (SYSTEM or PUBLIC)
     * <p>sawPublicInNotation: Track if we saw PUBLIC keyword (vs SYSTEM)
     * 
     * <p>When GT is encountered, the notation is added to the notations map.
     */

    /**
     * Constructs a new DTDParser.
     * @param xmlParser the parent ContentParser for processing external entities
     */
    public DTDParser(ContentParser xmlParser) {
        this.xmlParser = xmlParser;
        // Initialize entity stack (includes document entity by default)
        // Use a locator accessor method from ContentParser
        this.entityStack = new EntityStack(this, xmlParser.getLocator());
        // Inherit the document's XML version from the ContentParser
        if (!entityStack.isEmpty()) {
            entityStack.peek().xml11 = xmlParser.isXml11();
        }
    }

    @Override
    public void setLocator(Locator locator) {
        this.locator = locator;
        // Store the first tokenizer as the root (for standalone checks)
        if (rootTokenizer == null && locator instanceof Tokenizer) {
            rootTokenizer = (Tokenizer) locator;
        }
    }

    /**
     * Sets the DTD handler for receiving DTD events.
     * @param handler the DTD handler
     */
    public void setContentHandler(ContentHandler handler) {
        this.contentHandler = handler;
    }
    
    public void setDTDHandler(DTDHandler handler) {
        this.dtdHandler = handler;
    }

    /**
     * Sets the lexical handler for receiving lexical events.
     * @param handler the lexical handler
     */
    public void setLexicalHandler(LexicalHandler handler) {
        this.lexicalHandler = handler;
    }

    /**
     * Sets the error handler for reporting errors.
     * @param handler the error handler
     */
    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }
    
    /**
     * Reports a fatal error through the error handler and returns the exception.
     * Implements TokenConsumer interface.
     * 
     * @param message the error message
     * @return the SAXException to throw
     * @throws SAXException if the ErrorHandler itself throws
     */
    @Override
    public SAXException fatalError(String message) throws SAXException {
        SAXParseException exception = new SAXParseException(message, locator);
        if (errorHandler != null) {
            errorHandler.fatalError(exception);
        }
        return exception;
    }

    SAXException fatalError(String message, Locator locator) throws SAXException {
        SAXParseException exception = new SAXParseException(message, locator);
        if (errorHandler != null) {
            errorHandler.fatalError(exception);
        }
        return exception;
    }
    
    SAXException fatalError(String message, Locator locator, Exception e) throws SAXException {
        SAXParseException exception = new SAXParseException(message, locator, e);
        if (errorHandler != null) {
            errorHandler.fatalError(exception);
        }
        return exception;
    }
    
    
    @Override
    public void tokenizerState(TokenizerState state) {
        // Track the tokenizer's current state for entity expansion
        this.currentTokenizerState = state;
        
        // If we're expanding a parameter entity in a conditional section context,
        // track the nested tokenizer's state to sync the parent tokenizer later
        // We identify the nested tokenizer by checking if the locator matches it
        if (this.state == State.IN_CONDITIONAL &&
            currentNestedTokenizer != null &&
            this.locator == currentNestedTokenizer &&
            (state == TokenizerState.CONDITIONAL_SECTION_INCLUDE ||
             state == TokenizerState.CONDITIONAL_SECTION_IGNORE)) {
            // Track the nested tokenizer's final state for syncing the parent
            this.nestedTokenizerFinalState = state;
        }
    }
    
    @Override
    public void xmlVersion(boolean xml11) {
        // Update the XML version at the current entity expansion level (top of stack)
        // This is called when a tokenizer parses an XML/text declaration
        entityStack.xmlVersion(xml11);
    }

    /**
     * Checks if this parser can receive more tokens.
     * <p>
     * This method is called by the ContentParser before delegating each token.
     * It returns true while the DTDParser is still processing tokens inside
     * the doctypedecl production, and false when the final GT is detected.
     * <p>
     * When this returns false, the ContentParser knows to stop delegating tokens
     * to the DTDParser and resume normal parsing.
     *
     * @param token the token that would be received
     * @return true if this parser can receive the token, false otherwise
     */
    public boolean canReceive(Token token) {
        return state != State.DONE;
    }
    
    /**
     * Validates a public ID according to XML spec.
     * Public IDs may only contain: space, CR, LF, letters, digits,
     * and the punctuation: - ' () + , . / : = ? ; ! * # @ $ _ %
     * Package-private to allow access from NotationDeclParser.
     * 
     * @param publicId the public ID to validate
     * @throws SAXException if the public ID contains illegal characters
     */
    void validatePublicId(String publicId) throws SAXException {
        for (int i = 0; i < publicId.length(); i++) {
            char c = publicId.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                           (c >= '0' && c <= '9') || c == ' ' || c == '\r' || c == '\n' ||
                           c == '-' || c == '\'' || c == '(' || c == ')' || c == '+' ||
                           c == ',' || c == '.' || c == '/' || c == ':' || c == '=' ||
                           c == '?' || c == ';' || c == '!' || c == '*' || c == '#' ||
                           c == '@' || c == '$' || c == '_' || c == '%';
            if (!valid) {
                // WFC: Public ID (Production 75)
                // "Public identifiers must match the PublicChar production"
                throw fatalError("Illegal character in public ID: '" + c +
                    "' (0x" + Integer.toHexString(c) + ")", locator);
            }
        }
    }
    
    /**
     * Validates an entity or notation name in namespace-aware mode.
     * Package-private to allow access from EntityDeclParser and NotationDeclParser.
     * 
     * @param name the name to validate
     * @throws SAXException if the name contains a colon in namespace-aware mode
     */
    void validateNameInNamespaceMode(String name) throws SAXException {
        if (xmlParser != null && xmlParser.isNamespacesEnabled() && name.indexOf(':') != -1) {
            // WFC: Names (Production 5)
            // "In namespace-aware mode, names must not contain colons"
            throw fatalError(
                "Name '" + name + "' contains colon (not allowed in namespace-aware mode)", 
                locator);
        }
    }

    @Override
    public void receive(Token token, CharBuffer data) throws SAXException {
        if (state == State.DONE) {
            throw new IllegalStateException("DTDParser has finished processing");
        }
        
        // Check if we're in buffering mode (forward parameter entity references)
        if (isBuffering()) {
            // Buffer this token for later processing
            bufferToken(token, data);
            return;
        }

        switch (state) {
            case INITIAL:
                handleInitial(token, data);
                break;

            case AFTER_NAME:
                handleAfterName(token, data);
                break;

            case AFTER_SYSTEM_PUBLIC:
                handleAfterSystemPublic(token, data);
                break;

            case AFTER_PUBLIC_ID:
                handleAfterPublicId(token, data);
                break;

            case AFTER_EXTERNAL_ID:
                handleAfterExternalId(token, data);
                break;

            case IN_INTERNAL_SUBSET:
                handleInInternalSubset(token, data);
                break;

            case AFTER_INTERNAL_SUBSET:
                handleAfterInternalSubset(token, data);
                break;
                
            case IN_ELEMENTDECL:
                // Handle parameter entity expansion before delegating
                if (token == Token.PARAMETERENTITYREF) {
                    checkAndExpandParameterEntityRef(data.toString(), token, data);
                } else {
                    handleInElementDecl(token, data);
                }
                break;
                
            case IN_ATTLISTDECL:
                // Handle parameter entity expansion based on context
                if (token == Token.PARAMETERENTITYREF) {
                    // In external subset: expand PE refs inline (they can appear anywhere)
                    // In internal subset: let AttListDeclParser handle them (only allowed in default values)
                    if (xmlParser.isProcessingExternalEntity()) {
                        // Mark that we saw a PE reference - this affects name coalescing
                        // If we're coalescing names and see a PE ref, the next NAME should be coalesced
                        if (nameCoalesceBuffer != null) {
                            sawPERefSinceWhitespace = true;
                        }
                        // External subset - expand inline
                        String refName = data.toString();
                        expandParameterEntityInline(refName, token, data);
                    } else {
                        // Internal subset - delegate to AttListDeclParser
                        // It will accept PE refs in default values, reject elsewhere
                        if (attListDeclParser != null && attListDeclParser.handleToken(token, data)) {
                            attListDeclParser = null;
                            state = savedState;
                        }
                    }
                } else if (attListDeclParser != null) {
                    // Handle name coalescing for tokens from parameter entity expansion
                    // When %e3; expands to %e1;%e2; which expands to "do" + "c", we need to
                    // coalesce these into a single NAME token "doc"
                    // NOTE: Coalescing only applies to the ELEMENT NAME, not to attribute names
                    int currentDepth = entityStack.size();
                    boolean insidePE = currentDepth > attlistBaseDepth;
                    boolean inElementNamePhase = attListDeclParser.isInElementNamePhase();
                    
                    if (token == Token.NAME && insidePE && inElementNamePhase) {
                        // NAME token from inside entity expansion while expecting element name
                        // Check if we should coalesce or treat as separate name
                        if (pendingWhitespaceFromPE && !sawPERefSinceWhitespace) {
                            // Whitespace was seen, and NO PE reference since then
                            // This means they're separate names in the same entity value (like "doc a1")
                            // Emit the coalesced name first, then the whitespace, then this NAME
                            if (nameCoalesceBuffer != null) {
                                String coalescedName = nameCoalesceBuffer.toString();
                                nameCoalesceBuffer = null;
                                if (!coalescedName.isEmpty()) {
                                    CharBuffer coalescedData = CharBuffer.wrap(coalescedName);
                                    if (attListDeclParser.handleToken(Token.NAME, coalescedData)) {
                                        attListDeclParser = null;
                                        state = savedState;
                                        attlistBaseDepth = -1;
                                        pendingWhitespaceFromPE = false;
                                        sawPERefSinceWhitespace = false;
                                        break;
                                    }
                                }
                            }
                            // Emit the pending whitespace
                            pendingWhitespaceFromPE = false;
                            CharBuffer wsData = CharBuffer.wrap(" ");
                            if (attListDeclParser.handleToken(Token.S, wsData)) {
                                attListDeclParser = null;
                                state = savedState;
                                attlistBaseDepth = -1;
                                sawPERefSinceWhitespace = false;
                                break;
                            }
                            // Now pass this NAME token
                            if (attListDeclParser.handleToken(token, data)) {
                                attListDeclParser = null;
                                state = savedState;
                                attlistBaseDepth = -1;
                                sawPERefSinceWhitespace = false;
                            }
                        } else {
                            // Either no whitespace before, or we saw a PE reference since the whitespace
                            // (meaning this NAME is from a different PE) - coalesce
                            pendingWhitespaceFromPE = false; // Clear pending whitespace
                            sawPERefSinceWhitespace = false;
                            if (nameCoalesceBuffer == null) {
                                nameCoalesceBuffer = new StringBuilder();
                            }
                            nameCoalesceBuffer.append(data.toString());
                        }
                    } else if (token == Token.S && insidePE && nameCoalesceBuffer != null && inElementNamePhase) {
                        // Whitespace from inside entity expansion while coalescing element name
                        // Don't pass to AttListDeclParser yet - we might need to coalesce more
                        // Only mark pending whitespace if we haven't seen a PE ref since the last whitespace
                        // (If we saw a PE ref, the whitespace is between PEs and should be ignored)
                        if (!sawPERefSinceWhitespace) {
                            pendingWhitespaceFromPE = true;
                        }
                        // Don't reset sawPERefSinceWhitespace here - it will be reset when we see a NAME
                    } else if (!insidePE) {
                        // Token from base level (not inside PE expansion)
                        // First, emit any coalesced name
                        if (nameCoalesceBuffer != null) {
                            String coalescedName = nameCoalesceBuffer.toString();
                            nameCoalesceBuffer = null;
                            
                            if (!coalescedName.isEmpty()) {
                                // Create a CharBuffer for the coalesced name and pass to AttListDeclParser
                                CharBuffer coalescedData = CharBuffer.wrap(coalescedName);
                                if (attListDeclParser.handleToken(Token.NAME, coalescedData)) {
                                    attListDeclParser = null;
                                    state = savedState;
                                    attlistBaseDepth = -1;
                                    pendingWhitespaceFromPE = false;
                                    break; // Declaration complete
                                }
                            }
                            
                            // If there was pending whitespace from PE, emit it as separator
                            if (pendingWhitespaceFromPE) {
                                pendingWhitespaceFromPE = false;
                                CharBuffer wsData = CharBuffer.wrap(" ");
                                if (attListDeclParser.handleToken(Token.S, wsData)) {
                                    attListDeclParser = null;
                                    state = savedState;
                                    attlistBaseDepth = -1;
                                    break; // Declaration complete
                                }
                            }
                        }
                        
                        // Now handle the current token
                        if (attListDeclParser.handleToken(token, data)) {
                            // ATTLIST declaration complete - return to saved state
                            attListDeclParser = null;
                            state = savedState;
                            attlistBaseDepth = -1;
                        }
                    } else {
                        // Inside PE but either:
                        // - Not in element name phase (so NAME/S tokens should pass through)
                        // - Or non-NAME, non-S token (like APOS, CDATA, CDATA_TYPE, etc.)
                        // Pass to AttListDeclParser (except PARAMETERENTITYREF which is handled above)
                        if (token != Token.PARAMETERENTITYREF) {
                            // Not a PE reference - pass to AttListDeclParser
                            if (attListDeclParser.handleToken(token, data)) {
                                attListDeclParser = null;
                                state = savedState;
                                attlistBaseDepth = -1;
                                pendingWhitespaceFromPE = false;
                            }
                        }
                        // For PARAMETERENTITYREF, the expansion happens elsewhere
                    }
                }
                break;
                
            case IN_ENTITYDECL:
                // Handle parameter entity expansion before delegating
                if (token == Token.PARAMETERENTITYREF) {
                    // If we're inside an entity value, let EntityDeclParser handle it
                    // (it will store it as a ParameterEntityReference for later expansion)
                    // Otherwise, expand it inline
                    if (entityDeclParser != null && entityDeclParser.isInEntityValue()) {
                        // Inside entity value - delegate to EntityDeclParser
                        if (entityDeclParser.handleToken(token, data)) {
                            entityDeclParser = null;
                            state = savedState;
                        }
                    } else {
                        // Not in entity value - expand inline
                        checkAndExpandParameterEntityRef(data.toString(), token, data);
                    }
                } else if (entityDeclParser != null && entityDeclParser.handleToken(token, data)) {
                    // ENTITY declaration complete - return to saved state
                    entityDeclParser = null;
                    state = savedState;
                }
                break;
            
            case IN_NOTATIONDECL:
                // Handle parameter entity expansion before delegating
                if (token == Token.PARAMETERENTITYREF) {
                    checkAndExpandParameterEntityRef(data.toString(), token, data);
                } else if (notationDeclParser != null && notationDeclParser.handleToken(token, data)) {
                    // NOTATION declaration complete - return to saved state
                    notationDeclParser = null;
                    state = savedState;
                }
                break;
                
            case IN_COMMENT:
                handleInComment(token, data);
                break;
                
            case IN_PI:
                handleInPI(token, data);
                break;
                
            case IN_CONDITIONAL:
                handleInConditional(token, data);
                break;
                
            case IN_CONDITIONAL_INCLUDE:
                handleInConditionalInclude(token, data);
                break;
                
            case IN_CONDITIONAL_IGNORE:
                handleInConditionalIgnore(token, data);
                break;
        }
    }

    /**
     * Handles tokens in the INITIAL state (expecting DOCTYPE name).
     */
    private void handleInitial(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace before name, ignore
                break;

            case NAME:
                // This is the DOCTYPE name (root element name)
                doctypeName = data.toString();
                changeState(State.AFTER_NAME);
                break;

            default:
                // WFC: Document Type Declaration (Production 28)
                // "The Name in the document type declaration must match the element type of the root element"
                throw fatalError("Expected name after &lt;!DOCTYPE, got: " + token, locator);
        }
    }

    /**
     * Handles tokens after reading the DOCTYPE name.
     */
    private void handleAfterName(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                startComment();
                break;

            case SYSTEM:
                // SYSTEM external ID
                sawPublicKeyword = false;
                sawWhitespaceAfterKeyword = false; // Reset - need whitespace after keyword
                sawWhitespaceAfterPublicId = false; // Reset for new external ID
                quoteDepth = 0; // Reset quote depth
                openingQuoteToken = null; // Reset opening quote tracker
                changeState(State.AFTER_SYSTEM_PUBLIC);
                break;

            case PUBLIC:
                // PUBLIC external ID
                sawPublicKeyword = true;
                sawWhitespaceAfterKeyword = false; // Reset - need whitespace after keyword
                sawWhitespaceAfterPublicId = false; // Reset for new external ID
                sawClosingQuoteOfPublicId = false; // Reset for new public ID
                quoteDepth = 0; // Reset quote depth
                openingQuoteToken = null; // Reset opening quote tracker
                changeState(State.AFTER_SYSTEM_PUBLIC);
                break;

            case OPEN_BRACKET:
                // Internal subset starts
                changeState(State.IN_INTERNAL_SUBSET);
                nestingDepth = 1;
                reportStartDTD();
                break;

            case GT:
                // End of DOCTYPE (no external ID, no internal subset)
                changeState(State.DONE);
                // Expand parameter entities in entity values (post-processing)
                expandParameterEntitiesInEntityValues();
                // Report start and end of DTD
                reportStartDTD();
                if (lexicalHandler != null) {
                    lexicalHandler.endDTD();
                }
                break;

            default:
                // WFC: Document Type Declaration (Production 28)
                // "After the Name, the DOCTYPE declaration must contain either ExternalID or InternalSubset or both"
                throw fatalError("Unexpected token after DOCTYPE name: " + token, locator);
        }
    }

    /**
     * Handles tokens after SYSTEM or PUBLIC keyword.
     */
    private void handleAfterSystemPublic(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace after keyword
                sawWhitespaceAfterKeyword = true;
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                startComment();
                break;

            case QUOT:
            case APOS:
                // Quotes around external IDs
                // Enforce whitespace after SYSTEM/PUBLIC keyword
                if (!sawWhitespaceAfterKeyword) {
                    String keyword = sawPublicKeyword ? "PUBLIC" : "SYSTEM";
                    // WFC: External ID (Production 75)
                    // "Whitespace is required after SYSTEM or PUBLIC keyword"
                    throw fatalError("Expected whitespace after " + keyword + " keyword in DOCTYPE", locator);
                }
                
                // Track quote depth to detect empty strings
                if (quoteDepth == 0) {
                    // Opening quote
                    quoteDepth = 1;
                } else {
                    // Closing quote - check if this completes an empty string
                    quoteDepth = 0;
                    
                    // If PUBLIC and we haven't set publicId yet, this was an empty public ID
                    if (sawPublicKeyword) {
                        if (doctypeExternalID == null) {
                            doctypeExternalID = new ExternalID();
                        }
                        if (doctypeExternalID.publicId == null) {
                            doctypeExternalID.publicId = ""; // Empty public ID
                            changeState(State.AFTER_PUBLIC_ID);
                        }
                    }
                    // If we're in AFTER_PUBLIC_ID and haven't set systemId yet, this was an empty system ID
                    else if (state == State.AFTER_PUBLIC_ID) {
                        if (doctypeExternalID != null && doctypeExternalID.systemId == null) {
                            doctypeExternalID.systemId = ""; // Empty system ID
                            changeState(State.AFTER_EXTERNAL_ID);
                        }
                    }
                }
                break;

            case CDATA:
                // This is the ID string
                // Reset quote depth since we're inside a non-empty quoted string
                quoteDepth = 1;
                
                if (sawPublicKeyword) {
                    // PUBLIC keyword - first string is publicId, second is systemId
                    if (doctypeExternalID == null) {
                        doctypeExternalID = new ExternalID();
                    }
                    if (doctypeExternalID.publicId == null) {
                        String publicId = data.toString();
                        validatePublicId(publicId);
                        doctypeExternalID.publicId = publicId;
                        changeState(State.AFTER_PUBLIC_ID);
                    } else {
                        // Second string after PUBLIC
                        doctypeExternalID.systemId = data.toString();
                        validateSystemId(doctypeExternalID.systemId);
                        changeState(State.AFTER_EXTERNAL_ID);
                    }
                } else {
                    // SYSTEM keyword - only one string, and it's the systemId
                    if (doctypeExternalID == null) {
                        doctypeExternalID = new ExternalID();
                    }
                    doctypeExternalID.systemId = data.toString();
                    validateSystemId(doctypeExternalID.systemId);
                    changeState(State.AFTER_EXTERNAL_ID);
                }
                break;

            default:
                // WFC: External ID (Production 75)
                // "External identifiers must be quoted strings"
                throw fatalError("Expected quoted string for external ID, got: " + token, locator);
        }
    }

    /**
     * Handles tokens after reading the public ID.
     */
    private void handleAfterPublicId(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace after public ID - required before system ID
                sawWhitespaceAfterPublicId = true;
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                startComment();
                break;

            case QUOT:
            case APOS:
                // Track which quote we're seeing
                if (!sawClosingQuoteOfPublicId) {
                    // First quote we see is the closing quote of the public ID
                    sawClosingQuoteOfPublicId = true;
                } else {
                    // Subsequent quotes - must have whitespace before them (opening quote of system ID)
                    // This is the opening quote of system ID - just ignore and wait for CDATA
                    // But we need to have seen whitespace first
                }
                break;

            case CDATA:
                // This is the system ID - must have seen whitespace and quote first
                if (!sawWhitespaceAfterPublicId) {
                    // WFC: External ID (Production 75)
                    // "Whitespace is required between public ID and system ID"
                    throw fatalError("Expected whitespace between public ID and system ID", locator);
                }
                if (doctypeExternalID == null) {
                    doctypeExternalID = new ExternalID();
                }
                doctypeExternalID.systemId = data.toString();
                validateSystemId(doctypeExternalID.systemId);
                changeState(State.AFTER_EXTERNAL_ID);
                break;

            default:
                // WFC: External ID (Production 75)
                // "PUBLIC declarations must have both public ID and system ID"
                throw fatalError("Expected system ID after public ID, got: " + token, locator);
        }
    }

    /**
     * Handles tokens after reading the external ID.
     */
    private void handleAfterExternalId(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case QUOT:
            case APOS:
                // Quote closing system ID, ignore
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                startComment();
                break;

            case OPEN_BRACKET:
                // Internal subset starts (after external ID)
                // Report start of DTD
                reportStartDTD();
                // Enter internal subset state to process [ ... ] content FIRST
                // External DTD subset will be processed AFTER internal subset closes
                // This allows PEs declared in internal subset to be available in external subset
                changeState(State.IN_INTERNAL_SUBSET);
                nestingDepth = 1;
                break;

            case GT:
                // End of DOCTYPE (external ID, no internal subset)
                // Expand parameter entities in entity values (post-processing)
                expandParameterEntitiesInEntityValues();
                reportStartDTD();
                // Enter internal subset state (even though there's no [ ... ])
                // This allows external DTD tokens to be processed as internal subset
                changeState(State.IN_INTERNAL_SUBSET);
                nestingDepth = 0; // No bracket nesting
                // Process external DTD subset (tokens processed as internal subset)
                processExternalDTDSubset();
                
                // After processing external subset, check for unresolved parameter entities
                // Only check if we were in buffering mode (encountered undefined entities)
                // WFC: Entity Declared (Section 4.1)
                // "For a well-formed document, the Name given in the entity reference must match
                // that in an entity declaration"
                if (isBuffering()) {
                    // There are parameter entities that were referenced but never declared
                    String entityName = unresolvedParameterEntities.iterator().next();
                    throw fatalError(
                        "Undefined parameter entity: %" + entityName + ";",
                        locator);
                }
                
                // External DTD processing complete, now we're done
                changeState(State.DONE);
                // Report end of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.endDTD();
                }
                break;

            default:
                // WFC: Document Type Declaration (Production 28)
                // "After ExternalID, the DOCTYPE declaration must contain InternalSubset or GT"
                throw fatalError("Unexpected token after external ID: " + token, locator);
        }
    }

    /**
     * Handles tokens within the internal subset (between [ and ]).
     */
    private void handleInInternalSubset(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CLOSE_BRACKET:
                // End of internal subset
                // However, if we see CLOSE_BRACKET and conditionalDepth > 0, it means
                // we have an improperly terminated conditional section (should be ]]> not ]>)
                if (conditionalDepth > 0) {
                    // WFC: Conditional Sections (Production 61)
                    // "Conditional sections must be properly terminated with ']]>'"
                    throw fatalError(
                        "Conditional section not properly terminated (expected ']]>' but got ']>')",
                        locator);
                }
                nestingDepth--;
                if (nestingDepth == 0) {
                    changeState(State.AFTER_INTERNAL_SUBSET);
                }
                break;

            case OPEN_BRACKET:
                // Nested bracket (e.g., in conditional sections)
                nestingDepth++;
                break;

            case START_ELEMENTDECL:
                // Start parsing element declaration - create dedicated parser
                savedState = state;
                state = State.IN_ELEMENTDECL;
                elementDeclParser = new ElementDeclParser(this, locator);
                break;

            case START_ATTLISTDECL:
                // Start parsing attribute list declaration - instantiate parser
                savedState = state;
                state = State.IN_ATTLISTDECL;
                // Track if this declaration comes from external DTD subset
                boolean fromExternal = xmlParser.isProcessingExternalEntity();
                attListDeclParser = new AttListDeclParser(this, locator, fromExternal);
                // Track the entity stack depth for name coalescing
                attlistBaseDepth = entityStack.size();
                nameCoalesceBuffer = null;
                break;

            case START_ENTITYDECL:
                // Start parsing entity declaration - instantiate parser
                savedState = state;
                state = State.IN_ENTITYDECL;
                entityDeclParser = new EntityDeclParser(this, locator, savedState);
                break;

            case START_NOTATIONDECL:
                // Start parsing notation declaration - instantiate parser
                savedState = state;
                state = State.IN_NOTATIONDECL;
                notationDeclParser = new NotationDeclParser(this, locator);
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                startComment();
                break;

            case START_PI:
                // Processing instruction - just enter PI state (savedState already contains current state)
                piTarget = null;
                piDataBuilder = new StringBuilder();
                piStartEntityDepth = entityStack.size();
                state = State.IN_PI;
                break;

            case START_CONDITIONAL:
                // Conditional sections are ONLY allowed in external DTD subset
                // Check if we're processing an external entity
                if (!xmlParser.isProcessingExternalEntity()) {
                    // WFC: Conditional Sections (Production 61)
                    // "Conditional sections are only allowed in external DTD subset"
                    throw fatalError(
                        "Conditional sections are not allowed in internal DTD subset", locator);
                }
                // Enter conditional section parsing
                conditionalState = ConditionalSectionState.EXPECT_KEYWORD;
                conditionalSavedState = state; // Save current state to return to after conditional section
                state = State.IN_CONDITIONAL; // Don't use changeState() to preserve conditionalSavedState
                break;

            case PARAMETERENTITYREF:
                // Parameter entity expansion in DTD markup
                // Direct parameter entity references in DTD declarations (not in entity values)
                // Example: <!ELEMENT doc %content-model;>
                // This requires inline expansion during DTD parsing.
                String refName = data.toString();
                expandParameterEntityInline(refName, token, data);
                break;
                    
            case START_CDATA:
                // CDATA sections are not allowed in DTD
                // WFC: CDATA Sections (Production 18)
                // "CDATA sections are only allowed in element content, not in DTD"
                throw fatalError("CDATA sections are not allowed in DOCTYPE internal subset", locator);

            default:
                // Other tokens within declarations, ignore for now
                break;
        }
    }

    /**
     * Handles tokens after the internal subset closing bracket.
     */
    private void handleAfterInternalSubset(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case START_COMMENT:
                // Comment - just enter comment state (savedState already contains current state)
                startComment();
                break;

            case GT:
                // End of DOCTYPE - process external subset NOW (after internal subset)
                // This ensures PEs declared in internal subset are available in external subset
                if (doctypeExternalID != null) {
                    // Re-enter IN_INTERNAL_SUBSET state temporarily for external DTD processing
                    changeState(State.IN_INTERNAL_SUBSET);
                    nestingDepth = 0; // No bracket nesting for external subset
                    processExternalDTDSubset();
                    
                    // After processing external subset, check for unresolved parameter entities
                    // Only check if we were in buffering mode (encountered undefined entities)
                    // WFC: Entity Declared (Section 4.1)
                    // "For a well-formed document, the Name given in the entity reference must match
                    // that in an entity declaration"
                    if (isBuffering()) {
                        // There are parameter entities that were referenced but never declared
                        String entityName = unresolvedParameterEntities.iterator().next();
                        throw fatalError(
                            "Undefined parameter entity: %" + entityName + ";",
                            locator);
                    }
                }
                // Now we're done
                changeState(State.DONE);
                // Expand parameter entities in entity values (post-processing)
                expandParameterEntitiesInEntityValues();
                // Report end of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.endDTD();
                }
                break;

            default:
                // WFC: Document Type Declaration (Production 28)
                // "The DOCTYPE declaration must end with '>'"
                throw fatalError("Expected GT after internal subset, got: " + token, locator);
        }
    }

    /**
     * Handles tokens within an &lt;!ELEMENT declaration.
     * Uses a sub-state machine to enforce well-formedness constraints.
     * Builds the ContentModel tree incrementally using a stack.
     */
    private void handleInElementDecl(Token token, CharBuffer data) throws SAXException {
        // Delegate to ElementDeclParser
        if (elementDeclParser != null) {
            boolean complete = elementDeclParser.receive(token, data);
            if (complete) {
                // Declaration complete, return to saved state
                state = savedState;
                elementDeclParser = null;
            }
        } else {
            // WFC: Element Type Declaration (Production 45)
            // "Element declarations must be properly formed"
            throw fatalError("No element declaration parser active", locator);
        }
    }
    
    /**
     * Handles tokens within a comment (&lt;!-- ... --&gt;).
     * Accumulates CDATA chunks until END_COMMENT is encountered.
     * Comments can appear in various locations within the DTD.
     */
    private void handleInComment(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CDATA:
            case S:
                // Accumulate comment text (may receive multiple chunks)
                if (data != null) {
                    commentBuilder.append(data.toString());
                }
                break;
                
            case END_COMMENT:
                // End of comment - validate entity boundaries first (WFC: PE Between Declarations)
                int currentEntityDepth = entityStack.size();
                if (currentEntityDepth != commentStartEntityDepth) {
                    throw fatalError(
                        "Comment markup declaration spans entity boundary (WFC: PE Between Declarations). " +
                        "Started at entity depth " + commentStartEntityDepth + " but ended at depth " + currentEntityDepth,
                        locator);
                }
                
                // Emit event and return to saved state
                if (lexicalHandler != null) {
                    String text = commentBuilder.toString();
                    lexicalHandler.comment(text.toCharArray(), 0, text.length());
                }
                commentBuilder = null;
                commentStartEntityDepth = -1;
                state = savedState;
                break;
                
            default:
                // Other tokens might be part of comment content
                // (tokenizer should emit everything as CDATA within comments)
                break;
        }
    }
    
    /**
     * Handles tokens within a processing instruction (&lt;? ... ?&gt;).
     * First token must be NAME (PI target), followed by optional data (CDATA chunks), then END_PI.
     * PIs can appear in various locations within the DTD.
     */
    private void handleInPI(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case NAME:
                // First token should be the PI target
                if (piTarget == null) {
                    piTarget = data.toString();
                } else {
                    throw fatalError("Unexpected NAME token in PI data", locator);
                }
                break;
                
            case S:
            case CDATA:
                // Accumulate PI data (may receive multiple chunks)
                if (data != null) {
                    piDataBuilder.append(data.toString());
                }
                break;
                
            case END_PI:
                // End of PI - validate entity boundaries first (WFC: PE Between Declarations)
                int currentEntityDepth = entityStack.size();
                if (currentEntityDepth != piStartEntityDepth) {
                    throw fatalError(
                        "Processing instruction markup declaration spans entity boundary (WFC: PE Between Declarations). " +
                        "Started at entity depth " + piStartEntityDepth + " but ended at depth " + currentEntityDepth,
                        locator);
                }
                
                // Emit event and return to saved state
                if (piTarget == null) {
                    throw fatalError("Processing instruction missing target", locator);
                }
                if (contentHandler != null) {
                    contentHandler.processingInstruction(piTarget, piDataBuilder.toString());
                }
                piTarget = null;
                piDataBuilder = null;
                piStartEntityDepth = -1;
                state = savedState;
                break;
                
            default:
                // Unexpected token in PI
                throw fatalError("Unexpected token in processing instruction: " + token, locator);
        }
    }
    
    /**
     * Handles tokens in the IN_CONDITIONAL state (after &lt;![, expecting keyword).
     */
    private void handleInConditional(Token token, CharBuffer data) throws SAXException {
        switch (conditionalState) {
            case EXPECT_KEYWORD:
                switch (token) {
                    case S:
                        // Skip whitespace before keyword
                        break;
                        
                    case INCLUDE:
                        // INCLUDE section - will process markup normally
                        conditionalIsInclude = true;
                        conditionalState = ConditionalSectionState.AFTER_KEYWORD;
                        break;
                        
                    case IGNORE:
                        // IGNORE section - will skip all content
                        conditionalIsInclude = false;
                        conditionalState = ConditionalSectionState.AFTER_KEYWORD;
                        break;
                        
                    case PARAMETERENTITYREF:
                        // Parameter entity reference for keyword (e.g., <![ %draft; [...)
                        // Expand inline to get INCLUDE or IGNORE
                        String refName = data.toString();
                        expandParameterEntityInline(refName, token, data);
                        // After expansion, we should receive INCLUDE or IGNORE token
                        break;
                        
                    default:
                        throw fatalError(
                            "Expected INCLUDE, IGNORE, or parameter entity reference in conditional section, got: " + token,
                            locator);
                }
                break;
                
            case AFTER_KEYWORD:
                switch (token) {
                    case S:
                        // Skip whitespace after keyword
                        conditionalState = ConditionalSectionState.EXPECT_OPEN_BRACKET;
                        break;
                        
                    case OPEN_BRACKET:
                        // '[' directly after keyword (whitespace is optional per XML spec)
                        // Start of conditional section content
                        conditionalDepth++;
                        // Transition to INCLUDE or IGNORE mode based on keyword
                        // savedState already points to the context we'll return to when depth reaches 0
                        if (conditionalIsInclude) {
                            state = State.IN_CONDITIONAL_INCLUDE; // Don't use changeState() to preserve savedState
                        } else {
                            state = State.IN_CONDITIONAL_IGNORE; // Don't use changeState() to preserve savedState
                        }
                        break;
                        
                    default:
                        throw fatalError(
                            "Expected whitespace or '[' after INCLUDE/IGNORE keyword, got: " + token,
                            locator);
                }
                break;
                
            case EXPECT_OPEN_BRACKET:
                switch (token) {
                    case S:
                        // Skip additional whitespace
                        break;
                        
                    case OPEN_BRACKET:
                        // Start of conditional section content
                        conditionalDepth++;
                        // Transition to INCLUDE or IGNORE mode based on keyword
                        if (conditionalIsInclude) {
                            state = State.IN_CONDITIONAL_INCLUDE; // Don't use changeState() to preserve savedState
                        } else {
                            state = State.IN_CONDITIONAL_IGNORE; // Don't use changeState() to preserve savedState
                        }
                        break;
                        
                    default:
                        throw fatalError(
                            "Expected '[' after INCLUDE/IGNORE keyword, got: " + token,
                            locator);
                }
                break;
        }
    }
    
    /**
     * Handles tokens in the IN_CONDITIONAL_INCLUDE state (processing markup in INCLUDE section).
     */
    private void handleInConditionalInclude(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case START_CONDITIONAL:
                // Nested conditional section
                // Don't increment conditionalDepth here - it will be incremented when we see the '[' after the keyword
                // Recursively handle this nested conditional section
                conditionalState = ConditionalSectionState.EXPECT_KEYWORD;
                // Don't change savedState - it should already point to the outermost context
                // (e.g., IN_INTERNAL_SUBSET) that we'll return to when all sections close
                state = State.IN_CONDITIONAL; // Don't use changeState() to preserve savedState
                break;
                
            case END_CDATA:
                // End of conditional section - ]]>
                conditionalDepth--;
                if (conditionalDepth == 0) {
                    // End of outermost INCLUDE section, return to saved state
                    state = conditionalSavedState;
                } else {
                    // End of nested INCLUDE section, stay in INCLUDE mode
                    // (no state change needed)
                }
                break;
                
            default:
                // All other tokens are processed as normal DTD markup
                // Delegate to handleInInternalSubset
                handleInInternalSubset(token, data);
                break;
        }
    }
    
    /**
     * Handles tokens in the IN_CONDITIONAL_IGNORE state (skipping content in IGNORE section).
     */
    private void handleInConditionalIgnore(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case START_CONDITIONAL:
                // Nested conditional section within IGNORE - just track depth, don't process keywords
                // When in IGNORE mode, we ignore everything except the <![  and ]]> delimiters
                // Increment depth immediately at <![, not at the '[' after the keyword
                conditionalDepth++;
                // Stay in IGNORE mode, don't transition to IN_CONDITIONAL
                break;
                
            case END_CDATA:
                // End of conditional section - ]]>
                conditionalDepth--;
                if (conditionalDepth == 0) {
                    // End of outermost IGNORE section, return to saved state
                    state = conditionalSavedState;
                }
                // Otherwise stay in IGNORE mode for nested sections
                break;
                
            default:
                // Ignore all other tokens (don't process them)
                break;
        }
    }
    
    /**
     * Checks if a parameter entity reference is allowed in the current context.
     * Throws fatal error if in internal subset (WFC: PEs in Internal Subset).
     * 
     * @param entityName the parameter entity name
     * @param token the PARAMETERENTITYREF token
     * @param data the token data
     * @return true if the reference should be expanded inline, false if it should be rejected
     * @throws SAXException if the reference is not allowed
     */
    private boolean checkAndExpandParameterEntityRef(String entityName, Token token, CharBuffer data) throws SAXException {
        // In internal subset, PE references are not allowed within declarations
        if (!xmlParser.isProcessingExternalEntity()) {
            throw fatalError(
                "Parameter entity references are not allowed within markup declarations " +
                "in the internal subset (WFC: PEs in Internal Subset)", locator);
        }
        expandParameterEntityInline(entityName, token, data);
        return true;
    }
    
    /**
     * Initializes comment parsing state.
     * Called when START_COMMENT token is encountered.
     */
    private void startComment() {
        commentBuilder = new StringBuilder();
        commentStartEntityDepth = entityStack.size();
        state = State.IN_COMMENT;
    }
    
    /**
     * Reports start of DTD to lexical handler if present.
     * 
     * @throws SAXException if the lexical handler throws
     */
    private void reportStartDTD() throws SAXException {
        if (lexicalHandler != null) {
            String publicId = doctypeExternalID != null ? doctypeExternalID.publicId : null;
            String systemId = doctypeExternalID != null ? doctypeExternalID.systemId : null;
            lexicalHandler.startDTD(doctypeName, publicId, systemId);
        }
    }
    
    /**
     * Changes the parser state and saves the new state.
     * This ensures savedState always contains the current state for returning from comments.
     * Use this instead of direct state assignment (except for START_COMMENT).
     * 
     * @param newState the new state to transition to
     */
    private void changeState(State newState) {
        if (Parser.debug) {
            System.err.println("DTDParser.changeState: "+state+" -> "+newState);
        }
        savedState = state = newState;
        
        // If DTD parsing is complete, notify ContentParser to switch to CONTENT state
        if (newState == State.DONE && xmlParser != null) {
            xmlParser.dtdComplete();
        }
    }

    /**
     * Gets the DOCTYPE name (root element name).
     * @return the DOCTYPE name, or null if not yet parsed
     */
    public String getDoctypeName() {
        return doctypeName;
    }
    
    /**
     * Checks if we're currently processing an external entity.
     * This is a proxy to ContentParser.isProcessingExternalEntity().
     * Package-private to allow access from sub-parsers like EntityDeclParser.
     * 
     * @return true if processing external entity, false if in document entity
     */
    boolean isProcessingExternalEntity() {
        return xmlParser.isProcessingExternalEntity();
    }
    
    /**
     * Checks if the document is standalone and has an external DTD subset.
     * Used for VC: Standalone Document Declaration validation.
     * 
     * @return true if standalone="yes" and external DTD exists
     */
    boolean isStandaloneWithExternalDTD() {
        if (doctypeExternalID == null) {
            return false; // No external DTD
        }
        // Use rootTokenizer for standalone check (locator might be nested tokenizer)
        return rootTokenizer != null && rootTokenizer.isStandalone();
    }
    
    /**
     * Checks if the DTD has an external subset.
     * 
     * @return true if external DTD exists
     */
    boolean hasExternalDTD() {
        return doctypeExternalID != null;
    }
    
    /**
     * Checks if the document is standalone (standalone="yes").
     * Uses the root tokenizer to get the standalone status.
     * 
     * @return true if standalone="yes"
     */
    boolean isStandalone() {
        return rootTokenizer != null && rootTokenizer.isStandalone();
    }
    
    /**
     * Checks if validation is enabled.
     * Package-private to allow access from sub-parsers like AttListDeclParser.
     * 
     * @return true if validation is enabled
     */
    boolean getValidationEnabled() {
        return xmlParser.getValidationEnabled();
    }
    
    /**
     * Reports a validation error via the ContentParser's error handler.
     * Package-private to allow access from sub-parsers like AttListDeclParser.
     * 
     * @param message the validation error message
     * @throws SAXException if the error handler throws
     */
    void reportValidationError(String message) throws SAXException {
        xmlParser.reportValidationError(message);
    }

    /**
     * Gets the external ID for the external DTD subset.
     * @return the ExternalID, or null if not specified
     */
    public ExternalID getDoctypeExternalID() {
        return doctypeExternalID;
    }

    /**
     * Gets the public identifier for the external DTD subset.
     * @return the public ID, or null if not specified
     */
    public String getPublicId() {
        return doctypeExternalID != null ? doctypeExternalID.publicId : null;
    }

    /**
     * Gets the system identifier for the external DTD subset.
     * @return the system ID, or null if not specified
     */
    public String getSystemId() {
        return doctypeExternalID != null ? doctypeExternalID.systemId : null;
    }

    /**
     * Gets the element declaration for a given element name.
     *
     * @param elementName the element name
     * @return the ElementDeclaration, or null if not declared
     */
    public ElementDeclaration getElementDeclaration(String elementName) {
        return elementDecls != null ? elementDecls.get(elementName) : null;
    }

    /**
     * Gets the attribute declaration for a specific attribute on an element.
     *
     * @param elementName the element name
     * @param attributeName the attribute name
     * @return the AttributeDeclaration, or null if not declared
     */
    public AttributeDeclaration getAttributeDeclaration(String elementName, String attributeName) {
        if (attributeDecls == null) {
            return null;
        }
        // Intern keys for fast comparison
        elementName = elementName.intern();
        attributeName = attributeName.intern();
        
        Map<String, AttributeDeclaration> elementAttrs = attributeDecls.get(elementName);
        if (elementAttrs == null) {
            return null;
        }
        return elementAttrs.get(attributeName);
    }
    
    /**
     * Gets all attribute declarations for a specific element.
     * @param elementName the element name
     * @return Map of attribute name → AttributeDeclaration, or null if no attributes declared
     */
    public Map<String, AttributeDeclaration> getAttributeDeclarations(String elementName) {
        if (attributeDecls == null) {
            return null;
        }
        return attributeDecls.get(elementName.intern());
    }
    
    /**
     * Gets the external ID for a notation.
     * @param notationName the notation name
     * @return the ExternalID, or null if not declared
     */
    public ExternalID getNotation(String notationName) {
        if (notations == null) {
            return null;
        }
        return notations.get(notationName.intern());
    }
    
    /**
     * Returns the set of declared notation names.
     * 
     * @return set of notation names, or null if no notations declared
     */
    public java.util.Set<String> getNotationNames() {
        if (notations == null) {
            return null;
        }
        return notations.keySet();
    }
    
    /**
     * Retrieves a general entity declaration by name.
     * 
     * @param entityName the name of the entity to retrieve
     * @return the EntityDeclaration, or null if not found
     */
    public EntityDeclaration getGeneralEntity(String entityName) {
        if (entities == null) {
            return null;
        }
        return entities.get(entityName.intern());
    }
    
    /**
     * Returns whether the DTD contains any parameter entity references.
     * Per XML 1.0 Section 5.1, if parameter entity references are present,
     * undeclared general entities become validity errors rather than
     * well-formedness errors.
     * 
     * @return true if parameter entity references were encountered
     */
    public boolean hasParameterEntityReferences() {
        return hasParameterEntityReferences;
    }
    
    /**
     * Retrieves a parameter entity declaration by name.
     * 
     * @param entityName the entity name
     * @return the EntityDeclaration, or null if not found
     */
    public EntityDeclaration getParameterEntity(String entityName) {
        if (parameterEntities == null) {
            return null;
        }
        return parameterEntities.get(entityName.intern());
    }

    /**
     * Stores an element declaration.
     * Called internally when parsing &lt;!ELEMENT declarations.
     * Package-private to allow access from ElementDeclParser.
     *
     * @param decl the element declaration to store
     * @throws SAXException if validation fails
     */
    void addElementDeclaration(ElementDeclaration decl) throws SAXException {
        if (elementDecls == null) {
            elementDecls = new HashMap<>();
        }
        
        // VC: Unique Element Type Declaration (Section 3.2)
        // Each element type must be declared only once
        // Per XML 1.0 § 3.2: first declaration is binding
        String internedName = decl.name.intern();
        if (elementDecls.containsKey(internedName)) {
            // Duplicate declaration - report validation error but don't overwrite
            if (xmlParser.getValidationEnabled()) {
                xmlParser.reportValidationError(
                    "Validity Constraint: Unique Element Type Declaration (Section 3.2). " +
                    "Element type '" + decl.name + "' is declared more than once in the DTD.");
            }
            // First declaration is binding - don't overwrite
            return;
        }
        
        // Intern element name for fast comparison
        elementDecls.put(internedName, decl);
    }

    /**
     * Stores attribute declarations for an element.
     * Called internally when parsing &lt;!ATTLIST declarations.
     * Package-private to allow access from AttListDeclParser.
     *
     * @param elementName the element name these attributes belong to
     * @param attributeMap the map of attribute declarations keyed by attribute name
     * @throws SAXException if validation fails
     */
    void addAttributeDeclarations(String elementName, Map<String, AttributeDeclaration> attributeMap) throws SAXException {
        if (attributeMap == null || attributeMap.isEmpty()) {
            return;
        }
        
        // Ensure attributeDecls map exists
        if (attributeDecls == null) {
            attributeDecls = new HashMap<>();
        }
        
        // Get or create the attribute map for this element
        String internedElementName = elementName.intern();
        Map<String, AttributeDeclaration> elementAttrs = attributeDecls.get(internedElementName);
        if (elementAttrs == null) {
            // First ATTLIST for this element - use the provided map directly
            attributeDecls.put(internedElementName, attributeMap);
        } else {
            // Merge with existing attributes for this element
            // Per XML 1.0 § 3.3: first declaration is binding for each attribute
            // Only add attributes that don't already exist
            // VC: One ID per Element Type (Section 3.3.1) - check for multiple ID attributes when merging
            if (xmlParser.getValidationEnabled()) {
                AttributeDeclaration existingID = null;
                AttributeDeclaration newID = null;
                
                // Find existing ID attribute
                for (AttributeDeclaration attr : elementAttrs.values()) {
                    if ("ID".equals(attr.type)) {
                        existingID = attr;
                        break;
                    }
                }
                
                // Find new ID attribute
                for (AttributeDeclaration attr : attributeMap.values()) {
                    if ("ID".equals(attr.type)) {
                        newID = attr;
                        break;
                    }
                }
                
                // If both exist and are different, report error
                if (existingID != null && newID != null && !existingID.name.equals(newID.name)) {
                    xmlParser.reportValidationError(
                        "Validity Constraint: One ID per Element Type (Section 3.3.1). " +
                        "Element '" + elementName + "' has multiple ID attributes: " +
                        "'" + existingID.name + "' and '" + newID.name + "'.");
                }
            }
            
            // Only add attributes that don't already exist (first declaration is binding)
            for (Map.Entry<String, AttributeDeclaration> entry : attributeMap.entrySet()) {
                String attrName = entry.getKey();
                if (!elementAttrs.containsKey(attrName)) {
                    elementAttrs.put(attrName, entry.getValue());
                }
            }
        }
    }

    /**
     * Stores a notation declaration.
     * Called internally when parsing &lt;!NOTATION declarations.
     * Package-private to allow access from NotationDeclParser.
     *
     * @param notationName the notation name
     * @param externalID the external ID for the notation
     * @throws SAXException if reporting to DTDHandler fails
     */
    void addNotationDeclaration(String notationName, ExternalID externalID) throws SAXException {
        if (notationName == null || externalID == null) {
            return;
        }
        
        // Per XML 1.0 § 4.7: first declaration is binding
        // Ensure notations map exists (lazy initialization)
        if (notations == null) {
            notations = new HashMap<>();
        }
        
        // Add to notations map keyed by notation name (interned)
        // Only store first declaration (first is binding)
        String internedName = notationName.intern();
        if (notations.containsKey(internedName)) {
            // Duplicate notation declaration - first is binding, skip this one
            return;
        }
        notations.put(internedName, externalID);
        
        // Report to DTDHandler if present
        if (dtdHandler != null) {
            String publicId = externalID.publicId;
            String systemId = externalID.systemId;
            dtdHandler.notationDecl(notationName, publicId, systemId);
        }
    }

    /**
     * Stores an entity declaration.
     * Called internally when parsing &lt;!ENTITY declarations.
     * Package-private to allow access from EntityDeclParser.
     *
     * @param entity the entity declaration to store
     * @throws SAXException if reporting to DTDHandler fails
     */
    void addEntityDeclaration(EntityDeclaration entity) throws SAXException {
        if (entity == null || entity.name == null) {
            return;
        }
        
        // Record the systemId of the context where this entity is declared
        // This is used as the base URI for resolving relative references in the entity's replacement text
        entity.declarationBaseURI = (locator != null) ? locator.getSystemId() : null;
        
        if (entity.isParameter) {
            // Parameter entity
            if (parameterEntities == null) {
                parameterEntities = new HashMap<>();
            }
            // Per XML 1.0 § 4.2: first declaration is binding
            String internedName = entity.name.intern();
            if (!parameterEntities.containsKey(internedName)) {
                parameterEntities.put(internedName, entity);
                
                // If we were waiting for this entity, remove it from unresolved set
                if (unresolvedParameterEntities != null) {
                    unresolvedParameterEntities.remove(internedName);
                    
                    // If all unresolved entities are now declared, process buffered tokens
                    if (unresolvedParameterEntities.isEmpty()) {
                        processBufferedTokens();
                    }
                }
            }
        } else {
            // General entity
            if (entities == null) {
                entities = new HashMap<>();
            }
            // Per XML 1.0 § 4.2: first declaration is binding
            String internedName = entity.name.intern();
            if (!entities.containsKey(internedName)) {
                entities.put(internedName, entity);
            }
            
            // Report unparsed entity to DTDHandler if applicable
            if (entity.isUnparsed() && dtdHandler != null) {
                dtdHandler.unparsedEntityDecl(
                    entity.name,
                    entity.externalID.publicId,
                    entity.externalID.systemId,
                    entity.notationName
                );
            }
        }
    }

    /**
     * Processes the external DTD subset by resolving and parsing it.
     *
     * <p>This method is called when an external ID (SYSTEM or PUBLIC) is
     * present in the DOCTYPE declaration. It uses the parent ContentParser's
     * processExternalEntity method to resolve and parse the external DTD.
     *
     * <p>The external DTD subset is processed **as if it were an internal subset**.
     * The tokens from the external DTD are fed back through the ContentParser to this
     * DTDParser, which processes them as markup declarations. The only difference
     * from a true internal subset is that there are no surrounding [ and ] brackets.
     *
     * <p>Processing order:
     * <ul>
     *   <li>If no internal subset (&lt;!DOCTYPE root SYSTEM "file.dtd"&gt;):
     *       External DTD processed, then DOCTYPE ends (&gt;)</li>
     *   <li>If internal subset present (&lt;!DOCTYPE root SYSTEM "file.dtd" [ ... ]&gt;):
     *       External DTD processed first, then internal subset [ ... ]</li>
     * </ul>
     *
     * <p>If external parameter entities are disabled or if resolution fails,
     * this method returns silently without error.
     *
     * @throws SAXException if DTD processing fails
     */
    private void processExternalDTDSubset() throws SAXException {
        // Only process if we have an external ID
        if (doctypeExternalID == null) {
            return;
        }
        
        // If standalone="yes" and validation is NOT enabled, skip external DTD processing
        // The document is declaring it's self-contained
        // However, if validation IS enabled, we need to load the external DTD to check
        // the standalone constraints (VC: Standalone Document Declaration)
        if (((Tokenizer) locator).isStandalone() && !xmlParser.getValidationEnabled()) {
            return;
        }
        
        String publicId = doctypeExternalID.publicId;
        String systemId = doctypeExternalID.systemId;
        
        // Must have at least one ID
        if (systemId == null && publicId == null) {
            return;
        }
        
        try {
            // Use ContentParser's processExternalEntity to resolve and parse
            // the external DTD subset. This will create a nested Tokenizer
            // that sends tokens back through ContentParser to this DTDParser.
            // The DTDParser is in IN_INTERNAL_SUBSET state, so it processes
            // the tokens as markup declarations.
            xmlParser.processExternalEntity(doctypeName, publicId, systemId);
        } catch (IOException e) {
            // I/O error resolving external DTD
            // Report as SAX error if we have an error handler
            fatalError("Failed to resolve external DTD subset: " + systemId, locator, e);
        }
    }
    
    /**
     * Post-processes all entity declarations to expand parameter entity references.
     * This must be called after the DOCTYPE declaration is complete, because
     * parameter entities can have forward references (an entity can reference
     * a parameter entity declared later in the DTD).
     * 
     * <p>This method iterates through all general and parameter entity declarations
     * and expands any ParameterEntityReference objects in their replacementText.
     * 
     * @throws SAXException if parameter entity expansion fails (undefined entity,
     *         circular reference, etc.)
     */
    private void expandParameterEntitiesInEntityValues() throws SAXException {
        // Expand parameter entities in general entity values
        if (entities != null) {
            for (EntityDeclaration entity : entities.values()) {
                if (entity.replacementText != null && !entity.isExternal()) {
                    entity.replacementText = expandParameterReferencesInList(entity.replacementText);
                }
            }
        }
        
        // NOTE: We do NOT expand parameter entities in parameter entity values here
        // because we now use a retokenization strategy that handles nested parameter
        // entity references dynamically. This allows chains like %a; -> %b; -> external %c;
        // to work correctly. The retokenization happens in expandParameterEntityInline().
        
        // Note: We also need to expand parameter entities in attribute default values
        if (attributeDecls != null) {
            for (Map<String, AttributeDeclaration> elementAttrs : attributeDecls.values()) {
                for (AttributeDeclaration attr : elementAttrs.values()) {
                    if (attr.defaultValue != null) {
                        attr.defaultValue = expandParameterReferencesInList(attr.defaultValue);
                    }
                }
            }
        }
        
        // Validate all general entity values for unparsed entity references
        // Per XML 1.0 § 4.3.2: references to unparsed entities in entity values are errors
        if (entities != null) {
            for (EntityDeclaration entity : entities.values()) {
                if (entity.replacementText != null) {
                    validateEntityValue(entity);
                }
            }
        }
    }
    
    /**
     * Validates an entity value to ensure it doesn't reference unparsed entities.
     * This is called after all entities have been declared.
     * 
     * @param entity the entity declaration to validate
     * @throws SAXException if the entity value references an unparsed entity
     */
    private void validateEntityValue(EntityDeclaration entity) throws SAXException {
        if (entity.replacementText == null) {
            return;
        }
        
        for (Object part : entity.replacementText) {
            if (part instanceof GeneralEntityReference) {
                GeneralEntityReference ref = (GeneralEntityReference) part;
                EntityDeclaration referencedEntity = getGeneralEntity(ref.name);
                if (referencedEntity != null && referencedEntity.isUnparsed()) {
                    throw fatalError(
                        "Entity '" + entity.name + "' has replacement text that references unparsed entity '" + 
                        ref.name + "'",
                        locator);
                }
            }
        }
    }
    
    /**
     * Expands all ParameterEntityReference objects in a list to their expanded values.
     * 
     * @param list the list containing String and ParameterEntityReference/GeneralEntityReference objects
     * @return a new list with ParameterEntityReference objects replaced by their expanded content
     * @throws SAXException if parameter entity expansion fails
     */
    private List<Object> expandParameterReferencesInList(List<Object> list) throws SAXException {
        if (list == null || list.isEmpty()) {
            return list;
        }
        
        // Check if there are any parameter entity references
        boolean hasParamRefs = false;
        for (Object part : list) {
            if (part instanceof ParameterEntityReference) {
                hasParamRefs = true;
                break;
            }
        }
        
        if (!hasParamRefs) {
            return list; // No parameter entities to expand
        }
        
        // Expand parameter entity references
        List<Object> result = new ArrayList<>();
        
        for (Object part : list) {
            if (part instanceof ParameterEntityReference) {
                // Expand the parameter entity reference
                ParameterEntityReference ref = (ParameterEntityReference) part;
                String expanded = entityStack.expandParameterEntity(ref.name, EntityExpansionContext.ENTITY_VALUE);
                
                if (expanded == null) {
                    throw fatalError(
                        "External parameter entity reference in entity value requires async resolution: %" + ref.name + ";",
                        locator);
                }
                
                // The expanded value is a string - add it to the result
                // Note: The expanded value might itself contain general entity references,
                // which will remain as GeneralEntityReference objects for deferred expansion
                if (!expanded.isEmpty()) {
                    result.add(expanded);
                }
            } else {
                // Keep strings and general entity references as-is
                result.add(part);
            }
        }
        
        return result;
    }
    
    /**
     * Expands a parameter entity reference inline in DTD markup.
     * Creates a tokenizer for the entity's replacement text and feeds tokens
     * back through this DTD parser.
     * Package-private to allow access from declaration parsers.
     * 
     * Per XML spec section 4.4.8: "When a parameter-entity reference is recognized 
     * in the DTD and included, its replacement text MUST be enlarged by the attachment 
     * of one leading and one following space (#x20) character"
     * 
     * @param entityName the parameter entity name (without % and ;)
     * @throws SAXException if expansion fails
     */
    void expandParameterEntityInline(String entityName, Token token, CharBuffer data) throws SAXException {
        // Mark that we've seen a parameter entity reference
        // This affects whether undeclared general entities are WF errors or validity errors
        hasParameterEntityReferences = true;
        
        // Check if entity is declared
        EntityDeclaration entity = getParameterEntity(entityName);
        
        // If entity is not declared and we're in external subset, handle forward reference
        if (entity == null && xmlParser.isProcessingExternalEntity()) {
            // Forward reference in external subset - enter buffering mode
            if (unresolvedParameterEntities == null) {
                unresolvedParameterEntities = new java.util.HashSet<>();
            }
            unresolvedParameterEntities.add(entityName.intern());
            
            // Buffer the current token (PARAMETERENTITYREF) before entering buffering mode
            bufferToken(token, data);
            
            // From now on, all tokens will be buffered until the entity is declared
            return;
        }

        if (entity == null) {
            throw fatalError("WFC: Entity Declared");
        }
        
        // Check if entity is external
        if (entity.isExternal()) {
            // External parameter entity - resolve and process it
            if (entity.externalID != null) {
                try {
                    xmlParser.processExternalEntity(
                        entityName, 
                        entity.externalID.publicId, 
                        entity.externalID.systemId);
                } catch (IOException e) {
                    throw fatalError(
                        "Failed to resolve external parameter entity %" + entityName + ";",
                        locator, e);
                }
            }
            return;
        }
        
        // Internal parameter entity - get UNEXPANDED replacement text
        // This allows nested parameter entity references to be expanded recursively during retokenization
        String unexpandedValue = entityStack.replacementTextToString(entity.replacementText);
        
        // Add required spaces around parameter entity replacement text (XML spec 4.4.8)
        // "replacement text MUST be enlarged by the attachment of one leading and 
        // one following space (#x20) character"
        String unexpandedValueWithSpaces = " " + unexpandedValue + " ";
        
        // If empty (just spaces now), nothing to tokenize
        if (unexpandedValueWithSpaces.trim().isEmpty()) {
            return;
        }
        
        // For inline DTD expansion, we need to tokenize the unexpanded value
        // This allows nested parameter entity references to be expanded recursively
        // Create entity context for this tokenization
        EntityStackEntry parentEntry = entityStack.peek();
        boolean currentVersion = parentEntry != null ? parentEntry.xml11 : false;
        
        // Capture content model depth for Proper Group/PE Nesting validation
        int contentModelDepth = -1;
        if (state == State.IN_ELEMENTDECL && elementDeclParser != null) {
            contentModelDepth = elementDeclParser.getContentModelDepth();
        }
        
        // Push entity context onto stack for tokenization
        // Store current DTD parser state to validate WFC: PE Between Declarations
        // Store content model depth to validate VC: Proper Group/PE Nesting
        EntityStackEntry entry = new EntityStackEntry(
            entityName, true /* parameter entity */, currentVersion, 0 /* element depth N/A for DTD */, state, contentModelDepth);
        entityStack.push(entry);
        
        // Create a tokenizer for the replacement text and feed tokens through DTD parser
        // Use the current tokenizer state to ensure proper context
        try {
            Tokenizer currentTokenizer = (Tokenizer) locator;
            Locator savedLocator = this.locator; // Save parent locator
            
            // Use the systemId from where the entity was declared as the base URI
            // This is important for resolving relative systemIds in the entity's replacement text
            String declarationSystemId = (entity != null) ? entity.declarationBaseURI : null;
            // Fall back to current systemId if not set
            if (declarationSystemId == null && locator != null) {
                declarationSystemId = locator.getSystemId();
            }

            // Create a tokenizer with the current tokenizer state and XML version
            TokenConsumer consumer = Parser.debug ? new DebugTokenConsumer(this, entityName) : this;
            Tokenizer tokenizer = new Tokenizer(entityName, consumer, currentTokenizerState, currentVersion);
            
            // Set systemId from declaration context for entity resolution
            // This ensures that relative external entity references are resolved correctly
            tokenizer.systemId = declarationSystemId;
            
            // Track the nested tokenizer and temporarily set it as the locator
            // This allows tokenizerState() to identify when the nested tokenizer calls it
            currentNestedTokenizer = tokenizer;
            nestedTokenizerFinalState = null;
            this.locator = tokenizer; // Temporarily set locator to nested tokenizer
            
            // Feed the replacement text (with added spaces) through the tokenizer
            // (internal entity values are already decoded strings)
            CharBuffer buffer = CharBuffer.wrap(unexpandedValueWithSpaces);
            tokenizer.receive(buffer);
            
            // Verify buffer was fully consumed - if not, entity value has incomplete token
            if (buffer.hasRemaining()) {
                throw fatalError(
                    "Parameter entity %" + entityName + "; has malformed replacement text: " +
                    "incomplete token at end of entity value",
                    locator
                );
            }
            
            tokenizer.close();
            
            // Restore parent locator
            this.locator = savedLocator;
            
            // If we're in a conditional section context and the unexpanded text is exactly
            // "INCLUDE" or "IGNORE", set the parent tokenizer's pendingConditionalType
            // so it knows which state to transition to when it processes the '[' that follows
            if (state == State.IN_CONDITIONAL) {
                String trimmedValue = unexpandedValue.trim();
                if ("INCLUDE".equals(trimmedValue)) {
                    currentTokenizer.setPendingConditionalType(Token.INCLUDE);
                } else if ("IGNORE".equals(trimmedValue)) {
                    currentTokenizer.setPendingConditionalType(Token.IGNORE);
                }
            }
            
            // Sync parent tokenizer to current state (nested tokenizer may have updated it)
            currentTokenizer.changeState(currentTokenizerState);
            
            // Clear nested state tracking
            currentNestedTokenizer = null;
            nestedTokenizerFinalState = null;
            
        } catch (SAXException e) {
            throw fatalError(
                "Error expanding parameter entity %" + entityName + ";: " + e.getMessage(),
                locator, e);
        } finally {
            // Before popping entity, validate proper declaration/PE nesting
            // XML Spec Section 2.8 - Validity Constraint: Proper Declaration/PE Nesting
            // 
            // "Parameter-entity replacement text must be properly nested with markup declarations.
            // That is to say, if either the first character or the last character of a markup 
            // declaration (markupdecl above) is contained in the replacement text for a 
            // parameter-entity reference, both must be contained in the same replacement text."
            //
            // This means: comments, PIs, and markup declarations (ELEMENT, ATTLIST, etc.) 
            // must not span parameter entity boundaries.
            State entryState = (State) entry.dtdParserState;
            
            // Check if we're in a different declaration/comment/PI state than when we entered
            boolean wasInDeclaration = isInDeclarationState(entryState);
            boolean isInDeclaration = isInDeclarationState(state);
            
            // If state changed from/to a declaration state, PE boundary is invalid
            if (wasInDeclaration != isInDeclaration || 
                (wasInDeclaration && isInDeclaration && entryState != state)) {
                
                // There are two related constraints:
                //
                // 1. WFC: PE Between Declarations (Section 2.8, Production 28a)
                //    "The replacement text of a parameter entity reference in a DeclSep 
                //    MUST match the production extSubsetDecl."
                //    This applies when the PE appears BETWEEN declarations (as a DeclSep).
                //    The PE replacement text must contain complete declarations.
                //
                // 2. VC: Proper Declaration/PE Nesting (Section 2.8)
                //    "Parameter-entity replacement text must be properly nested with markup 
                //    declarations."
                //    This applies when the PE appears INSIDE a declaration.
                //
                // The key distinction:
                // - If we were NOT in a declaration when entering the PE (wasInDeclaration=false),
                //   the PE appeared as a DeclSep, so WFC applies (fatal error)
                // - If we WERE in a declaration when entering the PE (wasInDeclaration=true),
                //   the PE appeared inside a declaration, so VC applies (validation error)
                
                if (!wasInDeclaration) {
                    // WFC: PE Between Declarations - PE appeared between declarations
                    // but its replacement text started a declaration that wasn't completed
                    throw fatalError(
                        "Well-Formedness Constraint: PE Between Declarations. " +
                        "Markup declaration spans parameter entity boundary. " +
                        "Parser was in state " + entryState + " when entering %" + entityName + "; " +
                        "but in state " + state + " when exiting. " +
                        "Both the first and last character of a markup declaration must be contained " +
                        "in the same parameter-entity text replacement.",
                        locator);
                } else {
                    // VC: Proper Declaration/PE Nesting - PE appeared inside a declaration
                    // Report as validation error only when validation is enabled
                    if (xmlParser.getValidationEnabled()) {
                        String message = "Validity Constraint: Proper Declaration/PE Nesting (Section 2.8). " +
                            "Markup declaration spans parameter entity boundary. " +
                            "Parser was in state " + entryState + " when entering %" + entityName + "; " +
                            "but in state " + state + " when exiting. " +
                            "Both the first and last character of a markup declaration must be contained " +
                            "in the same parameter-entity text replacement.";
                        xmlParser.reportValidationError(message);
                    }
                }
            }
            
            
            // VC: Proper Group/PE Nesting (Section 3.2.1)
            // Validate Proper Group/PE Nesting for content models in ELEMENT declarations
            // XML Spec Section 3.2.1 - Validity Constraint: Proper Group/PE Nesting
            //
            // "Parameter-entity replacement text must be properly nested with parenthesized groups.
            // That is to say, if either of the opening or closing parentheses in a choice, seq, or
            // Mixed construct is contained in the replacement text for a parameter entity, both must
            // be contained in the same replacement text."
            int entryDepth = entry.contentModelDepth;
            if (entryDepth >= 0 && state == State.IN_ELEMENTDECL && elementDeclParser != null) {
                int exitDepth = elementDeclParser.getContentModelDepth();
                
                if (entryDepth != exitDepth) {
                    // VC: Proper Group/PE Nesting (Section 3.2.1)
                    // Report as validation error only when validation is enabled
                    if (xmlParser.getValidationEnabled()) {
                        String message = "Validity Constraint: Proper Group/PE Nesting (Section 3.2.1). " +
                            "Parenthesized group in content model spans parameter entity boundary. " +
                            "Content model depth was " + entryDepth + " when entering %" + entityName + "; " +
                            "but is " + exitDepth + " when exiting. " +
                            "Parameter entity replacement text must contain complete groups.";
                        xmlParser.reportValidationError(message);
                    }
                }
            }
            
            // VC: Proper Conditional Section/PE Nesting (Section 3.4 [62])
            // Validate that conditional section keywords do not span parameter entity boundaries
            // XML Spec Section 3.4 - Validity Constraint: Proper Conditional Section/PE Nesting
            //
            // "If any of the &lt;![, the keyword, or the ]]&gt; of a conditional section
            // is contained in the replacement text for a parameter-entity reference,
            // all of them must be contained in the same replacement text."
            //
            // This means: if we entered the PE in IN_CONDITIONAL state (expecting keyword),
            // and we exit in IN_CONDITIONAL_INCLUDE or IN_CONDITIONAL_IGNORE, the keyword
            // spans the PE boundary, which is a validity constraint violation.
            State entryStateForConditional = (State) entry.dtdParserState;
            if (entryStateForConditional == State.IN_CONDITIONAL &&
                (state == State.IN_CONDITIONAL_INCLUDE || state == State.IN_CONDITIONAL_IGNORE)) {
                // VC: Proper Conditional Section/PE Nesting (Section 3.4 [62])
                // Report as validation error only when validation is enabled
                boolean inExternalSubset = xmlParser.isProcessingExternalEntity();
                if (inExternalSubset && xmlParser.getValidationEnabled()) {
                    String message = "Validity Constraint: Proper Conditional Section/PE Nesting (Section 3.4 [62]). " +
                        "Conditional section keyword spans parameter entity boundary. " +
                        "Parser was in state " + entryStateForConditional + " when entering %" + entityName + "; " +
                        "but in state " + state + " when exiting. " +
                        "The keyword (INCLUDE or IGNORE) must be contained in the same " +
                        "parameter-entity text replacement as the <![ and ]]>.";
                    xmlParser.reportValidationError(message);
                } else {
                    // In internal subset, this is a WFC: Conditional Section/PE Nesting
                    // "If any of the <![, the keyword, or the ]]> of a conditional section
                    // is contained in the replacement text for a parameter-entity reference,
                    // all of them must be contained in the same replacement text."
                    throw fatalError(
                        "Well-Formedness Constraint: Conditional Section/PE Nesting. " +
                        "Conditional section keyword spans parameter entity boundary. " +
                        "Parser was in state " + entryStateForConditional + " when entering %" + entityName + "; " +
                        "but in state " + state + " when exiting. " +
                        "The keyword (INCLUDE or IGNORE) must be contained in the same " +
                        "parameter-entity text replacement as the <![ and ]]>.",
                        locator);
                }
            }
            
            // Pop entity context to restore parent's state
            if (!entityStack.isEmpty() && entityStack.peek() == entry) {
                entityStack.pop();
            }
        }
    }
    
    /**
     * Checks if a DTDParser state represents being inside a markup declaration, comment, or PI.
     * Used for validating WFC: PE Between Declarations.
     */
    private boolean isInDeclarationState(State s) {
        return s == State.IN_ELEMENTDECL || 
               s == State.IN_ATTLISTDECL || 
               s == State.IN_ENTITYDECL || 
               s == State.IN_NOTATIONDECL ||
               s == State.IN_COMMENT ||
               s == State.IN_PI;
    }
    
    /**
     * Checks if we're in buffering mode (waiting for parameter entities to be declared).
     * 
     * @return true if we're buffering tokens due to forward parameter entity references
     */
    private boolean isBuffering() {
        return unresolvedParameterEntities != null && !unresolvedParameterEntities.isEmpty();
    }
    
    /**
     * Buffers a token event for later processing.
     * Called when we're in buffering mode (forward parameter entity references).
     * 
     * @param token the token type
     * @param data the token data (CharBuffer, will be extracted as String)
     */
    private void bufferToken(Token token, CharBuffer data) {
        if (tokenStreamBuffer == null) {
            tokenStreamBuffer = new ArrayList<>();
        }
        // Create TokenStreamEvent, which copies data and locator info
        TokenStreamEvent event = new TokenStreamEvent(token, data, locator);
        tokenStreamBuffer.add(event);
    }
    
    /**
     * Processes all buffered tokens when buffering mode ends.
     * Called when all unresolved parameter entities have been declared.
     * 
     * @throws SAXException if processing a buffered token fails
     */
    private void processBufferedTokens() throws SAXException {
        if (tokenStreamBuffer == null || tokenStreamBuffer.isEmpty()) {
            return;
        }
        
        // Process all buffered tokens
        List<TokenStreamEvent> buffer = new ArrayList<>(tokenStreamBuffer);
        tokenStreamBuffer.clear(); // Clear buffer before processing (in case processing adds more)
        
        for (TokenStreamEvent event : buffer) {
            // Convert TokenStreamEvent back to token + CharBuffer
            CharBuffer data = null;
            if (event.data != null) {
                data = CharBuffer.wrap(event.data);
            }
            
            // Process the token (this may trigger more buffering if there are more forward refs)
            receive(event.token, data);
        }
    }
    
    /**
     * Validates a system identifier to ensure it doesn't contain a URI fragment.
     * Per XML spec section 4.2.2: "Fragment identifiers must not be used with system identifiers."
     * 
     * @param systemId the system identifier to validate
     * @throws SAXParseException if the system ID contains a fragment identifier (#)
     */
    private void validateSystemId(String systemId) throws SAXException {
        if (systemId != null && systemId.indexOf('#') >= 0) {
            // WFC: System Literal (Production 11)
            // "System identifiers must not contain URI fragment identifiers (#)"
            throw fatalError("System identifier must not contain URI fragment (found '#' in: " + systemId + ")");
        }
    }
    
    /**
     * Validates that all structures (especially conditional sections) are properly closed
     * when an external entity finishes processing.
     * 
     * Called by ContentParser after an external entity has been fully processed.
     * 
     * @param isParameterEntity true if this is a parameter entity ending, false if it's the main external DTD subset
     */
    void validateExternalEntityClosed(boolean isParameterEntity) throws SAXException {
        // Check if we have unclosed conditional sections (depth counter)
        // This applies to ALL external entities (DTD subset AND parameter entities)
        // All conditional sections must be properly closed before the entity ends
        if (conditionalDepth > 0) {
            throw fatalError("Conditional section not properly terminated (missing ']]>' at end of external entity)");
        }
        
        // Check if we're in the middle of parsing a conditional section (state check)
        // This check only applies to parameter entities, not the main external DTD subset
        // For parameter entities, WFC: Conditional Section/PE Nesting requires that
        // conditional sections do not span parameter entity boundaries
        if (isParameterEntity && 
            (state == State.IN_CONDITIONAL || 
             state == State.IN_CONDITIONAL_INCLUDE || 
             state == State.IN_CONDITIONAL_IGNORE)) {
            throw fatalError("Conditional section spans parameter entity boundary (WFC: Conditional Section/PE Nesting)");
        }
    }

}

