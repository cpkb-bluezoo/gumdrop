/*
 * MiniState.java
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

/**
 * Fine-grained tokenizer state indicating progress through recognizing a token.
 * <p>
 * MiniStates represent positions in the state trie used to recognize XML tokens.
 * Each MiniState combined with a State and an input CharClass determines the
 * next MiniState and any tokens to emit.
 * <p>
 * Some mini-states are "greedy accumulation" states that consume characters
 * until a delimiter is found, and flush their accumulated content when the
 * buffer is exhausted.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
enum MiniState {
    
    /**
     * Ready to start recognizing a new token.
     */
    READY,
    
    // --- Content/element start sequences ---
    
    /**
     * Seen '&lt;' in a context where it could start various constructs.
     */
    SEEN_LT,
    
    /**
     * Seen '&lt;/' (start of end tag).
     */
    SEEN_LT_SLASH,
    
    /**
     * Seen '&lt;?' (possible start of PI or XML declaration).
     */
    SEEN_LT_QUERY,
    
    /**
     * Seen '&lt;?x'.
     */
    SEEN_LT_QUERY_X,
    
    /**
     * Seen '&lt;?xm'.
     */
    SEEN_LT_QUERY_XM,
    
    /**
     * Seen '&lt;?xml'.
     */
    SEEN_LT_QUERY_XML,
    
    /**
     * Seen '&lt;!' (possible start of comment, CDATA, DOCTYPE, or DTD declaration).
     */
    SEEN_LT_BANG,
    
    /**
     * Seen '&lt;!-' (partial comment start).
     */
    SEEN_LT_BANG_DASH,
    
    /**
     * Seen '&lt;!--' (complete comment start marker).
     */
    SEEN_LT_BANG_DASH_DASH,
    
    /**
     * Seen '&lt;![' (possible start of CDATA section or conditional section).
     */
    SEEN_LT_BANG_OPEN_BRACKET,
    
    /**
     * Seen '&lt;!D' (possible DOCTYPE).
     */
    SEEN_LT_BANG_D,
    
    /**
     * Seen '&lt;!E' (possible ELEMENT or ENTITY).
     */
    SEEN_LT_BANG_E,
    
    /**
     * Seen '&lt;!A' (possible ATTLIST).
     */
    SEEN_LT_BANG_A,
    
    /**
     * Seen '&lt;!N' (possible NOTATION).
     */
    SEEN_LT_BANG_N,
    
    /**
     * Seen '&lt;!' followed by any NAME_START_CHAR in DOCTYPE_INTERNAL.
     * Used to start accumulating markup declaration names.
     */
    SEEN_LT_BANG_LETTER,
    
    // --- Entity reference sequences ---
    
    /**
     * Seen '&amp;' (start of entity reference).
     */
    SEEN_AMP,
    
    /**
     * Seen '&amp;#' (start of character reference).
     */
    SEEN_AMP_HASH,
    
    /**
     * Seen '&amp;#x' (start of hexadecimal character reference).
     */
    SEEN_AMP_HASH_X,
    
    // --- Predefined entity reference sequences ---
    
    /** Seen '&amp;l' - could be &lt; */
    SEEN_AMP_L,
    
    /** Seen '&amp;lt' - recognized as &lt; predefined entity */
    SEEN_PREDEF_LT(false, null, 1),  // Index into "&<>'\""
    
    /** Seen '&amp;g' - could be &gt; */
    SEEN_AMP_G,
    
    /** Seen '&amp;gt' - recognized as &gt; predefined entity */
    SEEN_PREDEF_GT(false, null, 2),  // Index into "&<>'\""
    
    /** Seen '&amp;a' - could be &amp; or &apos; */
    SEEN_AMP_A,
    
    /** Seen '&amp;am' - could be &amp; */
    SEEN_AMP_A_M,
    
    /** Seen '&amp;amp' - recognized as &amp; predefined entity */
    SEEN_PREDEF_AMP(false, null, 0),  // Index into "&<>'\""
    
    /** Seen '&amp;ap' - could be &apos; */
    SEEN_AMP_A_P,
    
    /** Seen '&amp;apo' - could be &apos; */
    SEEN_AMP_A_P_O,
    
    /** Seen '&amp;apos' - recognized as &apos; predefined entity */
    SEEN_PREDEF_APOS(false, null, 3),  // Index into "&<>'\""
    
    /** Seen '&amp;q' - could be &quot; */
    SEEN_AMP_Q,
    
    /** Seen '&amp;qu' - could be &quot; */
    SEEN_AMP_Q_U,
    
    /** Seen '&amp;quo' - could be &quot; */
    SEEN_AMP_Q_U_O,
    
    /** Seen '&amp;quot' - recognized as &quot; predefined entity */
    SEEN_PREDEF_QUOT(false, null, 4),  // Index into "&<>'\""
    
    /**
     * Seen '%' in DTD context (start of parameter entity reference).
     */
    SEEN_PERCENT,
    
    // --- Greedy accumulation states (flush on buffer end) ---
    
    /**
     * Accumulating character data (CDATA token).
     * Greedy: consumes until delimiter, flushes on buffer end.
     */
    ACCUMULATING_CDATA(true, Token.CDATA),
    
    /**
     * Accumulating whitespace (S token).
     * Greedy: consumes until non-whitespace, flushes on buffer end.
     */
    ACCUMULATING_WHITESPACE(true, Token.S),
    
    // --- DOCTYPE keyword detection states ---
    
    /** Seen 'S' in DOCTYPE - could be SYSTEM */
    SEEN_KEYWORD_S,
    
    /** Seen 'SY' in DOCTYPE - checking for SYSTEM */
    SEEN_KEYWORD_SY,
    
    /** Seen 'SYS' in DOCTYPE - checking for SYSTEM */
    SEEN_KEYWORD_SYS,
    
    /** Seen 'SYST' in DOCTYPE - checking for SYSTEM */
    SEEN_KEYWORD_SYST,
    
    /** Seen 'SYSTE' in DOCTYPE - checking for SYSTEM */
    SEEN_KEYWORD_SYSTE,
    
    /** Seen 'P' in DOCTYPE - could be PUBLIC */
    SEEN_KEYWORD_P,
    
    /** Seen 'PU' in DOCTYPE - checking for PUBLIC */
    SEEN_KEYWORD_PU,
    
    /** Seen 'PUB' in DOCTYPE - checking for PUBLIC */
    SEEN_KEYWORD_PUB,
    
    /** Seen 'PUBL' in DOCTYPE - checking for PUBLIC */
    SEEN_KEYWORD_PUBL,
    
    /** Seen 'PUBLI' in DOCTYPE - checking for PUBLIC */
    SEEN_KEYWORD_PUBLI,
    
    // --- Delimited accumulation states (must see complete token) ---
    
    /**
     * Accumulating a NAME token (element name, attribute name, etc.).
     * Terminates on non-NameChar.
     */
    ACCUMULATING_NAME(false, Token.NAME),
    
    /**
     * Accumulating a markup declaration name (after '<!X' in DOCTYPE_INTERNAL).
     * Will be converted to START_ELEMENTDECL, START_ATTLISTDECL, etc.
     */
    ACCUMULATING_MARKUP_NAME(false, Token.NAME),
    
    /**
     * Accumulating general entity name (after '&amp;').
     * Terminates on ';'.
     */
    ACCUMULATING_ENTITY_NAME(false, Token.GENERALENTITYREF),
    
    /**
     * Accumulating parameter entity name (after '%' in DTD).
     * Terminates on ';'.
     */
    ACCUMULATING_PARAM_ENTITY_NAME(false, Token.PARAMETERENTITYREF),
    
    /**
     * Accumulating decimal character reference digits (after '&amp;#').
     * Terminates on ';'.
     */
    ACCUMULATING_CHAR_REF_DEC(false, null),  // No token - converted to CDATA
    
    /**
     * Accumulating hexadecimal character reference digits (after '&amp;#x').
     * Terminates on ';'.
     */
    ACCUMULATING_CHAR_REF_HEX(false, null),  // No token - converted to CDATA
    
    // --- Comment end sequences ---
    
    /**
     * Seen '-' in comment context (possible start of comment end).
     */
    SEEN_DASH,
    
    /**
     * Seen '--' in comment context (possible comment end).
     */
    SEEN_DASH_DASH,
    
    // --- Processing instruction end sequences ---
    
    /**
     * Seen '?' in PI context (possible PI end).
     */
    SEEN_QUERY,
    
    // --- XML declaration quote sequences ---
    
    /**
     * Seen opening apostrophe in XML declaration attribute value.
     */
    SEEN_APOS,
    
    /**
     * Seen opening quote in XML declaration attribute value.
     */
    SEEN_QUOT,
    
    // --- CDATA section end sequences ---
    
    /**
     * Seen ']' in CDATA section (possible start of CDATA end).
     */
    SEEN_CLOSE_BRACKET,
    
    /**
     * Seen ']]' in CDATA section (possible CDATA end).
     */
    SEEN_CLOSE_BRACKET_CLOSE_BRACKET,
    
    // --- Empty element sequences ---
    
    /**
     * Seen '/' in element context (possible empty element tag end).
     */
    SEEN_SLASH;
    
    private final boolean greedyAccumulation;
    private final Token tokenType;
    private final int predefinedEntityIndex;  // -1 if not a predefined entity, otherwise index into PREDEFINED_ENTITY_TEXT
    
    MiniState() {
        this(false, null, -1);
    }
    
    MiniState(boolean greedyAccumulation, Token tokenType) {
        this(greedyAccumulation, tokenType, -1);
    }
    
    MiniState(boolean greedyAccumulation, Token tokenType, int predefinedEntityIndex) {
        this.greedyAccumulation = greedyAccumulation;
        this.tokenType = tokenType;
        this.predefinedEntityIndex = predefinedEntityIndex;
    }
    
    /**
     * Returns true if this mini-state accumulates content greedily and should
     * flush on buffer end rather than waiting for more data.
     */
    boolean isGreedyAccumulation() {
        return greedyAccumulation;
    }
    
    /**
     * Returns the token type emitted by this accumulation state, or null if
     * this is not an accumulation state or the token is computed dynamically.
     */
    Token getTokenType() {
        return tokenType;
    }
    
    /**
     * Returns the index into PREDEFINED_ENTITY_TEXT buffer for this predefined entity,
     * or -1 if this is not a predefined entity state.
     */
    int getPredefinedEntityIndex() {
        return predefinedEntityIndex;
    }
    
    /**
     * Returns true if this is a predefined entity recognition state.
     */
    boolean isPredefinedEntity() {
        return predefinedEntityIndex >= 0;
    }
}

