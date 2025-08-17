/*
 * Request.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.util.IteratorEnumeration;
import gnu.inet.http.HTTPDateFormat;
import gnu.inet.util.BASE64;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URI;
import java.net.ProtocolException;
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

    static final DateFormat dateFormat = new HTTPDateFormat();
    private static final byte COLON = 0x3a;

    final ServletStream stream;

    String method;
    String requestTarget;
    URI uri;

    boolean secure;
    Collection<Header> headers;
    final PipedOutputStream pipe;
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

    StreamAsyncContext asyncContext;

    /**
     * Constructor.
     */
    Request(ServletStream stream, int bufferSize, String method, String requestTarget, Collection<Header> headers) throws IOException {
        this.stream = stream;
        pipe = new PipedOutputStream();
        in = new RequestInputStream(new PipedInputStream(pipe, bufferSize));
        this.method = method;
        this.requestTarget = requestTarget;
        this.headers = headers;
        uri = "*".equals(requestTarget) ? null : URI.create(requestTarget);
        ServletConnection connection = stream.connection;
        this.secure = connection.isSecure();

        if (secure) {
            Certificate[] certificates = connection.getPeerCertificates();
            String cipherSuite = connection.getCipherSuite();
            int keySize = connection.getKeySize();
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
        if (sessionId != null) {
            // Update session last accessed time
            synchronized (context.sessions) {
                Session session = (Session) context.sessions.get(sessionId);
                if (session != null) {
                    session.lastAccessedTime = System.currentTimeMillis();
                }
            }
        }
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

    /* TODO @Override public PushBuilder newPushBuilder() {
    }*/

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
        // TODO RequestDispatcher stuff
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
        synchronized (context.sessions) {
            Session session = context.sessions.get(sessionId);
            if (session == null) {
                if (!create) {
                    return null;
                }
                if (sessionId == null) {
                    sessionId = createSessionId();
                }
                session = new Session(context, sessionId);
                context.sessions.put(sessionId, session);

                HttpSessionEvent event = new HttpSessionEvent(session);
                for (HttpSessionListener l : context.sessionListeners) {
                    l.sessionCreated(event);
                }
            }
            return session;
        }
    }

    String createSessionId() {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            InetSocketAddress remoteAddress = (InetSocketAddress) stream.connection.getChannel().getRemoteAddress();
            byte[] address = remoteAddress.getAddress().getAddress();
            long time = System.currentTimeMillis();
            long random = (long) Math.random();

            md5.update(address);
            for (int i = 0; i < 8; i++) {
                md5.update((byte) (time << i));
            }
            for (int i = 0; i < 8; i++) {
                md5.update((byte) (random << i));
            }
            byte[] all = md5.digest();
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < all.length; i++) {
                int c = (int) all[i] & 0xff;
                buf.append(Character.forDigit(c / 16, 16));
                buf.append(Character.forDigit(c % 16, 16));
            }
            return buf.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw (RuntimeException) new RuntimeException().initCause(e);
        }
    }

    private int longToBytes(byte[] buf, int off, long value) {
        for (int i = 0; i < 8; i++) {
            buf[off++] = (byte) (value << i);
        }
        return off;
    }

    @Override public HttpSession getSession() {
        return getSession(true);
    }

    @Override public String changeSessionId() { // @since 3.1
        synchronized (context.sessions) {
            Session session = (Session) context.sessions.remove(sessionId);
            if (session == null) {
                throw new IllegalStateException("No session associated with request");
            }
            sessionId = createSessionId();
            context.sessions.put(sessionId, session);
            return sessionId;
        }
    }

    @Override public boolean isRequestedSessionIdValid() {
        synchronized (context.sessions) {
            return context.sessions.containsKey(sessionId);
        }
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
        // Authentication required
        String username = null;
        String realm = context.getRealmName();
        String authMethod = context.getAuthMethod();
        if (HttpServletRequest.BASIC_AUTH.equals(authMethod)) {
            String authorization = getHeader("Authorization");
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
        } else if (HttpServletRequest.DIGEST_AUTH.equals(authMethod)) {
            String authorization = getHeader("Authorization");
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
            String method = getMethod();
            String digestUri = getRequestURI();
            String requestQueryString = getQueryString();
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
        } else if (HttpServletRequest.FORM_AUTH.equals(authMethod)) {
            username = getParameter("j_username");
            String requestPassword = getParameter("j_password");
            if (username == null) {
                response.sendRedirect(context.getFormLoginPage());
                return false;
            } else {
                String password = context.getPassword(realm, username);
                if (requestPassword == null || !requestPassword.equals(password)) {
                    String message = Context.L10N.getString("err.auth_fail");
                    message = MessageFormat.format(message, username, requestPassword);
                    Context.LOGGER.warning(message);
                    response.sendRedirect(context.getFormErrorPage());
                    return false;
                }
            }
        } else if (HttpServletRequest.CLIENT_CERT_AUTH.equals(authMethod)) {
            // TODO HTTP client cert
            return false;
        } else {
            String message = Context.L10N.getString("http.unknown_auth_mechanism");
            message = MessageFormat.format(message, authMethod);
            response.sendError(500, message);
            return false;
        }
        // associate principal with request
        userPrincipal = new ServletPrincipal(context, realm, username);
        return true;
    }

    void requireAuthentication(HttpServletResponse response, String scheme) throws ServletException, IOException {
        String realm = context.getRealmName();
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
            ret[j++] = Character.forDigit(c % 0x10, 0x10);                                                                      }
        return new String(ret);
    }

    @Override public void login(String username, String password) throws ServletException {
        String realm = context.getRealmName();
        String realmPassword = context.getPassword(realm, username);
        if (userPrincipal != null || password == null || !password.equals(realmPassword)) {
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
        // TODO
        return null;
    }

    @Override public Part getPart(String name) throws IOException, ServletException {
        // TODO
        return null;
    }

    @Override public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override public Map<String,String> getTrailerFields() {
        return stream.getTrailerFields();
    }

    @Override public boolean isTrailerFieldsReady() {
        return stream.isTrailerFieldsReady();
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
                    return unq(value);
                }
            }
        }
        return null;
    }

    @Override public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
        if (encoding != null) {
            // Check that encoding is supported by InputStreamReader
            ByteArrayInputStream dummy = new ByteArrayInputStream(new byte[0]);
            InputStreamReader reader = new InputStreamReader(dummy, encoding);
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
        return in;
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
        return stream.getVersion().toString();
    }

    @Override public String getScheme() {
        return stream.getScheme();
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
        // See SRV.4.9
        String charset = getCharacterEncoding();
        if (charset == null) {
            return new BufferedReader(new InputStreamReader(in, "ISO-8859-1"));
        }
        return new BufferedReader(new InputStreamReader(in, charset));
    }

    @Override public String getRemoteAddr() {
        try {
            InetSocketAddress remoteAddress = (InetSocketAddress) stream.connection.getChannel().getRemoteAddress();
            return remoteAddress.getAddress().getHostAddress();
        } catch (IOException e) {
            return null;
        }
    }

    @Override public String getRemoteHost() {
        try {
            InetSocketAddress remoteAddress = (InetSocketAddress) stream.connection.getChannel().getRemoteAddress();
            return remoteAddress.getHostName();
        } catch (IOException e) {
            return null;
        }
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
        try {
            InetSocketAddress remoteAddress = (InetSocketAddress) stream.connection.getChannel().getRemoteAddress();
            return remoteAddress.getPort();
        } catch (IOException e) {
            return -1;
        }
    }

    @Override public String getLocalName() {
        try {
            InetSocketAddress localAddress = (InetSocketAddress) stream.connection.getChannel().getLocalAddress();
            return localAddress.getHostName();
        } catch (IOException e) {
            return null;
        }
    }

    @Override public String getLocalAddr() {
        try {
            InetSocketAddress localAddress = (InetSocketAddress) stream.connection.getChannel().getLocalAddress();
            return localAddress.getAddress().getHostAddress();
        } catch (IOException e) {
            return null;
        }
    }

    @Override public int getLocalPort() {
        try {
            InetSocketAddress localAddress = (InetSocketAddress) stream.connection.getChannel().getLocalAddress();
            return localAddress.getPort();
        } catch (IOException e) {
            return -1;
        }
    }

    // -- 3.0 --

    @Override public ServletContext getServletContext() {
        return context;
    }

    @Override public synchronized AsyncContext startAsync() throws IllegalStateException {
        // This mechanism is a bit redundant for the gumdrop architecture
        // since we are already separating the connection-handling threads
        // from the servlet processing and the servlet can't block any other
        // connections.
        if (asyncContext != null || stream.isClosed()) {
            throw new IllegalStateException();
        }
        asyncContext = new StreamAsyncContext(stream);
        return asyncContext;
    }

    @Override public AsyncContext startAsync(ServletRequest request, ServletResponse response) throws IllegalStateException {
        while (request instanceof ServletRequestWrapper) {
            request = ((ServletRequestWrapper) request).getRequest();
        }
        if (request != this) {
            throw new IllegalStateException();
        }
        while (response instanceof ServletResponseWrapper) {
            response = ((ServletResponseWrapper) response).getResponse();
        }
        if (response != this.stream.response) {
            throw new IllegalStateException();
        }
        return startAsync();
    }

    @Override public boolean isAsyncStarted() {
        return (asyncContext != null);
    }

    @Override public boolean isAsyncSupported() {
        return false; // TODO make true when StreamAsyncContext is fully implemented
    }

    @Override public AsyncContext getAsyncContext() {
        return asyncContext;
    }

    @Override public DispatcherType getDispatcherType() {
        return (asyncContext != null) ? DispatcherType.ASYNC : DispatcherType.REQUEST;
    }

    // -- Debugging --

    public String toString() {
        return String.format("%s %s %s", method, requestTarget, getProtocol());
    }

}
