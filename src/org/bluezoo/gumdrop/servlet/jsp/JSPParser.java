/*
 * JSPParser.java
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

import java.io.InputStream;
import java.io.IOException;

/**
 * Common interface for JSP parsers that can handle both XML (JSPX) and traditional JSP syntax.
 * Implementations parse JSP source files and create an in-memory abstract syntax tree
 * that can be used to generate Java servlet source code.
 * 
 * <p>This interface supports two parsing modes:
 * <ul>
 * <li><strong>XML Mode</strong> - Parses JSP files in XML format (JSPX) using SAX parsing</li>
 * <li><strong>Traditional Mode</strong> - Parses traditional JSP syntax with {@code <% %>} scriptlets</li>
 * </ul>
 * 
 * <p>Both parsers create the same internal representation, enabling uniform code generation
 * and compilation to {@code javax.servlet.http.HttpServlet} classes.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface JSPParser {
    
    /**
     * Parses a JSP source file from the provided input stream and creates an
     * abstract syntax tree representation.
     * 
     * @param input the input stream containing JSP source bytes
     * @param encoding the character encoding of the JSP source (e.g., "UTF-8")
     * @param jspUri the URI/path of the JSP file being parsed (for error reporting)
     * @return a {@link JSPPage} representing the parsed JSP structure
     * @throws IOException if an I/O error occurs while reading the input
     * @throws JSPParseException if the JSP source contains syntax errors
     */
    JSPPage parse(InputStream input, String encoding, String jspUri) 
        throws IOException, JSPParseException;
    
    /**
     * Parses a JSP source file from the provided input stream using the specified
     * JSP configuration properties. This method allows parsers to respect JSP
     * configuration such as scriptingInvalid and elIgnored settings.
     * 
     * @param input the input stream containing JSP source bytes
     * @param encoding the character encoding of the JSP source (e.g., "UTF-8")
     * @param jspUri the URI/path of the JSP file being parsed (for error reporting)
     * @param jspProperties the resolved JSP configuration properties
     * @return a {@link JSPPage} representing the parsed JSP structure
     * @throws IOException if an I/O error occurs while reading the input
     * @throws JSPParseException if the JSP source contains syntax errors or violates configuration
     */
    default JSPPage parse(InputStream input, String encoding, String jspUri, 
                         JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties) 
        throws IOException, JSPParseException {
        // Default implementation delegates to the standard parse method
        // Concrete parsers can override this to respect JSP configuration
        return parse(input, encoding, jspUri);
    }
    
    /**
     * Determines if this parser can handle the given JSP source format.
     * 
     * @param input the input stream containing JSP source (will be reset after checking)
     * @param encoding the character encoding to use for format detection
     * @return {@code true} if this parser can handle the format, {@code false} otherwise
     * @throws IOException if an I/O error occurs during format detection
     */
    boolean canParse(InputStream input, String encoding) throws IOException;
    
    /**
     * Gets a human-readable name for this parser implementation.
     * 
     * @return the parser name (e.g., "XML JSP Parser" or "Traditional JSP Parser")
     */
    String getParserName();
}
