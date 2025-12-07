/*
 * JspException.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package javax.servlet.jsp;

/**
 * Basic JSP Exception for Gumdrop JSP support.
 * 
 * <p>This provides a minimal implementation of the JSP API's JspException class.</p>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspException extends Exception {

    /**
     * Construct a JspException.
     */
    public JspException() {
        super();
    }

    /**
     * Constructs a new JSP exception with the specified message.
     *
     * @param message the detail message
     */
    public JspException(String message) {
        super(message);
    }

    /**
     * Constructs a new JSP exception with the specified detail message and cause.
     *
     * @param message the detail message 
     * @param cause the cause
     */
    public JspException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new JSP exception with the specified cause.
     *
     * @param cause the cause
     */
    public JspException(Throwable cause) {
        super(cause);
    }
}
