/*
 * Request.java
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

import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.TLSInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPAuthenticationProvider;
import org.bluezoo.gumdrop.http.HTTPDateFormat;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.ContentTypeParser;
import org.bluezoo.gumdrop.util.IteratorEnumeration;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;

/**
 * A single HTTP request, from the point of view of the servlet.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Request implements HttpServletRequest {

    enum InputStreamState {
        NONE,
        GET_INPUT_STREAM_CALLED,
        GET_READER_CALLED,
        GET_PARTS_CALLED;
    }

    static final DateFormat dateFormat = new HTTPDateFormat();
    private static final byte COLON = 0x3a;

    final ServletHandler handler;

    String method;
    String requestTarget;
    URI uri;

    boolean secure;
    Headers headers;
    final RequestInputStream in;
    final Map attributes = new HashMap();
    Map<String,String[]> parameters = new LinkedHashMap<>();
    boolean parametersParsed;
    String encoding;
    Thread handlerThread;
    Cookie[] cookies;

    // Determined during servlet resolution
    Context context;
    String contextPath;
    ServletMatch match;
    String queryString;

    String sessionId;
    Boolean sessionType;
    ServletPrincipal userPrincipal;

    AsyncContextImpl asyncContext;
    InputStreamState inputStreamState = InputStreamState.NONE;
    Collection<Part> parts;

    Request(ServletHandler handler, int bufferSize, String method, String requestTarget, Headers headers,
            PipedInputStream pipeIn) throws IOException {
        this.handler = handler;
        in = new RequestInputStream(pipeIn);
        this.method = method;
        this.requestTarget = requestTarget;
        this.headers = headers;
        uri = "*".equals(requestTarget) ? null : URI.create(requestTarget);
        if (uri != null) {
            queryString = uri.getRawQuery();
        }
        
        HTTPResponseState state = handler.getState();
        ConnectionInfo connInfo = state.getConnectionInfo();
        this.secure = connInfo != null && connInfo.isSecure();

        if (secure) {
            TLSInfo tlsInfo = state.getTLSInfo();
            if (tlsInfo != null) {
                Certificate[] certificates = tlsInfo.getPeerCertificates();
                String cipherSuite = tlsInfo.getCipherSuite();
                int keySize = getKeySize(cipherSuite);
                populateTLSAttributes(certificates, cipherSuite, keySize);
            }
        }
    }

    private void populateTLSAttributes(Certificate[] certificates, String cipherSuite, int keySize) {
        if (certificates != null) {
            List<X509Certificate> x509 = new ArrayList<>();
            for (Certificate c : certificates) {
                if (c instanceof X509Certificate) {
                    x509.add((X509Certificate) c);
                }
            }
            X509Certificate[] array = x509.toArray(new X509Certificate[x509.size()]);
            attributes.put("javax.servlet.request.X509Certificate", array);
        }
        attributes.put("javax.servlet.request.cipher_suite", cipherSuite);
        if (keySize > 0) {
            attributes.put("javax.servlet.request.key_size", Integer.valueOf(keySize));
        }
    }

    private int getKeySize(String cipherSuite) {
        if (cipherSuite == null) return -1;
        if (cipherSuite.contains("256")) return 256;
        if (cipherSuite.contains("128")) return 128;
        return -1;
    }

    private Certificate[] getPeerCertificates() {
        TLSInfo tlsInfo = handler.getState().getTLSInfo();
        return tlsInfo != null ? tlsInfo.getPeerCertificates() : null;
    }

    // Helper methods for accessing connection info

    private HTTPVersion getHTTPVersion() {
        return handler.getState().getVersion();
    }

    private String getHTTPScheme() {
        return handler.getState().getScheme();
    }

    private java.net.InetSocketAddress getRemoteSocketAddress() {
        ConnectionInfo connInfo = handler.getState().getConnectionInfo();
        return connInfo != null ? connInfo.getRemoteAddress() : null;
    }

    private java.net.InetSocketAddress getLocalSocketAddress() {
        ConnectionInfo connInfo = handler.getState().getConnectionInfo();
        return connInfo != null ? connInfo.getLocalAddress() : null;
    }

    private boolean isStreamClosed() {
        // Handler doesn't have a closed concept the same way
        return false;
    }

    private boolean isResponseAlreadyStarted() {
        return handler.isResponseStarted();
    }

    private Map<String, String> getRequestTrailerFields() {
        return handler.getRequestTrailerFields();
    }

    private boolean areTrailerFieldsReady() {
        return handler.isRequestFinished();
    }

    /**
     * Initialize this request's session data.
     * This is called by the RequestHandlerThread during path resolution.
     */
    void initSession() {
        sessionId = getParameter("jsessionid");
        if (sessionId == null) {
            getCookies();
            if (cookies != null) {
                for (int i = 0; i < cookies.length; i++) {
                    if ("JSESSIONID".equals(cookies[i].getName())) {
                        sessionId = cookies[i].getValue();
                        sessionType = Boolean.TRUE;
                    }
                }
            }
        } else {
            sessionType = Boolean.FALSE;
        }
        // Session last accessed time is updated by SessionManager.getSession()
    }

    URI getURI() {
        return uri;
    }

    // -- HttpServletRequest --

    @Override public String getAuthType() {
        return (userPrincipal == null) ? null : context.getAuthMethod();
    }

    @Override public Cookie[] getCookies() {
        if (cookies != null) {
            return cookies;
        }
        String cookieHeader = getHeader("Cookie");
        if (cookieHeader != null) {
            List<String> tokens = new LinkedList<>();
            int start = 0, end = 0, len = cookieHeader.length();
            while (end < len) {
                char c = cookieHeader.charAt(end);
                if (c == ',') {
                    tokens.add(cookieHeader.substring(start, end));
                    start = end + 1;
                } else if (c == ';' && end < len - 1 && cookieHeader.charAt(end + 1) == ' ') {
                    tokens.add(cookieHeader.substring(start, end));
                    start = end + 1;
                }
                end++;
            }
            if (start < len) {
                tokens.add(cookieHeader.substring(start, len));
            }
            List<Cookie> acc = new ArrayList<>();
            int version = -1;
            for (String token : tokens) {
                token = token.trim();
                if (token.length() == 0) {
                    continue;
                }
                String name = token, value = null;
                String path = null, domain = null;
                int ei = token.indexOf('=');
                if (ei != -1) {
                    name = token.substring(0, ei);
                    value = token.substring(ei + 1);
                }
                int sci = value.indexOf(';');
                if (sci != -1) {
                    StringTokenizer vst = new StringTokenizer(value.substring(sci + 1), ";");
                    value = value.substring(0, sci);
                    while (vst.hasMoreTokens()) {
                        String vtoken = vst.nextToken().trim();
                        String vname = vtoken, vvalue = null;
                        ei = vtoken.indexOf('=');
                        if (ei != -1) {
                            vname = token.substring(0, ei);
                            vvalue = token.substring(ei + 1);
                        }
                        if ("$Path".equals(vname)) {
                            path = value;
                        } else if ("$Domain".equals(vname)) {
                            domain = value;
                        }
                    }
                }
                if ("$Version".equals(name)) {
                    version = Integer.parseInt(value);
                } else {
                    Cookie cookie = new Cookie(name, value);
                    if (version != -1) {
                        cookie.setVersion(version);
                    }
                    if (path != null) {
                        cookie.setPath(path);
                    }
                    if (domain != null) {
                        cookie.setDomain(domain);
                    }
                    acc.add(cookie);
                }
            }
            cookies = new Cookie[acc.size()];
            acc.toArray(cookies);
            return cookies;
        }
        return null;
    }

    @Override public long getDateHeader(String name) {
        String value = getHeader(name);
        if (value == null) {
            return -1L;
        }
        try {
            Date date = dateFormat.parse(value);
            return date.getTime();
        } catch (ParseException e) {
            throw (IllegalArgumentException) new IllegalArgumentException(value).initCause(e);
        }
    }

    @Override public String getHeader(String name) {
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    @Override public Enumeration<String> getHeaders(String name) {
        Collection<String> acc = new ArrayList<>();
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                acc.add(header.getValue());
            }
        }
        return new IteratorEnumeration<>(acc);
    }

    @Override public Enumeration<String> getHeaderNames() {
        Collection<String> acc = new LinkedHashSet<>();
        for (Header header : headers) {
            acc.add(header.getName());
        }
        return new IteratorEnumeration<>(acc);
    }

    @Override public int getIntHeader(String name) {
        String value = getHeader(name);
        if (value == null) {
            return -1;
        }
        return Integer.parseInt(value);
    }

    @Override public HttpServletMapping getHttpServletMapping() {
        return match;
    }

    @Override public String getMethod() {
        return method;
    }

    @Override public String getPathInfo() {
        return match.pathInfo;
    }

    @Override public String getPathTranslated() {
        if (match.pathInfo != null) {
            return context.getRealPath(match.pathInfo);
        }
        return null;
    }


    @Override public String getContextPath() {
        return contextPath;
    }

    @Override public String getQueryString() {
        return queryString;
    }

    @Override public String getRemoteUser() {
        return (userPrincipal == null) ? null : userPrincipal.getName();
    }

    @Override public boolean isUserInRole(String role) {
        return (userPrincipal == null) ? false : userPrincipal.hasRole(role);
    }

    @Override public Principal getUserPrincipal() {
        return userPrincipal;
    }

    @Override public String getRequestedSessionId() {
        return sessionId;
    }

    @Override public String getRequestURI() {
        return uri == null ? null : uri.getRawPath();
    }

    @Override public StringBuffer getRequestURL() {
        // Note: For forwards/includes, FilterRequest delegates to this method
        // which correctly returns the original request URL per Servlet spec
        String s = (uri == null) ? "" : uri.toString();
        int qi = s.indexOf('?');
        if (qi != -1) {
            s = s.substring(0, qi);
        }
        return new StringBuffer(s);
    }

    @Override public String getServletPath() {
        return match.servletPath;
    }

    @Override public HttpSession getSession(boolean create) {
        HttpSession session = context.getSessionManager().getSession(sessionId, create);
        if (session != null && sessionId == null) {
            sessionId = session.getId();
            // Notify session listeners for new sessions
            HttpSessionEvent event = new HttpSessionEvent(session);
            for (HttpSessionListener l : context.sessionListeners) {
                l.sessionCreated(event);
            }
        }
        return session;
    }

    @Override public HttpSession getSession() {
        return getSession(true);
    }

    @Override public String changeSessionId() { // @since 3.1
        HttpSession session = context.getSessionManager().getSession(sessionId, false);
        if (session == null) {
            throw new IllegalStateException("No session associated with request");
        }
        // Remove old session and create new one with same data
        context.getSessionManager().removeSession(sessionId);
        HttpSession newSession = context.getSessionManager().createSession();
        sessionId = newSession.getId();
        // Copy attributes from old to new session
        Enumeration<?> names = session.getAttributeNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            newSession.setAttribute(name, session.getAttribute(name));
        }
        return sessionId;
    }

    @Override public boolean isRequestedSessionIdValid() {
        return sessionId != null && context.getSessionManager().getSession(sessionId) != null;
    }

    @Override public boolean isRequestedSessionIdFromCookie() {
        return Boolean.TRUE == sessionType;
    }

    @Override public boolean isRequestedSessionIdFromURL() {
        return Boolean.FALSE == sessionType;
    }

    @Override public boolean isRequestedSessionIdFromUrl() {
        return isRequestedSessionIdFromURL();
    }

    @Override public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
        String authMethod = context.getAuthMethod();
        if (authMethod == null) {
            return true; // No authentication required
        }

        // Handle servlet-specific authentication methods
        if (HttpServletRequest.FORM_AUTH.equals(authMethod)) {
            return authenticateForm(response);
        } else if (HttpServletRequest.CLIENT_CERT_AUTH.equals(authMethod)) {
            return authenticateClientCert(response);
        }

        // All other authentication methods (BASIC, DIGEST, BEARER, OAUTH, JWT)
        // Create an authentication provider for this context and use it directly
        ServletAuthenticationProvider authProvider = new ServletAuthenticationProvider(context);
        String authHeader = getHeader("Authorization");
        HTTPAuthenticationProvider.AuthenticationResult result = authProvider.authenticate(authHeader);

        if (!result.success) {
            // Generate the authentication challenge
            String challenge = authProvider.generateChallenge();
            
            if (challenge == null) {
                // Challenge could not be generated - likely the Realm doesn't support
                // the configured authentication method (e.g., DIGEST with LDAP)
                String message = Context.L10N.getString("err.auth_method_not_supported");
                message = MessageFormat.format(message, authMethod);
                Context.LOGGER.severe(message);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message);
                return false;
            }
            
            // Authentication failed - send challenge
            response.setHeader("WWW-Authenticate", challenge);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentLength(0);
            return false;
        }

        // Authentication succeeded - create principal
        userPrincipal = new ServletPrincipal(context, result.realm, result.username);
        return true;
    }

    /**
     * Handle FORM authentication (servlet-specific).
     */
    private boolean authenticateForm(HttpServletResponse response) throws IOException {
        String username = getParameter("j_username");
        String password = getParameter("j_password");
        String realm = context.getRealmName();

        if (username == null) {
            response.sendRedirect(context.getFormLoginPage());
            return false;
        } else {
            if (!context.passwordMatch(realm, username, password)) {
                String message = Context.L10N.getString("err.auth_fail");
                message = MessageFormat.format(message, username, password);
                Context.LOGGER.warning(message);
                response.sendRedirect(context.getFormErrorPage());
                return false;
            }
        }

        userPrincipal = new ServletPrincipal(context, realm, username);
        return true;
    }

    /**
     * Handle CLIENT_CERT authentication (servlet-specific).
     */
    private boolean authenticateClientCert(HttpServletResponse response) throws IOException {
        String username = null;
        String realm = context.getRealmName();

        Certificate[] certificates = getPeerCertificates();
        if (certificates != null) {
            for (Certificate certificate : certificates) {
                if (certificate instanceof X509Certificate) {
                    X509Certificate x509 = (X509Certificate) certificate;
                    username = x509.getSubjectX500Principal().getName();
                    break;
                }
            }
        }

        if (username == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN); // NB not 401 Unauthorized
            response.setContentLength(0);
            return false;
        }

        userPrincipal = new ServletPrincipal(context, realm, username);
        return true;
    }


    @Override public void login(String username, String password) throws ServletException {
        String realm = context.getRealmName();
        if (userPrincipal != null || !context.passwordMatch(realm, username, password)) {
            String message = Context.L10N.getString("err.auth_fail");
            message = MessageFormat.format(message, username, password);
            throw new ServletException(message);
        }
        userPrincipal = new ServletPrincipal(context, realm, username);
    }

    @Override public void logout() throws ServletException {
        userPrincipal = null;
    }

    @Override public Collection<Part> getParts() throws IOException, ServletException {
        if (parts != null) {
            return parts;
        }
        String contentTypeString = getContentType();
        if (contentTypeString == null) {
            throw new ServletException(Context.L10N.getString("err.not_multipart_form_data"));
        }
        ContentType contentType = ContentTypeParser.parse(contentTypeString);
        if (contentType == null || !contentType.isMimeType("multipart/form-data")) {
            throw new ServletException(Context.L10N.getString("err.not_multipart_form_data"));
        }
        String boundary = contentType.getParameter("boundary");
        if (boundary == null) {
            throw new ServletException(Context.L10N.getString("err.no_boundary"));
        }
        ServletDef servletDef = match.servletDef;
        MultipartConfigDef multipartConfig = servletDef.multipartConfig;
        if (multipartConfig == null) {
            throw new IllegalStateException(Context.L10N.getString("err.no_multipart_config"));
        }
        switch (inputStreamState) {
            case NONE:
            case GET_PARTS_CALLED:
                inputStreamState = InputStreamState.GET_PARTS_CALLED;
                long contentLength = getContentLength();
                if (contentLength > multipartConfig.maxRequestSize) {
                    throw new IllegalStateException(Context.L10N.getString("err.request_body_exceeds_maximum_size"));
                }
                MultipartParser parser = new MultipartParser(multipartConfig, boundary);
                parts = parser.parse(getInputStream());
                return parts;
            default:
                throw new IllegalStateException(Context.L10N.getString("err.input_stream_state"));
        }
    }

    @Override public Part getPart(String name) throws IOException, ServletException {
        Collection<Part> parts = getParts();
        for (Part part : parts) {
            if (part.getName().equals(name)) {
                return part;
            }
        }
        return null;
    }

    @Override public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws ServletException {
        // Validate that this request is eligible for upgrade
        if (isResponseAlreadyStarted()) {
            throw new IllegalStateException("Response has already been started");
        }

        // Check if this is a WebSocket upgrade request
        if (!isWebSocketUpgrade()) {
            throw new IllegalStateException("Request is not a valid WebSocket upgrade");
        }

        try {
            // Create the upgrade handler instance
            T handler = handlerClass.getDeclaredConstructor().newInstance();
            
            // Perform the WebSocket upgrade
            performWebSocketUpgrade(handler);
            
            return handler;
            
        } catch (Exception e) {
            throw new ServletException("Failed to create or initialize upgrade handler", e);
        }
    }

    /**
     * Checks if this request is a valid WebSocket upgrade request.
     *
     * @return true if this is a valid WebSocket upgrade request
     */
    private boolean isWebSocketUpgrade() {
        // Use the request headers from this Request object
        Headers requestHeaders = new Headers();
        
        // Convert servlet headers to HTTP headers
        Enumeration<String> headerNames = getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            Enumeration<String> values = getHeaders(name);
            while (values.hasMoreElements()) {
                String value = values.nextElement();
                requestHeaders.add(name, value);
            }
        }
        
        return WebSocketHandshake.isValidWebSocketUpgrade(requestHeaders);
    }

    /**
     * Performs the WebSocket upgrade process.
     *
     * @param upgradeHandler the upgrade handler to use
     * @throws ServletException if the upgrade fails
     */
    private void performWebSocketUpgrade(HttpUpgradeHandler upgradeHandler) throws ServletException {
        try {
            // Get negotiated subprotocol
            String protocol = getHeader("Sec-WebSocket-Protocol");
            
            // Create the WebConnection that will bridge to the servlet
            ServletWebConnection webConnection = new ServletWebConnection(upgradeHandler, 4096);
            
            // Get the HTTPResponseState and perform the upgrade
            HTTPResponseState state = handler.getState();
            state.upgradeToWebSocket(protocol, webConnection.getEventHandler());
            
            // Note: The WebConnectionEventHandler.opened() will call
            // upgradeHandler.init(webConnection) when the connection is established
            
        } catch (IOException e) {
            throw new ServletException("WebSocket upgrade failed: " + e.getMessage(), e);
        }
    }

    @Override public Map<String,String> getTrailerFields() {
        return getRequestTrailerFields();
    }

    @Override public boolean isTrailerFieldsReady() {
        return areTrailerFieldsReady();
    }

    public void setHeader(String name, String value) {
        removeHeaders(name);
        addHeader(name, value);
    }

    private void removeHeaders(String name) {
        for (Iterator<Header> i = headers.iterator(); i.hasNext(); ) {
            Header header = i.next();
            if (header.getName().equalsIgnoreCase(name)) {
                i.remove();
            }
        }
    }

    public void addHeader(String name, String value) {
        if (!Response.MULTIPLE_VALUE.contains(name.toLowerCase())) {
            removeHeaders(name);
        }
        headers.add(new Header(name, value));
    }

    public long getLongHeader(String name) {
        String value = getHeader(name);
        if (value == null) {
            return -1;
        }
        return Long.parseLong(value);
    }

    // -- ServletRequest --

    @Override public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override public Enumeration<String> getAttributeNames() {
        return new IteratorEnumeration<>(attributes.keySet());
    }

    @Override public String getCharacterEncoding() {
        if (encoding != null) {
            return encoding;
        }
        String contentType = getHeader("Content-Type");
        if (contentType == null) {
            return null;
        }
        // Get charset parameter
        StringTokenizer st = new StringTokenizer(contentType, "; ");
        while (st.hasMoreTokens()) {
            String token = st.nextToken();
            int ei = token.indexOf('=');
            if (ei != -1) {
                String key = token.substring(0, ei);
                if ("charset".equals(key)) {
                    String value = token.substring(ei + 1);
                    return unquote(value);
                }
            }
        }
        if (contentType.startsWith("application/x-www-form-urlencoded")) {
            // See servlet 4.0 section 3.12
            return "US-ASCII";
        }
        return null;
    }

    @Override public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
        if (encoding != null) {
            // Check that encoding is supported by InputStreamReader
            if (!Charset.isSupported(encoding)) {
                throw new UnsupportedEncodingException(encoding);
            }
        }
        // OK
        this.encoding = encoding;
    }

    @Override public int getContentLength() {
        return getIntHeader("Content-Length");
    }

    @Override public long getContentLengthLong() {
        return getLongHeader("Content-Length");
    }

    @Override public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override public ServletInputStream getInputStream() {
        switch (inputStreamState) {
            case NONE:
            case GET_INPUT_STREAM_CALLED:
                inputStreamState = InputStreamState.GET_INPUT_STREAM_CALLED;
                return in;
            default:
                throw new IllegalStateException(Context.L10N.getString("err.input_stream_state"));
        }
    }

    @Override public String getParameter(String name) {
        if (!parametersParsed) {
            parseParameters();
        }
        String[] values = parameters.get(name);
        return (values == null || values.length == 0) ? null : values[0];
    }

    @Override public Enumeration<String> getParameterNames() {
        if (!parametersParsed) {
            parseParameters();
        }
        return new IteratorEnumeration<>(parameters.keySet());
    }

    @Override public String[] getParameterValues(String name) {
        if (!parametersParsed) {
            parseParameters();
        }
        return parameters.get(name);
    }

    @Override public Map<String,String[]> getParameterMap() {
        if (!parametersParsed) {
            parseParameters();
        }
        return Collections.unmodifiableMap(parameters);
    }

    /**
     * Parse the request parameters (SRV.4.1).
     */
    private void parseParameters() {
        // Parameters specified in query-string
        if (queryString != null) {
            int start = 0;
            int end = queryString.indexOf('&', start);
            while (end > start) {
                addParameter(parameters, queryString.substring(start, end));
                start = end + 1;
                end = queryString.indexOf('&', start);
            }
            addParameter(parameters, queryString.substring(start));
        }
        // Parameters in x-www-form-urlencoded POST body
        if ("POST".equals(method)) {
            String contentType = getContentType();
            if (contentType != null
                    && contentType.startsWith("application/x-www-form-urlencoded")) {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                try {
                    for (int len = in.read(buf); len != -1; len = in.read(buf)) {
                        sink.write(buf, 0, len);
                    }
                    buf = sink.toByteArray();
                    String body = new String(buf, "US-ASCII");
                    int start = 0;
                    int end = body.indexOf('&', start);
                    while (end > start) {
                        addParameter(parameters, body.substring(start, end));
                        start = end + 1;
                        end = body.indexOf('&', start);
                    }
                    addParameter(parameters, body.substring(start));
                } catch (IOException e) {
                    // XXX log error?
                    e.printStackTrace(System.err);
                }
            }
        }
        parametersParsed = true;
    }

    static void addParameter(Map<String,String[]> parameters, String param) {
        try {
            param = URLDecoder.decode(param, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            RuntimeException e2 = new RuntimeException("UTF-8 not supported");
            e2.initCause(e);
            throw e2;
        }
        String paramName = param;
        String paramValue = null;
        int ei = param.indexOf('=');
        if (ei != -1) {
            paramName = param.substring(0, ei);
            paramValue = param.substring(ei + 1);
        }
        if (paramName.length() == 0) {
            return;
        }
        addParameter(parameters, paramName, paramValue);
    }

    static void addParameter(Map<String,String[]> parameters, String name, String value) {
        String[] values = parameters.get(name);
        if (values == null) {
            values = new String[] {value};
        } else {
            String[] values2 = new String[values.length + 1];
            System.arraycopy(values, 0, values2, 0, values.length);
            values2[values.length] = value;
            values = values2;
        }
        parameters.put(name, values);
    }

    @Override public String getProtocol() {
        return getHTTPVersion().toString();
    }

    @Override public String getScheme() {
        return getHTTPScheme();
    }

    @Override public String getServerName() {
        String host = getHeader("Host");
        if (host == null) {
            host = getLocalName();
            if (host == null) {
                host = getLocalAddr();
            }
        } else {
            int ci = host.indexOf(':');
            if (ci >= 0) {
                host = host.substring(0, ci);
            }
        }
        return host;
    }

    @Override public int getServerPort() {
        String host = getHeader("Host");
        if (host != null) {
            int ci = host.indexOf(':');
            if (ci >= 0) {
                return Integer.parseInt(host.substring(ci + 1));
            }
        }
        return getLocalPort();
    }

    @Override public BufferedReader getReader() throws IOException {
        switch (inputStreamState) {
            case NONE:
            case GET_READER_CALLED:
                String charset = getCharacterEncoding();
                if (charset == null) {
                    // See servlet 4.0 section 3.12
                    charset = context.getRequestCharacterEncoding();
                    if (charset == null) {
                        charset = "ISO-8859-1";
                    }
                }
                inputStreamState = InputStreamState.GET_READER_CALLED;
                return new BufferedReader(new InputStreamReader(in, charset));
            default:
                throw new IllegalStateException(Context.L10N.getString("err.input_stream_state"));
        }
    }

    @Override public String getRemoteAddr() {
        InetSocketAddress remoteAddress = getRemoteSocketAddress();
        if (remoteAddress != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return null;
    }

    @Override public String getRemoteHost() {
        InetSocketAddress remoteAddress = getRemoteSocketAddress();
        if (remoteAddress != null) {
            return remoteAddress.getHostName();
        }
        return null;
    }

    @Override public void setAttribute(String name, Object value) {
        Object oldValue = attributes.put(name, value);
        ServletRequestAttributeEvent event =
            new ServletRequestAttributeEvent(context, this, name, value);
        for (ServletRequestAttributeListener l : context.servletRequestAttributeListeners) {
            if (oldValue == null) {
                l.attributeAdded(event);
            } else {
                l.attributeReplaced(event);
            }
        }
    }

    @Override public void removeAttribute(String name) {
        Object oldValue = attributes.remove(name);
        ServletRequestAttributeEvent event =
            new ServletRequestAttributeEvent(context, this, name, oldValue);
        for (ServletRequestAttributeListener l : context.servletRequestAttributeListeners) {
            l.attributeRemoved(event);
        }
    }

    @Override public Locale getLocale() {
        String acceptLanguage = getHeader("Accept-Language");
        if (acceptLanguage != null) {
            List locales = getLocales(acceptLanguage);
            if (!locales.isEmpty()) {
                Collections.sort(locales);
                return ((AcceptLanguage) locales.get(0)).toLocale();
            }
        }
        return null;
    }

    @Override public Enumeration<Locale> getLocales() {
        String acceptLanguage = getHeader("Accept-Language");
        if (acceptLanguage != null) {
            List<AcceptLanguage> locales = getLocales(acceptLanguage);
            if (!locales.isEmpty()) {
                List<Locale> ret = new LinkedList<>();
                for (AcceptLanguage al : locales) {
                    ret.add(al.toLocale());
                }
                return new IteratorEnumeration<>(ret);
            }
        }
        return new IteratorEnumeration<>(Collections.singleton(Locale.getDefault()));
    }

    List<AcceptLanguage> getLocales(String acceptLanguage) {
        List<AcceptLanguage> ret = new LinkedList<>();
        StringTokenizer st = new StringTokenizer(acceptLanguage, ",");
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            String spec = token;
            double q = 1.0;
            int sci = token.indexOf(';');
            if (sci != -1) {
                spec = token.substring(0, sci);
                String qspec = token.substring(sci + 1).trim();
                if (qspec.startsWith("q=")) {
                    q = parseDouble(qspec.substring(2));
                }
            }
            ret.add(new AcceptLanguage(spec, q));
        }
        return ret;
    }

    /**
     * This method parses an all-numeric double value (i.e. without exponent
     * syntax. It would be easier and faster to use Double.parseDouble.
     */
    static double parseDouble(String text) {
        int di = text.indexOf('.');
        if (di == -1) {
            return (double) Long.parseLong(text);
        } else {
            double d = (double) Long.parseLong(text.substring(0, di));
            int len = text.length() - di;
            for (int i = 1; i < len; i++) {
                char c = text.charAt(di + i);
                int n = Character.digit(c, 10);
                if (n > 0) {
                    d += (n * Math.pow(10.0, -((double) i)));
                }
            }
            return d;
        }
    }

    @Override public boolean isSecure() {
        return secure;
    }

    @Override public RequestDispatcher getRequestDispatcher(String path) {
        // Convert to absolute path
        if (!path.startsWith("/")) {
            String ref = (match.pathInfo == null) ? match.servletPath : match.servletPath + match.pathInfo;
            int si = ref.lastIndexOf('/');
            if (si != -1) {
                ref = ref.substring(0, si + 1);
            }
            path = ref + path;
        }
        return context.getRequestDispatcher(path);
    }

    @Override public String getRealPath(String path) {
        // Convert to absolute path
        if (!path.startsWith("/")) {
            String ref = (match.pathInfo == null) ? match.servletPath : match.servletPath + match.pathInfo;
            int si = ref.lastIndexOf('/');
            if (si != -1) {
                ref = ref.substring(0, si + 1);
            }
            path = ref + path;
        }
        return context.getRealPath(path);
    }

    @Override public int getRemotePort() {
        InetSocketAddress remoteAddress = getRemoteSocketAddress();
        if (remoteAddress != null) {
            return remoteAddress.getPort();
        }
        return -1;
    }

    @Override public String getLocalName() {
        InetSocketAddress localAddress = getLocalSocketAddress();
        if (localAddress != null) {
            return localAddress.getHostName();
        }
        return null;
    }

    @Override public String getLocalAddr() {
        InetSocketAddress localAddress = getLocalSocketAddress();
        if (localAddress != null) {
            return localAddress.getAddress().getHostAddress();
        }
        return null;
    }

    @Override public int getLocalPort() {
        InetSocketAddress localAddress = getLocalSocketAddress();
        if (localAddress != null) {
            return localAddress.getPort();
        }
        return -1;
    }

    // -- 3.0 --

    @Override public ServletContext getServletContext() {
        return context;
    }

    @Override public synchronized AsyncContext startAsync() throws IllegalStateException {
        if (!isAsyncSupported()) {
            throw new IllegalStateException("Async not supported for this request");
        }
        if (asyncContext != null && asyncContext.isCompleted()) {
            throw new IllegalStateException("Async already completed");
        }
        asyncContext = new AsyncContextImpl(handler, this, handler.getResponse());
        return asyncContext;
    }

    @Override public AsyncContext startAsync(ServletRequest request, ServletResponse response) throws IllegalStateException {
        if (!isAsyncSupported()) {
            throw new IllegalStateException("Async not supported for this request");
        }
        while (request instanceof ServletRequestWrapper) {
            request = ((ServletRequestWrapper) request).getRequest();
        }
        if (request != this) {
            throw new IllegalStateException("Request must wrap this request");
        }
        while (response instanceof ServletResponseWrapper) {
            response = ((ServletResponseWrapper) response).getResponse();
        }
        if (response != handler.getResponse()) {
            throw new IllegalStateException("Response must wrap this response");
        }
        if (asyncContext != null && asyncContext.isCompleted()) {
            throw new IllegalStateException("Async already completed");
        }
        asyncContext = new AsyncContextImpl(handler, this, handler.getResponse(), request, response);
        return asyncContext;
    }

    @Override public boolean isAsyncStarted() {
        return asyncContext != null && !asyncContext.isCompleted();
    }

    @Override public boolean isAsyncSupported() {
        // Check if the target servlet supports async
        if (match != null && match.servletDef != null) {
            return match.servletDef.asyncSupported;
        }
        // Default to supporting async
        return true;
    }

    @Override public AsyncContext getAsyncContext() {
        if (asyncContext == null) {
            throw new IllegalStateException("Async not started");
        }
        return asyncContext;
    }

    @Override public DispatcherType getDispatcherType() {
        return (asyncContext != null && !asyncContext.isCompleted()) ? DispatcherType.ASYNC : DispatcherType.REQUEST;
    }
    
    // -- Servlet 4.0 --
    
    /**
     * Creates a new PushBuilder for HTTP/2 server push functionality.
     * 
     * <p>Server push allows the server to proactively send resources that the client
     * will likely request, reducing round-trip latency. This is only supported on
     * HTTP/2 connections.
     * 
     * <p>If server push is not supported (e.g., on HTTP/1.x connections), this method
     * returns null.
     * 
     * @return a new PushBuilder instance, or null if server push is not supported
     * @since Servlet 4.0
     */
    @Override
    public javax.servlet.http.PushBuilder newPushBuilder() {
        // Check if server push is supported
        if (!handler.supportsServerPush()) {
            return null;
        }
        return new ServletPushBuilder(handler, this);
    }

    /**
     * Remove quotes from a quoted string.
     */
    private String unquote(String text) {
        if (text != null) {
            int len = text.length();
            if (len > 1 && text.charAt(0) == '"' && text.charAt(len - 1) == '"') {
                text = text.substring(1, len - 1);
            }
        }
        return text;
    }

    /**
     * Static utility method to remove quotes from a quoted string.
     * Used by other classes in the servlet package.
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

    // -- Debugging --

    public String toString() {
        return String.format("%s %s %s", method, requestTarget, getProtocol());
    }

}
