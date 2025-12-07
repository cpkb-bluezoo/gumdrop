/*
 * AdministeredObject.java
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

package org.bluezoo.gumdrop.servlet;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import javax.servlet.ServletException;
import org.xml.sax.Attributes;

/**
 * A JCA administered object.
 * Corresponds to an <code>administered-object</code> element in a
 * deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class AdministeredObject extends Resource implements Injectable {

    String description;
    String jndiName;
    String administeredObjectInterface;
    String administeredObjectClass;
    Map<String,String> properties = new LinkedHashMap<>();

    // Injectable
    String lookupName;
    InjectionTarget injectionTarget;
    String mappedName;

    // -- Resource --

    @Override public void addProperty(String name, String value) {
        properties.put(name, value);
    }

    @Override public void init(Attributes config) {
        description = config.getValue("description");
        jndiName = config.getValue("jndi-name");
        administeredObjectInterface = config.getValue("administered-object-interface");
        administeredObjectClass = config.getValue("administered-object-class");
    }

    // -- Injectable --

    @Override public String getLookupName() {
        return lookupName;
    }

    @Override public void setLookupName(String lookupName) {
        this.lookupName = lookupName;
    }

    @Override public String getMappedName() {
        return mappedName;
    }

    @Override public void setMappedName(String mappedName) {
        this.mappedName = mappedName;
    }

    @Override public InjectionTarget getInjectionTarget() {
        return injectionTarget;
    }

    @Override public void setInjectionTarget(InjectionTarget injectionTarget) {
        this.injectionTarget = injectionTarget;
    }

    // -- Resource --

    @Override String getName() {
        return jndiName;
    }

    @Override String getClassName() {
        return administeredObjectClass;
    }

    @Override String getInterfaceName() {
        return administeredObjectInterface;
    }

    @Override Object newInstance() {
        if (administeredObjectClass == null) {
            String message = "No administered-object-class specified for: " + jndiName;
            Context.LOGGER.log(Level.SEVERE, message);
            return null;
        }
        
        try {
            // Load the administered object class
            Class<?> clazz = Class.forName(administeredObjectClass);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            
            // Set properties via reflection (bean-style property setting)
            setProperties(instance, properties);
            
            return instance;
            
        } catch (Exception e) {
            String message = "Failed to create administered object: " + jndiName;
            Context.LOGGER.log(Level.SEVERE, message, e);
            return null;
        }
    }
    
    /**
     * Set properties on the administered object using JavaBean conventions.
     */
    private void setProperties(Object target, Map<String,String> properties) throws Exception {
        Class<?> clazz = target.getClass();
        
        for (Map.Entry<String,String> entry : properties.entrySet()) {
            String propertyName = entry.getKey();
            String propertyValue = entry.getValue();
            
            try {
                // Convert property name to setter method name (e.g., "serverUrl" -> "setServerUrl")
                String setterName = "set" + Character.toUpperCase(propertyName.charAt(0)) + 
                                   propertyName.substring(1);
                
                // Try common property types
                setProperty(target, clazz, setterName, propertyValue);
                
            } catch (Exception e) {
                // Log warning but continue - some properties may be optional
                Context.LOGGER.warning("Failed to set property " + propertyName + 
                                     " on " + administeredObjectClass + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Set a single property with type conversion.
     */
    private void setProperty(Object target, Class<?> clazz, String setterName, String value) 
            throws Exception {
        
        // Try different parameter types in order of likelihood
        Class<?>[] parameterTypes = {
            String.class,           // Most common
            int.class, Integer.class,
            boolean.class, Boolean.class,
            long.class, Long.class,
            double.class, Double.class,
            float.class, Float.class
        };
        
        Exception lastException = null;
        
        for (Class<?> paramType : parameterTypes) {
            try {
                java.lang.reflect.Method setter = clazz.getMethod(setterName, paramType);
                Object convertedValue = convertValue(value, paramType);
                setter.invoke(target, convertedValue);
                return; // Success
            } catch (NoSuchMethodException e) {
                // Try next parameter type
                lastException = e;
            }
        }
        
        // If we get here, no suitable setter was found
        throw new Exception("No suitable setter method found for property: " + setterName, lastException);
    }
    
    /**
     * Convert string value to the target type.
     */
    private Object convertValue(String value, Class<?> targetType) {
        if (targetType == String.class) {
            return value;
        } else if (targetType == int.class || targetType == Integer.class) {
            return Integer.valueOf(value);
        } else if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.valueOf(value);
        } else if (targetType == long.class || targetType == Long.class) {
            return Long.valueOf(value);
        } else if (targetType == double.class || targetType == Double.class) {
            return Double.valueOf(value);
        } else if (targetType == float.class || targetType == Float.class) {
            return Float.valueOf(value);
        } else {
            throw new IllegalArgumentException("Unsupported property type: " + targetType);
        }
    }

}
