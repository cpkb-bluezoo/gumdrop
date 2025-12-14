/*
 * CommentElement.java
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
 * Represents a JSP comment element: {@code <%-- comment --%>}.
 * 
 * <p>JSP comments are removed during compilation and do not appear
 * in the generated servlet code or in the final HTML output sent
 * to the client. This is different from HTML comments ({@code <!-- -->})
 * which are included in the output.
 * 
 * <p>Example JSP comments:
 * <pre>
 * &lt;%-- This is a JSP comment that won't appear in output --%&gt;
 * &lt;%-- 
 *   Multi-line JSP comment
 *   Can span multiple lines
 * --%&gt;
 * </pre>
 * 
 * <p>JSP comments are useful for:
 * <ul>
 * <li>Documenting JSP code without affecting output</li>
 * <li>Temporarily disabling JSP elements during development</li>
 * <li>Including metadata that should not be visible to clients</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CommentElement implements JSPElement {
    
    private final String comment;
    private final int lineNumber;
    private final int columnNumber;
    
    /**
     * Creates a new comment element.
     * 
     * @param comment the comment text (without the &lt;%-- --%&gt; delimiters)
     * @param lineNumber the line number where this element begins (1-based)
     * @param columnNumber the column number where this element begins (1-based)
     */
    public CommentElement(String comment, int lineNumber, int columnNumber) {
        this.comment = comment != null ? comment : "";
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    @Override
    public Type getType() {
        return Type.COMMENT;
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
     * Gets the comment text contained in this element.
     * 
     * @return the comment text (never null)
     */
    public String getComment() {
        return comment;
    }
    
    /**
     * Checks if this comment contains only whitespace.
     * 
     * @return {@code true} if the comment is empty or contains only whitespace
     */
    public boolean isEmpty() {
        return comment.trim().isEmpty();
    }
    
    /**
     * Gets the number of lines spanned by this comment.
     * 
     * @return the number of lines (at least 1)
     */
    public int getLineCount() {
        if (comment.isEmpty()) {
            return 1;
        }
        
        int count = 1;
        for (int i = 0; i < comment.length(); i++) {
            if (comment.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
    
    @Override
    public void accept(JSPElementVisitor visitor) throws Exception {
        visitor.visitComment(this);
    }
    
    @Override
    public String toString() {
        String preview = comment.length() > 50 
            ? comment.substring(0, 50) + "..." 
            : comment;
        
        // Replace newlines for cleaner display
        preview = preview.replace('\n', '↵').replace('\r', ' ');
        
        return "CommentElement{" +
                "comment='" + preview + '\'' +
                ", lines=" + getLineCount() +
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
        
        CommentElement other = (CommentElement) obj;
        return lineNumber == other.lineNumber &&
               columnNumber == other.columnNumber &&
               comment.equals(other.comment);
    }
    
    @Override
    public int hashCode() {
        int result = comment.hashCode();
        result = 31 * result + lineNumber;
        result = 31 * result + columnNumber;
        return result;
    }
}
