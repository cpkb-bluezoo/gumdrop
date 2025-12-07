/*
 * FilterDef.java
 * Copyright (C) 2005 Chris Burdess
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.annotation.WebFilter;
import javax.servlet.annotation.WebInitParam;

import org.bluezoo.gumdrop.servlet.manager.FilterReg;

/**
 * Filter definition.
 * This corresponds to a <code>filter</code> element in the web deployment
 * descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FilterDef implements FilterConfig, FilterReg {

    Context context;

    // Description
    String description;
    String displayName;
    String smallIcon;
    String largeIcon;

    String name; // filter-name
    String className; // filter-class
    Map<String,InitParam> initParams = new LinkedHashMap<>();
    boolean asyncSupported;

    long unavailableUntil;

    void init(WebFilter config, String className) {
        this.className = className;
        description = config.description();
        displayName = config.displayName();
        smallIcon = config.smallIcon();
        largeIcon = config.largeIcon();
        name = config.filterName();
        for (WebInitParam configInitParam : config.initParams()) {
            addInitParam(new InitParam(configInitParam));
        }
        asyncSupported = config.asyncSupported();
    }

    Filter newInstance() throws ServletException {
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        ClassLoader contextLoader = context.getContextClassLoader();
        try {
            thread.setContextClassLoader(contextLoader);
            Class t = contextLoader.loadClass(className);
            Filter filter = (Filter) t.newInstance();
            filter.init(this);
            return filter;
        } catch (UnavailableException e) {
            if (e.isPermanent()) {
                unavailableUntil = -1L;
            } else {
                unavailableUntil = System.currentTimeMillis() + ((long) e.getUnavailableSeconds() * 1000L);
            }
            throw e;
        } catch (Exception e) {
            String message = Context.L10N.getString("err.init_filter");
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

    // -- FilterConfig --

    @Override public ServletContext getServletContext() {
        return context;
    }

    @Override public String getFilterName() {
        return name;
    }

    @Override public String getInitParameter(String name) {
        InitParam initParam = initParams.get(name);
        return (initParam == null) ? null : initParam.value;
    }

    @Override public Enumeration<String> getInitParameterNames() {
        return new IteratorEnumeration(initParams.keySet());
    }

    // -- FilterRegistration.Dynamic --

    @Override public String getName() {
        return name;
    }

    @Override public String getClassName() {
        return className;
    }

    @Override public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... servletNames) {
        if (servletNames == null || servletNames.length == 0) {
            throw new IllegalArgumentException();
        }
        if (context.initialized) {
            throw new IllegalStateException();
        }
        // Can't use Set.of since we want to preserve order
        Set<String> unmapped = new LinkedHashSet<>(Arrays.asList(servletNames));
        FilterMapping filterMapping = new FilterMapping();
        filterMapping.filterDef = this;
        filterMapping.filterName = name;
        filterMapping.servletNames = unmapped;
        filterMapping.dispatchers = dispatcherTypes.clone();
        int index = indexOfFilterMapping(context.filterMappings, unmapped, true);
        if (index != -1) {
            if (isMatchAfter) {
                index = lastIndexOfFilterMapping(context.filterMappings, unmapped, true) + 1;
            }
            context.filterMappings.add(index, filterMapping);
        } else {
            context.filterMappings.add(filterMapping);
        }
    }

    @Override public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... urlPatterns) {
        if (urlPatterns == null || urlPatterns.length == 0) {
            throw new IllegalArgumentException();
        }
        if (context.initialized) {
            throw new IllegalStateException();
        }
        // Can't use Set.of since we want to preserve order
        Set<String> unmapped = new LinkedHashSet<>(Arrays.asList(urlPatterns));
        FilterMapping filterMapping = new FilterMapping();
        filterMapping.filterDef = this;
        filterMapping.filterName = name;
        filterMapping.urlPatterns = unmapped;
        filterMapping.dispatchers = dispatcherTypes.clone();
        int index = indexOfFilterMapping(context.filterMappings, unmapped, false);
        if (index != -1) {
            if (isMatchAfter) {
                index = lastIndexOfFilterMapping(context.filterMappings, unmapped, false) + 1;
            }
            context.filterMappings.add(index, filterMapping);
        } else {
            context.filterMappings.add(filterMapping);
        }
    }

    private int indexOfFilterMapping(List<FilterMapping> filterMappings, Collection<String> values, boolean matchServletName) {
        for (int i = 0; i < filterMappings.size(); i++) {
            FilterMapping filterMapping = filterMappings.get(i);
            if (containsAny(matchServletName ? filterMapping.servletNames : filterMapping.urlPatterns, values)) {
                return i;
            }
        }
        return -1;
    }

    private int lastIndexOfFilterMapping(List<FilterMapping> filterMappings, Collection<String> values, boolean matchServletName) {
        for (int i = filterMappings.size(); i >= 0; i--) {
            FilterMapping filterMapping = filterMappings.get(i);
            if (containsAny(matchServletName ? filterMapping.servletNames : filterMapping.urlPatterns, values)) {
                return i;
            }
        }
        return -1;
    }

    private boolean containsAny(Collection<String> collection, Collection<String> candidates) {
        for (String candidate : candidates) {
            if (collection.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    @Override public Collection<String> getServletNameMappings() {
        Collection<String> ret = new LinkedHashSet<>();
        for (FilterMapping filterMapping : context.filterMappings) {
            if (filterMapping.filterDef == this) {
                ret.addAll(filterMapping.servletNames);
            }
        }
        return ret;
    }

    @Override public Collection<String> getUrlPatternMappings() {
        Collection<String> ret = new LinkedHashSet<>();
        for (FilterMapping filterMapping : context.filterMappings) {
            if (filterMapping.filterDef == this) {
                ret.addAll(filterMapping.urlPatterns);
            }
        }
        return ret;
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

    // -- internal --

    void addInitParam(InitParam initParam) {
        initParams.put(initParam.name, initParam);
    }

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
