/*
 * JSPElement.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet.jsp;

/**
 * Base interface for all JSP elements in the abstract syntax tree.
 * JSP elements represent the various components that make up a JSP page,
 * including text content, scriptlets, expressions, declarations, and directives.
 * 
 * <p>All JSP elements maintain location information for error reporting
 * and debugging purposes.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface JSPElement {
    
    /**
     * Enumeration of JSP element types.
     */
    enum Type {
        /** Plain text or HTML content */
        TEXT,
        /** JSP scriptlet: {@code <% code %>} */
        SCRIPTLET,
        /** JSP expression: {@code <%= expression %>} */
        EXPRESSION,
        /** JSP declaration: {@code <%! declaration %>} */
        DECLARATION,
        /** JSP directive: {@code <%@ directive attributes %>} */
        DIRECTIVE,
        /** JSP comment: {@code <%-- comment --%>} */
        COMMENT,
        /** Custom tag usage */
        CUSTOM_TAG,
        /** JSP standard action (e.g., jsp:include, jsp:forward) */
        STANDARD_ACTION
    }
    
    /**
     * Gets the type of this JSP element.
     * 
     * @return the element type
     */
    Type getType();
    
    /**
     * Gets the line number where this element begins in the source JSP file.
     * 
     * @return the line number (1-based), or -1 if not available
     */
    int getLineNumber();
    
    /**
     * Gets the column number where this element begins in the source JSP file.
     * 
     * @return the column number (1-based), or -1 if not available
     */
    int getColumnNumber();
    
    /**
     * Accepts a visitor for processing this element.
     * This enables the visitor pattern for code generation and other
     * tree traversal operations.
     * 
     * @param visitor the visitor to accept
     * @throws Exception if the visitor encounters an error
     */
    void accept(JSPElementVisitor visitor) throws Exception;
}
