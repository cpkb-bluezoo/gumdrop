/*
 * MockSessionContext.java
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

package org.bluezoo.gumdrop.servlet.session;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionListener;

/**
 * Mock SessionContext implementation for unit testing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class MockSessionContext implements SessionContext {

    private String contextPath = "/test";
    private int sessionTimeout = 1800; // 30 minutes
    private boolean distributable = false;
    private byte[] contextDigest = new byte[16];
    private ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    private Collection<HttpSessionAttributeListener> sessionAttributeListeners = new ArrayList<>();
    private Collection<HttpSessionListener> sessionListeners = new ArrayList<>();
    private Collection<HttpSessionActivationListener> sessionActivationListeners = new ArrayList<>();
    private Map<String, Object> attributes = new HashMap<>();

    // Setters for test configuration

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    public void setSessionTimeout(int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    public void setDistributable(boolean distributable) {
        this.distributable = distributable;
    }

    public void setContextDigest(byte[] contextDigest) {
        this.contextDigest = contextDigest;
    }

    public void setContextClassLoader(ClassLoader contextClassLoader) {
        this.contextClassLoader = contextClassLoader;
    }

    public void addSessionAttributeListener(HttpSessionAttributeListener listener) {
        sessionAttributeListeners.add(listener);
    }

    public void addSessionListener(HttpSessionListener listener) {
        sessionListeners.add(listener);
    }

    public void addSessionActivationListener(HttpSessionActivationListener listener) {
        sessionActivationListeners.add(listener);
    }

    // SessionContext implementation

    @Override
    public int getSessionTimeout() {
        return sessionTimeout;
    }

    @Override
    public boolean isDistributable() {
        return distributable;
    }

    @Override
    public byte[] getContextDigest() {
        return contextDigest;
    }

    @Override
    public ClassLoader getContextClassLoader() {
        return contextClassLoader;
    }

    @Override
    public Collection<HttpSessionAttributeListener> getSessionAttributeListeners() {
        return sessionAttributeListeners;
    }

    @Override
    public Collection<HttpSessionListener> getSessionListeners() {
        return sessionListeners;
    }

    @Override
    public Collection<HttpSessionActivationListener> getSessionActivationListeners() {
        return sessionActivationListeners;
    }

    // ServletContext implementation (minimal stubs for testing)

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public ServletContext getContext(String uripath) {
        return null;
    }

    @Override
    public int getMajorVersion() {
        return 3;
    }

    @Override
    public int getMinorVersion() {
        return 1;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 3;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 1;
    }

    @Override
    public String getMimeType(String file) {
        return null;
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        return null;
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        return null;
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        return null;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return null;
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name) {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Servlet getServlet(String name) throws ServletException {
        return null;
    }

    @Override
    @SuppressWarnings({"deprecation", "rawtypes"})
    public Enumeration getServlets() {
        return Collections.emptyEnumeration();
    }

    @Override
    @SuppressWarnings({"deprecation", "rawtypes"})
    public Enumeration getServletNames() {
        return Collections.emptyEnumeration();
    }

    @Override
    public void log(String msg) {
    }

    @Override
    @SuppressWarnings("deprecation")
    public void log(Exception exception, String msg) {
    }

    @Override
    public void log(String message, Throwable throwable) {
    }

    @Override
    public String getRealPath(String path) {
        return null;
    }

    @Override
    public String getServerInfo() {
        return "MockServer/1.0";
    }

    @Override
    public String getInitParameter(String name) {
        return null;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Enumeration getInitParameterNames() {
        return Collections.emptyEnumeration();
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        return false;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Enumeration getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object object) {
        attributes.put(name, object);
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public String getServletContextName() {
        return "TestContext";
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        return null;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        return null;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        return null;
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
        return null;
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        return null;
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return Collections.emptyMap();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        return null;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        return null;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        return null;
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
        return null;
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        return null;
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return Collections.emptyMap();
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        return null;
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return Collections.emptySet();
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return Collections.emptySet();
    }

    @Override
    public void addListener(String className) {
    }

    @Override
    public <T extends EventListener> void addListener(T t) {
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        return null;
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return null;
    }

    @Override
    public ClassLoader getClassLoader() {
        return contextClassLoader;
    }

    @Override
    public void declareRoles(String... roleNames) {
    }

    @Override
    public String getVirtualServerName() {
        return "localhost";
    }

    // Servlet 4.0 methods

    @Override
    public String getRequestCharacterEncoding() {
        return "UTF-8";
    }

    @Override
    public void setRequestCharacterEncoding(String encoding) {
    }

    @Override
    public String getResponseCharacterEncoding() {
        return "UTF-8";
    }

    @Override
    public void setResponseCharacterEncoding(String encoding) {
    }

    @Override
    public javax.servlet.ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        return null;
    }

}

