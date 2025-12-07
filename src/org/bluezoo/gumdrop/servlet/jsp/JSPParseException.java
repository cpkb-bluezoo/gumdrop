/*
 * JSPParseException.java
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
 * Exception thrown when JSP parsing encounters syntax errors or other parsing failures.
 * This exception provides detailed information about the location and nature of parsing errors.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPParseException extends Exception {
    
    private static final long serialVersionUID = 1L;
    
    private final String jspUri;
    private final int lineNumber;
    private final int columnNumber;
    
    /**
     * Creates a new JSP parse exception with the specified message.
     * 
     * @param message the error message
     */
    public JSPParseException(String message) {
        this(message, null, -1, -1);
    }
    
    /**
     * Creates a new JSP parse exception with the specified message and cause.
     * 
     * @param message the error message
     * @param cause the underlying cause of the parsing error
     */
    public JSPParseException(String message, Throwable cause) {
        this(message, null, -1, -1, cause);
    }
    
    /**
     * Creates a new JSP parse exception with location information.
     * 
     * @param message the error message
     * @param jspUri the URI/path of the JSP file where the error occurred
     * @param lineNumber the line number where the error occurred (1-based, -1 if unknown)
     * @param columnNumber the column number where the error occurred (1-based, -1 if unknown)
     */
    public JSPParseException(String message, String jspUri, int lineNumber, int columnNumber) {
        super(formatMessage(message, jspUri, lineNumber, columnNumber));
        this.jspUri = jspUri;
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    /**
     * Creates a new JSP parse exception with location information and underlying cause.
     * 
     * @param message the error message
     * @param jspUri the URI/path of the JSP file where the error occurred
     * @param lineNumber the line number where the error occurred (1-based, -1 if unknown)
     * @param columnNumber the column number where the error occurred (1-based, -1 if unknown)
     * @param cause the underlying cause of the parsing error
     */
    public JSPParseException(String message, String jspUri, int lineNumber, int columnNumber, Throwable cause) {
        super(formatMessage(message, jspUri, lineNumber, columnNumber), cause);
        this.jspUri = jspUri;
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    /**
     * Gets the URI/path of the JSP file where the error occurred.
     * 
     * @return the JSP URI, or {@code null} if not available
     */
    public String getJSPUri() {
        return jspUri;
    }
    
    /**
     * Gets the line number where the error occurred.
     * 
     * @return the line number (1-based), or -1 if not available
     */
    public int getLineNumber() {
        return lineNumber;
    }
    
    /**
     * Gets the column number where the error occurred.
     * 
     * @return the column number (1-based), or -1 if not available
     */
    public int getColumnNumber() {
        return columnNumber;
    }
    
    /**
     * Formats an error message with location information.
     */
    private static String formatMessage(String message, String jspUri, int lineNumber, int columnNumber) {
        StringBuilder sb = new StringBuilder();
        
        if (jspUri != null) {
            sb.append(jspUri);
            if (lineNumber >= 0) {
                sb.append(':').append(lineNumber);
                if (columnNumber >= 0) {
                    sb.append(':').append(columnNumber);
                }
            }
            sb.append(": ");
        }
        
        sb.append(message);
        return sb.toString();
    }
}
