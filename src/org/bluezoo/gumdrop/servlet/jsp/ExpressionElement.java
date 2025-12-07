/*
 * ExpressionElement.java
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
 * Represents a JSP expression element: {@code <%= java expression %>}.
 * 
 * <p>Expressions contain Java expressions that are evaluated and their
 * string representation is written to the output. The expression is
 * automatically converted to a string and HTML-escaped if necessary.
 * 
 * <p>Example expressions:
 * <pre>
 * &lt;%= new java.util.Date() %&gt;
 * &lt;%= request.getParameter("username") %&gt;
 * &lt;%= Math.random() * 100 %&gt;
 * </pre>
 * 
 * <p>In the generated servlet, expressions are converted to:
 * <pre>
 * out.print(expression);
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ExpressionElement implements JSPElement {
    
    private final String expression;
    private final int lineNumber;
    private final int columnNumber;
    
    /**
     * Creates a new expression element.
     * 
     * @param expression the Java expression
     * @param lineNumber the line number where this element begins (1-based)
     * @param columnNumber the column number where this element begins (1-based)
     */
    public ExpressionElement(String expression, int lineNumber, int columnNumber) {
        this.expression = expression != null ? expression.trim() : "";
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    @Override
    public Type getType() {
        return Type.EXPRESSION;
    }
    
    @Override
    public int getLineNumber() {
        return lineNumber;
    }
    
    @Override
    public int getColumnNumber() {
        return columnNumber;
    }
    
    /**
     * Gets the Java expression contained in this element.
     * 
     * @return the Java expression (never null, whitespace trimmed)
     */
    public String getExpression() {
        return expression;
    }
    
    /**
     * Checks if this expression is empty or contains only whitespace.
     * 
     * @return {@code true} if the expression is empty
     */
    public boolean isEmpty() {
        return expression.isEmpty();
    }
    
    /**
     * Checks if this expression appears to be a simple variable reference.
     * This can be used for optimization purposes.
     * 
     * @return {@code true} if the expression looks like a simple variable
     */
    public boolean isSimpleVariable() {
        if (expression.isEmpty()) {
            return false;
        }
        
        // Check if it's a valid Java identifier (simple variable name)
        return expression.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }
    
    @Override
    public void accept(JSPElementVisitor visitor) throws Exception {
        visitor.visitExpression(this);
    }
    
    @Override
    public String toString() {
        String preview = expression.length() > 50 
            ? expression.substring(0, 50) + "..." 
            : expression;
        
        return "ExpressionElement{" +
                "expression='" + preview + '\'' +
                ", line=" + lineNumber +
                ", col=" + columnNumber +
                '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        
        ExpressionElement other = (ExpressionElement) obj;
        return lineNumber == other.lineNumber &&
               columnNumber == other.columnNumber &&
               expression.equals(other.expression);
    }
    
    @Override
    public int hashCode() {
        int result = expression.hashCode();
        result = 31 * result + lineNumber;
        result = 31 * result + columnNumber;
        return result;
    }
}
