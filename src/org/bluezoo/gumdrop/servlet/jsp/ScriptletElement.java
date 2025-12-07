/*
 * ScriptletElement.java
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
 * Represents a JSP scriptlet element: {@code <% java code %>}.
 * 
 * <p>Scriptlets contain Java code that is executed during request processing.
 * The code is inserted directly into the service method of the generated servlet
 * and has access to implicit objects like {@code request}, {@code response},
 * {@code session}, {@code out}, etc.
 * 
 * <p>Example scriptlet:
 * <pre>
 * &lt;% 
 *   String name = request.getParameter("name");
 *   if (name != null) {
 *     out.println("Hello, " + name + "!");
 *   }
 * %&gt;
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ScriptletElement implements JSPElement {
    
    private final String code;
    private final int lineNumber;
    private final int columnNumber;
    
    /**
     * Creates a new scriptlet element.
     * 
     * @param code the Java code contained in the scriptlet
     * @param lineNumber the line number where this element begins (1-based)
     * @param columnNumber the column number where this element begins (1-based)
     */
    public ScriptletElement(String code, int lineNumber, int columnNumber) {
        this.code = code != null ? code : "";
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    @Override
    public Type getType() {
        return Type.SCRIPTLET;
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
     * Gets the Java code contained in this scriptlet.
     * 
     * @return the Java code (never null)
     */
    public String getCode() {
        return code;
    }
    
    /**
     * Checks if this scriptlet contains only whitespace or comments.
     * 
     * @return {@code true} if the code is effectively empty
     */
    public boolean isEmpty() {
        // Remove single-line and multi-line comments, then check if only whitespace remains
        String cleanCode = code
            .replaceAll("//.*?(?=\n|$)", "")  // Remove single-line comments
            .replaceAll("/\\*.*?\\*/", "")     // Remove multi-line comments
            .trim();
        
        return cleanCode.isEmpty();
    }
    
    @Override
    public void accept(JSPElementVisitor visitor) throws Exception {
        visitor.visitScriptlet(this);
    }
    
    @Override
    public String toString() {
        String preview = code.length() > 50 
            ? code.substring(0, 50) + "..." 
            : code;
        
        // Replace newlines for cleaner display
        preview = preview.replace('\n', '↵');
        
        return "ScriptletElement{" +
                "code='" + preview + '\'' +
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
        
        ScriptletElement other = (ScriptletElement) obj;
        return lineNumber == other.lineNumber &&
               columnNumber == other.columnNumber &&
               code.equals(other.code);
    }
    
    @Override
    public int hashCode() {
        int result = code.hashCode();
        result = 31 * result + lineNumber;
        result = 31 * result + columnNumber;
        return result;
    }
}
