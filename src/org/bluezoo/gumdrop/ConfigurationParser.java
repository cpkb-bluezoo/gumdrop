/*
 * NewConfigurationParser.java
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

package org.bluezoo.gumdrop;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SAX-based parser for the gumdroprc configuration file.
 * Supports dependency injection format with component references
 * and nested properties.
 * 
 * <p>Configuration format features:
 * <ul>
 * <li>Component registration with id attributes</li>
 * <li>References using #id syntax</li>
 * <li>Nested property elements</li>
 * <li>List and map property values</li>
 * <li>Inline component definitions</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConfigurationParser extends DefaultHandler {
    
    private static final Logger LOGGER = Logger.getLogger(ConfigurationParser.class.getName());

    /** Default class names for configuration element types. */
    private static final Map<String, String> DEFAULT_CLASS_NAMES;
    static {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("server", "org.bluezoo.gumdrop.http.HTTPServer");
        map.put("realm", "org.bluezoo.gumdrop.BasicRealm");
        map.put("container", "org.bluezoo.gumdrop.servlet.Container");
        map.put("context", "org.bluezoo.gumdrop.servlet.Context");
        map.put("data-source", "org.bluezoo.gumdrop.servlet.DataSourceDef");
        map.put("mail-session", "org.bluezoo.gumdrop.servlet.MailSession");
        map.put("jms-connection-factory", "org.bluezoo.gumdrop.servlet.JmsConnectionFactory");
        map.put("jms-destination", "org.bluezoo.gumdrop.servlet.JmsDestination");
        map.put("connection-factory", "org.bluezoo.gumdrop.servlet.ConnectionFactory");
        map.put("administered-object", "org.bluezoo.gumdrop.servlet.AdministeredObject");
        map.put("mailbox-factory", "org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxFactory");
        map.put("smtp-handler-factory", "org.bluezoo.gumdrop.smtp.SMTPConnectionHandlerFactory");
        map.put("ftp-handler-factory", "org.bluezoo.gumdrop.ftp.FTPConnectionHandlerFactory");
        map.put("component", "java.lang.Object");
        DEFAULT_CLASS_NAMES = map;
    }

    private ComponentRegistry registry;
    private Locator locator;
    private Deque<ParseContext> contextStack = new ArrayDeque<>();
    
    // Current parsing state
    private ComponentDefinition currentComponent;
    private String currentPropertyName;
    private Object currentPropertyValue;
    private StringBuilder textContent = new StringBuilder();
    
    /**
     * Parse a configuration file and return the result.
     * 
     * @param file the configuration file
     * @return the parse result containing the component registry
     * @throws SAXException if parsing fails
     * @throws IOException if file cannot be read
     */
    public ParseResult parse(File file) throws SAXException, IOException {
        registry = new ComponentRegistry();
        
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(file));
            InputSource source = new InputSource(in);
            source.setSystemId(file.toURI().toString());
            
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser parser = factory.newSAXParser();
            parser.parse(source, this);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Parsed configuration file: " + file + 
                           " (" + registry.getComponentIds().size() + " components)");
            }
            
            return new ParseResult(registry);
        } catch (Exception e) {
            if (e instanceof SAXException) {
                throw (SAXException) e;
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            SAXException se = new SAXException("Failed to parse configuration file", e);
            throw se;
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }
    
    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
    }
    
    @Override
    public void startElement(String uri, String localName, String qName, 
                            Attributes atts) throws SAXException {
        String name = localName != null && !localName.isEmpty() ? localName : qName;
        textContent.setLength(0); // Reset text buffer
        
        ParseContext currentContext = contextStack.isEmpty() ? null : contextStack.peek();
        
        if ("gumdrop".equals(name)) {
            // Root element
            contextStack.push(new RootContext());
        } else if (isComponentElement(name)) {
            startComponent(name, atts);
        } else if (isInlineContextElement(name)) {
            // Special handling for Context which requires constructor args
            startInlineContext(atts);
        } else if ("property".equals(name)) {
            startProperty(atts);
        } else if ("ref".equals(name)) {
            handleReference(atts);
        } else if ("list".equals(name)) {
            startList();
        } else if ("map".equals(name)) {
            startMap();
        } else if ("entry".equals(name)) {
            startMapEntry(atts);
        } else if (currentContext instanceof PropertyContext) {
            // Inline component definition within a property
            startInlineComponent(name, atts);
        } else {
            // Unknown element - log warning
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("Unknown element: " + name + " at line " + 
                              (locator != null ? locator.getLineNumber() : "?"));
            }
        }
    }
    
    @Override
    public void characters(char[] ch, int start, int length) {
        textContent.append(ch, start, length);
    }
    
    @Override
    public void endElement(String uri, String localName, String qName) 
            throws SAXException {
        String name = localName != null && !localName.isEmpty() ? localName : qName;
        
        if ("gumdrop".equals(name)) {
            contextStack.pop();
        } else if (isComponentElement(name)) {
            endComponent();
        } else if (isInlineContextElement(name)) {
            endInlineContext();
        } else if ("property".equals(name)) {
            endProperty();
        } else if ("list".equals(name)) {
            endList();
        } else if ("map".equals(name)) {
            endMap();
        }
        
        textContent.setLength(0);
    }
    
    private boolean isComponentElement(String name) {
        return "server".equals(name) || 
               "realm".equals(name) || 
               "container".equals(name) ||
               "data-source".equals(name) ||
               "mail-session".equals(name) ||
               "jms-connection-factory".equals(name) ||
               "jms-destination".equals(name) ||
               "connection-factory".equals(name) ||
               "administered-object".equals(name) ||
               "mailbox-factory".equals(name) ||
               "smtp-handler-factory".equals(name) ||
               "ftp-handler-factory".equals(name);
    }
    
    private boolean isInlineContextElement(String name) {
        return "context".equals(name);
    }
    
    private void startComponent(String type, Attributes atts) throws SAXException {
        String id = atts.getValue("id");
        String className = atts.getValue("class");
        
        // Map element name to default class if not specified
        if (className == null) {
            className = getDefaultClassName(type);
        }
        
        try {
            Class<?> clazz = Class.forName(className);
            currentComponent = new ComponentDefinition(id, clazz);
            
            // Process simple attributes as properties
            for (int i = 0; i < atts.getLength(); i++) {
                String attrName = getAttributeName(atts, i);
                if (!"id".equals(attrName) && !"class".equals(attrName)) {
                    String attrValue = atts.getValue(i);
                    currentComponent.addProperty(
                        new PropertyDefinition(attrName, attrValue));
                }
            }
            
            contextStack.push(new ComponentContext(currentComponent));
        } catch (ClassNotFoundException e) {
            throw new SAXParseException("Class not found: " + className + 
                                       " at line " + locator.getLineNumber(), locator, e);
        }
    }
    
    private void startInlineComponent(String type, Attributes atts) throws SAXException {
        // Inline component without an id (anonymous)
        String className = atts.getValue("class");
        if (className == null) {
            className = getDefaultClassName(type);
        }
        
        try {
            Class<?> clazz = Class.forName(className);
            ComponentDefinition inlineComponent = new ComponentDefinition(null, clazz);
            
            // Process attributes as properties
            for (int i = 0; i < atts.getLength(); i++) {
                String attrName = getAttributeName(atts, i);
                if (!"class".equals(attrName)) {
                    inlineComponent.addProperty(
                        new PropertyDefinition(attrName, atts.getValue(i)));
                }
            }
            
            contextStack.push(new ComponentContext(inlineComponent));
            currentPropertyValue = inlineComponent; // Will be instantiated on demand
        } catch (ClassNotFoundException e) {
            throw new SAXParseException("Class not found: " + className + 
                                       " at line " + locator.getLineNumber(), locator, e);
        }
    }
    
    private void endComponent() {
        ComponentContext ctx = (ComponentContext) contextStack.pop();
        ComponentDefinition component = ctx.component;
        
        // Register if it has an id
        if (component.getId() != null) {
            registry.register(component.getId(), component);
        }
        
        // If we're inside a property context, this is an inline component
        ParseContext parent = contextStack.isEmpty() ? null : contextStack.peek();
        if (parent instanceof PropertyContext) {
            currentPropertyValue = component;
        }
        
        currentComponent = getParentComponent();
    }
    
    private ComponentDefinition getParentComponent() {
        for (ParseContext ctx : contextStack) {
            if (ctx instanceof ComponentContext) {
                return ((ComponentContext) ctx).component;
            }
        }
        return null;
    }
    
    private void startInlineContext(Attributes atts) throws SAXException {
        // Context needs special handling because it requires constructor args
        // and must call load() after creation
        String path = atts.getValue("path");
        String root = atts.getValue("root");
        
        if (root == null || root.isEmpty()) {
            throw new SAXParseException("Context requires 'root' attribute at line " + 
                                       locator.getLineNumber(), locator);
        }
        
        if (path == null) {
            path = "";
        }
        
        // Expand ~ in root path
        if (root.length() > 0 && root.charAt(0) == '~') {
            root = System.getProperty("user.home") + root.substring(1);
        }
        
        // Get parent container
        ComponentDefinition parentComponent = getParentComponent();
        if (parentComponent == null || 
            !org.bluezoo.gumdrop.servlet.Container.class.isAssignableFrom(parentComponent.getComponentClass())) {
            throw new SAXParseException("Context must be defined within a Container at line " + 
                                       locator.getLineNumber(), locator);
        }
        
        try {
            // Store context creation info for later
            InlineContextInfo contextInfo = new InlineContextInfo(path, new java.io.File(root));
            
            // Process other attributes (like distributable)
            for (int i = 0; i < atts.getLength(); i++) {
                String attrName = getAttributeName(atts, i);
                if (!"path".equals(attrName) && !"root".equals(attrName)) {
                    String attrValue = atts.getValue(i);
                    contextInfo.addProperty(attrName, attrValue);
                }
            }
            
            contextStack.push(new InlineContextContext(contextInfo));
        } catch (Exception e) {
            throw new SAXParseException("Failed to create context at line " + 
                                       locator.getLineNumber(), locator, e);
        }
    }
    
    private void endInlineContext() {
        InlineContextContext ctx = (InlineContextContext) contextStack.pop();
        InlineContextInfo contextInfo = ctx.contextInfo;
        
        // Add to current property value (should be inside a list)
        ParseContext parent = contextStack.isEmpty() ? null : contextStack.peek();
        if (parent instanceof ListContext) {
            ((ListContext) parent).value.addItem(contextInfo);
        } else if (parent instanceof PropertyContext) {
            currentPropertyValue = contextInfo;
        }
    }
    
    private void startProperty(Attributes atts) {
        currentPropertyName = atts.getValue("name");
        String refAttr = atts.getValue("ref");
        
        if (refAttr != null) {
            // Property value is a reference
            currentPropertyValue = parseReference(refAttr);
        } else {
            // Property value will come from text content or child elements
            currentPropertyValue = null;
        }
        
        contextStack.push(new PropertyContext(currentPropertyName));
    }
    
    private void endProperty() {
        contextStack.pop();
        
        // If no value set by child elements, use text content
        if (currentPropertyValue == null) {
            String text = textContent.toString().trim();
            if (!text.isEmpty()) {
                currentPropertyValue = text;
            }
        }
        
        if (currentPropertyValue != null && currentComponent != null) {
            currentComponent.addProperty(
                new PropertyDefinition(currentPropertyName, currentPropertyValue));
        }
        
        currentPropertyName = null;
        currentPropertyValue = null;
    }
    
    private void handleReference(Attributes atts) {
        String refAttr = atts.getValue("ref");
        if (refAttr == null) {
            refAttr = textContent.toString().trim();
        }
        
        ComponentReference ref = parseReference(refAttr);
        
        ParseContext context = contextStack.peek();
        if (context instanceof ListContext) {
            ((ListContext) context).value.addItem(ref);
        } else if (context instanceof PropertyContext) {
            currentPropertyValue = ref;
        }
    }
    
    private ComponentReference parseReference(String refAttr) {
        // Remove leading # if present
        String refId = refAttr.startsWith("#") ? refAttr.substring(1) : refAttr;
        return new ComponentReference(refId);
    }
    
    private void startList() {
        ListValue list = new ListValue();
        contextStack.push(new ListContext(list));
    }
    
    private void endList() {
        ListContext ctx = (ListContext) contextStack.pop();
        
        ParseContext parent = contextStack.isEmpty() ? null : contextStack.peek();
        if (parent instanceof PropertyContext) {
            currentPropertyValue = ctx.value;
        }
    }
    
    private void startMap() {
        MapValue map = new MapValue();
        contextStack.push(new MapContext(map));
    }
    
    private void endMap() {
        MapContext ctx = (MapContext) contextStack.pop();
        
        ParseContext parent = contextStack.isEmpty() ? null : contextStack.peek();
        if (parent instanceof PropertyContext) {
            currentPropertyValue = ctx.value;
        }
    }
    
    private void startMapEntry(Attributes atts) {
        String key = atts.getValue("key");
        String refAttr = atts.getValue("ref");
        String valueAttr = atts.getValue("value");
        
        MapContext mapContext = (MapContext) contextStack.peek();
        
        if (refAttr != null) {
            ComponentReference ref = parseReference(refAttr);
            mapContext.value.put(key, ref);
        } else if (valueAttr != null) {
            // Simple string value from attribute
            mapContext.value.put(key, valueAttr);
        } else {
            // Value will come from child elements or text
            mapContext.pendingKey = key;
        }
    }
    
    private String getDefaultClassName(String elementName) {
        String className = DEFAULT_CLASS_NAMES.get(elementName);
        if (className == null) {
            throw new IllegalArgumentException("No default class for element: " + elementName);
        }
        return className;
    }
    
    private String getAttributeName(Attributes atts, int index) {
        String name = atts.getLocalName(index);
        return name != null && !name.isEmpty() ? name : atts.getQName(index);
    }
    
    // Parse context classes for tracking parser state
    private static abstract class ParseContext {}
    
    private static class RootContext extends ParseContext {}
    
    private static class ComponentContext extends ParseContext {
        final ComponentDefinition component;
        ComponentContext(ComponentDefinition component) {
            this.component = component;
        }
    }
    
    private static class PropertyContext extends ParseContext {
        final String name;
        PropertyContext(String name) {
            this.name = name;
        }
    }
    
    private static class ListContext extends ParseContext {
        final ListValue value;
        ListContext(ListValue value) {
            this.value = value;
        }
    }
    
    private static class MapContext extends ParseContext {
        final MapValue value;
        String pendingKey;
        MapContext(MapValue value) {
            this.value = value;
        }
    }
    
    private static class InlineContextContext extends ParseContext {
        final InlineContextInfo contextInfo;
        InlineContextContext(InlineContextInfo contextInfo) {
            this.contextInfo = contextInfo;
        }
    }
    
    /**
     * Information for creating a Context inline.
     * Context requires special handling because it needs constructor arguments
     * and must call load() after creation.
     */
    public static class InlineContextInfo {
        private final String path;
        private final java.io.File root;
        private final Map<String, String> properties = new java.util.LinkedHashMap<>();
        
        public InlineContextInfo(String path, java.io.File root) {
            this.path = path;
            this.root = root;
        }
        
        public void addProperty(String name, String value) {
            properties.put(name, value);
        }
        
        public String getPath() {
            return path;
        }
        
        public java.io.File getRoot() {
            return root;
        }
        
        public Map<String, String> getProperties() {
            return properties;
        }
    }
}

