/*
 * DeclarationElement.java
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
 * Represents a JSP declaration element: {@code <%! java declarations %>}.
 * 
 * <p>Declarations contain Java code that becomes class-level members
 * of the generated servlet. This includes instance variables, methods,
 * and inner classes. Declarations are executed when the servlet is
 * instantiated, not during request processing.
 * 
 * <p>Example declarations:
 * <pre>
 * &lt;%!
 *   private int counter = 0;
 *   
 *   private String formatDate(Date date) {
 *     return new SimpleDateFormat("yyyy-MM-dd").format(date);
 *   }
 *   
 *   static {
 *     System.out.println("Servlet class loaded");
 *   }
 * %&gt;
 * </pre>
 * 
 * <p><strong>Note:</strong> Instance variables declared in JSP declarations
 * are shared across all requests to the servlet, so thread safety must
 * be considered.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DeclarationElement implements JSPElement {
    
    private final String declaration;
    private final int lineNumber;
    private final int columnNumber;
    
    /**
     * Creates a new declaration element.
     * 
     * @param declaration the Java declaration code
     * @param lineNumber the line number where this element begins (1-based)
     * @param columnNumber the column number where this element begins (1-based)
     */
    public DeclarationElement(String declaration, int lineNumber, int columnNumber) {
        this.declaration = declaration != null ? declaration : "";
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    @Override
    public Type getType() {
        return Type.DECLARATION;
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
     * Gets the Java declaration code contained in this element.
     * 
     * @return the Java declaration code (never null)
     */
    public String getDeclaration() {
        return declaration;
    }
    
    /**
     * Checks if this declaration contains only whitespace or comments.
     * 
     * @return {@code true} if the declaration is effectively empty
     */
    public boolean isEmpty() {
        // Remove single-line and multi-line comments, then check if only whitespace remains
        String cleanCode = declaration
            .replaceAll("//.*?(?=\n|$)", "")  // Remove single-line comments
            .replaceAll("/\\*.*?\\*/", "")     // Remove multi-line comments
            .trim();
        
        return cleanCode.isEmpty();
    }
    
    /**
     * Checks if this declaration appears to contain method definitions.
     * This is a heuristic check for code organization purposes.
     * 
     * @return {@code true} if the declaration likely contains methods
     */
    public boolean containsMethods() {
        // Simple heuristic: look for method signatures
        return declaration.matches("(?s).*\\b(public|private|protected|static)?\\s*(\\w+\\s+)*\\w+\\s*\\([^)]*\\)\\s*\\{.*");
    }
    
    /**
     * Checks if this declaration appears to contain field definitions.
     * This is a heuristic check for code organization purposes.
     * 
     * @return {@code true} if the declaration likely contains fields
     */
    public boolean containsFields() {
        // Simple heuristic: look for variable declarations (not in method signatures)
        String withoutMethods = declaration.replaceAll("\\([^)]*\\)\\s*\\{[^}]*\\}", "");
        return withoutMethods.matches("(?s).*\\b(public|private|protected|static|final)?\\s*(\\w+\\s+)*\\w+\\s+(\\w+)(\\s*=.*?)?\\s*;.*");
    }
    
    @Override
    public void accept(JSPElementVisitor visitor) throws Exception {
        visitor.visitDeclaration(this);
    }
    
    @Override
    public String toString() {
        String preview = declaration.length() > 50 
            ? declaration.substring(0, 50) + "..." 
            : declaration;
        
        // Replace newlines for cleaner display
        preview = preview.replace('\n', '↵');
        
        return "DeclarationElement{" +
                "declaration='" + preview + '\'' +
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
        
        DeclarationElement other = (DeclarationElement) obj;
        return lineNumber == other.lineNumber &&
               columnNumber == other.columnNumber &&
               declaration.equals(other.declaration);
    }
    
    @Override
    public int hashCode() {
        int result = declaration.hashCode();
        result = 31 * result + lineNumber;
        result = 31 * result + columnNumber;
        return result;
    }
}
