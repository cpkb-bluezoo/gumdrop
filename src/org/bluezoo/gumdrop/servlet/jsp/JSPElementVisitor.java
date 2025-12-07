/*
 * JSPElementVisitor.java
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
 * Visitor interface for processing JSP elements using the visitor pattern.
 * This enables various operations on the JSP abstract syntax tree such as
 * code generation, validation, and transformation.
 * 
 * <p>Implementations of this interface can traverse the JSP AST and
 * perform specific operations on each element type without modifying
 * the element classes themselves.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface JSPElementVisitor {
    
    /**
     * Visits a text content element.
     * 
     * @param element the text element
     * @throws Exception if processing fails
     */
    void visitText(TextElement element) throws Exception;
    
    /**
     * Visits a scriptlet element.
     * 
     * @param element the scriptlet element
     * @throws Exception if processing fails
     */
    void visitScriptlet(ScriptletElement element) throws Exception;
    
    /**
     * Visits an expression element.
     * 
     * @param element the expression element
     * @throws Exception if processing fails
     */
    void visitExpression(ExpressionElement element) throws Exception;
    
    /**
     * Visits a declaration element.
     * 
     * @param element the declaration element
     * @throws Exception if processing fails
     */
    void visitDeclaration(DeclarationElement element) throws Exception;
    
    /**
     * Visits a directive element.
     * 
     * @param element the directive element
     * @throws Exception if processing fails
     */
    void visitDirective(DirectiveElement element) throws Exception;
    
    /**
     * Visits a comment element.
     * 
     * @param element the comment element
     * @throws Exception if processing fails
     */
    void visitComment(CommentElement element) throws Exception;
    
    /**
     * Visits a custom tag element.
     * 
     * @param element the custom tag element
     * @throws Exception if processing fails
     */
    void visitCustomTag(CustomTagElement element) throws Exception;
    
    /**
     * Visits a standard action element.
     * 
     * @param element the standard action element
     * @throws Exception if processing fails
     */
    void visitStandardAction(StandardActionElement element) throws Exception;
}
