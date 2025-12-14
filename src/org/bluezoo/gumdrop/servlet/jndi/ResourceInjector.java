/*
 * ResourceInjector.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;

/**
 * Resource injection processor for Gumdrop servlet container.
 * 
 * <p>This class handles injection of JNDI resources into servlet instances
 * via the {@code @Resource} annotation. It supports both field and method
 * injection following Java EE conventions.
 * 
 * <p>Usage example:
 * <pre>
 * &#64;WebServlet("/data")
 * public class DataServlet extends HttpServlet &#123;
 *     &#64;Resource(name = "jdbc/MyDatabase")
 *     private DataSource dataSource;
 *     
 *     &#64;Resource(name = "jca/MyConnector")  
 *     private ConnectionFactory connFactory;
 *     
 *     // Method injection also supported
 *     &#64;Resource(name = "mail/MyMailSession")
 *     public void setMailSession(Session mailSession) &#123;
 *         this.mailSession = mailSession;
 *     &#125;
 * &#125;
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResourceInjector {
    
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.jndi.L10N");
    private static final Logger LOGGER = Logger.getLogger(ResourceInjector.class.getName());
    
    /**
     * Inject resources into the target object using @Resource annotations.
     * 
     * @param target the object to inject resources into
     * @param context the JNDI context for resource lookup
     * @throws ServletException if resource injection fails
     */
    public static void injectResources(Object target, JndiContext context) throws ServletException {
        if (target == null) {
            return;
        }
        
        Class<?> clazz = target.getClass();
        
        try {
            // Inject fields annotated with @Resource
            injectFields(target, clazz, context);
            
            // Inject methods annotated with @Resource
            injectMethods(target, clazz, context);
            
        } catch (Exception e) {
            String message = L10N.getString("err.resource_injection_failed");
            message = MessageFormat.format(message, clazz.getName());
            throw new ServletException(message, e);
        }
    }
    
    /**
     * Inject resources into fields annotated with @Resource.
     */
    private static void injectFields(Object target, Class<?> clazz, JndiContext context) 
            throws Exception {
        
        for (Field field : clazz.getDeclaredFields()) {
            javax.annotation.Resource resourceAnnotation = field.getAnnotation(javax.annotation.Resource.class);
            if (resourceAnnotation != null) {
                injectField(target, field, resourceAnnotation, context);
            }
        }
        
        // Process superclass fields
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            injectFields(target, superclass, context);
        }
    }
    
    /**
     * Inject resources into methods annotated with @Resource.
     */
    private static void injectMethods(Object target, Class<?> clazz, JndiContext context) 
            throws Exception {
        
        for (Method method : clazz.getDeclaredMethods()) {
            javax.annotation.Resource resourceAnnotation = method.getAnnotation(javax.annotation.Resource.class);
            if (resourceAnnotation != null) {
                injectMethod(target, method, resourceAnnotation, context);
            }
        }
        
        // Process superclass methods
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            injectMethods(target, superclass, context);
        }
    }
    
    /**
     * Inject a resource into a specific field.
     */
    private static void injectField(Object target, Field field, 
            javax.annotation.Resource resourceAnnotation, JndiContext context) throws Exception {
        
        // Determine JNDI name for lookup
        String jndiName = getJndiName(resourceAnnotation, field.getName(), field.getType());
        
        // Lookup the resource
        Object resource = lookupResource(jndiName, field.getType(), context);
        
        if (resource != null) {
            // Make field accessible and inject
            field.setAccessible(true);
            field.set(target, resource);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("debug.injected_field");
                message = MessageFormat.format(message, jndiName, field.getName(), 
                        target.getClass().getName());
                LOGGER.fine(message);
            }
        } else {
            // Check if resource is required
            if (isRequired(resourceAnnotation)) {
                String message = L10N.getString("err.resource_required");
                message = MessageFormat.format(message, jndiName);
                throw new ServletException(message);
            } else {
                if (LOGGER.isLoggable(Level.FINE)) {
                    String message = L10N.getString("debug.optional_resource_not_found");
                    message = MessageFormat.format(message, jndiName);
                    LOGGER.fine(message);
                }
            }
        }
    }
    
    /**
     * Inject a resource into a specific method.
     */
    private static void injectMethod(Object target, Method method, 
            javax.annotation.Resource resourceAnnotation, JndiContext context) throws Exception {
        
        // Validate setter method (must have exactly one parameter)
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != 1) {
            String message = L10N.getString("err.resource_method_params");
            message = MessageFormat.format(message, method.getName());
            throw new ServletException(message);
        }
        
        Class<?> resourceType = parameterTypes[0];
        
        // Determine JNDI name for lookup
        String jndiName = getJndiName(resourceAnnotation, getPropertyNameFromSetter(method.getName()), resourceType);
        
        // Lookup the resource
        Object resource = lookupResource(jndiName, resourceType, context);
        
        if (resource != null) {
            // Make method accessible and invoke
            method.setAccessible(true);
            method.invoke(target, resource);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("debug.injected_method");
                message = MessageFormat.format(message, jndiName, method.getName(), 
                        target.getClass().getName());
                LOGGER.fine(message);
            }
        } else {
            // Check if resource is required
            if (isRequired(resourceAnnotation)) {
                String message = L10N.getString("err.resource_required");
                message = MessageFormat.format(message, jndiName);
                throw new ServletException(message);
            } else {
                if (LOGGER.isLoggable(Level.FINE)) {
                    String message = L10N.getString("debug.optional_resource_not_found");
                    message = MessageFormat.format(message, jndiName);
                    LOGGER.fine(message);
                }
            }
        }
    }
    
    /**
     * Determine the JNDI name for resource lookup.
     */
    private static String getJndiName(javax.annotation.Resource resourceAnnotation, 
            String fallbackName, Class<?> resourceType) {
        // Use explicit name if provided
        if (!resourceAnnotation.name().isEmpty()) {
            return resourceAnnotation.name();
        }
        
        // Use mapped name if provided  
        if (!resourceAnnotation.mappedName().isEmpty()) {
            return resourceAnnotation.mappedName();
        }
        
        // Use lookup name if provided
        if (!resourceAnnotation.lookup().isEmpty()) {
            return resourceAnnotation.lookup();
        }
        
        // Generate default name based on type and field/property name
        return generateDefaultJndiName(resourceType, fallbackName);
    }
    
    /**
     * Generate a default JNDI name based on resource type and name.
     */
    private static String generateDefaultJndiName(Class<?> resourceType, String name) {
        String packageName = resourceType.getPackage().getName();
        
        if (packageName.startsWith("javax.sql")) {
            return "jdbc/" + name;
        } else if (packageName.startsWith("javax.jms")) {
            return "jms/" + name;
        } else if (packageName.startsWith("javax.mail")) {
            return "mail/" + name;
        } else if (packageName.startsWith("javax.resource")) {
            return "jca/" + name;
        } else {
            return name; // Use as-is for custom resources
        }
    }
    
    /**
     * Extract property name from setter method name.
     */
    private static String getPropertyNameFromSetter(String methodName) {
        if (methodName.startsWith("set") && methodName.length() > 3) {
            String propertyName = methodName.substring(3);
            return Character.toLowerCase(propertyName.charAt(0)) + propertyName.substring(1);
        }
        return methodName;
    }
    
    /**
     * Check if the resource is required (default is true).
     */
    private static boolean isRequired(javax.annotation.Resource resourceAnnotation) {
        // @Resource annotation doesn't have a 'required' attribute in standard Java EE
        // For this implementation, we'll assume all resources are required by default
        return true;
    }
    
    /**
     * Lookup a resource from JNDI.
     */
    private static Object lookupResource(String jndiName, Class<?> expectedType, JndiContext context) {
        try {
            InitialContext jndiContext = new InitialContext();
            
            // Try with java:comp/env/ prefix first
            String fullName = jndiName.startsWith("java:") ? jndiName : "java:comp/env/" + jndiName;
            
            Object resource = jndiContext.lookup(fullName);
            
            // Validate type compatibility
            if (resource != null && !expectedType.isAssignableFrom(resource.getClass())) {
                String message = L10N.getString("err.resource_type_mismatch");
                message = MessageFormat.format(message, jndiName, expectedType.getName(), 
                        resource.getClass().getName());
                LOGGER.warning(message);
                return null;
            }
            
            return resource;
            
        } catch (NamingException e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("debug.resource_not_found");
                message = MessageFormat.format(message, jndiName);
                LOGGER.log(Level.FINE, message, e);
            }
            return null;
        }
    }
}

