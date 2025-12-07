/*
 * PageContext.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package javax.servlet.jsp;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

/**
 * Minimal JSP PageContext implementation for Gumdrop JSP support.
 * 
 * <p>This provides the essential functionality of the JSP PageContext class,
 * which serves as a repository for JSP page-scope objects and provides access
 * to servlet API objects.</p>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class PageContext {

    /**
     * Page scope: (this is the default) - search only in page
     */
    public static final int PAGE_SCOPE = 1;

    /**
     * Request scope: search only in request  
     */
    public static final int REQUEST_SCOPE = 2;

    /**
     * Session scope: search only in session
     */
    public static final int SESSION_SCOPE = 3;

    /**
     * Application scope: search only in application
     */
    public static final int APPLICATION_SCOPE = 4;

    /**
     * Initialize this PageContext so that it may be used by a JSP
     * Implementation class to service an incoming request and response within
     * the current thread.
     */
    public abstract void initialize(Servlet servlet, ServletRequest request,
                                   ServletResponse response, String errorPageURL, 
                                   boolean needsSession, int bufferSize, 
                                   boolean autoFlush) throws IOException;

    /**
     * This method is called to release all allocated resources.
     */
    public abstract void release();

    /**
     * Return the current value of the session object (HttpSession).
     */
    public abstract HttpSession getSession();

    /**
     * Return the current value of the page object (Servlet).
     */
    public abstract Object getPage();

    /**
     * Return the current value of the request object (ServletRequest).
     */
    public abstract ServletRequest getRequest();

    /**
     * Return the current value of the response object (ServletResponse).
     */
    public abstract ServletResponse getResponse();

    /**
     * Return the current value of the exception object (Exception).
     */
    public abstract Exception getException();

    /**
     * Return the current value of the config object (ServletConfig).
     */
    public abstract ServletConfig getServletConfig();

    /**
     * Return the current value of the context object (ServletContext).
     */
    public abstract ServletContext getServletContext();

    /**
     * Return the current JspWriter stream being used for client response.
     */
    public abstract JspWriter getOut();

    /**
     * Register the name and value specified with page scope semantics.
     */
    public abstract void setAttribute(String name, Object attribute);

    /**
     * Register the name and value specified with appropriate scope semantics.
     */
    public abstract void setAttribute(String name, Object o, int scope);

    /**
     * Return the object associated with the name in the page scope or null if not found.
     */
    public abstract Object getAttribute(String name);

    /**
     * Return the object associated with the name in the specified scope or null if not found.
     */
    public abstract Object getAttribute(String name, int scope);

    /**
     * Searches for the named attribute in page, request, session (if valid), 
     * and application scope(s) in order and returns the value associated or null.
     */
    public abstract Object findAttribute(String name);

    /**
     * Remove the object reference associated with the specified name.
     */
    public abstract void removeAttribute(String name);

    /**
     * Remove the object reference associated with the specified name in the given scope.
     */
    public abstract void removeAttribute(String name, int scope);

    /**
     * Get the scope where a given attribute is defined.
     */
    public abstract int getAttributesScope(String name);

    /**
     * Enumerate all the attributes in a given scope.
     */
    public abstract java.util.Enumeration<String> getAttributeNamesInScope(int scope);

    /**
     * This method is intended to process an unhandled "page" level exception.
     */
    public abstract void handlePageException(Exception e) throws IOException;

    /**
     * This method is intended to process an unhandled "page" level exception.
     */
    public abstract void handlePageException(Throwable t) throws IOException;

    /**
     * This method is used to re-direct, or "forward" the current ServletRequest and ServletResponse 
     * to another active component in the application.
     */
    public abstract void forward(String relativeUrlPath) throws IOException;

    /**
     * Causes the resource specified to be processed as part of the current ServletRequest and 
     * ServletResponse being processed by the calling Thread.
     */
    public abstract void include(String relativeUrlPath) throws IOException;

    /**
     * Causes the resource specified to be processed as part of the current ServletRequest and 
     * ServletResponse being processed by the calling Thread.
     */
    public abstract void include(String relativeUrlPath, boolean flush) throws IOException;
}
