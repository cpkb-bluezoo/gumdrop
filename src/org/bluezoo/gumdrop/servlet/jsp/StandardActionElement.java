/*
 * StandardActionElement.java
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
 * Represents a JSP standard action element.
 * 
 * <p>Standard actions are predefined JSP elements that provide common
 * functionality. They use the "jsp:" prefix and include actions like:
 * <ul>
 * <li>{@code <jsp:include>} - Include another resource</li>
 * <li>{@code <jsp:forward>} - Forward to another resource</li>
 * <li>{@code <jsp:useBean>} - Use a JavaBean</li>
 * <li>{@code <jsp:setProperty>} - Set bean property</li>
 * <li>{@code <jsp:getProperty>} - Get bean property</li>
 * </ul>
 * 
 * <p>Example standard actions:
 * <pre>
 * &lt;jsp:include page="header.jsp"/&gt;
 * &lt;jsp:useBean id="user" class="com.example.User" scope="session"/&gt;
 * &lt;jsp:setProperty name="user" property="name" value="John"/&gt;
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StandardActionElement implements JSPElement {
    
    // Standard action names
    public static final String INCLUDE_ACTION = "include";
    public static final String FORWARD_ACTION = "forward";
    public static final String USE_BEAN_ACTION = "useBean";
    public static final String SET_PROPERTY_ACTION = "setProperty";
    public static final String GET_PROPERTY_ACTION = "getProperty";
    public static final String PARAM_ACTION = "param";
    
    private final String actionName;
    private final Map<String, String> attributes;
    private final int lineNumber;
    private final int columnNumber;
    
    // TODO: Add support for nested jsp:param elements
    
    /**
     * Creates a new standard action element.
     * 
     * @param actionName the action name (without "jsp:" prefix)
     * @param attributes the action attributes
     * @param lineNumber the line number where this element begins (1-based)
     * @param columnNumber the column number where this element begins (1-based)
     */
    public StandardActionElement(String actionName, Map<String, String> attributes,
                               int lineNumber, int columnNumber) {
        this.actionName = actionName != null ? actionName : "";
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    @Override
    public Type getType() {
        return Type.STANDARD_ACTION;
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
     * Gets the action name (without the "jsp:" prefix).
     * 
     * @return the action name (never null)
     */
    public String getActionName() {
        return actionName;
    }
    
    /**
     * Gets the fully qualified action name with "jsp:" prefix.
     * 
     * @return the qualified action name
     */
    public String getQualifiedName() {
        return "jsp:" + actionName;
    }
    
    /**
     * Gets all attributes of this action.
     * 
     * @return a map of attribute names to values (never null)
     */
    public Map<String, String> getAttributes() {
        return new HashMap<>(attributes);
    }
    
    /**
     * Gets the value of a specific attribute.
     * 
     * @param attributeName the attribute name
     * @return the attribute value, or null if not present
     */
    public String getAttribute(String attributeName) {
        return attributes.get(attributeName);
    }
    
    /**
     * Checks if this is an include action.
     * 
     * @return true if this is a jsp:include action
     */
    public boolean isIncludeAction() {
        return INCLUDE_ACTION.equals(actionName);
    }
    
    /**
     * Checks if this is a forward action.
     * 
     * @return true if this is a jsp:forward action
     */
    public boolean isForwardAction() {
        return FORWARD_ACTION.equals(actionName);
    }
    
    /**
     * Checks if this is a useBean action.
     * 
     * @return true if this is a jsp:useBean action
     */
    public boolean isUseBeanAction() {
        return USE_BEAN_ACTION.equals(actionName);
    }
    
    @Override
    public void accept(JSPElementVisitor visitor) throws Exception {
        visitor.visitStandardAction(this);
    }
    
    @Override
    public String toString() {
        return "StandardActionElement{" +
                "qualifiedName='" + getQualifiedName() + '\'' +
                ", attributes=" + attributes.size() +
                ", line=" + lineNumber +
                ", col=" + columnNumber +
                '}';
    }
}
