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

import gnu.inet.util.BASE64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.ProtocolException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The handler chain for a request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ContextRequestDispatcher implements RequestDispatcher, FilterChain {

    private static final byte COLON = 0x3a;

    final Context context;
    final ServletMatch match;
    final String queryString;
    final List handlers;
    final boolean named;
    int index;
    boolean errorSent;
    int mode;
    Request originalRequest;
    Response originalResponse;

    ContextRequestDispatcher(Context context, ServletMatch match, String queryString, List handlers, boolean named) {
        this.context = context;
        this.match = match;
        this.queryString = queryString;
        this.handlers = handlers;
        this.named = named;
        index = 0;
        mode = FilterMapping.REQUEST;
    }

    // -- RequestDispatcher --

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
        int oldMode = mode;
        mode = FilterMapping.FORWARD;
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
        int oldMode = mode;
        mode = FilterMapping.INCLUDE;
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
                    handled = (filterMatch.filterMapping.dispatcher | mode) != 0;
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
                if (servletDef.singleThreadModel) {
                    synchronized (servlet) {
                        servlet.service(request, filterResponse);
                    }
                } else {
                    servlet.service(request, filterResponse);
                }
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

        // Authorize if necessary
        for (Iterator i = context.securityConstraints.iterator(); i.hasNext(); ) {
            SecurityConstraint sc = (SecurityConstraint) i.next();
            // Check if HTTPS is required
            if (sc.transportGuarantee != SecurityConstraint.NONE && !request.isSecure()) {
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
            if (sc.matches(request.getMethod(), path)) {
                // Need authentication
                if (!authenticated && !authenticate(request, response)) {
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
                    if (!authenticate(request, response)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    boolean authenticate(Request request, Response response) throws ServletException, IOException {
        // Authentication required
        String username = null;
        String realm = context.realmName;
        if (HttpServletRequest.BASIC_AUTH.equals(context.authMethod)) {
            String authorization = request.getHeader("Authorization");
            if (authorization == null) {
                // Authorization required
                requireAuthentication(response, "Basic");
                return false;
            }
            int si = authorization.indexOf(' ');
            if (si < 1) {
                String message = Context.L10N.getString("http.bad_auth_header");
                message = MessageFormat.format(message, authorization);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, message);
                return false;
            }
            String clientScheme = authorization.substring(0, si);
            if (!"Basic".equals(clientScheme)) {
                // Basic authorization required
                requireAuthentication(response, "Basic");
                return false;
            }
            // Decode credentials
            byte[] base64UserPass = authorization.substring(si + 1).getBytes("US-ASCII");
            String userPass = new String(BASE64.decode(base64UserPass), "US-ASCII");
            int ci = userPass.indexOf(COLON);
            if (ci < 1) {
                String message = Context.L10N.getString("http.bad_basic_creds");
                message = MessageFormat.format(message, userPass);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, message);
                return false;
            }
            username = userPass.substring(0, ci);
            String password = userPass.substring(ci + 1);
            String userPassword = context.getPassword(realm, username);
            if (!password.equals(userPassword)) {
                String message = Context.L10N.getString("err.auth_fail");
                message = MessageFormat.format(message, username, password);
                Context.LOGGER.warning(message);
                // Correct password required
                requireAuthentication(response, "Basic");
                return false;
            }
        } else if (HttpServletRequest.DIGEST_AUTH.equals(context.authMethod)) {
            String authorization = request.getHeader("Authorization");
            if (authorization == null) {
                // Authorization required
                requireAuthentication(response, "Digest");
                return false;
            }
            int si = authorization.indexOf(' ');
            if (si < 1) {
                String message = Context.L10N.getString("http.bad_auth_header");
                message = MessageFormat.format(message, authorization);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, message);
                return false;
            }
            String clientScheme = authorization.substring(0, si);
            if (!"Digest".equals(clientScheme)) {
                // Digest authorization required
                requireAuthentication(response, "Digest");
                return false;
            }
            String drText = authorization.substring(si + 1);
            Map digestResponse = parseDigestResponse(drText);
            username = (String) digestResponse.get("username");
            realm = (String) digestResponse.get("realm");
            String userPassword = context.getPassword(realm, username);
            if (userPassword == null) {
                // No such user
                requireAuthentication(response, "Digest");
                return false;
            }
            String nonce = (String) digestResponse.get("nonce");
            // TODO nonce must be the same as that sent to the client
            String requestDigest = (String) digestResponse.get("response");
            String qop = (String) digestResponse.get("qop");
            String algorithm = (String) digestResponse.get("algorithm");
            String cnonce = (String) digestResponse.get("cnonce");
            String nonceCount = (String) digestResponse.get("nc");
            String method = request.getMethod();
            String digestUri = request.getRequestURI();
            String requestQueryString = request.getQueryString();
            if (requestQueryString != null) {
                digestUri = digestUri + "?" + requestQueryString;
            }
            if (username == null || realm == null || requestDigest == null || nonce == null) {
                String message = Context.L10N.getString("http.bad_digest");
                message = MessageFormat.format(message, drText);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, message);
                return false;
            }
            if (algorithm == null) {
                algorithm = "MD5";
            }
            try {
                MessageDigest md = MessageDigest.getInstance(algorithm);

                // Compute H(A1)
                md.reset();
                md.update(username.getBytes("US-ASCII"));
                md.update(COLON);
                md.update(realm.getBytes("US-ASCII"));
                md.update(COLON);
                md.update(userPassword.getBytes("US-ASCII"));
                byte[] ha1 = md.digest();
                if ("MD5-sess".equals(algorithm)) {
                    if (cnonce == null) {
                        String message = Context.L10N.getString("http.bad_digest");
                        message = MessageFormat.format(message, drText);
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, message);
                        return false;
                    }
                    md.reset();
                    md.update(ha1);
                    md.update(COLON);
                    md.update(nonce.getBytes("US-ASCII"));
                    md.update(COLON);
                    md.update(cnonce.getBytes("US-ASCII"));
                    ha1 = md.digest();
                    // TODO sessions
                }
                String ha1Hex = toHexString(ha1);

                // Compute H(A2)
                md.reset();
                md.update(method.getBytes("US-ASCII"));
                md.update(COLON);
                md.update(digestUri.getBytes("US-ASCII"));
                if ("auth-int".equals(qop)) {
                    byte[] hEntity = null;
                    // need to read entire entity body here
                    // but this will affect servlet
                    throw new ProtocolException("auth-int not permitted");
                    /*md5.update(COLON);
                      md5.update(hEntity);*/
                }
                byte[] ha2 = md.digest();
                String ha2Hex = toHexString(ha2);

                // Calculate response
                md.reset();
                md.update(ha1Hex.getBytes("US-ASCII"));
                md.update(COLON);
                md.update(nonce.getBytes("US-ASCII"));
                if ("auth".equals(qop)) {
                    if (cnonce == null || nonceCount == null) {
                        String message = Context.L10N.getString("http.bad_digest");
                        message = MessageFormat.format(message, drText);
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, message);
                        return false;
                    }
                    md.update(COLON);
                    md.update(nonceCount.getBytes("US-ASCII"));
                    md.update(COLON);
                    md.update(cnonce.getBytes("US-ASCII"));
                    md.update(COLON);
                    md.update(qop.getBytes("US-ASCII"));
                } else if ("auth-int".equals(qop)) {
                    // need to read entire entity body here
                    // but this will affect servlet
                    throw new ProtocolException("auth-int not permitted");
                }
                md.update(COLON);
                md.update(ha2Hex.getBytes("US-ASCII"));
                String test = toHexString(md.digest());

                // Compare computed response with the one specified by the
                // client
                if (!test.equals(requestDigest)) {
                    // Authentication failed
                    String message = Context.L10N.getString("err.digest_auth_fail");
                    message = MessageFormat.format(message, username);
                    Context.LOGGER.warning(message);
                    requireAuthentication(response, "Digest");
                    return false;
                }
            } catch (NoSuchAlgorithmException e) {
                throw new ServletException(e);
            }
        } else if (HttpServletRequest.FORM_AUTH.equals(context.authMethod)) {
            username = request.getParameter("j_username");
            String requestPassword = request.getParameter("j_password");
            if (username == null) {
                response.sendRedirect(context.formLoginPage);
                return false;
            } else {
                String password = context.getPassword(realm, username);
                if (requestPassword == null || !requestPassword.equals(password)) {
                    String message = Context.L10N.getString("err.auth_fail");
                    message = MessageFormat.format(message, username, requestPassword);
                    Context.LOGGER.warning(message);
                    response.sendRedirect(context.formErrorPage);
                    return false;
                }
            }
        } else if (HttpServletRequest.CLIENT_CERT_AUTH.equals(context.authMethod)) {
            // TODO HTTP client cert
            return false;
        } else {
            String message = Context.L10N.getString("http.unknown_auth_mechanism");
            message = MessageFormat.format(message, context.authMethod);
            response.sendError(500, message);
            return false;
        }
        // associate principal with request
        request.userPrincipal = new ServletPrincipal(context, realm, username);
        return true;
    }

    void requireAuthentication(Response response, String scheme)
        throws ServletException, IOException {
            String realm = context.realmName;
            if ("Digest".equals(scheme)) {
                // Digest
                try {
                    // Create a suitable nonce value
                    MessageDigest md = MessageDigest.getInstance("MD5");
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    ObjectOutputStream oo = new ObjectOutputStream(bo);
                    oo.writeLong(System.currentTimeMillis());
                    md.update(bo.toByteArray());
                    bo.reset();
                    oo.writeDouble(Math.random());
                    md.update(bo.toByteArray());
                    String nonce = toHexString(BASE64.encode(md.digest()));

                    // TODO associate nonce with context to avoid replay attacks

                    // Send UNAUTHORIZED with the WWW-Authenticate header
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    String wwwAuthenticate =
                        "Digest realm=\"" + realm + "\", nonce=\"" + nonce + "\", qop=\"auth\"";
                    response.setHeader("WWW-Authenticate", wwwAuthenticate);
                } catch (NoSuchAlgorithmException e) {
                    throw new ServletException(e);
                }
            } else if ("Basic".equals(scheme)) {
                // Basic
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
            } else {
                String message = Context.L10N.getString("http.unknown_auth_mechanism");
                message = MessageFormat.format(message, scheme);
                response.sendError(500, message);
            }
        }

    /**
     * Parse the specified text into an RFC 2617 digest-response.
     */
    protected final Map parseDigestResponse(String text) throws IOException {
        Map map = new LinkedHashMap();
        boolean inQuote = false;
        char[] chars = text.toCharArray();
        StringBuffer buf = new StringBuffer();
        String key = null;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '"') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == ',') {
                    // End of pair
                    String val = unq(buf.toString());
                    buf.setLength(0);
                    if (key == null) {
                        String message = Context.L10N.getString("http.bad_digest");
                        message = MessageFormat.format(message, text);
                        throw new ProtocolException(message);
                    }
                    map.put(key, val);
                    key = null;
                } else if (c == '=') {
                    // End of key
                    key = buf.toString().trim();
                    buf.setLength(0);
                } else {
                    buf.append(c);
                }
            } else {
                buf.append(c);
            }
        }
        if (inQuote || key == null) {
            String message = Context.L10N.getString("http.bad_digest");
            message = MessageFormat.format(message, text);
            throw new ProtocolException(message);
        } else {
            // End of pair
            String val = unq(buf.toString());
            map.put(key, val);
        }
        return map;
    }

    /**
     * Provides the canonical representation of the specified quoted-string,
     * i.e. without the quotes.
     */
    static String unq(String text) {
        if (text != null) {
            int len = text.length();
            if (len > 1 && text.charAt(0) == '"' && text.charAt(len - 1) == '"') {
                text = text.substring(1, len - 1);
            }
        }
        return text;
    }

    static String toHexString(byte[] bytes) {
        char[] ret = new char[bytes.length * 2];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            int c = (int) bytes[i];
            if (c < 0) {
                c += 0x100;
            }
            ret[j++] = Character.forDigit(c / 0x10, 0x10);
            ret[j++] = Character.forDigit(c % 0x10, 0x10);
        }
        return new String(ret);
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
