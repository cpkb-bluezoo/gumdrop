/*
 * DirectiveElement.java
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

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a JSP directive element: {@code <%@ directive attributes %>}.
 * 
 * <p>Directives provide information about the JSP page and control
 * how the JSP compiler processes the page. The main directive types are:
 * <ul>
 * <li><strong>page</strong> - Page-level settings (imports, content type, etc.)</li>
 * <li><strong>include</strong> - Include another file at compile time</li>
 * <li><strong>taglib</strong> - Declare a tag library</li>
 * </ul>
 * 
 * <p>Example directives:
 * <pre>
 * &lt;%@ page language="java" contentType="text/html; charset=UTF-8" %&gt;
 * &lt;%@ page import="java.util.*,java.text.*" %&gt;
 * &lt;%@ include file="header.jsp" %&gt;
 * &lt;%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %&gt;
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DirectiveElement implements JSPElement {
    
    /**
     * Standard JSP directive names.
     */
    public static final String PAGE_DIRECTIVE = "page";
    public static final String INCLUDE_DIRECTIVE = "include";
    public static final String TAGLIB_DIRECTIVE = "taglib";
    
    private final String name;
    private final Map<String, String> attributes;
    private final int lineNumber;
    private final int columnNumber;
    
    /**
     * Creates a new directive element.
     * 
     * @param name the directive name (e.g., "page", "include", "taglib")
     * @param attributes the directive attributes as name-value pairs
     * @param lineNumber the line number where this element begins (1-based)
     * @param columnNumber the column number where this element begins (1-based)
     */
    public DirectiveElement(String name, Map<String, String> attributes, 
                           int lineNumber, int columnNumber) {
        this.name = name != null ? name.trim() : "";
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    @Override
    public Type getType() {
        return Type.DIRECTIVE;
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
     * Gets the name of this directive.
     * 
     * @return the directive name (never null)
     */
    public String getName() {
        return name;
    }
    
    /**
     * Gets all attributes of this directive.
     * 
     * @return a map of attribute name to value pairs (never null)
     */
    public Map<String, String> getAttributes() {
        return new HashMap<>(attributes);
    }
    
    /**
     * Gets the value of a specific attribute.
     * 
     * @param attributeName the name of the attribute
     * @return the attribute value, or {@code null} if not present
     */
    public String getAttribute(String attributeName) {
        return attributes.get(attributeName);
    }
    
    /**
     * Checks if this directive has a specific attribute.
     * 
     * @param attributeName the name of the attribute
     * @return {@code true} if the attribute is present
     */
    public boolean hasAttribute(String attributeName) {
        return attributes.containsKey(attributeName);
    }
    
    /**
     * Checks if this is a page directive.
     * 
     * @return {@code true} if this is a page directive
     */
    public boolean isPageDirective() {
        return PAGE_DIRECTIVE.equals(name);
    }
    
    /**
     * Checks if this is an include directive.
     * 
     * @return {@code true} if this is an include directive
     */
    public boolean isIncludeDirective() {
        return INCLUDE_DIRECTIVE.equals(name);
    }
    
    /**
     * Checks if this is a taglib directive.
     * 
     * @return {@code true} if this is a taglib directive
     */
    public boolean isTaglibDirective() {
        return TAGLIB_DIRECTIVE.equals(name);
    }
    
    @Override
    public void accept(JSPElementVisitor visitor) throws Exception {
        visitor.visitDirective(this);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DirectiveElement{");
        sb.append("name='").append(name).append('\'');
        sb.append(", attributes={");
        
        boolean first = true;
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("='").append(entry.getValue()).append('\'');
            first = false;
        }
        
        sb.append("}");
        sb.append(", line=").append(lineNumber);
        sb.append(", col=").append(columnNumber);
        sb.append('}');
        
        return sb.toString();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        
        DirectiveElement other = (DirectiveElement) obj;
        return lineNumber == other.lineNumber &&
               columnNumber == other.columnNumber &&
               name.equals(other.name) &&
               attributes.equals(other.attributes);
    }
    
    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + attributes.hashCode();
        result = 31 * result + lineNumber;
        result = 31 * result + columnNumber;
        return result;
    }
}
