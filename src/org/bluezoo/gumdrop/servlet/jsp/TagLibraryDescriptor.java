/*
 * TagLibraryDescriptor.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a parsed Tag Library Descriptor (TLD) containing tag definitions
 * and metadata for a JSP tag library.
 * 
 * <p>This class holds all information extracted from a .tld file, including:
 * <ul>
 *   <li>Library metadata (version, shortname, URI, description)</li>
 *   <li>Tag definitions with their handler classes and attributes</li>
 *   <li>Function definitions for EL functions</li>
 *   <li>Validation and other configuration settings</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TagLibraryDescriptor {

    // Library metadata
    private String tlibVersion;
    private String shortName;
    private String uri;
    private String description;
    private String displayName;
    private String smallIcon;
    private String largeIcon;
    private String jspVersion;

    // Tag definitions
    private final Map<String, TagDescriptor> tags = new HashMap<>();
    
    // Function definitions for EL functions
    private final Map<String, FunctionDescriptor> functions = new HashMap<>();
    
    // Validator configuration
    private String validatorClass;
    private final Map<String, String> initParams = new HashMap<>();

    // Source location for debugging
    private String sourceLocation;

    /**
     * Creates a new empty TagLibraryDescriptor.
     */
    public TagLibraryDescriptor() {
    }

    // Getters and setters for library metadata

    public String getTlibVersion() {
        return tlibVersion;
    }

    public void setTlibVersion(String tlibVersion) {
        this.tlibVersion = tlibVersion;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getSmallIcon() {
        return smallIcon;
    }

    public void setSmallIcon(String smallIcon) {
        this.smallIcon = smallIcon;
    }

    public String getLargeIcon() {
        return largeIcon;
    }

    public void setLargeIcon(String largeIcon) {
        this.largeIcon = largeIcon;
    }

    public String getJspVersion() {
        return jspVersion;
    }

    public void setJspVersion(String jspVersion) {
        this.jspVersion = jspVersion;
    }

    public String getValidatorClass() {
        return validatorClass;
    }

    public void setValidatorClass(String validatorClass) {
        this.validatorClass = validatorClass;
    }

    public String getSourceLocation() {
        return sourceLocation;
    }

    public void setSourceLocation(String sourceLocation) {
        this.sourceLocation = sourceLocation;
    }

    // Tag management

    public void addTag(TagDescriptor tag) {
        if (tag != null && tag.getName() != null) {
            tags.put(tag.getName(), tag);
        }
    }

    public TagDescriptor getTag(String tagName) {
        return tags.get(tagName);
    }

    public Map<String, TagDescriptor> getTags() {
        return Collections.unmodifiableMap(tags);
    }

    // Function management

    public void addFunction(FunctionDescriptor function) {
        if (function != null && function.getName() != null) {
            functions.put(function.getName(), function);
        }
    }

    public FunctionDescriptor getFunction(String functionName) {
        return functions.get(functionName);
    }

    public Map<String, FunctionDescriptor> getFunctions() {
        return Collections.unmodifiableMap(functions);
    }

    // Init param management

    public void addInitParam(String name, String value) {
        if (name != null) {
            initParams.put(name, value);
        }
    }

    public String getInitParam(String name) {
        return initParams.get(name);
    }

    public Map<String, String> getInitParams() {
        return Collections.unmodifiableMap(initParams);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TagLibraryDescriptor{");
        sb.append("uri='").append(uri).append('\'');
        sb.append(", shortName='").append(shortName).append('\'');
        sb.append(", version='").append(tlibVersion).append('\'');
        sb.append(", tags=").append(tags.keySet());
        sb.append(", functions=").append(functions.keySet());
        sb.append('}');
        return sb.toString();
    }

    /**
     * Represents a tag definition within a tag library.
     */
    public static class TagDescriptor {
        private String name;
        private String tagClass;
        private String teiClass; // Tag Extra Info class
        private String bodyContent = "JSP"; // JSP, empty, scriptless, tagdependent
        private String description;
        private String displayName;
        private String smallIcon;
        private String largeIcon;
        private boolean dynamicAttributes = false;

        private final Map<String, AttributeDescriptor> attributes = new HashMap<>();
        private final List<VariableDescriptor> variables = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTagClass() {
            return tagClass;
        }

        public void setTagClass(String tagClass) {
            this.tagClass = tagClass;
        }

        public String getTeiClass() {
            return teiClass;
        }

        public void setTeiClass(String teiClass) {
            this.teiClass = teiClass;
        }

        public String getBodyContent() {
            return bodyContent;
        }

        public void setBodyContent(String bodyContent) {
            this.bodyContent = bodyContent;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getSmallIcon() {
            return smallIcon;
        }

        public void setSmallIcon(String smallIcon) {
            this.smallIcon = smallIcon;
        }

        public String getLargeIcon() {
            return largeIcon;
        }

        public void setLargeIcon(String largeIcon) {
            this.largeIcon = largeIcon;
        }

        public boolean isDynamicAttributes() {
            return dynamicAttributes;
        }

        public void setDynamicAttributes(boolean dynamicAttributes) {
            this.dynamicAttributes = dynamicAttributes;
        }

        public void addAttribute(AttributeDescriptor attribute) {
            if (attribute != null && attribute.getName() != null) {
                attributes.put(attribute.getName(), attribute);
            }
        }

        public AttributeDescriptor getAttribute(String name) {
            return attributes.get(name);
        }

        public Map<String, AttributeDescriptor> getAttributes() {
            return Collections.unmodifiableMap(attributes);
        }

        public void addVariable(VariableDescriptor variable) {
            if (variable != null) {
                variables.add(variable);
            }
        }

        public List<VariableDescriptor> getVariables() {
            return Collections.unmodifiableList(variables);
        }

        @Override
        public String toString() {
            return "TagDescriptor{name='" + name + "', tagClass='" + tagClass + "'}";
        }
    }

    /**
     * Represents an attribute definition for a tag.
     */
    public static class AttributeDescriptor {
        private String name;
        private boolean required = false;
        private boolean rtexprvalue = false; // Can contain runtime expressions
        private String type = "java.lang.String";
        private String description;
        private boolean fragment = false; // JSP 2.0 fragment attribute

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public boolean isRtexprvalue() {
            return rtexprvalue;
        }

        public void setRtexprvalue(boolean rtexprvalue) {
            this.rtexprvalue = rtexprvalue;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isFragment() {
            return fragment;
        }

        public void setFragment(boolean fragment) {
            this.fragment = fragment;
        }

        @Override
        public String toString() {
            return "AttributeDescriptor{name='" + name + "', required=" + required + ", type='" + type + "'}";
        }
    }

    /**
     * Represents a scripting variable exposed by a tag.
     */
    public static class VariableDescriptor {
        private String nameGiven;
        private String nameFromAttribute;
        private String variableClass = "java.lang.String";
        private boolean declare = true;
        private String scope = "NESTED"; // NESTED, AT_BEGIN, AT_END
        private String description;

        public String getNameGiven() {
            return nameGiven;
        }

        public void setNameGiven(String nameGiven) {
            this.nameGiven = nameGiven;
        }

        public String getNameFromAttribute() {
            return nameFromAttribute;
        }

        public void setNameFromAttribute(String nameFromAttribute) {
            this.nameFromAttribute = nameFromAttribute;
        }

        public String getVariableClass() {
            return variableClass;
        }

        public void setVariableClass(String variableClass) {
            this.variableClass = variableClass;
        }

        public boolean isDeclare() {
            return declare;
        }

        public void setDeclare(boolean declare) {
            this.declare = declare;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return "VariableDescriptor{nameGiven='" + nameGiven + "', variableClass='" + variableClass + "'}";
        }
    }

    /**
     * Represents an EL function definition.
     */
    public static class FunctionDescriptor {
        private String name;
        private String functionClass;
        private String functionSignature;
        private String description;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getFunctionClass() {
            return functionClass;
        }

        public void setFunctionClass(String functionClass) {
            this.functionClass = functionClass;
        }

        public String getFunctionSignature() {
            return functionSignature;
        }

        public void setFunctionSignature(String functionSignature) {
            this.functionSignature = functionSignature;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return "FunctionDescriptor{name='" + name + "', functionClass='" + functionClass + "'}";
        }
    }
}
