/*
 * CustomTagElement.java
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
 * Represents a custom tag element in a JSP page.
 * 
 * <p>Custom tags are user-defined tags that extend JSP functionality.
 * They are defined in tag libraries (TLD files) and can have attributes,
 * body content, and nested tags.
 * 
 * <p>Example custom tags:
 * <pre>
 * &lt;c:if test="${user != null}"&gt;
 *   Welcome, &lt;c:out value="${user.name}"/&gt;
 * &lt;/c:if&gt;
 * 
 * &lt;my:formatDate value="${date}" pattern="yyyy-MM-dd"/&gt;
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CustomTagElement implements JSPElement {
    
    private final String prefix;
    private final String tagName;
    private final Map<String, String> attributes;
    private final int lineNumber;
    private final int columnNumber;
    
    // TODO: Add support for body content and nested elements
    
    /**
     * Creates a new custom tag element.
     * 
     * @param prefix the tag library prefix
     * @param tagName the tag name
     * @param attributes the tag attributes
     * @param lineNumber the line number where this element begins (1-based)
     * @param columnNumber the column number where this element begins (1-based)
     */
    public CustomTagElement(String prefix, String tagName, Map<String, String> attributes,
                           int lineNumber, int columnNumber) {
        this.prefix = prefix != null ? prefix : "";
        this.tagName = tagName != null ? tagName : "";
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    @Override
    public Type getType() {
        return Type.CUSTOM_TAG;
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
     * Gets the tag library prefix.
     * 
     * @return the prefix (never null)
     */
    public String getPrefix() {
        return prefix;
    }
    
    /**
     * Gets the tag name.
     * 
     * @return the tag name (never null)
     */
    public String getTagName() {
        return tagName;
    }
    
    /**
     * Gets the fully qualified tag name (prefix:tagName).
     * 
     * @return the qualified tag name
     */
    public String getQualifiedName() {
        return prefix.isEmpty() ? tagName : prefix + ":" + tagName;
    }
    
    /**
     * Gets all attributes of this tag.
     * 
     * @return a map of attribute names to values (never null)
     */
    public Map<String, String> getAttributes() {
        return new HashMap<>(attributes);
    }
    
    @Override
    public void accept(JSPElementVisitor visitor) throws Exception {
        visitor.visitCustomTag(this);
    }
    
    @Override
    public String toString() {
        return "CustomTagElement{" +
                "qualifiedName='" + getQualifiedName() + '\'' +
                ", attributes=" + attributes.size() +
                ", line=" + lineNumber +
                ", col=" + columnNumber +
                '}';
    }
}
