/*
 * TokenizerState.java
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
 * High-level tokenizer state indicating the current parsing context.
 * <p>
 * The TokenizerState enum represents what kind of XML construct we are currently parsing.
 * Each TokenizerState has an associated set of valid mini-state transitions that determine
 * how individual characters are processed to recognize tokens.
 * <p>
 * The first three states (INIT, BOM_READ, XMLDECL) are special initialization
 * states that use custom parsing logic rather than the mini-state trie system.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
enum TokenizerState {
    
    /**
     * In document prolog, before DOCTYPE declaration.
     * Can contain: comments, PIs, whitespace, DOCTYPE, or root element.
     */
    PROLOG_BEFORE_DOCTYPE,
    
    /**
     * In document prolog, after DOCTYPE declaration.
     * Can contain: comments, PIs, whitespace, or root element (but no more DOCTYPE).
     */
    PROLOG_AFTER_DOCTYPE,
    
    /**
     * Parsing element content (text, child elements, entity references, etc.).
     */
    CONTENT,
    
    /**
     * Parsing element name after '&lt;' or '&lt;/'.
     */
    ELEMENT_NAME,
    
    /**
     * Parsing element attributes (between element name and '&gt;' or '/&gt;').
     */
    ELEMENT_ATTRS,
    
    /**
     * Inside an attribute value delimited by apostrophe (').
     */
    ATTR_VALUE_APOS,
    
    /**
     * Inside an attribute value delimited by quotation mark (").
     */
    ATTR_VALUE_QUOT,
    
    /**
     * Inside DOCTYPE declaration, before internal subset.
     */
    DOCTYPE,
    
    /**
     * Inside internal DTD subset (between '[' and ']' in DOCTYPE).
     */
    DOCTYPE_INTERNAL,
    
    /**
     * Inside a quoted string in DTD, delimited by apostrophe (').
     * Used for entity values, attribute default values, etc.
     */
    DOCTYPE_QUOTED_APOS,
    
    /**
     * Inside a quoted string in DTD, delimited by quotation mark (").
     * Used for entity values, attribute default values, etc.
     */
    DOCTYPE_QUOTED_QUOT,
    
    /**
     * Inside a quoted string in internal DTD subset, delimited by apostrophe (').
     * Used for entity replacement text.
     */
    DOCTYPE_INTERNAL_QUOTED_APOS,
    
    /**
     * Inside a quoted string in internal DTD subset, delimited by quotation mark (").
     * Used for entity replacement text.
     */
    DOCTYPE_INTERNAL_QUOTED_QUOT,
    
    /**
     * Parsing conditional section keyword (INCLUDE or IGNORE).
     * After seeing &lt;![, expecting INCLUDE or IGNORE keyword.
     */
    CONDITIONAL_SECTION_KEYWORD,
    
    /**
     * Inside an INCLUDE conditional section (between &lt;![INCLUDE[ and ]]&gt;).
     * Content is processed as DTD declarations.
     */
    CONDITIONAL_SECTION_INCLUDE,
    
    /**
     * Inside an IGNORE conditional section (between &lt;![IGNORE[ and ]]&gt;).
     * Content is skipped (not parsed).
     */
    CONDITIONAL_SECTION_IGNORE,
    
    /**
     * Inside an XML comment (&lt;!-- ... --&gt;).
     */
    COMMENT,
    
    /**
     * Inside a CDATA section (&lt;![CDATA[ ... ]]&gt;).
     */
    CDATA_SECTION,
    
    /**
     * Parsing processing instruction target (after <? and before whitespace or ?>).
     */
    PI_TARGET,
    
    /**
     * Parsing processing instruction data (after target and before ?&gt;).
     */
    PI_DATA,

    CLOSED
}

