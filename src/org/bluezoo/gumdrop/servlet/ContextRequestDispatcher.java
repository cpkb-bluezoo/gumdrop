/*
 * ContextRequestDispatcher.java
 * Copyright (C) 2005 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The handler chain for a request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ContextRequestDispatcher implements RequestDispatcher, FilterChain {

    final Context context;
    final ServletMatch match;
    final String queryString;
    final List handlers;
    final boolean named;
    int index;
    boolean errorSent;
    DispatcherType mode;
    Request originalRequest;
    Response originalResponse;

    ContextRequestDispatcher(Context context, ServletMatch match, String queryString, List handlers, boolean named) {
        this.context = context;
        this.match = match;
        this.queryString = queryString;
        this.handlers = handlers;
        this.named = named;
        index = 0;
        mode = DispatcherType.REQUEST;
    }

    @Override public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        if (response.isCommitted()) {
            // SRV.8.4
            String message = Context.L10N.getString("err.committed");
            throw new IllegalStateException(message);
        }
        if (request instanceof HttpServletRequest && !named) {
            HttpServletRequest hq = (HttpServletRequest) request;
            // SRV.8.4.1, SRV.8.4.2
            String contextPath = context.contextPath;
            StringBuffer uri = new StringBuffer();
            if (!"/".equals(contextPath)) {
                uri.append(contextPath);
            } else {
                contextPath = "";
            }
            if (match.servletPath != null) {
                uri.append(match.servletPath);
            }
            if (match.pathInfo != null) {
                uri.append(match.pathInfo);
            }
            Map attrs = new HashMap();
            attrs.put("javax.servlet.forward.request_uri", hq.getRequestURI());
            attrs.put("javax.servlet.forward.context_path", hq.getContextPath());
            attrs.put("javax.servlet.forward.servlet_path", hq.getServletPath());
            attrs.put("javax.servlet.forward.path_info", hq.getPathInfo());
            attrs.put("javax.servlet.forward.query_string", hq.getQueryString());
            request = new FilterRequest(hq, uri.toString(), contextPath, match, queryString, attrs, DispatcherType.FORWARD);
        }
        DispatcherType oldMode = mode;
        mode = DispatcherType.FORWARD;
        doFilter(request, response);
        mode = oldMode;
    }

    @Override public void include(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest hq = (HttpServletRequest) request;
            // SRV.8.3.1
            String contextPath = context.contextPath;
            StringBuffer uri = new StringBuffer();
            if (!"/".equals(contextPath)) {
                uri.append(contextPath);
            } else {
                contextPath = "";
            }
            if (match.servletPath != null) {
                uri.append(match.servletPath);
            }
            if (match.pathInfo != null) {
                uri.append(match.pathInfo);
            }
            Map attrs = new HashMap();
            attrs.put("javax.servlet.include.request_uri", hq.getRequestURI());
            attrs.put("javax.servlet.include.context_path", hq.getContextPath());
            attrs.put("javax.servlet.include.servlet_path", hq.getServletPath());
            attrs.put("javax.servlet.include.path_info", hq.getPathInfo());
            attrs.put("javax.servlet.include.query_string", hq.getQueryString());
            request = new FilterRequest(hq, uri.toString(), contextPath, match, queryString, attrs, DispatcherType.INCLUDE);
        }
        if (response instanceof HttpServletResponse) {
            HttpServletResponse hr = (HttpServletResponse) response;
            // SRV.8.3
            response = new FilterResponse(hr, true);
        }
        DispatcherType oldMode = mode;
        mode = DispatcherType.INCLUDE;
        doFilter(request, response);
        mode = oldMode;
    }

    void handleRequest(Request request, Response response) throws ServletException, IOException {
        originalRequest = request;
        originalResponse = response;
        if (!context.authentication || authorize(request, response)) {
            doFilter(request, response);
        }
    }

    // -- FilterChain --

    @Override public void doFilter(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        ServletResponse filterResponse =
            (response instanceof HttpServletResponse)
            ? new FilterResponse((HttpServletResponse) response)
            : response;
        String servletName = null;
        Servlet servlet = null;
        // Get original request
        ServletRequest cur = request;
        while (cur != null && (cur instanceof ServletRequestWrapper)) {
            cur = ((ServletRequestWrapper) cur).getRequest();
        }
        Request r = (Request) cur;
        try {
            int servletIndex = handlers.size() - 1;
            boolean handled = false;
            if (index < servletIndex) {
                FilterMatch filterMatch = null;
                // Get next filter matching mode
                do {
                    filterMatch = (FilterMatch) handlers.get(index++);
                    handled = filterMatch.filterMapping.matches(mode);
                } while (!handled && index < servletIndex);
                if (handled) {
                    // apply matching filter
                    // Filters are already loaded
                    FilterDef filterDef = filterMatch.filterDef;
                    servletName = filterDef.name;
                    Filter filter = (Filter) context.filters.get(filterDef.name);
                    filter.doFilter(request, filterResponse, this);
                    handled = true;
                }
                // otherwise fall through to servlet
            }
            if (!handled) {
                ServletDef servletDef = (ServletDef) handlers.get(index++);
                servletName = servletDef.name;
                // Servlet may be loaded or not
                servlet = context.loadServlet(servletDef.name);
                servlet.service(request, filterResponse);
            }
            // Check if startAsync was called
            StreamAsyncContext async = r.asyncContext;
            if (async != null) {
                async.asyncStarted();
            }
        } catch (Exception e) {
            StreamAsyncContext async = r.asyncContext;
            if (async != null) {
                async.error(e);
            }
            if (!errorSent) {
                if (originalResponse != null && !originalResponse.committed) {
                    originalResponse.reset();
                    if (e instanceof UnavailableException) {
                        UnavailableException ue = (UnavailableException) e;
                        int retryAfter = ue.getUnavailableSeconds();
                        if (!ue.isPermanent() && retryAfter > -1) {
                            originalResponse.setIntHeader("Retry-After", retryAfter);
                            originalResponse.sendError(503, null, servletName, e);
                        } else {
                            originalResponse.sendError(500, null, servletName, e);
                        }
                    } else {
                        originalResponse.sendError(500, null, servletName, e);
                    }
                }
                // context.log(servletName, e);
                errorSent = true;
            }
            // Rethrow the exception
            if (e instanceof UnavailableException) {
                UnavailableException ue = (UnavailableException) e;
                if (ue.isPermanent()) {
                    // SRV.2.3.3.2
                    synchronized (context) {
                        servlet.destroy();
                        context.servlets.remove(servletName);
                        context.servletDefs.remove(servletName);
                    }
                }
                throw (UnavailableException) e;
            } else if (e instanceof ServletException) {
                throw (ServletException) e;
            } else if (e instanceof IOException) {
                throw (IOException) e;
            } else if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
        }
        if (!errorSent && (filterResponse instanceof FilterResponse)) {
            int sc = ((FilterResponse) filterResponse).code;
            if (sc > 399 && originalResponse != null && !originalResponse.committed) {
                originalResponse.sendError(sc, null, servletName, null);
            }
        }
    }

    boolean authorize(Request request, Response response) throws ServletException, IOException {
        String path =
            (request.match.pathInfo == null)
            ? request.match.servletPath
            : request.match.servletPath + request.match.pathInfo;
        boolean authenticated = false;

        // Build list of security constraints
        ServletDef servletDef = request.match.servletDef;
        Set<SecurityConstraint> securityConstraints = new LinkedHashSet<>();
        if (servletDef.servletSecurity != null) {
            // constraints specifically associated with this servlet
            securityConstraints.addAll(servletDef.servletSecurity);
        }
        // constraints matching path
        securityConstraints.addAll(context.securityConstraints);

        // Authorize if necessary
        for (SecurityConstraint sc : securityConstraints) {
            if (sc.matches(request.getMethod(), path)) {
                // Check if HTTPS is required
                if (sc.transportGuarantee != ServletSecurity.TransportGuarantee.NONE && !request.isSecure()) {
                    // Redirect to the secure host and port
                    if (context.secureHost == null) {
                        String message = Context.L10N.getString("http.no_secure_host");
                        response.sendError(500, message);
                        return false;
                    }
                    String url = request.getRequestURI();
                    String urlQueryString = request.getQueryString();
                    if (urlQueryString != null) {
                        url = url + "?" + urlQueryString;
                    }
                    url = "https://" + context.secureHost + url;
                    response.sendRedirect(url);
                    return false;
                }
                // Need authentication
                if (!authenticated && !request.authenticate(response)) {
                    return false;
                }
                authenticated = true;

                // Discover all roles
                Set roles = new LinkedHashSet();
                boolean authorized = false;
                for (Iterator j = sc.authConstraints.iterator(); j.hasNext(); ) {
                    String roleName = (String) j.next();
                    roles.add(roleName);
                }
                for (Iterator j = roles.iterator(); j.hasNext(); ) {
                    String roleName = (String) j.next();
                    if (request.isUserInRole(roleName)) {
                        authorized = true;
                        break;
                    }
                }
                if (!authorized) {
                    // User did not match any roles, request reauthentication
                    if (!request.authenticate(response)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public String toString() {
        StringBuffer buf = new StringBuffer(getClass().getName());
        buf.append("[servletPath=");
        buf.append(match.servletPath);
        if (match.pathInfo != null) {
            buf.append(",pathInfo=");
            buf.append(match.pathInfo);
        }
        if (queryString != null) {
            buf.append(",queryString=");
            buf.append(queryString);
        }
        buf.append("]");
        for (Iterator i = handlers.iterator(); i.hasNext(); ) {
            buf.append("\n\t");
            buf.append(i.next().toString());
        }
        return buf.toString();
    }

}
