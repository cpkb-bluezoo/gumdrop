/*
 * MiniStateTransitionBuilder.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builder for constructing the mini-state transition table.
 * <p>
 * This builder provides a fluent API for defining state transitions in the
 * tokenizer's state trie. The transition table maps (State, MiniState, CharClass)
 * tuples to Transition objects that specify the next state, tokens to emit,
 * and any special processing required.
 * <p>
 * The complete transition table is built once in a static initializer block
 * and exposed as a package-private constant for use by all Tokenizer instances.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class MiniStateTransitionBuilder {
    
    /**
     * The complete, pre-built transition table used by all tokenizer instances.
     * Built once in static initializer block for efficiency.
     */
    static final Map<TokenizerState, Map<MiniState, Map<CharClass, Transition>>> TRANSITION_TABLE;
    
    /**
     * Optimized flat array lookup for transitions.
     * Maps TokenizerState → (MiniState × CharClass flattened array).
     * This eliminates two levels of HashMap/EnumMap lookups per character.
     */
    static final EnumMap<TokenizerState, Transition[]> FLAT_TRANSITION_TABLE;
    
    /**
     * Number of CharClass enum values (for array stride calculation).
     * Package-private so Tokenizer can use it for flat array indexing.
     */
    static final int NUM_CHAR_CLASSES = CharClass.values().length;
    
    /**
     * Number of MiniState enum values (for array size calculation).
     */
    private static final int NUM_MINI_STATES = MiniState.values().length;
    
    static {
        // Build the complete transition table once at class load time
        MiniStateTransitionBuilder builder = new MiniStateTransitionBuilder();
        
        // ===== State.CONTENT Transitions =====
        
        // READY - Starting state in content
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.READY)
                .on(CharClass.LT).to(MiniState.SEEN_LT).done()
                .on(CharClass.AMP).to(MiniState.SEEN_AMP).done()
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA, 
                       CharClass.DIGIT, CharClass.HEX_DIGIT,
                       CharClass.APOS, CharClass.QUOT, CharClass.EQ, CharClass.SEMICOLON,
                       CharClass.HASH, CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // SEEN_LT - After '<' in content
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.SEEN_LT)
                .on(CharClass.SLASH)
                    .emit(Token.START_END_ELEMENT)
                    .changeState(TokenizerState.ELEMENT_NAME)
                    .to(MiniState.READY).done()
                .on(CharClass.QUERY).to(MiniState.SEEN_LT_QUERY).done()
                .on(CharClass.BANG).to(MiniState.SEEN_LT_BANG).done()
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.LT)
                    .changeState(TokenizerState.ELEMENT_NAME)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // SEEN_LT_QUERY - After '<?'
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.SEEN_LT_QUERY)
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.START_PI)
                    .changeState(TokenizerState.PI_TARGET)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // SEEN_LT_BANG - After '<!'
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.SEEN_LT_BANG)
                .on(CharClass.DASH).to(MiniState.SEEN_LT_BANG_DASH).done()
                .on(CharClass.OPEN_BRACKET).to(MiniState.SEEN_LT_BANG_OPEN_BRACKET).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.SEEN_LT_BANG_D).done();
        
        // SEEN_LT_BANG_D - After '<!D' (checking for DOCTYPE)
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.SEEN_LT_BANG_D)
                .on(CharClass.NAME_START_CHAR)
                    .consumeSequence("OCTYPE")
                    .emit(Token.START_DOCTYPE)
                    .changeState(TokenizerState.DOCTYPE)
                    .to(MiniState.READY).done();
        
        // SEEN_LT_BANG_DASH - After '<!-'
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.SEEN_LT_BANG_DASH)
                .on(CharClass.DASH)
                    .emit(Token.START_COMMENT)
                    .changeState(TokenizerState.COMMENT)
                    .to(MiniState.READY).done();
        
        // SEEN_LT_BANG_OPEN_BRACKET - After '<!['
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.SEEN_LT_BANG_OPEN_BRACKET)
                .on(CharClass.NAME_START_CHAR)
                    .consumeSequence("CDATA[")
                    .emit(Token.START_CDATA)
                    .changeState(TokenizerState.CDATA_SECTION)
                    .to(MiniState.READY).done();
        
        // SEEN_AMP - After '&' (start of entity reference)
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.SEEN_AMP)
                .on(CharClass.HASH).to(MiniState.SEEN_AMP_HASH).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_ENTITY_NAME).done();
        
        // SEEN_AMP_HASH - After '&#' (start of character reference)
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.SEEN_AMP_HASH)
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_DEC).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.SEEN_AMP_HASH_X).done();
        
        // SEEN_AMP_HASH_X - After '&#x' (checking for hex character reference)
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.SEEN_AMP_HASH_X)
                .on(CharClass.HEX_DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done()
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done();  // Digits are also hex digits
        
        // ACCUMULATING_ENTITY_NAME - Collecting entity name after '&'
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.ACCUMULATING_ENTITY_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_ENTITY_NAME).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.GENERALENTITYREF)
                    .to(MiniState.READY).done();
        
        // ACCUMULATING_CHAR_REF_DEC - Collecting decimal character reference digits
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.ACCUMULATING_CHAR_REF_DEC)
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_DEC).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.CDATA)
                    .to(MiniState.READY).done();
        
        // ACCUMULATING_CHAR_REF_HEX - Collecting hexadecimal character reference digits
        builder.state(TokenizerState.CONTENT)
            .miniState(MiniState.ACCUMULATING_CHAR_REF_HEX)
                .on(CharClass.HEX_DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done()
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done()  // Digits are also hex digits
                .on(CharClass.SEMICOLON)
                    .emit(Token.CDATA)
                    .to(MiniState.READY).done();
        
        // ACCUMULATING_WHITESPACE and ACCUMULATING_CDATA are greedy states
        // They don't need explicit transitions - handled specially in tokenize()
        
        // ===== State.PROLOG_BEFORE_DOCTYPE Transitions =====
        // In prolog before DOCTYPE: can have whitespace, comments, PIs, DOCTYPE, or root element
        // Note: <?xml here is a PI (not XML declaration) and will be rejected by PI target validation
        
        builder.state(TokenizerState.PROLOG_BEFORE_DOCTYPE)
            .miniState(MiniState.READY)
                .on(CharClass.LT).to(MiniState.SEEN_LT).done()
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done();
        
        builder.state(TokenizerState.PROLOG_BEFORE_DOCTYPE)
            .miniState(MiniState.SEEN_LT)
                .on(CharClass.QUERY).to(MiniState.SEEN_LT_QUERY).done()
                .on(CharClass.BANG).to(MiniState.SEEN_LT_BANG).done()
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.LT)
                    .changeState(TokenizerState.ELEMENT_NAME)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        builder.state(TokenizerState.PROLOG_BEFORE_DOCTYPE)
            .miniState(MiniState.SEEN_LT_QUERY)
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.START_PI)
                    .changeState(TokenizerState.PI_TARGET)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        builder.state(TokenizerState.PROLOG_BEFORE_DOCTYPE)
            .miniState(MiniState.SEEN_LT_BANG)
                .on(CharClass.DASH).to(MiniState.SEEN_LT_BANG_DASH).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.SEEN_LT_BANG_D).done();
        
        builder.state(TokenizerState.PROLOG_BEFORE_DOCTYPE)
            .miniState(MiniState.SEEN_LT_BANG_DASH)
                .on(CharClass.DASH)
                    .emit(Token.START_COMMENT)
                    .changeState(TokenizerState.COMMENT)
                    .to(MiniState.READY).done();
        
        builder.state(TokenizerState.PROLOG_BEFORE_DOCTYPE)
            .miniState(MiniState.SEEN_LT_BANG_D)
                .on(CharClass.NAME_START_CHAR)
                    .consumeSequence("OCTYPE")
                    .emit(Token.START_DOCTYPE)
                    .changeState(TokenizerState.DOCTYPE)
                    .to(MiniState.READY).done();
        
        // ===== State.PROLOG_AFTER_DOCTYPE Transitions =====
        // In prolog after DOCTYPE: can have whitespace, comments, PIs, or root element (but no DOCTYPE)
        
        builder.state(TokenizerState.PROLOG_AFTER_DOCTYPE)
            .miniState(MiniState.READY)
                .on(CharClass.LT).to(MiniState.SEEN_LT).done()
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done();
        
        builder.state(TokenizerState.PROLOG_AFTER_DOCTYPE)
            .miniState(MiniState.SEEN_LT)
                .on(CharClass.QUERY).to(MiniState.SEEN_LT_QUERY).done()
                .on(CharClass.BANG).to(MiniState.SEEN_LT_BANG).done()
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.LT)
                    .changeState(TokenizerState.ELEMENT_NAME)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        builder.state(TokenizerState.PROLOG_AFTER_DOCTYPE)
            .miniState(MiniState.SEEN_LT_QUERY)
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.START_PI)
                    .changeState(TokenizerState.PI_TARGET)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        builder.state(TokenizerState.PROLOG_AFTER_DOCTYPE)
            .miniState(MiniState.SEEN_LT_BANG)
                .on(CharClass.DASH).to(MiniState.SEEN_LT_BANG_DASH).done();
                // Note: No DOCTYPE transition here - that would be a duplicate DOCTYPE error
        
        builder.state(TokenizerState.PROLOG_AFTER_DOCTYPE)
            .miniState(MiniState.SEEN_LT_BANG_DASH)
                .on(CharClass.DASH)
                    .emit(Token.START_COMMENT)
                    .changeState(TokenizerState.COMMENT)
                    .to(MiniState.READY).done();

        // ===== State.ELEMENT_NAME Transitions =====
        
        // READY - After '<' or '</', expecting element name
        builder.state(TokenizerState.ELEMENT_NAME)
            .miniState(MiniState.READY)
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.COLON)
                    .emit(Token.COLON)
                    .to(MiniState.READY).done()
                .on(CharClass.WHITESPACE)
                    .changeState(TokenizerState.ELEMENT_ATTRS)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.GT)
                    .emit(Token.GT)
                    .changeState(TokenizerState.CONTENT)
                    .to(MiniState.READY).done()
                .on(CharClass.SLASH).to(MiniState.SEEN_SLASH).done();
        
        // ACCUMULATING_NAME - Building element name
        builder.state(TokenizerState.ELEMENT_NAME)
            .miniState(MiniState.ACCUMULATING_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT, CharClass.DASH)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.COLON)
                    .emit(Token.NAME)
                    .emit(Token.COLON)
                    .to(MiniState.READY).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.NAME)
                    .changeState(TokenizerState.ELEMENT_ATTRS)
                    .to(MiniState.READY).done()
                .on(CharClass.GT)
                    .emit(Token.NAME)
                    .emit(Token.GT)
                    .changeState(TokenizerState.CONTENT)
                    .to(MiniState.READY).done()
                .on(CharClass.SLASH)
                    .emit(Token.NAME)
                    .to(MiniState.SEEN_SLASH).done();
        
        // SEEN_SLASH - After element name and '/', expecting '>'
        builder.state(TokenizerState.ELEMENT_NAME)
            .miniState(MiniState.SEEN_SLASH)
                .on(CharClass.GT)
                    .emit(Token.END_EMPTY_ELEMENT)
                    .changeState(TokenizerState.CONTENT)
                    .to(MiniState.READY).done();
        
        // ===== State.ELEMENT_ATTRS Transitions =====
        
        // READY - Between attributes or after element name
        builder.state(TokenizerState.ELEMENT_ATTRS)
            .miniState(MiniState.READY)
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.GT)
                    .emit(Token.GT)
                    .changeState(TokenizerState.CONTENT)
                    .to(MiniState.READY).done()
                .on(CharClass.SLASH).to(MiniState.SEEN_SLASH).done()
                .on(CharClass.EQ)
                    .emit(Token.EQ)
                    .to(MiniState.READY).done()
                .on(CharClass.COLON)
                    .emit(Token.COLON)
                    .to(MiniState.READY).done()
                .on(CharClass.QUOT)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.ATTR_VALUE_QUOT)
                    .to(MiniState.READY).done()
                .on(CharClass.APOS)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.ATTR_VALUE_APOS)
                    .to(MiniState.READY).done();
        
        // ACCUMULATING_NAME - Building attribute name
        builder.state(TokenizerState.ELEMENT_ATTRS)
            .miniState(MiniState.ACCUMULATING_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT, CharClass.DASH)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.COLON)
                    .emit(Token.NAME)
                    .emit(Token.COLON)
                    .to(MiniState.READY).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.NAME)
                    .to(MiniState.READY).done()
                .on(CharClass.EQ)
                    .emit(Token.NAME)
                    .emit(Token.EQ)
                    .to(MiniState.READY).done();
        
        // After '=' we expect a quote to start attribute value
        // Note: This is handled by having READY transition on QUOT/APOS
        builder.state(TokenizerState.ELEMENT_ATTRS)
            .miniState(MiniState.READY)
                .on(CharClass.QUOT)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.ATTR_VALUE_QUOT)
                    .to(MiniState.READY).done()
                .on(CharClass.APOS)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.ATTR_VALUE_APOS)
                    .to(MiniState.READY).done();
        
        // SEEN_SLASH - After '/' in element tag
        builder.state(TokenizerState.ELEMENT_ATTRS)
            .miniState(MiniState.SEEN_SLASH)
                .on(CharClass.GT)
                    .emit(Token.END_EMPTY_ELEMENT)
                    .changeState(TokenizerState.CONTENT)
                    .to(MiniState.READY).done();
        
        // ===== State.ATTR_VALUE_QUOT Transitions (inside "..." ) =====
        
        // READY - Inside attribute value delimited by "
        // XML Spec Section 3.1: AttValue ::= '"' ([^<&"] | Reference)* '"'
        // Only <, &, and " are excluded. > is allowed.
        builder.state(TokenizerState.ATTR_VALUE_QUOT)
            .miniState(MiniState.READY)
                .on(CharClass.QUOT)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.ELEMENT_ATTRS)
                    .to(MiniState.READY).done()
                .on(CharClass.AMP).to(MiniState.SEEN_AMP).done()
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.APOS, CharClass.EQ,
                       CharClass.SEMICOLON, CharClass.HASH, CharClass.COLON,
                       CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN, CharClass.PIPE,
                       CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.DASH,
                       CharClass.BANG, CharClass.QUERY, CharClass.SLASH, CharClass.PERCENT,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET, CharClass.GT)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // Entity references in attribute values (same as CONTENT)
        builder.state(TokenizerState.ATTR_VALUE_QUOT)
            .miniState(MiniState.SEEN_AMP)
                .on(CharClass.HASH).to(MiniState.SEEN_AMP_HASH).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_ENTITY_NAME).done();
        
        builder.state(TokenizerState.ATTR_VALUE_QUOT)
            .miniState(MiniState.SEEN_AMP_HASH)
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_DEC).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.SEEN_AMP_HASH_X).done();
        
        builder.state(TokenizerState.ATTR_VALUE_QUOT)
            .miniState(MiniState.SEEN_AMP_HASH_X)
                .on(CharClass.HEX_DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done();
        
        builder.state(TokenizerState.ATTR_VALUE_QUOT)
            .miniState(MiniState.ACCUMULATING_ENTITY_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_ENTITY_NAME).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.GENERALENTITYREF)
                    .to(MiniState.READY).done();
        
        builder.state(TokenizerState.ATTR_VALUE_QUOT)
            .miniState(MiniState.ACCUMULATING_CHAR_REF_DEC)
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_DEC).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.CDATA)
                    .to(MiniState.READY).done();
        
        builder.state(TokenizerState.ATTR_VALUE_QUOT)
            .miniState(MiniState.ACCUMULATING_CHAR_REF_HEX)
                .on(CharClass.HEX_DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.CDATA)
                    .to(MiniState.READY).done();
        
        // ACCUMULATING_CDATA - Accumulating character data in attribute value
        // XML Spec Section 3.1: AttValue ::= '"' ([^<&"] | Reference)* '"'
        // Only <, &, and " are excluded. > is allowed.
        builder.state(TokenizerState.ATTR_VALUE_QUOT)
            .miniState(MiniState.ACCUMULATING_CDATA)
                .on(CharClass.QUOT)
                    .emit(Token.CDATA)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.ELEMENT_ATTRS)
                    .to(MiniState.READY).done()
                .on(CharClass.AMP)
                    .emit(Token.CDATA)
                    .to(MiniState.SEEN_AMP).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.CDATA)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.APOS, CharClass.EQ,
                       CharClass.SEMICOLON, CharClass.HASH, CharClass.COLON,
                       CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN, CharClass.PIPE,
                       CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.DASH,
                       CharClass.BANG, CharClass.QUERY, CharClass.SLASH, CharClass.PERCENT,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET, CharClass.GT)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // ACCUMULATING_WHITESPACE - Accumulating whitespace in attribute value
        // XML Spec Section 3.1: AttValue ::= '"' ([^<&"] | Reference)* '"'
        // Only <, &, and " are excluded. > is allowed.
        builder.state(TokenizerState.ATTR_VALUE_QUOT)
            .miniState(MiniState.ACCUMULATING_WHITESPACE)
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.QUOT)
                    .emit(Token.S)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.ELEMENT_ATTRS)
                    .to(MiniState.READY).done()
                .on(CharClass.AMP)
                    .emit(Token.S)
                    .to(MiniState.SEEN_AMP).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.APOS, CharClass.EQ,
                       CharClass.SEMICOLON, CharClass.HASH, CharClass.COLON,
                       CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN, CharClass.PIPE,
                       CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.DASH,
                       CharClass.BANG, CharClass.QUERY, CharClass.SLASH, CharClass.PERCENT,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET, CharClass.GT)
                    .emit(Token.S)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // ===== State.ATTR_VALUE_APOS Transitions (inside '...' ) =====
        
        // READY - Inside attribute value delimited by '
        // XML Spec Section 3.1: AttValue ::= "'" ([^<&'] | Reference)* "'"
        // Only <, &, and ' are excluded. > is allowed.
        builder.state(TokenizerState.ATTR_VALUE_APOS)
            .miniState(MiniState.READY)
                .on(CharClass.APOS)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.ELEMENT_ATTRS)
                    .to(MiniState.READY).done()
                .on(CharClass.AMP).to(MiniState.SEEN_AMP).done()
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.QUOT, CharClass.EQ,
                       CharClass.SEMICOLON, CharClass.HASH, CharClass.COLON,
                       CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN, CharClass.PIPE,
                       CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.DASH,
                       CharClass.BANG, CharClass.QUERY, CharClass.SLASH, CharClass.PERCENT,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET, CharClass.GT)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // Entity references in attribute values (same as ATTR_VALUE_QUOT)
        builder.state(TokenizerState.ATTR_VALUE_APOS)
            .miniState(MiniState.SEEN_AMP)
                .on(CharClass.HASH).to(MiniState.SEEN_AMP_HASH).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_ENTITY_NAME).done();
        
        builder.state(TokenizerState.ATTR_VALUE_APOS)
            .miniState(MiniState.SEEN_AMP_HASH)
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_DEC).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.SEEN_AMP_HASH_X).done();
        
        builder.state(TokenizerState.ATTR_VALUE_APOS)
            .miniState(MiniState.SEEN_AMP_HASH_X)
                .on(CharClass.HEX_DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done();
        
        builder.state(TokenizerState.ATTR_VALUE_APOS)
            .miniState(MiniState.ACCUMULATING_ENTITY_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_ENTITY_NAME).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.GENERALENTITYREF)
                    .to(MiniState.READY).done();
        
        builder.state(TokenizerState.ATTR_VALUE_APOS)
            .miniState(MiniState.ACCUMULATING_CHAR_REF_DEC)
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_DEC).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.CDATA)
                    .to(MiniState.READY).done();
        
        builder.state(TokenizerState.ATTR_VALUE_APOS)
            .miniState(MiniState.ACCUMULATING_CHAR_REF_HEX)
                .on(CharClass.HEX_DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.CDATA)
                    .to(MiniState.READY).done();
        
        // ACCUMULATING_CDATA - Accumulating character data in attribute value
        // XML Spec Section 3.1: AttValue ::= "'" ([^<&'] | Reference)* "'"
        // Only <, &, and ' are excluded. > is allowed.
        builder.state(TokenizerState.ATTR_VALUE_APOS)
            .miniState(MiniState.ACCUMULATING_CDATA)
                .on(CharClass.APOS)
                    .emit(Token.CDATA)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.ELEMENT_ATTRS)
                    .to(MiniState.READY).done()
                .on(CharClass.AMP)
                    .emit(Token.CDATA)
                    .to(MiniState.SEEN_AMP).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.CDATA)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.QUOT, CharClass.EQ,
                       CharClass.SEMICOLON, CharClass.HASH, CharClass.COLON,
                       CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN, CharClass.PIPE,
                       CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.DASH,
                       CharClass.BANG, CharClass.QUERY, CharClass.SLASH, CharClass.PERCENT,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET, CharClass.GT)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // ACCUMULATING_WHITESPACE - Accumulating whitespace in attribute value
        // XML Spec Section 3.1: AttValue ::= "'" ([^<&'] | Reference)* "'"
        // Only <, &, and ' are excluded. > is allowed.
        builder.state(TokenizerState.ATTR_VALUE_APOS)
            .miniState(MiniState.ACCUMULATING_WHITESPACE)
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.APOS)
                    .emit(Token.S)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.ELEMENT_ATTRS)
                    .to(MiniState.READY).done()
                .on(CharClass.AMP)
                    .emit(Token.S)
                    .to(MiniState.SEEN_AMP).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.QUOT, CharClass.EQ,
                       CharClass.SEMICOLON, CharClass.HASH, CharClass.COLON,
                       CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN, CharClass.PIPE,
                       CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.DASH,
                       CharClass.BANG, CharClass.QUERY, CharClass.SLASH, CharClass.PERCENT,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET, CharClass.GT)
                    .emit(Token.S)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // ===== State.COMMENT Transitions =====
        
        // READY - Inside comment, accumulating text
        builder.state(TokenizerState.COMMENT)
            .miniState(MiniState.READY)
                .on(CharClass.DASH).to(MiniState.SEEN_DASH).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.APOS,
                       CharClass.QUOT, CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.BANG, CharClass.QUERY, CharClass.SLASH, CharClass.PERCENT,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // SEEN_DASH - After '-' in comment
        builder.state(TokenizerState.COMMENT)
            .miniState(MiniState.SEEN_DASH)
                .on(CharClass.DASH).to(MiniState.SEEN_DASH_DASH).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.APOS,
                       CharClass.QUOT, CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.BANG, CharClass.QUERY, CharClass.SLASH, CharClass.PERCENT,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // SEEN_DASH_DASH - After '--' in comment
        builder.state(TokenizerState.COMMENT)
            .miniState(MiniState.SEEN_DASH_DASH)
                .on(CharClass.GT)
                    .emit(Token.END_COMMENT)
                    .changeState(TokenizerState.CONTENT)
                    .to(MiniState.READY).done();
        
        // ===== State.CDATA_SECTION Transitions =====
        
        // READY - Inside CDATA section
        builder.state(TokenizerState.CDATA_SECTION)
            .miniState(MiniState.READY)
                .on(CharClass.CLOSE_BRACKET).to(MiniState.SEEN_CLOSE_BRACKET).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.APOS,
                       CharClass.QUOT, CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // SEEN_CLOSE_BRACKET - After ']' in CDATA
        builder.state(TokenizerState.CDATA_SECTION)
            .miniState(MiniState.SEEN_CLOSE_BRACKET)
                .on(CharClass.CLOSE_BRACKET).to(MiniState.SEEN_CLOSE_BRACKET_CLOSE_BRACKET).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.APOS,
                       CharClass.QUOT, CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // SEEN_CLOSE_BRACKET_CLOSE_BRACKET - After ']]' in CDATA
        builder.state(TokenizerState.CDATA_SECTION)
            .miniState(MiniState.SEEN_CLOSE_BRACKET_CLOSE_BRACKET)
                .on(CharClass.GT)
                    .emit(Token.END_CDATA)
                    .changeState(TokenizerState.CONTENT)
                    .to(MiniState.READY).done()
                .on(CharClass.CLOSE_BRACKET)
                    .to(MiniState.SEEN_CLOSE_BRACKET_CLOSE_BRACKET).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.AMP, CharClass.APOS,
                       CharClass.QUOT, CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // ===== State.PI_TARGET Transitions =====
        
        // READY - Expecting PI target name
        builder.state(TokenizerState.PI_TARGET)
            .miniState(MiniState.READY)
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.DASH).to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.WHITESPACE)
                    .changeState(TokenizerState.PI_DATA)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.QUERY).to(MiniState.SEEN_QUERY).done();
        
        // ACCUMULATING_NAME - Building PI target
        builder.state(TokenizerState.PI_TARGET)
            .miniState(MiniState.ACCUMULATING_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT, CharClass.DASH, CharClass.COLON)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.NAME)
                    .changeState(TokenizerState.PI_DATA)
                    .to(MiniState.READY).done()
                .on(CharClass.QUERY)
                    .emit(Token.NAME)
                    .to(MiniState.SEEN_QUERY).done();
        
        // SEEN_QUERY - After '?' in PI target (checking for end)
        builder.state(TokenizerState.PI_TARGET)
            .miniState(MiniState.SEEN_QUERY)
                .on(CharClass.GT)
                    // NAME already emitted when we transitioned to SEEN_QUERY
                    .emit(Token.END_PI)
                    .changeState(TokenizerState.CONTENT)
                    .to(MiniState.READY).done();
        
        // ===== State.PI_DATA Transitions =====
        
        // READY - Inside PI data
        builder.state(TokenizerState.PI_DATA)
            .miniState(MiniState.READY)
                .on(CharClass.QUERY).to(MiniState.SEEN_QUERY).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.APOS,
                       CharClass.QUOT, CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.SLASH, CharClass.PERCENT,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // Note: No explicit ACCUMULATING_CDATA transitions needed for PI_DATA
        // Greedy accumulation automatically stops on '?' (per shouldStopAccumulating),
        // emits accumulated CDATA, resets to READY, and reprocesses '?' from READY state
        
        // SEEN_QUERY - After '?' in PI data (checking for '>')
        builder.state(TokenizerState.PI_DATA)
            .miniState(MiniState.SEEN_QUERY)
                .on(CharClass.GT)
                    // Found '?>' - end of PI
                    .emit(Token.END_PI)
                    .changeState(TokenizerState.CONTENT)
                    .to(MiniState.READY).done()
                .on(CharClass.QUERY)
                    // Another '?' - stay in SEEN_QUERY, still looking for '>'
                    // This handles sequences like '??' or '??>' correctly
                    .to(MiniState.SEEN_QUERY).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.AMP, CharClass.APOS,
                       CharClass.QUOT, CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.SLASH, CharClass.PERCENT,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    // Not '>' - this is regular data, go back to accumulating
                    // The '?' will be included in the next accumulated CDATA chunk
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // ===== TokenizerState.DOCTYPE Transitions =====
        
        // DOCTYPE:READY - Waiting for name, keywords, or end
        builder.state(TokenizerState.DOCTYPE)
            .miniState(MiniState.READY)
                .on(CharClass.LT).to(MiniState.SEEN_LT).done()
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.GT)
                    .emit(Token.GT)
                    .changeState(TokenizerState.PROLOG_AFTER_DOCTYPE)
                    .to(MiniState.READY).done()
                .on(CharClass.OPEN_BRACKET)
                    .emit(Token.OPEN_BRACKET)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL)
                    .to(MiniState.READY).done()
                .on(CharClass.APOS)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE_QUOTED_APOS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUOT)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE_QUOTED_QUOT)
                    .to(MiniState.READY).done();
        
        // DOCTYPE:SEEN_LT - After '<' in DOCTYPE (comments/PIs)
        builder.state(TokenizerState.DOCTYPE)
            .miniState(MiniState.SEEN_LT)
                .on(CharClass.BANG).to(MiniState.SEEN_LT_BANG).done()
                .on(CharClass.QUERY).to(MiniState.SEEN_LT_QUERY).done();
        
        // DOCTYPE:SEEN_LT_BANG - After '<!' in DOCTYPE
        builder.state(TokenizerState.DOCTYPE)
            .miniState(MiniState.SEEN_LT_BANG)
                .on(CharClass.DASH).to(MiniState.SEEN_LT_BANG_DASH).done();
        
        // DOCTYPE:SEEN_LT_BANG_DASH - After '<!-' in DOCTYPE
        builder.state(TokenizerState.DOCTYPE)
            .miniState(MiniState.SEEN_LT_BANG_DASH)
                .on(CharClass.DASH)
                    .emit(Token.START_COMMENT)
                    .changeState(TokenizerState.COMMENT)
                    .to(MiniState.READY).done();
        
        // DOCTYPE:SEEN_LT_QUERY - After '<?' in DOCTYPE
        builder.state(TokenizerState.DOCTYPE)
            .miniState(MiniState.SEEN_LT_QUERY)
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.START_PI)
                    .changeState(TokenizerState.PI_TARGET)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // DOCTYPE:ACCUMULATING_NAME - Collecting name tokens (may be keywords)
        builder.state(TokenizerState.DOCTYPE)
            .miniState(MiniState.ACCUMULATING_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.NAME)  // Will be converted to keyword token if needed
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.GT)
                    .emit(Token.NAME)
                    .emit(Token.GT)
                    .changeState(TokenizerState.PROLOG_AFTER_DOCTYPE)
                    .to(MiniState.READY).done()
                .on(CharClass.OPEN_BRACKET)
                    .emit(Token.NAME)
                    .emit(Token.OPEN_BRACKET)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL)
                    .to(MiniState.READY).done()
                .on(CharClass.APOS)
                    .emit(Token.NAME)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE_QUOTED_APOS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUOT)
                    .emit(Token.NAME)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE_QUOTED_QUOT)
                    .to(MiniState.READY).done();
        
        // ===== TokenizerState.DOCTYPE_QUOTED_QUOT Transitions =====
        // (Quoted strings in DOCTYPE - system/public IDs)
        
        // DOCTYPE_QUOTED_QUOT:READY - Inside "..." in DOCTYPE
        builder.state(TokenizerState.DOCTYPE_QUOTED_QUOT)
            .miniState(MiniState.READY)
                .on(CharClass.QUOT)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE)
                    .to(MiniState.READY).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.APOS,
                       CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // DOCTYPE_QUOTED_QUOT:ACCUMULATING_CDATA - Greedy CDATA accumulation
        builder.state(TokenizerState.DOCTYPE_QUOTED_QUOT)
            .miniState(MiniState.ACCUMULATING_CDATA)
                .on(CharClass.QUOT)
                    .emit(Token.CDATA)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE)
                    .to(MiniState.READY).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.APOS,
                       CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // ===== TokenizerState.DOCTYPE_QUOTED_APOS Transitions =====
        // (Quoted strings in DOCTYPE with single quotes)
        
        // DOCTYPE_QUOTED_APOS:READY - Inside '...' in DOCTYPE
        builder.state(TokenizerState.DOCTYPE_QUOTED_APOS)
            .miniState(MiniState.READY)
                .on(CharClass.APOS)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE)
                    .to(MiniState.READY).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.QUOT,
                       CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // DOCTYPE_QUOTED_APOS:ACCUMULATING_CDATA - Greedy CDATA accumulation
        builder.state(TokenizerState.DOCTYPE_QUOTED_APOS)
            .miniState(MiniState.ACCUMULATING_CDATA)
                .on(CharClass.APOS)
                    .emit(Token.CDATA)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE)
                    .to(MiniState.READY).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.QUOT,
                       CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // ===== TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT Transitions =====
        // (Quoted strings in DOCTYPE_INTERNAL - entity replacement text)
        
        // DOCTYPE_INTERNAL_QUOTED_QUOT:READY - Inside "..." in DOCTYPE_INTERNAL
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
            .miniState(MiniState.READY)
                .on(CharClass.QUOT)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL)
                    .to(MiniState.READY).done()
                .on(CharClass.AMP).to(MiniState.SEEN_AMP).done()
                .on(CharClass.PERCENT).to(MiniState.SEEN_PERCENT).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.APOS,
                       CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // DOCTYPE_INTERNAL_QUOTED_QUOT:ACCUMULATING_CDATA - Greedy CDATA accumulation
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
            .miniState(MiniState.ACCUMULATING_CDATA)
                .on(CharClass.QUOT)
                    .emit(Token.CDATA)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL)
                    .to(MiniState.READY).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.APOS,
                       CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // Entity references in DOCTYPE_INTERNAL_QUOTED_QUOT (same pattern as ATTR_VALUE_QUOT)
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
            .miniState(MiniState.SEEN_AMP)
                .on(CharClass.HASH).to(MiniState.SEEN_AMP_HASH).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_ENTITY_NAME).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
            .miniState(MiniState.SEEN_AMP_HASH)
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_DEC).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.SEEN_AMP_HASH_X).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
            .miniState(MiniState.SEEN_AMP_HASH_X)
                .on(CharClass.HEX_DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
            .miniState(MiniState.ACCUMULATING_ENTITY_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_ENTITY_NAME).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.GENERALENTITYREF)
                    .to(MiniState.READY).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
            .miniState(MiniState.ACCUMULATING_CHAR_REF_DEC)
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_DEC).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.CDATA)
                    .to(MiniState.READY).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
            .miniState(MiniState.ACCUMULATING_CHAR_REF_HEX)
                .on(CharClass.HEX_DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.CDATA)
                    .to(MiniState.READY).done();
        
        // Parameter entity references in DOCTYPE_INTERNAL_QUOTED_QUOT
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
            .miniState(MiniState.SEEN_PERCENT)
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_PARAM_ENTITY_NAME).done()
                .onAny(CharClass.NAME_CHAR, CharClass.CHAR_DATA, CharClass.DIGIT, CharClass.HEX_DIGIT,
                       CharClass.WHITESPACE, CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.APOS,
                       CharClass.QUOT, CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    // Not a param entity ref - treat % as literal and continue with this char
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
            .miniState(MiniState.ACCUMULATING_PARAM_ENTITY_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_PARAM_ENTITY_NAME).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.PARAMETERENTITYREF)
                    .to(MiniState.READY).done();
        
        // ===== TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS Transitions =====
        // (Quoted strings in DOCTYPE_INTERNAL - entity replacement text)
        
        // DOCTYPE_INTERNAL_QUOTED_APOS:READY - Inside '...' in DOCTYPE_INTERNAL
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
            .miniState(MiniState.READY)
                .on(CharClass.APOS)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL)
                    .to(MiniState.READY).done()
                .on(CharClass.AMP).to(MiniState.SEEN_AMP).done()
                .on(CharClass.PERCENT).to(MiniState.SEEN_PERCENT).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.QUOT,
                       CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // DOCTYPE_INTERNAL_QUOTED_APOS:ACCUMULATING_CDATA - Greedy CDATA accumulation
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
            .miniState(MiniState.ACCUMULATING_CDATA)
                .on(CharClass.APOS)
                    .emit(Token.CDATA)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL)
                    .to(MiniState.READY).done()
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.CHAR_DATA,
                       CharClass.DIGIT, CharClass.HEX_DIGIT, CharClass.WHITESPACE,
                       CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.QUOT,
                       CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        // Entity references in DOCTYPE_INTERNAL_QUOTED_APOS (same pattern as ATTR_VALUE_APOS)
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
            .miniState(MiniState.SEEN_AMP)
                .on(CharClass.HASH).to(MiniState.SEEN_AMP_HASH).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_ENTITY_NAME).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
            .miniState(MiniState.SEEN_AMP_HASH)
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_DEC).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.SEEN_AMP_HASH_X).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
            .miniState(MiniState.SEEN_AMP_HASH_X)
                .on(CharClass.HEX_DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
            .miniState(MiniState.ACCUMULATING_ENTITY_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_ENTITY_NAME).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.GENERALENTITYREF)
                    .to(MiniState.READY).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
            .miniState(MiniState.ACCUMULATING_CHAR_REF_DEC)
                .on(CharClass.DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_DEC).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.CDATA)
                    .to(MiniState.READY).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
            .miniState(MiniState.ACCUMULATING_CHAR_REF_HEX)
                .on(CharClass.HEX_DIGIT).to(MiniState.ACCUMULATING_CHAR_REF_HEX).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.CDATA)
                    .to(MiniState.READY).done();
        
        // Parameter entity references in DOCTYPE_INTERNAL_QUOTED_APOS
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
            .miniState(MiniState.SEEN_PERCENT)
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_PARAM_ENTITY_NAME).done()
                .onAny(CharClass.NAME_CHAR, CharClass.CHAR_DATA, CharClass.DIGIT, CharClass.HEX_DIGIT,
                       CharClass.WHITESPACE, CharClass.LT, CharClass.GT, CharClass.AMP, CharClass.APOS,
                       CharClass.QUOT, CharClass.EQ, CharClass.SEMICOLON, CharClass.HASH,
                       CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS,
                       CharClass.DASH, CharClass.BANG, CharClass.QUERY, CharClass.SLASH,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET)
                    // Not a param entity ref - treat % as literal and continue with this char
                    .to(MiniState.ACCUMULATING_CDATA).done();
        
        builder.state(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
            .miniState(MiniState.ACCUMULATING_PARAM_ENTITY_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_PARAM_ENTITY_NAME).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.PARAMETERENTITYREF)
                    .to(MiniState.READY).done();
        
        // ===== TokenizerState.DOCTYPE_INTERNAL Transitions =====
        // (Inside DOCTYPE internal subset: [ ... ])
        
        // DOCTYPE_INTERNAL:READY - Waiting for markup declarations or end
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.READY)
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.LT).to(MiniState.SEEN_LT).done()
                .on(CharClass.PERCENT).to(MiniState.SEEN_PERCENT).done()
                .on(CharClass.CLOSE_BRACKET)
                    .emit(Token.CLOSE_BRACKET)
                    .changeState(TokenizerState.DOCTYPE)
                    .to(MiniState.READY).done()
                .on(CharClass.OPEN_PAREN)
                    .emit(Token.OPEN_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.CLOSE_PAREN)
                    .emit(Token.CLOSE_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.PIPE)
                    .emit(Token.PIPE)
                    .to(MiniState.READY).done()
                .on(CharClass.GT)
                    .emit(Token.GT)
                    .to(MiniState.READY).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_NAME).done()
                .onAny(CharClass.DIGIT, CharClass.DASH, CharClass.COLON, CharClass.NAME_CHAR)
                    // NMTOKEN can start with digit, dash, colon, or other name chars like period (unlike NAME)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // DOCTYPE_INTERNAL:SEEN_LT - After '<'
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.SEEN_LT)
                .on(CharClass.BANG).to(MiniState.SEEN_LT_BANG).done()
                .on(CharClass.QUERY).to(MiniState.SEEN_LT_QUERY).done();
        
        // DOCTYPE_INTERNAL:SEEN_LT_QUERY - After '<?' (PI in DTD)
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.SEEN_LT_QUERY)
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.START_PI)
                    .changeState(TokenizerState.PI_TARGET)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // DOCTYPE_INTERNAL:SEEN_LT_BANG - After '<!' (markup declaration or comment)
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.SEEN_LT_BANG)
                .on(CharClass.DASH).to(MiniState.SEEN_LT_BANG_DASH).done()
                .on(CharClass.OPEN_BRACKET).to(MiniState.SEEN_LT_BANG_OPEN_BRACKET).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.SEEN_LT_BANG_LETTER).done();
        
        // DOCTYPE_INTERNAL:SEEN_LT_BANG_DASH - After '<!-'
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.SEEN_LT_BANG_DASH)
                .on(CharClass.DASH)
                    .emit(Token.START_COMMENT)
                    .changeState(TokenizerState.COMMENT)
                    .to(MiniState.READY).done();
        
        // DOCTYPE_INTERNAL:SEEN_LT_BANG_OPEN_BRACKET - After '<!['  (conditional section)
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.SEEN_LT_BANG_OPEN_BRACKET)
                .on(CharClass.WHITESPACE)
                    .emit(Token.START_CONDITIONAL)
                    .changeState(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.PERCENT)
                    .emit(Token.START_CONDITIONAL)
                    .changeState(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
                    .to(MiniState.READY).done()
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.START_CONDITIONAL)
                    .changeState(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // DOCTYPE_INTERNAL:SEEN_LT_BANG_LETTER - After '<!X' where X is a letter
        // Check for ELEMENT, ATTLIST, ENTITY, NOTATION
        // We'll use special handling in the tokenizer to check the first letter and emit appropriate tokens
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.SEEN_LT_BANG_LETTER)
                .on(CharClass.NAME_START_CHAR)
                    .to(MiniState.ACCUMULATING_MARKUP_NAME).done()
                .on(CharClass.NAME_CHAR)
                    .to(MiniState.ACCUMULATING_MARKUP_NAME).done();
        
        // DOCTYPE_INTERNAL:ACCUMULATING_MARKUP_NAME - Collecting markup declaration name
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.ACCUMULATING_MARKUP_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_MARKUP_NAME).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.NAME)  // Will be converted to START_ELEMENTDECL etc in emitTokenWindow
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.PERCENT)
                    .emit(Token.NAME)  // Emit markup name (e.g., "ATTLIST")
                    .to(MiniState.SEEN_PERCENT).done();  // Check if %name; follows (will expand with spaces)
        
        // DOCTYPE_INTERNAL:ACCUMULATING_NAME - Generic name accumulation
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.ACCUMULATING_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.NAME)  // May be converted to keyword
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.GT)
                    .emit(Token.NAME)
                    .emit(Token.GT)
                    .to(MiniState.READY).done()
                .on(CharClass.APOS)
                    .emit(Token.NAME)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUOT)
                    .emit(Token.NAME)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
                    .to(MiniState.READY).done()
                .on(CharClass.OPEN_PAREN)
                    .emit(Token.NAME)
                    .emit(Token.OPEN_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.CLOSE_PAREN)
                    .emit(Token.NAME)
                    .emit(Token.CLOSE_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.PIPE)
                    .emit(Token.NAME)
                    .emit(Token.PIPE)
                    .to(MiniState.READY).done()
                .on(CharClass.COMMA)
                    .emit(Token.NAME)
                    .emit(Token.COMMA)
                    .to(MiniState.READY).done()
                .on(CharClass.STAR)
                    .emit(Token.NAME)
                    .emit(Token.STAR)
                    .to(MiniState.READY).done()
                .on(CharClass.PLUS)
                    .emit(Token.NAME)
                    .emit(Token.PLUS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUERY)
                    .emit(Token.NAME)
                    .emit(Token.QUERY)
                    .to(MiniState.READY).done()
                .on(CharClass.HASH)
                    .emit(Token.NAME)
                    .emit(Token.HASH)
                    .to(MiniState.READY).done()
                .on(CharClass.PERCENT)
                    .emit(Token.NAME)
                    .to(MiniState.SEEN_PERCENT).done();
        
        // DOCTYPE_INTERNAL:SEEN_PERCENT - After '%' (parameter entity reference or delimiter)
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.SEEN_PERCENT)
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_PARAM_ENTITY_NAME).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.PERCENT)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.NAME_CHAR)
                    .emit(Token.PERCENT)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.LT)
                    .emit(Token.PERCENT)
                    .to(MiniState.SEEN_LT).done()
                .on(CharClass.GT)
                    .emit(Token.PERCENT)
                    .emit(Token.GT)
                    .to(MiniState.READY).done()
                .on(CharClass.APOS)
                    .emit(Token.PERCENT)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUOT)
                    .emit(Token.PERCENT)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
                    .to(MiniState.READY).done();
        
        // DOCTYPE_INTERNAL:ACCUMULATING_PARAM_ENTITY_NAME
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.ACCUMULATING_PARAM_ENTITY_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_PARAM_ENTITY_NAME).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.PARAMETERENTITYREF)
                    .to(MiniState.READY).done();
        
        // DOCTYPE_INTERNAL: Handle other punctuation in READY state
        builder.state(TokenizerState.DOCTYPE_INTERNAL)
            .miniState(MiniState.READY)
                .on(CharClass.GT)
                    .emit(Token.GT)
                    .to(MiniState.READY).done()
                .on(CharClass.OPEN_PAREN)
                    .emit(Token.OPEN_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.CLOSE_PAREN)
                    .emit(Token.CLOSE_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.PIPE)
                    .emit(Token.PIPE)
                    .to(MiniState.READY).done()
                .on(CharClass.COMMA)
                    .emit(Token.COMMA)
                    .to(MiniState.READY).done()
                .on(CharClass.STAR)
                    .emit(Token.STAR)
                    .to(MiniState.READY).done()
                .on(CharClass.PLUS)
                    .emit(Token.PLUS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUERY)
                    .emit(Token.QUERY)
                    .to(MiniState.READY).done()
                .on(CharClass.HASH)
                    .emit(Token.HASH)
                    .to(MiniState.READY).done()
                .on(CharClass.APOS)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUOT)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
                    .to(MiniState.READY).done()
                .on(CharClass.NAME_START_CHAR)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // ==================== CONDITIONAL SECTIONS ====================
        
        // CONDITIONAL_SECTION_KEYWORD:READY - Waiting for the first character of keyword, '%', or '['
        builder.state(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
            .miniState(MiniState.READY)
                .on(CharClass.WHITESPACE)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.OPEN_BRACKET)
                    .emit(Token.OPEN_BRACKET)
                    .to(MiniState.READY).done()
                .on(CharClass.PERCENT)
                    .to(MiniState.SEEN_PERCENT).done()
                .on(CharClass.NAME_START_CHAR)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // CONDITIONAL_SECTION_KEYWORD:ACCUMULATING_NAME - Collecting INCLUDE or IGNORE
        builder.state(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
            .miniState(MiniState.ACCUMULATING_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.OPEN_BRACKET)
                    .emit(Token.NAME)  // Will be converted to INCLUDE or IGNORE
                    .emit(Token.OPEN_BRACKET)
                    .to(MiniState.READY).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.NAME)  // Will be converted to INCLUDE or IGNORE
                    .to(MiniState.ACCUMULATING_WHITESPACE).done();
        
        // CONDITIONAL_SECTION_KEYWORD:ACCUMULATING_WHITESPACE - After keyword, before '['
        builder.state(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
            .miniState(MiniState.ACCUMULATING_WHITESPACE)
                .on(CharClass.WHITESPACE)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.S)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.PERCENT)
                    .emit(Token.S)
                    .to(MiniState.SEEN_PERCENT).done()
                .on(CharClass.OPEN_BRACKET)
                    .emit(Token.S)
                    .emit(Token.OPEN_BRACKET)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_KEYWORD:SEEN_PERCENT - After '%' (parameter entity ref)
        builder.state(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
            .miniState(MiniState.SEEN_PERCENT)
                .on(CharClass.NAME_START_CHAR)
                    .to(MiniState.ACCUMULATING_PARAM_ENTITY_NAME).done();
        
        // CONDITIONAL_SECTION_KEYWORD:ACCUMULATING_PARAM_ENTITY_NAME
        builder.state(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
            .miniState(MiniState.ACCUMULATING_PARAM_ENTITY_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_PARAM_ENTITY_NAME).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.PARAMETERENTITYREF)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_INCLUDE:READY - Processing content as DTD
        // Same as DOCTYPE_INTERNAL, but looking for ]]> terminator
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.READY)
                .on(CharClass.WHITESPACE).to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.LT).to(MiniState.SEEN_LT).done()
                .on(CharClass.PERCENT).to(MiniState.SEEN_PERCENT).done()
                .on(CharClass.CLOSE_BRACKET).to(MiniState.SEEN_CLOSE_BRACKET).done()
                .on(CharClass.OPEN_PAREN)
                    .emit(Token.OPEN_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.CLOSE_PAREN)
                    .emit(Token.CLOSE_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.PIPE)
                    .emit(Token.PIPE)
                    .to(MiniState.READY).done()
                .on(CharClass.GT)
                    .emit(Token.GT)
                    .to(MiniState.READY).done()
                .on(CharClass.COMMA)
                    .emit(Token.COMMA)
                    .to(MiniState.READY).done()
                .on(CharClass.STAR)
                    .emit(Token.STAR)
                    .to(MiniState.READY).done()
                .on(CharClass.PLUS)
                    .emit(Token.PLUS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUERY)
                    .emit(Token.QUERY)
                    .to(MiniState.READY).done()
                .on(CharClass.HASH)
                    .emit(Token.HASH)
                    .to(MiniState.READY).done()
                .on(CharClass.QUOT)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
                    .to(MiniState.READY).done()
                .on(CharClass.APOS)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
                    .to(MiniState.READY).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.ACCUMULATING_NAME).done()
                .onAny(CharClass.DIGIT, CharClass.DASH, CharClass.COLON)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // CONDITIONAL_SECTION_INCLUDE:SEEN_CLOSE_BRACKET - After ']'
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.SEEN_CLOSE_BRACKET)
                .on(CharClass.CLOSE_BRACKET).to(MiniState.SEEN_CLOSE_BRACKET_CLOSE_BRACKET).done();
        
        // CONDITIONAL_SECTION_INCLUDE:SEEN_CLOSE_BRACKET_CLOSE_BRACKET - After ']]'
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.SEEN_CLOSE_BRACKET_CLOSE_BRACKET)
                .on(CharClass.GT)
                    .emit(Token.END_CDATA)
                    // Don't change state here - Tokenizer manages state via conditionalStateStack
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_INCLUDE:SEEN_LT - After '<'
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.SEEN_LT)
                .on(CharClass.BANG).to(MiniState.SEEN_LT_BANG).done()
                .on(CharClass.QUERY).to(MiniState.SEEN_LT_QUERY).done();
        
        // CONDITIONAL_SECTION_INCLUDE:SEEN_LT_QUERY - After '<?' (PI in conditional)
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.SEEN_LT_QUERY)
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.START_PI)
                    .changeState(TokenizerState.PI_TARGET)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // CONDITIONAL_SECTION_INCLUDE:SEEN_LT_BANG - After '<!' (markup or comment)
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.SEEN_LT_BANG)
                .on(CharClass.DASH).to(MiniState.SEEN_LT_BANG_DASH).done()
                .on(CharClass.OPEN_BRACKET).to(MiniState.SEEN_LT_BANG_OPEN_BRACKET).done()
                .on(CharClass.NAME_START_CHAR).to(MiniState.SEEN_LT_BANG_LETTER).done();
        
        // CONDITIONAL_SECTION_INCLUDE:SEEN_LT_BANG_DASH - After '<!-'
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.SEEN_LT_BANG_DASH)
                .on(CharClass.DASH)
                    .emit(Token.START_COMMENT)
                    .changeState(TokenizerState.COMMENT)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_INCLUDE:SEEN_LT_BANG_OPEN_BRACKET - After '<!['  (nested conditional)
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.SEEN_LT_BANG_OPEN_BRACKET)
                .on(CharClass.WHITESPACE)
                    .emit(Token.START_CONDITIONAL)
                    .changeState(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.PERCENT)
                    .emit(Token.START_CONDITIONAL)
                    .changeState(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
                    .to(MiniState.READY).done()
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.START_CONDITIONAL)
                    .changeState(TokenizerState.CONDITIONAL_SECTION_KEYWORD)
                    .to(MiniState.ACCUMULATING_NAME).done();
        
        // CONDITIONAL_SECTION_INCLUDE:SEEN_LT_BANG_LETTER - After '<!X'
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.SEEN_LT_BANG_LETTER)
                .on(CharClass.NAME_START_CHAR)
                    .to(MiniState.ACCUMULATING_MARKUP_NAME).done()
                .on(CharClass.NAME_CHAR)
                    .to(MiniState.ACCUMULATING_MARKUP_NAME).done();
        
        // CONDITIONAL_SECTION_INCLUDE:ACCUMULATING_MARKUP_NAME - Markup declaration name
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.ACCUMULATING_MARKUP_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_MARKUP_NAME).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.NAME)  // Will be converted to START_ELEMENTDECL etc
                    .to(MiniState.ACCUMULATING_WHITESPACE).done();
        
        // CONDITIONAL_SECTION_INCLUDE:ACCUMULATING_NAME - Generic name
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.ACCUMULATING_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.NAME)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.GT)
                    .emit(Token.NAME)
                    .emit(Token.GT)
                    .to(MiniState.READY).done()
                .on(CharClass.OPEN_PAREN)
                    .emit(Token.NAME)
                    .emit(Token.OPEN_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.CLOSE_PAREN)
                    .emit(Token.NAME)
                    .emit(Token.CLOSE_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.PIPE)
                    .emit(Token.NAME)
                    .emit(Token.PIPE)
                    .to(MiniState.READY).done()
                .on(CharClass.COMMA)
                    .emit(Token.NAME)
                    .emit(Token.COMMA)
                    .to(MiniState.READY).done()
                .on(CharClass.STAR)
                    .emit(Token.NAME)
                    .emit(Token.STAR)
                    .to(MiniState.READY).done()
                .on(CharClass.PLUS)
                    .emit(Token.NAME)
                    .emit(Token.PLUS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUERY)
                    .emit(Token.NAME)
                    .emit(Token.QUERY)
                    .to(MiniState.READY).done()
                .on(CharClass.HASH)
                    .emit(Token.NAME)
                    .emit(Token.HASH)
                    .to(MiniState.READY).done()
                .on(CharClass.APOS)
                    .emit(Token.NAME)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUOT)
                    .emit(Token.NAME)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_INCLUDE:ACCUMULATING_WHITESPACE
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.ACCUMULATING_WHITESPACE)
                .on(CharClass.WHITESPACE)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.LT)
                    .emit(Token.S)
                    .to(MiniState.SEEN_LT).done()
                .on(CharClass.GT)
                    .emit(Token.S)
                    .emit(Token.GT)
                    .to(MiniState.READY).done()
                .on(CharClass.NAME_START_CHAR)
                    .emit(Token.S)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.PERCENT)
                    .emit(Token.S)
                    .to(MiniState.SEEN_PERCENT).done()
                .on(CharClass.CLOSE_BRACKET)
                    .emit(Token.S)
                    .to(MiniState.SEEN_CLOSE_BRACKET).done()
                .on(CharClass.OPEN_PAREN)
                    .emit(Token.S)
                    .emit(Token.OPEN_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.CLOSE_PAREN)
                    .emit(Token.S)
                    .emit(Token.CLOSE_PAREN)
                    .to(MiniState.READY).done()
                .on(CharClass.PIPE)
                    .emit(Token.S)
                    .emit(Token.PIPE)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_INCLUDE:SEEN_PERCENT - After '%' (parameter entity ref or delimiter)
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.SEEN_PERCENT)
                .on(CharClass.NAME_START_CHAR)
                    .to(MiniState.ACCUMULATING_PARAM_ENTITY_NAME).done()
                .on(CharClass.WHITESPACE)
                    .emit(Token.PERCENT)
                    .to(MiniState.ACCUMULATING_WHITESPACE).done()
                .on(CharClass.NAME_CHAR)
                    .emit(Token.PERCENT)
                    .to(MiniState.ACCUMULATING_NAME).done()
                .on(CharClass.LT)
                    .emit(Token.PERCENT)
                    .to(MiniState.SEEN_LT).done()
                .on(CharClass.GT)
                    .emit(Token.PERCENT)
                    .emit(Token.GT)
                    .to(MiniState.READY).done()
                .on(CharClass.APOS)
                    .emit(Token.PERCENT)
                    .emit(Token.APOS)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_APOS)
                    .to(MiniState.READY).done()
                .on(CharClass.QUOT)
                    .emit(Token.PERCENT)
                    .emit(Token.QUOT)
                    .changeState(TokenizerState.DOCTYPE_INTERNAL_QUOTED_QUOT)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_INCLUDE:ACCUMULATING_PARAM_ENTITY_NAME
        builder.state(TokenizerState.CONDITIONAL_SECTION_INCLUDE)
            .miniState(MiniState.ACCUMULATING_PARAM_ENTITY_NAME)
                .onAny(CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, CharClass.DIGIT)
                    .to(MiniState.ACCUMULATING_PARAM_ENTITY_NAME).done()
                .on(CharClass.SEMICOLON)
                    .emit(Token.PARAMETERENTITYREF)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_IGNORE:READY - Skipping content, looking for ]]> or nested <![
        builder.state(TokenizerState.CONDITIONAL_SECTION_IGNORE)
            .miniState(MiniState.READY)
                .on(CharClass.LT).to(MiniState.SEEN_LT).done()
                .on(CharClass.CLOSE_BRACKET).to(MiniState.SEEN_CLOSE_BRACKET).done()
                .onAny(CharClass.WHITESPACE, CharClass.GT, CharClass.AMP, 
                       CharClass.QUOT, CharClass.APOS, CharClass.EQ, CharClass.SEMICOLON,
                       CharClass.HASH, CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.QUERY,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.SLASH, CharClass.BANG,
                       CharClass.DASH, CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, 
                       CharClass.DIGIT, CharClass.CHAR_DATA)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_IGNORE:SEEN_LT - After '<' in IGNORE section
        builder.state(TokenizerState.CONDITIONAL_SECTION_IGNORE)
            .miniState(MiniState.SEEN_LT)
                .on(CharClass.BANG).to(MiniState.SEEN_LT_BANG).done()
                .onAny(CharClass.WHITESPACE, CharClass.LT, CharClass.GT, CharClass.AMP, 
                       CharClass.QUOT, CharClass.APOS, CharClass.EQ, CharClass.SEMICOLON,
                       CharClass.HASH, CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.QUERY,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET, 
                       CharClass.SLASH, CharClass.DASH, CharClass.NAME_START_CHAR, 
                       CharClass.NAME_CHAR, CharClass.DIGIT, CharClass.CHAR_DATA)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_IGNORE:SEEN_LT_BANG - After '<!' in IGNORE section
        builder.state(TokenizerState.CONDITIONAL_SECTION_IGNORE)
            .miniState(MiniState.SEEN_LT_BANG)
                .on(CharClass.OPEN_BRACKET)
                    .emit(Token.START_CONDITIONAL)  // Emit nested <![
                    .to(MiniState.READY).done()
                .onAny(CharClass.WHITESPACE, CharClass.LT, CharClass.GT, CharClass.AMP, 
                       CharClass.QUOT, CharClass.APOS, CharClass.EQ, CharClass.SEMICOLON,
                       CharClass.HASH, CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.QUERY,
                       CharClass.PERCENT, CharClass.CLOSE_BRACKET, CharClass.SLASH, CharClass.BANG,
                       CharClass.DASH, CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, 
                       CharClass.DIGIT, CharClass.CHAR_DATA)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_IGNORE:SEEN_CLOSE_BRACKET - After ']'
        builder.state(TokenizerState.CONDITIONAL_SECTION_IGNORE)
            .miniState(MiniState.SEEN_CLOSE_BRACKET)
                .on(CharClass.CLOSE_BRACKET).to(MiniState.SEEN_CLOSE_BRACKET_CLOSE_BRACKET).done()
                .onAny(CharClass.WHITESPACE, CharClass.LT, CharClass.GT, CharClass.AMP, 
                       CharClass.QUOT, CharClass.APOS, CharClass.EQ, CharClass.SEMICOLON,
                       CharClass.HASH, CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.QUERY,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.SLASH, CharClass.BANG,
                       CharClass.DASH, CharClass.NAME_START_CHAR, CharClass.NAME_CHAR, 
                       CharClass.DIGIT, CharClass.CHAR_DATA)
                    .to(MiniState.READY).done();
        
        // CONDITIONAL_SECTION_IGNORE:SEEN_CLOSE_BRACKET_CLOSE_BRACKET - After ']]'
        builder.state(TokenizerState.CONDITIONAL_SECTION_IGNORE)
            .miniState(MiniState.SEEN_CLOSE_BRACKET_CLOSE_BRACKET)
                .on(CharClass.GT)
                    .emit(Token.END_CDATA)
                    // Don't change state here - Tokenizer manages state via conditionalStateStack
                    .to(MiniState.READY).done()
                .onAny(CharClass.WHITESPACE, CharClass.LT, CharClass.AMP, 
                       CharClass.QUOT, CharClass.APOS, CharClass.EQ, CharClass.SEMICOLON,
                       CharClass.HASH, CharClass.COLON, CharClass.OPEN_PAREN, CharClass.CLOSE_PAREN,
                       CharClass.PIPE, CharClass.COMMA, CharClass.STAR, CharClass.PLUS, CharClass.QUERY,
                       CharClass.PERCENT, CharClass.OPEN_BRACKET, CharClass.CLOSE_BRACKET, 
                       CharClass.SLASH, CharClass.BANG, CharClass.DASH, CharClass.NAME_START_CHAR, 
                       CharClass.NAME_CHAR, CharClass.DIGIT, CharClass.CHAR_DATA)
                    .to(MiniState.READY).done();
        
        // ===== End of transitions =====
        
        TRANSITION_TABLE = builder.build();
        
        // Build optimized flat array lookup table
        FLAT_TRANSITION_TABLE = builder.buildFlatTable();
    }
    
    // ===== Instance fields and methods for builder pattern =====
    
    private final Map<TokenizerState, Map<MiniState, Map<CharClass, Transition>>> table;
    private TokenizerState currentState;
    private MiniState currentMiniState;
    
    /**
     * Creates a new transition table builder.
     */
    MiniStateTransitionBuilder() {
        this.table = new EnumMap<>(TokenizerState.class);
    }
    
    /**
     * Begins defining transitions for a specific State.
     * 
     * @param state the state to configure
     * @return a builder for this state
     */
    StateBuilder state(TokenizerState state) {
        this.currentState = state;
        if (!table.containsKey(state)) {
            table.put(state, new EnumMap<>(MiniState.class));
        }
        return new StateBuilder();
    }
    
    /**
     * Builds and returns the complete transition table.
     * 
     * @return the transition table mapping (State, MiniState, CharClass) to Transition
     */
    Map<TokenizerState, Map<MiniState, Map<CharClass, Transition>>> build() {
        return table;
    }
    
    /**
     * Builds an optimized flat array lookup table.
     * For each TokenizerState, creates a flattened array where:
     * index = miniState.ordinal() * NUM_CHAR_CLASSES + charClass.ordinal()
     * 
     * This eliminates two levels of map lookups per character.
     * 
     * @return the flat transition table
     */
    EnumMap<TokenizerState, Transition[]> buildFlatTable() {
        EnumMap<TokenizerState, Transition[]> flatTable = new EnumMap<>(TokenizerState.class);
        
        for (Map.Entry<TokenizerState, Map<MiniState, Map<CharClass, Transition>>> stateEntry : table.entrySet()) {
            TokenizerState state = stateEntry.getKey();
            Map<MiniState, Map<CharClass, Transition>> miniStateMap = stateEntry.getValue();
            
            // Create flat array for this state
            // Size: NUM_MINI_STATES * NUM_CHAR_CLASSES
            Transition[] flatArray = new Transition[NUM_MINI_STATES * NUM_CHAR_CLASSES];
            
            // Populate the flat array
            for (Map.Entry<MiniState, Map<CharClass, Transition>> miniEntry : miniStateMap.entrySet()) {
                MiniState miniState = miniEntry.getKey();
                Map<CharClass, Transition> charClassMap = miniEntry.getValue();
                
                int miniStateOrdinal = miniState.ordinal();
                
                for (Map.Entry<CharClass, Transition> charEntry : charClassMap.entrySet()) {
                    CharClass charClass = charEntry.getKey();
                    Transition transition = charEntry.getValue();
                    
                    int index = miniStateOrdinal * NUM_CHAR_CLASSES + charClass.ordinal();
                    flatArray[index] = transition;
                }
            }
            
            flatTable.put(state, flatArray);
        }
        
        return flatTable;
    }
    
    /**
     * Builder for transitions within a specific State.
     */
    class StateBuilder {
        /**
         * Begins defining transitions for a specific MiniState within the current State.
         * 
         * @param miniState the mini-state to configure
         * @return a builder for this mini-state
         */
        MiniStateBuilder miniState(MiniState miniState) {
            currentMiniState = miniState;
            Map<MiniState, Map<CharClass, Transition>> stateMap = table.get(currentState);
            if (!stateMap.containsKey(miniState)) {
                stateMap.put(miniState, new EnumMap<>(CharClass.class));
            }
            return new MiniStateBuilder();
        }
    }
    
    /**
     * Builder for transitions within a specific MiniState.
     */
    class MiniStateBuilder {
        /**
         * Begins defining a transition for a specific CharClass.
         * 
         * @param charClass the character class that triggers this transition
         * @return a builder for this transition
         */
        TransitionBuilder on(CharClass charClass) {
            return new TransitionBuilder(charClass);
        }
        
        /**
         * Begins defining a transition for any of several CharClasses.
         * All specified character classes will map to the same transition.
         * 
         * @param charClasses the character classes that trigger this transition
         * @return a builder for this transition
         */
        TransitionBuilder onAny(CharClass... charClasses) {
            return new TransitionBuilder(charClasses);
        }
        
        /**
         * Returns to the StateBuilder to define another mini-state.
         * 
         * @return the StateBuilder for the current state
         */
        StateBuilder endMiniState() {
            return new StateBuilder();
        }
    }
    
    /**
     * Builder for a specific transition.
     */
    class TransitionBuilder {
        private final CharClass[] charClasses;
        private MiniState nextMiniState;
        private List<Token> tokensToEmit = new ArrayList<>();  // Changed to list
        private TokenizerState stateToChangeTo;
        private String sequenceToConsume;
        
        TransitionBuilder(CharClass... charClasses) {
            this.charClasses = charClasses;
        }
        
        /**
         * Specifies the next MiniState to transition to.
         * 
         * @param nextMiniState the next mini-state
         * @return this builder
         */
        TransitionBuilder to(MiniState nextMiniState) {
            this.nextMiniState = nextMiniState;
            return this;
        }
        
        /**
         * Specifies a token to emit during this transition.
         * Can be called multiple times to emit multiple tokens in sequence.
         * 
         * @param token the token to emit
         * @return this builder
         */
        TransitionBuilder emit(Token token) {
            this.tokensToEmit.add(token);
            return this;
        }
        
        /**
         * Specifies a State change during this transition.
         * 
         * @param state the new state
         * @return this builder
         */
        TransitionBuilder changeState(TokenizerState state) {
            this.stateToChangeTo = state;
            return this;
        }
        
        /**
         * Specifies a character sequence that must be consumed during this transition.
         * If the sequence is not present or incomplete, the tokenizer will underflow.
         * 
         * @param sequence the exact character sequence to consume (e.g., "CDATA[")
         * @return this builder
         */
        TransitionBuilder consumeSequence(String sequence) {
            this.sequenceToConsume = sequence;
            return this;
        }
        
        /**
         * Completes this transition definition and adds it to the table.
         * 
         * @return the MiniStateBuilder to continue defining transitions
         */
        MiniStateBuilder done() {
            Transition transition = new Transition(
                nextMiniState,
                tokensToEmit.isEmpty() ? null : new ArrayList<>(tokensToEmit),
                stateToChangeTo,
                sequenceToConsume
            );
            
            Map<CharClass, Transition> miniStateMap = 
                table.get(currentState).get(currentMiniState);
            
            for (CharClass cc : charClasses) {
                miniStateMap.put(cc, transition);
            }
            
            return new MiniStateBuilder();
        }
    }
    
    /**
     * Represents a single state transition in the tokenizer.
     */
    static class Transition {
        final MiniState nextMiniState;
        final List<Token> tokensToEmit;  // Changed from single token to list
        final TokenizerState stateToChangeTo;
        final String sequenceToConsume;
        
        Transition(MiniState nextMiniState, List<Token> tokensToEmit, 
                   TokenizerState stateToChangeTo, String sequenceToConsume) {
            this.nextMiniState = nextMiniState;
            this.tokensToEmit = tokensToEmit != null ? tokensToEmit : Collections.emptyList();
            this.stateToChangeTo = stateToChangeTo;
            this.sequenceToConsume = sequenceToConsume;
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder();
            if (nextMiniState != null) {
                buf.append("nextMiniState=").append(nextMiniState);
            }
            if (tokensToEmit != null) {
                if (buf.length() > 0) {
                    buf.append(',');
                }
                buf.append("tokensToEmit=").append(tokensToEmit);
            }
            if (stateToChangeTo != null) {
                if (buf.length() > 0) {
                    buf.append(',');
                }
                buf.append("stateToChangeTo=").append(stateToChangeTo);
            }
            return buf.toString();
        }

    }
}

