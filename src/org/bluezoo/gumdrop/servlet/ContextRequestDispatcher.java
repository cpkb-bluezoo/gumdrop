/*
 * ContextRequestDispatcher.java
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
import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The handler chain for a request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ContextRequestDispatcher implements RequestDispatcher, FilterChain {

    private static final Logger LOGGER = Logger.getLogger(ContextRequestDispatcher.class.getName());

    final Context context;
    final ServletMatch match;
    final String queryString;
    final List<FilterMatch> filterMatches;
    final boolean named;
    int index; // current index of this dispatcher in the filter list
    boolean errorSent;
    DispatcherType mode;
    Request originalRequest;
    Response originalResponse;

    ContextRequestDispatcher(Context context, ServletMatch match, String queryString, List<FilterMatch> filterMatches, boolean named) {
        this.context = context;
        this.match = match;
        this.queryString = queryString;
        this.filterMatches = filterMatches;
        this.named = named;
        index = 0;
        mode = DispatcherType.REQUEST;
    }

    // Called by worker
    void handleRequest(Request request, Response response) throws ServletException, IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            String servletName = (match.servletDef != null) ? match.servletDef.name : "null";
            String servletClass = (match.servletDef != null) ? match.servletDef.className : "null";
            LOGGER.finest("Dispatching request: uri=" + request.getRequestURI() 
                + ", servletPath=" + match.servletPath 
                + ", pathInfo=" + match.pathInfo
                + ", servlet=" + servletName + " (" + servletClass + ")"
                + ", queryString=" + request.getQueryString()
                + ", matchType=" + match.mappingMatch);
        }
        
        originalRequest = request;
        originalResponse = response;
        if (!context.authentication || authorize(request, response)) {
            doFilter(request, response);
        }
    }

    // -- RequestDispatcher --

    @Override public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        if (response.isCommitted()) {
            // SRV.8.4
            String message = Context.L10N.getString("err.committed");
            throw new IllegalStateException(message);
        }

        if (request instanceof HttpServletRequest) {
            HttpServletRequest hq = (HttpServletRequest) request;
            ServletMatch match = this.match;

            if (named) {
                // We need to populate the match values for the request
                HttpServletMapping hsm = hq.getHttpServletMapping();
                match = new ServletMatch();
                match.servletDef = this.match.servletDef;
                match.mappingMatch = this.match.mappingMatch;
                match.servletPath = hsm.getPattern();
                match.matchValue = hsm.getMatchValue();
            }

            // SRV.8.4.1, SRV.8.4.2
            String contextPath = context.contextPath;
            StringBuilder uri = new StringBuilder(contextPath);
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
            ServletMatch match = this.match;

            if (named) {
                // We need to populate the match values for the request
                HttpServletMapping hsm = hq.getHttpServletMapping();
                match = new ServletMatch();
                match.servletDef = this.match.servletDef;
                match.mappingMatch = this.match.mappingMatch;
                match.servletPath = hsm.getPattern();
                match.matchValue = hsm.getMatchValue();
            }

            // SRV.8.3.1
            String contextPath = context.contextPath;
            StringBuilder uri = new StringBuilder(contextPath);
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
            boolean handled = false;
            if (index < filterMatches.size()) {
                FilterMatch filterMatch = null;
                // Get next filter matching mode
                do {
                    filterMatch = filterMatches.get(index++);
                    handled = filterMatch.filterMapping.matches(mode);
                } while (!handled && index < filterMatches.size());
                if (handled) {
                    // apply matching filter
                    // Filters are already loaded
                    FilterDef filterDef = filterMatch.filterDef;
                    servletName = filterDef.name;
                    try {
                        Filter filter = context.loadFilter(filterDef);
                        filter.doFilter(request, filterResponse, this);
                    } catch (UnavailableException e) {
                        if (e.isPermanent()) {
                            filterDef.unavailableUntil = -1L;
                        } else {
                            filterDef.unavailableUntil = System.currentTimeMillis() + ((long) e.getUnavailableSeconds() * 1000L);
                        }
                        throw e;
                    }
                    handled = true;
                }
                // otherwise fall through to servlet
            }
            if (!handled) {
                ServletDef servletDef = match.servletDef;
                servletName = servletDef.name;
                try {
                    // Servlet may be loaded or not
                    servlet = context.loadServlet(servletDef);
                    servlet.service(request, filterResponse);
                } catch (UnavailableException e) {
                    if (e.isPermanent()) {
                        servletDef.unavailableUntil = -1L;
                    } else {
                        servletDef.unavailableUntil = System.currentTimeMillis() + ((long) e.getUnavailableSeconds() * 1000L);
                    }
                    throw e;
                }
            }
            // Async handling is done in RequestHandler - no action needed here
        } catch (Exception e) {
            // If async is started, the error should be handled by the async context
            // Otherwise, proceed with normal error handling
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

        // Authorize if necessary. For every security constraint that matches
        // this request, enforce the transport guarantee and the authorization
        // (role) constraint. Access is denied with 403 when the authenticated
        // user is not a member of a permitted role, or when a deny-all
        // constraint (an empty auth-constraint) applies.
        for (SecurityConstraint sc : securityConstraints) {
            if (!sc.matches(request.getMethod(), path)) {
                continue;
            }

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

            ConstraintRequirement requirement = requirementOf(sc);
            if (requirement == ConstraintRequirement.NONE) {
                // No role restriction imposed by this constraint.
                continue;
            }
            if (requirement == ConstraintRequirement.DENY_ALL) {
                // An empty auth-constraint denies all access.
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }

            // A role constraint applies: the user must be authenticated.
            if (!authenticated && !request.authenticate(response)) {
                return false; // challenge already sent by authenticate()
            }
            authenticated = true;

            // The user must be a member of at least one permitted role.
            if (!isRoleAuthorized(sc.authConstraints, request::isUserInRole)) {
                // Authenticated but not authorized for this resource.
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
        }
        return true;
    }

    /**
     * The effective authorization requirement of a single matched security
     * constraint, independent of any request I/O.
     */
    enum ConstraintRequirement {
        /** The constraint imposes no role restriction (access permitted). */
        NONE,
        /** The constraint denies access to all users (empty auth-constraint). */
        DENY_ALL,
        /** The user must be a member of one of the permitted roles. */
        ROLE
    }

    /**
     * Determines the authorization requirement of a security constraint.
     *
     * <p>A constraint with at least one non-empty role requires role
     * membership. A constraint with no permitted roles denies all access when
     * its empty-role semantic is {@code DENY} (an empty {@code
     * <auth-constraint/>}), otherwise it imposes no restriction (the default
     * when no auth-constraint element is present).
     *
     * @param sc the security constraint
     * @return the requirement
     */
    static ConstraintRequirement requirementOf(SecurityConstraint sc) {
        for (String roleName : sc.authConstraints) {
            if (roleName != null && !roleName.isEmpty()) {
                return ConstraintRequirement.ROLE;
            }
        }
        return (sc.emptyRoleSemantic == ServletSecurity.EmptyRoleSemantic.DENY)
                ? ConstraintRequirement.DENY_ALL
                : ConstraintRequirement.NONE;
    }

    /**
     * Returns whether an authenticated user is authorized by an
     * auth-constraint listing the given roles.
     *
     * <p>The special role names {@code "*"} and {@code "**"} match any
     * authenticated user (Servlet 3.1 section 13.8). Null and empty role names
     * are ignored.
     *
     * @param allowedRoles the roles permitted by the constraint
     * @param roleTester tests whether the current user is in a named role
     * @return true if the user is authorized
     */
    static boolean isRoleAuthorized(Iterable<String> allowedRoles,
            java.util.function.Predicate<String> roleTester) {
        for (String roleName : allowedRoles) {
            if (roleName == null || roleName.isEmpty()) {
                continue;
            }
            if ("*".equals(roleName) || "**".equals(roleName)
                    || roleTester.test(roleName)) {
                return true;
            }
        }
        return false;
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
        for (FilterMatch filterMatch : filterMatches) {
            buf.append("\n\t");
            buf.append(filterMatch.toString());
        }
        return buf.toString();
    }

}
