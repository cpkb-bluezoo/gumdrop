/*
 * Tokenizer.java
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
import java.util.Map;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.Locator2;

/**
 * Handles tokenization for buffers of XML characters using a state trie architecture.
 * <p>
 * This tokenizer uses a deterministic state machine with no backtracking. Character
 * classification reduces the Unicode space to ~25 character classes, and state
 * transitions are looked up in a pre-built trie structure.
 * <p>
 * The tokenizer maintains two levels of state:
 * <ul>
 * <li><b>State</b> - High-level parsing context (what we're parsing: content, attributes, etc.)</li>
 * <li><b>MiniState</b> - Fine-grained token recognition progress (where we are in recognizing a token)</li>
 * </ul>
 * <p>
 * When the input buffer is exhausted mid-token, the tokenizer preserves its State
 * and resets MiniState to READY. On the next receive() call, unconsumed characters
 * are prepended and reprocessed from the beginning, naturally reconstructing the
 * token recognition process.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Tokenizer implements Locator2 {

    private static boolean debug = false;

    // ===== Debugging =====

    private String name;

    // ===== State Machine =====
    
    /**
     * Current high-level parsing state.
     */
    private TokenizerState state;

    /**
     * Initial parsing state.
     */
    private TokenizerState initialState;
    
    /**
     * State to return to after exiting COMMENT or PI_TARGET/PI_DATA states.
     * Used to properly nest comments/PIs within DOCTYPE, DOCTYPE_INTERNAL, etc.
     */
    private TokenizerState returnState = null;
    
    /**
     * State to transition to after parsing XML/text declaration.
     * For top-level documents: PROLOG_BEFORE_DOCTYPE
     * For external DTD subsets/parameter entities: DOCTYPE_INTERNAL
     * For external general entities: CONTENT
     */
    private TokenizerState postDeclState = TokenizerState.PROLOG_BEFORE_DOCTYPE;
    
    /**
     * Current fine-grained token recognition state.
     */
    private MiniState miniState = MiniState.READY;
    
    // ===== Conditional Section State =====
    
    /**
     * Pending conditional section type (INCLUDE or IGNORE) waiting for '[' to start the section.
     * Null if no conditional section is pending.
     */
    private Token pendingConditionalType = null;
    
    // ===== Buffers =====

    // NOTE: We no longer maintain separate underflow and working buffers.
    // The 'data' buffer passed to receive() serves as both:
    // - On entry: may contain underflow data from previous call (in write mode with position at end of underflow)
    // - During processing: used directly for tokenization (flipped to read mode)
    // - On exit: compacted to preserve underflow for next call (back to write mode)
    
    // ===== Position Tracking =====
    
    /**
     * Buffer position at the start of the current token being accumulated.
     */
    private int tokenStartPos;
    
    // ===== Token Consumer =====
    
    /**
     * The consumer that receives emitted tokens.
     */
    private final TokenConsumer consumer;
    
    /**
     * Whether XML 1.1 character rules are enabled.
     * If true, allows extended character ranges per XML 1.1 specification.
     * If false (default), uses XML 1.0 character rules.
     */
    boolean xml11 = false;
    
    /**
     * Whether to allow RestrictedChar during tokenization.
     * When true, RestrictedChar ([#x1-#x8], [#xB-#xC], [#xE-#x1F]) are allowed
     * in XML 1.1 contexts. This is used during retokenization of entity values
     * that contain character references expanding to RestrictedChar.
     */
    boolean allowRestrictedChar = false;
    
    /**
     * Stack of tokenizer states for conditional sections.
     * When we enter a conditional section (<![), we push the current state.
     * When we exit (]]>), we pop back to the previous state.
     * This handles nested conditional sections correctly.
     */
    private Deque<TokenizerState> conditionalStateStack = null;
    
    /**
     * Depth counter for nested conditional sections within IGNORE sections.
     * In IGNORE mode, we need to track <![...]]> nesting to know when to exit.
     * Incremented when START_CONDITIONAL is emitted in IGNORE mode.
     * Decremented when END_CDATA is emitted in IGNORE mode.
     * Only exit IGNORE mode when depth reaches 0.
     */
    private int ignoreConditionalDepth = 0;
    
    /**
     * Cached transition array for the current state.
     * This avoids EnumMap.get() lookup on every character - the lookup
     * is only done when the state changes.
     */
    private MiniStateTransitionBuilder.Transition[] cachedTransitions;
    
    // ===== Locator2 =====

    /**
     * Current line number (1-based).
     */
    long lineNumber = 1;

    /**
     * Current column number (0-based, position in current line).
     */
    long columnNumber = 0;

    /**
     * Total character position in the stream.
     */
    long charPosition = 0;

    /**
     * The XML version declared in the document (default "1.0").
     */
    String version = "1.0";
    
    /**
     * Document's XML version (from main document entity's XML declaration).
     * Used for version compatibility checking when including entities.
     * Once set by the document, this does not change when processing external entities.
     */
    String documentVersion = "1.0";

    /**
     * Whether this document is standalone.
     */
    Boolean standalone;

    /**
     * The document's encoding name (from XML declaration or BOM).
     */
    String encoding = null;

    /**
     * Public identifier for this entity.
     */
    String publicId;

    /**
     * System identifier (URI) for this entity.
     */
    String systemId;
    
    // ===== Predefined Entity Replacement =====
    
    private static final int PREDEFINED_AMP = 0;
    private static final int PREDEFINED_LT = 1;
    private static final int PREDEFINED_GT = 2;
    private static final int PREDEFINED_APOS = 3;
    private static final int PREDEFINED_QUOT = 4;
    
    /**
     * Pre-expanded text for predefined entity references.
     * Indexed by entity name: amp=0, lt=1, gt=2, apos=3, quot=4
     */
    private static final CharBuffer PREDEFINED_ENTITY_TEXT = CharBuffer.wrap("&<>'\"").asReadOnlyBuffer();
    
    /**
     * Reusable buffer for character references (e.g., &#60; or &#x1F4A9;).
     * Sized for 2 chars to handle surrogate pairs.
     * Reused across all character reference emissions to avoid allocating a new char[] every time.
     */
    private final CharBuffer charRefBuffer = CharBuffer.allocate(2);
    
    // ===== Constructor =====

    /**
     * Constructor.
     * 
     * @param name name of this tokenizer (systemId or entity name)
     * @param consumer the token consumer
     * @param initialState the initial tokenizer state
     * @param xml11 whether to use XML 1.1 character rules
     */
    Tokenizer(String name, TokenConsumer consumer, TokenizerState initialState, boolean xml11) {
        this(name, consumer, initialState, xml11, false);
    }
    
    /**
     * Creates a tokenizer with specified initial state, XML version, and RestrictedChar permission.
     * 
     * @param name name of this tokenizer (systemId or entity name)
     * @param consumer the token consumer to receive tokens
     * @param initialState the initial tokenizer state
     * @param xml11 true for XML 1.1 rules, false for XML 1.0
     * @param allowRestrictedChar true to allow RestrictedChar during tokenization (for entity retokenization)
     */
    Tokenizer(String name, TokenConsumer consumer, TokenizerState initialState, boolean xml11, boolean allowRestrictedChar) {
        this.name = name;
        this.consumer = consumer;
        this.state = this.initialState = initialState;
        this.xml11 = xml11;
        this.allowRestrictedChar = allowRestrictedChar;
        // Initialize cached transitions for the initial state
        this.cachedTransitions = MiniStateTransitionBuilder.FLAT_TRANSITION_TABLE.get(initialState);
        consumer.setLocator(this);
        // Notify consumer of initial state so it can track the tokenizer's state
        // This is important for nested tokenizers (external DTD, entity expansion)
        // where the consumer needs to know the state for parameter entity expansion
        consumer.tokenizerState(initialState);
    }

    // ===== Public API =====
    
    /**
     * Returns whether XML 1.1 character rules are enabled.
     * @return true if XML 1.1 rules are enabled, false if XML 1.0 rules are used
     */
    public boolean isXML11() {
        return xml11;
    }

    boolean isStandalone() {
        return (standalone == null) ? false : standalone.booleanValue();
    }

    String getName() {
        return (name == null) ? systemId : name;
    }

    // ===== Locator2 Implementation =====

    @Override
    public String getPublicId() {
        return publicId;
    }

    @Override
    public String getSystemId() {
        return systemId;
    }

    @Override
    public int getLineNumber() {
        return lineNumber > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) lineNumber;
    }

    @Override
    public int getColumnNumber() {
        return columnNumber > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) columnNumber;
    }

    @Override
    public String getXMLVersion() {
        return version;
    }

    @Override
    public String getEncoding() {
        return encoding;
    }
    
    /**
     * Sets the pending conditional section type (INCLUDE or IGNORE).
     * Used when a parameter entity expands to the keyword, so the parent tokenizer
     * knows which state to transition to when it processes the '[' that follows.
     * 
     * @param keyword the conditional section keyword (INCLUDE or IGNORE)
     */
    void setPendingConditionalType(Token keyword) {
        this.pendingConditionalType = keyword;
    }
    
    /**
     * Helper method to change state and notify the consumer.
     * Used internally to ensure the consumer tracks the tokenizer state.
     * 
     * Special handling for conditional sections:
     * - When entering CONDITIONAL_SECTION_KEYWORD (after START_CONDITIONAL), push current state
     * - When entering INCLUDE/IGNORE, maintain the stack
     * - State popping happens when END_CDATA is emitted
     * 
     * @param newState the new tokenizer state
     */
    void changeState(TokenizerState newState) {
        if (this.state != newState) {
            // Push state when entering conditional section parsing
            if (newState == TokenizerState.CONDITIONAL_SECTION_KEYWORD) {
                if (conditionalStateStack == null) {
                    conditionalStateStack = new ArrayDeque<>();
                }
                // Push the current state so we can return to it after ]]>
                conditionalStateStack.push(this.state);
            }
            
            this.state = newState;
            // Update cached transitions for the new state
            this.cachedTransitions = MiniStateTransitionBuilder.FLAT_TRANSITION_TABLE.get(newState);
            consumer.tokenizerState(newState);
        }
    }
    
    /**
     * Notifies the consumer of XML version change.
     * Called by ExternalEntityDecoder when parsing XML/text declarations.
     * 
     * @param xml11 true if XML 1.1, false if XML 1.0
     * @throws SAXException if the consumer throws an exception
     */
    void notifyXmlVersion(boolean xml11) throws SAXException {
        consumer.xmlVersion(xml11);
    }
    
    /**
     * Receives and processes a buffer of characters (for internal entity expansion).
     * This method is used when the character data has already been decoded.
     * 
     * <p><b>Buffer Contract:</b>
     * <ul>
     * <li><b>On Entry:</b> Buffer may be in write mode with underflow data from previous call
     *     (position at end of underflow, ready for more data to be appended)</li>
     * <li><b>On Exit:</b> Buffer is in write mode with any unconsumed data compacted to position 0
     *     (position at end of underflow data, ready for next decode)</li>
     * </ul>
     * 
     * @param data the character buffer to process (modified in place)
     * @throws SAXException if a parsing error occurs
     */
    public void receive(CharBuffer data) throws SAXException {
        if (state == TokenizerState.CLOSED) {
            throw new IllegalStateException("Tokenizer is closed");
        }
        // Buffer must be in read mode (position at start, limit at end of data)
        // Tokenize as much as possible (character data is already normalized by the entity value parser)
        // The buffer's position will be updated to reflect how many characters were consumed
        tokenize(data);
    }
    
    /**
     * Closes the tokenizer and flushes any remaining tokens.
     * 
     * @throws SAXException if a parsing error occurs
     */
    public void close() throws SAXException {
        if (state == TokenizerState.CLOSED) {
            return;
        }
        state = TokenizerState.CLOSED;
        
        // Note: We no longer maintain internal buffers (charBuffer/charUnderflow).
        // The buffer is managed by ExternalEntityDecoder and passed to receive().
        // Underflow checking is implicit - if there's underflow when EED closes,
        // it will result in an incomplete token state.
        
        // Validate that we're in a valid end state
        // If we're in the middle of parsing something (like an entity reference after '&'),
        // that's a well-formedness error
        if (miniState != MiniState.READY) {
            // WFC: Well-Formed Documents (Section 2.1)
            // "All tokens must be complete at end of document"
            throw fatalError("Incomplete token at end of input: " + miniState);
        }
    }
    
    /**
     * Resets the tokenizer to initial state.
     * 
     * @throws SAXException if an error occurs
     */
    public void reset() throws SAXException {
        state = initialState;
        miniState = MiniState.READY;
        xml11 = false; // Reset to XML 1.0 mode
        returnState = null;
        
        // Clear conditional section state
        if (conditionalStateStack != null) {
            conditionalStateStack.clear();
        }
        pendingConditionalType = null;
        ignoreConditionalDepth = 0;

        charPosition = 0;
        lineNumber = 1;
        columnNumber = 0;
    }

    // ===== Additional Setter Methods =====
    
    /**
     * Checks if an entity name is a predefined entity and returns its index into PREDEFINED_ENTITY_TEXT.
     * Returns -1 if not a predefined entity.
     * 
     * @param charBuffer the character buffer
     * @param nameStart start of entity name in charBuffer
     * @param nameEnd end of entity name in charBuffer
     * @return index into PREDEFINED_ENTITY_TEXT (0=&amp;, 1=&lt;, 2=&gt;, 3=', 4=") or -1 if not predefined
     */
    private int getPredefinedEntityIndex(CharBuffer charBuffer, int nameStart, int nameEnd) {
        int nameLen = nameEnd - nameStart;
        if (nameLen == 2) {
            if (charBufferContains(charBuffer, "lt", nameStart, 0)) {
                return PREDEFINED_LT;  // &lt; -> '<'
            } else if (charBufferContains(charBuffer, "gt", nameStart, 0)) {
                return PREDEFINED_GT;  // &gt; -> '>'
            }
        } else if (nameLen == 3) {
            if (charBufferContains(charBuffer, "amp", nameStart, 0)) {
                return PREDEFINED_AMP;  // &amp; -> '&'
            }
        } else if (nameLen == 4) {
            if (charBufferContains(charBuffer, "apos", nameStart, 0)) {
                return PREDEFINED_APOS;  // &apos; -> '\''
            } else if (charBufferContains(charBuffer, "quot", nameStart, 0)) {
                return PREDEFINED_QUOT;  // &quot; -> '"'
            }
        }
        return -1;
    }
    
    /**
     * Checks if a NAME token in DOCTYPE context is actually a keyword.
     * Returns the keyword token type or null if it's just a name.
     * 
     * @param start start position of name in charBuffer
     * @param length length of name
     * @return Token.SYSTEM, Token.PUBLIC, or null if not a keyword
     */
    private Token checkDOCTYPEKeyword(CharBuffer charBuffer, int start, int length) {
        if (length == 6) {
            // Check for "SYSTEM"
            if (charBufferContains(charBuffer, "SYSTEM", start, 0)) {
                return Token.SYSTEM;
            }
            // Check for "PUBLIC"
            if (charBufferContains(charBuffer, "PUBLIC", start, 0)) {
                return Token.PUBLIC;
            }
        }
        return null;
    }
    
    /**
     * Checks if a NAME in DOCTYPE_INTERNAL is a markup declaration keyword.
     * DTD keywords are case-sensitive. If we see something that looks like a keyword
     * but with wrong case, throw an error.
     * 
     * @param start start position in charBuffer
     * @param length length of the name
     * @return START_ELEMENTDECL, START_ATTLISTDECL, START_ENTITYDECL, START_NOTATIONDECL, or null
     * @throws SAXException if keyword has wrong case
     */
    private Token checkMarkupDeclaration(CharBuffer charBuffer, int start, int length) throws SAXException {
        // Extract the name for error messages (before buffer operations)
        String nameForError = null;
        
        if (length == 7) {
            // Check for "ELEMENT"
            if (charBufferContains(charBuffer, "ELEMENT", start, 0)) {
                return Token.START_ELEMENTDECL;
            }
            // Check for case variants of ELEMENT
            if (charBufferContainsIgnoreCase(charBuffer, "ELEMENT", start, 0)) {
                nameForError = extractName(charBuffer, start, length);
                // WFC: Element Type Declaration (Production 45)
                // "DTD keywords are case-sensitive"
                throw fatalError("DTD keyword 'ELEMENT' is case-sensitive (found: " + nameForError + ")");
            }
            // Check for "ATTLIST"
            if (charBufferContains(charBuffer, "ATTLIST", start, 0)) {
                return Token.START_ATTLISTDECL;
            }
            // Check for case variants of ATTLIST
            if (charBufferContainsIgnoreCase(charBuffer, "ATTLIST", start, 0)) {
                nameForError = extractName(charBuffer, start, length);
                // WFC: Attribute List Declaration (Production 52)
                // "DTD keywords are case-sensitive"
                throw fatalError("DTD keyword 'ATTLIST' is case-sensitive (found: " + nameForError + ")");
            }
        } else if (length > 7 && (charBufferContains(charBuffer, "ELEMENT", start, 0) || charBufferContains(charBuffer, "ATTLIST", start, 0))) {
            // Name starts with ELEMENT or ATTLIST but has more characters - missing whitespace
            String keyword = charBufferContains(charBuffer, "ELEMENT", start, 0) ? "ELEMENT" : "ATTLIST";
            nameForError = extractName(charBuffer, start, length);
            // WFC: Markup Declarations (Production 29)
            // "Whitespace is required after DTD keywords"
            throw fatalError("Expected whitespace after <!" + keyword + " keyword (found: <!" + nameForError + ")");
        } else if (length == 6) {
            // Check for "ENTITY"
            if (charBufferContains(charBuffer, "ENTITY", start, 0)) {
                return Token.START_ENTITYDECL;
            }
            // Check for case variants of ENTITY
            if (charBufferContainsIgnoreCase(charBuffer, "ENTITY", start, 0)) {
                nameForError = extractName(charBuffer, start, length);
                // WFC: Entity Declaration (Production 70)
                // "DTD keywords are case-sensitive"
                throw fatalError("DTD keyword 'ENTITY' is case-sensitive (found: " + nameForError + ")");
            }
        } else if (length > 6 && charBufferContains(charBuffer, "ENTITY", start, 0)) {
            // Name starts with ENTITY but has more characters - missing whitespace
            nameForError = extractName(charBuffer, start, length);
            // WFC: Markup Declarations (Production 29)
            // "Whitespace is required after DTD keywords"
            throw fatalError("Expected whitespace after <!ENTITY keyword (found: <!" + nameForError + ")");
        } else if (length == 8) {
            // Check for "NOTATION"
            if (charBufferContains(charBuffer, "NOTATION", start, 0)) {
                return Token.START_NOTATIONDECL;
            }
            // Check for case variants of NOTATION
            if (charBufferContainsIgnoreCase(charBuffer, "NOTATION", start, 0)) {
                nameForError = extractName(charBuffer, start, length);
                // WFC: Notation Declaration (Production 82)
                // "DTD keywords are case-sensitive"
                throw fatalError("DTD keyword 'NOTATION' is case-sensitive (found: " + nameForError + ")");
            }
        } else if (length > 8 && charBufferContains(charBuffer, "NOTATION", start, 0)) {
            // Name starts with NOTATION but has more characters - missing whitespace
            nameForError = extractName(charBuffer, start, length);
            // WFC: Markup Declarations (Production 29)
            // "Whitespace is required after DTD keywords"
            throw fatalError("Expected whitespace after <!NOTATION keyword (found: <!" + nameForError + ")");
        }
        
        // If we reach here, the name after <! is not a recognized markup declaration keyword
        // This is an error - only ELEMENT, ATTLIST, ENTITY, NOTATION, -- (comment), or [ (CDATA/conditional) are valid
        nameForError = extractName(charBuffer, start, length);
        // WFC: Markup Declarations (Production 29)
        // "Only ELEMENT, ATTLIST, ENTITY, NOTATION, comments, and conditional sections are valid markup declarations"
        throw fatalError("Unknown markup declaration: <!" + nameForError + 
            " (expected ELEMENT, ATTLIST, ENTITY, NOTATION, --, or [)");
    }
    
    /**
     * Extracts a name from the charBuffer at the given position.
     * Used for error messages to avoid issues with buffer position changes.
     */
    private String extractName(CharBuffer charBuffer, int start, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(charBuffer.get(start + i));
        }
        return sb.toString();
    }
    
    /**
     * Checks if a NAME token is actually a DTD keyword and returns the appropriate token.
     * This method recognizes DTD keywords like EMPTY, ANY, REQUIRED, IMPLIED, etc.
     * Only called for NAME tokens in DOCTYPE contexts.
     * Returns the keyword token, or Token.NAME if not a keyword.
     */
    private Token checkDTDKeyword(CharBuffer charBuffer, int start, int length) {
        // Check by length first for efficiency
        switch (length) {
            case 2:
                // "ID"
                if (charBufferContains(charBuffer, "ID", start, 0)) {
                    return Token.ID;
                }
                break;
                
            case 3:
                // "ANY"
                if (charBufferContains(charBuffer, "ANY", start, 0)) {
                    return Token.ANY;
                }
                break;
                
            case 5:
                // "EMPTY", "IDREF", "CDATA", "FIXED", "NDATA"
                char first = charBuffer.get(start);
                if (first == 'E') {
                    if (charBufferContains(charBuffer, "MPTY", start, 1)) {
                        return Token.EMPTY;
                    }
                } else if (first == 'I') {
                    if (charBufferContains(charBuffer, "DREF", start, 1)) {
                        return Token.IDREF;
                    }
                } else if (first == 'C') {
                    if (charBufferContains(charBuffer, "DATA", start, 1)) {
                        return Token.CDATA_TYPE;
                    }
                } else if (first == 'F') {
                    if (charBufferContains(charBuffer, "IXED", start, 1)) {
                        return Token.FIXED;
                    }
                } else if (first == 'N') {
                    if (charBufferContains(charBuffer, "DATA", start, 1)) {
                        return Token.NDATA;
                    }
                }
                break;
                
            case 6:
                // "IDREFS", "ENTITY"
                first = charBuffer.get(start);
                if (first == 'I') {
                    if (charBufferContains(charBuffer, "DREFS", start, 1)) {
                        return Token.IDREFS;
                    }
                } else if (first == 'E') {
                    if (charBufferContains(charBuffer, "NTITY", start, 1)) {
                        return Token.ENTITY;
                    }
                }
                break;
                
            case 7:
                // "NMTOKEN", "IMPLIED", "INCLUDE"
                first = charBuffer.get(start);
                if (first == 'N') {
                    if (charBufferContains(charBuffer, "MTOKEN", start, 1)) {
                        return Token.NMTOKEN;
                    }
                } else if (first == 'I') {
                    if (charBufferContains(charBuffer, "MPLIED", start, 1)) {
                        return Token.IMPLIED;
                    } else if (charBufferContains(charBuffer, "NCLUDE", start, 1)) {
                        return Token.INCLUDE;
                    }
                }
                break;
                
            case 8:
                // "NMTOKENS", "REQUIRED", "ENTITIES", "NOTATION"
                first = charBuffer.get(start);
                if (first == 'N') {
                    if (charBufferContains(charBuffer, "MTOKENS", start, 1)) {
                        return Token.NMTOKENS;
                    } else if (charBufferContains(charBuffer, "OTATION", start, 1)) {
                        return Token.NOTATION;
                    }
                } else if (first == 'R') {
                    if (charBufferContains(charBuffer, "EQUIRED", start, 1)) {
                        return Token.REQUIRED;
                    }
                } else if (first == 'E') {
                    if (charBufferContains(charBuffer, "NTITIES", start, 1)) {
                        return Token.ENTITIES;
                    }
                }
                break;
        }
        
        // Not a keyword
        return Token.NAME;
    }
    
    boolean charBufferContains(CharBuffer charBuffer, String test, int start, int offset) {
        int len = test.length();
        for (int i = 0; i < len; i++) {
            if (charBuffer.get(i + start + offset) != test.charAt(i)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Case-insensitive version of charBufferContains.
     * Used to detect when someone uses wrong case for a keyword.
     */
    boolean charBufferContainsIgnoreCase(CharBuffer charBuffer, String test, int start, int offset) {
        int len = test.length();
        for (int i = 0; i < len; i++) {
            char bufferChar = charBuffer.get(i + start + offset);
            char testChar = test.charAt(i);
            if (Character.toLowerCase(bufferChar) != Character.toLowerCase(testChar)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a code point is a legal XML character in a character reference.
     * 
     * XML 1.0: Char ::= #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
     * XML 1.1: Char ::= [#x1-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
     * 
     * Note: In XML 1.1, character references can use the extended range including C0/C1 controls,
     * but these characters are restricted when appearing directly in the document.
     * 
     * @param codePoint the Unicode code point to check
     * @return true if the code point is legal in this XML version
     */
    private boolean isLegalXMLChar(int codePoint) {
        if (xml11) {
            // XML 1.1: [#x1-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
            // Allows C0/C1 controls in character references (0x01-0x1F, 0x7F-0x9F)
            return (codePoint >= 0x1 && codePoint <= 0xD7FF) ||
                   (codePoint >= 0xE000 && codePoint <= 0xFFFD) ||
                   (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
        } else {
            // XML 1.0: #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
            return (codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
                    (codePoint >= 0x20 && codePoint <= 0xD7FF) ||
                    (codePoint >= 0xE000 && codePoint <= 0xFFFD) ||
                    (codePoint >= 0x10000 && codePoint <= 0x10FFFF));
        }
    }
    
    /**
     * Emits an ENTITYREF token for a character reference (&#ddd; or &#xhhh;).
     * Resolves the code point and emits 1 or 2 characters (for supplementary code points).
     * 
     * @param refStart start position in charBuffer (at '&')
     * @param refEnd end position in charBuffer (after ';')
     * @param isHex true if hexadecimal, false if decimal
     */
    private void emitCharacterReference(CharBuffer charBuffer, int refStart, int refEnd, boolean isHex) throws SAXException {
        // Parse: &#ddd; or &#xhhh;
        int numStart = refStart + 2;  // Skip '&#'
        if (isHex) {
            numStart++;  // Skip 'x'
        }
        int numEnd = refEnd - 1;  // Skip ';'
        
        // Parse the code point
        int codePoint = 0;
        for (int i = numStart; i < numEnd; i++) {
            char c = charBuffer.get(i);
            int digit;
            if (isHex) {
                if (c >= '0' && c <= '9') digit = c - '0';
                else if (c >= 'a' && c <= 'f') digit = c - 'a' + 10;
                else if (c >= 'A' && c <= 'F') digit = c - 'A' + 10;
                else {
                    // WFC: Character Reference (Production 66)
                    // "Hexadecimal character references must contain only valid hex digits"
                    throw fatalError("Invalid hexadecimal character reference");
                }
                codePoint = codePoint * 16 + digit;
            } else {
                if (c >= '0' && c <= '9') digit = c - '0';
                else {
                    // WFC: Character Reference (Production 66)
                    // "Decimal character references must contain only valid decimal digits"
                    throw fatalError("Invalid decimal character reference");
                }
                codePoint = codePoint * 10 + digit;
            }
        }
        
        // Validate the code point is a legal XML character
        // XML 1.0: Char ::= #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
        if (!isLegalXMLChar(codePoint)) {
            // WFC: Character Reference (Production 66)
            // "Character references must refer to legal XML characters"
            throw fatalError(String.format("Character reference &#x%X; refers to an illegal XML character", codePoint));
        }
        
        // Validate the character is legal in the current context
        // Check for literal '<' in attribute values
        if ((state == TokenizerState.ATTR_VALUE_APOS || state == TokenizerState.ATTR_VALUE_QUOT) &&
            codePoint == '<') {
            // WFC: Attribute Values (Production 10)
            // "Attribute values must not contain literal '<' characters"
            throw fatalError("Literal '<' character (from character reference) not allowed in attribute values");
        }
        
        // Encode the code point into the reusable buffer
        charRefBuffer.clear();
        if (codePoint <= 0xFFFF) {
            // BMP character - single char
            charRefBuffer.put((char) codePoint);
        } else {
            // Supplementary character - encode as surrogate pair
            codePoint -= 0x10000;
            charRefBuffer.put((char) (0xD800 + (codePoint >> 10)));  // high surrogate
            charRefBuffer.put((char) (0xDC00 + (codePoint & 0x3FF))); // low surrogate
        }
        charRefBuffer.flip();
        consumer.receive(Token.CHARENTITYREF, charRefBuffer);
    }
    
    // ===== Core Tokenization Logic =====
    
    /**
     * Main tokenization loop using state trie architecture.
     * 
     * @param charBuffer the character buffer to tokenize (in read mode)
     */
    private void tokenize(CharBuffer charBuffer) throws SAXException {
        tokenStartPos = charBuffer.position();
        
        int pos = charBuffer.position();
        int limit = charBuffer.limit();
        
        while (pos < limit) {
            // Check if charset was switched - if so, restart tokenization from beginning of buffer
            
            
            char c = charBuffer.get(pos);
            
            // Classify character
            CharClass cc = CharClass.classify(c, state, miniState, xml11, allowRestrictedChar);
            if (debug) System.err.println("tokenize: c="+c+" cc="+cc);
            
            // Check for illegal characters
            if (cc == CharClass.ILLEGAL) {
                // WFC: Characters (Production 2)
                // "All characters in an XML document must be legal XML characters"
                throw fatalError("Illegal XML character: 0x" + Integer.toHexString(c).toUpperCase());
            }
            
            // Special handling for greedy AND name accumulation states
            // Entity refs and char refs are NOT handled here - they go through the trie
            if (miniState.isGreedyAccumulation() ||
                miniState == MiniState.ACCUMULATING_NAME) {
                
                // Check if this character continues the accumulation
                boolean shouldContinue = false;
                if (miniState == MiniState.ACCUMULATING_NAME) {
                    shouldContinue = (cc == CharClass.NAME_START_CHAR || cc == CharClass.NAME_CHAR || 
                                     cc == CharClass.DIGIT || cc == CharClass.DASH || cc == CharClass.COLON);
                } else if (miniState.isGreedyAccumulation()) {
                    shouldContinue = !shouldStopAccumulating(cc, miniState);
                }
                
                if (shouldContinue) {
                    // Fast path for CDATA in CONTENT state: bulk scan for delimiters
                    // This avoids per-character classification for long text runs
                    if (miniState == MiniState.ACCUMULATING_CDATA && state == TokenizerState.CONTENT) {
                        // Scan ahead for '<', '&', or whitespace (CDATA delimiters in content)
                        while (++pos < limit) {
                            char ch = charBuffer.get(pos);
                            if (ch == '<' || ch == '&' || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                                break;
                            }
                            // Check for illegal characters (control chars except whitespace)
                            if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') {
                                break; // Let main loop handle the error
                            }
                        }
                        continue;
                    }
                    // Fast path for NAME accumulation: bulk scan for name characters
                    if (miniState == MiniState.ACCUMULATING_NAME) {
                        while (++pos < limit) {
                            char ch = charBuffer.get(pos);
                            // NameChar: [A-Za-z0-9._:-] plus extended Unicode (handled by main loop)
                            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') ||
                                (ch >= '0' && ch <= '9') || ch == '_' || ch == '.' || 
                                ch == '-' || ch == ':') {
                                continue;
                            }
                            // Non-ASCII or delimiter - let main loop handle it
                            break;
                        }
                        continue;
                    }
                    // Continue accumulating (single character)
                    pos++;
                    continue;
                } else {
                    // Normal case: emit accumulated token, then let trie handle delimiter
                    int tokenLength = pos - tokenStartPos;
                    if (tokenLength > 0) {
                        Token tokenType = miniState.getTokenType();
                        if (tokenType != null) {
                            emitTokenWindow(charBuffer, tokenType, tokenStartPos, tokenLength);                            }
                    }
                        
                    // Update tokenStartPos to start of delimiter, and reset miniState to READY
                    // The trie will then process the delimiter from READY state
                    tokenStartPos = pos;
                    miniState = MiniState.READY;
                    // Don't advance pos - reprocess delimiter through the trie from READY
                    continue;
                }
            }
            
            // Look up transition using cached flat array (updated when state changes)
            // This eliminates EnumMap.get() lookup on every character
            int index = miniState.ordinal() * MiniStateTransitionBuilder.NUM_CHAR_CLASSES + cc.ordinal();
            MiniStateTransitionBuilder.Transition transition = cachedTransitions[index];
            
            if (transition == null) {
                // Provide more specific error messages for common mistakes
                if (miniState == MiniState.SEEN_AMP) {
                    // WFC: Entity Reference (Production 67, 68)
                    // "Entity references must start with '&' followed by Name or '#'"
                    throw fatalError("Invalid entity reference: '&' must be followed by entity name or '#', not '" + c + "'");
                }
                // WFC: Well-Formed Documents (Section 2.1)
                // "All characters must be valid in their context"
                throw fatalError("Unexpected character '" + c + "' (" + cc + ") in " + state + ":" + miniState);
            }
            if (debug) System.err.println("\tminiState="+miniState+" transition="+transition);
            
            // Special validation: hex character references must use lowercase 'x'
            if (miniState == MiniState.SEEN_AMP_HASH && transition.nextMiniState == MiniState.SEEN_AMP_HASH_X) {
                if (c != 'x') {
                    // WFC: Character Reference (Production 66)
                    // "Hexadecimal character references must use lowercase 'x'"
                    throw fatalError("Hexadecimal character references must use lowercase 'x', not '" + c + "'");
                }
            }
            
            // Handle sequence consumption or position advancement
            int posAfterChar = pos + 1;  // Position after consuming this character
            if (transition.sequenceToConsume != null) {
                // The trigger character that matched the transition is at pos
                // We need to consume the sequence starting from pos (the trigger character)
                // For example, when we see 'O' in SEEN_LT_BANG_D, we want to consume "OCTYPE" starting from 'O'
                charBuffer.position(pos);
                if (!consumeSequence(charBuffer, transition.sequenceToConsume, pos)) {
                    // Not enough data - reset and underflow
                    charBuffer.position(tokenStartPos);
                    miniState = MiniState.READY;
                    return;
                }
                posAfterChar = charBuffer.position();  // Update after consuming sequence
            }
            
            // Emit token(s) if specified
            // Determine if we should exclude the trigger character from the first token
            // When emitting FROM ACCUMULATING_MARKUP_NAME, always exclude the trigger (e.g., % or whitespace)
            // When transitioning TO accumulating states, also exclude the trigger
            boolean excludeTrigger = (miniState == MiniState.ACCUMULATING_MARKUP_NAME ||
                                     transition.nextMiniState == MiniState.ACCUMULATING_NAME ||
                                     transition.nextMiniState == MiniState.ACCUMULATING_ENTITY_NAME ||
                                     transition.nextMiniState == MiniState.ACCUMULATING_PARAM_ENTITY_NAME ||
                                     (miniState == MiniState.SEEN_LT_BANG_OPEN_BRACKET && 
                                      transition.nextMiniState == MiniState.READY &&
                                      transition.stateToChangeTo == TokenizerState.CONDITIONAL_SECTION_KEYWORD) ||
                                     transition.nextMiniState == MiniState.ACCUMULATING_MARKUP_NAME ||
                                     transition.nextMiniState == MiniState.ACCUMULATING_CHAR_REF_DEC ||
                                     transition.nextMiniState == MiniState.ACCUMULATING_CHAR_REF_HEX ||
                                     transition.nextMiniState.isGreedyAccumulation());
            
            if (transition.tokensToEmit != null && !transition.tokensToEmit.isEmpty()) {
                
                for (int i = 0; i < transition.tokensToEmit.size(); i++) {
                    Token token = transition.tokensToEmit.get(i);
                    int tokenStart, tokenEnd;
                    
                    // Special handling for character references
                    if (miniState == MiniState.ACCUMULATING_CHAR_REF_DEC || 
                        miniState == MiniState.ACCUMULATING_CHAR_REF_HEX) {
                        // Emit ENTITYREF with resolved character(s)
                        boolean isHex = (miniState == MiniState.ACCUMULATING_CHAR_REF_HEX);
                        emitCharacterReference(charBuffer, tokenStartPos, posAfterChar, isHex);
                        // Character references count as 1 or 2 characters in output
                    }
                    // Special handling for markup declarations (ELEMENT, ATTLIST, ENTITY, NOTATION)
                    else if (token == Token.NAME && miniState == MiniState.ACCUMULATING_MARKUP_NAME) {
                        tokenStart = tokenStartPos;
                        tokenEnd = excludeTrigger ? pos : posAfterChar;
                        int tokenLength = tokenEnd - tokenStart;
                        if (tokenLength > 0) {
                            // Skip the '<!' prefix (2 characters) to get just the keyword
                            int keywordStart = tokenStart + 2;
                            int keywordLength = tokenLength - 2;
                            Token markupToken = checkMarkupDeclaration(charBuffer, keywordStart, keywordLength);
                            // Emit the entire token (including <!) but with the correct token type
                            // If not recognized as markup keyword, emit as NAME
                            emitTokenWindow(charBuffer, markupToken != null ? markupToken : token, tokenStart, tokenLength);                        }
                    }
                    // Special handling for general entity references
                    else if (token == Token.GENERALENTITYREF || token == Token.PARAMETERENTITYREF) {
                        // Extract just the entity name (without & and ; or % and ;)
                        tokenStart = tokenStartPos + 1;  // Skip '&' or '%'
                        tokenEnd = posAfterChar - 1;     // Skip ';'
                        int tokenLength = tokenEnd - tokenStart;
                        if (tokenLength > 0) {
                            // Check if it's a predefined entity (for GENERALENTITYREF only)
                            if (token == Token.GENERALENTITYREF) {
                                int predefinedIndex = getPredefinedEntityIndex(charBuffer, tokenStart, tokenEnd);
                                if (predefinedIndex >= 0) {
                                    // It's predefined - emit PREDEFENTITYREF with window into predefined text
                                    CharBuffer window = PREDEFINED_ENTITY_TEXT.duplicate();
                                    window.position(predefinedIndex);
                                    window.limit(predefinedIndex + 1);
                                    consumer.receive(Token.PREDEFENTITYREF, window);
                                } else {
                                    // General entity - emit name only
                                    emitTokenWindow(charBuffer, Token.GENERALENTITYREF, tokenStart, tokenLength);                                }
                            } else {
                                // Parameter entity - emit name only
                                emitTokenWindow(charBuffer, Token.PARAMETERENTITYREF, tokenStart, tokenLength);                            }
                        }
                    }
                    else if (i == 0) {
                        // First token
                        tokenStart = tokenStartPos;
                        tokenEnd = excludeTrigger ? pos : posAfterChar;
                        int tokenLength = tokenEnd - tokenStart;
                        if (tokenLength > 0) {
                            emitTokenWindow(charBuffer, token, tokenStart, tokenLength);                        }
                    } else {
                        // Subsequent tokens: emit the trigger character(s)
                        tokenStart = pos;
                        tokenEnd = posAfterChar;
                        int tokenLength = tokenEnd - tokenStart;
                        if (tokenLength > 0) {
                            emitTokenWindow(charBuffer, token, tokenStart, tokenLength);                        }
                    }
                }
                
                // Set tokenStartPos for next token
                // If transitioning to accumulating state, start at the trigger char (which will be accumulated)
                if (excludeTrigger/* && transition.tokensToEmit.isEmpty()*/) {
                    tokenStartPos = pos;  // Trigger char will be first char of accumulated token
                } else {
                    tokenStartPos = posAfterChar;
                }
            }
            
            // Advance position
            // Special case: if excludeTrigger is true and we're transitioning to READY from SEEN_LT_BANG_OPEN_BRACKET,
            // don't advance pos so the trigger character can be processed in the next iteration
            boolean skipAdvance = excludeTrigger && 
                                  miniState == MiniState.SEEN_LT_BANG_OPEN_BRACKET && 
                                  transition.nextMiniState == MiniState.READY &&
                                  transition.stateToChangeTo == TokenizerState.CONDITIONAL_SECTION_KEYWORD;
            int oldPos = pos;
            if (!skipAdvance) {
                pos = posAfterChar;
            }
            
            // Update location tracking for characters just consumed
            for (int i = oldPos; i < pos; i++) {
                char ch = charBuffer.get(i);
                charPosition++;
                if (ch == '\n') {
                    lineNumber++;
                    columnNumber = 0;
                } else {
                    columnNumber++;
                }
            }
            
            // Change state if specified
            if (transition.stateToChangeTo != null) {
                TokenizerState newState = transition.stateToChangeTo;
                
                // Save return state when entering COMMENT, PI_TARGET, or PI_DATA
                // But don't overwrite if transitioning from PI_TARGET to PI_DATA (preserve original)
                if (newState == TokenizerState.COMMENT ||
                    newState == TokenizerState.PI_TARGET ||
                    (newState == TokenizerState.PI_DATA && state != TokenizerState.PI_TARGET)) {
                    returnState = state;
                }
                // Restore return state when exiting COMMENT/PI back to CONTENT
                // Note: PI can exit from PI_TARGET (empty PI like <?Pi?>) or PI_DATA
                else if (newState == TokenizerState.CONTENT &&
                         (state == TokenizerState.COMMENT ||
                          state == TokenizerState.PI_TARGET ||
                          state == TokenizerState.PI_DATA)) {
                    if (returnState != null) {
                        newState = returnState;
                        returnState = null;
                    }
                }
                // Save return state when entering quoted string states from CONDITIONAL_SECTION_INCLUDE
                else if ((newState == TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT ||
                          newState == TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS) &&
                         state == TokenizerState.CONDITIONAL_SECTION_INCLUDE) {
                    returnState = state;
                }
                // Restore return state when exiting quoted string back to original state
                else if (newState == TokenizerState.DOCTYPE_INTERNAL &&
                         (state == TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT ||
                          state == TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS) &&
                         returnState != null) {
                    newState = returnState;
                    returnState = null;
                }
                
                changeState(newState);
            }
            
            // Check if we just emitted OPEN_BRACKET after INCLUDE/IGNORE keyword
            // If so, change to the appropriate conditional section state
            if (state == TokenizerState.CONDITIONAL_SECTION_KEYWORD && 
                pendingConditionalType != null &&
                transition.tokensToEmit != null) {
                // Check if OPEN_BRACKET was emitted
                for (Token emittedToken : transition.tokensToEmit) {
                    if (emittedToken == Token.OPEN_BRACKET) {
                        // Change to the appropriate conditional section state
                        TokenizerState newState;
                        if (pendingConditionalType == Token.INCLUDE) {
                            newState = TokenizerState.CONDITIONAL_SECTION_INCLUDE;
                        } else {
                            newState = TokenizerState.CONDITIONAL_SECTION_IGNORE;
                        }
                        changeState(newState);
                        pendingConditionalType = null;
                        break;
                    }
                }
            }
            
            // Move to next mini-state
            miniState = transition.nextMiniState;
        }
        
        // Update charBuffer position to reflect how much we consumed
        charBuffer.position(pos);
        
        // Buffer exhausted - flush greedy tokens or handle incomplete tokens
        if (miniState.isGreedyAccumulation()) {
            int tokenLength = pos - tokenStartPos;
            if (tokenLength > 0) {
                emitTokenWindow(charBuffer, miniState.getTokenType(), tokenStartPos, tokenLength);            }
            miniState = MiniState.READY;
        } else if (miniState != MiniState.READY) {
            // We're in a non-greedy, non-READY state (e.g., SEEN_AMP waiting for entity name)
            // This means we have an incomplete token. Rewind the buffer to save it for next receive()
            charBuffer.position(tokenStartPos);
            // Reset miniState to READY so the incomplete token will be reprocessed from scratch
            // when more data arrives (from underflow)
            miniState = MiniState.READY;
        }
    }
    
    // ===== Helper Methods =====
    
    /**
     * Determines if accumulation should stop for the given character class.
     */
    private boolean shouldStopAccumulating(CharClass cc, MiniState miniState) {
        if (miniState == MiniState.ACCUMULATING_WHITESPACE) {
            return cc != CharClass.WHITESPACE;
        }
        
        if (miniState == MiniState.ACCUMULATING_CDATA) {
            // Context-dependent stop characters
            switch (state) {
                case CONTENT:
                    return cc == CharClass.LT || cc == CharClass.AMP || cc == CharClass.WHITESPACE;
                case ATTR_VALUE_APOS:
                case ATTR_VALUE_QUOT:
                    // XML Spec Section 3.1: AttValue ::= '"' ([^<&"] | Reference)* '"' | "'" ([^<&'] | Reference)* "'"
                    // Only <, &, and the quote character are excluded. > is allowed.
                    return cc == CharClass.LT || cc == CharClass.AMP ||
                           cc == CharClass.APOS || cc == CharClass.QUOT;
                case DOCTYPE_QUOTED_APOS:
                case DOCTYPE_INTERNAL_QUOTED_APOS:
                    // Inside single-quoted string - stop on APOS (matching quote), AMP (entity ref), LT (illegal)
                    // QUOT (double quote) is literal data inside single quotes
                    return cc == CharClass.LT || cc == CharClass.AMP || cc == CharClass.APOS;
                case DOCTYPE_QUOTED_QUOT:
                case DOCTYPE_INTERNAL_QUOTED_QUOT:
                    // Inside double-quoted string - stop on QUOT (matching quote), AMP (entity ref), LT (illegal)
                    // APOS (apostrophe) is literal data inside double quotes
                    return cc == CharClass.LT || cc == CharClass.AMP || cc == CharClass.QUOT;
                case COMMENT:
                    return cc == CharClass.DASH;
                case CDATA_SECTION:
                    return cc == CharClass.CLOSE_BRACKET;
                case PI_DATA:
                    return cc == CharClass.QUERY;
                default:
                    return true;  // Unknown context, stop immediately
            }
        }
        
        return false;
    }
    
    /**
     * Attempts to consume an exact character sequence.
     * Returns false if not enough data is available.
     */
    private boolean consumeSequence(CharBuffer charBuffer, String sequence, int startPos) throws SAXException {
        if (charBuffer.remaining() < sequence.length()) {
            // Not enough data
            charBuffer.position(startPos);
            return false;
        }
        
        // Verify exact match
        for (int i = 0; i < sequence.length(); i++) {
            char actual = charBuffer.get();
            char expected = sequence.charAt(i);
            if (actual != expected) {
                // Include what we actually found for debugging
                StringBuilder found = new StringBuilder();
                charBuffer.position(charBuffer.position() - 1); // Back up to show what we found
                for (int j = 0; j < Math.min(sequence.length(), charBuffer.remaining() + 1); j++) {
                    found.append(charBuffer.get());
                }
                // WFC: Well-Formed Documents (Section 2.1)
                // "All markup must match expected patterns"
                throw fatalError("Expected '" + sequence + "' but found mismatch at position " + i + 
                    " (expected '" + expected + "', found '" + actual + "', context: '" + found + "')");
            }
        }
        
        return true;
    }
    
    /**
     * Emits a token with a window into the character buffer.
     * For NAME tokens in DOCTYPE context, checks if they're keywords and converts them.
     * For NAME tokens in DOCTYPE_INTERNAL with ACCUMULATING_MARKUP_NAME, converts to markup declaration tokens.
     */
    private void emitTokenWindow(CharBuffer charBuffer, Token token, int start, int length) throws SAXException {
        // Check if this is a NAME token in DOCTYPE context that should be a keyword
        // This applies to both DOCTYPE (<!DOCTYPE doc SYSTEM...>) and DOCTYPE_INTERNAL (inside [...])
        if (token == Token.NAME && (state == TokenizerState.DOCTYPE || state == TokenizerState.DOCTYPE_INTERNAL)) {
            Token keywordToken = checkDOCTYPEKeyword(charBuffer, start, length);
            if (keywordToken != null) {
                token = keywordToken;
            }
        }
        
        // Check if this is a NAME token in DOCTYPE_INTERNAL or CONDITIONAL_SECTION_INCLUDE that should be a DTD keyword
        // (except for markup declaration names which are handled separately below)
        if (token == Token.NAME && 
            (state == TokenizerState.DOCTYPE_INTERNAL ||
             state == TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT ||
             state == TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS ||
             state == TokenizerState.CONDITIONAL_SECTION_INCLUDE) &&
            miniState != MiniState.ACCUMULATING_MARKUP_NAME) {
            Token keywordToken = checkDTDKeyword(charBuffer, start, length);
            // checkDTDKeyword returns Token.NAME if not a keyword, so we don't need to check for null
            token = keywordToken;
        }
        
        // Check if this is a conditional section keyword (INCLUDE or IGNORE)
        if (token == Token.NAME && state == TokenizerState.CONDITIONAL_SECTION_KEYWORD) {
            // Check for "INCLUDE" or "IGNORE"
            if (length == 7 && 
                charBuffer.get(start) == 'I' &&
                charBuffer.get(start + 1) == 'N' &&
                charBuffer.get(start + 2) == 'C' &&
                charBuffer.get(start + 3) == 'L' &&
                charBuffer.get(start + 4) == 'U' &&
                charBuffer.get(start + 5) == 'D' &&
                charBuffer.get(start + 6) == 'E') {
                token = Token.INCLUDE;
                pendingConditionalType = Token.INCLUDE;
            } else if (length == 6 &&
                charBuffer.get(start) == 'I' &&
                charBuffer.get(start + 1) == 'G' &&
                charBuffer.get(start + 2) == 'N' &&
                charBuffer.get(start + 3) == 'O' &&
                charBuffer.get(start + 4) == 'R' &&
                charBuffer.get(start + 5) == 'E') {
                token = Token.IGNORE;
                pendingConditionalType = Token.IGNORE;
            }
            // If not INCLUDE or IGNORE, it's an error - but we'll let the parser handle that
        }
        
        // Check if this is a markup declaration name in DOCTYPE_INTERNAL or CONDITIONAL_SECTION_INCLUDE
        if (token == Token.NAME && 
            (state == TokenizerState.DOCTYPE_INTERNAL || state == TokenizerState.CONDITIONAL_SECTION_INCLUDE) &&
            miniState == MiniState.ACCUMULATING_MARKUP_NAME) {
            Token markupToken = checkMarkupDeclaration(charBuffer, start, length);
            if (markupToken != null) {
                token = markupToken;
            }
        }
        
        // Check if this is a PI target name and validate it's not "xml" (case-insensitive)
        // Per XML 1.0 spec rule 17: PITarget names matching [Xx][Mm][Ll] are reserved
        if (token == Token.NAME && state == TokenizerState.PI_TARGET) {
            if (length == 3) {
                char c0 = charBuffer.get(start);
                char c1 = charBuffer.get(start + 1);
                char c2 = charBuffer.get(start + 2);
                if ((c0 == 'x' || c0 == 'X') && (c1 == 'm' || c1 == 'M') && (c2 == 'l' || c2 == 'L')) {
                    // This is ALWAYS an error in PI_TARGET state because:
                    // 1. If we're at the start of an external entity the <?xml is handled by DeclParser not Tokenizer
                    // 2. After the first text declaration, subsequent <?xml...?> are illegal PIs
                    // WFC: Processing Instructions (Production 16)
                    // "Processing instruction targets matching [Xx][Mm][Ll] are reserved"
                    throw fatalError("Processing instruction target matching [Xx][Mm][Ll] is reserved");
                }
            }
        }
        

        // Track nested conditional sections in IGNORE mode
        // When we emit START_CONDITIONAL in IGNORE mode, increment depth
        if (token == Token.START_CONDITIONAL && state == TokenizerState.CONDITIONAL_SECTION_IGNORE) {
            ignoreConditionalDepth++;
        }
        
        // Special handling for END_CDATA in conditional sections
        // When we emit ]]>, pop back to the previous state if we're in a conditional context
        if (token == Token.END_CDATA && 
            (state == TokenizerState.CONDITIONAL_SECTION_INCLUDE || 
             state == TokenizerState.CONDITIONAL_SECTION_IGNORE)) {
            
            // For IGNORE sections, track nesting depth
            if (state == TokenizerState.CONDITIONAL_SECTION_IGNORE && ignoreConditionalDepth > 0) {
                // Still inside nested conditional, just decrement depth
                ignoreConditionalDepth--;
            } else {
                // Pop back to the state we were in before entering this conditional section
                if (conditionalStateStack != null && !conditionalStateStack.isEmpty()) {
                    TokenizerState previousState = conditionalStateStack.pop();
                    changeState(previousState);
                }
                // Reset depth counter when exiting IGNORE mode
                ignoreConditionalDepth = 0;
            }
        }

        // Normal tokens go to main consumer
        if (token.hasAssociatedText()) {
            // Set window into charBuffer (no duplicate needed - consumer won't modify position)
            int savedPosition = charBuffer.position();
            int savedLimit = charBuffer.limit();
            charBuffer.position(start);
            charBuffer.limit(start + length);
            consumer.receive(token, charBuffer);
            // Restore position/limit for tokenizer to continue
            charBuffer.position(savedPosition);
            charBuffer.limit(savedLimit);
        } else {
            consumer.receive(token, null);
        }

    }

    /**
     * Creates a fatal error exception.
     */
    SAXException fatalError(String message) throws SAXException {
        return consumer.fatalError(message);
    }

    @Override
    public String toString() {
        String name = getName();
        return (name == null) ? super.toString() : name;
    }
    
}

