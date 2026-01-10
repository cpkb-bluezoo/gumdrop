/*
 * Token.java
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
 * A token in an XML stream.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
enum Token {

    LT(false), // '<'
    GT(false), // '>'
    APOS(false), // "'"
    QUOT(false), // '"'
    S(true), // whitespace
    NAME(true), // name token
    CDATA(true), // character data
    CHARENTITYREF(true), // character reference replacement text (e.g., &#60; -> '<', &#65; -> 'A')
    PREDEFENTITYREF(true), // predefined entity reference replacement text (e.g., &amp; -> '&', &lt; -> '<')
    GENERALENTITYREF(true), // general entity reference &name; - CharBuffer contains entity name
    PARAMETERENTITYREF(true), // parameter entity reference %name; - CharBuffer contains entity name
    COLON(false), // ':'
    BANG(false), // '!'
    QUERY(false), // '?'
    EQ(false), // '='
    PERCENT(false), // '%'
    HASH(false), // '#'
    PIPE(false), // '|'
    START_END_ELEMENT(false), // "</"
    END_EMPTY_ELEMENT(false), // "/>"
    START_COMMENT(false), // "<!--"
    END_COMMENT(false), // "-->"
    START_CDATA(false), // "<[CDATA["
    END_CDATA(false), // "]]>" - ends both CDATA sections and conditional sections
    START_PI(false), // "<?"
    END_PI(false), // "?>"
    START_DOCTYPE(false), // "<!DOCTYPE"
    START_ELEMENTDECL(false), // "<!ELEMENT"
    START_ATTLISTDECL(false), // "<!ATTLIST"
    START_ENTITYDECL(false), // "<!ENTITY"
    START_NOTATIONDECL(false), // "<!NOTATION"
    START_CONDITIONAL(false), // "<!["
    OPEN_BRACKET(false), // '['
    CLOSE_BRACKET(false), // ']'
    OPEN_PAREN(false), // '('
    CLOSE_PAREN(false), // ')'
    STAR(false), // '*'
    PLUS(false), // '+'
    COMMA(false), // ','
    
    // DOCTYPE keywords
    SYSTEM(false), // "SYSTEM"
    PUBLIC(false), // "PUBLIC"
    NDATA(false), // "NDATA"
    
    // ELEMENT content model keywords
    EMPTY(false), // "EMPTY"
    ANY(false), // "ANY"
    PCDATA(false), // "#PCDATA"
    
    // ATTLIST type keywords
    CDATA_TYPE(false), // "CDATA" (attribute type, distinct from CDATA token for character data)
    ID(false), // "ID"
    IDREF(false), // "IDREF"
    IDREFS(false), // "IDREFS"
    ENTITY(false), // "ENTITY"
    ENTITIES(false), // "ENTITIES"
    NMTOKEN(false), // "NMTOKEN"
    NMTOKENS(false), // "NMTOKENS"
    NOTATION(false), // "NOTATION"
    
    // ATTLIST default value keywords
    REQUIRED(false), // "#REQUIRED"
    IMPLIED(false), // "#IMPLIED"
    FIXED(false), // "#FIXED"
    
    // Conditional section keywords
    INCLUDE(false), // "INCLUDE"
    IGNORE(false); // "IGNORE"

    private final boolean hasAssociatedText;
    
    Token(boolean hasAssociatedText) {
        this.hasAssociatedText = hasAssociatedText;
    }
    
    /**
     * Returns true if this token type should have associated text (CharBuffer) when emitted.
     * @return true if this token type has associated text
     */
    public boolean hasAssociatedText() {
        return hasAssociatedText;
    }

}
