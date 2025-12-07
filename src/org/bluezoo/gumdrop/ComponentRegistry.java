/*
 * ComponentRegistry.java
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple dependency injection container for Gumdrop components.
 * Manages component lifecycle and dependency wiring without external dependencies.
 * 
 * <p>This registry provides:
 * <ul>
 * <li>Component registration by ID</li>
 * <li>Dependency resolution with circular detection</li>
 * <li>Singleton lifecycle management</li>
 * <li>Type-safe component retrieval</li>
 * <li>Automatic property injection via setters</li>
 * <li>Optional lifecycle methods (init/destroy)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ComponentRegistry {
    
    private static final Logger LOGGER = Logger.getLogger(ComponentRegistry.class.getName());
    
    private final Map<String, ComponentDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, Object> singletons = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<String>> constructing = new ThreadLocal<Set<String>>() {
        @Override
        protected Set<String> initialValue() {
            return new HashSet<>();
        }
    };
    
    /**
     * Register a component definition.
     * 
     * @param id the unique component identifier
     * @param definition the component definition
     * @throws IllegalArgumentException if id is null or already registered
     */
    public void register(String id, ComponentDefinition definition) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Component ID cannot be null or empty");
        }
        if (definitions.containsKey(id)) {
            throw new IllegalArgumentException("Component already registered with id: " + id);
        }
        definitions.put(id, definition);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Registered component: " + id + " (" + 
                       definition.getComponentClass().getName() + ")");
        }
    }
    
    /**
     * Get or create a component instance by ID.
     * 
     * @param id the component identifier
     * @param type the expected component type
     * @return the component instance
     * @throws IllegalArgumentException if no component registered with this id
     * @throws IllegalStateException if circular dependency detected
     * @throws ClassCastException if component is not of expected type
     */
    public <T> T getComponent(String id, Class<T> type) {
        ComponentDefinition def = definitions.get(id);
        if (def == null) {
            throw new IllegalArgumentException("No component registered with id: " + id);
        }
        
        if (def.isSingleton()) {
            Object singleton = singletons.get(id);
            if (singleton == null) {
                singleton = createComponent(def);
                singletons.put(id, singleton);
            }
            return type.cast(singleton);
        } else {
            return type.cast(createComponent(def));
        }
    }
    
    /**
     * Get a component instance by ID without type checking.
     * 
     * @param id the component identifier
     * @return the component instance
     * @throws IllegalArgumentException if no component registered with this id
     * @throws IllegalStateException if circular dependency detected
     */
    public Object getComponent(String id) {
        return getComponent(id, Object.class);
    }
    
    /**
     * Check if a component with the given ID is registered.
     * 
     * @param id the component identifier
     * @return true if registered, false otherwise
     */
    public boolean hasComponent(String id) {
        return definitions.containsKey(id);
    }
    
    /**
     * Get all components of a given type.
     * This will instantiate all matching components.
     * 
     * @param type the component type
     * @return collection of all matching components
     */
    public <T> Collection<T> getComponentsOfType(Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Map.Entry<String, ComponentDefinition> entry : definitions.entrySet()) {
            ComponentDefinition def = entry.getValue();
            if (type.isAssignableFrom(def.getComponentClass())) {
                result.add(getComponent(entry.getKey(), type));
            }
        }
        return result;
    }
    
    /**
     * Get all registered component IDs.
     * 
     * @return set of all component IDs
     */
    public Set<String> getComponentIds() {
        return definitions.keySet();
    }
    
    /**
     * Shutdown all singleton components by calling their destroy methods.
     */
    public void shutdown() {
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("Shutting down component registry (" + singletons.size() + " singletons)");
        }
        
        for (Map.Entry<String, Object> entry : singletons.entrySet()) {
            try {
                invokeLifecycleMethod(entry.getValue(), "destroy");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error destroying component: " + entry.getKey(), e);
            }
        }
        singletons.clear();
    }
    
    private Object createComponent(ComponentDefinition def) {
        String id = def.getId();
        
        // Circular dependency detection
        Set<String> currentlyConstructing = constructing.get();
        if (currentlyConstructing.contains(id)) {
            throw new IllegalStateException("Circular dependency detected: " + id);
        }
        currentlyConstructing.add(id);
        
        try {
            // Create instance
            Class<?> clazz = def.getComponentClass();
            Object instance = clazz.getDeclaredConstructor().newInstance();
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Creating component: " + id);
            }
            
            // Inject properties
            for (PropertyDefinition prop : def.getProperties()) {
                injectProperty(instance, prop);
            }
            
            // Call lifecycle init method if present
            invokeLifecycleMethod(instance, "init");
            
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create component: " + id, e);
        } finally {
            currentlyConstructing.remove(id);
        }
    }
    
    private void injectProperty(Object target, PropertyDefinition prop) throws Exception {
        String propertyName = prop.getName();
        
        // Special handling for Container's contexts property
        if (target.getClass().getName().equals("org.bluezoo.gumdrop.servlet.Container") &&
            "contexts".equals(propertyName)) {
            // Store the container in thread-local so Context creation can access it
            currentContainer.set(target);
        }
        
        Object value = resolveValue(prop.getValue());
        
        // Find setter method
        String methodName = "set" + Character.toUpperCase(propertyName.charAt(0)) 
                          + propertyName.substring(1);
        
        // Handle hyphenated property names (e.g., "keystore-file" -> "setKeystoreFile")
        methodName = toCamelCase(methodName);
        
        Method setter = findSetter(target.getClass(), methodName, value);
        if (setter != null) {
            // Convert value to match parameter type if needed
            Object convertedValue = convertValue(value, setter.getParameterTypes()[0]);
            setter.invoke(target, convertedValue);
            
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("Injected property " + propertyName + " on " + 
                             target.getClass().getSimpleName());
            }
        } else {
            LOGGER.warning("No setter found for property: " + propertyName + 
                          " on " + target.getClass().getName());
        }
        
        // Clear container reference after contexts property is set
        if (target.getClass().getName().equals("org.bluezoo.gumdrop.servlet.Container") &&
            "contexts".equals(propertyName)) {
            currentContainer.remove();
        }
    }
    
    private String toCamelCase(String name) {
        // Convert "setKeystore-File" to "setKeystoreFile"
        int hyphenIndex = name.indexOf('-');
        while (hyphenIndex != -1 && hyphenIndex < name.length() - 1) {
            name = name.substring(0, hyphenIndex) + 
                   Character.toUpperCase(name.charAt(hyphenIndex + 1)) + 
                   name.substring(hyphenIndex + 2);
            hyphenIndex = name.indexOf('-');
        }
        return name;
    }
    
    private Method findSetter(Class<?> clazz, String methodName, Object value) {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(methodName) && 
                method.getParameterCount() == 1) {
                
                Class<?> paramType = method.getParameterTypes()[0];
                
                // Check if value is null (accept any type)
                if (value == null) {
                    return method;
                }
                
                // Check if value is directly assignable
                if (paramType.isAssignableFrom(value.getClass())) {
                    return method;
                }
                
                // Check if we can convert to the parameter type
                if (canConvert(value, paramType)) {
                    return method;
                }
            }
        }
        return null;
    }
    
    private Object resolveValue(Object value) {
        if (value instanceof ComponentReference) {
            ComponentReference ref = (ComponentReference) value;
            return getComponent(ref.getRefId());
        } else if (value instanceof ComponentDefinition) {
            // Inline anonymous component
            return createComponent((ComponentDefinition) value);
        } else if (value instanceof ConfigurationParser.InlineContextInfo) {
            // Special handling for Context which needs constructor args
            return createContext((ConfigurationParser.InlineContextInfo) value);
        } else if (value instanceof ListValue) {
            ListValue list = (ListValue) value;
            List<Object> result = new ArrayList<>();
            for (Object item : list.getItems()) {
                result.add(resolveValue(item));
            }
            return result;
        } else if (value instanceof MapValue) {
            MapValue map = (MapValue) value;
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : map.getEntries().entrySet()) {
                result.put(entry.getKey(), resolveValue(entry.getValue()));
            }
            return result;
        }
        return value;
    }
    
    private Object createContext(ConfigurationParser.InlineContextInfo contextInfo) {
        try {
            // We need to get the parent Container from the call stack
            // This is a bit hacky but necessary for Context creation
            Object container = findContainerInStack();
            if (container == null) {
                throw new RuntimeException("Cannot create Context without parent Container");
            }
            
            // Create Context with constructor args
            Class<?> contextClass = Class.forName("org.bluezoo.gumdrop.servlet.Context");
            java.lang.reflect.Constructor<?> constructor = contextClass.getConstructor(
                Class.forName("org.bluezoo.gumdrop.servlet.Container"),
                String.class,
                java.io.File.class
            );
            Object context = constructor.newInstance(container, contextInfo.getPath(), contextInfo.getRoot());
            
            // Set additional properties
            for (Map.Entry<String, String> prop : contextInfo.getProperties().entrySet()) {
                String methodName = "set" + Character.toUpperCase(prop.getKey().charAt(0)) + 
                                   prop.getKey().substring(1);
                methodName = toCamelCase(methodName);
                
                Method setter = findSetter(context.getClass(), methodName, prop.getValue());
                if (setter != null) {
                    Object convertedValue = convertValue(prop.getValue(), setter.getParameterTypes()[0]);
                    setter.invoke(context, convertedValue);
                }
            }
            
            // Call load() method
            Method loadMethod = contextClass.getMethod("load");
            loadMethod.invoke(context);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Created Context: " + contextInfo.getPath() + " -> " + contextInfo.getRoot());
            }
            
            return context;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Context", e);
        }
    }
    
    private Object findContainerInStack() {
        // Look for a Container instance in the current call stack by examining
        // the instances being created. This is called during property injection,
        // so the Container should be in the process of being wired up.
        // Since we're in the middle of injecting the "contexts" property,
        // we need to return "this" from the injection context.
        // Unfortunately, we don't have direct access to it here.
        // Instead, we'll store it temporarily during component creation.
        return currentContainer.get();
    }
    
    // Thread-local to store the current container being created
    private final ThreadLocal<Object> currentContainer = new ThreadLocal<>();
    
    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        
        // Already correct type
        if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }
        
        // String conversions
        if (value instanceof String) {
            String str = (String) value;
            
            // Primitive and wrapper types
            if (targetType == int.class || targetType == Integer.class) {
                return Integer.parseInt(str);
            }
            if (targetType == long.class || targetType == Long.class) {
                return Long.parseLong(str);
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return Boolean.parseBoolean(str);
            }
            if (targetType == double.class || targetType == Double.class) {
                return Double.parseDouble(str);
            }
            if (targetType == float.class || targetType == Float.class) {
                return Float.parseFloat(str);
            }
            
            // Path conversion
            if (targetType == java.nio.file.Path.class) {
                return java.nio.file.Paths.get(str);
            }
            
            // File conversion
            if (targetType == java.io.File.class) {
                return new java.io.File(str);
            }
        }
        
        // Number conversions
        if (value instanceof Number) {
            Number num = (Number) value;
            if (targetType == int.class || targetType == Integer.class) {
                return num.intValue();
            }
            if (targetType == long.class || targetType == Long.class) {
                return num.longValue();
            }
            if (targetType == double.class || targetType == Double.class) {
                return num.doubleValue();
            }
            if (targetType == float.class || targetType == Float.class) {
                return num.floatValue();
            }
        }
        
        // No conversion available
        return value;
    }
    
    private boolean canConvert(Object value, Class<?> targetType) {
        if (value instanceof String) {
            return targetType == int.class || targetType == Integer.class ||
                   targetType == long.class || targetType == Long.class ||
                   targetType == boolean.class || targetType == Boolean.class ||
                   targetType == double.class || targetType == Double.class ||
                   targetType == float.class || targetType == Float.class ||
                   targetType == java.nio.file.Path.class ||
                   targetType == java.io.File.class;
        }
        if (value instanceof Number) {
            return targetType == int.class || targetType == Integer.class ||
                   targetType == long.class || targetType == Long.class ||
                   targetType == double.class || targetType == Double.class ||
                   targetType == float.class || targetType == Float.class;
        }
        return false;
    }
    
    private void invokeLifecycleMethod(Object instance, String methodName) {
        try {
            Method method = instance.getClass().getMethod(methodName);
            method.invoke(instance);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Invoked " + methodName + "() on " + 
                           instance.getClass().getSimpleName());
            }
        } catch (NoSuchMethodException e) {
            // Lifecycle method not present, skip
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke lifecycle method: " + methodName + 
                                     " on " + instance.getClass().getName(), e);
        }
    }
}

