/*
 * DefaultPageContext.java
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

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of PageContext for Gumdrop JSP support.
 * 
 * <p>This provides a working implementation of the JSP PageContext class,
 * managing page-scope attributes and providing access to servlet API objects.</p>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultPageContext extends PageContext {

    private Servlet servlet;
    private ServletRequest request;
    private ServletResponse response;
    private String errorPageURL;
    private boolean needsSession;
    private int bufferSize;
    private boolean autoFlush;
    
    // Page scope attributes
    private final Map<String, Object> pageAttributes = new ConcurrentHashMap<>();
    
    // JSP Writer
    private JspWriter out;

    /**
     * Package-private constructor for use by DefaultJspFactory.
     */
    DefaultPageContext(Servlet servlet, ServletRequest request, ServletResponse response, 
                      String errorPageURL, boolean needsSession, int bufferSize, boolean autoFlush) {
        this.servlet = servlet;
        this.request = request;
        this.response = response;
        this.errorPageURL = errorPageURL;
        this.needsSession = needsSession;
        this.bufferSize = bufferSize;
        this.autoFlush = autoFlush;
        
        // Create JSP writer wrapping the response writer
        try {
            PrintWriter writer = response.getWriter();
            this.out = new DefaultJspWriter(writer, bufferSize, autoFlush);
        } catch (IOException e) {
            throw new RuntimeException("Failed to get response writer", e);
        }
    }

    @Override
    public void initialize(Servlet servlet, ServletRequest request, ServletResponse response,
                          String errorPageURL, boolean needsSession, int bufferSize, 
                          boolean autoFlush) throws IOException {
        // Already initialized in constructor
    }

    @Override
    public void release() {
        pageAttributes.clear();
        servlet = null;
        request = null;
        response = null;
        errorPageURL = null;
        out = null;
    }

    @Override
    public HttpSession getSession() {
        if (request instanceof HttpServletRequest) {
            return ((HttpServletRequest) request).getSession(needsSession);
        }
        return null;
    }

    @Override
    public Object getPage() {
        return servlet;
    }

    @Override
    public ServletRequest getRequest() {
        return request;
    }

    @Override
    public ServletResponse getResponse() {
        return response;
    }

    @Override
    public Exception getException() {
        return (Exception) request.getAttribute("javax.servlet.error.exception");
    }

    @Override
    public ServletConfig getServletConfig() {
        return servlet.getServletConfig();
    }

    @Override
    public ServletContext getServletContext() {
        return servlet.getServletConfig().getServletContext();
    }

    @Override
    public JspWriter getOut() {
        return out;
    }

    @Override
    public void setAttribute(String name, Object attribute) {
        setAttribute(name, attribute, PAGE_SCOPE);
    }

    @Override
    public void setAttribute(String name, Object o, int scope) {
        switch (scope) {
            case PAGE_SCOPE:
                if (o == null) {
                    pageAttributes.remove(name);
                } else {
                    pageAttributes.put(name, o);
                }
                break;
            case REQUEST_SCOPE:
                request.setAttribute(name, o);
                break;
            case SESSION_SCOPE:
                HttpSession session = getSession();
                if (session != null) {
                    if (o == null) {
                        session.removeAttribute(name);
                    } else {
                        session.setAttribute(name, o);
                    }
                }
                break;
            case APPLICATION_SCOPE:
                getServletContext().setAttribute(name, o);
                break;
            default:
                throw new IllegalArgumentException("Invalid scope: " + scope);
        }
    }

    @Override
    public Object getAttribute(String name) {
        return getAttribute(name, PAGE_SCOPE);
    }

    @Override
    public Object getAttribute(String name, int scope) {
        switch (scope) {
            case PAGE_SCOPE:
                return pageAttributes.get(name);
            case REQUEST_SCOPE:
                return request.getAttribute(name);
            case SESSION_SCOPE:
                HttpSession session = getSession();
                return session != null ? session.getAttribute(name) : null;
            case APPLICATION_SCOPE:
                return getServletContext().getAttribute(name);
            default:
                throw new IllegalArgumentException("Invalid scope: " + scope);
        }
    }

    @Override
    public Object findAttribute(String name) {
        Object result = getAttribute(name, PAGE_SCOPE);
        if (result != null) {
            return result;
        }
        
        result = getAttribute(name, REQUEST_SCOPE);
        if (result != null) {
            return result;
        }
        
        result = getAttribute(name, SESSION_SCOPE);
        if (result != null) {
            return result;
        }
        
        return getAttribute(name, APPLICATION_SCOPE);
    }

    @Override
    public void removeAttribute(String name) {
        removeAttribute(name, PAGE_SCOPE);
    }

    @Override
    public void removeAttribute(String name, int scope) {
        setAttribute(name, null, scope);
    }

    @Override
    public int getAttributesScope(String name) {
        if (pageAttributes.containsKey(name)) return PAGE_SCOPE;
        if (request.getAttribute(name) != null) return REQUEST_SCOPE;
        
        HttpSession session = getSession();
        if (session != null && session.getAttribute(name) != null) return SESSION_SCOPE;
        
        if (getServletContext().getAttribute(name) != null) return APPLICATION_SCOPE;
        
        return 0; // Not found
    }

    @Override
    public Enumeration<String> getAttributeNamesInScope(int scope) {
        switch (scope) {
            case PAGE_SCOPE:
                return Collections.enumeration(pageAttributes.keySet());
            case REQUEST_SCOPE:
                return request.getAttributeNames();
            case SESSION_SCOPE:
                HttpSession session = getSession();
                return session != null ? session.getAttributeNames() : Collections.emptyEnumeration();
            case APPLICATION_SCOPE:
                return getServletContext().getAttributeNames();
            default:
                throw new IllegalArgumentException("Invalid scope: " + scope);
        }
    }

    @Override
    public void handlePageException(Exception e) throws IOException {
        handlePageException((Throwable) e);
    }

    @Override
    public void handlePageException(Throwable t) throws IOException {
        if (errorPageURL != null && !errorPageURL.isEmpty()) {
            try {
                forward(errorPageURL);
            } catch (Exception e) {
                throw new IOException("Error forwarding to error page: " + errorPageURL, e);
            }
        } else {
            // Log and rethrow
            getServletContext().log("Unhandled JSP exception", t);
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof IOException) {
                throw (IOException) t;
            } else {
                throw new IOException("JSP exception", t);
            }
        }
    }

    @Override
    public void forward(String relativeUrlPath) throws IOException {
        try {
            RequestDispatcher rd = getServletContext().getRequestDispatcher(relativeUrlPath);
            if (rd != null) {
                rd.forward(request, response);
            } else {
                throw new IOException("No request dispatcher for path: " + relativeUrlPath);
            }
        } catch (ServletException e) {
            throw new IOException("Error forwarding to: " + relativeUrlPath, e);
        }
    }

    @Override
    public void include(String relativeUrlPath) throws IOException {
        include(relativeUrlPath, false);
    }

    @Override
    public void include(String relativeUrlPath, boolean flush) throws IOException {
        if (flush && out != null) {
            out.flush();
        }
        
        try {
            RequestDispatcher rd = getServletContext().getRequestDispatcher(relativeUrlPath);
            if (rd != null) {
                rd.include(request, response);
            } else {
                throw new IOException("No request dispatcher for path: " + relativeUrlPath);
            }
        } catch (ServletException e) {
            throw new IOException("Error including: " + relativeUrlPath, e);
        }
    }
}
