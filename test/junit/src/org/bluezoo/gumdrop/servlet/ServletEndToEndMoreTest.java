/*
 * ServletEndToEndMoreTest.java
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


package org.bluezoo.gumdrop.servlet;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;
import jakarta.servlet.http.WebConnection;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.servlet.ServletEndToEndTest.Result;
import org.bluezoo.gumdrop.servlet.ServletEndToEndTest.StubState;
import org.bluezoo.gumdrop.servlet.ServletEndToEndTest.TestContainer;
import org.bluezoo.gumdrop.servlet.ServletEndToEndTest.TestRealm;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives requests through {@link ServletHandler} against several in-memory
 * web applications to cover the response, request, dispatcher and security
 * paths that {@link ServletEndToEndTest} leaves alone: response headers and
 * encodings, default error pages, session URL rewriting, cookie parsing,
 * parameter decoding, multipart rejection, asynchronous misuse, transport
 * guarantees, deny-all and role constraints, FORM and CLIENT-CERT login.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletEndToEndMoreTest {

    static final StringBuffer ASYNC_LOG = new StringBuffer();
    static volatile String asyncAfterComplete;
    static volatile HttpServletRequest stashedRequest;
    static volatile HttpServletResponse stashedResponse;

    /** Renders non-ASCII characters as escapes so that output encoding is irrelevant. */
    static String show(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 126) {
                sb.append(String.format("\\u%04x", Integer.valueOf(c)));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String param(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null ? "" : v;
    }

    // ===== Response servlet =====

    /** Exercises {@link Response} and its wrappers by mode. */
    public static class RespServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        @SuppressWarnings("deprecation")
        protected void service(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            String mode = param(req, "mode");
            if ("ctype".equals(mode)) {
                ctype(resp);
            } else if ("misc".equals(mode)) {
                misc(resp);
            } else if ("encode".equals(mode)) {
                HttpSession s = req.getSession();
                StringBuilder sb = new StringBuilder();
                sb.append(resp.encodeURL("/a")).append('|');
                sb.append(resp.encodeURL("/a?x=1")).append('|');
                sb.append(resp.encodeURL("/a?x=1&jsessionid=old&y=2")).append('|');
                sb.append(resp.encodeURL("/a?")).append('|');
                sb.append(unwrap(resp).encodeUrl("/z")).append('|');
                sb.append(resp.encodeRedirectURL("rel")).append('|');
                sb.append(unwrap(resp).encodeRedirectUrl("http://other.example/p?q=1")).append('|');
                sb.append(s != null);
                resp.getWriter().print(sb);
            } else if ("e404".equals(mode)) {
                resp.getWriter().print("discarded");
                resp.sendError(404, "<b>missing</b>");
            } else if ("ctype-wire".equals(mode)) {
                resp.setContentType("text/html");
                resp.setCharacterEncoding("UTF-8");
                resp.getWriter().print("x");
            } else if ("e404nomsg".equals(mode)) {
                resp.sendError(404);
            } else if ("e599".equals(mode)) {
                resp.sendError(599);
            } else if ("e304".equals(mode)) {
                resp.sendError(304);
            } else if ("e400".equals(mode)) {
                resp.setHeader("Retry-After", "5");
                resp.sendError(400, null);
            } else if ("boom".equals(mode)) {
                throw new ServletException("kaboom", new IllegalStateException("cause"));
            } else if ("temp".equals(mode)) {
                throw new UnavailableException("later", 7);
            } else if ("perm".equals(mode)) {
                throw new UnavailableException("never");
            } else if ("r301".equals(mode)) {
                resp.getWriter().print("kept");
                resp.sendRedirect("/elsewhere", 301, false);
            } else if ("rabs".equals(mode)) {
                resp.sendRedirect("http://other.example/p");
            } else if ("rcommitted".equals(mode)) {
                resp.getWriter().print("x");
                resp.flushBuffer();
                resp.sendRedirect("/late", 302, true);
            } else if ("ostream".equals(mode)) {
                ostream(resp);
            } else if ("writer".equals(mode)) {
                writer(resp);
            } else if ("resetbuf".equals(mode)) {
                BufferResetter.run(resp);
            } else if ("mutators".equals(mode)) {
                mutators(resp, true);
            } else if ("mutators-lite".equals(mode)) {
                mutators(resp, false);
            } else if ("include-mutators".equals(mode)) {
                resp.getWriter().print("before|");
                req.getRequestDispatcher("/resp?mode=mutators").include(req, resp);
                resp.getWriter().print("|after");
            } else if ("forward-params".equals(mode)) {
                req.getRequestDispatcher("/req/y?mode=params&&z=1&").forward(req, resp);
            } else if ("forward-mutators".equals(mode)) {
                req.getRequestDispatcher("/resp?mode=mutators-lite").forward(req, resp);
            } else if ("setbuf".equals(mode)) {
                resp.getWriter().print("a");
                resp.flushBuffer();
                try {
                    resp.setBufferSize(10);
                } catch (IllegalStateException e) {
                    resp.getWriter().print("|ise");
                }
                resp.setLocale(Locale.GERMAN);
                resp.getWriter().print("|" + resp.getLocale());
            } else if ("forward-committed".equals(mode)) {
                resp.getWriter().print("a");
                resp.flushBuffer();
                try {
                    req.getRequestDispatcher("/req/x").forward(req, resp);
                } catch (IllegalStateException e) {
                    resp.getWriter().print("|ise");
                }
            } else if ("dispatcher".equals(mode)) {
                RequestDispatcher d = req.getRequestDispatcher("/req/x?q=1");
                resp.getWriter().print(d.toString());
            } else {
                resp.getWriter().print("default");
            }
        }

        private void ctype(HttpServletResponse resp) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("none=").append(resp.getContentType()).append(';');
            resp.setContentType("text/html");
            resp.setCharacterEncoding(StandardCharsets.UTF_8);
            sb.append(resp.getContentType()).append(';');
            sb.append(resp.getCharacterEncoding()).append(';');
            resp.setContentType("text/plain; charset=ISO-8859-1");
            resp.setCharacterEncoding("UTF-8");
            sb.append(resp.getContentType()).append(';');
            resp.setCharacterEncoding((java.nio.charset.Charset) null);
            sb.append(resp.getCharacterEncoding()).append(';');
            resp.setContentType("text/plain;charset=\"UTF-8\"");
            resp.setCharacterEncoding((String) null);
            sb.append(resp.getCharacterEncoding()).append(';');
            resp.setContentType("text/plain");
            sb.append(resp.getCharacterEncoding()).append(';');
            resp.setLocale(Locale.FRENCH);
            sb.append(resp.getCharacterEncoding()).append(';');
            resp.setLocale(Locale.JAPAN);
            sb.append(resp.getCharacterEncoding());
            resp.getWriter().print(sb);
        }

        private void misc(HttpServletResponse resp) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append(resp.getStatus()).append(';');
            unwrap(resp).setStatus(201, "Created");
            sb.append(resp.getStatus()).append(';');
            resp.setBufferSize(0);
            sb.append(resp.getBufferSize()).append(';');
            resp.setBufferSize(4096);
            Response r = unwrap(resp);
            r.setLongHeader("X-L", 5L);
            r.addLongHeader("X-L", 6L);
            sb.append(r.getContentLengthLong()).append(';');
            resp.setContentLengthLong(9L);
            sb.append(r.getContentLengthLong()).append(';');
            sb.append(resp.getHeaders("X-None")).append(';');
            resp.addHeader("X-Bad", "a\r\nInjected: yes");
            sb.append(resp.containsHeader("X-Bad")).append(';');
            resp.addHeader("Set-Cookie", "a=1");
            resp.addHeader("Set-Cookie", "b=2");
            sb.append(resp.getHeaders("Set-Cookie").size()).append(';');
            resp.addHeader("X-One", "1");
            resp.addHeader("X-One", "2");
            sb.append(resp.getHeaders("X-One").size()).append(';');
            sb.append(resp.getHeaderNames().contains("X-L")).append(';');
            Map<String, String> trailers = new TreeMap<String, String>();
            resp.setTrailerFields(null);
            sb.append(resp.getTrailerFields()).append(';');
            sb.append(resp.getBufferSize()).append(';');
            sb.append(r.toString()).append(';');
            sb.append(r.getPushBuilder() != null).append(';');
            sb.append(trailers.size());
            resp.setContentLengthLong(-1L);
            resp.setHeader("Content-Length", "x");
            resp.setHeader("Content-Length", Integer.toString(sb.length()));
            resp.getWriter().print(sb);
        }

        private void ostream(HttpServletResponse resp) throws IOException {
            ServletOutputStream o = resp.getOutputStream();
            o.print("a");
            resp.flushBuffer();
            ServletOutputStream again = resp.getOutputStream();
            boolean same = again == o;
            try {
                resp.getWriter();
            } catch (IllegalStateException e) {
                o.print("|ise");
            }
            o.print("|same=" + same);
        }

        private void writer(HttpServletResponse resp) throws IOException {
            java.io.PrintWriter w = resp.getWriter();
            w.print("w");
            resp.flushBuffer();
            java.io.PrintWriter again = resp.getWriter();
            boolean same = again == w;
            try {
                resp.getOutputStream();
            } catch (IllegalStateException e) {
                w.print("|ise");
            }
            w.print("|same=" + same);
        }

        private void mutators(HttpServletResponse resp, boolean destructive) throws IOException {
            resp.setStatus(404);
            resp.addCookie(new Cookie("m", "1"));
            resp.addDateHeader("X-D", 0L);
            resp.addHeader("X-M", "1");
            resp.addIntHeader("X-I", 1);
            resp.setDateHeader("X-D2", 0L);
            resp.setHeader("X-M2", "1");
            resp.setIntHeader("X-I2", 1);
            if (destructive) {
                resp.sendRedirect("/nowhere");
                resp.sendRedirect("/nowhere", 301, true);
                resp.sendError(500);
                resp.sendError(500, "no");
            }
            resp.getWriter().print("mutated");
        }
    }

    /** Resets the buffer around a write. */
    static final class BufferResetter {
        static void run(HttpServletResponse resp) throws IOException {
            java.io.PrintWriter w = resp.getWriter();
            w.print("x");
            resp.resetBuffer();
            w.print("y");
            resp.setCharacterEncoding("UTF-8");
            resp.reset();
            resp.getWriter().print("z");
        }
    }

    static Response unwrap(ServletResponse resp) {
        ServletResponse cur = resp;
        while (cur instanceof ServletResponseWrapper) {
            cur = ((ServletResponseWrapper) cur).getResponse();
        }
        return (Response) cur;
    }

    static Request unwrapRequest(ServletRequest req) {
        ServletRequest cur = req;
        while (cur instanceof ServletRequestWrapper) {
            cur = ((ServletRequestWrapper) cur).getRequest();
        }
        return (Request) cur;
    }

    // ===== Request servlet =====

    /** Exercises {@link Request} by mode. */
    public static class ReqServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        @SuppressWarnings({"deprecation", "removal"})
        protected void service(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            String mode = param(req, "mode");
            StringBuilder sb = new StringBuilder();
            if ("cookies".equals(mode)) {
                Cookie[] cs = req.getCookies();
                sb.append(cs == null ? -1 : cs.length);
                if (cs != null) {
                    for (int i = 0; i < cs.length; i++) {
                        sb.append('|').append(cs[i].getName()).append('=').append(cs[i].getValue());
                        sb.append(',').append(cs[i].getPath()).append(',').append(cs[i].getDomain());
                    }
                }
            } else if ("dates".equals(mode)) {
                sb.append(req.getDateHeader("If-Modified-Since")).append(';');
                sb.append(req.getDateHeader("X-Absent")).append(';');
                try {
                    req.getDateHeader("X-Bad-Date");
                } catch (IllegalArgumentException e) {
                    sb.append("bad-date;");
                }
                sb.append(req.getIntHeader("X-Int")).append(';');
                sb.append(req.getIntHeader("X-Absent")).append(';');
                sb.append(unwrapRequest(req).getLongHeader("X-Int")).append(';');
                sb.append(unwrapRequest(req).getLongHeader("X-Absent"));
            } else if ("params".equals(mode)) {
                Map<String, String[]> m = new TreeMap<String, String[]>(req.getParameterMap());
                for (Map.Entry<String, String[]> e : m.entrySet()) {
                    sb.append(e.getKey()).append('=');
                    String[] vs = e.getValue();
                    for (int i = 0; i < vs.length; i++) {
                        sb.append(i == 0 ? "" : "+").append(show(vs[i]));
                    }
                    sb.append(';');
                }
                sb.append(req.getParameterValues("none") == null).append(';');
                sb.append(req.getCharacterEncoding());
            } else if ("locales".equals(mode)) {
                sb.append(req.getLocale()).append(';');
                Enumeration<Locale> ls = req.getLocales();
                while (ls.hasMoreElements()) {
                    sb.append(ls.nextElement()).append(',');
                }
            } else if ("paths".equals(mode)) {
                sb.append(unwrapRequest(req).getRealPath("/hello.txt") != null).append(';');
                sb.append(unwrapRequest(req).getRealPath("rel.txt") != null).append(';');
                sb.append(req.getPathTranslated() != null).append(';');
                sb.append(req.getRequestDispatcher("/resp") != null).append(';');
                sb.append(req.getRequestDispatcher("resp") != null).append(';');
                sb.append(req.getRequestURL()).append(';');
                sb.append(req.getAuthType());
            } else if ("net".equals(mode)) {
                sb.append(req.getRemoteAddr()).append(';').append(req.getRemoteHost()).append(';');
                sb.append(req.getRemotePort()).append(';').append(req.getLocalName()).append(';');
                sb.append(req.getLocalAddr()).append(';').append(req.getLocalPort()).append(';');
                sb.append(req.getServerName()).append(';').append(req.getServerPort()).append(';');
                sb.append(req.getProtocol()).append(';').append(req.getRequestId() != null);
            } else if ("tls".equals(mode)) {
                Object certs = req.getAttribute("jakarta.servlet.request.X509Certificate");
                sb.append(certs == null ? "nocerts" : ((X509Certificate[]) certs).length).append(';');
                sb.append(req.getAttribute("jakarta.servlet.request.cipher_suite")).append(';');
                sb.append(req.getAttribute("jakarta.servlet.request.key_size")).append(';');
                sb.append(req.getAttribute("jakarta.servlet.request.secure_protocol"));
            } else if ("async".equals(mode)) {
                async(req, resp, sb);
            } else if ("async-listeners".equals(mode)) {
                asyncListeners(req, resp);
                return;
            } else if ("async-timeout".equals(mode)) {
                AsyncContext ac = req.startAsync();
                ac.addListener(new RecListener(!"".equals(param(req, "fail"))));
                ((AsyncContextImpl) ac).handleTimeout();
                return;
            } else if ("async-timeout-bare".equals(mode)) {
                AsyncContext ac = req.startAsync();
                ((AsyncContextImpl) ac).handleTimeout();
                return;
            } else if ("async-dispatch".equals(mode)) {
                AsyncContext ac = req.startAsync();
                ac.addListener(new RecListener());
                ac.dispatch(param(req, "to"));
                return;
            } else if ("async-dispatch-self".equals(mode)) {
                AsyncContext ac = req.startAsync();
                ac.dispatch();
                return;
            } else if ("async-dispatch-ctx".equals(mode)) {
                AsyncContext ac = req.startAsync();
                ac.dispatch(getServletContext(), "/sec/none/ctx");
                return;
            } else if ("stash".equals(mode)) {
                stashedRequest = req;
                stashedResponse = resp;
                sb.append("stashed");
            } else if ("async-mismatch".equals(mode)) {
                try {
                    req.startAsync(stashedRequest, resp);
                } catch (IllegalStateException e) {
                    sb.append("req-mismatch;");
                }
                try {
                    req.startAsync(req, stashedResponse);
                } catch (IllegalStateException e) {
                    sb.append("resp-mismatch;");
                }
                HttpServletRequestWrapper wrappedReq = new HttpServletRequestWrapper(req);
                HttpServletResponseWrapper wrappedResp = new HttpServletResponseWrapper(resp);
                AsyncContext ac = req.startAsync(wrappedReq, wrappedResp);
                boolean keptWrappers = ac.getRequest() == wrappedReq && ac.getResponse() == wrappedResp;
                sb.append(ac.hasOriginalRequestAndResponse() ? "plain" : "wrapped").append(';');
                sb.append(keptWrappers).append(';');
                resp.setContentType("text/plain");
                resp.getWriter().print(sb);
                ac.complete();
                try {
                    req.startAsync();
                } catch (IllegalStateException e) {
                    asyncAfterComplete = "completed";
                }
                return;
            } else if ("session".equals(mode)) {
                sb.append(req.getSession(false) == null).append(';');
                try {
                    req.changeSessionId();
                } catch (IllegalStateException e) {
                    sb.append("nosession;");
                }
                HttpSession s = req.getSession(true);
                sb.append(s.isNew()).append(';');
                sb.append(unwrapRequest(req).isRequestedSessionIdFromUrl()).append(';');
                sb.append(req.getSession() == s).append(';');
                sb.append(req.isRequestedSessionIdValid());
            } else if ("login".equals(mode)) {
                login(req, sb);
            } else if ("upgrade".equals(mode)) {
                try {
                    req.upgrade(NoopUpgrade.class);
                } catch (IllegalStateException e) {
                    sb.append("not-websocket");
                }
            } else if ("parts".equals(mode)) {
                try {
                    req.getParts();
                } catch (ServletException e) {
                    sb.append("servlet-exception");
                } catch (IllegalStateException e) {
                    sb.append("ise:").append(e.getMessage() != null);
                }
            } else if ("parts-after-stream".equals(mode)) {
                req.getInputStream();
                try {
                    req.getParts();
                } catch (IllegalStateException e) {
                    sb.append("ise");
                }
            } else if ("parts-none".equals(mode)) {
                PartsReader.parts(req, sb);
            } else if ("streams".equals(mode)) {
                req.getReader();
                try {
                    req.getInputStream();
                } catch (IllegalStateException e) {
                    sb.append("stream-after-reader;");
                }
                try {
                    req.setCharacterEncoding("no-such-charset");
                } catch (java.io.UnsupportedEncodingException e) {
                    sb.append("unsupported;");
                }
                req.setCharacterEncoding("UTF-8");
                req.setCharacterEncoding((String) null);
                sb.append(req.getCharacterEncoding());
            } else if ("streams2".equals(mode)) {
                req.getInputStream();
                try {
                    req.getReader();
                } catch (IllegalStateException e) {
                    sb.append("reader-after-stream;");
                }
                sb.append(req.getContentLength()).append(';').append(req.getContentLengthLong());
            } else if ("push".equals(mode)) {
                sb.append(req.newPushBuilder() != null);
            } else if ("trailers".equals(mode)) {
                sb.append(req.getTrailerFields().size()).append(';').append(req.isTrailerFieldsReady());
            } else if ("attrs".equals(mode)) {
                req.setAttribute("a", "1");
                req.setAttribute("a", "2");
                req.removeAttribute("a");
                req.removeAttribute("absent");
                Enumeration<String> names = req.getAttributeNames();
                int n = 0;
                while (names.hasMoreElements()) {
                    names.nextElement();
                    n++;
                }
                sb.append(n);
            } else {
                sb.append("default");
            }
            resp.setContentType("text/plain");
            resp.getWriter().print(sb);
        }

        private void asyncListeners(HttpServletRequest req, HttpServletResponse resp)
                throws IOException, ServletException {
            ASYNC_LOG.setLength(0);
            AsyncContext ac = req.startAsync();
            ac.addListener(new RecListener());
            ac.addListener(new RecListener(), req, resp);
            ac.setTimeout(0L);
            long none = ac.getTimeout();
            ac.setTimeout(30000L);
            long set = ac.getTimeout();
            AsyncListener created = ac.createListener(RecListener.class);
            String creation = created == null ? "null" : "created";
            try {
                ac.createListener(NoDefaultListener.class);
            } catch (ServletException e) {
                creation += ",uncreatable";
            }
            final StringBuilder ran = new StringBuilder();
            ac.start(new Runnable() {
                @Override
                public void run() {
                    ran.append("ran");
                }
            });
            ac.start(new Runnable() {
                @Override
                public void run() {
                    throw new IllegalStateException("task failed");
                }
            });
            ((AsyncContextImpl) ac).handleTimeout();
            resp.setContentType("text/plain");
            resp.getWriter().print(none + ";" + set + ";" + creation + ";" + ran);
            ac.complete();
            ac.complete();
            try {
                ac.start(new Runnable() {
                    @Override
                    public void run() {
                        ran.append("late");
                    }
                });
            } catch (IllegalStateException e) {
                asyncAfterComplete = "start-after-complete";
            }
            try {
                ac.dispatch("/resp");
            } catch (IllegalStateException e) {
                asyncAfterComplete += ",dispatch-after-complete";
            }
        }

        private void async(HttpServletRequest req, HttpServletResponse resp, StringBuilder sb)
                throws IOException {
            sb.append(req.isAsyncSupported()).append(';');
            sb.append(req.isAsyncStarted()).append(';');
            sb.append(req.getDispatcherType()).append(';');
            try {
                req.getAsyncContext();
            } catch (IllegalStateException e) {
                sb.append("no-context;");
            }
            AsyncContext ac = req.startAsync(req, resp);
            sb.append(req.isAsyncStarted()).append(';');
            sb.append(req.getDispatcherType()).append(';');
            sb.append(ac.hasOriginalRequestAndResponse()).append(';');
            sb.append(req.getAsyncContext() == ac).append(';');
            resp.setContentType("text/plain");
            resp.getWriter().print(sb);
            ac.complete();
            sb.setLength(0);
        }

        private void login(HttpServletRequest req, StringBuilder sb) throws ServletException {
            req.login("alice", "pw");
            sb.append(req.getRemoteUser()).append(';');
            sb.append(req.isUserInRole("admin")).append(';');
            sb.append(req.isUserInRole("other")).append(';');
            sb.append(req.getAuthType()).append(';');
            try {
                req.login("alice", "pw");
            } catch (ServletException e) {
                sb.append("again-rejected;");
            }
            req.logout();
            sb.append(req.getUserPrincipal()).append(';');
            try {
                req.login("alice", "wrong");
            } catch (ServletException e) {
                sb.append("bad-password;");
            }
            sb.append(req.getAuthType());
        }
    }

    /** Reads parts of a request. */
    static final class PartsReader {
        static void parts(HttpServletRequest req, StringBuilder sb) throws IOException, ServletException {
            try {
                java.util.Collection<Part> parts = req.getParts();
                sb.append(parts.size()).append(';');
                java.util.Collection<Part> again = req.getParts();
                sb.append(parts == again).append(';');
                Part p = req.getPart("nothing");
                sb.append(p == null);
            } catch (IllegalStateException e) {
                sb.append("too-large");
            }
        }
    }

    /** Upgrade handler used only for its type. */
    public static class NoopUpgrade implements HttpUpgradeHandler {
        @Override public void init(WebConnection wc) { }
        @Override public void destroy() { }
    }

    /** Not async-capable. */
    public static class NoAsyncServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String out;
            try {
                req.startAsync();
                out = "started";
            } catch (IllegalStateException e) {
                out = "ise:" + req.isAsyncSupported();
            }
            resp.getWriter().print(out);
        }
    }

    /** Reports the authenticated user. */
    public static class WhoServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("who=" + req.getRemoteUser() + ",path=" + req.getRequestURI()
                    + ",auth=" + req.getAuthType());
        }
    }

    /** Throws a permanent or temporary unavailable exception. */
    public static class UnavailableFilter implements Filter {
        private boolean permanent;

        @Override
        public void init(jakarta.servlet.FilterConfig config) {
            permanent = "true".equals(config.getInitParameter("permanent"));
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (permanent) {
                throw new UnavailableException("gone");
            }
            throw new UnavailableException("busy", 3);
        }
    }

    /** Records asynchronous events; optionally fails on timeout. */
    public static class RecListener implements AsyncListener {
        final boolean failOnTimeout;

        public RecListener() {
            this(false);
        }

        RecListener(boolean failOnTimeout) {
            this.failOnTimeout = failOnTimeout;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            ASYNC_LOG.append("complete;");
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            ASYNC_LOG.append("timeout;");
            if (failOnTimeout) {
                throw new IOException("cannot handle");
            }
        }

        @Override
        public void onError(AsyncEvent event) {
            ASYNC_LOG.append("error:").append(event.getThrowable().getMessage()).append(';');
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            ASYNC_LOG.append("start;");
        }
    }

    /** A listener type that cannot be instantiated reflectively. */
    public static class NoDefaultListener extends RecListener {
        public NoDefaultListener(String unused) {
            super(false);
        }
    }

    /** Runs tasks inline so asynchronous dispatch is deterministic. */
    private static final class InlineContainer extends TestContainer {
        private final ThreadPoolExecutor inline = new ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void execute(Runnable task) {
                task.run();
            }
        };

        @Override
        public ThreadPoolExecutor getWorkerThreadPool() {
            return inline;
        }
    }

    // ===== Fixtures =====

    private static final class CertRealm implements Realm {
        String grant;

        @Override public Realm forSelectorLoop(org.bluezoo.gumdrop.SelectorLoop loop) { return this; }
        @Override public java.util.Set<org.bluezoo.gumdrop.auth.SaslMechanism> getSupportedSASLMechanisms() {
            return Collections.<org.bluezoo.gumdrop.auth.SaslMechanism>emptySet();
        }
        @Override public boolean passwordMatch(String username, String password) { return false; }
        @Override public String getDigestHA1(String username, String realmName) { return null; }
        @Override public String getPassword(String username) { return null; }
        @Override public boolean isUserInRole(String username, String role) { return false; }
        @Override public CertificateAuthenticationResult authenticateCertificate(X509Certificate c) {
            if (grant == null) {
                return null;
            }
            if ("deny".equals(grant)) {
                return CertificateAuthenticationResult.failure();
            }
            return CertificateAuthenticationResult.success(grant);
        }
    }

    /** A certificate that is not X.509. */
    private static final class OddCertificate extends Certificate {
        private static final long serialVersionUID = 1L;

        OddCertificate() {
            super("odd");
        }

        @Override public byte[] getEncoded() { return new byte[0]; }
        @Override public void verify(PublicKey key) { }
        @Override public void verify(PublicKey key, String sigProvider) { }
        @Override public String toString() { return "odd"; }
        @Override public PublicKey getPublicKey() { return null; }
    }

    /** State with configurable addresses and TLS details. */
    private static final class ConfigState extends StubState {
        SocketAddress remote;
        SocketAddress local;
        Certificate[] certs;

        @Override public SocketAddress getRemoteAddress() {
            return remote;
        }

        @Override public SocketAddress getLocalAddress() {
            return local;
        }

        @Override public SecurityInfo getSecurityInfo() {
            final Certificate[] c = certs;
            return new SecurityInfo() {
                @Override public String getProtocol() { return "TLSv1.3"; }
                @Override public String getCipherSuite() { return "TLS_AES_128_GCM_SHA256"; }
                @Override public int getKeySize() { return 128; }
                @Override public Certificate[] getPeerCertificates() { return c; }
                @Override public boolean isSessionResumed() { return false; }
                @Override public long getHandshakeDurationMs() { return 0L; }
                @Override public String getApplicationProtocol() { return null; }
                @Override public Certificate[] getLocalCertificates() { return null; }
            };
        }
    }

    public static org.bluezoo.gumdrop.servlet.MemoryFolder tmp = new MemoryFolder();

    private static TestContainer container;
    private static Context more;
    private static Context form;
    private static Context cert;
    private static CertRealm certRealm;
    private static String savedFactory;

    private static final String NS = "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">";

    private static String cls(String name) {
        return ServletEndToEndMoreTest.class.getName() + "$" + name;
    }

    private static String servlet(String name, String cls, String pattern, String extra) {
        return "<servlet><servlet-name>" + name + "</servlet-name><servlet-class>" + cls(cls)
                + "</servlet-class><async-supported>true</async-supported>" + extra + "</servlet>"
                + "<servlet-mapping><servlet-name>" + name + "</servlet-name><url-pattern>" + pattern
                + "</url-pattern></servlet-mapping>";
    }

    private static String constraint(String pattern, String methods, String auth, String transport) {
        StringBuilder sb = new StringBuilder("<security-constraint><web-resource-collection>"
                + "<web-resource-name>c</web-resource-name><url-pattern>" + pattern + "</url-pattern>");
        if (methods != null) {
            sb.append("<http-method>").append(methods).append("</http-method>");
        }
        sb.append("</web-resource-collection>");
        if (auth != null) {
            sb.append(auth);
        }
        if (transport != null) {
            sb.append("<user-data-constraint><transport-guarantee>").append(transport)
                    .append("</transport-guarantee></user-data-constraint>");
        }
        sb.append("</security-constraint>");
        return sb.toString();
    }

    private static String moreXml() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append(NS);
        sb.append("<context-param><param-name>org.bluezoo.gumdrop.show-stack-traces</param-name>"
                + "<param-value>true</param-value></context-param>");
        sb.append(servlet("resp", "RespServlet", "/resp", ""));
        sb.append(servlet("req", "ReqServlet", "/req/*", ""));
        sb.append(servlet("nomp", "ReqServlet", "/nomp", ""));
        sb.append(servlet("mp", "ReqServlet", "/mp", "<multipart-config><max-request-size>100"
                + "</max-request-size></multipart-config>"));
        sb.append(servlet("who", "WhoServlet", "/sec/*", ""));
        sb.append("<servlet><servlet-name>noasync</servlet-name><servlet-class>" + cls("NoAsyncServlet")
                + "</servlet-class></servlet><servlet-mapping><servlet-name>noasync</servlet-name>"
                + "<url-pattern>/noasync</url-pattern></servlet-mapping>");
        sb.append(servlet("perm", "RespServlet", "/perm", ""));
        sb.append(servlet("fwho", "WhoServlet", "/fu/*", ""));
        sb.append("<filter><filter-name>fperm</filter-name><filter-class>" + cls("UnavailableFilter")
                + "</filter-class><init-param><param-name>permanent</param-name><param-value>true"
                + "</param-value></init-param></filter>");
        sb.append("<filter><filter-name>ftemp</filter-name><filter-class>" + cls("UnavailableFilter")
                + "</filter-class></filter>");
        sb.append("<filter-mapping><filter-name>fperm</filter-name><url-pattern>/fu/perm/*</url-pattern>"
                + "</filter-mapping>");
        sb.append("<filter-mapping><filter-name>ftemp</filter-name><url-pattern>/fu/temp/*</url-pattern>"
                + "</filter-mapping>");
        sb.append("<locale-encoding-mapping-list><locale-encoding-mapping><locale>fr</locale>"
                + "<encoding>ISO-8859-2</encoding></locale-encoding-mapping></locale-encoding-mapping-list>");
        String adminRole = "<auth-constraint><role-name>admin</role-name></auth-constraint>";
        sb.append(constraint("/sec/admin/*", null, adminRole, null));
        sb.append(constraint("/sec/any/*", null, "<auth-constraint><role-name>*</role-name></auth-constraint>",
                null));
        sb.append(constraint("/sec/deny/*", null, "<auth-constraint/>", null));
        sb.append(constraint("/sec/tls/*", null, null, "CONFIDENTIAL"));
        sb.append(constraint("/sec/post/*", "POST", adminRole, null));
        sb.append(constraint("/sec/none/*", null, null, null));
        sb.append("<login-config><auth-method>BASIC</auth-method><realm-name>morerealm</realm-name>"
                + "</login-config><security-role><role-name>admin</role-name></security-role>");
        sb.append("</web-app>");
        return sb.toString();
    }

    private static String formXml() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append(NS);
        sb.append(servlet("who", "WhoServlet", "/private/*", ""));
        sb.append(constraint("/private/*", null,
                "<auth-constraint><role-name>admin</role-name></auth-constraint>", null));
        sb.append("<login-config><auth-method>FORM</auth-method><realm-name>morerealm</realm-name>"
                + "<form-login-config><form-login-page>/login.html</form-login-page>"
                + "<form-error-page>/error.html</form-error-page></form-login-config></login-config>"
                + "<security-role><role-name>admin</role-name></security-role></web-app>");
        return sb.toString();
    }

    private static String certXml() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append(NS);
        sb.append(servlet("who", "WhoServlet", "/secret/*", ""));
        sb.append(constraint("/secret/*", null,
                "<auth-constraint><role-name>*</role-name></auth-constraint>", null));
        sb.append("<login-config><auth-method>CLIENT-CERT</auth-method><realm-name>certrealm</realm-name>"
                + "</login-config><security-role><role-name>admin</role-name></security-role></web-app>");
        return sb.toString();
    }

    private static Context load(Container shared, String ctxPath, String folder, String xml)
            throws Exception {
        Path root = tmp.newFolder(folder);
        MemoryFolder.write(root, "WEB-INF/web.xml", xml);
        MemoryFolder.write(root, "hello.txt", "hello");
        MemoryFolder.write(root, "sub/inner.txt", "inner");
        MemoryFolder.write(root, "META-INF/secret.txt", "secret");
        Context c = new Context(shared, ctxPath, root);
        shared.addContext(c);
        container.addContext(c);
        c.load();
        c.init();
        return c;
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
        savedFactory = System.getProperty("java.naming.factory.initial");
        System.setProperty("java.naming.factory.initial",
                "org.bluezoo.gumdrop.servlet.jndi.ServletInitialContextFactory");
        Container shared = SharedContainer.get();
        certRealm = new CertRealm();
        shared.addRealm("morerealm", new TestRealm());
        shared.addRealm("certrealm", certRealm);
        container = new InlineContainer();
        container.addRealm("morerealm", new TestRealm());
        container.addRealm("certrealm", certRealm);
        more = load(shared, "/more", "more", moreXml());
        form = load(shared, "/form", "form", formXml());
        cert = load(shared, "/cert", "cert", certXml());
    }

    @AfterClass
    public static void tearDownClass() {
        more.destroy();
        form.destroy();
        cert.destroy();
        if (savedFactory == null) {
            System.clearProperty("java.naming.factory.initial");
        } else {
            System.setProperty("java.naming.factory.initial", savedFactory);
        }
    }

    private static Result send(StubState state, String method, String target, byte[] body,
            String... headerPairs) throws Exception {
        ServletHandler handler = new ServletHandler(container, state, 8192);
        ServletHeaders h = new ServletHeaders();
        h.add(":method", method);
        h.add(":path", target);
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            h.add(headerPairs[i], headerPairs[i + 1]);
        }
        MessageEvents.headers(handler, h);
        if (body != null) {
            handler.bodyContent(ByteBuffer.wrap(body));
        }
        handler.endMessage();
        container.runPending();
        return state.result;
    }

    private static Result get(String target, String... headerPairs) throws Exception {
        return send(new StubState(), "GET", target, null, headerPairs);
    }

    private static void assertHas(String text, String needle) {
        boolean found = text.contains(needle);
        assertTrue("expected [" + needle + "] in [" + text + "]", found);
    }

    private static String basic(String user, String pw) {
        String raw = user + ":" + pw;
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(bytes);
    }

    // ===== Response =====

    @Test
    public void testContentTypeAndCharacterEncodingRules() throws Exception {
        Result r = get("/more/resp?mode=ctype");
        assertEquals(200, r.status);
        String t = r.text();
        assertTrue(t, t.startsWith("none=null;"));
        assertHas(t, ";text/plain; charset=UTF-8;");
        assertHas(t, ";ISO-8859-2;");
        assertTrue(t, t.endsWith("ISO-8859-1"));
    }

    @Test
    public void testMiscellaneousResponseAccessors() throws Exception {
        Result r = get("/more/resp?mode=misc");
        assertEquals(201, r.status);
        String t = r.text();
        assertTrue(t, t.startsWith("200;201;1;-1;9;null;false;2;2;true;null;4096;201;"));
        assertHas(t, ";true;0");
    }

    @Test
    public void testSessionUrlEncoding() throws Exception {
        Result r = get("/more/resp?mode=encode");
        String t = r.text();
        assertHas(t, "/a?jsessionid=");
        assertHas(t, "/a?x=1&jsessionid=");
        assertHas(t, "/a?jsessionid=");
        assertHas(t, "&jsessionid=");
        assertHas(t, "&y=2|");
        assertFalse(t, t.contains("old"));
        assertTrue(t, t.endsWith("|true"));
    }

    @Test
    public void testDefaultErrorPages() throws Exception {
        Result r404 = get("/more/resp?mode=e404");
        assertEquals(404, r404.status);
        String t = r404.text();
        assertHas(t, "<h1>404 ");
        assertHas(t, "&lt;b&gt;missing&lt;/b&gt;");
        assertFalse(t, t.contains("<b>missing"));
    }

    @Test
    public void testDefaultErrorPageVariants() throws Exception {
        Result nomsg = get("/more/resp?mode=e404nomsg");
        assertEquals(404, nomsg.status);
        Result odd = get("/more/resp?mode=e599");
        assertHas(odd.text(), "<h1>599 ");
        Result notModified = get("/more/resp?mode=e304");
        assertEquals(304, notModified.status);
        assertEquals(0, notModified.body.size());
        Result bad = get("/more/resp?mode=e400");
        assertEquals(400, bad.status);
    }

    @Test
    public void testContentTypeCarriesCharacterEncodingOnTheWire() throws Exception {
        Result r = get("/more/resp?mode=ctype-wire");
        String ct = r.header("content-type");
        assertNotNull(ct);
        assertHas(ct.toLowerCase(), "charset=utf-8");
    }

    @Test
    public void testServletExceptionShowsStackTrace() throws Exception {
        Result r = get("/more/resp?mode=boom");
        assertEquals(500, r.status);
        String t = r.text();
        assertHas(t, "class='stack-trace'");
        assertHas(t, "kaboom");
        assertHas(t, "class='servlet-name'");
    }

    @Test
    public void testUnavailableServlet() throws Exception {
        Result temp = get("/more/resp?mode=temp");
        assertEquals(503, temp.status);
        assertEquals("7", temp.header("retry-after"));
        Result perm = get("/more/perm?mode=perm");
        assertEquals(500, perm.status);
    }

    @Test
    public void testRedirectVariants() throws Exception {
        Result r301 = get("/more/resp?mode=r301");
        assertEquals(301, r301.status);
        assertHas(r301.header("location"), "/elsewhere");
        Result abs = get("/more/resp?mode=rabs");
        assertEquals("http://other.example/p", abs.header("location"));
        Result late = get("/more/resp?mode=rcommitted");
        assertEquals(200, late.status);
    }

    @Test
    public void testStreamAndWriterExclusivityOnceCommitted() throws Exception {
        Result o = get("/more/resp?mode=ostream");
        assertEquals("a|ise|same=true", o.text());
        Result w = get("/more/resp?mode=writer");
        assertEquals("w|ise|same=true", w.text());
        Result sb = get("/more/resp?mode=setbuf");
        assertTrue(sb.text(), sb.text().startsWith("a|ise|"));
    }

    @Test
    public void testResetBufferAndReset() throws Exception {
        Result r = get("/more/resp?mode=resetbuf");
        assertEquals("z", r.text());
    }

    @Test
    public void testIncludedResponseIgnoresHeaderMutators() throws Exception {
        Result inc = get("/more/resp?mode=include-mutators");
        assertEquals(200, inc.status);
        assertEquals("before|mutated|after", inc.text());
        assertNull(inc.header("x-m"));
        Result fwd = get("/more/resp?mode=forward-mutators");
        assertEquals(404, fwd.status);
    }

    @Test
    public void testForwardedQueryParametersSkipEmptySegments() throws Exception {
        Result r = get("/more/resp?mode=forward-params&x=2");
        String t = r.text();
        assertHas(t, "z=1;");
        assertHas(t, "mode=params+forward-params;");
    }

    @Test
    public void testForwardAfterCommitIsRejected() throws Exception {
        Result r = get("/more/resp?mode=forward-committed");
        assertEquals("a|ise", r.text());
    }

    @Test
    public void testDispatcherToString() throws Exception {
        Result r = get("/more/resp?mode=dispatcher");
        String t = r.text();
        assertHas(t, "ContextRequestDispatcher[servletPath=/req");
        assertHas(t, "queryString=q=1");
    }

    // ===== Request =====

    @Test
    public void testCookieParsing() throws Exception {
        Result r = get("/more/req/x?mode=cookies",
                "cookie", "$Version=1,a=1;$Path=/p;$Domain=d.org;$Other=z,b=2,,flag, c=3");
        assertEquals(200, r.status);
        String t = r.text();
        assertTrue(t, t.startsWith("4|"));
        assertHas(t, "|a=1,/p,d.org");
        assertHas(t, "|b=2,null,null");
        assertHas(t, "|flag=,null,null");
        assertHas(t, "|c=3,null,null");
        Result none = get("/more/req/x?mode=cookies");
        assertEquals("-1", none.text());
    }

    @Test
    public void testDateAndIntHeaders() throws Exception {
        Result r = get("/more/req/x?mode=dates",
                "if-modified-since", "Thu, 01 Jan 1970 00:00:10 GMT",
                "x-bad-date", "not a date", "x-int", "42");
        String t = r.text();
        assertTrue(t, t.startsWith("10000;-1;bad-date;42;-1;42;-1"));
    }

    @Test
    public void testParameterDecoding() throws Exception {
        byte[] body = "a=%C3%A9&b=1&b=2&&=skipped&c=3&flag".getBytes(StandardCharsets.US_ASCII);
        Result r = send(new StubState(), "POST", "/more/req/x?mode=params&q=%C3%A9&&r=9&", body,
                "content-type", "application/x-www-form-urlencoded");
        String t = r.text();
        assertHas(t, "a=\\u00c3\\u00a9;");
        assertHas(t, "b=1+2;");
        assertHas(t, "c=3;");
        assertHas(t, "flag=null;");
        assertHas(t, "q=\\u00e9;");
        assertHas(t, "r=9;");
        assertTrue(t, t.endsWith("true;US-ASCII"));
        Result latin = send(new StubState(), "POST", "/more/req/x?mode=params", "a=%E9".getBytes(
                StandardCharsets.US_ASCII), "content-type", "application/x-www-form-urlencoded; charset=\"ISO-8859-1\"");
        assertHas(latin.text(), "a=\\u00e9;");
        assertTrue(latin.text(), latin.text().endsWith("ISO-8859-1"));
    }

    @Test
    public void testLocaleNegotiation() throws Exception {
        Result r = get("/more/req/x?mode=locales", "accept-language", "de;q=0.4, fr;q=bad, en-GB;q=0.9, es");
        String t = r.text();
        assertTrue(t, t.startsWith("es;"));
        assertHas(t, "en_GB");
        assertHas(t, "de");
        Result none = get("/more/req/x?mode=locales");
        assertTrue(none.text(), none.text().startsWith(Locale.getDefault().toString() + ";"));
        Result empty = get("/more/req/x?mode=locales", "accept-language", "fr;q=x");
        assertTrue(empty.text(), empty.text().startsWith(Locale.getDefault().toString() + ";"));
    }

    @Test
    public void testPathsAndDispatchers() throws Exception {
        Result r = get("/more/req/x?mode=paths");
        String t = r.text();
        assertTrue(t, t.startsWith("false;false;false;true;true;"));
        assertTrue(t, t.endsWith(";null"));
    }

    @Test
    public void testNetworkAccessorsWithResolvedAddresses() throws Exception {
        Result r = get("/more/req/x?mode=net");
        String t = r.text();
        assertHas(t, "127.0.0.1;");
        assertHas(t, ";54321;");
        assertHas(t, ";8080;");
        assertHas(t, "HTTP/1.1");
    }

    @Test
    public void testNetworkAccessorsWithoutInetAddresses() throws Exception {
        ConfigState state = new ConfigState();
        state.remote = new SocketAddress() {
            private static final long serialVersionUID = 1L;
        };
        state.local = null;
        Result r = send(state, "GET", "/more/req/x?mode=net", null);
        String t = r.text();
        assertTrue(t, t.startsWith("null;null;-1;null;null;-1;null;-1;"));
    }

    @Test
    public void testTlsAttributes() throws Exception {
        ConfigState state = new ConfigState();
        state.secure = true;
        state.remote = new InetSocketAddress("127.0.0.1", 5000);
        state.local = new InetSocketAddress("127.0.0.1", 8443);
        X509Certificate c = TestCertificates.ec256().getCertificate();
        state.certs = new Certificate[] {c, new OddCertificate()};
        Result r = send(state, "GET", "/more/req/x?mode=tls", null);
        assertEquals("1;TLS_AES_128_GCM_SHA256;128;TLSv1.3", r.text());
        ConfigState noCerts = new ConfigState();
        noCerts.secure = true;
        noCerts.remote = new InetSocketAddress("127.0.0.1", 5000);
        noCerts.local = new InetSocketAddress("127.0.0.1", 8443);
        Result n = send(noCerts, "GET", "/more/req/x?mode=tls", null);
        assertTrue(n.text(), n.text().startsWith("nocerts;"));
    }

    @Test
    public void testAsyncApiAndMisuse() throws Exception {
        Result r = get("/more/req/x?mode=async");
        assertTrue(r.done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("true;false;REQUEST;no-context;true;ASYNC;false;true;", r.text());
        Result na = get("/more/noasync");
        assertEquals("ise:false", na.text());
    }

    @Test
    public void testAsyncRequestAndResponseMustMatch() throws Exception {
        Result stash = get("/more/req/x?mode=stash");
        assertEquals("stashed", stash.text());
        Result r = get("/more/req/x?mode=async-mismatch");
        assertTrue(r.done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("req-mismatch;resp-mismatch;wrapped;true;", r.text());
        assertEquals("completed", asyncAfterComplete);
    }

    @Test
    public void testSessionAccessors() throws Exception {
        Result r = get("/more/req/x?mode=session");
        assertEquals("true;nosession;true;false;true;true", r.text());
    }

    @Test
    public void testProgrammaticLogin() throws Exception {
        Result r = get("/more/req/x?mode=login");
        assertEquals("alice;true;false;BASIC;again-rejected;null;bad-password;null", r.text());
    }

    @Test
    public void testUpgradeRequiresWebSocketHandshake() throws Exception {
        Result r = get("/more/req/x?mode=upgrade");
        assertEquals("not-websocket", r.text());
    }

    @Test
    public void testMultipartPreconditions() throws Exception {
        Result none = get("/more/mp?mode=parts");
        assertEquals("servlet-exception", none.text());
        Result plain = send(new StubState(), "POST", "/more/mp?mode=parts", null, "content-type", "text/plain");
        assertEquals("servlet-exception", plain.text());
        Result nob = send(new StubState(), "POST", "/more/mp?mode=parts", null,
                "content-type", "multipart/form-data");
        assertEquals("servlet-exception", nob.text());
        Result noconfig = send(new StubState(), "POST", "/more/nomp?mode=parts", null,
                "content-type", "multipart/form-data; boundary=xyz");
        assertEquals("ise:true", noconfig.text());
        Result afterStream = send(new StubState(), "POST", "/more/mp?mode=parts-after-stream", null,
                "content-type", "multipart/form-data; boundary=xyz");
        assertEquals("ise", afterStream.text());
        byte[] body = "--xyz--\r\n".getBytes(StandardCharsets.US_ASCII);
        Result empty = send(new StubState(), "POST", "/more/mp?mode=parts-none", body,
                "content-type", "multipart/form-data; boundary=xyz", "content-length", "9");
        assertEquals("0;true;true", empty.text());
        Result big = send(new StubState(), "POST", "/more/mp?mode=parts-none", body,
                "content-type", "multipart/form-data; boundary=xyz", "content-length", "1000");
        assertEquals("too-large", big.text());
    }

    @Test
    public void testInputStreamAndReaderAreExclusive() throws Exception {
        Result a = get("/more/req/x?mode=streams");
        assertEquals("stream-after-reader;unsupported;null", a.text());
        Result b = send(new StubState(), "POST", "/more/req/x?mode=streams2", null, "content-length", "7");
        assertEquals("reader-after-stream;7;7", b.text());
    }

    @Test
    public void testPushBuilderAndTrailers() throws Exception {
        Result p = get("/more/req/x?mode=push");
        assertEquals("true", p.text());
        Result t = get("/more/req/x?mode=trailers");
        assertEquals("0;true", t.text());
    }

    @Test
    public void testAttributesWithoutListeners() throws Exception {
        Result r = get("/more/req/x?mode=attrs");
        assertEquals("0", r.text());
    }

    // ===== Security constraints =====

    @Test
    public void testRoleConstraintChallengeAndAuthorization() throws Exception {
        Result anon = get("/more/sec/admin/x");
        assertEquals(401, anon.status);
        Result ok = get("/more/sec/admin/x", "authorization", basic("alice", "pw"));
        assertEquals(200, ok.status);
        assertHas(ok.text(), "who=alice");
        Result anyone = get("/more/sec/any/x", "authorization", basic("alice", "pw"));
        assertEquals(200, anyone.status);
    }

    @Test
    public void testDenyAllConstraint() throws Exception {
        Result r = get("/more/sec/deny/x", "authorization", basic("alice", "pw"));
        assertEquals(403, r.status);
    }

    @Test
    public void testMethodSpecificConstraint() throws Exception {
        Result getAllowed = get("/more/sec/post/x");
        assertEquals(200, getAllowed.status);
        Result postDenied = send(new StubState(), "POST", "/more/sec/post/x", null);
        assertEquals(401, postDenied.status);
        Result none = get("/more/sec/none/x");
        assertEquals(200, none.status);
    }

    @Test
    public void testTransportGuaranteeWithoutSecureHost() throws Exception {
        String saved = more.secureHost;
        more.secureHost = null;
        try {
            Result r = get("/more/sec/tls/x");
            assertEquals(500, r.status);
        } finally {
            more.secureHost = saved;
        }
    }

    @Test
    public void testTransportGuaranteeRedirectsToSecureHost() throws Exception {
        String saved = more.secureHost;
        more.secureHost = "secure.example:8443";
        try {
            Result r = get("/more/sec/tls/x?q=1");
            assertEquals(302, r.status);
            assertEquals("https://secure.example:8443/more/sec/tls/x?q=1", r.header("location"));
            Result plain = get("/more/sec/tls/x");
            assertEquals("https://secure.example:8443/more/sec/tls/x", plain.header("location"));
            StubState secure = new StubState();
            secure.secure = true;
            Result ok = send(secure, "GET", "/more/sec/tls/x", null);
            assertEquals(200, ok.status);
        } finally {
            more.secureHost = saved;
        }
    }

    // ===== Filters failing =====

    @Test
    public void testFilterUnavailableTemporarily() throws Exception {
        Result r = get("/more/fu/temp/x");
        assertEquals(503, r.status);
        assertEquals("3", r.header("retry-after"));
    }

    @Test
    public void testFilterUnavailablePermanently() throws Exception {
        Result r = get("/more/fu/perm/x");
        assertEquals(500, r.status);
    }

    // ===== FORM login =====

    @Test
    public void testFormLogin() throws Exception {
        Result login = get("/form/private/a");
        assertEquals(302, login.status);
        assertHas(login.header("location"), "/form/login.html");
        Result wrong = get("/form/private/a?j_username=alice&j_password=wrong");
        assertEquals(302, wrong.status);
        assertHas(wrong.header("location"), "/form/error.html");
        Result ok = get("/form/private/a?j_username=alice&j_password=pw");
        assertEquals(200, ok.status);
        assertHas(ok.text(), "who=alice");
    }

    // ===== CLIENT-CERT login =====

    @Test
    public void testClientCertWithoutCertificates() throws Exception {
        certRealm.grant = null;
        ConfigState plain = new ConfigState();
        plain.remote = new InetSocketAddress("127.0.0.1", 5000);
        plain.local = new InetSocketAddress("127.0.0.1", 8443);
        Result none = send(plain, "GET", "/cert/secret/a", null);
        assertEquals(403, none.status);
        ConfigState odd = new ConfigState();
        odd.secure = true;
        odd.remote = plain.remote;
        odd.local = plain.local;
        odd.certs = new Certificate[] {new OddCertificate()};
        Result nonX509 = send(odd, "GET", "/cert/secret/a", null);
        assertEquals(403, nonX509.status);
        ConfigState empty = new ConfigState();
        empty.secure = true;
        empty.remote = plain.remote;
        empty.local = plain.local;
        empty.certs = new Certificate[0];
        Result emptyChain = send(empty, "GET", "/cert/secret/a", null);
        assertEquals(403, emptyChain.status);
    }

    @Test
    public void testClientCertAuthenticatedByRealmOrSubject() throws Exception {
        X509Certificate c = TestCertificates.ec256().getCertificate();
        ConfigState state = new ConfigState();
        state.secure = true;
        state.remote = new InetSocketAddress("127.0.0.1", 5000);
        state.local = new InetSocketAddress("127.0.0.1", 8443);
        state.certs = new Certificate[] {new OddCertificate(), c};
        certRealm.grant = "carol";
        Result granted = send(state, "GET", "/cert/secret/a", null);
        assertEquals(200, granted.status);
        assertHas(granted.text(), "who=carol");
        assertHas(granted.text(), "auth=CLIENT_CERT");
        certRealm.grant = "deny";
        Result fallback = send(state, "GET", "/cert/secret/a", null);
        assertEquals(200, fallback.status);
        assertHas(fallback.text(), "who=");
        certRealm.grant = null;
        Result unsupported = send(state, "GET", "/cert/secret/a", null);
        assertEquals(200, unsupported.status);
    }

    // ===== Default servlet =====

    @Test
    public void testDefaultServletOptions() throws Exception {
        Result ok = send(new StubState(), "OPTIONS", "/more/hello.txt", null);
        assertEquals(204, ok.status);
        assertEquals("OPTIONS, GET, HEAD", ok.header("allow"));
        Result missing = send(new StubState(), "OPTIONS", "/more/absent.txt", null);
        assertEquals(404, missing.status);
        Result protectedPath = send(new StubState(), "OPTIONS", "/more/WEB-INF/web.xml", null);
        assertEquals(404, protectedPath.status);
    }

    @Test
    public void testDefaultServletHead() throws Exception {
        Result ok = send(new StubState(), "HEAD", "/more/hello.txt", null);
        assertEquals(200, ok.status);
        assertEquals("5", ok.header("content-length"));
        assertNotNull(ok.header("etag"));
        Result missing = send(new StubState(), "HEAD", "/more/absent.txt", null);
        assertEquals(404, missing.status);
        Result meta = send(new StubState(), "HEAD", "/more/META-INF/secret.txt", null);
        assertEquals(404, meta.status);
        Result conditional = send(new StubState(), "HEAD", "/more/hello.txt", null,
                "if-none-match", ok.header("etag"));
        assertEquals(304, conditional.status);
    }

    @Test
    public void testDefaultServletConditionalGet() throws Exception {
        Result first = get("/more/hello.txt");
        assertEquals("hello", first.text());
        String etag = first.header("etag");
        assertNotNull(etag);
        Result notModified = get("/more/hello.txt", "if-none-match", etag);
        assertEquals(304, notModified.status);
        assertEquals(0, notModified.body.size());
        Result stale = get("/more/hello.txt", "if-none-match", "\"other\"");
        assertEquals(200, stale.status);
        Result garbage = get("/more/hello.txt", "if-modified-since", "not a date");
        assertEquals(200, garbage.status);
        Result future = get("/more/hello.txt", "if-modified-since", first.header("last-modified"));
        assertEquals(304, future.status);
    }

    @Test
    public void testDefaultServletCollectionRedirectAndProtection() throws Exception {
        Result redirect = get("/more/sub");
        assertEquals(301, redirect.status);
        assertEquals("/more/sub/", redirect.header("location"));
        Result withQuery = get("/more/sub?a=1");
        assertEquals("/more/sub/?a=1", withQuery.header("location"));
        Result inner = get("/more/sub/inner.txt");
        assertEquals("inner", inner.text());
        Result meta = get("/more/META-INF/secret.txt");
        assertEquals(404, meta.status);
        Result dots = get("/more/sub/../hello.txt");
        assertTrue(dots.status == 200 || dots.status == 404);
        Result escape = get("/more/sub/../../WEB-INF/web.xml");
        assertEquals(404, escape.status);
    }

    // ===== Asynchronous context =====

    @Test
    public void testAsyncContextListenersTimeoutsAndTasks() throws Exception {
        Result r = get("/more/req/x?mode=async-listeners");
        assertTrue(r.done.await(10, TimeUnit.SECONDS));
        assertEquals("0;30000;created,uncreatable;ran", r.text());
        assertEquals("start-after-complete,dispatch-after-complete", asyncAfterComplete);
        String log = ASYNC_LOG.toString();
        assertHas(log, "error:task failed;error:task failed;");
        assertHas(log, "timeout;timeout;");
        assertHas(log, "complete;complete;");
    }

    @Test
    public void testAsyncTimeoutHandledByListener() throws Exception {
        ASYNC_LOG.setLength(0);
        Result r = get("/more/req/x?mode=async-timeout");
        assertEquals("timeout;", ASYNC_LOG.toString());
        assertFalse(r.complete);
    }

    @Test
    public void testAsyncTimeoutWithoutHandlerSendsServerError() throws Exception {
        ASYNC_LOG.setLength(0);
        Result bare = get("/more/req/x?mode=async-timeout-bare");
        assertEquals(500, bare.status);
        assertTrue(bare.complete);
        Result failing = get("/more/req/x?mode=async-timeout&fail=1");
        assertEquals(500, failing.status);
        assertTrue(failing.complete);
    }

    @Test
    public void testAsyncDispatchToPathAndContext() throws Exception {
        ASYNC_LOG.setLength(0);
        Result path = get("/more/req/x?mode=async-dispatch&to=/sec/none/y");
        assertTrue(path.text(), path.text().startsWith("who=null"));
        assertHas(ASYNC_LOG.toString(), "start;");
        Result ctx = get("/more/req/x?mode=async-dispatch-ctx");
        assertTrue(ctx.text(), ctx.text().startsWith("who=null"));
        Result self = get("/more/req/x?mode=async-dispatch-self");
        assertTrue(self.complete);
    }
}
