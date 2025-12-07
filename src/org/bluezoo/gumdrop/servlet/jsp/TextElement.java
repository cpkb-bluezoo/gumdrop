/*
 * TextElement.java
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
 * Represents plain text or HTML content in a JSP page.
 * This includes any static content that is not JSP-specific markup.
 * 
 * <p>Text elements are rendered directly to the output by writing
 * to the servlet response's output stream or writer.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TextElement implements JSPElement {
    
    private final String content;
    private final int lineNumber;
    private final int columnNumber;
    
    /**
     * Creates a new text element.
     * 
     * @param content the text content
     * @param lineNumber the line number where this element begins (1-based)
     * @param columnNumber the column number where this element begins (1-based)
     */
    public TextElement(String content, int lineNumber, int columnNumber) {
        this.content = content != null ? content : "";
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    @Override
    public Type getType() {
        return Type.TEXT;
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
     * Gets the text content of this element.
     * 
     * @return the text content (never null)
     */
    public String getContent() {
        return content;
    }
    
    /**
     * Checks if this text element contains only whitespace.
     * 
     * @return {@code true} if the content is empty or contains only whitespace
     */
    public boolean isWhitespaceOnly() {
        return content.trim().isEmpty();
    }
    
    @Override
    public void accept(JSPElementVisitor visitor) throws Exception {
        visitor.visitText(this);
    }
    
    @Override
    public String toString() {
        String preview = content.length() > 50 
            ? content.substring(0, 50) + "..." 
            : content;
        
        // Replace newlines and tabs for cleaner display
        preview = preview.replace('\n', '\\').replace('\t', ' ');
        
        return "TextElement{" +
                "content='" + preview + '\'' +
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
        
        TextElement other = (TextElement) obj;
        return lineNumber == other.lineNumber &&
               columnNumber == other.columnNumber &&
               content.equals(other.content);
    }
    
    @Override
    public int hashCode() {
        int result = content.hashCode();
        result = 31 * result + lineNumber;
        result = 31 * result + columnNumber;
        return result;
    }
}
