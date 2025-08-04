/*
 * Request.java
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

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.util.IteratorEnumeration;
import gnu.inet.http.HTTPDateFormat;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
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

    final ServletStream stream;

    String method;
    String requestTarget;
    URI uri;

    boolean secure;
    Collection<Header> headers;
    final PipedOutputStream pipe;
    final RequestInputStream in;
    final Map attributes;
    Map parameters;
    boolean parametersParsed;
    String encoding;
    Thread handlerThread;
    Cookie[] cookies;

    // Determined during servlet resolution
    Context context;
    String contextPath;
    String servletPath;
    String pathInfo;
    String queryString;

    String sessionId;
    Boolean sessionType;
    ServletPrincipal userPrincipal;

    /**
     * Constructor.
     */
    Request(ServletStream stream, int bufferSize, String method, String requestTarget, Collection<Header> headers) throws IOException {
        this.stream = stream;

        attributes = new HashMap();
        parameters = new LinkedHashMap();

        pipe = new PipedOutputStream();
        in = new RequestInputStream(new PipedInputStream(pipe, bufferSize));

        this.method = method;
        this.requestTarget = requestTarget;
        this.headers = headers;
        uri = "*".equals(requestTarget) ? null : URI.create(requestTarget);
        ServletConnection connection = stream.connection;
        this.secure = connection.isSecure();

        attributes.clear();
        parameters.clear();
        parametersParsed = false;
        cookies = null;

        context = null;
        contextPath = null;
        servletPath = null;
        pathInfo = null;
        queryString = null;

        sessionId = null;
        sessionType = null;
        userPrincipal = null;

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

    public String getAuthType() {
        return (userPrincipal == null) ? null : context.authMethod;
    }

    public Cookie[] getCookies() {
        if (cookies != null) {
            return cookies;
        }
        String cookieHeader = getHeader("Cookie");
        if (cookieHeader != null) {
            List tokens = new LinkedList();
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
            List acc = new ArrayList();
            int version = -1;
            for (Iterator i = tokens.iterator(); i.hasNext(); ) {
                String token = ((String) i.next()).trim();
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

    public long getDateHeader(String name) {
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

    public String getHeader(String name) {
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
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

    public Enumeration getHeaders(String name) {
        Collection<String> acc = new ArrayList<>();
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                acc.add(header.getValue());
            }
        }
        return new IteratorEnumeration(acc);
    }

    public Enumeration getHeaderNames() {
        Collection<String> acc = new LinkedHashSet<>();
        for (Header header : headers) {
            acc.add(header.getName());
        }
        return new IteratorEnumeration(acc);
    }

    public int getIntHeader(String name) {
        String value = getHeader(name);
        if (value == null) {
            return -1;
        }
        return Integer.parseInt(value);
    }

    public long getLongHeader(String name) {
        String value = getHeader(name);
        if (value == null) {
            return -1;
        }
        return Long.parseLong(value);
    }

    public String getMethod() {
        return method;
    }

    public String getPathInfo() {
        return pathInfo;
    }

    public String getPathTranslated() {
        if (pathInfo != null) {
            return context.getRealPath(pathInfo);
        }
        return null;
    }

    public String getContextPath() {
        return contextPath;
    }

    public String getQueryString() {
        return queryString;
    }

    public String getRemoteUser() {
        return (userPrincipal == null) ? null : userPrincipal.getName();
    }

    public boolean isUserInRole(String role) {
        return (userPrincipal == null) ? false : userPrincipal.hasRole(role);
    }

    public Principal getUserPrincipal() {
        return userPrincipal;
    }

    public String getRequestedSessionId() {
        return sessionId;
    }

    public String getRequestURI() {
        return uri == null ? null : uri.getRawPath();
    }

    public StringBuffer getRequestURL() {
        // TODO RequestDispatcher stuff
        String s = (uri == null) ? "" : uri.toString();
        int qi = s.indexOf('?');
        if (qi != -1) {
            s = s.substring(0, qi);
        }
        return new StringBuffer(s);
    }

    public String getServletPath() {
        return servletPath;
    }

    public HttpSession getSession(boolean create) {
        synchronized (context.sessions) {
            Session session = (Session) context.sessions.get(sessionId);
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
            StringBuffer buf = new StringBuffer();
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

    public HttpSession getSession() {
        return getSession(true);
    }

    public boolean isRequestedSessionIdValid() {
        synchronized (context.sessions) {
            return context.sessions.containsKey(sessionId);
        }
    }

    public boolean isRequestedSessionIdFromCookie() {
        return Boolean.TRUE == sessionType;
    }

    public boolean isRequestedSessionIdFromURL() {
        return Boolean.FALSE == sessionType;
    }

    public boolean isRequestedSessionIdFromUrl() {
        return isRequestedSessionIdFromURL();
    }

    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    public Enumeration getAttributeNames() {
        return new IteratorEnumeration(attributes.keySet());
    }

    public String getCharacterEncoding() {
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
                    return ContextRequestDispatcher.unq(value);
                }
            }
        }
        return null;
    }

    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
        if (encoding != null) {
            // Check that encoding is supported by InputStreamReader
            ByteArrayInputStream dummy = new ByteArrayInputStream(new byte[0]);
            InputStreamReader reader = new InputStreamReader(dummy, encoding);
        }
        // OK
        this.encoding = encoding;
    }

    public int getContentLength() {
        return getIntHeader("Content-Length");
    }

    public long getContentLengthLong() {
        return getLongHeader("Content-Length");
    }

    public String getContentType() {
        return getHeader("Content-Type");
    }

    public ServletInputStream getInputStream() {
        return in;
    }

    public String getLocalAddr() {
        try {
            InetSocketAddress localAddress = (InetSocketAddress) stream.connection.getChannel().getLocalAddress();
            return localAddress.getAddress().getHostAddress();
        } catch (IOException e) {
            return null;
        }
    }

    public String getLocalName() {
        try {
            InetSocketAddress localAddress = (InetSocketAddress) stream.connection.getChannel().getLocalAddress();
            return localAddress.getHostName();
        } catch (IOException e) {
            return null;
        }
    }

    public int getLocalPort() {
        try {
            InetSocketAddress localAddress = (InetSocketAddress) stream.connection.getChannel().getLocalAddress();
            return localAddress.getPort();
        } catch (IOException e) {
            return -1;
        }
    }

    public String getParameter(String name) {
        if (!parametersParsed) {
            parseParameters();
        }
        String[] values = (String[]) parameters.get(name);
        return (values == null || values.length == 0) ? null : values[0];
    }

    public Enumeration getParameterNames() {
        if (!parametersParsed) {
            parseParameters();
        }
        return new IteratorEnumeration(parameters.keySet());
    }

    public String[] getParameterValues(String name) {
        if (!parametersParsed) {
            parseParameters();
        }
        return (String[]) parameters.get(name);
    }

    public Map getParameterMap() {
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

    static void addParameter(Map parameters, String param) {
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

    static void addParameter(Map parameters, String name, String value) {
        String[] values = (String[]) parameters.get(name);
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

    public String getProtocol() {
        return stream.getVersion().toString();
    }

    public String getScheme() {
        return stream.getScheme();
    }

    public String getServerName() {
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

    public int getServerPort() {
        String host = getHeader("Host");
        if (host != null) {
            int ci = host.indexOf(':');
            if (ci >= 0) {
                return Integer.parseInt(host.substring(ci + 1));
            }
        }
        return getLocalPort();
    }

    public BufferedReader getReader() throws IOException {
        // See SRV.4.9
        String charset = getCharacterEncoding();
        if (charset == null) {
            return new BufferedReader(new InputStreamReader(in, "ISO-8859-1"));
        }
        return new BufferedReader(new InputStreamReader(in, charset));
    }

    public String getRemoteAddr() {
        try {
            InetSocketAddress remoteAddress = (InetSocketAddress) stream.connection.getChannel().getRemoteAddress();
            return remoteAddress.getAddress().getHostAddress();
        } catch (IOException e) {
            return null;
        }
    }

    public String getRemoteHost() {
        try {
            InetSocketAddress remoteAddress = (InetSocketAddress) stream.connection.getChannel().getRemoteAddress();
            return remoteAddress.getHostName();
        } catch (IOException e) {
            return null;
        }
    }

    public int getRemotePort() {
        try {
            InetSocketAddress remoteAddress = (InetSocketAddress) stream.connection.getChannel().getRemoteAddress();
            return remoteAddress.getPort();
        } catch (IOException e) {
            return -1;
        }
    }

    public void setAttribute(String name, Object value) {
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

    public void removeAttribute(String name) {
        Object oldValue = attributes.remove(name);
        ServletRequestAttributeEvent event =
            new ServletRequestAttributeEvent(context, this, name, oldValue);
        for (ServletRequestAttributeListener l : context.servletRequestAttributeListeners) {
            l.attributeRemoved(event);
        }
    }

    public Locale getLocale() {
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

    public Enumeration getLocales() {
        String acceptLanguage = getHeader("Accept-Language");
        if (acceptLanguage != null) {
            List locales = getLocales(acceptLanguage);
            if (!locales.isEmpty()) {
                List ret = new LinkedList();
                Collections.sort(locales);
                for (Iterator i = ret.iterator(); i.hasNext(); ) {
                    ret.add(((AcceptLanguage) locales.get(0)).toLocale());
                }
                return new IteratorEnumeration(ret);
            }
        }
        return null;
    }

    List getLocales(String acceptLanguage) {
        List ret = new LinkedList();
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

    public boolean isSecure() {
        return secure;
    }

    public RequestDispatcher getRequestDispatcher(String path) {
        // Convert to absolute path
        if (!path.startsWith("/")) {
            String ref = (pathInfo == null) ? servletPath : servletPath + pathInfo;
            int si = ref.lastIndexOf('/');
            if (si != -1) {
                ref = ref.substring(0, si + 1);
            }
            path = ref + path;
        }
        return context.getRequestDispatcher(path);
    }

    public String getRealPath(String path) {
        // Convert to absolute path
        if (!path.startsWith("/")) {
            String ref = (pathInfo == null) ? servletPath : servletPath + pathInfo;
            int si = ref.lastIndexOf('/');
            if (si != -1) {
                ref = ref.substring(0, si + 1);
            }
            path = ref + path;
        }
        return context.getRealPath(path);
    }

    public String toString() {
        return String.format("%s %s %s", method, requestTarget, getProtocol());
    }

    // -- 3.0 --

    public ServletContext getServletContext() {
        return context;
    }

    public AsyncContext startAsync() throws IllegalStateException {
        // TODO
        return null;
    }

    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse)
        throws IllegalStateException {
            // TODO
            return null;
        }

    public boolean isAsyncStarted() {
        return false;
    }

    public boolean isAsyncSupported() {
        return false;
    }

    public AsyncContext getAsyncContext() {
        // TODO
        return null;
    }

    public DispatcherType getDispatcherType() {
        // TODO
        return null;
    }

    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
        // TODO
        return false;
    }

    public void login(String username, String password) throws ServletException {
        // TODO
    }

    public void logout() throws ServletException {
        // TODO
    }

    public Collection<Part> getParts() throws IOException, ServletException {
        // TODO
        return null;
    }

    public Part getPart(java.lang.String name) throws IOException, ServletException {
        // TODO
        return null;
    }

    // -- 4.0 --

    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
        throw new UnsupportedOperationException();
    }

    public String changeSessionId() {
        return null;
    }

}
