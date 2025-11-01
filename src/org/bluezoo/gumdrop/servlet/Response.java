/*
 * Response.java
 * Copyright (C) 2005, 2013 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.http.HTTPConstants;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Stream;
import gnu.inet.http.HTTPDateFormat;

import java.io.*;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.servlet.Servlet;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

/**
 * An HTTP response.
 * This forwards all write operations through to the underlying socket
 * channel.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Response implements HttpServletResponse {

    static final DateFormat dateFormat = new HTTPDateFormat();
    static final DateFormat expiresDateFormat = new SimpleDateFormat("EEEE, dd-MM-yyyy HH:mm:ss zzz");
    static final CharsetEncoder utf8Encoder = Charset.forName("UTF-8").newEncoder();

    /**
     * For these header names, it is permitted to have multiple headers of
     * the same name.
     * Otherwise we won't allow addHeader to create multiple headers with
     * the same name, it will quietly remove any previous header set.
     * This is to conform to RFC 9110.
     */
    static final Collection<String> MULTIPLE_VALUE = new TreeSet(Arrays.asList(new String[] {
        "set-cookie", "link", "prefer", "accept", "accept-charset", "accept-encoding", "allow",
        "accept-language", "cache-control", "connection", "content-encoding", "transfer-encoding", "via",
        "warning"
    }));

    ServletStream stream;
    Request request;
    int bufferSize;
    final List<Header> headers;

    Context context;
    int statusCode;
    String errorMessage;
    boolean committed;
    boolean errorCondition;
    String charset;
    OutputStream out;
    ServletOutputStream outputStream;
    PrintWriter writer;
    Locale locale;
    long contentLength = -1L;

    Response(ServletStream stream, Request request, int bufferSize) {
        this.stream = stream;
        this.request = request;
        this.bufferSize = bufferSize;
        headers = new ArrayList<>();
        locale = request.getLocale();
        if (locale == null) {
            locale = Locale.getDefault();
        }
    }

    public void addCookie(Cookie cookie) {
        String name = cookie.getName();
        String value = cookie.getValue();
        int maxAge = cookie.getMaxAge();
        String path = cookie.getPath();
        String domain = cookie.getDomain();
        boolean secure = cookie.getSecure();
        StringBuffer buf = new StringBuffer();
        buf.append(name);
        buf.append('=');
        buf.append(value);
        if (maxAge != -1) {
            Date now = new Date();
            Calendar cal = Calendar.getInstance();
            cal.setTime(now);
            cal.add(Calendar.SECOND, maxAge);
            Date expires = cal.getTime();
            buf.append("; expires=");
            buf.append(expiresDateFormat.format(expires));
        }
        if (path != null) {
            buf.append("; path=");
            buf.append(path);
        }
        if (domain != null) {
            buf.append("; domain=");
            buf.append(domain);
        }
        if (secure) {
            buf.append("; secure");
        }
        addHeader("Set-Cookie", buf.toString());
    }

    public boolean containsHeader(String name) {
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public String encodeURL(String url) {
        if (request.sessionId != null) {
            url = encodeSessionId(url, request.sessionId);
        }
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            RuntimeException e2 = new RuntimeException("UTF-8 not supported");
            e2.initCause(e);
            throw e2;
        }
    }

    public String encodeRedirectURL(String url) {
        if (request.sessionId != null) {
            url = encodeSessionId(url, request.sessionId);
        }
        // XXX make URL absolute here?
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            RuntimeException e2 = new RuntimeException("UTF-8 not supported");
            e2.initCause(e);
            throw e2;
        }
    }

    String encodeSessionId(String url, String sessionId) {
        int qi = url.indexOf('?');
        if (qi == -1) {
            return url + "?jsessionid=" + sessionId;
        } else {
            String queryString = url.substring(qi + 1);
            StringBuffer buf = new StringBuffer(url.substring(0, qi + 1));
            StringTokenizer st = new StringTokenizer(queryString, "&");
            boolean seen = false;
            while (st.hasMoreTokens()) {
                String token = st.nextToken();
                if (token.startsWith("jsessionid=")) {
                    token = "jsessionid=" + sessionId;
                    seen = true;
                }
                buf.append(token);
            }
            if (!seen) {
                buf.append("jsessionid=" + sessionId);
            }
            return buf.toString();
        }
    }

    public String encodeUrl(String url) {
        return encodeURL(url);
    }

    public String encodeRedirectUrl(String url) {
        return encodeRedirectURL(url);
    }

    public void sendError(int sc) throws IOException {
        sendError(sc, HTTPConstants.getMessage(sc), null, null);
    }

    public void sendError(int sc, String msg) throws IOException {
        sendError(sc, msg, null, null);
    }

    void sendError(int sc, String msg, String servletName, Throwable err) throws IOException {
        if (committed) {
            throw new IllegalStateException();
        }
        // Set the status code
        statusCode = sc;
        if (!errorCondition && context != null && !context.errorPages.isEmpty()) {
            // Avoid infinite loop if error-page servlet also calls sendError
            errorCondition = true;
            // Locate an error-page for this code/exception
            String location = null;
            String exceptionType = (err == null) ? null : err.getClass().getName();
            for (ErrorPage errorPage : context.errorPages) {
                if (errorPage.errorCode == sc
                        || (exceptionType != null
                                && exceptionType.equals(errorPage.exceptionType))) {
                    location = errorPage.location;
                    break;
                }
            }
            if (location != null) {
                try {
                    // Locate the corresponding servlet
                    ServletMatch match = new ServletMatch();
                    context.matchServletMapping(location, match);
                    if (match.servletDef == null) {
                        match.servletDef = context.defaultServletDef;
                        match.servletPath = "/";
                        match.pathInfo = "/".equals(location) ? null : location.substring(1);
                    }
                    Servlet servlet = context.loadServlet(match.servletDef);
                    ErrorRequest errorRequest =
                            new ErrorRequest(
                                    request,
                                    match.servletPath,
                                    match.pathInfo,
                                    sc,
                                    err,
                                    servletName);
                    servlet.service(errorRequest, this);
                    return;
                } catch (Exception e) {
                    err = e;
                }
            }
        }
        errorCondition = true;
        // No error-page or error processing error-page
        if (!committed) {
            if (sc == 304) {
                // HTTP 10.3.5: MUST NOT send a message-body.
                commit();
                return;
            }
            String httpMessage = HTTPConstants.getMessage(sc);
            StringBuffer buf = new StringBuffer();
            buf.append("<html>\r\n\t<head>\r\n\t\t<title>");
            buf.append(Integer.toString(sc));
            if (httpMessage != null) {
                buf.append(' ');
                buf.append(httpMessage);
            }
            buf.append("</title>\r\n\t\t<style type='text/css'>\r\n");
            buf.append("h1 {\r\n");
            buf.append("\tbackground: #cccccc;\r\n");
            buf.append("\tmargin-top: 0.5em;\r\n");
            buf.append("\tmargin-bottom: 1em;\r\n");
            buf.append("}\r\n");
            buf.append("servlet-name {\r\n");
            buf.append("\tfont-weight: bold;\r\n");
            buf.append("\tmargin-bottom: 1em;\r\n");
            buf.append("}\r\n");
            buf.append("stack-trace {\r\n");
            buf.append("\tmargin-top: 1em;\r\n");
            buf.append("}\r\n");
            buf.append(".server-info {\r\n");
            buf.append("\tbackground: #cccccc;\r\n");
            buf.append("\tfont-size: small;\r\n");
            buf.append("\tmargin-top: 0.5em;\r\n");
            buf.append("\tmargin-bottom: 0.5em;\r\n");
            buf.append("}\r\n");
            buf.append("</style>\r\n\t</head>\r\n\t<body>\r\n\t\t<h1>");
            buf.append(Integer.toString(sc));
            if (httpMessage != null) {
                buf.append(' ');
                buf.append(httpMessage);
            }
            buf.append("</h1>\r\n");
            if (servletName != null) {
                buf.append("\t\t<p class='servlet-name'>");
                buf.append(servletName);
                buf.append("</p>\r\n");
            }
            if (msg != null) {
                buf.append("\t\t<p class='message'>");
                buf.append(msg);
                buf.append("</p>\r\n");
            }
            if (err != null) {
                StringWriter sink = new StringWriter();
                PrintWriter w = new PrintWriter(sink, true);
                err.printStackTrace(w);
                w.flush();
                buf.append("\t\t<pre class='stack-trace'>\r\n");
                buf.append(sink.toString());
                buf.append("\r\n\t\t</pre>\r\n");
            }
            buf.append("\t\t<p class='server-info'>");
            if (context != null) {
                buf.append(context.getServerInfo());
            } else {
                buf.append("gumdrop");
            }
            buf.append("</p>\r\n");
            buf.append("\t</body>\r\n</html>\r\n");

            CharBuffer charBuf = CharBuffer.wrap(buf);
            setContentType("text/html; charset=UTF-8");
            ByteBuffer byteBuf = utf8Encoder.encode(charBuf);
            setContentLength(byteBuf.remaining());
            commit();
            stream.writeBody(byteBuf);
        }
    }

    void commit() throws IOException {
        // If the servlet did not set the Content-Length, close the connection
        if (getContentLength() == -1) {
            stream.explicitCloseConnection = true;
        }
        if (stream.explicitCloseConnection) {
            setHeader("Connection", "close");
        }
        // Session management
        if (request.sessionId != null) {
            // Add JSESSIONID cookie
            Session session;
            synchronized (context.sessions) {
                session = (Session) context.sessions.get(request.sessionId);
            }
            if (session != null) {
                Cookie cookie = new Cookie("JSESSIONID", request.sessionId);
                cookie.setMaxAge(session.maxInactiveInterval);
                addCookie(cookie);
            }
        }
        stream.commit(statusCode, headers);
        committed = true;
    }

    public void sendRedirect(String location) throws IOException {
        // Convert relative URIs to absolute
        URI uri = URI.create(location);
        if (!uri.isAbsolute()) {
            URI requestUri = request.getURI();
            uri = requestUri.resolve(uri);
        }
        statusCode = 302;
        setHeader("Location", uri.toString());
        setContentLength(0);
        commit();
    }

    public void setDateHeader(String name, long date) {
        setHeader(name, dateFormat.format(new Date(date)));
    }

    public void addDateHeader(String name, long date) {
        addHeader(name, dateFormat.format(new Date(date)));
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
        if (!MULTIPLE_VALUE.contains(name.toLowerCase())) {
            removeHeaders(name);
        }
        headers.add(new Header(name, value));
    }

    public String getHeader(String name) {
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    public void setIntHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    public void addIntHeader(String name, int value) {
        addHeader(name, Integer.toString(value));
    }

    public void setLongHeader(String name, long value) {
        setHeader(name, Long.toString(value));
    }

    public void addLongHeader(String name, long value) {
        addHeader(name, Long.toString(value));
    }

    long getContentLengthLong() {
        String contentLength = getHeader("Content-Length");
        if (contentLength != null) {
            return Long.parseLong(contentLength);
        }
        return -1L;
    }

    public void setStatus(int sc) {
        statusCode = sc;
    }

    public void setStatus(int sc, String msg) {
        setStatus(sc);
    }

    public String getCharacterEncoding() {
        if (charset != null) {
            return charset;
        }
        String contentType = getContentType();
        if (contentType != null) {
            // Find charset parameter
            StringTokenizer st = new StringTokenizer(contentType, "; ");
            while (st.hasMoreTokens()) {
                String token = st.nextToken();
                int ei = token.indexOf('=');
                if (ei != -1) {
                    String key = token.substring(0, ei);
                    if ("charset".equals(key)) {
                        String value = token.substring(ei + 1);
                        return Request.unq(value);
                    }
                }
            }
        }
        String encoding = context.getEncoding(locale);
        if (encoding != null) {
            return encoding;
        }
        return "ISO-8859-1"; // SRV.5.4
    }

    public String getContentType() {
        String contentType = getHeader("Content-Type");
        if (contentType == null) {
            return null;
        }
        if (charset != null) {
            // Find charset parameter and replace or add if necessary
            StringBuffer buf = new StringBuffer();
            StringTokenizer st = new StringTokenizer(contentType, "; ");
            while (st.hasMoreTokens()) {
                String token = st.nextToken();
                int ei = token.indexOf('=');
                if (ei != -1) {
                    String key = token.substring(0, ei);
                    if ("charset".equals(key)) {
                        token = key + "=" + charset;
                    }
                }
                if (buf.length() > 0) {
                    buf.append("; ");
                }
                buf.append(token);
            }
            contentType = buf.toString();
            setHeader("Content-Type", contentType);
        }
        return contentType;
    }

    public int getContentLength() {
        String value = getHeader("Content-Length");
        return (value != null) ? Integer.parseInt(value) : -1;
    }

    public ServletOutputStream getOutputStream() throws IOException {
        if (committed) {
            throw new IllegalStateException("already committed");
        }
        commit();
        if (writer != null) {
            throw new IllegalStateException();
        }
        if (outputStream == null) {
            out = new ResponseOutputStream(this, bufferSize);
            outputStream = new ServletOutputStreamWrapper(out);
        }
        return outputStream;
    }

    public PrintWriter getWriter() throws IOException {
        if (committed) {
            throw new IllegalStateException("already committed");
        }
        String encoding = getCharacterEncoding();
        if (encoding == null) {
            encoding = context.getResponseCharacterEncoding();
            if (encoding == null) {
                // servlet 4.0 section 4.6
                encoding = "ISO-8859-1";
            }
        }
        Charset c = Charset.forName(encoding);
        commit();
        if (outputStream != null) {
            throw new IllegalStateException();
        }
        if (writer == null) {
            out = new ResponseOutputStream(this, bufferSize);
            writer = new PrintWriter(new OutputStreamWriter(out, c));
        }
        return writer;
    }

    public void setCharacterEncoding(String charset) {
        this.charset = charset;
    }

    public void setContentLength(int len) {
        setIntHeader("Content-Length", len);
    }

    public void setContentLengthLong(long len) {
        setLongHeader("Content-Length", len);
    }

    public void setContentType(String type) {
        setHeader("Content-Type", type);
    }

    public void setBufferSize(int size) {
        if (committed) {
            throw new IllegalStateException("already committed");
        }
        bufferSize = Math.max(size, 1);
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public void flushBuffer() throws IOException {
        if (!committed) {
            commit();
        } else if (outputStream != null) {
            outputStream.flush();
        } else if (writer != null) {
            writer.flush();
        }
    }

    public void resetBuffer() {
        if (committed) {
            throw new IllegalStateException();
        }
        // NOOP no buffer for status-line or headers
    }

    // Called by worker thread once servlet processing is complete
    void endResponse() {
        stream.endResponse();
    }

    public boolean isCommitted() {
        return committed;
    }

    public void reset() {
        committed = false;
        errorCondition = false;
        resetBuffer();
        headers.clear();
        statusCode = 200;
        outputStream = null;
        writer = null;
        locale = Locale.getDefault();
        charset = null;
        bufferSize = 4096; // TODO make configurable

        if (stream.isCloseConnection()) {
            setHeader("Connection", "close");
        }
    }

    public void setLocale(Locale locale) {
        if (!committed) {
            this.locale = locale;
        }
    }

    public Locale getLocale() {
        return locale;
    }

    public String toString() {
        return String.format("%03d", statusCode);
    }

    // -- 3.0 --

    public int getStatus() {
        return statusCode;
    }

    public Collection<String> getHeaders(String name) {
        List<String> ret = new ArrayList<>();
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                ret.add(header.getValue());
            }
        }
        return ret.isEmpty() ? null : Collections.unmodifiableList(ret);
    }

    public Collection<String> getHeaderNames() {
        Set<String> ret = new LinkedHashSet<>();
        for (Header header : headers) {
            ret.add(header.getName());
        }
        return Collections.unmodifiableSet(ret);
    }

}
