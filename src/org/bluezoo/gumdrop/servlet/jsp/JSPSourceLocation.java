/*
 * JSPSourceLocation.java
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
 * Represents a location in a JSP source file.
 * Used for mapping compilation errors from generated Java code back to
 * the original JSP source.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPSourceLocation {

    private final String jspFile;
    private final int jspLine;
    private final int jspColumn;
    private final String elementType;
    
    /**
     * Creates a new JSP source location.
     * 
     * @param jspFile the JSP file path
     * @param jspLine the line number in the JSP file (1-based)
     */
    public JSPSourceLocation(String jspFile, int jspLine) {
        this(jspFile, jspLine, 0, null);
    }
    
    /**
     * Creates a new JSP source location with column information.
     * 
     * @param jspFile the JSP file path
     * @param jspLine the line number in the JSP file (1-based)
     * @param jspColumn the column number in the JSP file (1-based)
     */
    public JSPSourceLocation(String jspFile, int jspLine, int jspColumn) {
        this(jspFile, jspLine, jspColumn, null);
    }
    
    /**
     * Creates a new JSP source location with element type information.
     * 
     * @param jspFile the JSP file path
     * @param jspLine the line number in the JSP file (1-based)
     * @param jspColumn the column number in the JSP file (1-based)
     * @param elementType the type of JSP element (e.g., "scriptlet", "expression")
     */
    public JSPSourceLocation(String jspFile, int jspLine, int jspColumn, String elementType) {
        this.jspFile = jspFile;
        this.jspLine = jspLine;
        this.jspColumn = jspColumn;
        this.elementType = elementType;
    }
    
    /**
     * Returns the JSP file path.
     */
    public String getJspFile() {
        return jspFile;
    }
    
    /**
     * Returns the line number in the JSP file (1-based).
     */
    public int getJspLine() {
        return jspLine;
    }
    
    /**
     * Returns the column number in the JSP file (1-based), or 0 if not available.
     */
    public int getJspColumn() {
        return jspColumn;
    }
    
    /**
     * Returns the JSP element type (e.g., "scriptlet", "expression"), 
     * or null if not specified.
     */
    public String getElementType() {
        return elementType;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(jspFile);
        sb.append(':');
        sb.append(jspLine);
        if (jspColumn > 0) {
            sb.append(':');
            sb.append(jspColumn);
        }
        if (elementType != null) {
            sb.append(" (");
            sb.append(elementType);
            sb.append(')');
        }
        return sb.toString();
    }
}

