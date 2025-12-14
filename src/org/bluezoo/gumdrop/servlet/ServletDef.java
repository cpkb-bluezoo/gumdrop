/*
 * ServletDef.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

import org.bluezoo.gumdrop.util.IteratorEnumeration;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.security.RunAs;
import javax.servlet.HttpMethodConstraintElement;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletSecurityElement;
import javax.servlet.SingleThreadModel;
import javax.servlet.UnavailableException;
import javax.servlet.annotation.HttpConstraint;
import javax.servlet.annotation.HttpMethodConstraint;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;

import org.bluezoo.gumdrop.servlet.manager.ServletReg;

/**
 * Servlet definition.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ServletDef implements ServletConfig, Comparable, ServletReg {

    Context context;

    // Description
    String description;
    String displayName;
    String smallIcon;
    String largeIcon;

    String name;
    String className;
    String jspFile;
    int loadOnStartup = -1;
    Map<String,InitParam> initParams = new LinkedHashMap<>();
    String runAs;
    SecurityRole securityRoleRef;
    MultipartConfigDef multipartConfig;
    boolean asyncSupported;

    Set<SecurityConstraint> servletSecurity;

    long unavailableUntil;

    void init(WebServlet config, String className) {
        this.className = className;
        description = config.description();
        displayName = config.displayName();
        smallIcon = config.smallIcon();
        largeIcon = config.largeIcon();
        name = config.name();
        loadOnStartup = config.loadOnStartup();
        for (WebInitParam configInitParam : config.initParams()) {
            addInitParam(new InitParam(configInitParam));
        }
        asyncSupported = config.asyncSupported();
    }

    void init(MultipartConfig config) {
        multipartConfig = new MultipartConfigDef();
        multipartConfig.init(config);
    }

    void init(ServletSecurity config) {
        servletSecurity = new LinkedHashSet<>();
        Set<String> methods = new LinkedHashSet<>();
        for (HttpMethodConstraint httpMethodConstraint : config.httpMethodConstraints()) {
            String method = httpMethodConstraint.value();
            methods.add(method);
            SecurityConstraint methodConstraint = new SecurityConstraint();
            methodConstraint.init(httpMethodConstraint);
            servletSecurity.add(methodConstraint);
        }
        SecurityConstraint defaultMethodConstraint = new SecurityConstraint();
        defaultMethodConstraint.init(config.value(), methods);
        servletSecurity.add(defaultMethodConstraint);
    }

    void init(RunAs config) {
        runAs = config.value();
    }

    /**
     * Compare for sorting according to the value of loadOnStartup
     */
    @Override public int compareTo(Object other) {
        if (other instanceof ServletDef) {
            ServletDef servletDef = (ServletDef) other;
            return loadOnStartup - servletDef.loadOnStartup;
        }
        return 0;
    }

    /**
     * Create a new servlet instance.
     */
    Servlet newInstance() throws ServletException {
        if (jspFile != null) {
            Servlet servlet = context.parseJSPFile(jspFile);
            servlet.init(this);
            return servlet;
        }
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        ClassLoader contextLoader = context.getContextClassLoader();
        try {
            thread.setContextClassLoader(contextLoader);
            Class t = contextLoader.loadClass(className);
            Servlet servlet = (Servlet) t.newInstance();
            servlet.init(this);
            return servlet;
        } catch (UnavailableException e) {
            if (e.isPermanent()) {
                unavailableUntil = -1L;
            } else {
                unavailableUntil = System.currentTimeMillis() + ((long) e.getUnavailableSeconds() * 1000L);
            }
            throw e;
        } catch (Exception e) {
            String message = Context.L10N.getString("err.init_servlet");
            message = MessageFormat.format(message, name);
            ServletException e2 = new ServletException(message);
            e2.initCause(e);
            throw e2;
        } finally {
            thread.setContextClassLoader(loader);
        }
    }

    // -- Description --

    @Override public String getDescription() {
        return description;
    }

    @Override public void setDescription(String description) {
        this.description = description;
    }

    @Override public String getDisplayName() {
        return displayName;
    }

    @Override public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override public String getSmallIcon() {
        return smallIcon;
    }

    @Override public void setSmallIcon(String smallIcon) {
        this.smallIcon = smallIcon;
    }

    @Override public String getLargeIcon() {
        return largeIcon;
    }

    @Override public void setLargeIcon(String largeIcon) {
        this.largeIcon = largeIcon;
    }

    // -- ServletConfig --

    @Override public ServletContext getServletContext() {
        return context;
    }

    @Override public String getServletName() {
        return name;
    }

    @Override public Enumeration<String> getInitParameterNames() {
        return new IteratorEnumeration<>(initParams.keySet().iterator());
    }

    // -- ServletRegistration.Dynamic --

    @Override public void setLoadOnStartup(int loadOnStartup) {
        this.loadOnStartup = loadOnStartup;
    }

    @Override public Set<String> setServletSecurity(ServletSecurityElement constraint) {
        Set<String> ret = new HashSet<>();
        for (ServletMapping mapping : context.servletMappings) {
            if (mapping.servletDef == this) { // servlet mapping for this servlet
                for (String urlPattern : mapping.urlPatterns) {
                    if (context.isSecurityConstraintTarget(urlPattern)) {
                        ret.add(urlPattern);
                    }
                }
            }
        }
        
        // If not all patterns are already constrained, add security constraints
        if (ret.isEmpty() || ret.size() < getTotalUrlPatternCount()) {
            // Initialize servletSecurity if needed
            if (servletSecurity == null) {
                servletSecurity = new LinkedHashSet<SecurityConstraint>();
            }
            
            // Add method-specific constraints
            Set<String> methods = new LinkedHashSet<String>();
            for (HttpMethodConstraintElement methodConstraint : constraint.getHttpMethodConstraints()) {
                String method = methodConstraint.getMethodName();
                methods.add(method);
                SecurityConstraint sc = new SecurityConstraint();
                sc.init(methodConstraint);
                servletSecurity.add(sc);
            }
            
            // Add default constraint for other methods
            // ServletSecurityElement extends HttpConstraintElement, so use it directly
            SecurityConstraint defaultConstraint = new SecurityConstraint();
            defaultConstraint.init(constraint, methods);
            servletSecurity.add(defaultConstraint);
        }
        
        return Collections.unmodifiableSet(ret);
    }
    
    private int getTotalUrlPatternCount() {
        int count = 0;
        for (ServletMapping mapping : context.servletMappings) {
            if (mapping.servletDef == this) {
                count += mapping.urlPatterns.size();
            }
        }
        return count;
    }

    @Override public void setMultipartConfig(MultipartConfigElement multipartConfig) {
        if (this.multipartConfig == null) {
            this.multipartConfig = new MultipartConfigDef();
        }
        this.multipartConfig.location = multipartConfig.getLocation();
        this.multipartConfig.maxFileSize = multipartConfig.getMaxFileSize();
        this.multipartConfig.maxRequestSize = multipartConfig.getMaxRequestSize();
        this.multipartConfig.fileSizeThreshold = (long) multipartConfig.getFileSizeThreshold();
    }

    @Override public void setRunAsRole(String roleName) {
        runAs = roleName;
    }

    @Override public Set<String> addMapping(String... urlPatterns) {
        if (urlPatterns == null || urlPatterns.length == 0) {
            throw new IllegalArgumentException();
        }
        if (context.initialized) {
            throw new IllegalStateException();
        }
        Set<String> mapped = new HashSet<>();
        Set<String> unmapped = new LinkedHashSet<>();
        for (String urlPattern : urlPatterns) {
            unmapped.add(urlPattern);
            for (ServletMapping servletMapping : context.servletMappings) {
                if (servletMapping.urlPatterns.contains(urlPattern) && servletMapping.servletDef != this) {
                    mapped.add(urlPattern);
                    break;
                }
            }
        }
        if (mapped.isEmpty()) {
            ServletMapping servletMapping = new ServletMapping();
            servletMapping.servletDef = this;
            servletMapping.servletName = name;
            servletMapping.urlPatterns = unmapped;
            context.servletMappings.add(servletMapping);
        }
        return Collections.unmodifiableSet(mapped);
    }

    @Override public Collection<String> getMappings() {
        Set<String> acc = new LinkedHashSet<>();
        for (ServletMapping servletMapping : context.servletMappings) {
            if (servletMapping.servletDef == this) {
                acc.addAll(servletMapping.urlPatterns);
            }
        }
        return Collections.unmodifiableSet(acc);
    }

    @Override public String getRunAsRole() {
        return runAs;
    }

    @Override public String getName() {
        return name;
    }

    @Override public String getClassName() {
        return className;
    }

    @Override public boolean setInitParameter(String name, String value) {
        if (name == null || value == null) {
            throw new IllegalArgumentException();
        }
        if (context.initialized) {
            throw new IllegalStateException();
        }
        InitParam initParam = initParams.get(name);
        if (initParam != null) {
            return false; // spec seems to say we should not update it
        }
        initParam = new InitParam();
        initParam.name = name;
        initParam.value = value;
        initParams.put(name, initParam);
        return true;
    }

    @Override public String getInitParameter(String name) {
        InitParam initParam = initParams.get(name);
        return (initParam == null) ? null : initParam.value;
    }

    @Override public Set<String> setInitParameters(Map<String,String> initParameters) {
        Set<String> ret = new HashSet<>();
        for (Map.Entry<String,String> entry : initParameters.entrySet()) {
            String name = entry.getKey();
            if (!setInitParameter(name, entry.getValue())) {
                ret.add(name);
            }
        }
        return Collections.unmodifiableSet(ret);
    }

    @Override public Map<String,String> getInitParameters() {
        Map<String,String> ret = new LinkedHashMap<>();
        for (InitParam initParam : initParams.values()) {
            ret.put(initParam.name, initParam.value);
        }
        return Collections.unmodifiableMap(ret);
    }

    @Override public void setAsyncSupported(boolean flag) {
        asyncSupported = flag;
    }

    void addInitParam(InitParam initParam) {
        initParams.put(initParam.name, initParam);
    }

    // -- Debug --

    public String toString() {
        StringBuffer buf = new StringBuffer(getClass().getName());
        buf.append("[name=");
        buf.append(name);
        buf.append(",className=");
        buf.append(className);
        buf.append("]");
        return buf.toString();
    }

}
