/*
 * CharClass.java
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
 * Character classification for XML tokenization.
 * <p>
 * CharClass provides a reduced character space for the tokenizer state machine,
 * mapping ~1.1M Unicode codepoints into ~25 meaningful classes. This enables
 * efficient state transitions based on character type rather than individual
 * character values.
 * <p>
 * Classification is context-aware: some characters (like ':') have different
 * meanings depending on the current State.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
enum CharClass {
    
    /** '&lt;' - Less-than sign, starts tags and directives */
    LT,
    
    /** '&gt;' - Greater-than sign, ends tags */
    GT,
    
    /** '&amp;' - Ampersand, starts entity references */
    AMP,
    
    /** '\'' - Apostrophe, delimiter for attribute values and quoted strings */
    APOS,
    
    /** '"' - Quotation mark, delimiter for attribute values and quoted strings */
    QUOT,
    
    /** '!' - Exclamation mark, used in comments, CDATA, DOCTYPE, DTD declarations */
    BANG,
    
    /** '?' - Question mark, used in processing instructions and XML declaration */
    QUERY,
    
    /** '/' - Slash, used in end tags and empty element tags */
    SLASH,
    
    /** '=' - Equals sign, separates attribute names from values */
    EQ,
    
    /** ';' - Semicolon, terminates entity references */
    SEMICOLON,
    
    /** '%' - Percent sign, starts parameter entity references in DTD */
    PERCENT,
    
    /** '#' - Hash/pound sign, used in character references and DTD keywords */
    HASH,
    
    /** ':' - Colon, namespace separator (context-dependent) */
    COLON,
    
    /** 'a' - Letter a, used in predefined entity names (amp, apos) */
    LETTER_A,
    
    /** 'l' - Letter l, used in predefined entity name (lt) */
    LETTER_L,
    
    /** 'g' - Letter g, used in predefined entity name (gt) */
    LETTER_G,
    
    /** 'm' - Letter m, used in predefined entity name (amp) */
    LETTER_M,
    
    /** 'p' - Letter p, used in predefined entity names (amp, apos) */
    LETTER_P,
    
    /** 'o' - Letter o, used in predefined entity names (apos, quot) */
    LETTER_O,
    
    /** 's' - Letter s, used in predefined entity name (apos) */
    LETTER_S,
    
    /** 't' - Letter t, used in predefined entity names (lt, gt, quot) */
    LETTER_T,
    
    /** 'q' - Letter q, used in predefined entity name (quot) */
    LETTER_Q,
    
    /** 'u' - Letter u, used in predefined entity name (quot) */
    LETTER_U,
    
    /** 'x' - Letter x, used in hexadecimal character references (&#x...) */
    LETTER_X,
    
    /** '[' - Open bracket, used in CDATA sections and conditional sections */
    OPEN_BRACKET,
    
    /** ']' - Close bracket, ends CDATA sections and conditional sections */
    CLOSE_BRACKET,
    
    /** '(' - Open parenthesis, used in DTD content models */
    OPEN_PAREN,
    
    /** ')' - Close parenthesis, used in DTD content models */
    CLOSE_PAREN,
    
    /** '-' - Dash/hyphen, used in comments */
    DASH,
    
    /** '|' - Pipe, used in DTD content models (choice operator) */
    PIPE,
    
    /** ',' - Comma, used in DTD content models (sequence operator) */
    COMMA,
    
    /** '*' - Asterisk, occurrence indicator in DTD content models */
    STAR,
    
    /** '+' - Plus sign, occurrence indicator in DTD content models */
    PLUS,
    
    /** Whitespace: space, tab, line feed, carriage return */
    WHITESPACE,
    
    /** XML NameStartChar: letter, underscore, colon (except in namespace contexts) */
    NAME_START_CHAR,
    
    /** XML NameChar (but not NameStartChar): digit, dot, hyphen, etc. */
    NAME_CHAR,
    
    /** Decimal digit [0-9] */
    DIGIT,
    
    /** Hexadecimal digit [0-9A-Fa-f] */
    HEX_DIGIT,
    
    /** Legal XML character data not covered by above categories */
    CHAR_DATA,
    
    /** Illegal XML character (outside allowed Unicode ranges) */
    ILLEGAL;
    
    /**
     * Pre-computed character class lookup table for ASCII characters (0-127).
     * Provides O(1) classification for common characters.
     */
    private static final CharClass[] ASCII_LOOKUP = new CharClass[128];
    
    static {
        // Initialize all as ILLEGAL first
        for (int i = 0; i < 128; i++) {
            ASCII_LOOKUP[i] = ILLEGAL;
        }
        
        // Single-character mappings
        ASCII_LOOKUP['<'] = LT;
        ASCII_LOOKUP['>'] = GT;
        ASCII_LOOKUP['&'] = AMP;
        ASCII_LOOKUP['\''] = APOS;
        ASCII_LOOKUP['"'] = QUOT;
        ASCII_LOOKUP['!'] = BANG;
        ASCII_LOOKUP['?'] = QUERY;
        ASCII_LOOKUP['/'] = SLASH;
        ASCII_LOOKUP['='] = EQ;
        ASCII_LOOKUP[';'] = SEMICOLON;
        ASCII_LOOKUP['%'] = PERCENT;
        ASCII_LOOKUP['#'] = HASH;
        ASCII_LOOKUP[':'] = COLON;
        ASCII_LOOKUP['['] = OPEN_BRACKET;
        ASCII_LOOKUP[']'] = CLOSE_BRACKET;
        ASCII_LOOKUP['('] = OPEN_PAREN;
        ASCII_LOOKUP[')'] = CLOSE_PAREN;
        ASCII_LOOKUP['-'] = DASH;
        ASCII_LOOKUP['|'] = PIPE;
        ASCII_LOOKUP[','] = COMMA;
        ASCII_LOOKUP['*'] = STAR;
        ASCII_LOOKUP['+'] = PLUS;
        
        // Whitespace
        ASCII_LOOKUP[' '] = WHITESPACE;
        ASCII_LOOKUP['\t'] = WHITESPACE;
        ASCII_LOOKUP['\n'] = WHITESPACE;
        ASCII_LOOKUP['\r'] = WHITESPACE;
        
        // Digits (both DIGIT and HEX_DIGIT)
        for (char c = '0'; c <= '9'; c++) {
            ASCII_LOOKUP[c] = DIGIT;
        }
        
        // Hex digits A-F (already covered by NAME_START_CHAR for letters)
        // We'll handle this in classify() method
        
        // NAME_START_CHAR: A-Z, a-z, underscore
        for (char c = 'A'; c <= 'Z'; c++) {
            ASCII_LOOKUP[c] = NAME_START_CHAR;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            ASCII_LOOKUP[c] = NAME_START_CHAR;
        }
        ASCII_LOOKUP['_'] = NAME_START_CHAR;
        
        // Period is NAME_CHAR but not NAME_START_CHAR
        ASCII_LOOKUP['.'] = NAME_CHAR;
        
        // Other printable ASCII as CHAR_DATA
        for (int i = 32; i < 127; i++) {
            if (ASCII_LOOKUP[i] == ILLEGAL) {
                ASCII_LOOKUP[i] = CHAR_DATA;
            }
        }
    }
    
    /**
     * Classifies a character into a CharClass based on the current State and MiniState.
     * <p>
     * Classification is context-aware: some characters have different meanings
     * in different states. For example, specific letters in predefined entity reference
     * contexts are classified specially to enable trie-based recognition.
     * 
     * @param c the character to classify
     * @param state the current tokenizer state
     * @param miniState the current tokenizer mini-state
     * @param isXML11 whether processing as XML 1.1 (affects character validity)
     * @return the CharClass for this character in this context
     */
    static CharClass classify(char c, TokenizerState state, MiniState miniState, boolean isXML11) {
        return classify(c, state, miniState, isXML11, false);
    }
    
    /**
     * Classifies a character in context with RestrictedChar permission.
     * 
     * @param c the character to classify
     * @param state the current tokenizer state
     * @param miniState the current mini-state
     * @param isXML11 true if using XML 1.1 rules
     * @param allowRestrictedChar true to allow RestrictedChar in XML 1.1
     * @return the character class
     */
    static CharClass classify(char c, TokenizerState state, MiniState miniState, boolean isXML11, boolean allowRestrictedChar) {
        // Fast path for ASCII (covers 99%+ of typical XML content)
        if (c < 128) {
            CharClass base = ASCII_LOOKUP[c];

            // Fast path for WHITESPACE (very common, no context-dependent handling)
            if (base == WHITESPACE) {
                return WHITESPACE;
            }
            
            // Fast path for NAME_START_CHAR (letters a-z, A-Z, underscore)
            // Only need context check for hex digits in character reference context
            if (base == NAME_START_CHAR) {
                // Hex digit check only needed in very specific states
                if (miniState == MiniState.ACCUMULATING_CHAR_REF_HEX || miniState == MiniState.SEEN_AMP_HASH_X) {
                    if ((c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')) {
                        return HEX_DIGIT;
                    }
                }
                return NAME_START_CHAR;
            }
            
            // Fast path for DIGIT (0-9)
            if (base == DIGIT) {
                if (miniState == MiniState.ACCUMULATING_CHAR_REF_HEX || miniState == MiniState.SEEN_AMP_HASH_X) {
                    return HEX_DIGIT;
                }
                return DIGIT;
            }
            
            // COLON is always NAME_START_CHAR per XML 1.0 § 2.3
            if (base == COLON) {
                return NAME_START_CHAR;
            }
            
            // DASH needs context check for name accumulation
            if (base == DASH) {
                if (miniState == MiniState.ACCUMULATING_NAME ||
                    miniState == MiniState.ACCUMULATING_ENTITY_NAME ||
                    miniState == MiniState.ACCUMULATING_PARAM_ENTITY_NAME ||
                    miniState == MiniState.ACCUMULATING_MARKUP_NAME) {
                    return NAME_CHAR;
                }
                return DASH;
            }
            
            // ILLEGAL needs XML 1.1 check
            if (base == ILLEGAL) {
                if (isXML11 && allowRestrictedChar) {
                    if ((c >= 0x1 && c <= 0x8) || (c == 0xB) || (c == 0xC) || (c >= 0xE && c <= 0x1F)) {
                        return CHAR_DATA;
                    }
                }
                if (c == 0x7F && !isXML11) {
                    return CHAR_DATA;
                }
                return ILLEGAL;
            }
            
            // All other ASCII characters (punctuation, etc.) - return as-is
            return base;
        }
        
        // Unicode path (slower)
        return classifyUnicode(c, isXML11);
    }
    
    /**
     * Returns true if the mini-state is in a context where we're recognizing predefined entities.
     */
    private static boolean isPredefinedEntityContext(MiniState miniState) {
        switch (miniState) {
            case SEEN_AMP:
            case SEEN_AMP_HASH:  // For 'x' in &#x...
            case SEEN_AMP_L:
            case SEEN_AMP_G:
            case SEEN_AMP_A:
            case SEEN_AMP_A_M:
            case SEEN_AMP_A_P:
            case SEEN_AMP_A_P_O:
            case SEEN_AMP_Q:
            case SEEN_AMP_Q_U:
            case SEEN_AMP_Q_U_O:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Returns true if the mini-state is accumulating character reference digits.
     */
    private static boolean isCharRefDigitContext(MiniState miniState) {
        return miniState == MiniState.ACCUMULATING_CHAR_REF_HEX;
    }
    
    /**
     * Classifies a Unicode character (code point >= 128).
     * 
     * @param c the character to classify
     * @param isXML11 whether processing as XML 1.1
     * @return the CharClass for this character
     */
    private static CharClass classifyUnicode(char c, boolean isXML11) {
        // Check if legal XML character first
        if (!isLegalXMLChar(c, isXML11)) {
            return ILLEGAL;
        }
        
        // Check if NameStartChar
        if (isNameStartChar(c, isXML11)) {
            return NAME_START_CHAR;
        }
        
        // Check if NameChar (but not NameStartChar)
        if (isNameChar(c, isXML11)) {
            return NAME_CHAR;
        }
        
        // Otherwise it's just character data
        return CHAR_DATA;
    }
    
    /**
     * Checks if a character is a legal XML character.
     * 
     * XML 1.0: #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
     * XML 1.1: [#x1-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
     *          but excluding [#x7F-#x84], [#x86-#x9F] (C0/C1 controls except tab/LF/CR)
     * 
     * Note: Characters in the range #x10000-#x10FFFF are represented as UTF-16 surrogate pairs.
     * We accept surrogates (0xD800-0xDFFF) here because they're part of valid surrogate pairs.
     * The actual character validation (ensuring proper pairing) is done elsewhere.
     * 
     * @param c the character to check
     * @param isXML11 whether processing as XML 1.1
     * @return true if the character is legal
     */
    private static boolean isLegalXMLChar(char c, boolean isXML11) {
        if (isXML11) {
            // XML 1.1 allows more characters but excludes C0/C1 controls except specific ones
            // Allowed: [#x1-#xD7FF] | [#xE000-#xFFFD] except [#x7F-#x84], [#x86-#x9F]
            // Note: NEL (0x85) and LS (0x2028) ARE legal in XML 1.1, but they are normalized
            // to LF by the ExternalEntityDecoder, so by the time we classify them here they
            // should already be 0x0A. If we see them here, treat as regular characters.
            if (c >= 0x1 && c <= 0xD7FF) {
                // Exclude C0/C1 control range: [#x7F-#x84], [#x86-#x9F]
                // Excludes: DEL (0x7F), C1 controls (0x80-0x84, 0x86-0x9F)
                // Does NOT exclude: NEL (0x85) - it's normalized by EED, not rejected here
                if ((c >= 0x7F && c <= 0x84) || (c >= 0x86 && c <= 0x9F)) {
                    return false;
                }
                return true;
            }
            return (c >= 0xDC00 && c <= 0xDFFF) || // Low surrogates
                   (c >= 0xD800 && c <= 0xDBFF) || // High surrogates
                   (c >= 0xE000 && c <= 0xFFFD);
        } else {
            // XML 1.0
            return (c == 0x9 || c == 0xA || c == 0xD ||
                    (c >= 0x20 && c <= 0xD7FF) ||
                    (c >= 0xDC00 && c <= 0xDFFF) || // Low surrogates
                    (c >= 0xD800 && c <= 0xDBFF) || // High surrogates
                    (c >= 0xE000 && c <= 0xFFFD));
        }
    }
    
    /**
     * Checks if a character is an XML NameStartChar.
     * 
     * XML 1.0 5th Edition / XML 1.1 spec:
     * NameStartChar = ":" | [A-Z] | "_" | [a-z] | [#xC0-#xD6] | [#xD8-#xF6] | [#xF8-#x2FF] | 
     *                 [#x370-#x37D] | [#x37F-#x1FFF] | [#x200C-#x200D] | [#x2070-#x218F] | 
     *                 [#x2C00-#x2FEF] | [#x3001-#xD7FF] | [#xF900-#xFDCF] | [#xFDF0-#xFFFD] | 
     *                 [#x10000-#xEFFFF]
     * 
     * Unicode Blocks covered:
     * - 0xC0-0x2FF: Latin Extended-A/B, IPA Extensions, Spacing Modifier Letters, Greek, Cyrillic, etc.
     * - 0x370-0x1FFF: Greek Extended, General Punctuation, Arrows, Mathematical Operators, 
     *                 Miscellaneous Technical, Optical Character Recognition, Enclosed Alphanumerics,
     *                 Box Drawing, Block Elements, Geometric Shapes, Miscellaneous Symbols, Dingbats,
     *                 Ethiopic, Cherokee, Unified Canadian Aboriginal Syllabics, Ogham, Runic, Tagalog,
     *                 Hanunoo, Buhid, Tagbanwa, Khmer, Mongolian, Limbu, Tai Le, Khmer Symbols, Phonetic
     *                 Extensions, Latin Extended Additional, Greek Extended, and many others including
     *                 THAI (U+0E00-U+0E7F) and LAO (U+0E80-U+0EFF)
     * - 0x200C-0x200D: Zero Width Non-Joiner (ZWNJ) and Zero Width Joiner (ZWJ)
     * - 0x2070-0x218F: Superscripts and Subscripts, Currency Symbols, Combining Diacritical Marks for Symbols,
     *                  Letterlike Symbols, Number Forms, Arrows
     * - 0x2C00-0x2FEF: Glagolitic, Latin Extended-C, Coptic, Georgian Supplement, Tifinagh, Ethiopic Extended,
     *                  Supplemental Punctuation, CJK Radicals Supplement, Kangxi Radicals, Ideographic Description
     * - 0x3001-0xD7FF: CJK Symbols and Punctuation, Hiragana, Katakana, Bopomofo, Hangul Compatibility Jamo,
     *                  Kanbun, Bopomofo Extended, CJK Strokes, Katakana Phonetic Extensions, Enclosed CJK Letters,
     *                  CJK Unified Ideographs, Hangul Syllables
     * - 0xF900-0xFDCF: CJK Compatibility Ideographs
     * - 0xFDF0-0xFFFD: Arabic Presentation Forms-A, Variation Selectors, Vertical Forms, Combining Half Marks,
     *                  CJK Compatibility Forms, Small Form Variants, Arabic Presentation Forms-B, Halfwidth and
     *                  Fullwidth Forms, Specials
     * 
     * Note: The NameStartChar production is the same in XML 1.0 5th Edition and XML 1.1. 
     * The 5th edition simplified ranges to align with XML 1.1. Earlier editions had more specific
     * sub-ranges for scripts like Thai and Lao.
     * 
     * @param c the character to check
     * @param isXML11 whether processing as XML 1.1 (currently unused, same rules for both versions)
     * @return true if the character is a NameStartChar
     */
    private static boolean isNameStartChar(char c, boolean isXML11) {
        // Fast path: ASCII (most common case)
        if (c == ':' || c == '_' ||
            (c >= 'A' && c <= 'Z') ||
            (c >= 'a' && c <= 'z')) {
            return true;
        }
        
        // Fast reject: below valid Unicode range
        if (c < 0xC0) {
            return false;
        }
        
        // [#xC0-#xD6] | [#xD8-#xF6] | [#xF8-#x2FF]
        // Unicode blocks: Latin Extended-A/B, IPA Extensions, Spacing Modifier Letters, 
        // Greek, Cyrillic, Armenian, Hebrew, Arabic, Syriac, Thaana, etc.
        if (c <= 0x2FF) {
            return (c >= 0xC0 && c <= 0xD6) ||  // Latin Extended-A (excluding 0xD7 multiplication sign)
                   (c >= 0xD8 && c <= 0xF6) ||  // Latin Extended-A continued (excluding 0xF7 division sign)
                   (c >= 0xF8 && c <= 0x2FF);   // Latin Extended-A/B, IPA, Spacing Modifiers, Greek, Cyrillic
        }
        
        // [#x370-#x37D] | [#x37F-#x1FFF]
        // Unicode blocks: Greek Extended, General Punctuation, Arrows, Math Operators, Technical,
        // Box Drawing, Block Elements, Geometric Shapes, Ethiopic, Cherokee, Canadian Aboriginal,
        // Ogham, Runic, Tagalog, Hanunoo, Buhid, Tagbanwa, Khmer, Mongolian, Limbu, Tai Le,
        // THAI (U+0E00-U+0E7F), LAO (U+0E80-U+0EFF), and many others
        // XML 1.0 5th edition simplified this to match XML 1.1's broader ranges
        if (c >= 0x370 && c <= 0x1FFF) {
            return c != 0x37E;  // Exclude 0x37E (Greek Question Mark, not a NameStartChar)
        }
        
        // [#x200C-#x200D]
        // Zero Width Non-Joiner (ZWNJ) and Zero Width Joiner (ZWJ)
        // Part of General Punctuation block, special joiners for complex scripts
        if (c >= 0x200C && c <= 0x200D) {
            return true;
        }
        
        // [#x2070-#x218F]
        // Unicode blocks: Superscripts and Subscripts, Currency Symbols, Combining Diacritical 
        // Marks for Symbols, Letterlike Symbols, Number Forms, Arrows
        if (c >= 0x2070 && c <= 0x218F) {
            return true;
        }
        
        // [#x2C00-#x2FEF]
        // Unicode blocks: Glagolitic, Latin Extended-C, Coptic, Georgian Supplement, Tifinagh,
        // Ethiopic Extended, Supplemental Punctuation, CJK Radicals Supplement, Kangxi Radicals,
        // Ideographic Description Characters
        if (c >= 0x2C00 && c <= 0x2FEF) {
            return true;
        }
        
        // [#x3001-#xD7FF]
        // Unicode blocks: CJK Symbols and Punctuation, Hiragana, Katakana, Bopomofo, 
        // Hangul Compatibility Jamo, Kanbun, CJK Strokes, Katakana Phonetic Extensions,
        // Enclosed CJK Letters, CJK Unified Ideographs, Hangul Syllables, and more
        // Note: 0x3099-0x309A are Combining Katakana-Hiragana marks, which as of XML 1.0
        // 5th Edition Erratum E09, ARE allowed as NameStartChars (changed from earlier editions)
        if (c >= 0x3001 && c <= 0xD7FF) {
            return true;
        }
        
        // [#xF900-#xFDCF]
        // Unicode block: CJK Compatibility Ideographs
        if (c >= 0xF900 && c <= 0xFDCF) {
            return true;
        }
        
        // [#xFDF0-#xFFFD]
        // Unicode blocks: Arabic Presentation Forms-A, Variation Selectors, Vertical Forms,
        // Combining Half Marks, CJK Compatibility Forms, Small Form Variants,
        // Arabic Presentation Forms-B, Halfwidth and Fullwidth Forms, Specials
        if (c >= 0xFDF0 && c <= 0xFFFD) {
            return true;
        }
        
        // [#x10000-#xEFFFF] - Supplementary planes
        // These are represented as UTF-16 surrogate pairs. High surrogates in range
        // 0xD800-0xDB7F can be the start of a valid NameStartChar.
        // U+10000 = (0xD800, 0xDC00), U+EFFFF = (0xDB7F, 0xDFFF)
        // We accept high surrogates as potential NameStartChars; the actual validation
        // of the complete surrogate pair happens elsewhere.
        if (c >= 0xD800 && c <= 0xDB7F) {
            return true;  // High surrogate for U+10000-U+EFFFF
        }
        
        // Low surrogates (0xDC00-0xDFFF) are valid as continuation of a surrogate pair
        // They should follow a high surrogate and together form a valid code point
        if (c >= 0xDC00 && c <= 0xDFFF) {
            return true;  // Low surrogate - part of supplementary character
        }
        
        return false;
    }
    
    /**
     * Checks if a character is an XML NameChar (but not necessarily NameStartChar).
     * NameChar = NameStartChar | "-" | "." | [0-9] | #xB7 | [#x0300-#x036F] | [#x203F-#x2040]
     * 
     * This includes all NameStartChar plus digits, hyphen, period, middle dot,
     * combining characters, and extenders.
     * 
     * @param c the character to check
     * @param isXML11 whether processing as XML 1.1 (currently unused, same rules for both versions)
     * @return true if the character is a NameChar
     */
    private static boolean isNameChar(char c, boolean isXML11) {
        if (isNameStartChar(c, isXML11)) {
            return true;
        }
        
        // Fast path: common ASCII name characters
        if (c == '-' || c == '.' || (c >= '0' && c <= '9')) {
            return true;
        }
        
        // Middle dot (Extender)
        if (c == 0xB7) {
            return true;
        }
        
        // Combining Diacritical Marks (CombiningChar)
        if (c >= 0x0300 && c <= 0x036F) {
            return true;
        }
        
        // Additional CombiningChar ranges from XML 1.0 spec
        if ((c >= 0x0591 && c <= 0x05A1) ||
            (c >= 0x05A3 && c <= 0x05B9) ||
            (c >= 0x05BB && c <= 0x05BD) ||
            c == 0x05BF ||
            (c >= 0x05C1 && c <= 0x05C2) ||
            c == 0x05C4 ||
            (c >= 0x064B && c <= 0x0652) ||
            c == 0x0670 ||
            (c >= 0x06D6 && c <= 0x06DC) ||
            (c >= 0x06DD && c <= 0x06DF) ||
            (c >= 0x06E0 && c <= 0x06E4) ||
            (c >= 0x06E7 && c <= 0x06E8) ||
            (c >= 0x06EA && c <= 0x06ED) ||
            (c >= 0x0901 && c <= 0x0903) ||
            c == 0x093C ||
            (c >= 0x093E && c <= 0x094C) ||
            c == 0x094D ||
            (c >= 0x0951 && c <= 0x0954) ||
            (c >= 0x0962 && c <= 0x0963) ||
            (c >= 0x0981 && c <= 0x0983) ||
            c == 0x09BC ||
            c == 0x09BE ||
            c == 0x09BF ||
            (c >= 0x09C0 && c <= 0x09C4) ||
            (c >= 0x09C7 && c <= 0x09C8) ||
            (c >= 0x09CB && c <= 0x09CD) ||
            c == 0x09D7 ||
            (c >= 0x09E2 && c <= 0x09E3) ||
            c == 0x0A02 ||
            c == 0x0A3C ||
            c == 0x0A3E ||
            c == 0x0A3F ||
            (c >= 0x0A40 && c <= 0x0A42) ||
            (c >= 0x0A47 && c <= 0x0A48) ||
            (c >= 0x0A4B && c <= 0x0A4D) ||
            (c >= 0x0A70 && c <= 0x0A71) ||
            (c >= 0x0A81 && c <= 0x0A83) ||
            c == 0x0ABC ||
            (c >= 0x0ABE && c <= 0x0AC5) ||
            (c >= 0x0AC7 && c <= 0x0AC9) ||
            (c >= 0x0ACB && c <= 0x0ACD) ||
            (c >= 0x0B01 && c <= 0x0B03) ||
            c == 0x0B3C ||
            (c >= 0x0B3E && c <= 0x0B43) ||
            (c >= 0x0B47 && c <= 0x0B48) ||
            (c >= 0x0B4B && c <= 0x0B4D) ||
            (c >= 0x0B56 && c <= 0x0B57) ||
            (c >= 0x0B82 && c <= 0x0B83) ||
            (c >= 0x0BBE && c <= 0x0BC2) ||
            (c >= 0x0BC6 && c <= 0x0BC8) ||
            (c >= 0x0BCA && c <= 0x0BCD) ||
            c == 0x0BD7 ||
            (c >= 0x0C01 && c <= 0x0C03) ||
            (c >= 0x0C3E && c <= 0x0C44) ||
            (c >= 0x0C46 && c <= 0x0C48) ||
            (c >= 0x0C4A && c <= 0x0C4D) ||
            (c >= 0x0C55 && c <= 0x0C56) ||
            (c >= 0x0C82 && c <= 0x0C83) ||
            (c >= 0x0CBE && c <= 0x0CC4) ||
            (c >= 0x0CC6 && c <= 0x0CC8) ||
            (c >= 0x0CCA && c <= 0x0CCD) ||
            (c >= 0x0CD5 && c <= 0x0CD6) ||
            (c >= 0x0D02 && c <= 0x0D03) ||
            (c >= 0x0D3E && c <= 0x0D43) ||
            (c >= 0x0D46 && c <= 0x0D48) ||
            (c >= 0x0D4A && c <= 0x0D4D) ||
            c == 0x0D57 ||
            c == 0x0E31 ||
            (c >= 0x0E34 && c <= 0x0E3A) ||
            (c >= 0x0E47 && c <= 0x0E4E) ||
            c == 0x0EB1 ||
            (c >= 0x0EB4 && c <= 0x0EB9) ||
            (c >= 0x0EBB && c <= 0x0EBC) ||
            (c >= 0x0EC8 && c <= 0x0ECD) ||
            (c >= 0x0F18 && c <= 0x0F19) ||
            c == 0x0F35 ||
            c == 0x0F37 ||
            c == 0x0F39 ||
            c == 0x0F3E ||
            c == 0x0F3F ||
            (c >= 0x0F71 && c <= 0x0F84) ||
            (c >= 0x0F86 && c <= 0x0F8B) ||
            (c >= 0x0F90 && c <= 0x0F95) ||
            c == 0x0F97 ||
            (c >= 0x0F99 && c <= 0x0FAD) ||
            (c >= 0x0FB1 && c <= 0x0FB7) ||
            c == 0x0FB9 ||
            (c >= 0x20D0 && c <= 0x20DC) ||
            c == 0x20E1 ||
            (c >= 0x302A && c <= 0x302F) ||
            c == 0x3099 ||
            c == 0x309A) {
            return true;
        }
        
        // Extenders (beyond middle dot 0xB7 already checked)
        if (c == 0x02D0 ||
            c == 0x02D1 ||
            c == 0x0387 ||
            c == 0x0640 ||
            c == 0x0E46 ||
            c == 0x0EC6 ||
            c == 0x3005 ||
            (c >= 0x3031 && c <= 0x3035) ||
            (c >= 0x309D && c <= 0x309E) ||
            (c >= 0x30FC && c <= 0x30FE)) {
            return true;
        }
        
        // Underscore combining mark (used in some scripts)
        if (c >= 0x203F && c <= 0x2040) {
            return true;
        }
        
        return false;
    }
}

